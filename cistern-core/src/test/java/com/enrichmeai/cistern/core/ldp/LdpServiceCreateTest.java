package com.enrichmeai.cistern.core.ldp;

import com.enrichmeai.cistern.core.CisternException;
import com.enrichmeai.cistern.core.InMemoryResourceStore;
import com.enrichmeai.cistern.core.Representation;
import com.enrichmeai.cistern.core.ResourceIdentifier;
import com.enrichmeai.cistern.core.StoredResource;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T2.3's core create path: {@link LdpService#createIn} — the single operation the HTTP layer
 * (and later MCP) calls to serve a {@code POST}.
 *
 * <p>What is pinned here is the LDP/Solid semantics of creating a resource in a container,
 * because the handler above it is deliberately incapable of expressing any of it:
 * <ul>
 *   <li>which URI gets minted from a {@link Slug} hint, and which gets minted without one
 *       (RFC 5023 §9.7, LDP §5.2.3.8 and §5.2.3.10);</li>
 *   <li>that a taken name is never overwritten, in either of its two spellings — Solid Protocol
 *       §3.1 makes {@code /c/n} and {@code /c/n/} one name;</li>
 *   <li>the requested interaction model (LDP §5.2.3.4): a child container gets a trailing slash
 *       (Solid Protocol §3.1) and a document does not;</li>
 *   <li>the two refusals of Solid Protocol §5.3 — 404 for a target with no representation, 405
 *       for a target that is not a container — and the order they are decided in;</li>
 *   <li>that the body is validated and based against the <em>created</em> resource (LDP
 *       §4.2.1.5, §5.2.3.7).</li>
 * </ul>
 *
 * <p>Every case runs against {@link InMemoryResourceStore}, the contract-kit-verified reference
 * backend, so what these tests pin is the service's behaviour over a conformant store rather
 * than over a mock built from a guess (ground rule 6).
 */
class LdpServiceCreateTest {

    private static final String BASE = "https://pod.example";

    private static final String TURTLE_BODY = "<> <https://vocab.example/k> \"v\" .";

    private static final byte[] ORIGINAL_BYTES = {'o', 'r', 'i', 'g', 'i', 'n', 'a', 'l'};
    private static final byte[] INTRUDER_BYTES = {'i', 'n', 't', 'r', 'u', 'd', 'e', 'r'};

    /** {@link LdpService}'s generated names: lower-case alphanumerics, fixed length. */
    private static final int GENERATED_NAME_LENGTH = 22;

    private InMemoryResourceStore store;
    private LdpService service;

    @BeforeEach
    void freshService() {
        store = new InMemoryResourceStore();
        service = new LdpService(store);
        // Every case needs a container to post into; creating a document inside it also creates
        // the intermediate containers, which is T2.2's job and is asserted there.
        seed("/c/seed.ttl", turtle(TURTLE_BODY));
    }

    // ---------------------------------------------------------------- helpers

    private static ResourceIdentifier id(String path) {
        return new ResourceIdentifier(URI.create(BASE + path));
    }

    private static Representation turtle(String content) {
        return new Representation(Representation.TURTLE, content.getBytes(StandardCharsets.UTF_8));
    }

    private static Representation binary(byte[] bytes) {
        return new Representation("application/octet-stream", bytes);
    }

    private void seed(String path, Representation representation) {
        awaitOne(service.put(id(path), representation), "seed " + path);
    }

    /** Creates a document with no name hint. */
    private ResourceView create(String containerPath, Representation representation) {
        return create(containerPath, null, InteractionModel.RESOURCE, representation);
    }

    private ResourceView create(String containerPath, String slug, InteractionModel model,
                                Representation representation) {
        return awaitOne(
                service.createIn(id(containerPath), Slug.from(slug), model, representation),
                "createIn " + containerPath);
    }

    private void expectError(String containerPath, String slug, InteractionModel model,
                             Representation representation,
                             Class<? extends Throwable> expected) {
        StepVerifier.create(service.createIn(id(containerPath), Slug.from(slug), model, representation))
                .expectError(expected)
                .verify();
    }

