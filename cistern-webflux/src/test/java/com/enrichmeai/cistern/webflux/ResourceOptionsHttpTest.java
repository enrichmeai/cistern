package com.enrichmeai.cistern.webflux;

import com.enrichmeai.cistern.core.Representation;
import com.enrichmeai.cistern.core.ResourceIdentifier;
import com.enrichmeai.cistern.core.ResourceStore;
import com.enrichmeai.cistern.core.ldp.Ldp;
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
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T2.8's {@code OPTIONS} over real HTTP: router, handler, {@code LdpService} and the production
 * {@code FileResourceStore} on a temp directory, with nothing mocked.
 *
 * <p>What these assertions pin, and where each comes from:
 * <ul>
 *   <li>{@code Allow} and the {@code Accept-*} fields per resource kind — Solid Protocol §5.2,
 *       with §5.4's storage-root exclusion of {@code DELETE}. Every expectation is read off
 *       {@link ResourceKind} rather than spelled out, so this class tests that {@code OPTIONS}
 *       <em>reports the table</em>; that the table is right is {@link ResourceKindTest}'s job.
 *       Spelling the strings out here would create the second source of truth the architect
 *       requirement on #19 exists to prevent — the test would then have to be edited when T2.7
 *       refines {@code Accept-Patch}, and could be edited to agree with a wrong answer.</li>
 *   <li>Preconditions ignored — RFC 9110 §13.2.1, which names {@code OPTIONS} explicitly.</li>
 *   <li>{@code OPTIONS *} — RFC 9110 §9.3.7.</li>
 * </ul>
 */
@SpringBootTest(properties = "cistern.base-url=http://localhost:3000")
@AutoConfigureWebTestClient
class ResourceOptionsHttpTest {

    private static final String BASE = "http://localhost:3000";

    private static final Path STORAGE_ROOT = createTempRoot();

