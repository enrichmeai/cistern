package com.enrichmeai.cistern.webflux;

import com.enrichmeai.cistern.core.Representation;
import com.enrichmeai.cistern.core.ResourceIdentifier;
import com.enrichmeai.cistern.core.ResourceStore;
import com.enrichmeai.cistern.webflux.error.ProblemDocument;
import com.enrichmeai.cistern.webflux.error.ProblemType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T2.4 over real HTTP: {@code DELETE} through the whole stack — router, handler,
 * {@code LdpService}, and the production {@code FileResourceStore} on a temp directory.
 * Nothing is mocked, so what these assertions pin is what a client sees.
 *
 * <p>Every expectation traces to a sentence of Solid Protocol §5.4 or RFC 9110:
 * <ul>
 *   <li>Success → {@code 204}, RFC 9110 §9.3.5 (enacted, nothing further to supply).</li>
 *   <li>"When a contained resource is deleted, the server MUST also remove the corresponding
 *       containment triple" — §5.4, asserted by reading the parent back afterwards.</li>
 *   <li>"When a {@code DELETE} request targets a container, the server MUST delete the
 *       container if it contains no resources. If the container contains resources, the server
 *       MUST respond with the {@code 409} status code and response body describing the
 *       error" — §5.4.</li>
 *   <li>"When a {@code DELETE} request targets storage's root container ... the server MUST
 *       respond with the {@code 405} status code. Server MUST exclude the {@code DELETE}
 *       method in the field value of the {@code Allow} header field, in response to requests
 *       to these resources" — §5.4, plus RFC 9110 §15.5.6 which makes {@code Allow} mandatory
 *       on any 405.</li>
 * </ul>
 *
 * <p>The 4xx bodies are produced by T2.6's single error mapper, which the component scan picks
 * up like any other production bean — so this class also pins that the halves compose: T2.4
 * signals a {@code CisternException} subtype and T2.6 renders it as RFC 9457.
 */
@SpringBootTest(properties = "cistern.base-url=http://localhost:3000")
@AutoConfigureWebTestClient
class ResourceDeleteHttpTest {

    private static final String BASE = "http://localhost:3000";

    /** The root's Allow per Solid Protocol §5.4: a container's methods, minus DELETE. */
    private static final String ROOT_ALLOW = "GET, HEAD, OPTIONS, POST, PUT, PATCH";

    private static final Path STORAGE_ROOT = createTempRoot();

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
            return Files.createTempDirectory("cistern-t24-");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---------------------------------------------------------------- fixtures

    /**
     * Seeded through the real backend, because there is no {@code PUT} endpoint until T2.2.
     * Re-seeding is idempotent (put creates or replaces) and re-creates whatever the previous
     * test deleted, so every test starts from the same pod.
     */
    @BeforeEach
    void seedPod() {
        put("/", turtle(""));
        put("/notes/", turtle("""
                @prefix dcterms: <http://purl.org/dc/terms/> .
                <> dcterms:title "My notes" .
                """));
        put("/notes/a.ttl", turtle("<> <https://vocab.example/k> \"v\" ."));
        put("/notes/b.ttl", turtle("<> <https://vocab.example/k> \"w\" ."));
        put("/logo.png", new Representation("image/png", new byte[]{(byte) 0x89, 'P', 'N', 'G'}));
    }

    // ---------------------------------------------------------------- helpers

    private static Representation turtle(String content) {
        return new Representation(Representation.TURTLE, content.getBytes(StandardCharsets.UTF_8));
    }

    private static ResourceIdentifier id(String path) {
        return new ResourceIdentifier(URI.create(BASE + path));
    }

    private void put(String path, Representation representation) {
        StepVerifier.create(store.put(id(path), representation))
                .expectNextCount(1)
                .verifyComplete();
    }

    /** {@code uri(URI)} rather than {@code uri(String)}: no template expansion, no re-encoding. */
    private WebTestClient.RequestHeadersSpec<?> delete(String rawPath) {
        return client.method(HttpMethod.DELETE).uri(URI.create(rawPath));
    }

    private WebTestClient.RequestHeadersSpec<?> get(String rawPath) {
        return client.get().uri(URI.create(rawPath));
    }

