package com.enrichmeai.cistern.webflux;

import com.enrichmeai.cistern.core.ldp.LdpKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpMethod;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

    /**
     * T2.7's seam: core decides what a resource <em>is</em> ({@link LdpKind}) and this table says
     * what that kind advertises. {@code ResourceKind.forKind} must therefore be total — a kind
     * added in core with no interface here would otherwise surface as a
     * {@code NullPointerException} while rendering a 405, which is the worst possible place to
     * discover it.
     */
    @ParameterizedTest
    @EnumSource(LdpKind.class)
    @DisplayName("every core LdpKind has exactly one interface row")
    void everyLdpKindHasARow(LdpKind kind) {
        ResourceKind resourceKind = ResourceKind.forKind(kind);

        assertNotNull(resourceKind, "no ResourceKind row describes " + kind
                + " — add one, or ProblemMapper will fail rendering its 405");
        assertEquals(kind, resourceKind.ldpKind(), "a row must describe the kind it is keyed by");
    }

    /**
     * Solid Protocol §5.3.1 scopes {@code PATCH} to RDF documents ("Servers MUST accept a
     * {@code PATCH} request with an N3 Patch body when the target of the request is an RDF
     * document"), and RFC 5789 §3.1 makes {@code Accept-Patch}'s presence "an implicit indication
     * that PATCH is allowed on the resource".
     *
     * <p>So the two must agree exactly, for every kind: advertising {@code Accept-Patch} on a
     * resource that would answer 405 to a {@code PATCH}, or omitting it from one that accepts
     * patches, are both self-contradicting responses. This is the invariant T2.7 adds beside
     * T2.5's — {@code Allow} and {@code permits} are one fact, and now so are {@code PATCH} and
     * {@code Accept-Patch}.
     */
    @ParameterizedTest
    @EnumSource(ResourceKind.class)
    @DisplayName("Accept-Patch is advertised exactly where PATCH is permitted")
    void acceptPatchAgreesWithPatch(ResourceKind kind) {
        assertEquals(kind.permits(HttpMethod.PATCH), kind.acceptPatch() != null,
                () -> kind + " permits(PATCH)=" + kind.permits(HttpMethod.PATCH)
                        + " but Accept-Patch=" + kind.acceptPatch()
                        + " — RFC 5789 §3.1 makes the field's presence mean PATCH is allowed");
    }

    /** §5.3.1: a binary resource has no graph, so it is the one kind that is not patchable. */
    @Test
    @DisplayName("Solid Protocol §5.3.1: only RDF sources accept PATCH")
    void onlyRdfSourcesAcceptPatch() {
        assertTrue(ResourceKind.CONTAINER.permits(HttpMethod.PATCH), "a container is an RDF source (§4.2)");
        assertTrue(ResourceKind.STORAGE_ROOT.permits(HttpMethod.PATCH));
        assertTrue(ResourceKind.RDF_DOCUMENT.permits(HttpMethod.PATCH));
        assertFalse(ResourceKind.NON_RDF_DOCUMENT.permits(HttpMethod.PATCH),
                "there is no graph in a byte stream to patch, so a PATCH of one is a 405 (T2.7)");
    }

    /**
     * The distinction that made the kind a core-carried fact: these two rows are identical apart
     * from {@code PATCH}, and nothing in a URI can tell them apart — only the stored media type
     * can. If this ever became false, deriving the kind from the request path would be safe again
     * and T2.7's plumbing would be pointless; it is asserted so the reason stays visible.
     */
    @Test
    @DisplayName("the two document rows differ in PATCH alone — which is why the URI cannot decide")
    void theDocumentRowsDifferOnlyInPatch() {
        for (HttpMethod method : ALL_METHODS) {
            if (!HttpMethod.PATCH.equals(method)) {
                assertEquals(ResourceKind.RDF_DOCUMENT.permits(method),
                        ResourceKind.NON_RDF_DOCUMENT.permits(method),
                        () -> "the document rows must differ in PATCH alone; " + method + " diverged");
            }
        }
        assertTrue(ResourceKind.RDF_DOCUMENT.permits(HttpMethod.PATCH));
        assertFalse(ResourceKind.NON_RDF_DOCUMENT.permits(HttpMethod.PATCH));
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
