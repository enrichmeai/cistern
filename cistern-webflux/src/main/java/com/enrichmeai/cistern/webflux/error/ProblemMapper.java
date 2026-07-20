package com.enrichmeai.cistern.webflux.error;

import com.enrichmeai.cistern.core.CisternException;
import com.enrichmeai.cistern.webflux.ResourceKind;
import com.enrichmeai.cistern.webflux.WebfluxMessage;
import java.net.URI;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
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
 * <p>The table itself is {@link #DOMAIN_PROBLEMS} plus {@link ProblemType} — a registry keyed
 * by exception class, not a chain of {@code instanceof} tests, so
 * {@code ProblemMapperCoverageTest} can assert that every {@code CisternException} subtype has
 * a row. {@code AccessDenied} is the one domain error whose status is not a function of its
 * class alone; see {@link RequestAuthentication}.
 */
final class ProblemMapper {

    private static final Logger log = LoggerFactory.getLogger(ProblemMapper.class);

    /** Solid Protocol §3.1: a URI path ending with this separator denotes a container. */
    private static final String CONTAINER_SUFFIX = "/";

    /**
     * Exception class → problem type, for every {@link CisternException} subtype whose status
     * depends on nothing but its class. {@code AccessDenied} is deliberately absent:
     * {@link #accessDenied} resolves it through the 401/403 seam.
     */
    static final Map<Class<? extends CisternException>, ProblemType> DOMAIN_PROBLEMS = Map.of(
            CisternException.BadInput.class, ProblemType.BAD_INPUT,
            CisternException.UnprocessableEntity.class, ProblemType.UNPROCESSABLE_ENTITY,
            CisternException.NotFound.class, ProblemType.NOT_FOUND,
            CisternException.NotAcceptable.class, ProblemType.NOT_ACCEPTABLE,
            CisternException.MethodNotAllowed.class, ProblemType.METHOD_NOT_ALLOWED,
            CisternException.Conflict.class, ProblemType.CONFLICT,
            CisternException.PreconditionFailed.class, ProblemType.PRECONDITION_FAILED);

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
     * <p>Only 405 has one: RFC 9110 §15.5.6 — "The origin server MUST generate an
     * {@code Allow} header field in a 405 response containing a list of the target resource's
     * currently supported methods." The value is {@link ResourceKind}'s, so a 405's
     * {@code Allow} and the {@code Allow} on a successful read of the same resource are one
     * table entry rather than two literals that can drift — which Solid Protocol §5.4 requires
     * for the root ("Server MUST exclude the {@code DELETE} method in the field value of the
     * {@code Allow} header field, in response to requests to these resources").
     *
     * <p>T2.3 brought the second such resource this method's earlier note predicted: a
     * {@code POST} to a document is a 405 (Solid Protocol §5.3 confines creation by {@code POST}
     * to paths ending in {@code /}), and answering it with the storage root's {@code Allow} would
     * have listed {@code POST} in the refusal of a {@code POST} — a self-contradicting response.
     * See {@link #allowedMethods} for how the kind is chosen without the exception having to
     * carry it.
     */
    private static HttpHeaders domainHeaders(CisternException error, ServerWebExchange exchange) {
        if (!(error instanceof CisternException.MethodNotAllowed)) {
            return HttpHeaders.EMPTY;
        }
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.ALLOW, allowedMethods(exchange));
        return headers;
    }

    /**
     * The {@code Allow} field value for a resource known only by its request path.
     *
     * <p>Solid Protocol §3.1 makes the trailing slash decide the container/document split, so the
     * path alone separates the two resources that can raise a {@link
     * CisternException.MethodNotAllowed} today: the storage root refusing {@code DELETE} (§5.4)
     * and a document refusing {@code POST} (§5.3). Reading it from the exchange keeps a
     * {@code CisternException} free of HTTP, which is the whole point of the type.
     *
     * <p>{@link ResourceKind#NON_RDF_DOCUMENT} is the document row rather than
     * {@link ResourceKind#RDF_DOCUMENT} because the two differ only in {@code PATCH}, the path
     * cannot say which applies, and RFC 9110 §10.2.1 defines {@code Allow} as the methods the
     * resource supports — so the row that claims only what every document supports is the one
     * that cannot over-advertise. {@code PATCH} is not served until T2.7, which is when this
     * needs to become exact; at that point the distinction is a fact only core holds, and the
     * exception will have to carry it.
     *
     * <p><b>One source of truth with T2.5's precondition gate.</b> This reads
     * {@link ResourceKind#allow()}, which since T2.5 is <em>derived</em> from the same
     * {@code List<HttpMethod>} that {@code ResourceKind.permits} answers from — and that is what
     * {@code ConditionalRequests} consults to decide whether RFC 9110 §13.2.1 requires a
     * request's preconditions to be ignored because the answer would be a 405 regardless. So the
     * {@code Allow} on a refusal and the applicability of a precondition cannot disagree; they
     * are one list. {@code ResourceKindTest} pins that invariant rather than leaving it to
     * inspection.
     *
     * <p>What is still duplicated is narrower and deliberate: the container/document split is
     * decided here from the raw path, while {@link ResourceKind#ofContainer} decides it from a
     * {@link com.enrichmeai.cistern.core.ResourceIdentifier}. The mapper has no identifier —
     * only an exchange — so unifying the two belongs with the interface-metadata consolidation
     * tracked in #60, not with a ticket that would have to invent a base URL here to do it.
     */
    private static String allowedMethods(ServerWebExchange exchange) {
        boolean container = exchange.getRequest().getPath().value().endsWith(CONTAINER_SUFFIX);
        return container
                ? ResourceKind.STORAGE_ROOT.allow()
                : ResourceKind.NON_RDF_DOCUMENT.allow();
    }

    private ProblemDocument domain(CisternException error, URI instance, ServerWebExchange exchange) {
        if (error instanceof CisternException.AccessDenied) {
            return problem(accessDenied(exchange), error, instance);
        }
        ProblemType type = DOMAIN_PROBLEMS.get(error.getClass());
        if (type == null) {
            // A subtype nobody taught the registry about is a bug in Cistern, not in the
            // request. ProblemMapperCoverageTest exists to make this unreachable.
            log.error(WebfluxMessage.LOG_UNMAPPED_DOMAIN_ERROR.format(error.getClass().getName()), error);
            return ProblemDocument.blank(HttpStatus.INTERNAL_SERVER_ERROR, null, instance);
        }
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
