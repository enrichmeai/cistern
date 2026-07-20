package com.enrichmeai.cistern.core.ldp;

import com.enrichmeai.cistern.core.CisternException;
import com.enrichmeai.cistern.core.InMemoryResourceStore;
import com.enrichmeai.cistern.core.Representation;
import com.enrichmeai.cistern.core.ResourceIdentifier;
import com.enrichmeai.cistern.core.StoredResource;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T2.2's core write path: {@link LdpService#put(ResourceIdentifier, Representation)} — the
 * single operation the HTTP layer (and later MCP) calls to serve a {@code PUT}.
 *
 * <p>What is pinned here is the LDP/Solid semantics of a write, because the handler above it
 * is deliberately incapable of expressing any of it:
 * <ul>
 *   <li>create vs replace, the {@link WriteEffect} that RFC 9110 §9.3.4 turns into 201 vs
 *       204;</li>
 *   <li>intermediate containers and their derived containment (Solid Protocol §5.3, §4.2);</li>
 *   <li>slash semantics — one name is a container or a document, never both (§3.1) — and the
 *       {@link CisternException.Conflict} that surfaces from the store when a write would
 *       flip a name's kind;</li>
 *   <li>the server-managed containment guard of §5.3, and the deliberate tolerance of
 *       client-echoed {@code rdf:type ldp:*} triples that sits beside it;</li>
 *   <li>bodies validated before storage, so unparseable stored bytes can stay a 500-class
 *       server fault rather than a client one.</li>
 * </ul>
 *
 * <p>Every case runs against {@link InMemoryResourceStore}, the contract-kit-verified
 * reference backend, so what these tests pin is the service's behaviour over a conformant
 * store rather than over a mock built from a guess (ground rule 6).
 */
class LdpServicePutTest {

    private static final String BASE = "https://pod.example";

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

    private static Representation png() {
        return new Representation("image/png", PNG_BYTES);
    }

    /**
     * Runs a write to completion via {@link StepVerifier} and hands back its outcome. No
     * {@code .block()} anywhere in this codebase, tests included (ground rule 3) — the value
     * is captured out of the reactive chain instead.
     */
    private WriteOutcome put(String path, Representation representation) {
        return awaitOne(service.put(id(path), representation), "put " + path);
    }

    private void expectConflict(String path, Representation representation) {
        StepVerifier.create(service.put(id(path), representation))
                .expectError(CisternException.Conflict.class)
                .verify();
    }

    /** The URIs a container currently contains, read back through the service. */
    private Set<String> containedIn(String containerPath) {
        ResourceIdentifier container = id(containerPath);
        return awaitOne(service.getContainer(container).map(model -> {
            Resource subject = model.createResource(container.uri().toString());
            return model.listObjectsOfProperty(subject, Ldp.CONTAINS).toList().stream()
                    .filter(RDFNode::isURIResource)
                    .map(node -> node.asResource().getURI())
                    .collect(Collectors.toSet());
        }), "containment of " + containerPath);
    }

    /** The single value a Mono emits, captured without blocking. */
    private static <T> T awaitOne(Mono<T> mono, String what) {
        AtomicReference<T> captured = new AtomicReference<>();
        StepVerifier.create(mono.doOnNext(captured::set)).expectNextCount(1).verifyComplete();
        assertNotNull(captured.get(), what + " must emit a value");
        return captured.get();
    }

    // ---------------------------------------------------------------- create vs replace

    /**
     * RFC 9110 §9.3.4: a PUT to a target with no current representation creates one, which the
     * origin server MUST report as 201 — so the effect must come back CREATED.
     */
    @Test
    void createsAResourceThatDidNotExist() {
        WriteOutcome outcome = put("/notes/a.ttl", turtle("<> <https://vocab.example/k> \"v\" ."));

        assertEquals(WriteEffect.CREATED, outcome.effect());
        assertTrue(outcome.created());
        ResourceView.Rdf view = assertInstanceOf(ResourceView.Rdf.class, outcome.view());
        assertEquals(id("/notes/a.ttl"), view.identifier());
        assertFalse(view.container(), "a document view must not report itself a container");
    }