    /** The path of a created resource, relative to the base — what a Location would carry. */
    private static String pathOf(ResourceView view) {
        return view.identifier().uri().toString().substring(BASE.length());
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

    private byte[] storedBytesOf(String path) {
        return awaitOne(store.get(id(path)).map(StoredResource::representation)
                .map(Representation::data), "stored bytes of " + path);
    }

    /** The single value a Mono emits, captured without blocking (ground rule 3). */
    private static <T> T awaitOne(Mono<T> mono, String what) {
        AtomicReference<T> captured = new AtomicReference<>();
        StepVerifier.create(mono.doOnNext(captured::set)).expectNextCount(1).verifyComplete();
        assertNotNull(captured.get(), what + " must emit a value");
        return captured.get();
    }

    // ---------------------------------------------------------------- naming

    /**
     * RFC 5023 §9.7: the client asks "to use the header's value as part of any URIs that would
     * normally be used to retrieve the to-be-created ... Resource". Nothing else is in the way,
     * so the hint is what gets used.
     */
    @Test
    @DisplayName("a usable Slug becomes the child's name")
    void honoursASlug() {
        ResourceView created = create("/c/", "notes.ttl", InteractionModel.RESOURCE,
                turtle(TURTLE_BODY));

        assertEquals("/c/notes.ttl", pathOf(created));
    }

    /**
     * LDP §5.2.3.8: "LDP servers SHOULD assign the URI for the resource to be created using
     * server application specific rules in the absence of a client hint."
     */
    @Test
    @DisplayName("with no hint the server mints a short, URL-safe, unguessable name")
    void generatesANameWithoutASlug() {
        ResourceView created = create("/c/", turtle(TURTLE_BODY));

        String name = pathOf(created).substring("/c/".length());
        assertEquals(GENERATED_NAME_LENGTH, name.length(), "generated names are fixed-length");
        assertTrue(name.matches("[0-9a-z]+"),
                "a generated name needs no percent-encoding and no case folding: " + name);
    }

    /** Two creates in a row must not fight over one name — the point of generating them. */
    @Test
    @DisplayName("generated names do not repeat")
    void generatedNamesAreDistinct() {
        Set<String> names = new HashSet<>();
        for (int i = 0; i < 25; i++) {
            names.add(pathOf(create("/c/", turtle(TURTLE_BODY))));
        }

        assertEquals(25, names.size(), "every create must land on its own URI");
    }

    /**
     * The sanitization is not merely available to the handler — it is on the path a POST takes,
     * so a hostile hint cannot address anything outside the container it was posted to.
     */
    @Test
    @DisplayName("a traversing Slug still lands inside the container")
    void sanitizesASlugBeforeUsingIt() {
        ResourceView created = create("/c/", "../../etc/passwd", InteractionModel.RESOURCE,
                turtle(TURTLE_BODY));

        assertEquals("/c/etc-passwd", pathOf(created));
        assertTrue(containedIn("/c/").contains(created.identifier().uri().toString()),
                "the created resource must be a child of the container that was posted to");
    }

    /** A hint that sanitizes to nothing is no hint at all — see {@link Slug}. */
    @Test
    @DisplayName("a Slug that sanitizes away falls back to a generated name")
    void generatesANameWhenTheSlugSanitizesAway() {
        ResourceView created = create("/c/", "../", InteractionModel.RESOURCE, turtle(TURTLE_BODY));

        String name = pathOf(created).substring("/c/".length());
        assertEquals(GENERATED_NAME_LENGTH, name.length(), "expected a generated name: " + name);
    }

    // ---------------------------------------------------------------- collisions

    /**
     * The rule with teeth: {@code POST} has no replace semantics, so a taken name must cost the
     * client a different URI and must cost the existing resource nothing at all.
     */
    @Test
    @DisplayName("a Slug collision picks a fresh name and leaves the existing resource untouched")
    void neverOverwritesOnASlugCollision() {
        seed("/c/note", binary(ORIGINAL_BYTES));

        ResourceView created = create("/c/", "note", InteractionModel.RESOURCE,
                binary(INTRUDER_BYTES));

        assertNotEquals("/c/note", pathOf(created), "the taken name must not be reused");
        assertArrayEquals(ORIGINAL_BYTES, storedBytesOf("/c/note"),
                "the resource that held the name must be byte-identical afterwards");
        assertArrayEquals(INTRUDER_BYTES, storedBytesOf(pathOf(created)),
                "the new body belongs to the new name");
    }

    /**
     * Solid Protocol §3.1: if a server has associated a resource with one of {@code /c/n} and
     * {@code /c/n/}, "the other URI MUST NOT correspond to another resource". So a container
     * occupies the name for a document too, and the free spelling is not free.
     */
    @Test
    @DisplayName("an existing container occupies the name for a document as well")
    void treatsBothSpellingsOfANameAsTaken() {
        seed("/c/thing/inner.ttl", turtle(TURTLE_BODY));

        ResourceView created = create("/c/", "thing", InteractionModel.RESOURCE,
                turtle(TURTLE_BODY));

        assertNotEquals("/c/thing", pathOf(created),
                "minting /c/thing beside the container /c/thing/ would break §3.1");
        assertTrue(containedIn("/c/").contains(id("/c/thing/").uri().toString()),
                "the existing container is still there");
    }

    /** The mirror image: an existing document occupies the name for a child container. */
    @Test
    @DisplayName("an existing document occupies the name for a container as well")
    void treatsBothSpellingsOfANameAsTakenWhenCreatingAContainer() {
        seed("/c/thing", binary(ORIGINAL_BYTES));

        ResourceView created = create("/c/", "thing", InteractionModel.BASIC_CONTAINER,
                turtle(TURTLE_BODY));

        assertNotEquals("/c/thing/", pathOf(created));
        assertArrayEquals(ORIGINAL_BYTES, storedBytesOf("/c/thing"));
    }

    // ---------------------------------------------------------------- interaction model

    /**
     * LDP §5.2.3.4 requires the requested interaction model to be honoured; Solid Protocol §3.1
     * fixes what honouring it looks like in a URI — "paths ending with a slash denote a
     * container resource".
     */
    @Test
    @DisplayName("the BasicContainer model creates a child container, with a trailing slash")
    void createsAChildContainer() {
        ResourceView created = create("/c/", "kids", InteractionModel.BASIC_CONTAINER,
                turtle(TURTLE_BODY));

        assertEquals("/c/kids/", pathOf(created));
        assertTrue(created.identifier().isContainer());
        assertTrue(created.container(), "the created resource must read back as a container");
        assertInstanceOf(ResourceView.Rdf.class, created);
    }

    @Test
    @DisplayName("the default model creates a document, with no trailing slash")
    void createsADocumentByDefault() {
        ResourceView created = create("/c/", "doc.ttl", InteractionModel.RESOURCE,
                turtle(TURTLE_BODY));

        assertFalse(created.identifier().isContainer());
        assertFalse(created.container());
    }

    /**
     * LDP §5.2.3.2 requires the containment triple; Cistern derives containment from the store's
     * children on every read, so this asserts the outcome rather than a stored triple.
     */
    @Test
    @DisplayName("the created resource is contained by the container that minted it")
    void addsTheContainmentTriple() {
        ResourceView document = create("/c/", "a.ttl", InteractionModel.RESOURCE, turtle(TURTLE_BODY));
        ResourceView child = create("/c/", "sub", InteractionModel.BASIC_CONTAINER, turtle(""));

        Set<String> contained = containedIn("/c/");
        assertTrue(contained.contains(document.identifier().uri().toString()));
        assertTrue(contained.contains(child.identifier().uri().toString()));
    }

    // ---------------------------------------------------------------- refusals

    /**
     * Solid Protocol §5.3: "When a POST method request targets a resource without an existing
     * representation, the server MUST respond with the 404 status code." Note what this is
     * <em>not</em>: unlike PUT, a POST does not create the container it was addressed to.
     */
    @Test
    @DisplayName("POST to a container that does not exist is a 404, not an implicit create")
    void refusesAContainerWithNoRepresentation() {
        expectError("/absent/", null, InteractionModel.RESOURCE, turtle(TURTLE_BODY),
                CisternException.NotFound.class);

        StepVerifier.create(store.exists(id("/absent/")))
                .expectNext(false)
                .verifyComplete();
    }

    /** The same sentence covers a document-shaped path that names nothing. */
    @Test
    @DisplayName("POST to a path with no representation is a 404 whatever its shape")
    void refusesAnyTargetWithNoRepresentation() {
        expectError("/c/absent", null, InteractionModel.RESOURCE, turtle(TURTLE_BODY),
                CisternException.NotFound.class);
    }

    /**
     * Solid Protocol §5.3 requires creation by POST only "to a URI path ending with /", and §5.2
     * makes the server advertise the methods a resource supports — a document's Allow has no
     * POST in it. RFC 9110 §15.5.6 is the matching status. 405 and not 404, because the resource
     * exists and a GET will serve it.
     */
    @Test
    @DisplayName("POST to an existing document is a 405, not a 404")
    void refusesADocumentTarget() {
        expectError("/c/seed.ttl", null, InteractionModel.RESOURCE, turtle(TURTLE_BODY),
                CisternException.MethodNotAllowed.class);
    }

    /** Existence is checked first, so the two refusals cannot both apply to one request. */
    @Test
    @DisplayName("absence outranks kind: a missing document target is a 404, never a 405")
    void checksExistenceBeforeKind() {
        expectError("/c/never-created.ttl", null, InteractionModel.RESOURCE, turtle(TURTLE_BODY),
                CisternException.NotFound.class);
    }

    /** The storage root is a container like any other; nothing exempts it from POST. */
    @Test
    @DisplayName("the storage root accepts a POST")
    void createsInTheStorageRoot() {
        ResourceView created = create("/", "top.ttl", InteractionModel.RESOURCE, turtle(TURTLE_BODY));

        assertEquals("/top.ttl", pathOf(created));
    }

    // ---------------------------------------------------------------- the body

    /** Reusing {@link LdpService#put} means POST inherits its validation, not a second copy of it. */
    @Test
    @DisplayName("an unparseable RDF body is the client's error, before anything is stored")
    void refusesAMalformedRdfBody() {
        expectError("/c/", "bad.ttl", InteractionModel.RESOURCE, turtle("<not turtle at all"),
                CisternException.BadInput.class);

        StepVerifier.create(store.exists(id("/c/bad.ttl")))
                .expectNext(false)
                .verifyComplete();
    }

    /**
     * Solid Protocol §4.2 — a container's representation is an RDF source — so asking for a
     * child container while offering bytes is a conflict, exactly as it is on PUT.
     */
    @Test
    @DisplayName("a non-RDF body cannot create a container")
    void refusesANonRdfBodyForAContainer() {
        expectError("/c/", "kids", InteractionModel.BASIC_CONTAINER, binary(ORIGINAL_BYTES),
                CisternException.Conflict.class);
    }

    /** Solid Protocol §5.3: containment triples are server-managed, on POST as much as on PUT. */
    @Test
    @DisplayName("a body asserting the new resource's own containment is refused")
    void refusesAServerManagedContainmentTriple() {
        expectError("/c/", "kids", InteractionModel.BASIC_CONTAINER,
                turtle("<> <http://www.w3.org/ns/ldp#contains> <https://pod.example/c/kids/x> ."),
                CisternException.Conflict.class);
    }

    /**
     * LDP §4.2.1.5: "LDP servers MUST assign the default base-URI ... to the URI of the created
     * resource when the request results in the creation of a new resource", which is what makes
     * §5.2.3.7 fall out — a triple whose subject is the null relative URI describes the new
     * resource. The client cannot know that URI in advance, so getting the base wrong would
     * silently mis-subject every POSTed graph.
     */
    @Test
    @DisplayName("the body is based against the created resource, not the container")
    void basesTheBodyOnTheCreatedResource() {
        ResourceView created = create("/c/", "based.ttl", InteractionModel.RESOURCE,
                turtle(TURTLE_BODY));

        ResourceView.Rdf view = assertInstanceOf(ResourceView.Rdf.class, created);
        Resource subject = view.graph().createResource(created.identifier().uri().toString());
        assertTrue(view.graph().listStatements(subject, null, (RDFNode) null).hasNext(),
                "<> in the posted body must resolve to " + created.identifier().uri());
    }

    // ---------------------------------------------------------------- argument contract

    @Test
    @DisplayName("createIn never throws synchronously, whatever it is handed")
    void signalsRatherThanThrows() {
        StepVerifier.create(service.createIn(null, Optional.empty(), InteractionModel.RESOURCE,
                        turtle(TURTLE_BODY)))
                .expectError(NullPointerException.class)
                .verify();
    }
}
