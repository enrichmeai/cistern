package com.enrichmeai.cistern.core.ldp;

import com.enrichmeai.cistern.core.CisternException;
import com.enrichmeai.cistern.core.InMemoryResourceStore;
import com.enrichmeai.cistern.core.Representation;
import com.enrichmeai.cistern.core.ResourceIdentifier;
import com.enrichmeai.cistern.core.rdf.RdfIo;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.DCTerms;
import org.apache.jena.vocabulary.RDF;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T1.4 containment layer. Containment is DERIVED from live {@code children()} at read
 * time (Solid Protocol §4.2 — containment mirrors the URI path hierarchy; architecture
 * rule: never stored), and the write guard enforces §5.3's server-managed containment
 * triples. The guard's asymmetry is deliberate and encoded here: target-subject
 * {@code ldp:contains} → BadInput; echoed {@code rdf:type ldp:*} tolerated;
 * other-subject {@code ldp:contains} ignored (not server-managed for this target).
 */
class LdpServiceTest {

    private static final String BASE = "https://pod.example";

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

    /** Arrange step: put and swallow the StoredResource (reactively, no block). */
    private void put(ResourceIdentifier identifier, Representation representation) {
        StepVerifier.create(store.put(identifier, representation))
                .expectNextCount(1)
                .verifyComplete();
    }

    /** The objects of {@code <container> ldp:contains ?o} in the model, as URI strings. */
    private static Set<String> containsObjects(Model model, ResourceIdentifier container) {
        Resource subject = model.createResource(container.uri().toString());
        return model.listStatements(subject, Ldp.CONTAINS, (RDFNode) null)
                .toList().stream()
                .map(statement -> statement.getObject().asResource().getURI())
                .collect(Collectors.toSet());
    }

    /** The rdf:type objects for the container, as URI strings. */
    private static Set<String> typeObjects(Model model, ResourceIdentifier container) {
        Resource subject = model.createResource(container.uri().toString());
        return model.listStatements(subject, RDF.type, (RDFNode) null)
                .toList().stream()
                .map(statement -> statement.getObject().asResource().getURI())
                .collect(Collectors.toSet());
    }

    /** Lifts the synchronous guard the way Phase 2 will: throw becomes an error signal. */
    private Mono<Void> guard(Model body, ResourceIdentifier target) {
        return Mono.fromRunnable(() -> service.rejectServerManagedTriples(body, target));
    }

    private static Model parseBody(String turtleText, ResourceIdentifier target) {
        return RdfIo.parse(turtle(turtleText), target);
    }

    // ---------------------------------------------------------------- getContainer

    @Nested
    class GetContainer {

        private final ResourceIdentifier notes = id("/notes/");

        @Test
        void mergesStoredClientTriplesWithDerivedContainmentAndServerTypes() {
            // Stored representation carries client data (title, a custom type), a STALE
            // containment triple, and an echoed ldp type — the last two must not survive.
            put(notes, turtle("""
                    @prefix dcterms: <http://purl.org/dc/terms/> .
                    @prefix ldp: <http://www.w3.org/ns/ldp#> .
                    @prefix ex: <https://vocab.example/> .
                    <> dcterms:title "My notes" ;
                       a ldp:Container, ex:NoteFolder ;
                       ldp:contains <ghost.ttl> .
                    """));
            put(id("/notes/a.ttl"), turtle("<#it> a <#Note> ."));
            put(id("/notes/sub/"), turtle(""));

            StepVerifier.create(service.getContainer(notes))
                    .assertNext(model -> {
                        Resource subject = model.createResource(notes.uri().toString());
                        assertTrue(model.contains(subject, DCTerms.title, "My notes"),
                                "client-authored triples survive the merge");
                        assertEquals(
                                Set.of(BASE + "/notes/a.ttl", BASE + "/notes/sub/"),
                                containsObjects(model, notes),
                                "ldp:contains shows exactly the live children — the stale"
                                        + " stored ghost.ttl triple must be gone");
                        assertEquals(
                                Set.of(Ldp.BASIC_CONTAINER.getURI(), Ldp.CONTAINER.getURI(),
                                        Ldp.RESOURCE.getURI(), "https://vocab.example/NoteFolder"),
                                typeObjects(model, notes),
                                "server asserts the three LDP types; the client's custom"
                                        + " type survives; no other ldp:* types remain");
                    })
                    .verifyComplete();
        }

        @Test
        void staleStoredContainmentIsStrippedEvenWhenTheContainerIsEmpty() {
            put(notes, turtle("""
                    @prefix ldp: <http://www.w3.org/ns/ldp#> .
                    <> ldp:contains <ghost.ttl>, </elsewhere/thing> .
                    """));

            StepVerifier.create(service.getContainer(notes))
                    .assertNext(model -> assertEquals(Set.of(), containsObjects(model, notes),
                            "no live children → no ldp:contains, whatever storage says"))
                    .verifyComplete();
        }

        @Test
        void childrenMutationsAreReflectedOnTheNextRead() {
            put(id("/notes/a.ttl"), turtle("<#it> a <#Note> ."));

            StepVerifier.create(service.getContainer(notes))
                    .assertNext(model -> assertEquals(Set.of(BASE + "/notes/a.ttl"),
                            containsObjects(model, notes)))
                    .verifyComplete();

            put(id("/notes/b.ttl"), turtle("<#it> a <#Note> ."));

            StepVerifier.create(service.getContainer(notes))
                    .assertNext(model -> assertEquals(
                            Set.of(BASE + "/notes/a.ttl", BASE + "/notes/b.ttl"),
                            containsObjects(model, notes),
                            "a child added after the first read appears on the next read"))
                    .verifyComplete();

            StepVerifier.create(store.delete(id("/notes/a.ttl"))).verifyComplete();

            StepVerifier.create(service.getContainer(notes))
                    .assertNext(model -> assertEquals(Set.of(BASE + "/notes/b.ttl"),
                            containsObjects(model, notes),
                            "a child deleted after a read is gone on the next read"))
                    .verifyComplete();
        }

