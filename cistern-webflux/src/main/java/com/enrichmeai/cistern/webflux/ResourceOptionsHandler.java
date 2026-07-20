package com.enrichmeai.cistern.webflux;

import com.enrichmeai.cistern.core.CisternException;
import com.enrichmeai.cistern.core.ResourceIdentifier;
import com.enrichmeai.cistern.core.ldp.LdpService;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

/**
 * Serves {@code OPTIONS} (T2.8).
 *
 * <p>Solid Protocol §5.2 makes it mandatory — "Servers MUST support the HTTP {@code GET},
 * {@code HEAD} and {@code OPTIONS} methods" — and §8.1 makes it load-bearing for browser
 * clients: "A server MUST also support the HTTP {@code OPTIONS} method such that it can respond
 * appropriately to CORS preflight requests."
 *
 * <p>Those are two different jobs and this class only does the first. A <em>preflight</em> is a
 * CORS negotiation about a request that has not happened yet; it is answered by the
 * {@code CorsWebFilter} wired in {@code CisternWebFluxConfiguration}, before routing, and never
 * reaches this handler. What arrives here is a plain {@code OPTIONS} — a client asking what it
 * may do with a resource — which RFC 9110 §9.3.7 defines as a request for "the communication
 * options available for the target resource".
 *
 * <h2>Every value comes from {@link ResourceKind}</h2>
 * {@code Allow}, {@code Accept-Put}, {@code Accept-Post}, {@code Accept-Patch} and the typing
 * {@code Link}s are read off the same one table {@code GET}, {@code PUT}, {@code POST} and the
 * error mapper's 405 read. That is the architect requirement on this ticket, and the reason for
 * it is that {@code OPTIONS} is the header set's own advertisement: a second source of truth
 * here would not merely drift, it would drift in the one response whose entire purpose is to be
 * authoritative about the first. It also means T2.7's work lands here for free — when
 * {@code PATCH} learns to distinguish an RDF source from a binary, this handler starts saying so
 * without being edited.
 *
 * <h2>Preconditions are not evaluated</h2>
 * This handler never touches {@link ConditionalRequests}, and that absence is the
 * implementation. RFC 9110 §13.2.1: "a server MUST ignore the conditional request header fields
 * defined by this specification when received with a request method that does not involve the
 * selection or modification of a selected representation, such as CONNECT, {@code OPTIONS}, or
 * TRACE." An {@code OPTIONS} carrying a stale {@code If-Match} therefore answers 204, not 412 —
 * pinned by {@code ResourceOptionsHttpTest}. For the same reason the response carries no
 * {@code ETag} and no {@code Last-Modified}: no representation was selected, so there is no
 * validator this response could honestly be describing.
 *
 * <h2>Why 204</h2>
 * RFC 9110 §9.3.7 prescribes no status for a successful {@code OPTIONS} and Cistern has no
 * options body to send, so 204 (No Content) says exactly that. The alternative — 200 — would
 * oblige a {@code Content-Length: 0} ("A server MUST generate a Content-Length field with a
 * value of 0 if no content is sent in the response"), whereas §8.6 forbids that field on a 204.
 * 204 is the shorter true statement.
 *
 * <h2>Absence is a 404</h2>
 * See {@link #options}.
 *
 * <h2>Errors</h2>
 * Nothing here maps a status code (ground rule 4) and there is no {@code onErrorResume}.
 * Absence leaves core as {@link CisternException.NotFound} and an unusable request-target as
 * {@link CisternException.BadInput}; the single error mapper (T2.6) renders both. The body sits
 * inside {@code Mono.defer} so {@link RequestPaths}' synchronous throw becomes an error signal.
 */
@Component
public class ResourceOptionsHandler {

    /**
     * RFC 9110 §9.3.7's asterisk-form request-target: "applies to the server in general rather
     * than to a specific resource". Not a path, which is why it is matched before
     * {@link RequestPaths} is asked to make an identifier of it.
     */
    private static final String ASTERISK_FORM = "*";

    /**
     * What the asterisk-form actually looks like by the time it reaches this handler.
     *
     * <p>{@code *} is a legal request-target but not a legal URI path, so Reactor Netty's
     * request URI resolution yields an <em>empty</em> path rather than the literal asterisk —
     * verified by curl {@code --request-target '*'} against the running server, which is why
     * this constant exists rather than a lone {@code "*"} comparison that would never fire. No
     * other request-target produces an empty path: origin-form always begins with {@code /} and
     * absolute-form carries the path through, so empty is an unambiguous marker.
     */
    private static final String ASTERISK_FORM_PATH = "";

