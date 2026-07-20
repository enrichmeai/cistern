package com.enrichmeai.cistern.webflux;

import com.enrichmeai.cistern.core.Representation;
import com.enrichmeai.cistern.core.ResourceIdentifier;
import com.enrichmeai.cistern.core.ResourceStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T2.8's CORS behaviour, exercised from a fake origin against a real port.
 *
 * <p>Solid Protocol §8.1 is the whole of what is being pinned here: "A server MUST implement the
 * CORS protocol [FETCH] such that, to the extent possible, the browser allows Solid apps to send
 * any request and combination of request headers to the server", "the server MUST set the
 * {@code Access-Control-Allow-Origin} header field value to the valid {@code Origin} header field
 * value from the request and list {@code Origin} in the {@code Vary} header field value", and
 * "The server MUST make all used response headers readable for the Solid app through
 * {@code Access-Control-Expose-Headers}".
 *
 * <p>These are assertions about what a <em>browser</em> would be permitted to do, which is the
 * reason each one is here — a Solid app that cannot read {@code Location} cannot address what it
 * created, and one that cannot send {@code Authorization} cannot authenticate at all.
 *
 * <p>Requests go over a plain socket; see {@link RawHttp} for why neither binding mode of
 * {@code WebTestClient} can be used for CORS assertions in this environment.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "cistern.base-url=http://localhost:3000")
class CorsHttpTest {

    private static final String BASE = "http://localhost:3000";

    /** A fake origin: never configured anywhere, so a pass proves the wildcard default works. */
    private static final String FAKE_ORIGIN = "https://fake-app.example";

    private static final String OTHER_ORIGIN = "https://another-app.example";

    private static final Path STORAGE_ROOT = createTempRoot();

    @LocalServerPort
    private int port;

    @Autowired
    private ResourceStore store;

    @DynamicPropertySource
    static void storageRoot(DynamicPropertyRegistry registry) {
        registry.add("cistern.storage.root", STORAGE_ROOT::toString);
    }

    private static Path createTempRoot() {
        try {
            return Files.createTempDirectory("cistern-t28-cors-");
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

    // ---------------------------------------------------------------- preflight

    @Test
    @DisplayName("a preflight from a fake origin is granted, and echoes that origin back")
    void preflightFromAFakeOriginIsGranted() {
        RawHttp.Response response = preflight("/notes/a.ttl", HttpMethod.PUT);

        assertSuccessful(response);
        // §8.1 requires the request's own origin, not a wildcard. Both halves asserted: a
        // literal "*" would satisfy "permissive" but violate the MUST, so it is ruled out
        // explicitly rather than left to the equality check to catch by luck.
        assertEquals(FAKE_ORIGIN, allowedOriginOf(response));
        assertNotEquals(CisternProperties.Cors.ANY_ORIGIN, allowedOriginOf(response),
                "§8.1: the response must name the request's origin, never the literal *");
    }

    @Test
    @DisplayName("every method the server serves may be preflighted — the union of ResourceKind")
    void allowedMethodsAreTheUnionOfTheResourceKindTable() {
        for (HttpMethod method : ResourceKind.supportedMethods()) {
            RawHttp.Response response = preflight("/notes/a.ttl", method);
            assertSuccessful(response);

            List<String> allowed =
                    listOf(response.first(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS));
            assertTrue(allowed.contains(method.name()),
                    () -> "the server serves " + method
                            + " but will not let a browser preflight it; got " + allowed);
        }
    }

    @Test
    @DisplayName("the headers a Solid client sends are all allowed, Authorization by name")
    void everyHeaderASolidClientSendsIsAllowed() {
        // The Fetch standard excludes Authorization from the Access-Control-Allow-Headers
        // wildcard, so a "*" configuration would fail exactly this assertion while appearing to
        // permit everything. That is why AllowedRequestHeader enumerates rather than wildcards.
        String requested =
                String.join(HttpConstants.LIST_SEPARATOR, AllowedRequestHeader.fieldNames());

        RawHttp.Response response = RawHttp.request(port, HttpMethod.OPTIONS, "/notes/a.ttl")
                .header(HttpHeaders.ORIGIN, FAKE_ORIGIN)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, HttpMethod.PUT.name())
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, requested)
                .send();

        assertSuccessful(response);
        List<String> granted = listOf(response.first(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS));
        for (String header : AllowedRequestHeader.fieldNames()) {
            assertTrue(granted.stream().anyMatch(header::equalsIgnoreCase),
                    () -> header + " is not granted by preflight, so a browser will never send it");
        }
    }

