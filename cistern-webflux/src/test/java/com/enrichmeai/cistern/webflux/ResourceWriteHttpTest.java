package com.enrichmeai.cistern.webflux;

import com.enrichmeai.cistern.core.ldp.Ldp;
import com.enrichmeai.cistern.webflux.error.ProblemDocument;
import com.enrichmeai.cistern.webflux.error.ProblemType;
import org.junit.jupiter.api.Test;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T2.2 over real HTTP: {@code PUT} through the whole stack — router, handler, media-type
 * canonicalization, {@code LdpService}, and the production {@code FileResourceStore} on a temp
 * directory. Nothing is mocked, so what these assertions pin is what a client sees.
 *
 * <p>The ticket's DoD matrix — create, replace, kind flip, nested — plus the rules that make
 * those safe, each traced to its source:
 * <ul>
 *   <li>201 on create, 204 on replace, and the {@code ETag} prohibition for a transformed
 *       representation — RFC 9110 §9.3.4, §15.3.2, §15.3.5.</li>
 *   <li>Intermediate containers and their derived containment — Solid Protocol §5.3, §4.2.</li>
 *   <li>One name is a container or a document, never both — Solid Protocol §3.1.</li>
 *   <li>Containment triples are server-managed; a body asserting them is a 409 — §5.3.</li>
 *   <li>A write with no (or an unusable) {@code Content-Type} is a 400 — §2.1.</li>
 * </ul>
 *
 * <p>Every path used here is unique per test, because the store is shared across the class and
 * a write is not idempotent in its <em>effect</em>: the same {@code PUT} is a 201 once and a 204
 * afterwards. Tests that need a pre-existing resource create it themselves, so no ordering
 * dependency exists between them.
 *
 * <p>The 4xx responses come from T2.6's single error mapper, picked up by the component scan
 * like any other production bean, and are asserted as full RFC 9457 documents rather than as
 * bare status codes — so this class also pins that T2.2 signals domain exceptions rather than
 * writing status codes itself.
 */
@SpringBootTest(properties = "cistern.base-url=http://localhost:3000")
@AutoConfigureWebTestClient
class ResourceWriteHttpTest {

    private static final String TURTLE_BODY = "<> <https://vocab.example/k> \"v\" .";

