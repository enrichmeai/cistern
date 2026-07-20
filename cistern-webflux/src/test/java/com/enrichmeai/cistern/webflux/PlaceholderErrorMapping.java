package com.enrichmeai.cistern.webflux;

import com.enrichmeai.cistern.core.CisternException;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;

/**
 * <b>TEMPORARY SCAFFOLDING — DELETE THIS FILE WHEN T2.6 MERGES.</b> It exists only because
 * T2.6's global error mapper is not on this branch. Once it is, this class must go: leaving
 * it would mean these tests keep asserting against a stand-in instead of against the real
 * mapper, which is the one way this file could quietly outlive its purpose. The architect is
 * tracking the deletion; this comment is the second guard.
 *
 * <p>T2.1's handler deliberately maps no status codes: it signals {@link CisternException}
 * subtypes and the single global error mapper (T2.6, {@code webflux/error/}) renders them.
 * That mapper does not exist on this branch yet, so without this stub every domain signal
 * would surface as Boot's default 500 and the status assertions in
 * {@link ResourceReadHttpTest} could not be written at all.
 *
 * <p>It maps status codes <em>only</em> — no body, no headers — so the assertions it enables
 * are exactly the ones T2.6 must keep satisfying. It is a {@code @TestConfiguration}, so it
 * is never picked up by component scanning and cannot leak into production or into another
 * test that does not import it explicitly.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PlaceholderErrorMapping {

    /** Ahead of Boot's {@code DefaultErrorWebExceptionHandler} (order -1). */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public WebExceptionHandler placeholderCisternExceptionMapper() {
        return (ServerWebExchange exchange, Throwable error) -> {
            HttpStatus status = statusFor(error);
            if (status == null) {
                return Mono.error(error);
            }
            exchange.getResponse().setStatusCode(status);
            return exchange.getResponse().setComplete();
        };
    }

    private static HttpStatus statusFor(Throwable error) {
        return switch (error) {
            case CisternException.BadInput ignored -> HttpStatus.BAD_REQUEST;
            case CisternException.NotFound ignored -> HttpStatus.NOT_FOUND;
            case CisternException.NotAcceptable ignored -> HttpStatus.NOT_ACCEPTABLE;
            case CisternException.Conflict ignored -> HttpStatus.CONFLICT;
            case CisternException.PreconditionFailed ignored -> HttpStatus.PRECONDITION_FAILED;
            case CisternException.UnprocessableEntity ignored -> HttpStatus.UNPROCESSABLE_ENTITY;
            case CisternException.AccessDenied ignored -> HttpStatus.FORBIDDEN;
            default -> null;
        };
    }
}
