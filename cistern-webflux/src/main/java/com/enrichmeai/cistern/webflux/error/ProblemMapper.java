package com.enrichmeai.cistern.webflux.error;

import com.enrichmeai.cistern.core.CisternException;
import com.enrichmeai.cistern.core.ldp.LdpKind;
import com.enrichmeai.cistern.core.rdf.N3Patch;
import com.enrichmeai.cistern.webflux.ResourceKind;
import java.net.URI;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.ErrorResponse;
import org.springframework.web.server.ServerWebExchange;

/**
 * Chooses the {@link ProblemDocument} for a {@link Throwable}. Pure: no I/O, no HTTP writing
 * — {@link CisternErrorWebExceptionHandler} does both. Splitting it out keeps the table
 * readable and directly unit-testable.
 *
 * <p><strong>This is the only place in Cistern where a domain error becomes a status
 * code</strong> (CLAUDE.md ground rule 4). Handlers signal {@link CisternException} subtypes
 * through the reactive chain and never speak HTTP.
 *
 * <p>The table itself is the exhaustive switch in {@link #domain} over
 * {@link CisternException}'s sealed {@code permits} list (#60). Because the hierarchy is
 * closed, the compiler — not a reflection-based test — is what guarantees every subtype has a
 * status: adding one in cistern-core without a row here fails the build.
 * {@code AccessDenied} is the one domain error whose status is not a function of its class
 * alone; see {@link RequestAuthentication}.
 */
final class ProblemMapper {

    private final RequestAuthentication requestAuthentication;

    ProblemMapper(RequestAuthentication requestAuthentication) {
        this.requestAuthentication = requestAuthentication;
    }

    /** A rendered problem: the RFC 9457 body plus any headers its status code requires. */
    record Problem(ProblemDocument body, HttpHeaders headers) {
    }

    Problem map(Throwable error, ServerWebExchange exchange) {
        URI instance = URI.create(exchange.getRequest().getPath().value());

        if (error instanceof CisternException domainError) {
            return new Problem(domain(domainError, instance, exchange),
                    domainHeaders(domainError, exchange));
        }
        // Every Spring web exception worth mapping (UnsupportedMediaTypeStatusException →
        // 415, MethodNotAllowedException → 405 + Allow, ServerWebInputException → 400,
        // NotAcceptableStatusException → 406, the NoResourceFoundException raised for an
        // unrouted path) implements ErrorResponse and already carries both the right status
        // and the headers that status mandates. One branch covers them all; enumerating
        // subclasses would only be a chance to get one wrong.
        if (error instanceof ErrorResponse springError) {
            HttpHeaders headers = new HttpHeaders();
            headers.putAll(springError.getHeaders());
            return new Problem(
                    ProblemDocument.blank(springError.getStatusCode(), springError.getBody().getDetail(), instance),
                    headers);
        }
        // A caller programming error surfacing from core — children() on a document, say.
        if (error instanceof IllegalArgumentException) {
            return blank(HttpStatus.BAD_REQUEST, error.getMessage(), instance);
        }
        // IllegalStateException (server-side corruption: an unparseable stored representation)
        // and everything unforeseen land here. ProblemDocument replaces the detail on any 5xx,
        // so nothing about the fault reaches the client; the cause goes to the log instead.
        return blank(HttpStatus.INTERNAL_SERVER_ERROR, null, instance);
    }

