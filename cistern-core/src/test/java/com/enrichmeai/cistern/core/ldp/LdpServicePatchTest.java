package com.enrichmeai.cistern.core.ldp;

import com.enrichmeai.cistern.core.CisternException;
import com.enrichmeai.cistern.core.InMemoryResourceStore;
import com.enrichmeai.cistern.core.Representation;
import com.enrichmeai.cistern.core.ResourceIdentifier;
import com.enrichmeai.cistern.core.rdf.N3Patch;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T2.7's core patch path: {@link LdpService#patch(ResourceIdentifier, Representation)} — the
 * single operation the HTTP layer (and later MCP) calls to serve a {@code PATCH}.
 *
 * <p>T1.5 already pins the patch <em>engine</em> — parsing, the 400/422 split, the three 409s of
 * application. What is pinned here is the <em>orchestration</em> the engine cannot see, all of it
 * Solid Protocol §5.3.1 and §5.3:
 * <ul>
 *   <li>which graph a patch starts from, including the spec's "or an empty RDF dataset if the
 *       target resource does not exist yet" — the sentence that makes a patch able to create;</li>
 *   <li>whether the write created or modified, which is the 201/204 the front-end sends;</li>
 *   <li>the server-managed containment guard applied to the <em>result</em>, so a patch cannot
 *       insert a containment triple its own text never contained;</li>
 *   <li>the refusal of a target that has no graph at all, carrying the kind its {@code Allow}
 *       is rendered from;</li>
 *   <li>that the stored serialization survives a patch, which RFC 5789 §2 requires.</li>
 * </ul>
 *
 * <p>Every case runs against {@link InMemoryResourceStore}, the contract-kit-verified reference
 * backend, so these pin the service's behaviour over a conformant store rather than over a mock
 * built from a guess (ground rule 6).
 */
class LdpServicePatchTest {

    private static final String BASE = "https://pod.example";

    private static final String VOCAB = "https://vocab.example/";

