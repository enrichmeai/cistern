package com.enrichmeai.cistern.webflux;

import com.enrichmeai.cistern.core.Representation;
import com.enrichmeai.cistern.core.ResourceIdentifier;
import com.enrichmeai.cistern.core.ResourceStore;
import com.enrichmeai.cistern.core.rdf.RdfIo;
import com.enrichmeai.cistern.core.vocab.Pim;
import com.enrichmeai.cistern.webflux.error.ProblemType;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.vocabulary.RDF;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T2.9's discovery surface over real HTTP: the storage description resource of Solid Protocol
 * §4.1, and the two {@code Link} headers that lead a client to it.
 *
 * <p>Every assertion here traces to a sentence of §4.1, quoted at the test that pins it. The
 * three requirements are deliberately tested from the client's side — by following the links
 * rather than by reading the beans — because what §4.1 specifies is a <em>traversal</em>: from
 * any resource, reach the description; from the description, learn which URI is the storage;
 * from that URI's own response, confirm it.
 */
@SpringBootTest(properties = "cistern.base-url=http://localhost:3000")
@AutoConfigureWebTestClient
class StorageDescriptionHttpTest {

    private static final String BASE = "http://localhost:3000";

    /** §4.1 leaves the location to the server; this is Cistern's, and the CSS convention. */
    private static final String WELL_KNOWN = "/.well-known/solid";

    private static final Path STORAGE_ROOT = createTempRoot();

    @Autowired
    private WebTestClient client;

    @Autowired
    private ResourceStore store;

    @Autowired
    private StorageDescription storageDescription;

    @DynamicPropertySource
    static void storageRoot(DynamicPropertyRegistry registry) {
        registry.add("cistern.storage.root", STORAGE_ROOT::toString);
    }

