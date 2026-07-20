package com.enrichmeai.cistern.webflux;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpMethod;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The invariant that keeps {@link ResourceKind}'s two consumers from ever disagreeing.
 *
 * <p>Two independent parts of the server ask this table what a resource supports, and they must
 * get the same answer or the responses contradict each other:
 *
 * <ul>
 *   <li>{@code ProblemMapper} renders {@link ResourceKind#allow()} into the {@code Allow} header
 *       RFC 9110 §15.5.6 makes mandatory on a 405 (T2.3 made the choice of row path-selective,
 *       so a {@code POST} to a document is not refused with an {@code Allow} listing
 *       {@code POST}).</li>
 *   <li>{@code ConditionalRequests} consults {@link ResourceKind#permits} to decide whether RFC
 *       9110 §13.2.1 requires a request's preconditions to be ignored, because the response
 *       would be a 405 whatever they said (T2.5).</li>
 * </ul>
 *
 * <p>Since T2.5 the {@code Allow} value is <em>derived</em> from the permitted-method list, so
 * the two are one fact by construction. This class pins that rather than trusting it: an edit
 * that reintroduced an independently-written {@code Allow} string would be caught here, and the
 * failure would name the exact method the two disagree about. Without it, the drift would show
 * up only as a 405 that advertises a method the server would in fact refuse — or, worse, as
 * preconditions being silently skipped on a method the resource does support.
 */
class ResourceKindTest {

    /** Every method Cistern serves across Phase 2, so "not permitted" is asserted too. */
    private static final List<HttpMethod> ALL_METHODS = List.of(
            HttpMethod.GET, HttpMethod.HEAD, HttpMethod.OPTIONS,
            HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE);

    private static List<HttpMethod> advertisedBy(ResourceKind kind) {
        return Arrays.stream(kind.allow().split(",\\s*")).map(HttpMethod::valueOf).toList();
    }

    @ParameterizedTest
    @EnumSource(ResourceKind.class)
    @DisplayName("Allow advertises exactly the methods the kind permits — no more, no fewer")
    void allowAndPermitsAreOneFact(ResourceKind kind) {
        List<HttpMethod> advertised = advertisedBy(kind);

        for (HttpMethod method : ALL_METHODS) {
            assertEquals(advertised.contains(method), kind.permits(method),
                    () -> kind + " advertises Allow: " + kind.allow() + " but permits(" + method
                            + ") says " + kind.permits(method)
                            + " — a 405's Allow and the precondition applicability check would"
                            + " disagree about " + method);
        }
    }

    @ParameterizedTest
    @EnumSource(ResourceKind.class)
    @DisplayName("every kind supports the safe methods Solid Protocol §5.2 requires")
    void everyKindSupportsTheMandatorySafeMethods(ResourceKind kind) {
        // §5.2: "Servers MUST support the HTTP GET, HEAD and OPTIONS methods". This is also why
        // ConditionalRequests' applicability check can never suppress a 304 or a conditional
        // GET's 412 — the read methods are in every row.
        for (HttpMethod method : List.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.OPTIONS)) {
            assertTrue(kind.permits(method), kind + " must support " + method);
        }
    }

    @Test
    @DisplayName("Solid Protocol §5.4: the storage root permits everything a container does but DELETE")
    void theStorageRootIsAContainerMinusDelete() {
        assertFalse(ResourceKind.STORAGE_ROOT.permits(HttpMethod.DELETE),
                "§5.4: the server MUST exclude DELETE from the root's Allow");

        for (HttpMethod method : ALL_METHODS) {
            if (!HttpMethod.DELETE.equals(method)) {
                assertEquals(ResourceKind.CONTAINER.permits(method),
                        ResourceKind.STORAGE_ROOT.permits(method),
                        () -> "the root differs from a container in DELETE alone; " + method
                                + " diverged");
            }
        }
    }

    @Test
    @DisplayName("Solid Protocol §5.3: only a container accepts POST")
    void onlyContainersAcceptPost() {
        assertTrue(ResourceKind.CONTAINER.permits(HttpMethod.POST));
        assertTrue(ResourceKind.STORAGE_ROOT.permits(HttpMethod.POST));
        assertFalse(ResourceKind.RDF_DOCUMENT.permits(HttpMethod.POST),
                "a document cannot mint children, so a POST to one is a 405 (T2.3)");
        assertFalse(ResourceKind.NON_RDF_DOCUMENT.permits(HttpMethod.POST));
    }

    @Test
    @DisplayName("the Allow a 405 for POST-to-a-document advertises cannot contain POST")
    void theDocumentRowUsedForA405NeverAdvertisesPost() {
        // ProblemMapper answers a POST to a document with NON_RDF_DOCUMENT's Allow, chosen from
        // the request path. Pinned here as well as over HTTP in ResourceCreateHttpTest, because
        // this is the property that makes the response self-consistent rather than an incidental
        // fact about one row.
        assertFalse(advertisedBy(ResourceKind.NON_RDF_DOCUMENT).contains(HttpMethod.POST));
        assertFalse(advertisedBy(ResourceKind.STORAGE_ROOT).contains(HttpMethod.DELETE));
    }
}