    /** Second write to the same target reports REPLACED — RFC 9110 §9.3.4's 204 branch. */
    @Test
    void replacesAResourceThatAlreadyExists() {
        put("/notes/a.ttl", turtle("<> <https://vocab.example/k> \"first\" ."));

        WriteOutcome outcome = put("/notes/a.ttl", turtle("<> <https://vocab.example/k> \"second\" ."));

        assertEquals(WriteEffect.REPLACED, outcome.effect());
        StepVerifier.create(store.get(id("/notes/a.ttl")).map(LdpServicePutTest::bodyOf))
                .expectNextMatches(body -> body.contains("second") && !body.contains("first"))
                .verifyComplete();
    }

    /** The post-write view is the one the next read resolves — same construction, no drift. */
    @Test
    void reportsThePostWriteViewThatAReadWouldResolve() {
        put("/notes/a.ttl", turtle("<> <https://vocab.example/k> \"v\" ."));
        WriteOutcome outcome = put("/notes/", turtle(""));

        ResourceView.Rdf written = assertInstanceOf(ResourceView.Rdf.class, outcome.view());
        assertTrue(written.container());
        ResourceView read = awaitOne(service.read(id("/notes/")), "read /notes/");
        ResourceView.Rdf reread = assertInstanceOf(ResourceView.Rdf.class, read);
        assertEquals(reread.etag(), written.etag());
        // Derived containment is present in the view the WRITE reported, not only on a reread.
        assertTrue(written.graph().contains(
                written.graph().createResource(id("/notes/").uri().toString()),
                Ldp.CONTAINS,
                written.graph().createResource(id("/notes/a.ttl").uri().toString())));
    }

    // ---------------------------------------------------------------- intermediate containers

    /**
     * Solid Protocol §5.3: "Servers MUST create intermediate containers and include
     * corresponding containment triples in container representations derived from the URI
     * path component of PUT and PATCH requests."
     */
    @Test
    void createsIntermediateContainersAndTheirContainment() {
        put("/a/b/c/deep.ttl", turtle("<> <https://vocab.example/k> \"v\" ."));

        StepVerifier.create(store.exists(id("/a/"))).expectNext(true).verifyComplete();
        StepVerifier.create(store.exists(id("/a/b/"))).expectNext(true).verifyComplete();
        StepVerifier.create(store.exists(id("/a/b/c/"))).expectNext(true).verifyComplete();
        assertEquals(Set.of(BASE + "/a/b/"), containedIn("/a/"));
        assertEquals(Set.of(BASE + "/a/b/c/"), containedIn("/a/b/"));
        assertEquals(Set.of(BASE + "/a/b/c/deep.ttl"), containedIn("/a/b/c/"));
    }

    /** A container target gets its own intermediates too, not only a document target. */
    @Test
    void createsIntermediateContainersForANestedContainer() {
        put("/x/y/z/", turtle(""));

        StepVerifier.create(store.exists(id("/x/y/"))).expectNext(true).verifyComplete();
        assertEquals(Set.of(BASE + "/x/y/z/"), containedIn("/x/y/"));
    }

    // ---------------------------------------------------------------- slash semantics (§3.1)

    /**
     * Solid Protocol §3.1: if two URIs differ only in the trailing slash and the server has
     * associated a resource with one of them, the other MUST NOT correspond to another
     * resource. The store enforces it; this pins that {@link LdpService#put} propagates the
     * Conflict rather than swallowing or re-labelling it.
     */
    @Test
    void rejectsDocumentWrittenOverAnExistingContainer() {
        put("/foo/", turtle(""));

        expectConflict("/foo", turtle("<> <https://vocab.example/k> \"v\" ."));
    }

    /** The same rule in the other direction: a container may not take a document's name. */
    @Test
    void rejectsContainerWrittenOverAnExistingDocument() {
        put("/foo", turtle("<> <https://vocab.example/k> \"v\" ."));

        expectConflict("/foo/", turtle(""));
    }

    /** An intermediate container cannot displace a document that already holds the name. */
    @Test
    void rejectsIntermediateContainerBlockedByADocument() {
        put("/foo", turtle("<> <https://vocab.example/k> \"v\" ."));

        expectConflict("/foo/bar.ttl", turtle("<> <https://vocab.example/k> \"v\" ."));
    }