    private static Path createTempRoot() {
        try {
            return Files.createTempDirectory("cistern-t29-");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @BeforeEach
    void seedPod() {
        put("/", turtle(""));
        put("/notes/", turtle(""));
        put("/notes/a.ttl", turtle("<> <https://vocab.example/k> \"v\" ."));
    }

    // ------------------------------------------------- the description resource itself

    /**
     * §4.1: "Servers MUST include statements about the storage as part of the storage description
     * resource", where "Storage description statements include the properties: {@code rdf:type} —
     * A class whose URI is {@code http://www.w3.org/ns/pim/space#Storage}."
     *
     * <p>Asserted as a parsed graph, not as a substring of Turtle: the requirement is about what
     * the document <em>means</em>, and a serializer is free to write the same triple with a
     * prefix, with the full IRI, or as {@code a}.
     */
    @Test
    @DisplayName("§4.1: the description types the storage root as pim:Storage")
    void theDescriptionTypesTheStorageRootAsAStorage() {
        Model graph = getGraph(WELL_KNOWN, RdfSerialization.TURTLE);

        assertTrue(graph.contains(graph.createResource(BASE + "/"), RDF.type, Pim.STORAGE),
                "the description MUST state <" + BASE + "/> a pim:Storage; got:\n" + asTurtle(graph));
    }

    /**
     * Solid Protocol §5.5: "the server MUST satisfy {@code GET} requests on this resource when
     * the field value of the {@code Accept} header field requests a representation in
     * {@code text/turtle} or {@code application/ld+json}". The description is an RDF source like
     * any other, so it goes through the same negotiation and both forms carry the same triple.
     */
    @Test
    @DisplayName("§5.5: Turtle and JSON-LD are both serviceable, and say the same thing")
    void bothRdfSerializationsAreServed() {
        for (RdfSerialization serialization : RdfSerialization.values()) {
            Model graph = getGraph(WELL_KNOWN, serialization);

            assertTrue(graph.contains(graph.createResource(BASE + "/"), RDF.type, Pim.STORAGE),
                    serialization + " must carry the storage statement");
        }
    }

    /** LDP 1.0 §4.3.2.2: no {@code Accept} means Turtle, as everywhere else in Cistern. */
    @Test
    @DisplayName("no Accept header serves Turtle")
    void turtleIsTheDefault() {
        client.get().uri(WELL_KNOWN).exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(RdfSerialization.TURTLE.mediaType());
    }

    /**
     * The description has two representations, so a shared cache must key on {@code Accept}
     * (RFC 9110 §12.5.5) — the same reason {@code ResourceReadHandler} sets it for any RDF source.
     *
     * <p>Asserted as membership rather than equality because T2.8's {@code OriginVaryFilter} adds
     * {@code Origin} on the way out, which Solid Protocol §8.1 requires. That the two compose on
     * this route as they do on the others is the substance of the test: a handler that had
     * bypassed the filter chain would show {@code Vary: Accept} alone.
     */
    @Test
    @DisplayName("the negotiated response varies by Accept, and still by Origin (§8.1)")
    void theResponseVariesByAccept() {
        List<String> vary = client.get().uri(WELL_KNOWN).exchange()
                .expectStatus().isOk()
                .expectBody().returnResult()
                .getResponseHeaders().getOrEmpty(HttpHeaders.VARY);

        assertTrue(vary.contains(HttpHeaders.ACCEPT),
                "two representations are available, so the cache key includes Accept; got " + vary);
        assertTrue(vary.contains(HttpHeaders.ORIGIN),
                "§8.1 requires Origin in Vary, and T2.8's filter must reach this route too; got "
                        + vary);
    }

    /**
     * Negotiation is the shared one, so its refusal is the shared one too: an {@code Accept} that
     * excludes both RDF forms is a 406 rendered by T2.6's single error mapper, not a special case
     * written into this handler.
     */
    @Test
    @DisplayName("an Accept that excludes both RDF forms is a 406 from the one error mapper")
    void anUnsatisfiableAcceptIsNotAcceptable() {
        client.get().uri(WELL_KNOWN)
                .accept(MediaType.IMAGE_PNG)
                .exchange()
                .expectStatus().isEqualTo(ProblemType.NOT_ACCEPTABLE.status())
                .expectHeader().contentType(com.enrichmeai.cistern.webflux.error.ProblemDocument.MEDIA_TYPE);
    }

    /** RFC 9110 §9.3.2: {@code HEAD} is {@code GET} minus the content, here as everywhere. */
    @Test
    @DisplayName("HEAD on the description sends GET's headers and no body")
    void headIsGetMinusTheBody() {
        EntityExchangeResult<byte[]> get = client.get().uri(WELL_KNOWN)
                .exchange().expectStatus().isOk().expectBody().returnResult();
        EntityExchangeResult<byte[]> head = client.head().uri(URI.create(WELL_KNOWN))
                .exchange().expectStatus().isOk().expectBody().returnResult();

        for (String header : List.of(HttpHeaders.CONTENT_TYPE, HttpHeaders.CONTENT_LENGTH,
                HttpHeaders.ALLOW, HttpHeaders.LINK, HttpHeaders.VARY)) {
            assertEquals(get.getResponseHeaders().get(header),
                    head.getResponseHeaders().get(header),
                    "RFC 9110 §9.3.2: HEAD sends the same header fields as GET — " + header);
        }
        byte[] body = head.getResponseBodyContent();
        assertTrue(body == null || body.length == 0, "HEAD MUST NOT send content");
    }

    /**
     * Solid Protocol §5.2 requires {@code Allow} to "indicate the HTTP methods supported by the
     * target resource", and the {@code Accept-*} fields to "correspond to acceptable HTTP methods
     * listed in {@code Allow}". The description supports the mandatory trio and no write method,
     * so it advertises exactly that and none of the {@code Accept-*} fields.
     */
    @Test
    @DisplayName("§5.2: the description advertises GET, HEAD, OPTIONS and no Accept-* fields")
    void theDescriptionAdvertisesTheReadMethodsOnly() {
        for (HttpMethod method : List.of(HttpMethod.GET, HttpMethod.OPTIONS)) {
            HttpHeaders headers = client.method(method).uri(WELL_KNOWN)
                    .exchange().expectBody().returnResult().getResponseHeaders();

            assertEquals(HttpConstants.allow(StorageDescriptionHandler.METHODS),
                    headers.getFirst(HttpHeaders.ALLOW), "Allow on " + method);
            assertNull(headers.getFirst(HttpConstants.ACCEPT_PUT), "no PUT in Allow");
            assertNull(headers.getFirst(HttpConstants.ACCEPT_POST), "no POST in Allow");
            assertNull(headers.getFirst(HttpHeaders.ACCEPT_PATCH), "no PATCH in Allow");
        }
    }

    /** RFC 9110 §9.3.7 with no representation selected — 204, as {@code ResourceOptionsHandler}. */
    @Test
    @DisplayName("OPTIONS on the description is a 204 carrying the discovery links")
    void optionsOnTheDescription() {
        HttpHeaders headers = client.options().uri(WELL_KNOWN)
                .exchange().expectStatus().isNoContent()
                .expectBody().isEmpty().getResponseHeaders();

        assertTrue(headers.getOrEmpty(HttpHeaders.LINK).contains(storageDescription.linkValue()),
                "§4.1 names OPTIONS among the methods whose response carries the link");
        assertNull(headers.getFirst(HttpHeaders.ETAG),
                "no representation was selected, so there is no validator to describe");
    }

    // ------------------------------------------------- the traversal §4.1 specifies

    /**
     * §4.1: "Servers MUST include the {@code Link} header field with
     * {@code rel="http://www.w3.org/ns/solid/terms#storageDescription"} targeting the URI of the
     * storage description resource in the response of HTTP {@code GET}, {@code HEAD} and
     * {@code OPTIONS} requests targeting a resource in a storage."
     *
     * <p>All three methods, on a resource of every kind — the requirement names the methods
     * explicitly and scopes them to "a resource", not to the root.
     */
    @Test
    @DisplayName("§4.1: GET, HEAD and OPTIONS on any resource all carry the link")
    void allThreeMethodsCarryTheLinkOnEveryResource() {
        for (String path : List.of("/", "/notes/", "/notes/a.ttl", WELL_KNOWN)) {
            for (HttpMethod method : List.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.OPTIONS)) {
                List<String> links = client.method(method).uri(path)
                        .exchange().expectBody().returnResult()
                        .getResponseHeaders().getOrEmpty(HttpHeaders.LINK);

                assertTrue(links.contains(storageDescription.linkValue()),
                        method + " " + path + " must carry the storage-description link; got "
                                + links);
            }
        }
    }

    /**
     * The end-to-end traversal: start at an arbitrary resource, read the link, fetch what it
     * names, and learn the storage's URI from the graph — then confirm that URI announces itself
     * as the storage. Nothing in this test knows {@value #WELL_KNOWN}; that is the point, since
     * §4.1 specifies the link and leaves the location to the server.
     */
    @Test
    @DisplayName("a client can reach the storage root from any resource by following §4.1's links")
    void followingTheLinksFromAnyResourceFindsTheStorage() {
        List<String> links = client.get().uri("/notes/a.ttl")
                .exchange().expectBody().returnResult()
                .getResponseHeaders().getOrEmpty(HttpHeaders.LINK);

        List<String> targets = LinkHeader.targetsWithRelation(links,
                LinkRelation.STORAGE_DESCRIPTION);
        assertEquals(1, targets.size(), "exactly one storage description is named; got " + links);

        Model description = getGraph(URI.create(targets.get(0)).getPath(), RdfSerialization.TURTLE);
        List<String> storages = description.listResourcesWithProperty(RDF.type, Pim.STORAGE)
                .toList().stream().map(resource -> resource.getURI()).toList();
        assertEquals(List.of(BASE + "/"), storages,
                "the description names the storage; got:\n" + asTurtle(description));

        // "Clients can determine a resource is of type storage by making an HTTP HEAD or GET
        // request on the target URL, and checking for the Link header field with rel="type"
        // targeting http://www.w3.org/ns/pim/space#Storage."
        List<String> rootLinks = client.head().uri(URI.create(URI.create(storages.get(0)).getPath()))
                .exchange().expectBody().returnResult()
                .getResponseHeaders().getOrEmpty(HttpHeaders.LINK);
        assertTrue(LinkHeader.targetsWithRelation(rootLinks, LinkRelation.TYPE)
                        .contains(Pim.STORAGE.getURI()),
                "the URI the description names MUST advertise rel=\"type\" pim:Storage; got "
                        + rootLinks);
    }

    /**
     * §4.1's hierarchy walk: "Clients can determine the storage of a resource by moving up the
     * URI path hierarchy until the response includes a {@code Link} header field with
     * {@code rel="type"} targeting {@code http://www.w3.org/ns/pim/space#Storage}." The walk is
     * only guaranteed to terminate if exactly one ancestor carries it, which is what this pins.
     */
    @Test
    @DisplayName("§4.1: walking up from a deep resource stops at the root and nowhere else")
    void theHierarchyWalkStopsAtTheRootAndNowhereElse() {
        put("/notes/deep/", turtle(""));
        put("/notes/deep/x.ttl", turtle(""));

        List<String> stops = List.of("/notes/deep/x.ttl", "/notes/deep/", "/notes/", "/").stream()
                .filter(this::advertisesStorageType)
                .toList();

        assertEquals(List.of("/"), stops,
                "exactly one ancestor may claim pim:Storage, or the walk is ambiguous");
    }

    private boolean advertisesStorageType(String path) {
        List<String> links = client.head().uri(URI.create(path))
                .exchange().expectBody().returnResult()
                .getResponseHeaders().getOrEmpty(HttpHeaders.LINK);
        return LinkHeader.targetsWithRelation(links, LinkRelation.TYPE)
                .contains(Pim.STORAGE.getURI());
    }

    // ------------------------------------------------- routing

    /**
     * The description route is registered ahead of the catch-all, so it answers rather than
     * falling through to {@code LdpService}, which holds nothing at this path and would 404.
     */
    @Test
    @DisplayName("the description is served even though nothing is stored at its path")
    void theDescriptionIsNotAStoredResource() {
        StepVerifier.create(store.get(new ResourceIdentifier(URI.create(BASE + WELL_KNOWN))))
                .verifyComplete();

        client.get().uri(WELL_KNOWN).exchange().expectStatus().isOk();
    }

    // ------------------------------------------------- helpers

    private Model getGraph(String path, RdfSerialization serialization) {
        EntityExchangeResult<byte[]> result = client.get().uri(path)
                .accept(serialization.mediaType())
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(serialization.mediaType())
                .expectBody().returnResult();

        byte[] body = result.getResponseBodyContent();
        assertNotNull(body, "a GET of " + path + " must have a body");
        return RdfIo.parse(new Representation(serialization.contentType(), body),
                new ResourceIdentifier(URI.create(BASE + path)));
    }

    private static String asTurtle(Model graph) {
        return new String(RdfIo.serialize(graph, Representation.TURTLE).data(),
                StandardCharsets.UTF_8);
    }

    private void put(String path, Representation representation) {
        StepVerifier.create(store.put(new ResourceIdentifier(URI.create(BASE + path)),
                representation)).expectNextCount(1).verifyComplete();
    }

    private static Representation turtle(String body) {
        return new Representation(Representation.TURTLE, body.getBytes(StandardCharsets.UTF_8));
    }
}