    private static String body(EntityExchangeResult<byte[]> result) {
        byte[] content = result.getResponseBodyContent();
        return content == null ? "" : new String(content, StandardCharsets.UTF_8);
    }

    /** A successful delete: 204 and, per RFC 9110 §6.4.1 for a 204, no content at all. */
    private void deleteSucceeds(String rawPath) {
        EntityExchangeResult<byte[]> result = delete(rawPath).exchange()
                .expectStatus().isNoContent()
                .expectBody().returnResult();

        byte[] content = result.getResponseBodyContent();
        assertTrue(content == null || content.length == 0,
                "RFC 9110 §15.3.5: a 204 response has no content");
    }

    /** Asserts a full RFC 9457 problem response, every member read off {@link ProblemType}. */
    private WebTestClient.BodyContentSpec expectProblem(
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

    // ---------------------------------------------------------------- documents

    @Test
    @DisplayName("RFC 9110 §9.3.5: deleting a document answers 204 and the resource is gone")
    void deletingADocumentAnswersNoContent() {
        deleteSucceeds("/notes/a.ttl");

        expectProblem(get("/notes/a.ttl"), ProblemType.NOT_FOUND, "/notes/a.ttl");
    }

    @Test
    @DisplayName("Solid Protocol §5.4: the parent stops listing the deleted resource")
    void parentContainmentReflectsTheDelete() {
        EntityExchangeResult<byte[]> before = get("/notes/").exchange()
                .expectStatus().isOk().expectBody().returnResult();
        assertTrue(body(before).contains(BASE + "/notes/a.ttl"),
                "arrange: the container lists the child before the delete");

        deleteSucceeds("/notes/a.ttl");

        EntityExchangeResult<byte[]> after = get("/notes/").exchange()
                .expectStatus().isOk().expectBody().returnResult();
        assertFalse(body(after).contains(BASE + "/notes/a.ttl"),
                "§5.4: the containment triple must go with the resource; got: " + body(after));
        assertTrue(body(after).contains(BASE + "/notes/b.ttl"),
                "the sibling must survive; got: " + body(after));
        assertTrue(body(after).contains("My notes"),
                "and so must the container's own client-authored triples");
    }

    @Test
    @DisplayName("a binary resource deletes like any other document")
    void deletingANonRdfDocumentWorksToo() {
        deleteSucceeds("/logo.png");

        expectProblem(get("/logo.png"), ProblemType.NOT_FOUND, "/logo.png");
    }

    @Test
    @DisplayName("deleting a resource that is not there is a 404 problem document")
    void deletingAMissingResourceIsNotFound() {
        expectProblem(delete("/notes/nope.ttl"), ProblemType.NOT_FOUND, "/notes/nope.ttl");
    }

    @Test
    @DisplayName("the delete is not idempotent-by-silence: the second attempt 404s")
    void deletingTwiceIsNotFoundTheSecondTime() {
        deleteSucceeds("/notes/a.ttl");

        expectProblem(delete("/notes/a.ttl"), ProblemType.NOT_FOUND, "/notes/a.ttl");
    }

    // ---------------------------------------------------------------- containers

    @Test
    @DisplayName("Solid Protocol §5.4: a non-empty container is refused with 409 and a body")
    void nonEmptyContainerIsConflict() {
        expectProblem(delete("/notes/"), ProblemType.CONFLICT, "/notes/");

        get("/notes/").exchange().expectStatus().isOk();
        get("/notes/a.ttl").exchange().expectStatus().isOk();
    }

    @Test
    @DisplayName("Solid Protocol §5.4: an emptied container is deleted, and leaves its parent")
    void emptyContainerIsDeleted() {
        deleteSucceeds("/notes/a.ttl");
        deleteSucceeds("/notes/b.ttl");

        deleteSucceeds("/notes/");

        expectProblem(get("/notes/"), ProblemType.NOT_FOUND, "/notes/");
        EntityExchangeResult<byte[]> root = get("/").exchange()
                .expectStatus().isOk().expectBody().returnResult();
        assertFalse(body(root).contains(BASE + "/notes/"),
                "the storage root must stop listing the deleted container; got: " + body(root));
    }

    // ---------------------------------------------------------------- root protection

    @Test
    @DisplayName("Solid Protocol §5.4: DELETE on the storage root is 405 with Allow")
    void storageRootIsRefusedWith405AndAllow() {
        expectProblem(delete("/"), ProblemType.METHOD_NOT_ALLOWED, "/");

        // The problem document above is the body; the Allow header is the other MUST.
        delete("/").exchange()
                .expectStatus().isEqualTo(ProblemType.METHOD_NOT_ALLOWED.status())
                .expectHeader().valueEquals(HttpHeaders.ALLOW, ROOT_ALLOW);
    }

    @Test
    @DisplayName("RFC 9110 §15.5.6: the 405's Allow lists the methods the root does support")
    void rootAllowExcludesDeleteAndNothingElse() {
        EntityExchangeResult<byte[]> result = delete("/").exchange()
                .expectStatus().isEqualTo(ProblemType.METHOD_NOT_ALLOWED.status())
                .expectBody().returnResult();

        String allow = result.getResponseHeaders().getFirst(HttpHeaders.ALLOW);
        assertNotNull(allow, "RFC 9110 §15.5.6 makes Allow mandatory on a 405");
        List<String> methods = List.of(allow.split(",\\s*"));
        assertFalse(methods.contains(HttpMethod.DELETE.name()),
                "§5.4: DELETE MUST be excluded from the root's Allow; got: " + allow);
        assertTrue(methods.containsAll(List.of(HttpMethod.GET.name(), HttpMethod.HEAD.name(),
                        HttpMethod.OPTIONS.name(), HttpMethod.POST.name(), HttpMethod.PUT.name(),
                        HttpMethod.PATCH.name())),
                "everything the root still supports must be listed; got: " + allow);
    }

    @Test
    @DisplayName("the refused root delete changes nothing: the pod is still there")
    void refusingTheRootDeleteMutatesNothing() {
        delete("/").exchange().expectStatus().isEqualTo(ProblemType.METHOD_NOT_ALLOWED.status());

        get("/").exchange().expectStatus().isOk();
        get("/notes/").exchange().expectStatus().isOk();
        get("/notes/a.ttl").exchange().expectStatus().isOk();
    }

    @Test
    @DisplayName("Solid Protocol §5.4: a successful GET of the root also excludes DELETE")
    void rootReadAdvertisesTheSameAllowAsIts405() {
        // "Server MUST exclude the DELETE method in the field value of the Allow header field,
        // in response to REQUESTS to these resources" — not only in the 405. The two values
        // come from one ResourceKind row, so they cannot drift apart.
        get("/").exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.ALLOW, ROOT_ALLOW);
    }