    private static final byte[] PNG_BYTES = {
            (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x01};

    private InMemoryResourceStore store;
    private LdpService service;

    @BeforeEach
    void freshService() {
        store = new InMemoryResourceStore();
        service = new LdpService(store);
    }

    // ---------------------------------------------------------------- helpers

    private static ResourceIdentifier id(String path) {
        return new ResourceIdentifier(URI.create(BASE + path));
    }

    private static Representation turtle(String content) {
        return new Representation(Representation.TURTLE, content.getBytes(StandardCharsets.UTF_8));
    }

    private static Representation jsonLd(String content) {
        return new Representation(Representation.JSON_LD, content.getBytes(StandardCharsets.UTF_8));
    }

    private static Representation n3(String document) {
        return new Representation(N3Patch.MEDIA_TYPE, document.getBytes(StandardCharsets.UTF_8));
    }

    private WriteOutcome patch(String path, String n3Document) {
        return awaitOne(service.patch(id(path), n3(n3Document)), "patch " + path);
    }

    private WriteOutcome put(String path, Representation representation) {
        return awaitOne(service.put(id(path), representation), "put " + path);
    }

    private <T extends Throwable> void expectError(String path, String n3Document, Class<T> type) {
        StepVerifier.create(service.patch(id(path), n3(n3Document))).expectError(type).verify();
    }

    /** The patched resource's graph, read back through the service. */
    private Model graphOf(String path) {
        ResourceView view = awaitOne(service.read(id(path)), "read " + path);
        return assertInstanceOf(ResourceView.Rdf.class, view).graph();
    }

    /** The bytes as they actually sit in the store, to check the serialization that was kept. */
    private Representation storedRepresentationOf(String path) {
        return awaitOne(store.get(id(path)).map(stored -> stored.representation()), "stored " + path);
    }

    private static <T> T awaitOne(Mono<T> mono, String what) {
        AtomicReference<T> captured = new AtomicReference<>();
        StepVerifier.create(mono.doOnNext(captured::set)).expectNextCount(1).verifyComplete();
        assertNotNull(captured.get(), what + " must emit a value");
        return captured.get();
    }

    private static Resource subject(Model graph, String uri) {
        return graph.createResource(uri);
    }

    private static Property property(Model graph, String localName) {
        return graph.createProperty(VOCAB, localName);
    }

    // ---------------------------------------------------------------- creating by patch

    @Nested
    @DisplayName("Solid Protocol §5.3.1: a patch starts from an empty dataset if the target does not exist")
    class CreatesAnAbsentResource {

        /**
         * The spec's processing step 1, verbatim: "Start from the RDF dataset in the target
         * document, <b>or an empty RDF dataset if the target resource does not exist yet</b>."
         * So an insert-only patch of a resource that is not there is a create, not a 404 — which
         * is what §5.5 ("When a server creates an RDF source on HTTP PUT, POST, or PATCH
         * requests") and §5.3's URI-allocation note both presuppose.
         */
        @Test
        @DisplayName("an insert-only patch of an absent resource creates it, reporting CREATED")
        void createsTheResource() {
            WriteOutcome outcome = patch("/notes/new.ttl", """
                    @prefix solid: <http://www.w3.org/ns/solid/terms#>.
                    _:add a solid:InsertDeletePatch;
                      solid:inserts { <https://pod.example/notes/new.ttl> <https://vocab.example/k> "v". }.
                    """);

            assertEquals(WriteEffect.CREATED, outcome.effect());
            assertTrue(outcome.created());

            Model graph = graphOf("/notes/new.ttl");
            assertTrue(graph.contains(subject(graph, BASE + "/notes/new.ttl"),
                    property(graph, "k"), "v"), "the inserted triple is the resource's new state");
        }

        /**
         * §5.3: "Servers MUST create intermediate containers and include corresponding
         * containment triples in container representations derived from the URI path component
         * of PUT and PATCH requests." Free here because the write goes through
         * {@link LdpService#put}, so the store creates the path exactly as it does for a PUT —
         * pinned rather than assumed.
         */
        @Test
        @DisplayName("§5.3: intermediate containers on the path to a patched resource are created")
        void createsIntermediateContainers() {
            patch("/deep/nested/note.ttl", """
                    @prefix solid: <http://www.w3.org/ns/solid/terms#>.
                    _:add a solid:InsertDeletePatch;
                      solid:inserts { <https://pod.example/deep/nested/note.ttl> <https://vocab.example/k> "v". }.
                    """);

            StepVerifier.create(store.exists(id("/deep/"))).expectNext(true).verifyComplete();
            StepVerifier.create(store.exists(id("/deep/nested/"))).expectNext(true).verifyComplete();
        }

        /**
         * A resource created by a patch has no previous serialization to preserve, so it is
         * stored as Turtle. §5.5 keeps both RDF serializations available on GET regardless.
         */
        @Test
        @DisplayName("a resource created by a patch is stored as text/turtle")
        void storesANewResourceAsTurtle() {
            patch("/notes/new.ttl", """
                    @prefix solid: <http://www.w3.org/ns/solid/terms#>.
                    _:add a solid:InsertDeletePatch;
                      solid:inserts { <https://pod.example/notes/new.ttl> <https://vocab.example/k> "v". }.
                    """);

            assertEquals(Representation.TURTLE, storedRepresentationOf("/notes/new.ttl").contentType());
        }

        /**
         * A {@code where} clause cannot be satisfied by a dataset that is empty, so the same
         * patch that creates when it only inserts is a 409 when it also conditions — the spec's
         * "If no such mapping exists ... the server MUST respond with a 409 status code",
         * evaluated against the empty starting dataset rather than short-circuited to a 404.
         */
        @Test
        @DisplayName("a conditional patch of an absent resource is a 409, not a 404")
        void refusesAConditionalPatchOfAnAbsentResource() {
            expectError("/notes/absent.ttl", """
                    @prefix solid: <http://www.w3.org/ns/solid/terms#>.
                    _:u a solid:InsertDeletePatch;
                      solid:where   { ?s <https://vocab.example/k> "v". };
                      solid:inserts { ?s <https://vocab.example/k2> "v2". }.
                    """, CisternException.Conflict.class);

            StepVerifier.create(store.exists(id("/notes/absent.ttl"))).expectNext(false).verifyComplete();
        }
    }

    // ---------------------------------------------------------------- patching what is there

    @Nested
    @DisplayName("patching an existing document")
    class PatchesAnExistingDocument {

        @Test
        @DisplayName("a patch that modifies an existing resource reports REPLACED")
        void reportsReplaced() {
            put("/notes/a.ttl", turtle("<> <https://vocab.example/k> \"v\" ."));

            WriteOutcome outcome = patch("/notes/a.ttl", """
                    @prefix solid: <http://www.w3.org/ns/solid/terms#>.
                    _:add a solid:InsertDeletePatch;
                      solid:inserts { <https://pod.example/notes/a.ttl> <https://vocab.example/k2> "v2". }.
                    """);

            assertEquals(WriteEffect.REPLACED, outcome.effect());
            assertFalse(outcome.created());

            Model graph = graphOf("/notes/a.ttl");
            assertTrue(graph.contains(subject(graph, BASE + "/notes/a.ttl"), property(graph, "k"), "v"),
                    "the pre-existing triple survives a patch that did not delete it");
            assertTrue(graph.contains(subject(graph, BASE + "/notes/a.ttl"), property(graph, "k2"), "v2"));
        }

        /**
         * RFC 5789 §2: "entity-headers contained in the request apply only to the contained
         * patch document and MUST NOT be applied to the resource being modified ... this
         * document does not specify a way to modify a document's Content-Type". A patch changes
         * the graph and nothing else, so a JSON-LD document stays JSON-LD.
         */
        @Test
        @DisplayName("RFC 5789 §2: the stored serialization survives the patch")
        void keepsTheStoredMediaType() {
            put("/notes/j.jsonld", jsonLd("""
                    {"@id": "https://pod.example/notes/j.jsonld", "https://vocab.example/k": "v"}
                    """));

            patch("/notes/j.jsonld", """
                    @prefix solid: <http://www.w3.org/ns/solid/terms#>.
                    _:add a solid:InsertDeletePatch;
                      solid:inserts { <https://pod.example/notes/j.jsonld> <https://vocab.example/k2> "v2". }.
                    """);

            assertEquals(Representation.JSON_LD, storedRepresentationOf("/notes/j.jsonld").contentType());
            Model graph = graphOf("/notes/j.jsonld");
            assertTrue(graph.contains(subject(graph, BASE + "/notes/j.jsonld"), property(graph, "k2"), "v2"));
        }

        /** The deletes half of the spec's algorithm, over the wire-facing service rather than the engine. */
        @Test
        @DisplayName("solid:deletes removes triples from the stored graph")
        void deletesTriples() {
            put("/notes/a.ttl", turtle("""
                    <> <https://vocab.example/k> "v" ;
                       <https://vocab.example/k2> "v2" .
                    """));

            patch("/notes/a.ttl", """
                    @prefix solid: <http://www.w3.org/ns/solid/terms#>.
                    _:d a solid:InsertDeletePatch;
                      solid:deletes { <https://pod.example/notes/a.ttl> <https://vocab.example/k> "v". }.
                    """);

            Model graph = graphOf("/notes/a.ttl");
            assertFalse(graph.contains(subject(graph, BASE + "/notes/a.ttl"), property(graph, "k"), "v"),
                    "the deleted triple is gone");
            assertTrue(graph.contains(subject(graph, BASE + "/notes/a.ttl"), property(graph, "k2"), "v2"),
                    "the untouched triple remains");
        }
    }

    // ---------------------------------------------------------------- the spec's own example

    /**
     * Solid Protocol §5.3.1's example, verbatim, applied through the service rather than through
     * the engine: T1.5 proved the algorithm, this proves the orchestration around it stores what
     * the algorithm produced.
     */
    @Test
    @DisplayName("§5.3.1's example renames Claudia Garcia into Alex Garcia and stores the result")
    void appliesTheSpecExample() {
        put("/notes/doc.ttl", turtle("""
                @prefix ex: <http://www.example.org/terms#>.
                <#claudia> ex:familyName "Garcia" ;
                           ex:givenName "Claudia" .
                """));

        patch("/notes/doc.ttl", """
                @prefix solid: <http://www.w3.org/ns/solid/terms#>.
                @prefix ex: <http://www.example.org/terms#>.

                _:rename a solid:InsertDeletePatch;
                  solid:where   { ?person ex:familyName "Garcia". };
                  solid:inserts { ?person ex:givenName "Alex". };
                  solid:deletes { ?person ex:givenName "Claudia". }.
                """);

        Model graph = graphOf("/notes/doc.ttl");
        Resource claudia = graph.createResource(BASE + "/notes/doc.ttl#claudia");
        Property givenName = graph.createProperty("http://www.example.org/terms#", "givenName");
        Property familyName = graph.createProperty("http://www.example.org/terms#", "familyName");
        assertTrue(graph.contains(claudia, givenName, "Alex"), "solid:inserts added the new given name");
        assertFalse(graph.contains(claudia, givenName, "Claudia"), "solid:deletes removed the old one");
        assertTrue(graph.contains(claudia, familyName, "Garcia"), "the condition's triple is untouched");
    }

    // ---------------------------------------------------------------- refusals

    @Nested
    @DisplayName("what a patch may not do")
    class Refusals {

        /**
         * Solid Protocol §5.3.1 requires PATCH with an N3 Patch body only "when the target of
         * the request is an RDF document", and there is no graph in a byte stream. RFC 9110
         * §15.5.6's 405 is the refusal, and it carries {@link LdpKind#NON_RDF_DOCUMENT} so the
         * mandatory {@code Allow} is the one that resource really advertises — a fact the URI
         * cannot supply.
         */
        @Test
        @DisplayName("§5.3.1: patching a non-RDF source is 405, carrying its kind")
        void refusesANonRdfTarget() {
            put("/img/logo.png", new Representation("image/png", PNG_BYTES));

            AtomicReference<CisternException.MethodNotAllowed> captured = new AtomicReference<>();
            StepVerifier.create(service.patch(id("/img/logo.png"), n3("""
                            @prefix solid: <http://www.w3.org/ns/solid/terms#>.
                            _:a a solid:InsertDeletePatch;
                              solid:inserts { <https://pod.example/img/logo.png> <https://vocab.example/k> "v". }.
                            """)))
                    .consumeErrorWith(error -> captured.set(
                            assertInstanceOf(CisternException.MethodNotAllowed.class, error)))
                    .verify();

            assertEquals(LdpKind.NON_RDF_DOCUMENT, captured.get().kind(),
                    "the refusal must carry the kind its Allow is rendered from");
        }

        /**
         * Solid Protocol §5.3: "Servers MUST NOT allow HTTP PUT or PATCH on a container to
         * update its containment triples; if the server receives such a request, it MUST respond
         * with a 409 status code."
         *
         * <p>The insertion here names the container and {@code ldp:contains} literally, which is
         * the simple case; {@link #refusesContainmentInsertedThroughAVariable} covers the case
         * the guard exists for.
         */
        @Test
        @DisplayName("§5.3: a patch inserting ldp:contains for the target is 409")
        void refusesInsertingContainment() {
            put("/c/", turtle("<> <https://vocab.example/k> \"v\" ."));

            expectError("/c/", """
                    @prefix solid: <http://www.w3.org/ns/solid/terms#>.
                    @prefix ldp: <http://www.w3.org/ns/ldp#>.
                    _:a a solid:InsertDeletePatch;
                      solid:inserts { <https://pod.example/c/> ldp:contains <https://pod.example/c/fake>. }.
                    """, CisternException.Conflict.class);
        }

        /**
         * The reason the containment guard runs on the <b>result</b> graph rather than on the
         * patch document: here the patch's own text contains neither the container's URI nor a
         * containment triple — both terms arrive through the variable the {@code where} clause
         * binds. A guard that inspected the request body would pass this straight through.
         */
        @Test
        @DisplayName("§5.3: containment smuggled in through a bound variable is still 409")
        void refusesContainmentInsertedThroughAVariable() {
            put("/c/", turtle("<> <https://vocab.example/self> <https://pod.example/c/> ."));

            expectError("/c/", """
                    @prefix solid: <http://www.w3.org/ns/solid/terms#>.
                    @prefix ldp: <http://www.w3.org/ns/ldp#>.
                    _:a a solid:InsertDeletePatch;
                      solid:where   { ?c <https://vocab.example/self> ?c. };
                      solid:inserts { ?c ldp:contains <https://pod.example/c/fake>. }.
                    """, CisternException.Conflict.class);
        }

        /**
         * The other half of §5.3's containment rule. A container is patched against its stored
         * triples, which never include the derived containment (see {@link LdpService#patch}),
         * so a patch that tries to delete a containment triple finds it absent — and the spec's
         * deletes-absent rule answers with the same 409 the containment rule demands.
         */
        @Test
        @DisplayName("§5.3: a patch deleting a derived containment triple is 409")
        void refusesDeletingContainment() {
            put("/c/", turtle("<> <https://vocab.example/k> \"v\" ."));
            put("/c/child.ttl", turtle("<> <https://vocab.example/k> \"v\" ."));

            expectError("/c/", """
                    @prefix solid: <http://www.w3.org/ns/solid/terms#>.
                    @prefix ldp: <http://www.w3.org/ns/ldp#>.
                    _:a a solid:InsertDeletePatch;
                      solid:deletes { <https://pod.example/c/> ldp:contains <https://pod.example/c/child.ttl>. }.
                    """, CisternException.Conflict.class);

            // The child is still contained: the refused patch changed nothing.
            StepVerifier.create(store.exists(id("/c/child.ttl"))).expectNext(true).verifyComplete();
        }

        /** A patch that is not N3 at all — T1.5's 400, surfacing unchanged through the service. */
        @Test
        @DisplayName("a malformed patch document is BadInput (400)")
        void refusesAMalformedPatchDocument() {
            expectError("/notes/a.ttl", "this is not N3 {{{", CisternException.BadInput.class);
        }

        /**
         * A well-formed N3 document that breaches §5.3.1's constraints — here the patch resource
         * is missing its {@code solid:InsertDeletePatch} type. T1.5's 422, surfacing unchanged.
         */
        @Test
        @DisplayName("a patch breaching §5.3.1's constraints is UnprocessableEntity (422)")
        void refusesAConstraintBreach() {
            expectError("/notes/a.ttl", """
                    @prefix solid: <http://www.w3.org/ns/solid/terms#>.
                    _:a solid:inserts { <https://pod.example/notes/a.ttl> <https://vocab.example/k> "v". }.
                    """, CisternException.UnprocessableEntity.class);
        }

        /** §5.3.1: "If the set of triples resulting from ?deletions is non-empty and the dataset does not contain all of these triples ... 409". */
        @Test
        @DisplayName("deleting a triple that is not in the graph is 409")
        void refusesDeletingAnAbsentTriple() {
            put("/notes/a.ttl", turtle("<> <https://vocab.example/k> \"v\" ."));

            expectError("/notes/a.ttl", """
                    @prefix solid: <http://www.w3.org/ns/solid/terms#>.
                    _:d a solid:InsertDeletePatch;
                      solid:deletes { <https://pod.example/notes/a.ttl> <https://vocab.example/nope> "gone". }.
                    """, CisternException.Conflict.class);
        }

        /** §5.3.1: "if multiple mappings exist, the server MUST respond with a 409 status code". */
        @Test
        @DisplayName("a where clause with multiple solutions is 409")
        void refusesMultipleMappings() {
            put("/notes/a.ttl", turtle("""
                    <#one> <https://vocab.example/k> "v" .
                    <#two> <https://vocab.example/k> "v" .
                    """));

            expectError("/notes/a.ttl", """
                    @prefix solid: <http://www.w3.org/ns/solid/terms#>.
                    _:m a solid:InsertDeletePatch;
                      solid:where   { ?s <https://vocab.example/k> "v". };
                      solid:inserts { ?s <https://vocab.example/k2> "v2". }.
                    """, CisternException.Conflict.class);
        }

        /** A refused patch must leave the stored state exactly as it was. */
        @Test
        @DisplayName("a refused patch does not change the resource")
        void leavesTheResourceUntouchedOnRefusal() {
            put("/notes/a.ttl", turtle("<> <https://vocab.example/k> \"v\" ."));

            expectError("/notes/a.ttl", """
                    @prefix solid: <http://www.w3.org/ns/solid/terms#>.
                    _:d a solid:InsertDeletePatch;
                      solid:deletes { <https://pod.example/notes/a.ttl> <https://vocab.example/nope> "gone". }.
                    """, CisternException.Conflict.class);

            Model graph = graphOf("/notes/a.ttl");
            assertTrue(graph.contains(subject(graph, BASE + "/notes/a.ttl"), property(graph, "k"), "v"));
            assertEquals(1, graph.size(), "nothing was added or removed by the refused patch");
        }
    }

    // ---------------------------------------------------------------- containers

    @Nested
    @DisplayName("patching a container")
    class PatchesAContainer {

        /**
         * A container is an RDF source (§4.2), so it is patchable. Its client-authored triples
         * are what a patch changes; its containment is derived on read and is not part of the
         * patchable state.
         */
        @Test
        @DisplayName("a container's client-authored triples can be patched")
        void patchesClientAuthoredTriples() {
            put("/c/", turtle("<> <https://vocab.example/title> \"old\" ."));
            put("/c/child.ttl", turtle("<> <https://vocab.example/k> \"v\" ."));

            WriteOutcome outcome = patch("/c/", """
                    @prefix solid: <http://www.w3.org/ns/solid/terms#>.
                    _:t a solid:InsertDeletePatch;
                      solid:deletes { <https://pod.example/c/> <https://vocab.example/title> "old". };
                      solid:inserts { <https://pod.example/c/> <https://vocab.example/title> "new". }.
                    """);

            assertEquals(WriteEffect.REPLACED, outcome.effect());

            Model graph = graphOf("/c/");
            Resource container = subject(graph, BASE + "/c/");
            assertTrue(graph.contains(container, property(graph, "title"), "new"));
            assertFalse(graph.contains(container, property(graph, "title"), "old"));
            assertTrue(graph.contains(container, Ldp.CONTAINS,
                            graph.createResource(BASE + "/c/child.ttl")),
                    "containment is still derived on read after a patch of the container");
        }

        /**
         * The derived triples must not be written back into storage by a patch: containment is
         * derived, never stored (see {@link LdpService}'s class javadoc). Checked against the
         * stored bytes rather than the served graph, because the served graph re-derives them
         * and so could not tell the two apart.
         */
        @Test
        @DisplayName("patching a container does not persist its derived containment triples")
        void doesNotPersistDerivedTriples() {
            put("/c/", turtle("<> <https://vocab.example/title> \"t\" ."));
            put("/c/child.ttl", turtle("<> <https://vocab.example/k> \"v\" ."));

            patch("/c/", """
                    @prefix solid: <http://www.w3.org/ns/solid/terms#>.
                    _:a a solid:InsertDeletePatch;
                      solid:inserts { <https://pod.example/c/> <https://vocab.example/k2> "v2". }.
                    """);

            String stored = new String(storedRepresentationOf("/c/").data(), StandardCharsets.UTF_8);
            assertFalse(stored.contains("contains"),
                    "no ldp:contains may reach storage; stored bytes were:\n" + stored);
            assertFalse(stored.contains("BasicContainer"),
                    "no server-asserted LDP type may reach storage; stored bytes were:\n" + stored);
        }
    }
}
