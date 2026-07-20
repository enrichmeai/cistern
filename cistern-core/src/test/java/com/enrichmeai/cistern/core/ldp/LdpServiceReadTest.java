package com.enrichmeai.cistern.core.ldp;

import com.enrichmeai.cistern.core.CisternException;
import com.enrichmeai.cistern.core.InMemoryResourceStore;
import com.enrichmeai.cistern.core.Representation;
import com.enrichmeai.cistern.core.ResourceIdentifier;
import com.enrichmeai.cistern.core.StoredResource;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.DCTerms;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T2.1's core read path: {@link LdpService#read(ResourceIdentifier)} — the single
 * operation the HTTP layer (and later MCP) calls to serve a {@code GET}/{@code HEAD}.
 *
 * <p>What is pinned here is the classification, because everything above it depends on it:
 * containers and RDF documents come back as graphs (Solid Protocol §5.5 — the server must
 * be able to serve both {@code text/turtle} and {@code application/ld+json}, which is only
 * possible from a parsed graph), non-RDF documents come back as untouched bytes, and
 * absence is an explicit {@link CisternException.NotFound} signal rather than an empty
 * Mono.
 */
class LdpServiceReadTest {

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

    private void put(ResourceIdentifier identifier, Representation representation) {
        StepVerifier.create(store.put(identifier, representation))
                .expectNextCount(1)
                .verifyComplete();
    }

    /** Arrange step that keeps the stored metadata, so a test can compare validators. */
    private StoredResource putAndKeep(ResourceIdentifier identifier, Representation representation) {
        AtomicReference<StoredResource> kept = new AtomicReference<>();
        StepVerifier.create(store.put(identifier, representation).doOnNext(kept::set))
                .expectNextCount(1)
                .verifyComplete();
        return kept.get();
    }

    private static Set<String> uriObjects(ResourceView.Rdf view, org.apache.jena.rdf.model.Property p) {
        Resource subject = view.graph().createResource(view.identifier().uri().toString());
        return view.graph().listStatements(subject, p, (RDFNode) null)
                .toList().stream()
                .map(statement -> statement.getObject().asResource().getURI())
                .collect(Collectors.toSet());
    }

    private static <T extends ResourceView> Mono<T> readAs(
            LdpService service, ResourceIdentifier target, Class<T> kind) {
        return service.read(target).map(kind::cast);
    }

    // ---------------------------------------------------------------- containers

    @Test
    void containerReadsAsAnRdfViewCarryingDerivedContainmentAndServerTypes() {
        ResourceIdentifier notes = id("/notes/");
        put(notes, turtle("""
                @prefix dcterms: <http://purl.org/dc/terms/> .
                <> dcterms:title "My notes" .
                """));
        put(id("/notes/a.ttl"), turtle("<#it> a <#Note> ."));
        put(id("/notes/sub/"), turtle(""));

        StepVerifier.create(readAs(service, notes, ResourceView.Rdf.class))
                .assertNext(view -> {
                    assertTrue(view.container(), "a trailing-slash identifier is a container");
                    assertEquals(Set.of(BASE + "/notes/a.ttl", BASE + "/notes/sub/"),
                            uriObjects(view, Ldp.CONTAINS),
                            "read() must show the same live containment getContainer() does");
                    assertEquals(
                            Set.of(Ldp.BASIC_CONTAINER.getURI(), Ldp.CONTAINER.getURI(),
                                    Ldp.RESOURCE.getURI()),
                            uriObjects(view, RDF.type),
                            "server-asserted LDP types (Solid Protocol §4.2)");
                    assertTrue(view.graph().contains(
                                    view.graph().createResource(notes.uri().toString()),
                                    DCTerms.title, "My notes"),
                            "client-authored triples survive");
                })
                .verifyComplete();
    }

    @Test
    void containerViewCarriesTheStoredValidators() {
        ResourceIdentifier notes = id("/notes/");
        StoredResource stored = putAndKeep(notes, turtle(""));

        StepVerifier.create(service.read(notes))
                .assertNext(view -> {
                    assertEquals(stored.etag(), view.etag());
                    assertEquals(stored.lastModified(), view.lastModified());
                })
                .verifyComplete();
    }

