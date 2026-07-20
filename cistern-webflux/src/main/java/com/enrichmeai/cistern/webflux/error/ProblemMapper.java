package com.enrichmeai.cistern.webflux.error;

import com.enrichmeai.cistern.core.CisternException;
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

    /**
     * Exception class → problem type, for every {@link CisternException} subtype whose status
     * depends on nothing but its class. {@code AccessDenied} is deliberately absent:
     * {@link #accessDenied} resolves it through the 401/403 seam.
     */
    static final Map<Class<? extends CisternException>, ProblemType> DOMAIN_PROBLEMS = Map.of(
            CisternException.BadInput.class, ProblemType.BAD_INPUT,
            CisternException.UnprocessableEntity.class, ProblemType.UNPROCESSABLE_ENTITY,
            CisternException.NotFound.class, ProblemType.NOT_FOUND,
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
            return new Problem(domain(domainError, instance, exchange), HttpHeaders.EMPTY);
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
