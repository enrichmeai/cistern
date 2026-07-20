package com.enrichmeai.cistern.webflux.error;

import com.enrichmeai.cistern.core.CisternException;
import java.net.URI;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponse;
import org.springframework.web.server.ServerWebExchange;

/**
 * Turns a {@link Throwable} into an RFC 9457 problem detail. Pure: no I/O, no HTTP writing,
 * no logging — {@link CisternErrorWebExceptionHandler} does all three. Splitting it out keeps
 * the mapping table readable and directly unit-testable.
 *
 * <p><strong>This table is the only place in Cistern where a domain error becomes a status
 * code</strong> (CLAUDE.md ground rule 4). Handlers signal {@link CisternException} subtypes
 * through the reactive chain and never speak HTTP.
 *
 * <table>
 *   <caption>Mapping</caption>
 *   <tr><th>Throwable</th><th>Status</th></tr>
 *   <tr><td>{@code CisternException.BadInput}</td><td>400</td></tr>
 *   <tr><td>{@code CisternException.AccessDenied}</td><td>401 unauthenticated / 403 authenticated</td></tr>
 *   <tr><td>{@code CisternException.NotFound}</td><td>404</td></tr>
 *   <tr><td>{@code CisternException.Conflict}</td><td>409 (Solid Protocol §5.3, §5.4)</td></tr>
 *   <tr><td>{@code CisternException.PreconditionFailed}</td><td>412</td></tr>
 *   <tr><td>{@code CisternException.UnprocessableEntity}</td><td>422 (RFC 4918 §11.2)</td></tr>
 *   <tr><td>{@code ErrorResponse} (Spring: 415, 405, 400, 406, 404 …)</td><td>its own status</td></tr>
 *   <tr><td>{@code IllegalArgumentException}</td><td>400</td></tr>
 *   <tr><td>{@code IllegalStateException}</td><td>500</td></tr>
 *   <tr><td>anything else</td><td>500</td></tr>
 * </table>
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9457">RFC 9457 — Problem Details for HTTP APIs</a>
 */
final class ProblemMapper {

    /**
     * Base for Cistern's own problem type URIs. RFC 9457 §3.1.1 encourages resolvable type
     * URIs; these are expected to resolve to human-readable documentation of each problem
     * type. {@code about:blank} (§4.2.1) is used wherever the HTTP status code alone carries
     * the whole meaning — i.e. for Spring's own errors and for 500s.
     */
    static final String TYPE_BASE = "https://enrichmeai.com/cistern/problems/";

    /**
     * Body text for every 500. RFC 9457 §5 warns that problem details must not leak
     * information about the system; the real cause is logged server-side and never sent.
     */
    static final String INTERNAL_ERROR_DETAIL =
            "The server encountered an unexpected condition that prevented it from fulfilling the request.";

    private final RequestAuthentication requestAuthentication;

    ProblemMapper(RequestAuthentication requestAuthentication) {
        this.requestAuthentication = requestAuthentication;
    }

    /** A rendered problem: the RFC 9457 body plus any headers the status code requires. */
    record Problem(ProblemDetail body, HttpHeaders headers) {

        HttpStatusCode status() {
            return HttpStatusCode.valueOf(body.getStatus());
        }

        boolean isServerError() {
            return status().is5xxServerError();
        }
    }

    Problem map(Throwable error, ServerWebExchange exchange) {
        URI instance = URI.create(exchange.getRequest().getPath().value());

        if (error instanceof CisternException cisternError) {
            return cistern(cisternError, instance, exchange);
        }
        // Every Spring web exception worth mapping (UnsupportedMediaTypeStatusException →
        // 415, MethodNotAllowedException → 405 + Allow, ServerWebInputException → 400,
        // NotAcceptableStatusException → 406, the ResponseStatusException(404) raised for an
        // unrouted path) implements ErrorResponse and already carries both the right status
        // and the headers that status mandates. One branch covers them all; enumerating
        // subclasses would only be a chance to get one wrong.
        if (error instanceof ErrorResponse springError) {
            return spring(springError, instance);
        }
        // A caller programming error surfacing from core (e.g. children() on a document) is
        // the client's fault only in the sense that it reached us as a bad request shape.
        if (error instanceof IllegalArgumentException) {
            return blank(HttpStatus.BAD_REQUEST, error.getMessage(), instance);
        }
        // IllegalStateException (server-side corruption — an unparseable stored
        // representation, say) and everything unforeseen land here. Never blame the client,
        // never describe the damage: the detail is a constant and the cause goes to the log.
        return blank(HttpStatus.INTERNAL_SERVER_ERROR, INTERNAL_ERROR_DETAIL, instance);
    }

