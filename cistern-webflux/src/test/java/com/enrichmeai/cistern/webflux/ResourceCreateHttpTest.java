package com.enrichmeai.cistern.webflux;

import com.enrichmeai.cistern.core.ldp.Ldp;
import com.enrichmeai.cistern.webflux.error.ProblemDocument;
import com.enrichmeai.cistern.webflux.error.ProblemType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T2.3 over real HTTP: {@code POST} through the whole stack — router, handler, {@code Slug} and
 * {@code Link} parsing, {@code LdpService}, and the production {@code FileResourceStore} on a
 * temp directory. Nothing is mocked, so what these assertions pin is what a client sees.
 *
 * <p>The ticket's DoD matrix — slug honoured, slug collision, generated name, container via
 * {@code Link}, POST to a non-container — plus the rules that make those safe, each traced to
 * its source:
 * <ul>
 *   <li>201 with {@code Location} naming the created resource — LDP §5.2.3.1, RFC 9110 §9.3.3,
 *       §10.2.2, §15.3.2.</li>
 *   <li>{@code Slug} is a hint the server sanitizes and may ignore — RFC 5023 §9.7, LDP
 *       §5.2.3.10; a taken name is never reused — LDP §5.2.3.11, Solid Protocol §3.1.</li>
 *   <li>{@code Link ... rel="type"} selects the interaction model — LDP §5.2.3.4; a container's
 *       URI ends in a slash — Solid Protocol §3.1.</li>
 *   <li>404 for a target with no representation and 405 for a target that is not a container —
 *       Solid Protocol §5.3, §5.2, RFC 9110 §15.5.6.</li>
 * </ul>
 *
 * <p>Every path used here is unique per test, because the store is shared across the class and
 * a create is not idempotent: posting twice with one {@code Slug} is the collision case, which
 * is tested deliberately rather than by accident. Tests that need a pre-existing resource create
 * it themselves, so no ordering dependency exists between them.
 *
 * <p>The 4xx responses come from T2.6's single error mapper and are asserted as full RFC 9457
 * documents rather than as bare status codes — so this class also pins that T2.3 signals domain
 * exceptions rather than writing status codes itself.
 */
@SpringBootTest(properties = "cistern.base-url=" + ResourceCreateHttpTest.BASE_URL)
@AutoConfigureWebTestClient
class ResourceCreateHttpTest {

    static final String BASE_URL = "http://localhost:3000";

    private static final String TURTLE_BODY = "<> <https://vocab.example/k> \"v\" .";

    private static final byte[] ORIGINAL_BYTES = {'o', 'r', 'i', 'g', 'i', 'n', 'a', 'l'};
    private static final byte[] INTRUDER_BYTES = {'i', 'n', 't', 'r', 'u', 'd', 'e', 'r'};

    private static final String OCTET_STREAM = MediaType.APPLICATION_OCTET_STREAM_VALUE;

    private static final String LDP_RESOURCE = HttpConstants.linkType(Ldp.RESOURCE.getURI());
    private static final String LDP_BASIC_CONTAINER =
            HttpConstants.linkType(Ldp.BASIC_CONTAINER.getURI());

    /** {@code LdpService}'s generated names: lower-case alphanumerics, fixed length. */
    private static final int GENERATED_NAME_LENGTH = 22;

    private static final Path STORAGE_ROOT = createTempRoot();

    /** Makes each test's paths unique without depending on execution order. */
    private static final AtomicInteger UNIQUE = new AtomicInteger();

    @Autowired
    private WebTestClient client;

    /** Solid Protocol §4.1's storage-description link, read from the bean that emits it (T2.9). */
    @Autowired
    private StorageDescription storageDescription;

    @DynamicPropertySource
    static void storageRoot(DynamicPropertyRegistry registry) {
        registry.add("cistern.storage.root", STORAGE_ROOT::toString);
    }