    private final LdpService ldp;
    private final RequestPaths requestPaths;

    public ResourceOptionsHandler(LdpService ldp, RequestPaths requestPaths) {
        this.ldp = ldp;
        this.requestPaths = requestPaths;
    }

    /**
     * The handler behind {@code OPTIONS}, in both of the forms RFC 9110 §9.3.7 defines.
     *
     * <h3>{@code OPTIONS *} — the server, not a resource</h3>
     * "An OPTIONS request with an asterisk ("*") as the request-target ... applies to the server
     * in general rather than to a specific resource." So it is answered without consulting
     * storage at all, with the union of every method the server supports
     * ({@link ResourceKind#supportedMethodsAllow()}), and without {@code Accept-Put} and
     * friends — those describe a resource, and this response is about none. It is dispatched
     * before {@link RequestPaths} sees it because {@code *} is not a path: asked to resolve it,
     * {@code RequestPaths} would correctly refuse it as a malformed request-target (400), which
     * is the wrong answer to a legal request.
     *
     * <h3>{@code OPTIONS} on a resource that is not there — 404</h3>
     * The spec settles this indirectly but consistently. §9.3.7 scopes the response to "the
     * target resource", and Solid Protocol §5.2 requires the {@code Allow} of a successful
     * response to "indicate the HTTP methods supported by the target resource" — when nothing
     * exists at the URI there is no target resource, and any method set this server named would
     * be a claim about a resource it does not have. Answering 404 also keeps {@code OPTIONS}
     * consistent with {@code GET} and {@code HEAD} on the same URI, which is what a client
     * probing for existence expects.
     *
     * <p>This costs browser clients nothing, which is the check that matters: the case people
     * fear — a preflight for a {@code PUT} that will <em>create</em> a resource, addressed to a
     * URI that necessarily does not exist yet — never reaches this method. The CORS filter
     * answers preflights before routing and consults no storage, so creating a resource from a
     * browser works exactly as it must. Pinned by {@code CorsHttpTest}.
     */
    public Mono<ServerResponse> options(ServerRequest request) {
        return Mono.defer(() -> {
            if (isAsteriskForm(request)) {
                return serverOptions();
            }
            ResourceIdentifier target = requestPaths.identifierFor(request);
            return ldp.read(target).flatMap(view -> resourceOptions(ResourceKind.of(view)));
        });
    }

    /**
     * Whether this is the asterisk-form of §9.3.7. Both spellings are accepted — the literal
     * {@code *}, in case a server adapter passes it through untouched, and the empty path
     * Reactor Netty actually produces (see {@link #ASTERISK_FORM_PATH}).
     */
    private static boolean isAsteriskForm(ServerRequest request) {
        String rawPath = request.uri().getRawPath();
        return ASTERISK_FORM.equals(rawPath) || ASTERISK_FORM_PATH.equals(rawPath);
    }

    /** The whole-server answer: which methods exist here, and nothing about any resource. */
    private static Mono<ServerResponse> serverOptions() {
        return ServerResponse.noContent()
                .headers(headers -> headers.set(HttpHeaders.ALLOW,
                        ResourceKind.supportedMethodsAllow()))
                .build();
    }

    /**
     * The per-resource answer. Solid Protocol §5.2 requires the {@code Allow} and the
     * {@code Accept-*} fields together — the {@code Accept-*} values "correspond to acceptable
     * HTTP methods listed in {@code Allow} header field value" — which is one table lookup here
     * precisely so that they cannot disagree.
     */
    private static Mono<ServerResponse> resourceOptions(ResourceKind kind) {
        return ServerResponse.noContent()
                .headers(headers -> {
                    headers.set(HttpHeaders.ALLOW, kind.allow());
                    setIfPresent(headers, HttpConstants.ACCEPT_PUT, kind.acceptPut());
                    setIfPresent(headers, HttpConstants.ACCEPT_POST, kind.acceptPost());
                    setIfPresent(headers, HttpHeaders.ACCEPT_PATCH, kind.acceptPatch());
                    // LDP §4.2.1.4 — the interaction model is discoverable from any response,
                    // and OPTIONS is the response a client asks discovery questions of.
                    for (String linkValue : kind.linkTypeValues()) {
                        headers.add(HttpHeaders.LINK, linkValue);
                    }
                })
                .build();
    }

    private static void setIfPresent(HttpHeaders headers, String name, String value) {
        if (value != null) {
            headers.set(name, value);
        }
    }
}