    private Problem cistern(CisternException error, URI instance, ServerWebExchange exchange) {
        return switch (error) {
            case CisternException.BadInput e ->
                    typed(HttpStatus.BAD_REQUEST, "bad-input", "Malformed request entity", e, instance);
            case CisternException.UnprocessableEntity e ->
                    typed(HttpStatus.UNPROCESSABLE_CONTENT, "unprocessable-entity",
                            "Request entity violates a protocol constraint", e, instance);
            case CisternException.NotFound e ->
                    typed(HttpStatus.NOT_FOUND, "not-found", "Resource not found", e, instance);
            case CisternException.Conflict e ->
                    typed(HttpStatus.CONFLICT, "conflict",
                            "Request conflicts with the state of the resource", e, instance);
            case CisternException.PreconditionFailed e ->
                    typed(HttpStatus.PRECONDITION_FAILED, "precondition-failed",
                            "Precondition failed", e, instance);
            // Solid Protocol §2.1: no valid credentials → 401. An agent that authenticated
            // and still lacks the mode gets 403 — retrying with the same credentials cannot
            // help. See RequestAuthentication for how this is wired in Phase 4.
            case CisternException.AccessDenied e -> requestAuthentication.isAuthenticated(exchange)
                    ? typed(HttpStatus.FORBIDDEN, "access-denied", "Access denied", e, instance)
                    : typed(HttpStatus.UNAUTHORIZED, "authentication-required",
                            "Authentication required", e, instance);
            // CisternException is abstract but not sealed, so the compiler cannot prove the
            // arms above are exhaustive. A subtype nobody taught this table about is a bug in
            // Cistern, not in the request: 500, and the real cause is logged. Sealing
            // CisternException would turn this into a compile error instead — worth doing,
            // but it is a cistern-core change and T2.6 does not own that module.
            default -> blank(HttpStatus.INTERNAL_SERVER_ERROR, INTERNAL_ERROR_DETAIL, instance);
        };
    }

    private Problem spring(ErrorResponse error, URI instance) {
        HttpStatusCode status = error.getStatusCode();
        String detail = status.is5xxServerError() ? INTERNAL_ERROR_DETAIL : error.getBody().getDetail();
        ProblemDetail body = body(status, URI.create("about:blank"), reasonPhrase(status), detail, instance);
        HttpHeaders headers = new HttpHeaders();
        headers.putAll(error.getHeaders());
        return new Problem(body, headers);
    }

    private Problem typed(HttpStatus status, String slug, String title, CisternException error, URI instance) {
        return new Problem(
                body(status, URI.create(TYPE_BASE + slug), title, error.getMessage(), instance),
                new HttpHeaders());
    }

    private Problem blank(HttpStatus status, String detail, URI instance) {
        return new Problem(
                body(status, URI.create("about:blank"), status.getReasonPhrase(), detail, instance),
                new HttpHeaders());
    }

    private ProblemDetail body(HttpStatusCode status, URI type, String title, String detail, URI instance) {
        ProblemDetail body = ProblemDetail.forStatus(status);
        body.setType(type);
        body.setTitle(title);
        body.setDetail(detail);
        body.setInstance(instance);
        return body;
    }

    /**
     * RFC 9457 §4.2.1: with {@code about:blank}, the title SHOULD be the status code's
     * recommended reason phrase.
     */
    private static String reasonPhrase(HttpStatusCode status) {
        HttpStatus resolved = HttpStatus.resolve(status.value());
        return resolved != null ? resolved.getReasonPhrase() : "Error";
    }
}