    @Test
    @DisplayName("a header outside the set is refused rather than silently granted")
    void anUnlistedHeaderIsRefused() {
        // The complement of the test above: without this, an implementation that granted
        // everything unconditionally would pass and the enumeration would be decorative.
        RawHttp.Response response = RawHttp.request(port, HttpMethod.OPTIONS, "/notes/a.ttl")
                .header(HttpHeaders.ORIGIN, FAKE_ORIGIN)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, HttpMethod.PUT.name())
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "X-Cistern-Not-A-Real-Header")
                .send();

        assertEquals(HttpStatus.FORBIDDEN.value(), response.status());
    }

    @Test
    @DisplayName("a preflight for a URI that does not exist yet succeeds — creating from a browser")
    void preflightForAResourceThatDoesNotExistYetSucceeds() {
        // The case that decides whether OPTIONS-on-missing may be a 404. A browser must
        // preflight a PUT that creates, and that PUT is addressed to a URI with nothing behind
        // it. The CORS filter answers ahead of routing and consults no storage, so this passes
        // while a plain OPTIONS on the same URI is a 404 — the two together are what make the
        // 404 choice safe.
        RawHttp.Response preflight = preflight("/notes/not-created-yet.ttl", HttpMethod.PUT);
        assertSuccessful(preflight);
        assertEquals(FAKE_ORIGIN, allowedOriginOf(preflight));

        RawHttp.Response plainOptions =
                RawHttp.request(port, HttpMethod.OPTIONS, "/notes/not-created-yet.ttl").send();
        assertEquals(HttpStatus.NOT_FOUND.value(), plainOptions.status(),
                "a plain OPTIONS on a missing resource is still a 404");
    }

    @Test
    @DisplayName("credentials are never allowed, which is what keeps permissive origins safe")
    void credentialsAreNeverAllowed() {
        // Fetch forbids credentials together with a permissive origin, and Solid-OIDC has no
        // use for them: it authenticates with an explicit Authorization header plus a DPoP
        // proof, neither of which a browser attaches by itself. Allowing them would add ambient
        // authority any page could spend as the user.
        RawHttp.Response response = preflight("/notes/a.ttl", HttpMethod.PUT);

        assertFalse(response.has(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS),
                "Access-Control-Allow-Credentials must not be sent");
    }

    // ---------------------------------------------------------------- actual requests

    @Test
    @DisplayName("§8.1: every response header Cistern emits is exposed to the Solid app")
    void allUsedResponseHeadersAreExposed() {
        List<String> exposed = exposedHeadersOf(crossOriginGet("/notes/a.ttl"));

        assertEquals(ExposedResponseHeader.fieldNames(), exposed,
                "the exposed set must be exactly the enumerated one — §8.1 says to enumerate"
                        + " rather than resort to *");
    }

    @Test
    @DisplayName("the headers the Solid protocol depends on are individually readable")
    void theProtocolCriticalHeadersAreReadable() {
        // Named one by one rather than left to the set comparison above, because each is a
        // separate capability a browser app loses if it goes missing, and the failure message
        // should say which.
        List<String> exposed = exposedHeadersOf(crossOriginGet("/notes/a.ttl"));

        for (String required : List.of(HttpHeaders.ETAG, HttpHeaders.LINK, HttpHeaders.LOCATION,
                HttpConstants.WAC_ALLOW, HttpHeaders.ACCEPT_PATCH, HttpConstants.ACCEPT_POST,
                HttpConstants.ACCEPT_PUT, HttpHeaders.LAST_MODIFIED)) {
            assertTrue(exposed.stream().anyMatch(required::equalsIgnoreCase),
                    () -> required + " is not exposed, so a browser-based Solid app cannot read it");
        }
    }

    @Test
    @DisplayName("§8.1: Vary lists Origin as well as Accept on a negotiated cross-origin read")
    void varyListsOriginAlongsideAccept() {
        // The regression this pins was real: the CORS processor writes Vary: Origin before the
        // handler runs, and the functional ServerResponse put its own Vary: Accept over the
        // top, so the Origin entry vanished from exactly the responses that carry an echoed
        // Access-Control-Allow-Origin. A shared cache could then serve one origin's response to
        // another. See OriginVaryFilter.
        RawHttp.Response response = crossOriginGet("/notes/a.ttl");
        assertEquals(HttpStatus.OK.value(), response.status());

        List<String> vary = varyOf(response);
        assertTrue(vary.stream().anyMatch(HttpHeaders.ORIGIN::equalsIgnoreCase),
                () -> "§8.1 requires Origin in Vary; got " + vary);
        assertTrue(vary.stream().anyMatch(HttpHeaders.ACCEPT::equalsIgnoreCase),
                () -> "RFC 9110 §12.5.5 requires Accept in Vary for a negotiated read; got " + vary);
    }

    @Test
    @DisplayName("Vary lists Origin even when the handler set no Vary of its own")
    void varyListsOriginOnAnUnnegotiatedResponse() {
        RawHttp.Response response = RawHttp.request(port, HttpMethod.OPTIONS, "/notes/a.ttl")
                .header(HttpHeaders.ORIGIN, FAKE_ORIGIN)
                .send();

        assertEquals(HttpStatus.NO_CONTENT.value(), response.status());
        assertTrue(varyOf(response).stream().anyMatch(HttpHeaders.ORIGIN::equalsIgnoreCase),
                () -> "§8.1 requires Origin in Vary on every response; got " + varyOf(response));
    }

    @Test
    @DisplayName("two different origins each get their own echoed back")
    void eachOriginGetsItsOwnAnswer() {
        // Together with Vary: Origin, this is what makes the echo correct rather than merely
        // present: the answer genuinely differs per origin, which is why the cache must be told.
        assertEquals(FAKE_ORIGIN, allowedOriginOf(crossOriginGet("/notes/a.ttl")));

        RawHttp.Response other = RawHttp.request(port, HttpMethod.GET, "/notes/a.ttl")
                .header(HttpHeaders.ORIGIN, OTHER_ORIGIN)
                .send();
        assertEquals(OTHER_ORIGIN, allowedOriginOf(other));
    }

    @Test
    @DisplayName("CORS headers are present on an error response, so a browser can read the 404")
    void corsHeadersArePresentOnErrorResponses() {
        // Without these a browser reports an opaque network failure instead of the RFC 9457
        // problem document T2.6 went to the trouble of producing.
        RawHttp.Response response = crossOriginGet("/notes/absent.ttl");

        assertEquals(HttpStatus.NOT_FOUND.value(), response.status());
        assertEquals(FAKE_ORIGIN, allowedOriginOf(response));
        assertTrue(response.has(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS),
                "a browser must be able to read the problem document's headers too");
    }

    @Test
    @DisplayName("a same-origin request is unaffected — no CORS headers invented for it")
    void aRequestWithNoOriginIsNotACorsRequest() {
        RawHttp.Response response = RawHttp.request(port, HttpMethod.GET, "/notes/a.ttl").send();

        assertEquals(HttpStatus.OK.value(), response.status());
        assertFalse(response.has(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    // ---------------------------------------------------------------- helpers

    private RawHttp.Response preflight(String target, HttpMethod method) {
        return RawHttp.request(port, HttpMethod.OPTIONS, target)
                .header(HttpHeaders.ORIGIN, FAKE_ORIGIN)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, method.name())
                .send();
    }

    private RawHttp.Response crossOriginGet(String target) {
        return RawHttp.request(port, HttpMethod.GET, target)
                .header(HttpHeaders.ORIGIN, FAKE_ORIGIN)
                .send();
    }

    private static void assertSuccessful(RawHttp.Response response) {
        assertTrue(HttpStatus.valueOf(response.status()).is2xxSuccessful(),
                () -> "expected a granted CORS response, got " + response.status()
                        + " with headers " + response.headers());
    }

    private static String allowedOriginOf(RawHttp.Response response) {
        return response.first(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);
    }

    private static List<String> exposedHeadersOf(RawHttp.Response response) {
        return listOf(response.first(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS));
    }

    /** {@code Vary} may arrive as several field lines; {@link HttpHeaders#getVary} unifies them. */
    private static List<String> varyOf(RawHttp.Response response) {
        return response.headers().getVary();
    }

    /** The members of an RFC 9110 §5.6.1 comma-delimited list value. */
    private static List<String> listOf(String fieldValue) {
        return fieldValue == null ? List.of() : Arrays.asList(fieldValue.split(",\\s*"));
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
