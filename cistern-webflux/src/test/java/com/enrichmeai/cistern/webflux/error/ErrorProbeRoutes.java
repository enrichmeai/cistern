package com.enrichmeai.cistern.webflux.error;

import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RequestPredicates.POST;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

import java.util.EnumSet;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Turns {@link Probe} into routes that raise one error apiece, so each row of
 * {@link ProblemMapper}'s table can be exercised over real HTTP. Deliberately independent of
 * the resource routes (T2.1) — this ticket's tests must not wait on, or break with, another
 * agent's routing work.
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

    /** Probes that are not plain "GET raises this error" routes. */
    private static final EnumSet<Probe> SPECIAL = EnumSet.of(Probe.UNROUTED, Probe.ECHO);

    @Bean
    RouterFunction<ServerResponse> probeRoutes() {
        RouterFunction<ServerResponse> routes = route(
                // Decoding the body produces the genuine article: text/plain in →
                // UnsupportedMediaTypeStatusException (415); malformed JSON in →
                // ServerWebInputException (400). Neither exception is hand-constructed.
                POST(Probe.ECHO.path()),
                request -> request.bodyToMono(Echo.class).flatMap(ServerResponse.ok()::bodyValue));

        for (Probe probe : EnumSet.complementOf(SPECIAL)) {
            routes = routes.andRoute(GET(probe.path()), request -> Mono.error(probe.error()));
        }
        return routes;
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
}