    @Test
    @DisplayName("root protection is about the root, not about containers in general")
    void aTopLevelContainerIsNotTheRoot() {
        put("/empty/", turtle(""));

        deleteSucceeds("/empty/");
    }

    @Test
    @DisplayName("a non-root container's Allow still offers DELETE")
    void nonRootContainerStillAdvertisesDelete() {
        get("/notes/").exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.ALLOW,
                        "GET, HEAD, OPTIONS, POST, PUT, PATCH, DELETE");
    }

    // ---------------------------------------------------------------- rejected targets

    @Test
    @DisplayName("an unusable request-target is refused before the store is reached")
    void encodedSlashInASegmentIsRejected() {
        expectProblem(delete("/notes/a%2Fb.ttl"), ProblemType.BAD_INPUT, "/notes/a%2Fb.ttl");
    }

    @Test
    @DisplayName("an empty path segment is refused the same way DELETE or not")
    void emptyPathSegmentIsRejected() {
        expectProblem(delete("/notes//a.ttl"), ProblemType.BAD_INPUT, "/notes//a.ttl");
    }

    @Test
    @DisplayName("dot segments are refused rather than silently normalized")
    void dotSegmentIsRejected() {
        expectProblem(delete("/notes/../notes/a.ttl"), ProblemType.BAD_INPUT,
                "/notes/../notes/a.ttl");
    }
}