    /** A refused write leaves nothing behind — no target, no half-built ancestor chain. */
    @Test
    void aRefusedWriteMutatesNothing() {
        put("/foo", turtle("<> <https://vocab.example/k> \"v\" ."));

        expectConflict("/foo/bar/baz.ttl", turtle("<> <https://vocab.example/k> \"v\" ."));

        StepVerifier.create(store.exists(id("/foo/"))).expectNext(false).verifyComplete();
        StepVerifier.create(store.exists(id("/foo/bar/"))).expectNext(false).verifyComplete();
        StepVerifier.create(store.exists(id("/foo/bar/baz.ttl"))).expectNext(false).verifyComplete();
    }

    // ---------------------------------------------------------------- container body kind

    /**
     * Solid Protocol §4.2 makes a container an LDP Basic Container, i.e. an RDF source, so a
     * non-RDF body is inconsistent with the target resource. RFC 9110 §9.3.4 suggests 409 or
     * 415 for exactly that; Cistern reports the container/document constraint the same way
     * §3.1's kind flip is reported.
     */
    @Test
    void rejectsNonRdfBodyForAContainer() {
        expectConflict("/pictures/", png());
    }

    /** The same body is perfectly legal against a document target. */
    @Test
    void acceptsNonRdfBodyForADocument() {
        WriteOutcome outcome = put("/logo.png", png());

        assertEquals(WriteEffect.CREATED, outcome.effect());
        ResourceView.NonRdf view = assertInstanceOf(ResourceView.NonRdf.class, outcome.view());
        assertEquals("image/png", view.representation().contentType());
        assertArrayEquals(PNG_BYTES, view.representation().data());
    }

    /** JSON-LD is an RDF source too, so it may carry a container's representation. */
    @Test
    void acceptsJsonLdBodyForAContainer() {
        WriteOutcome outcome = put("/notes/", jsonLd("""
                {"@id": "", "http://purl.org/dc/terms/title": "My notes"}
                """));

        assertEquals(WriteEffect.CREATED, outcome.effect());
        assertTrue(outcome.view().container());
    }

    // ---------------------------------------------------------------- server-managed triples

    /**
     * Solid Protocol §5.3: "Servers MUST NOT allow HTTP PUT or PATCH on a container to update
     * its containment triples; if the server receives such a request, it MUST respond with a
     * 409 status code."
     */
    @Test
    void rejectsBodyAssertingContainmentForAContainer() {
        expectConflict("/notes/", turtle("""
                @prefix ldp: <http://www.w3.org/ns/ldp#> .
                <> ldp:contains <https://pod.example/notes/injected.ttl> .
                """));
    }

    /** The guard is not container-only: a document may not claim containment for itself. */
    @Test
    void rejectsBodyAssertingContainmentForADocument() {
        expectConflict("/notes/a.ttl", turtle("""
                @prefix ldp: <http://www.w3.org/ns/ldp#> .
                <> ldp:contains <https://pod.example/notes/injected.ttl> .
                """));
    }

    /**
     * Containment about some OTHER subject is ordinary client data (a cached remote listing,
     * say) and is stored, not refused — the guard is scoped to the target's own containment.
     */
    @Test
    void acceptsContainmentTriplesAboutAnotherSubject() {
        WriteOutcome outcome = put("/notes/a.ttl", turtle("""
                @prefix ldp: <http://www.w3.org/ns/ldp#> .
                <https://other.example/c/> ldp:contains <https://other.example/c/x> .
                """));

        assertEquals(WriteEffect.CREATED, outcome.effect());
    }

    /**
     * The documented asymmetry: clients routinely echo back the {@code rdf:type ldp:*} triples
     * a GET handed them, so those are tolerated on write and re-derived on read.
     */
    @Test
    void toleratesClientEchoedLdpTypeTriples() {
        WriteOutcome outcome = put("/notes/", turtle("""
                @prefix ldp: <http://www.w3.org/ns/ldp#> .
                <> a ldp:BasicContainer, ldp:Container, ldp:Resource .
                """));

        assertEquals(WriteEffect.CREATED, outcome.effect());
        ResourceView.Rdf view = assertInstanceOf(ResourceView.Rdf.class, outcome.view());
        Resource subject = view.graph().createResource(id("/notes/").uri().toString());
        assertTrue(view.graph().contains(subject, RDF.type, Ldp.BASIC_CONTAINER));
    }