    private static Path createTempRoot() {
        try {
            return Files.createTempDirectory("cistern-t23-");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---------------------------------------------------------------- helpers

    /** A path nothing else in this class touches. */
    private static String unique(String template) {
        return template.formatted(UNIQUE.incrementAndGet());
    }

    /** A container of this test's own, created and asserted so a fixture cannot silently drift. */
    private String container() {
        String path = unique("/t%d/");
        client.put().uri(path)
                .header(HttpHeaders.CONTENT_TYPE, RdfSerialization.TURTLE.contentType())
                .bodyValue(TURTLE_BODY.getBytes(StandardCharsets.UTF_8))
                .exchange().expectStatus().isCreated();
        return path;
    }

    private WebTestClient.RequestHeadersSpec<?> post(String path, String contentType, byte[] body) {
        return client.post().uri(path)
                .header(HttpHeaders.CONTENT_TYPE, contentType)
                .bodyValue(body);
    }

    private WebTestClient.RequestHeadersSpec<?> postTurtle(String path) {
        return post(path, RdfSerialization.TURTLE.contentType(),
                TURTLE_BODY.getBytes(StandardCharsets.UTF_8));
    }

    /** The {@code Location} of a successful create, asserted to be present and absolute. */
    private String createdLocation(WebTestClient.RequestHeadersSpec<?> request) {
        EntityExchangeResult<byte[]> result = request.exchange()
                .expectStatus().isCreated()
                .expectBody().returnResult();
        String location = result.getResponseHeaders().getFirst(HttpHeaders.LOCATION);
        assertNotNull(location, "LDP §5.2.3.1 makes Location mandatory on a 201");
        assertTrue(URI.create(location).isAbsolute(), "Location must be absolute: " + location);
        assertTrue(location.startsWith(BASE_URL), "Location must name this pod: " + location);
        return location;
    }

    /** The path part of a {@code Location} — what the created resource is addressed by. */
    private String createdPath(WebTestClient.RequestHeadersSpec<?> request) {
        return createdLocation(request).substring(BASE_URL.length());
    }

    private static String nameIn(String containerPath, String createdPath) {
        assertTrue(createdPath.startsWith(containerPath),
                createdPath + " must be inside " + containerPath);
        return createdPath.substring(containerPath.length());
    }

    private static WebTestClient.BodyContentSpec expectProblem(
            WebTestClient.RequestHeadersSpec<?> request, ProblemType expected, String instance) {
        return request.exchange()
                .expectStatus().isEqualTo(expected.status())
                .expectHeader().contentType(ProblemDocument.MEDIA_TYPE)
                .expectBody()
                .jsonPath("$.status").isEqualTo(expected.status().value())
                .jsonPath("$.type").isEqualTo(expected.uri().toString())
                .jsonPath("$.title").isEqualTo(expected.title())
                .jsonPath("$.detail").isNotEmpty()
                .jsonPath("$.instance").isEqualTo(instance);
    }

    private static String body(EntityExchangeResult<byte[]> result) {
        byte[] content = result.getResponseBodyContent();
        return content == null ? "" : new String(content, StandardCharsets.UTF_8);
    }

    private String readAsText(String path, String accept) {
        EntityExchangeResult<byte[]> result = client.get().uri(path)
                .accept(MediaType.parseMediaType(accept))
                .exchange()
                .expectStatus().isOk()
                .expectBody().returnResult();
        return body(result);
    }

    // ---------------------------------------------------------------- 201 and Location

    /**
     * LDP §5.2.3.1: "If the resource was created successfully, LDP servers MUST respond with
     * status code 201 (Created) and the Location header set to the new resource's URL." RFC 9110
     * §15.3.2 makes {@code Location} the identity of what was created; unlike {@code PUT}, the
     * client did not choose the name, so without it the response would be unusable.
     */
    @Test
    @DisplayName("a create answers 201 with a Location naming the new resource")
    void createsWith201AndLocation() {
        String container = container();

        String created = createdPath(postTurtle(container));

        assertTrue(created.startsWith(container), created + " must be inside " + container);
        client.get().uri(created)
                .accept(MediaType.parseMediaType(RdfSerialization.TURTLE.contentType()))
                .exchange().expectStatus().isOk();
    }

    /** LDP §5.2.3.1: "Clients shall not expect any representation in the response entity body". */
    @Test
    @DisplayName("a 201 carries no content")
    void createResponseHasNoBody() {
        postTurtle(container()).exchange()
                .expectStatus().isCreated()
                .expectBody().isEmpty();
    }

    // ---------------------------------------------------------------- Slug

    /** RFC 5023 §9.7 / LDP §5.2.3.10 — the hint is used when nothing is in its way. */
    @Test
    @DisplayName("a Slug is honoured")
    void honoursASlug() {
        String container = container();

        String created = createdPath(postTurtle(container).header(HttpConstants.SLUG, "notes.ttl"));

        assertEquals(container + "notes.ttl", created);
    }

    /** The sanitization reaches the wire: a traversing hint cannot address anything above. */
    @Test
    @DisplayName("a traversing Slug is sanitized, not obeyed")
    void sanitizesASlug() {
        String container = container();

        String created = createdPath(
                postTurtle(container).header(HttpConstants.SLUG, "../../etc/passwd"));

        assertEquals(container + "etc-passwd", created);
    }

    /** Cistern's stated rule: a hint that sanitizes to nothing is ignored, not refused. */
    @Test
    @DisplayName("a Slug that sanitizes away falls back to a generated name")
    void ignoresAnUnusableSlug() {
        String container = container();

        String created = createdPath(postTurtle(container).header(HttpConstants.SLUG, "../"));

        assertEquals(GENERATED_NAME_LENGTH, nameIn(container, created).length());
    }

    /**
     * The one slug case that is a 400 rather than an ignored hint. RFC 5023 §9.7.1: "The field
     * value is the percent-encoded value of the UTF-8 encoding of the character sequence to be
     * included" — a truncated escape is not that, so the header itself is malformed. Its sibling,
     * a control character, is refused by the same rule but cannot be exercised over real HTTP:
     * the codec rejects the message before any handler sees it, which is the outcome one wants.
     */
    @Test
    @DisplayName("a Slug with a malformed percent-escape is a 400")
    void refusesAMalformedSlug() {
        String container = container();

        expectProblem(postTurtle(container).header(HttpConstants.SLUG, "note%2"),
                ProblemType.BAD_INPUT, container);
    }

    // ---------------------------------------------------------------- generated names

    /** LDP §5.2.3.8 — with no hint the server assigns the URI. */
    @Test
    @DisplayName("with no Slug the server mints a short, URL-safe name")
    void generatesAName() {
        String container = container();

        String name = nameIn(container, createdPath(postTurtle(container)));

        assertEquals(GENERATED_NAME_LENGTH, name.length());
        assertTrue(name.matches("[0-9a-z]+"), "must need no percent-encoding: " + name);
    }

    /** LDP §5.2.3.11: "LDP servers that allow member creation via POST SHOULD NOT re-use URIs." */
    @Test
    @DisplayName("repeated creates never land on one URI")
    void generatedNamesDoNotRepeat() {
        String container = container();
        Set<String> created = new HashSet<>();

        for (int i = 0; i < 10; i++) {
            created.add(createdPath(postTurtle(container)));
        }

        assertEquals(10, created.size(), "every create must get its own URI");
    }

    // ---------------------------------------------------------------- collision

    /**
     * The DoD's headline case. A {@code POST} has no replace semantics, so a taken {@code Slug}
     * must cost the client a different URI — and must cost the resource holding that name
     * nothing whatsoever.
     */
    @Test
    @DisplayName("a Slug collision yields a distinct name and leaves the original byte-identical")
    void slugCollisionNeverOverwrites() {
        String container = container();
        String first = createdPath(post(container, OCTET_STREAM, ORIGINAL_BYTES)
                .header(HttpConstants.SLUG, "note"));
        assertEquals(container + "note", first, "the first create takes the requested name");

        String second = createdPath(post(container, OCTET_STREAM, INTRUDER_BYTES)
                .header(HttpConstants.SLUG, "note"));

        assertNotEquals(first, second, "the taken name must not be reused");
        assertEquals(GENERATED_NAME_LENGTH, nameIn(container, second).length(),
                "a collision falls back to a generated name, not to a numeric suffix");
        client.get().uri(first).accept(MediaType.APPLICATION_OCTET_STREAM).exchange()
                .expectStatus().isOk()
                .expectBody(byte[].class).isEqualTo(ORIGINAL_BYTES);
        client.get().uri(second).accept(MediaType.APPLICATION_OCTET_STREAM).exchange()
                .expectStatus().isOk()
                .expectBody(byte[].class).isEqualTo(INTRUDER_BYTES);
    }

    /**
     * Solid Protocol §3.1 makes {@code /c/n} and {@code /c/n/} one name, so an existing container
     * occupies it for a document too — a collision the client never asked about, and which would
     * otherwise surface as a 409 from the store.
     */
    @Test
    @DisplayName("an existing child container occupies the name for a document too")
    void collidesAcrossBothSpellingsOfAName() {
        String container = container();
        String childContainer = createdPath(postTurtle(container)
                .header(HttpHeaders.LINK, LDP_BASIC_CONTAINER)
                .header(HttpConstants.SLUG, "thing"));
        assertEquals(container + "thing/", childContainer);

        String document = createdPath(postTurtle(container).header(HttpConstants.SLUG, "thing"));

        assertNotEquals(container + "thing", document,
                "minting /thing beside the container /thing/ would break §3.1");
        client.get().uri(childContainer)
                .accept(MediaType.parseMediaType(RdfSerialization.TURTLE.contentType()))
                .exchange().expectStatus().isOk();
    }

    // ---------------------------------------------------------------- interaction model

    /**
     * LDP §5.2.3.4's requested interaction model, and Solid Protocol §3.1's slash: the created
     * container must both be addressed with a trailing slash and read back as a container.
     */
    @Test
    @DisplayName("Link rel=type BasicContainer creates a child container")
    void createsAChildContainerFromALinkHeader() {
        String container = container();

        String created = createdPath(postTurtle(container)
                .header(HttpHeaders.LINK, LDP_BASIC_CONTAINER)
                .header(HttpConstants.SLUG, "kids"));

        assertEquals(container + "kids/", created);
        client.get().uri(created)
                .accept(MediaType.parseMediaType(RdfSerialization.TURTLE.contentType()))
                .exchange()
                .expectStatus().isOk()
                // The third value is Solid Protocol §4.1's storage-description link (T2.9),
                // which every GET in the storage carries; the two rel="type" links are what
                // this test is actually about.
                .expectHeader().valueEquals(HttpHeaders.LINK, LDP_RESOURCE, LDP_BASIC_CONTAINER,
                        storageDescription.linkValue());
    }

    /**
     * LDP §5.2.3.4's other half: "If any requested interaction model cannot be honored, the server
     * MUST fail the request." Solid Protocol §4.2 gives Cistern Basic Containers only, and neither
     * membership-based container has any machinery behind it — so creating a basic container and
     * calling it done would be the silent downgrade the MUST forbids. 400 rather than 501 because
     * LDP §4.2.1.6 frames creation-constraint violations as the server's "4xx responses", and RFC
     * 9110 §15.5.1 covers a request the server "will not process ... due to something that is
     * perceived to be a client error".
     */
    @ParameterizedTest(name = "[{index}] Link rel=type ldp:{0} must fail the request")
    @ValueSource(strings = {"DirectContainer", "IndirectContainer"})
    @DisplayName("an LDP interaction model Cistern cannot honour is refused, not downgraded")
    void refusesAnUnhonourableInteractionModel(String localName) {
        String container = container();

        expectProblem(postTurtle(container)
                        .header(HttpHeaders.LINK, HttpConstants.linkType(Ldp.NS + localName))
                        .header(HttpConstants.SLUG, "wanted"),
                ProblemType.BAD_INPUT, container);

        client.get().uri(container + "wanted").exchange().expectStatus().isNotFound();
        client.get().uri(container + "wanted/").exchange().expectStatus().isNotFound();
    }

    /** "If <b>any</b> requested model cannot be honored" — one bad link spoils an otherwise fine set. */
    @Test
    @DisplayName("an unhonourable model refuses the request even beside a BasicContainer request")
    void refusesAMixedInteractionModelRequest() {
        String container = container();

        expectProblem(postTurtle(container)
                        .header(HttpHeaders.LINK,
                                LDP_BASIC_CONTAINER + ", " + HttpConstants.linkType(
                                        Ldp.DIRECT_CONTAINER.getURI())),
                ProblemType.BAD_INPUT, container);
    }

    /**
     * The LDPR interaction model in its three spellings, each creating a document.
     * {@code ldp:NonRDFSource} is honoured rather than refused: it is an LDPR, so a document
     * handles later requests as §5.2.3.4 requires, and what the state actually is comes from
     * {@code Content-Type} (§5.2.3.6) — which is why the binary case below round-trips verbatim.
     */
    @ParameterizedTest(name = "[{index}] Link rel=type ldp:{0} creates a document")
    @ValueSource(strings = {"Resource", "RDFSource", "NonRDFSource"})
    void honoursTheLdprInteractionModels(String localName) {
        String container = container();

        String created = createdPath(postTurtle(container)
                .header(HttpHeaders.LINK, HttpConstants.linkType(Ldp.NS + localName))
                .header(HttpConstants.SLUG, "ldpr-" + localName));

        assertEquals(container + "ldpr-" + localName, created);
        assertFalse(created.endsWith("/"), "an LDPR is not a container (Solid Protocol §3.1)");
    }

    /** A NonRDFSource link with a binary body is honoured in substance, not just in shape. */
    @Test
    @DisplayName("ldp:NonRDFSource with a binary body creates the LDP-NR the client asked for")
    void honoursNonRdfSourceWithABinaryBody() {
        String container = container();

        String created = createdPath(post(container, OCTET_STREAM, ORIGINAL_BYTES)
                .header(HttpHeaders.LINK, HttpConstants.linkType(Ldp.NON_RDF_SOURCE.getURI()))
                .header(HttpConstants.SLUG, "nr.bin"));

        client.get().uri(created).accept(MediaType.APPLICATION_OCTET_STREAM).exchange()
                .expectStatus().isOk()
                .expectHeader().contentType(MediaType.APPLICATION_OCTET_STREAM)
                .expectBody(byte[].class).isEqualTo(ORIGINAL_BYTES);
    }

    /**
     * The asymmetry that keeps the refusal above from becoming "reject any Link I do not know":
     * §5.2.3.4 ends "This specification does not constrain the server's behavior in other cases",
     * and a domain vocabulary class is one of those cases. Clients type their own data routinely.
     */
    @Test
    @DisplayName("a rel=type link outside the LDP namespace still creates a document")
    void allowsNonLdpTypeLinks() {
        String container = container();

        String created = createdPath(postTurtle(container)
                .header(HttpHeaders.LINK, HttpConstants.linkType("https://vocab.example/Note"))
                .header(HttpConstants.SLUG, "domain-typed"));

        assertEquals(container + "domain-typed", created);
    }

    @Test
    @DisplayName("no Link, or a Link naming something else, creates a document")
    void createsADocumentByDefault() {
        String container = container();

        String plain = createdPath(postTurtle(container).header(HttpConstants.SLUG, "plain"));
        String typed = createdPath(postTurtle(container)
                .header(HttpHeaders.LINK, HttpConstants.linkType("https://vocab.example/Note"))
                .header(HttpConstants.SLUG, "typed"));

        assertEquals(container + "plain", plain);
        assertEquals(container + "typed", typed);
    }

    /** LDP §5.2.3.2: the container gains a containment triple for what it just minted. */
    @Test
    @DisplayName("the created resource shows up in the container's listing")
    void addsTheCreatedResourceToTheContainment() {
        String container = container();
        String created = createdPath(postTurtle(container).header(HttpConstants.SLUG, "listed.ttl"));

        String listing = readAsText(container, RdfSerialization.TURTLE.contentType());

        assertTrue(listing.contains(Ldp.CONTAINS.getURI()) || listing.contains("contains"),
                "the container must derive an ldp:contains triple: " + listing);
        assertTrue(listing.contains("listed.ttl"), created + " must be listed: " + listing);
    }

    // ---------------------------------------------------------------- refusals

    /**
     * Solid Protocol §5.3: "When a POST method request targets a resource without an existing
     * representation, the server MUST respond with the 404 status code." A POST does not create
     * the container it was addressed to — that asymmetry with PUT is deliberate.
     */
    @Test
    @DisplayName("POST to a container that does not exist is 404")
    void refusesAnAbsentContainer() {
        String absent = unique("/t%d-absent/");

        expectProblem(postTurtle(absent), ProblemType.NOT_FOUND, absent);

        client.get().uri(absent).exchange().expectStatus().isNotFound();
    }

    /**
     * The DoD's "404/405 per spec", resolved from the text: an <em>existing</em> document is not
     * a 404 — a GET serves it — so the refusal is RFC 9110 §15.5.6's 405, the status for a method
     * "known by the origin server but not supported by the target resource". Solid Protocol §5.3
     * confines creation by POST to "a URI path ending with /".
     */
    @Test
    @DisplayName("POST to an existing document is 405, with a truthful Allow")
    void refusesADocumentTarget() {
        String container = container();
        String document = createdPath(postTurtle(container).header(HttpConstants.SLUG, "doc.ttl"));

        expectProblem(postTurtle(document), ProblemType.METHOD_NOT_ALLOWED, document);

        EntityExchangeResult<byte[]> result = postTurtle(document).exchange()
                .expectStatus().isEqualTo(ProblemType.METHOD_NOT_ALLOWED.status())
                .expectBody().returnResult();
        String allow = result.getResponseHeaders().getFirst(HttpHeaders.ALLOW);
        assertNotNull(allow, "RFC 9110 §15.5.6 makes Allow mandatory on a 405");
        List<String> methods = List.of(allow.split(",\\s*"));
        assertFalse(methods.contains("POST"),
                "a 405 for POST must not advertise POST; got: " + allow);
        assertTrue(methods.containsAll(List.of("GET", "HEAD", "OPTIONS", "PUT", "DELETE")),
                "everything a document still supports must be listed; got: " + allow);
    }

    /** Existence outranks kind, so a document-shaped path that names nothing is still a 404. */
    @Test
    @DisplayName("POST to a path that names nothing is 404 whatever its shape")
    void refusesAnAbsentDocumentTarget() {
        String absent = unique("/t%d-absent.ttl");

        expectProblem(postTurtle(absent), ProblemType.NOT_FOUND, absent);
    }

    /** Solid Protocol §2.1, as T2.2 already enforces for PUT: no Content-Type, no create. */
    @Test
    @DisplayName("a POST with no Content-Type is 400")
    void refusesAPostWithNoContentType() {
        String container = container();

        expectProblem(client.post().uri(container).bodyValue(new byte[0]),
                ProblemType.BAD_INPUT, container);
    }

    /** Solid Protocol §4.2 — a container's representation is an RDF source. */
    @Test
    @DisplayName("a non-RDF body cannot create a container")
    void refusesANonRdfBodyForAContainer() {
        String container = container();

        expectProblem(post(container, OCTET_STREAM, ORIGINAL_BYTES)
                        .header(HttpHeaders.LINK, LDP_BASIC_CONTAINER),
                ProblemType.CONFLICT, container);
    }

    /** T2.2's guard, inherited by reusing the same write path: malformed RDF is the client's 400. */
    @Test
    @DisplayName("a malformed RDF body is 400")
    void refusesAMalformedBody() {
        String container = container();

        expectProblem(post(container, RdfSerialization.TURTLE.contentType(),
                        "<not turtle".getBytes(StandardCharsets.UTF_8)),
                ProblemType.BAD_INPUT, container);
    }

    // ---------------------------------------------------------------- response metadata

    /**
     * RFC 9110 §10.2.1 defines {@code Allow} as "the set of methods supported by the target
     * resource", and LDP §4.2.1.4 ties the type link to the request URI. For a POST both are the
     * container — the created resource is named by {@code Location}, not described by the
     * headers.
     */
    @Test
    @DisplayName("the interface metadata describes the container that was posted to")
    void advertisesTheTargetContainersInterface() {
        String container = container();

        postTurtle(container).exchange()
                .expectStatus().isCreated()
                .expectHeader().valueEquals(HttpHeaders.LINK, LDP_RESOURCE, LDP_BASIC_CONTAINER)
                .expectHeader().valueEquals(HttpHeaders.ALLOW,
                        "GET, HEAD, OPTIONS, POST, PUT, PATCH, DELETE")
                .expectHeader().exists(HttpConstants.ACCEPT_POST)
                .expectHeader().exists(HttpConstants.ACCEPT_PUT);
    }

    /** Solid Protocol §5.4: the root's Allow never lists DELETE, on a POST response as anywhere. */
    @Test
    @DisplayName("posting to the storage root advertises the root's method set")
    void advertisesTheStorageRootsInterface() {
        container(); // guarantees the root container exists, whatever order tests run in

        postTurtle("/").exchange()
                .expectStatus().isCreated()
                .expectHeader().valueEquals(HttpHeaders.ALLOW, "GET, HEAD, OPTIONS, POST, PUT, PATCH");
    }

    // ---------------------------------------------------------------- validators

    /**
     * RFC 9110 §9.3.4's validator prohibition binds {@code PUT} only. §15.3.2 says so while
     * defining 201 — "Any validator fields (Section 8.8) sent in the response convey the current
     * validators for a new representation created by the request. Note that the PUT method
     * (Section 9.3.4) has additional requirements that might preclude sending such validators."
     * A non-RDF resource is served verbatim, so the tag sent here is the one a GET returns.
     */
    @Test
    @DisplayName("a created non-RDF resource carries the validators a GET would return")
    void sendsValidatorsForANonRdfCreate() {
        String container = container();

        EntityExchangeResult<byte[]> created = post(container, OCTET_STREAM, ORIGINAL_BYTES)
                .header(HttpConstants.SLUG, "bytes.bin")
                .exchange()
                .expectStatus().isCreated()
                .expectHeader().exists(HttpHeaders.ETAG)
                .expectHeader().exists(HttpHeaders.LAST_MODIFIED)
                .expectBody().returnResult();

        String location = created.getResponseHeaders().getFirst(HttpHeaders.LOCATION);
        assertNotNull(location);
        EntityExchangeResult<byte[]> read = client.get().uri(location.substring(BASE_URL.length()))
                .accept(MediaType.APPLICATION_OCTET_STREAM)
                .exchange().expectStatus().isOk().expectBody().returnResult();
        assertEquals(created.getResponseHeaders().getETag(), read.getResponseHeaders().getETag(),
                "§15.3.2: a validator on a 201 must be the created representation's current one");
    }

    /**
     * An RDF source has two representations (Solid Protocol §5.5) with two different validators,
     * and a 201 carries no {@code Content-Type} to say which one a tag described — so no tag is
     * sent. §15.3.2 permits validators; it does not require them, and an ambiguous validator is
     * worse than none.
     */
    @Test
    @DisplayName("a created RDF resource carries no validators, because it has two representations")
    void sendsNoValidatorsForAnRdfCreate() {
        String container = container();

        postTurtle(container).exchange()
                .expectStatus().isCreated()
                .expectHeader().doesNotExist(HttpHeaders.ETAG)
                .expectHeader().doesNotExist(HttpHeaders.LAST_MODIFIED);
    }

    @Test
    @DisplayName("a created container carries no validators either")
    void sendsNoValidatorsForACreatedContainer() {
        String container = container();

        postTurtle(container).header(HttpHeaders.LINK, LDP_BASIC_CONTAINER).exchange()
                .expectStatus().isCreated()
                .expectHeader().doesNotExist(HttpHeaders.ETAG);
    }

    // ---------------------------------------------------------------- round trip

    /**
     * LDP §4.2.1.5 / §5.2.3.7: the body is based against the created resource, so {@code <>}
     * describes what was made. The client cannot know that URI in advance, so getting this wrong
     * would silently mis-subject every POSTed graph.
     */
    @Test
    @DisplayName("<> in a posted body means the resource that was created")
    void basesThePostedBodyOnTheCreatedResource() {
        String container = container();

        String created = createdPath(post(container, RdfSerialization.TURTLE.contentType(),
                "<> <https://vocab.example/title> \"Hello\" ."
                        .getBytes(StandardCharsets.UTF_8)));

        String turtle = readAsText(created, RdfSerialization.TURTLE.contentType());
        assertTrue(turtle.contains("Hello"), "GET must return what POST stored: " + turtle);
    }
}