    private static final byte[] PNG_BYTES = {
            (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x01};

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
            return Files.createTempDirectory("cistern-t28-");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @BeforeEach
    void seedPod() {
        put("/", turtle(""));
        put("/notes/", turtle(""));
        put("/notes/a.ttl", turtle("<> <https://vocab.example/k> \"v\" ."));
        put("/logo.png", new Representation("image/png", PNG_BYTES));
    }

    // ---------------------------------------------------------------- per-kind advertisement

    @Test
    @DisplayName("OPTIONS on an RDF document reports the RDF_DOCUMENT row")
    void optionsOnRdfDocument() {
        assertAdvertises("/notes/a.ttl", ResourceKind.RDF_DOCUMENT);
    }

    @Test
    @DisplayName("OPTIONS on a binary document reports the NON_RDF_DOCUMENT row (no Accept-Patch)")
    void optionsOnBinaryDocument() {
        assertAdvertises("/logo.png", ResourceKind.NON_RDF_DOCUMENT);
    }

    @Test
    @DisplayName("OPTIONS on a container reports the CONTAINER row, POST and Accept-Post included")
    void optionsOnContainer() {
        assertAdvertises("/notes/", ResourceKind.CONTAINER);
    }

    @Test
    @DisplayName("Solid Protocol §5.4: OPTIONS on the storage root excludes DELETE")
    void optionsOnStorageRoot() {
        assertAdvertises("/", ResourceKind.STORAGE_ROOT);

        assertTrue(allowOf("/").stream().noneMatch(HttpMethod.DELETE.name()::equals),
                "§5.4: the server MUST exclude DELETE from the root's Allow");
    }

    /**
     * The whole per-kind contract in one place: every header {@code OPTIONS} advertises is
     * compared against {@link ResourceKind}, so the two cannot drift and T2.7's refinement of
     * {@code Accept-Patch} flows through without this file being touched.
     */
    private void assertAdvertises(String rawPath, ResourceKind expected) {
        HttpHeaders headers = options(rawPath).expectStatus().isNoContent()
                .expectBody().isEmpty().getResponseHeaders();

        assertEquals(expected.allow(), headers.getFirst(HttpHeaders.ALLOW));
        assertEquals(expected.acceptPut(), headers.getFirst(HttpConstants.ACCEPT_PUT));
        assertEquals(expected.acceptPost(), headers.getFirst(HttpConstants.ACCEPT_POST));
        assertEquals(expected.acceptPatch(), headers.getFirst(HttpHeaders.ACCEPT_PATCH));
        assertEquals(expected.linkTypeValues(), headers.get(HttpHeaders.LINK));

        // No representation was selected, so there is no validator this response could be
        // describing (RFC 9110 §13.2.1 — OPTIONS does not involve representation selection).
        assertNull(headers.getFirst(HttpHeaders.ETAG));
        assertNull(headers.getFirst(HttpHeaders.LAST_MODIFIED));
    }

    @Test
    @DisplayName("Accept-Patch is present exactly where PATCH is in Allow (Solid Protocol §5.2)")
    void acceptHeadersMatchAllow() {
        // §5.2 requires the Accept-* fields to "correspond to acceptable HTTP methods listed in
        // Allow header field value". Asserted over the wire on one resource of every kind, so a
        // handler that emitted an Accept-* the Allow contradicts would fail here rather than in
        // a client's debugger.
        for (String rawPath : List.of("/", "/notes/", "/notes/a.ttl", "/logo.png")) {
            HttpHeaders headers = options(rawPath).expectStatus().isNoContent()
                    .expectBody().isEmpty().getResponseHeaders();
            List<String> allow = allowOf(rawPath);

            assertEquals(allow.contains(HttpMethod.PATCH.name()),
                    headers.getFirst(HttpHeaders.ACCEPT_PATCH) != null,
                    () -> rawPath + ": Accept-Patch and Allow disagree about PATCH");
            assertEquals(allow.contains(HttpMethod.POST.name()),
                    headers.getFirst(HttpConstants.ACCEPT_POST) != null,
                    () -> rawPath + ": Accept-Post and Allow disagree about POST");
            assertEquals(allow.contains(HttpMethod.PUT.name()),
                    headers.getFirst(HttpConstants.ACCEPT_PUT) != null,
                    () -> rawPath + ": Accept-Put and Allow disagree about PUT");
        }
    }

    // ---------------------------------------------------------------- preconditions ignored

    @Test
    @DisplayName("RFC 9110 §13.2.1: a stale If-Match on OPTIONS is ignored, not answered with 412")
    void staleIfMatchIsIgnoredOnOptions() {
        // "a server MUST ignore the conditional request header fields defined by this
        // specification when received with a request method that does not involve the selection
        // or modification of a selected representation, such as CONNECT, OPTIONS, or TRACE."
        // The architect requirement carried in from T2.5 (issue #19).
        client.options().uri(URI.create("/notes/a.ttl"))
                .header(HttpHeaders.IF_MATCH, "\"definitely-stale\"")
                .exchange()
                .expectStatus().isNoContent()
                .expectHeader().valueEquals(HttpHeaders.ALLOW, ResourceKind.RDF_DOCUMENT.allow());
    }

    @Test
    @DisplayName("the control: the same stale If-Match on GET does answer 412")
    void theSameStaleIfMatchStillFailsOnGet() {
        // Without this, the test above would pass just as well against a server that had lost
        // the ability to evaluate preconditions at all.
        client.get().uri(URI.create("/notes/a.ttl"))
                .header(HttpHeaders.IF_MATCH, "\"definitely-stale\"")
                .exchange()
                .expectStatus().isEqualTo(org.springframework.http.HttpStatus.PRECONDITION_FAILED);
    }

    @Test
    @DisplayName("If-None-Match on OPTIONS is ignored too — no 304")
    void ifNoneMatchIsIgnoredOnOptions() {
        client.options().uri(URI.create("/notes/a.ttl"))
                .header(HttpHeaders.IF_NONE_MATCH, "*")
                .exchange()
                .expectStatus().isNoContent();
    }

    // ---------------------------------------------------------------- absence

    @Test
    @DisplayName("OPTIONS on a resource that is not there answers 404, like GET on the same URI")
    void optionsOnMissingResourceIsNotFound() {
        // §9.3.7 scopes the response to "the target resource" and Solid Protocol §5.2 requires
        // Allow to describe "the target resource"; there is none, so there is no method set to
        // report. The browser case people fear — preflighting a create — never reaches this
        // handler; CorsHttpTest pins that.
        client.options().uri(URI.create("/notes/absent.ttl")).exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.type").isEqualTo(ProblemType.NOT_FOUND.uri().toString())
                .jsonPath("$.status").isEqualTo(404);
    }

    // ---------------------------------------------------------------- asterisk-form

    @Test
    @DisplayName("RFC 9110 §9.3.7: OPTIONS * describes the server, so Allow is the union of kinds")
    void asteriskFormDescribesTheServer() {
        // Reactor Netty resolves the asterisk-form request-target to an empty path, which is
        // what the handler matches on; ResourceOptionsAsteriskFormTest proves that end to end
        // over a real socket, since WebTestClient cannot send the asterisk-form itself.
        HttpHeaders headers = options("").expectStatus().isNoContent()
                .expectBody().isEmpty().getResponseHeaders();

        assertEquals(ResourceKind.supportedMethodsAllow(), headers.getFirst(HttpHeaders.ALLOW));
        // "applies to the server in general rather than to a specific resource" — so nothing
        // resource-shaped is claimed.
        assertNull(headers.getFirst(HttpConstants.ACCEPT_PUT));
        assertNull(headers.getFirst(HttpConstants.ACCEPT_POST));
        assertNull(headers.getFirst(HttpHeaders.ACCEPT_PATCH));
        assertNull(headers.getFirst(HttpHeaders.LINK));
    }

    @Test
    @DisplayName("the server-wide Allow is the union of every kind's methods, computed not written")
    void serverWideAllowIsTheUnionOfTheTable() {
        List<String> serverWide = Arrays.asList(ResourceKind.supportedMethodsAllow().split(",\\s*"));

        for (ResourceKind kind : ResourceKind.values()) {
            for (String method : kind.allow().split(",\\s*")) {
                assertTrue(serverWide.contains(method),
                        () -> kind + " permits " + method + " but the server-wide Allow omits it");
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private WebTestClient.ResponseSpec options(String rawPath) {
        return client.options().uri(URI.create(rawPath)).exchange();
    }

    private List<String> allowOf(String rawPath) {
        String allow = options(rawPath).expectBody().isEmpty()
                .getResponseHeaders().getFirst(HttpHeaders.ALLOW);
        return Arrays.asList(allow.split(",\\s*"));
    }

    private static Representation turtle(String content) {
        return new Representation(Representation.TURTLE, content.getBytes(StandardCharsets.UTF_8));
    }

    private void put(String path, Representation representation) {
        StepVerifier.create(store.put(new ResourceIdentifier(URI.create(BASE + path)), representation))
                .expectNextCount(1)
                .verifyComplete();
    }
}
