package com.enrichmeai.cistern.webflux.error;

import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RequestPredicates.POST;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

import com.enrichmeai.cistern.core.CisternException;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.MethodNotAllowedException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Probe routes that raise one error apiece, so each row of {@link ProblemMapper}'s table can
 * be exercised over real HTTP. Deliberately independent of the resource routes (T2.1) — this
 * ticket's tests must not wait on, or break with, another agent's routing work.
 *
 * <p>Errors are signalled with {@code Mono.error}, the way real domain code reaches the HTTP
 * layer, rather than thrown synchronously from the handler.
 */
@Configuration(proxyBeanMethods = false)
class ErrorProbeRoutes {

    /** Payload type that exists only to give the codecs something to fail at decoding. */
    record Echo(String value) {
    }

    /** Marker header standing in for the Phase 4 auth filter; see {@link #probeAuthFilter()}. */
    static final String AUTHENTICATED_HEADER = "X-Probe-Authenticated";

    @Bean
    RouterFunction<ServerResponse> probeRoutes() {
        return route(GET("/probe/bad-input"),
                        request -> Mono.error(new CisternException.BadInput("not valid Turtle")))
                .andRoute(GET("/probe/unprocessable-entity"),
                        request -> Mono.error(new CisternException.UnprocessableEntity(
                                "patch deletes a triple it does not bind")))
                .andRoute(GET("/probe/not-found"),
                        request -> Mono.error(new CisternException.NotFound("no such resource")))
                .andRoute(GET("/probe/conflict"),
                        request -> Mono.error(new CisternException.Conflict(
                                "cannot write containment triples")))
                .andRoute(GET("/probe/precondition-failed"),
                        request -> Mono.error(new CisternException.PreconditionFailed(
                                "If-Match did not match the current ETag")))
                .andRoute(GET("/probe/access-denied"),
                        request -> Mono.error(new CisternException.AccessDenied("Write mode required")))
                .andRoute(GET("/probe/illegal-argument"),
                        request -> Mono.error(new IllegalArgumentException("/doc is not a container")))
                .andRoute(GET("/probe/illegal-state"),
                        request -> Mono.error(new IllegalStateException(SECRET)))
                .andRoute(GET("/probe/boom"), request -> Mono.error(new RuntimeException(SECRET)))
                .andRoute(GET("/probe/method-not-allowed"),
                        request -> Mono.error(new MethodNotAllowedException(
                                HttpMethod.DELETE, Set.of(HttpMethod.GET, HttpMethod.HEAD))))
                // Decoding the body is what produces the genuine article: text/plain in →
                // UnsupportedMediaTypeStatusException (415); malformed JSON in →
                // ServerWebInputException (400). Neither exception is hand-constructed.
                .andRoute(POST("/probe/echo"),
                        request -> request.bodyToMono(Echo.class).flatMap(ServerResponse.ok()::bodyValue));
    }

    /**
     * Stands in for the Phase 4 authentication {@code WebFilter}: on a marker header it
     * publishes an agent under {@link RequestAuthentication#AUTHENTICATED_AGENT_ATTRIBUTE},
     * which is the contract the real filter will honour. This fakes no credential checking —
     * it only proves that once <em>something</em> marks the request as authenticated, the
     * mapper takes the 403 branch instead of 401.
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    WebFilter probeAuthFilter() {
        return (ServerWebExchange exchange, WebFilterChain chain) -> {
            if (exchange.getRequest().getHeaders().containsHeader(AUTHENTICATED_HEADER)) {
                exchange.getAttributes().put(RequestAuthentication.AUTHENTICATED_AGENT_ATTRIBUTE,
                        "https://alice.example/profile/card#me");
            }
            return chain.filter(exchange);
        };
    }

    /**
     * Text that must never reach a client: it stands for the kind of internal detail a real
     * {@code IllegalStateException} would carry (paths, corrupt sidecar contents).
     */
    static final String SECRET = "corrupt sidecar at /var/data/pod/secret-file.meta";
}