        @Test
        void emptyContainerCarriesExactlyTheThreeServerAssertedTypeTriples() {
            put(notes, turtle(""));

            StepVerifier.create(service.getContainer(notes))
                    .assertNext(model -> {
                        assertEquals(
                                Set.of(Ldp.BASIC_CONTAINER.getURI(), Ldp.CONTAINER.getURI(),
                                        Ldp.RESOURCE.getURI()),
                                typeObjects(model, notes));
                        assertEquals(3, model.size(),
                                "empty stored body + no children → nothing but the types");
                    })
                    .verifyComplete();
        }

        @Test
        void intermediateContainersCreatedByTheStoreAreReadable() {
            // /deep/nested/ springs into existence as an intermediate (Solid Protocol
            // §5.3) with an empty body; its read must still be a full container graph.
            put(id("/deep/nested/leaf.ttl"), turtle("<#it> a <#Leaf> ."));

            StepVerifier.create(service.getContainer(id("/deep/nested/")))
                    .assertNext(model -> {
                        assertEquals(Set.of(BASE + "/deep/nested/leaf.ttl"),
                                containsObjects(model, id("/deep/nested/")));
                        assertEquals(
                                Set.of(Ldp.BASIC_CONTAINER.getURI(), Ldp.CONTAINER.getURI(),
                                        Ldp.RESOURCE.getURI()),
                                typeObjects(model, id("/deep/nested/")));
                    })
                    .verifyComplete();
        }

        @Test
        void missingContainerCompletesEmpty() {
            StepVerifier.create(service.getContainer(id("/absent/")))
                    .verifyComplete();
        }

        @Test
        void nonContainerIdentifierSignalsIllegalArgument() {
            put(id("/note.ttl"), turtle("<#it> a <#Note> ."));

            // Signalled, not thrown: consistent with the SPI's children() rule.
            StepVerifier.create(service.getContainer(id("/note.ttl")))
                    .verifyError(IllegalArgumentException.class);
        }
    }

    // ---------------------------------------------------------------- write guard

    @Nested
    class RejectServerManagedTriples {

        private final ResourceIdentifier target = id("/notes/");

        @Test
        void acceptsABodyWithoutServerManagedTriples() {
            Model body = parseBody("""
                    @prefix dcterms: <http://purl.org/dc/terms/> .
                    <> dcterms:title "My notes" .
                    <thing> dcterms:creator <https://alice.example/profile#me> .
                    """, target);

            StepVerifier.create(guard(body, target)).verifyComplete();
        }

        @Test
        void rejectsABodyAssertingContainmentForTheTarget() {
            Model body = parseBody("""
                    @prefix ldp: <http://www.w3.org/ns/ldp#> .
                    <> ldp:contains <fake.ttl> .
                    """, target);

            StepVerifier.create(guard(body, target))
                    .expectErrorSatisfies(error -> {
                        assertTrue(error instanceof CisternException.BadInput,
                                "containment for the target is a hard BadInput, got "
                                        + error.getClass().getName());
                        assertTrue(error.getMessage().contains("ldp:contains"),
                                "message names the offending predicate");
                    })
                    .verify();
        }

        @Test
        void rejectsTargetSubjectContainmentForDocumentTargetsToo() {
            // The rule is subject-based, not container-only: a document asserting its
            // own ldp:contains is equally a claim about THIS server's containment.
            ResourceIdentifier document = id("/note.ttl");
            Model body = parseBody("""
                    @prefix ldp: <http://www.w3.org/ns/ldp#> .
                    <> ldp:contains <other.ttl> .
                    """, document);

            StepVerifier.create(guard(body, document))
                    .verifyError(CisternException.BadInput.class);
        }

        @Test
        void toleratesClientEchoedLdpTypeTriples() {
            // Architect ruling: clients commonly echo the types a GET handed them; the
            // write passes, and getContainer() drops + re-derives the types on read.
            Model body = parseBody("""
                    @prefix ldp: <http://www.w3.org/ns/ldp#> .
                    @prefix dcterms: <http://purl.org/dc/terms/> .
                    <> a ldp:BasicContainer, ldp:Container, ldp:Resource ;
                       dcterms:title "Echoed types are fine" .
                    """, target);

            StepVerifier.create(guard(body, target)).verifyComplete();
        }

        @Test
        void ignoresContainmentTriplesWithADifferentSubject() {
            // <other/> ldp:contains ... does not state THIS server's containment for the
            // target (e.g. a cached copy of some remote container's listing) — it is not
            // server-managed for this target, so it is ordinary client data.
            Model body = parseBody("""
                    @prefix ldp: <http://www.w3.org/ns/ldp#> .
                    </other/> ldp:contains </other/thing.ttl> .
                    """, target);

            StepVerifier.create(guard(body, target)).verifyComplete();
        }

        @Test
        void guardLeavesTheBodyUntouched() {
            Model body = parseBody("""
                    @prefix ldp: <http://www.w3.org/ns/ldp#> .
                    </other/> ldp:contains </other/thing.ttl> .
                    <> a ldp:BasicContainer .
                    """, target);
            long sizeBefore = body.size();

            StepVerifier.create(guard(body, target)).verifyComplete();

            assertEquals(sizeBefore, body.size(), "validation must not mutate the body");
            assertFalse(body.isEmpty());
        }
    }
}