    private static final byte[] PNG_BYTES = {
            (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x01, 0x02, 0x03};

    private static final String LDP_RESOURCE = HttpConstants.linkType(Ldp.RESOURCE.getURI());
    private static final String LDP_BASIC_CONTAINER =
            HttpConstants.linkType(Ldp.BASIC_CONTAINER.getURI());

    private static final Path STORAGE_ROOT = createTempRoot();

    /** Makes each test's paths unique without depending on execution order. */
    private static final AtomicInteger UNIQUE = new AtomicInteger();

    @Autowired
    private WebTestClient client;

    @DynamicPropertySource
    static void storageRoot(DynamicPropertyRegistry registry) {
        registry.add("cistern.storage.root", STORAGE_ROOT::toString);
    }

    private static Path createTempRoot() {
        try {
            return Files.createTempDirectory("cistern-t22-");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---------------------------------------------------------------- helpers

    /** A path nothing else in this class touches. */
    private static String unique(String template) {
        return template.formatted(UNIQUE.incrementAndGet());
    }

    private WebTestClient.RequestHeadersSpec<?> put(String path, String contentType, byte[] body) {
        return client.put().uri(path)
                .header(HttpHeaders.CONTENT_TYPE, contentType)
                .bodyValue(body);
    }

    private WebTestClient.RequestHeadersSpec<?> putTurtle(String path, String turtle) {
        return put(path, RdfSerialization.TURTLE.contentType(),
                turtle.getBytes(StandardCharsets.UTF_8));
    }

    /** Creates a resource and asserts it was a create, so a test's fixture cannot silently drift. */
    private void seed(String path, String turtle) {
        putTurtle(path, turtle).exchange().expectStatus().isCreated();
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

    // ---------------------------------------------------------------- create

    /**
     * RFC 9110 §9.3.4: a PUT that creates a representation where there was none MUST be
     * reported with 201.
     */
    @Test
    void createsADocumentWith201() {
        String path = unique("/t%d-created.ttl");

        putTurtle(path, TURTLE_BODY).exchange()
                .expectStatus().isCreated()
                .expectBody().isEmpty();
    }

    /**
     * RFC 9110 §15.3.2: the created resource is identified "by either a Location header field
     * in the response or, if no Location field is received, by the target URI". A PUT always
     * creates at the target URI, so Location would only restate the request line.
     */
    @Test
    void createResponseCarriesNoLocation() {
        String path = unique("/t%d-no-location.ttl");

        putTurtle(path, TURTLE_BODY).exchange()
                .expectStatus().isCreated()
                .expectHeader().doesNotExist(HttpHeaders.LOCATION);
    }

    /** The written document is readable, and reads back as the RDF source it was stored as. */
    @Test
    void aCreatedDocumentIsImmediatelyReadable() {
        String path = unique("/t%d-readable.ttl");
        seed(path, "<> <https://vocab.example/title> \"Hello\" .");

        EntityExchangeResult<byte[]> read = client.get().uri(path)
                .accept(MediaType.parseMediaType(RdfSerialization.TURTLE.contentType()))
                .exchange()
                .expectStatus().isOk()
                .expectBody().returnResult();
        assertTrue(body(read).contains("Hello"), "GET must return what PUT stored: " + body(read));
    }

    /** A container target is created as a container, advertised as an LDP Basic Container. */
    @Test
    void createsAContainerWith201() {
        String path = unique("/t%d-container/");

        putTurtle(path, "").exchange()
                .expectStatus().isCreated()
                .expectHeader().valueEquals(HttpHeaders.LINK, LDP_RESOURCE, LDP_BASIC_CONTAINER);
    }

    /** A binary body is stored verbatim and served back byte for byte. */
    @Test
    void createsANonRdfDocumentAndServesItVerbatim() {
        String path = unique("/t%d-logo.png");

        put(path, MediaType.IMAGE_PNG_VALUE, PNG_BYTES).exchange()
                .expectStatus().isCreated();

        EntityExchangeResult<byte[]> read = client.get().uri(path)
                .exchange().expectStatus().isOk().expectBody().returnResult();
        assertArrayEquals(PNG_BYTES, read.getResponseBodyContent());
    }

    // ---------------------------------------------------------------- replace

    /**
     * RFC 9110 §9.3.4 permits 200 or 204 for a replace; Cistern sends 204 (§15.3.5) because the
     * only honest content for a replace is the representation the client just supplied.
     */
    @Test
    void replacesADocumentWith204AndNoBody() {
        String path = unique("/t%d-replaced.ttl");
        seed(path, "<> <https://vocab.example/k> \"first\" .");

        putTurtle(path, "<> <https://vocab.example/k> \"second\" .").exchange()
                .expectStatus().isNoContent()
                .expectBody().isEmpty();

        EntityExchangeResult<byte[]> read = client.get().uri(path)
                .exchange().expectStatus().isOk().expectBody().returnResult();
        assertTrue(body(read).contains("second"), "the replacement must be what is served");
        assertTrue(!body(read).contains("first"), "the replaced state must be gone");
    }

    /** Replacing a container is a 204 as well — the kind of the target does not change this. */
    @Test
    void replacesAContainerWith204() {
        String path = unique("/t%d-recontainer/");
        putTurtle(path, "").exchange().expectStatus().isCreated();

        putTurtle(path, "<> <https://vocab.example/title> \"Renamed\" .").exchange()
                .expectStatus().isNoContent();
    }

    // ---------------------------------------------------------------- validators

    /**
     * RFC 9110 §9.3.4: "An origin server MUST NOT send a validator field ... such as an ETag or
     * Last-Modified field, in a successful response to PUT unless the request's representation
     * data was saved without any transformation applied to the content."
     *
     * <p>An <b>RDF document</b> fails that condition even though its bytes are stored verbatim,
     * because the read path re-serializes from a parsed graph — what a GET returns is never
     * byte-identical to what was PUT, so the client cannot treat the bytes it still holds as
     * the result. Both validator fields are therefore withheld (architect ruling, PR #66).
     */
    @Test
    void rdfDocumentWriteReturnsNoValidator() {
        String path = unique("/t%d-novalidator.ttl");

        EntityExchangeResult<byte[]> written = putTurtle(path, TURTLE_BODY).exchange()
                .expectStatus().isCreated()
                .expectBody().returnResult();

        assertNoValidators(written, "an RDF document write");
    }

    /**
     * A container fails the same condition twice over: its served representation additionally
     * includes {@code ldp:contains} triples derived from its live children (Solid Protocol
     * §4.2), which were in no part of the request content.
     */
    @Test
    void containerWriteReturnsNoValidator() {
        String path = unique("/t%d-novalidator/");

        EntityExchangeResult<byte[]> written = putTurtle(path, "").exchange()
                .expectStatus().isCreated()
                .expectBody().returnResult();

        assertNoValidators(written, "a container write");
    }

    /** The same rule on the 204 branch — a replace is a successful PUT too. */
    @Test
    void rdfReplaceReturnsNoValidator() {
        String path = unique("/t%d-novalidator-replace.ttl");
        seed(path, TURTLE_BODY);

        EntityExchangeResult<byte[]> written = putTurtle(path, "<> <https://vocab.example/k> \"2\" .")
                .exchange()
                .expectStatus().isNoContent()
                .expectBody().returnResult();

        assertNoValidators(written, "an RDF replace");
    }

    /**
     * The other side of the ruling: a non-RDF resource IS stored and served back verbatim, so
     * the §9.3.4 condition genuinely holds and both validators are sent — and the ETag must be
     * the one a GET returns, or PUT and GET disagree about the resource.
     */
    @Test
    void nonRdfWriteReturnsValidatorsMatchingAGet() {
        String path = unique("/t%d-validators.png");

        EntityExchangeResult<byte[]> written = put(path, MediaType.IMAGE_PNG_VALUE, PNG_BYTES)
                .exchange()
                .expectStatus().isCreated()
                .expectBody().returnResult();
        String writeEtag = written.getResponseHeaders().getFirst(HttpHeaders.ETAG);
        assertNotNull(writeEtag, "a non-RDF write must carry an ETag");
        assertTrue(written.getResponseHeaders().getLastModified() > 0,
                "a non-RDF write must carry a Last-Modified");

        EntityExchangeResult<byte[]> read = client.get().uri(path)
                .exchange().expectStatus().isOk().expectBody().returnResult();
        assertEquals(writeEtag, read.getResponseHeaders().getFirst(HttpHeaders.ETAG),
                "PUT and GET must not disagree about a representation's validator");
        assertEquals(written.getResponseHeaders().getLastModified(),
                read.getResponseHeaders().getLastModified(),
                "PUT and GET must not disagree about Last-Modified either");
    }

    /** Both validator fields or neither — §9.3.4 names them together. */
    private static void assertNoValidators(EntityExchangeResult<byte[]> result, String what) {
        HttpHeaders headers = result.getResponseHeaders();
        assertNull(headers.getFirst(HttpHeaders.ETAG),
                what + " must not claim the client holds the new representation (ETag)");
        assertNull(headers.getFirst(HttpHeaders.LAST_MODIFIED),
                what + " must not send Last-Modified either — it is a validator field too");
    }

    /** Solid Protocol §5.2: Allow (and its Accept-* companions) in successful responses. */
    @Test
    void writeResponsesAdvertiseTheResourceInterface() {
        String path = unique("/t%d-interface/");

        putTurtle(path, "").exchange()
                .expectStatus().isCreated()
                .expectHeader().valueEquals(HttpHeaders.ALLOW, ResourceKind.CONTAINER.allow())
                .expectHeader().valueEquals(HttpConstants.ACCEPT_PUT,
                        ResourceKind.CONTAINER.acceptPut())
                .expectHeader().valueEquals(HttpConstants.ACCEPT_POST,
                        ResourceKind.CONTAINER.acceptPost());
    }

    // ---------------------------------------------------------------- nested / intermediates

    /**
     * Solid Protocol §5.3: "Servers MUST create intermediate containers and include
     * corresponding containment triples in container representations derived from the URI path
     * component of PUT and PATCH requests."
     */
    @Test
    void createsIntermediateContainersForANestedDocument() {
        int n = UNIQUE.incrementAndGet();
        String document = "/t%d-a/b/c/deep.ttl".formatted(n);

        putTurtle(document, TURTLE_BODY).exchange().expectStatus().isCreated();

        // Every intermediate now exists and lists the next level down.
        assertContains("/t%d-a/".formatted(n), "/t%d-a/b/".formatted(n));
        assertContains("/t%d-a/b/".formatted(n), "/t%d-a/b/c/".formatted(n));
        assertContains("/t%d-a/b/c/".formatted(n), document);
    }

    /** Containment is derived on read, so a new child shows up in its parent immediately. */
    @Test
    void aNewChildAppearsInItsParentsContainment() {
        int n = UNIQUE.incrementAndGet();
        String container = "/t%d-parent/".formatted(n);
        String child = container + "child.ttl";
        putTurtle(container, "").exchange().expectStatus().isCreated();

        putTurtle(child, TURTLE_BODY).exchange().expectStatus().isCreated();

        assertContains(container, child);
    }

    /** Asserts a container's representation contains the given member URI. */
    private void assertContains(String containerPath, String memberPath) {
        EntityExchangeResult<byte[]> read = client.get().uri(containerPath)
                .accept(MediaType.parseMediaType(RdfSerialization.TURTLE.contentType()))
                .exchange().expectStatus().isOk().expectBody().returnResult();
        String expected = "http://localhost:3000" + memberPath;
        assertTrue(body(read).contains(expected),
                containerPath + " must contain " + expected + "; got: " + body(read));
    }

    // ---------------------------------------------------------------- slash semantics (§3.1)

    /**
     * Solid Protocol §3.1: if two URIs differ only in the trailing slash and the server has
     * associated a resource with one of them, the other MUST NOT correspond to another
     * resource. Writing a document over an existing container is that collision.
     */
    @Test
    void rejectsDocumentWrittenOverAnExistingContainer() {
        int n = UNIQUE.incrementAndGet();
        String container = "/t%d-flip/".formatted(n);
        String document = "/t%d-flip".formatted(n);
        putTurtle(container, "").exchange().expectStatus().isCreated();

        expectProblem(putTurtle(document, TURTLE_BODY), ProblemType.CONFLICT, document);
    }

    /** The same rule in the other direction. */
    @Test
    void rejectsContainerWrittenOverAnExistingDocument() {
        int n = UNIQUE.incrementAndGet();
        String document = "/t%d-flop".formatted(n);
        String container = "/t%d-flop/".formatted(n);
        seed(document, TURTLE_BODY);

        expectProblem(putTurtle(container, ""), ProblemType.CONFLICT, container);
    }

    /** An intermediate container cannot displace a document already holding the name. */
    @Test
    void rejectsNestedWriteBlockedByADocumentAncestor() {
        int n = UNIQUE.incrementAndGet();
        String blocker = "/t%d-blocker".formatted(n);
        String nested = "/t%d-blocker/child.ttl".formatted(n);
        seed(blocker, TURTLE_BODY);

        expectProblem(putTurtle(nested, TURTLE_BODY), ProblemType.CONFLICT, nested);
    }

    /** A container's representation is an RDF source (Solid Protocol §4.2). */
    @Test
    void rejectsNonRdfBodyForAContainer() {
        String path = unique("/t%d-binary-container/");

        expectProblem(put(path, MediaType.IMAGE_PNG_VALUE, PNG_BYTES),
                ProblemType.CONFLICT, path);
    }

    // ---------------------------------------------------------------- server-managed triples

    /**
     * Solid Protocol §5.3: "Servers MUST NOT allow HTTP PUT or PATCH on a container to update
     * its containment triples; if the server receives such a request, it MUST respond with a
     * 409 status code."
     */
    @Test
    void rejectsBodyAssertingContainmentForAContainer() {
        String path = unique("/t%d-inject/");

        expectProblem(putTurtle(path, """
                @prefix ldp: <http://www.w3.org/ns/ldp#> .
                <> ldp:contains <http://localhost:3000/injected.ttl> .
                """), ProblemType.CONFLICT, path);
    }

    /** Clients routinely echo the LDP type triples a GET handed them; those are tolerated. */
    @Test
    void toleratesClientEchoedLdpTypeTriples() {
        String path = unique("/t%d-echo/");

        putTurtle(path, """
                @prefix ldp: <http://www.w3.org/ns/ldp#> .
                <> a ldp:BasicContainer, ldp:Container, ldp:Resource .
                """).exchange().expectStatus().isCreated();
    }

    // ---------------------------------------------------------------- Content-Type handling

    /**
     * The regression this ticket exists to prevent (architect note on issue #13):
     * {@code Representation.isRdf()} matches the media type exactly, so an uncanonicalized
     * {@code text/turtle;charset=utf-8} would store an RDF source as opaque binary — and it
     * could never be served as RDF again. Proof that it did not: the resource comes back as
     * JSON-LD, which is only possible from a parsed graph (Solid Protocol §5.5).
     */
    @Test
    void canonicalizesAParameterizedRdfMediaType() {
        String path = unique("/t%d-charset.ttl");

        put(path, "text/turtle;charset=utf-8", TURTLE_BODY.getBytes(StandardCharsets.UTF_8))
                .exchange().expectStatus().isCreated();

        client.get().uri(path)
                .accept(MediaType.parseMediaType(RdfSerialization.JSON_LD.contentType()))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(RdfSerialization.JSON_LD.mediaType());
    }

    /**
     * The other half of the canonicalization ruling (PR #66): canonicalization applies ONLY to
     * recognised RDF types. A non-RDF type keeps its parameters, because dropping them is data
     * loss with a visible consequence — bytes sent as {@code text/plain;charset=utf-16} would
     * later be served labelled {@code text/plain} and a client would decode them wrongly.
     */
    @Test
    void nonRdfMediaTypeParametersRoundTrip() {
        String path = unique("/t%d-utf16.txt");
        String declared = "text/plain;charset=utf-16";
        byte[] utf16Bytes = "Hello, UTF-16".getBytes(java.nio.charset.StandardCharsets.UTF_16);

        put(path, declared, utf16Bytes).exchange().expectStatus().isCreated();

        EntityExchangeResult<byte[]> read = client.get().uri(path)
                .exchange().expectStatus().isOk().expectBody().returnResult();
        MediaType served = read.getResponseHeaders().getContentType();
        assertNotNull(served, "the stored media type must come back");
        assertEquals(MediaType.parseMediaType(declared), served,
                "a non-RDF type must round-trip with its parameters intact");
        assertEquals(java.nio.charset.StandardCharsets.UTF_16, served.getCharset(),
                "the declared charset is what makes these bytes decodable");
        assertArrayEquals(utf16Bytes, read.getResponseBodyContent(),
                "and the bytes themselves are served verbatim");
    }

    /** Canonicalization is case-insensitive too — media types are not case-sensitive. */
    @Test
    void canonicalizesAnUpperCasedRdfMediaType() {
        String path = unique("/t%d-upper.ttl");

        put(path, "TEXT/TURTLE", TURTLE_BODY.getBytes(StandardCharsets.UTF_8))
                .exchange().expectStatus().isCreated();

        client.get().uri(path)
                .accept(MediaType.parseMediaType(RdfSerialization.JSON_LD.contentType()))
                .exchange().expectStatus().isOk();
    }

    /** Solid Protocol §2.1 mandates 400 when a write does not declare its body's media type. */
    @Test
    void rejectsAWriteWithNoContentType() {
        String path = unique("/t%d-notype.ttl");

        expectProblem(client.put().uri(path).bodyValue(TURTLE_BODY.getBytes(StandardCharsets.UTF_8)),
                ProblemType.BAD_INPUT, path);
    }

    /**
     * An unparseable {@code Content-Type} is the client's error, never a 500 — the handler
     * translates Spring's {@code InvalidMediaTypeException} into a domain {@code BadInput}.
     *
     * <p>Sent without content because WebTestClient's own codecs parse {@code Content-Type} to
     * choose a writer and refuse to transmit a malformed one alongside a body; the header still
     * reaches the server, which is what this exercises. {@link RequestMediaTypeTest} covers the
     * parsing rules directly, without an HTTP stack.
     */
    @Test
    void rejectsAMalformedContentType() {
        String path = unique("/t%d-badtype.ttl");

        expectProblem(client.put().uri(path).header(HttpHeaders.CONTENT_TYPE, "text/"),
                ProblemType.BAD_INPUT, path);
    }

    /** RFC 9110 §8.3: Content-Type names what the body IS, so a range cannot be stored. */
    @Test
    void rejectsAWildcardContentType() {
        String path = unique("/t%d-wildcard.ttl");

        expectProblem(put(path, MediaType.ALL_VALUE, TURTLE_BODY.getBytes(StandardCharsets.UTF_8)),
                ProblemType.BAD_INPUT, path);
    }

    // ---------------------------------------------------------------- body validation

    /**
     * Bodies are validated before storage, so malformed RDF is a 400 at write time rather than
     * a 500 at read time.
     */
    @Test
    void rejectsAMalformedRdfBody() {
        String path = unique("/t%d-malformed.ttl");

        expectProblem(putTurtle(path, "<> <https://vocab.example/k>"), ProblemType.BAD_INPUT, path);
    }

    /** A refused body leaves nothing behind: the target must still be absent. */
    @Test
    void aRefusedBodyIsNotStored() {
        String path = unique("/t%d-unstored.ttl");

        putTurtle(path, "<> <https://vocab.example/k>").exchange().expectStatus().isBadRequest();

        client.get().uri(path).exchange().expectStatus().isNotFound();
    }

    /** An empty RDF body is a valid empty graph — a legitimate way to create a container. */
    @Test
    void acceptsAnEmptyRdfBody() {
        String path = unique("/t%d-empty/");

        client.put().uri(path)
                .header(HttpHeaders.CONTENT_TYPE, RdfSerialization.TURTLE.contentType())
                .exchange()
                .expectStatus().isCreated();
    }

    // ---------------------------------------------------------------- request targets

    /** T2.1's request-target rules apply to writes as well — dot segments are unaddressable. */
    @Test
    void rejectsAnUnusableRequestTarget() {
        expectProblem(putTurtle("/a/../b.ttl", TURTLE_BODY), ProblemType.BAD_INPUT, "/a/../b.ttl");
    }
}