    // ---------------------------------------------------------------- body validation

    /**
     * Bodies are parsed before they are stored, so malformed RDF is the client's BadInput at
     * write time. That is what entitles the read path to treat unparseable stored bytes as
     * server-side corruption (a 500) instead of a client fault.
     */
    @Test
    void rejectsMalformedTurtle() {
        StepVerifier.create(service.put(id("/notes/a.ttl"), turtle("<> <https://vocab.example/k>")))
                .expectError(CisternException.BadInput.class)
                .verify();
    }

    /** Same rule for the other RDF serialization Solid Protocol §5.5 requires. */
    @Test
    void rejectsMalformedJsonLd() {
        StepVerifier.create(service.put(id("/notes/a.ttl"), jsonLd("{ not json")))
                .expectError(CisternException.BadInput.class)
                .verify();
    }

    /** A refused body never reaches storage. */
    @Test
    void aMalformedBodyIsNotStored() {
        StepVerifier.create(service.put(id("/notes/a.ttl"), turtle("<> <https://vocab.example/k>")))
                .expectError(CisternException.BadInput.class)
                .verify();

        StepVerifier.create(store.exists(id("/notes/a.ttl"))).expectNext(false).verifyComplete();
    }

    /** An empty RDF body is a valid empty graph, not a parse failure. */
    @Test
    void acceptsAnEmptyRdfBody() {
        assertEquals(WriteEffect.CREATED, put("/notes/", turtle("")).effect());
    }

    // ---------------------------------------------------------------- storage fidelity

    /**
     * Parsing is validation, not transformation: the stored bytes are the client's, prefixes,
     * comments, layout and all. Re-serializing would churn etags (Jena's JSON-LD writer is not
     * byte-stable) and would forfeit the right to return a validator with the response under
     * RFC 9110 §9.3.4.
     */
    @Test
    void storesTheClientBytesVerbatim() {
        String authored = """
                # a comment the client wrote
                @prefix dcterms: <http://purl.org/dc/terms/> .

                <>    dcterms:title    "Spaced out on purpose" .
                """;

        put("/notes/a.ttl", turtle(authored));

        StoredResource stored = awaitOne(store.get(id("/notes/a.ttl")), "stored /notes/a.ttl");
        assertArrayEquals(authored.getBytes(StandardCharsets.UTF_8), stored.representation().data());
        assertEquals(Representation.TURTLE, stored.representation().contentType());
    }

    // ---------------------------------------------------------------- storage root

    /**
     * Solid Protocol §5.4 singles the storage root out for DELETE only; nothing exempts it
     * from being written, and a pod must be able to describe its own root.
     */
    @Test
    void writesTheStorageRootContainer() {
        WriteOutcome created = put("/", turtle("<> <https://vocab.example/name> \"My pod\" ."));
        assertEquals(WriteEffect.CREATED, created.effect());
        assertTrue(created.view().container());

        WriteOutcome replaced = put("/", turtle("<> <https://vocab.example/name> \"Renamed\" ."));
        assertEquals(WriteEffect.REPLACED, replaced.effect());
    }

    // ---------------------------------------------------------------- argument guards

    /** Never throws synchronously: a null argument arrives as an error signal (SPI style). */
    @Test
    void signalsRatherThanThrowsForNullArguments() {
        Mono<WriteOutcome> nullTarget = service.put(null, turtle(""));
        StepVerifier.create(nullTarget).expectError(NullPointerException.class).verify();

        Mono<WriteOutcome> nullBody = service.put(id("/a.ttl"), null);
        StepVerifier.create(nullBody).expectError(NullPointerException.class).verify();
    }

    private static String bodyOf(StoredResource stored) {
        return new String(stored.representation().data(), StandardCharsets.UTF_8);
    }
}