    /** read() and getContainer() must not be able to disagree: one merge implementation. */
    @Test
    void readAndGetContainerProduceTheSameGraph() {
        ResourceIdentifier notes = id("/notes/");
        put(notes, turtle("<> <https://vocab.example/k> \"v\" ."));
        put(id("/notes/a.ttl"), turtle(""));

        StepVerifier.create(service.getContainer(notes)
                        .zipWith(readAs(service, notes, ResourceView.Rdf.class)))
                .assertNext(both -> assertTrue(both.getT1().isIsomorphicWith(both.getT2().graph()),
                        "the containment merge has exactly one implementation"))
                .verifyComplete();
    }

    // ---------------------------------------------------------------- documents

    @Test
    void rdfDocumentReadsAsAnRdfViewWithRelativeUrisResolvedAgainstItsOwnUri() {
        ResourceIdentifier note = id("/notes/a.ttl");
        put(note, turtle("<> <https://vocab.example/about> <other.ttl> ."));

        StepVerifier.create(readAs(service, note, ResourceView.Rdf.class))
                .assertNext(view -> {
                    assertFalse(view.container(), "no trailing slash → document");
                    assertTrue(view.graph().contains(
                                    view.graph().createResource(BASE + "/notes/a.ttl"),
                                    view.graph().createProperty("https://vocab.example/about"),
                                    view.graph().createResource(BASE + "/notes/other.ttl")),
                            "relative references resolve against the resource URI (RFC 3986 §5.1.3)");
                })
                .verifyComplete();
    }

    @Test
    void jsonLdDocumentAlsoReadsAsAGraph() {
        ResourceIdentifier note = id("/notes/a.jsonld");
        put(note, new Representation(Representation.JSON_LD, """
                {"@id": "https://pod.example/notes/a.jsonld",
                 "https://vocab.example/k": [{"@value": "v"}]}
                """.getBytes(StandardCharsets.UTF_8)));

        StepVerifier.create(readAs(service, note, ResourceView.Rdf.class))
                .assertNext(view -> assertTrue(view.graph().contains(
                                view.graph().createResource(BASE + "/notes/a.jsonld"),
                                view.graph().createProperty("https://vocab.example/k"), "v"),
                        "both RDF media types parse into the same graph model (Solid Protocol §5.5)"))
                .verifyComplete();
    }

    @Test
    void nonRdfDocumentReadsAsUntouchedBytesWithItsStoredMediaType() {
        ResourceIdentifier image = id("/pics/logo.png");
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        put(image, new Representation("image/png", png));

        StepVerifier.create(readAs(service, image, ResourceView.NonRdf.class))
                .assertNext(view -> {
                    assertFalse(view.container());
                    assertEquals("image/png", view.representation().contentType());
                    assertArrayEquals(png, view.representation().data(),
                            "non-RDF resources are served verbatim — never parsed, never converted");
                })
                .verifyComplete();
    }

    @Test
    void emptyRdfDocumentReadsAsAnEmptyGraphRatherThanFailing() {
        ResourceIdentifier note = id("/notes/empty.ttl");
        put(note, turtle(""));

        StepVerifier.create(readAs(service, note, ResourceView.Rdf.class))
                .assertNext(view -> assertTrue(view.graph().isEmpty()))
                .verifyComplete();
    }

    // ---------------------------------------------------------------- failure signals

    @Test
    void missingResourceSignalsNotFoundRatherThanCompletingEmpty() {
        StepVerifier.create(service.read(id("/nope.ttl")))
                .verifyErrorSatisfies(error -> assertInstanceOf(CisternException.NotFound.class, error,
                        "absence must reach the one error mapper as a domain signal (T2.6 → 404),"
                                + " not as an empty Mono the framework turns into a bare 404"));
    }

    @Test
    void missingContainerAlsoSignalsNotFound() {
        StepVerifier.create(service.read(id("/nope/")))
                .verifyErrorSatisfies(error -> assertInstanceOf(CisternException.NotFound.class, error));
    }

    /**
     * Corrupt stored RDF is a server fault (500), never the reading client's 400: bytes
     * only reach storage through a validated write.
     */
    @Test
    void unparseableStoredRdfSignalsIllegalStateNotBadInput() {
        ResourceIdentifier note = id("/notes/broken.ttl");
        put(note, turtle("this is not turtle at all @@@"));

        StepVerifier.create(service.read(note))
                .verifyErrorSatisfies(error -> {
                    assertInstanceOf(IllegalStateException.class, error);
                    assertFalse(error instanceof CisternException.BadInput);
                });
    }

    /** No method may throw on the calling thread — every condition is a signal. */
    @Test
    void readNeverThrowsSynchronously() {
        service.read(id("/nope.ttl"));      // no subscription: must not throw
        service.read(id("/nope/"));
    }
}