    /**
     * The headers a domain error's status code makes mandatory. Spring's own exceptions carry
     * theirs already (see {@link #map}); Cistern's do not, because a {@link CisternException}
     * knows nothing about HTTP — so where a status has a required header, this is where it is
     * supplied.
     *
     * <p>Two statuses have one:
     * <ul>
     *   <li><b>405 → {@code Allow}.</b> RFC 9110 §15.5.6: "The origin server MUST generate an
     *       {@code Allow} header field in a 405 response containing a list of the target
     *       resource's currently supported methods." The value is {@link ResourceKind}'s, so a
     *       405's {@code Allow} and the {@code Allow} on a successful read of the same resource
     *       are one table entry rather than two literals that can drift — which Solid Protocol
     *       §5.4 requires for the root ("Server MUST exclude the {@code DELETE} method in the
     *       field value of the {@code Allow} header field, in response to requests to these
     *       resources").</li>
     *   <li><b>415 on a {@code PATCH} → {@code Accept-Patch}.</b> RFC 5789 §2.2: a 415 for an
     *       unsupported patch document "SHOULD include an {@code Accept-Patch} response header
     *       ... to notify the client what patch document media types are supported". Scoped to
     *       {@code PATCH} because RFC 5789 §3.1 makes the field's presence "an implicit
     *       indication that PATCH is allowed on the resource", so emitting it on some future
     *       415 from another method would assert something this response does not know.</li>
     * </ul>
     *
     * <h2>The {@code Allow} is core's answer now, not a guess from the path (T2.7)</h2>
     * This method used to derive the kind from the request path, which was exact only while
     * {@code PATCH} was unimplemented: a document's {@code Allow} was the same whether it held a
     * graph or a JPEG. It no longer is, and no amount of looking at the URI can say which a path
     * names — Solid Protocol §3.1 puts only the container/document split in the trailing slash.
     * So {@link CisternException.MethodNotAllowed} carries the {@link LdpKind} of the resource
     * that refused, and this maps it through the one table. A refusal now advertises exactly
     * what a successful read of the same resource advertises, for every kind, which is what
     * Solid Protocol §5.2 asks of {@code Allow} and its {@code Accept-*} companions.
     *
     * <p><b>One source of truth with T2.5's precondition gate.</b> The value read here is
     * {@link ResourceKind#allow()}, <em>derived</em> from the same {@code List<HttpMethod>} that
     * {@code ResourceKind.permits} answers from — and that is what {@code ConditionalRequests}
     * consults to decide whether RFC 9110 §13.2.1 requires a request's preconditions to be
     * ignored because the answer would be a 405 regardless. So the {@code Allow} on a refusal and
     * the applicability of a precondition cannot disagree; they are one list.
     * {@code ResourceKindTest} pins that invariant rather than leaving it to inspection.
     */
    private static HttpHeaders domainHeaders(CisternException error, ServerWebExchange exchange) {
        if (error instanceof CisternException.MethodNotAllowed methodNotAllowed) {
            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.ALLOW, ResourceKind.forKind(methodNotAllowed.kind()).allow());
            return headers;
        }
        if (error instanceof CisternException.UnsupportedMediaType
                && HttpMethod.PATCH.equals(exchange.getRequest().getMethod())) {
            HttpHeaders headers = new HttpHeaders();
            // N3Patch.MEDIA_TYPE rather than a literal or the webflux constant: core's patch
            // engine defines the media type it accepts, so what the 415 tells the client to
            // retry with is the very string the parser matches against.
            headers.set(HttpHeaders.ACCEPT_PATCH, N3Patch.MEDIA_TYPE);
            return headers;
        }
        return HttpHeaders.EMPTY;
    }

    /**
     * The status a domain error carries, as an exhaustive switch over {@link CisternException}'s
     * {@code permits} list — the completeness guarantee itself (#60).
     *
     * <p>No {@code default} branch, deliberately: a {@code default} would restore exactly the
     * hole sealing closed, by giving a subtype nobody has thought about somewhere to land. With
     * the switch exhaustive, adding a subtype in cistern-core without deciding its status is a
     * compile error here, which is the moment the decision is cheapest to make. This replaces
     * both the class-keyed registry and the reflection test that used to police it.
     *
     * <p>{@code AccessDenied} is the one row whose answer is not a function of the class alone;
     * see {@link #accessDenied}.
     */
    private ProblemDocument domain(CisternException error, URI instance, ServerWebExchange exchange) {
        ProblemType type = switch (error) {
            case CisternException.AccessDenied ignored -> accessDenied(exchange);
            case CisternException.BadInput ignored -> ProblemType.BAD_INPUT;
            case CisternException.Conflict ignored -> ProblemType.CONFLICT;
            case CisternException.MethodNotAllowed ignored -> ProblemType.METHOD_NOT_ALLOWED;
            case CisternException.NotAcceptable ignored -> ProblemType.NOT_ACCEPTABLE;
            case CisternException.NotFound ignored -> ProblemType.NOT_FOUND;
            case CisternException.PreconditionFailed ignored -> ProblemType.PRECONDITION_FAILED;
            case CisternException.UnprocessableEntity ignored -> ProblemType.UNPROCESSABLE_ENTITY;
            case CisternException.UnsupportedMediaType ignored -> ProblemType.UNSUPPORTED_MEDIA_TYPE;
        };
        return problem(type, error, instance);
    }

    /**
     * Solid Protocol §2.1: no valid credentials → 401. An agent that authenticated and still
     * lacks the mode gets 403, because retrying with the same credentials cannot help.
     */
    private ProblemType accessDenied(ServerWebExchange exchange) {
        return requestAuthentication.isAuthenticated(exchange)
                ? ProblemType.ACCESS_DENIED
                : ProblemType.AUTHENTICATION_REQUIRED;
    }

    /** Falls back to the type's title when a domain exception carries no message. */
    private ProblemDocument problem(ProblemType type, CisternException error, URI instance) {
        String detail = error.getMessage() != null ? error.getMessage() : type.title();
        return ProblemDocument.of(type, detail, instance);
    }

    private Problem blank(HttpStatus status, String detail, URI instance) {
        return new Problem(ProblemDocument.blank(status, detail, instance), HttpHeaders.EMPTY);
    }
}
