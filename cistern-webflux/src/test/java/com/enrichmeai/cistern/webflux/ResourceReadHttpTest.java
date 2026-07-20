package com.enrichmeai.cistern.webflux;

import com.enrichmeai.cistern.core.Representation;
import com.enrichmeai.cistern.core.ResourceIdentifier;
import com.enrichmeai.cistern.core.ResourceStore;
import com.enrichmeai.cistern.core.StoredResource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
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
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T2.1 over real HTTP: {@code GET}/{@code HEAD} through the whole stack — router, handler,
 * negotiation, {@code LdpService}, and the production {@code FileResourceStore} on a temp
 * directory. Nothing is mocked, so what these assertions pin is what a client sees.
 *
 * <p>Header expectations and their sources:
 * <ul>
 *   <li>Turtle/JSON-LD both serviceable for any RDF source, Turtle by default — Solid
 *       Protocol §5.5, LDP 1.0 §4.3.2.2.</li>
 *   <li>{@code Link: <...ldp#Resource>; rel="type"} on every resource and
 *       {@code ...ldp#BasicContainer} additionally on containers — LDP 1.0 §4.2.1.4 and
 *       §5.2.1.4, with Solid Protocol §4.2 fixing the container type.</li>
 *   <li>{@code Allow} plus matching {@code Accept-Put}/{@code Accept-Post}/
 *       {@code Accept-Patch} — Solid Protocol §5.2.</li>
 *   <li>Strong {@code ETag} and second-precision {@code Last-Modified} — RFC 9110 §8.8.3,
 *       §8.8.2.</li>
 *   <li>{@code HEAD} identical to {@code GET} minus the body — RFC 9110 §9.3.2.</li>
 * </ul>
 *
 * <p>The 4xx statuses come from {@link PlaceholderErrorMapping} until T2.6's real mapper
 * lands; the assertions themselves do not change when it does.
 */
@SpringBootTest(properties = "cistern.base-url=http://localhost:3000")
@AutoConfigureWebTestClient
@Import(PlaceholderErrorMapping.class)
class ResourceReadHttpTest {

    private static final String BASE = "http://localhost:3000";
    private static final String LDP_RESOURCE = "<http://www.w3.org/ns/ldp#Resource>; rel=\"type\"";
    private static final String LDP_BASIC_CONTAINER =
            "<http://www.w3.org/ns/ldp#BasicContainer>; rel=\"type\"";

    private static final Path STORAGE_ROOT = createTempRoot();

    private static final byte[] PNG_BYTES = {
            (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x01, 0x02, 0x03};

    @Autowired
    private WebTestClient client;

    @Autowired
    private ResourceStore store;

    @DynamicPropertySource
    static void storageRoot(DynamicPropertyRegistry registry) {
        registry.add("cistern.storage.root", STORAGE_ROOT::toString);
    }

    private static Path createTempRoot() {
        try {
            return Files.createTempDirectory("cistern-t21-");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---------------------------------------------------------------- fixtures

    /**
     * Seeded through the real backend, because there is no {@code PUT} endpoint until T2.2.
     * Re-seeding is idempotent (put creates or replaces), so every test sees the same pod.
     */
    @BeforeEach
    void seedPod() {
        put("/", turtle(""));
        put("/notes/", turtle("""
                @prefix dcterms: <http://purl.org/dc/terms/> .
                <> dcterms:title "My notes" .
                """));
        put("/notes/a.ttl", turtle("<> <https://vocab.example/k> \"v\" ."));
        put("/logo.png", new Representation("image/png", PNG_BYTES));
    }

    private static Representation turtle(String content) {
        return new Representation(Representation.TURTLE, content.getBytes(StandardCharsets.UTF_8));
    }

    private StoredResource put(String path, Representation representation) {
        AtomicReference<StoredResource> kept = new AtomicReference<>();
        StepVerifier.create(store.put(id(path), representation).doOnNext(kept::set))
                .expectNextCount(1)
                .verifyComplete();
        return kept.get();
    }

    private static ResourceIdentifier id(String path) {
        return new ResourceIdentifier(URI.create(BASE + path));
    }

    /** {@code uri(URI)} rather than {@code uri(String)}: no template expansion, no re-encoding. */
    private WebTestClient.RequestHeadersSpec<?> get(String rawPath) {
        return client.get().uri(URI.create(rawPath));
    }

    private static String body(EntityExchangeResult<byte[]> result) {
        return new String(result.getResponseBodyContent(), StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------- negotiation

    @Test
    void rdfDocumentDefaultsToTurtleWhenAcceptIsAbsent() {
        EntityExchangeResult<byte[]> result = get("/notes/a.ttl")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(RdfMediaTypes.TURTLE)
                .expectBody().returnResult();

        assertTrue(body(result).contains("https://vocab.example/k"),
                "the stored triple must be in the served Turtle");
    }

    @Test
    void wildcardAcceptAlsoYieldsTurtle() {
        get("/notes/a.ttl")
                .header(HttpHeaders.ACCEPT, "*/*")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(RdfMediaTypes.TURTLE);
    }

    @Test
    void jsonLdIsServedWhenAsked() {
        EntityExchangeResult<byte[]> result = get("/notes/a.ttl")
                .header(HttpHeaders.ACCEPT, Representation.JSON_LD)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(RdfMediaTypes.JSON_LD)
                .expectBody().returnResult();

        String json = body(result);
        assertTrue(json.trim().startsWith("{") || json.trim().startsWith("["),
                "a Turtle-stored document must still be serviceable as JSON-LD"
                        + " (Solid Protocol §5.5); got: " + json);
        assertTrue(json.contains("https://vocab.example/k"));
    }

    @Test
    void qValuesDecideBetweenTheTwoRdfTypes() {
        get("/notes/a.ttl")
                .header(HttpHeaders.ACCEPT, "text/turtle;q=0.5, application/ld+json;q=0.9")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(RdfMediaTypes.JSON_LD);
    }

    @Test
    void aMoreSpecificRangeOverridesAWildcardRange() {
        // */* would allow Turtle, but the specific range excludes it (q=0), so JSON-LD wins.
        get("/notes/a.ttl")
                .header(HttpHeaders.ACCEPT, "*/*;q=0.8, text/turtle;q=0")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(RdfMediaTypes.JSON_LD);
    }

    @Test
    void unsatisfiableAcceptOnAnRdfSourceIsNotAcceptable() {
        get("/notes/a.ttl")
                .header(HttpHeaders.ACCEPT, "application/xml")
                .exchange()
                .expectStatus().isEqualTo(406);
    }

    @Test
    void containerNegotiatesTheSameWayADocumentDoes() {
        get("/notes/")
                .header(HttpHeaders.ACCEPT, Representation.JSON_LD)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(RdfMediaTypes.JSON_LD);
    }

    // ---------------------------------------------------------------- containers

    @Test
    void containerServesDerivedContainmentAndServerAssertedTypes() {
        EntityExchangeResult<byte[]> result = get("/notes/")
                .exchange()
                .expectStatus().isOk()
                .expectBody().returnResult();

        String turtle = body(result);
        assertTrue(turtle.contains(BASE + "/notes/a.ttl"),
                "ldp:contains must list the live child (Solid Protocol §4.2); got: " + turtle);
        assertTrue(turtle.contains("ldp#BasicContainer") || turtle.contains("BasicContainer"),
                "the container's server-asserted LDP types must be in the body; got: " + turtle);
        assertTrue(turtle.contains("My notes"), "client-authored triples survive");
    }

    @Test
    void rootContainerIsReadable() {
        EntityExchangeResult<byte[]> result = get("/")
                .exchange()
                .expectStatus().isOk()
                .expectBody().returnResult();

        assertTrue(body(result).contains(BASE + "/notes/"),
                "the storage root lists its children like any other container");
    }

    // ---------------------------------------------------------------- Link rel="type"

    @Test
    void everyResourceAdvertisesLdpResource() {
        get("/notes/a.ttl").exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.LINK, LDP_RESOURCE);
    }

    @Test
    void binaryResourceAlsoAdvertisesLdpResource() {
        get("/logo.png").exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.LINK, LDP_RESOURCE);
    }

    @Test
    void containerAdditionallyAdvertisesBasicContainer() {
        get("/notes/").exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.LINK, LDP_RESOURCE, LDP_BASIC_CONTAINER);
    }

    @Test
    void documentDoesNotAdvertiseBasicContainer() {
        EntityExchangeResult<byte[]> result = get("/notes/a.ttl")
                .exchange().expectBody().returnResult();

        List<String> links = result.getResponseHeaders().getOrEmpty(HttpHeaders.LINK);
        assertTrue(links.stream().noneMatch(link -> link.contains("BasicContainer")),
                "a document is not a container: " + links);
    }

    // ---------------------------------------------------------------- validators

    @Test
    void etagIsTheStoredStrongValidatorQuoted() {
        StoredResource stored = put("/notes/a.ttl", turtle("<> <https://vocab.example/k> \"v2\" ."));

        get("/notes/a.ttl").exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.ETAG, "\"" + stored.etag() + "\"");
    }

    @Test
    void etagIsStrongNotWeak() {
        EntityExchangeResult<byte[]> result = get("/notes/a.ttl")
                .exchange().expectBody().returnResult();

        String etag = result.getResponseHeaders().getFirst(HttpHeaders.ETAG);
        assertNotNull(etag);
        assertTrue(etag.startsWith("\"") && etag.endsWith("\""),
                "RFC 9110 §8.8.3: a strong validator carries no W/ prefix; got " + etag);
    }

    @Test
    void lastModifiedIsAnImfFixdateMatchingTheStoredInstant() {
        StoredResource stored = put("/notes/a.ttl", turtle("<> <https://vocab.example/k> \"v3\" ."));

        EntityExchangeResult<byte[]> result = get("/notes/a.ttl")
                .exchange().expectBody().returnResult();

        String lastModified = result.getResponseHeaders().getFirst(HttpHeaders.LAST_MODIFIED);
        assertNotNull(lastModified, "RFC 9110 §8.8.2");
        // Parses as an IMF-fixdate and, at second resolution, is the store's instant.
        ZonedDateTime parsed = ZonedDateTime.parse(lastModified, DateTimeFormatter.RFC_1123_DATE_TIME);
        assertEquals(stored.lastModified(), parsed.toInstant());
    }

    // ---------------------------------------------------------------- Allow / Accept-*

    @Test
    void containerAdvertisesItsMethodsAndAcceptableMediaTypes() {
        get("/notes/").exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.ALLOW,
                        "GET, HEAD, OPTIONS, POST, PUT, PATCH, DELETE")
                .expectHeader().valueEquals(HttpConstants.ACCEPT_PUT,
                        "text/turtle, application/ld+json")
                .expectHeader().valueEquals(HttpConstants.ACCEPT_POST, "*/*")
                .expectHeader().valueEquals(HttpHeaders.ACCEPT_PATCH, "text/n3");
    }

    @Test
    void rdfDocumentAdvertisesPatchButNotPost() {
        EntityExchangeResult<byte[]> result = get("/notes/a.ttl")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.ALLOW,
                        "GET, HEAD, OPTIONS, PUT, PATCH, DELETE")
                .expectHeader().valueEquals(HttpConstants.ACCEPT_PUT, "*/*")
                .expectHeader().valueEquals(HttpHeaders.ACCEPT_PATCH, "text/n3")
                .expectBody().returnResult();

        assertNull(result.getResponseHeaders().getFirst(HttpConstants.ACCEPT_POST),
                "only containers accept POST, so only containers advertise Accept-Post"
                        + " (Solid Protocol §5.2)");
    }

    @Test
    void binaryResourceAdvertisesNeitherPatchNorPost() {
        EntityExchangeResult<byte[]> result = get("/logo.png")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.ALLOW, "GET, HEAD, OPTIONS, PUT, DELETE")
                .expectHeader().valueEquals(HttpConstants.ACCEPT_PUT, "*/*")
                .expectBody().returnResult();

        HttpHeaders headers = result.getResponseHeaders();
        assertNull(headers.getFirst(HttpHeaders.ACCEPT_PATCH),
                "there is no graph in a binary resource to patch");
        assertNull(headers.getFirst(HttpConstants.ACCEPT_POST));
    }

    // ---------------------------------------------------------------- non-RDF

    @Test
    void nonRdfResourceIsServedVerbatimWithItsStoredContentType() {
        EntityExchangeResult<byte[]> result = get("/logo.png")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType("image/png")
                .expectBody().returnResult();

        assertArrayEquals(PNG_BYTES, result.getResponseBodyContent(),
                "binary resources are copied out byte for byte — never parsed, never converted");
    }

    @Test
    void nonRdfResourceIsNotConvertedToTurtleOnRequest() {
        get("/logo.png")
                .header(HttpHeaders.ACCEPT, Representation.TURTLE)
                .exchange()
                .expectStatus().isEqualTo(406);
    }

    @Test
    void nonRdfResourceIsServedWhenItsOwnTypeIsAcceptable() {
        get("/logo.png")
                .header(HttpHeaders.ACCEPT, "image/png")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType("image/png");
    }

    // ---------------------------------------------------------------- HEAD

    @Test
    void headIsGetMinusTheBody() {
        EntityExchangeResult<byte[]> getResult = get("/notes/a.ttl")
                .exchange().expectBody().returnResult();
        EntityExchangeResult<byte[]> headResult = client.head().uri(URI.create("/notes/a.ttl"))
                .exchange().expectStatus().isOk().expectBody().returnResult();

        HttpHeaders getHeaders = getResult.getResponseHeaders();
        HttpHeaders headHeaders = headResult.getResponseHeaders();
        for (String header : List.of(HttpHeaders.CONTENT_TYPE, HttpHeaders.CONTENT_LENGTH,
                HttpHeaders.ETAG, HttpHeaders.LAST_MODIFIED, HttpHeaders.ALLOW,
                HttpHeaders.LINK, HttpConstants.ACCEPT_PUT, HttpHeaders.ACCEPT_PATCH)) {
            assertEquals(getHeaders.get(header), headHeaders.get(header),
                    "RFC 9110 §9.3.2: HEAD sends the same header fields as GET — " + header);
        }
        byte[] headBody = headResult.getResponseBodyContent();
        assertTrue(headBody == null || headBody.length == 0, "HEAD MUST NOT send content");
        assertEquals(String.valueOf(getResult.getResponseBodyContent().length),
                headHeaders.getFirst(HttpHeaders.CONTENT_LENGTH),
                "Content-Length must be the length GET would have sent");
    }

    @Test
    void headOnAContainerCarriesTheContainerHeaders() {
        client.head().uri(URI.create("/notes/"))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.LINK, LDP_RESOURCE, LDP_BASIC_CONTAINER)
                .expectHeader().valueEquals(HttpHeaders.ALLOW,
                        "GET, HEAD, OPTIONS, POST, PUT, PATCH, DELETE");
    }

    @Test
    void headHonoursContentNegotiationToo() {
        client.head().uri(URI.create("/notes/a.ttl"))
                .header(HttpHeaders.ACCEPT, Representation.JSON_LD)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(RdfMediaTypes.JSON_LD);
    }

    @Test
    void headOfAMissingResourceIsNotFoundToo() {
        client.head().uri(URI.create("/nope.ttl")).exchange().expectStatus().isNotFound();
    }

    // ---------------------------------------------------------------- slash semantics

    @Test
    void trailingSlashSelectsTheContainerAndItsAbsenceTheDocument() {
        put("/thing", turtle("<> <https://vocab.example/kind> \"document\" ."));
        put("/thing-c/", turtle("<> <https://vocab.example/kind> \"container\" ."));

        EntityExchangeResult<byte[]> document = get("/thing").exchange()
                .expectStatus().isOk().expectBody().returnResult();
        EntityExchangeResult<byte[]> container = get("/thing-c/").exchange()
                .expectStatus().isOk().expectBody().returnResult();

        assertTrue(body(document).contains("document"));
        assertTrue(body(container).contains("container"));
        assertTrue(document.getResponseHeaders().getOrEmpty(HttpHeaders.LINK).stream()
                        .noneMatch(link -> link.contains("BasicContainer")),
                "/thing is a document (Solid Protocol §3.1)");
        assertTrue(container.getResponseHeaders().getOrEmpty(HttpHeaders.LINK).stream()
                        .anyMatch(link -> link.contains("BasicContainer")),
                "/thing-c/ is a container (Solid Protocol §3.1)");
    }

    @Test
    void containerUriDoesNotResolveToTheSameNamedDocument() {
        put("/only-document", turtle(""));

        get("/only-document/").exchange().expectStatus().isNotFound();
    }

    // ---------------------------------------------------------------- rejected targets

    @Test
    void missingResourceIsNotFound() {
        get("/nope.ttl").exchange().expectStatus().isNotFound();
    }

    @Test
    void encodedSlashInASegmentIsRejectedRatherThanPassedToTheStore() {
        // FileResourceStore signals IllegalArgumentException for these (→ 500) and
        // ResourceIdentifier.isContainer() reads the DECODED path, so raw and decoded slash
        // structure would disagree. The HTTP layer refuses the target instead: 400, not 500.
        get("/notes/a%2Fb.ttl").exchange().expectStatus().isBadRequest();
    }

    @Test
    void lowercaseEncodedSlashIsRejectedToo() {
        get("/notes/a%2fb.ttl").exchange().expectStatus().isBadRequest();
    }

    @Test
    void emptyPathSegmentIsRejected() {
        get("/notes//a.ttl").exchange().expectStatus().isBadRequest();
    }
}
