package com.enrichmeai.cistern.webflux;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.web.reactive.function.server.RequestPredicates;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.MethodNotAllowedException;
import reactor.core.publisher.Mono;

/**
 * Every {@link ServerEndpoint} the context declared, indexed by path — the one place the
 * filter and the routes consult, so the two cannot disagree about which paths are reserved.
 */
final class ServerEndpoints {

    private final Map<String, ServerEndpoint> byPath;

    ServerEndpoints(List<ServerEndpoint> declared) {
        Map<String, ServerEndpoint> indexed = new LinkedHashMap<>();
        for (ServerEndpoint endpoint : declared) {
            if (indexed.putIfAbsent(endpoint.path(), endpoint) != null) {
                throw new IllegalArgumentException(WebfluxMessage.ENDPOINT_DUPLICATED.format(endpoint.path()));
            }
        }
        this.byPath = Map.copyOf(indexed);
    }

    /** The endpoint reserved at {@code rawPath}, if any. Exact match: endpoints are not patterns. */
    Optional<ServerEndpoint> at(String rawPath) {
        return Optional.ofNullable(byPath.get(rawPath));
    }

    /** All declared endpoints, for the boot log. */
    List<ServerEndpoint> all() {
        return List.copyOf(byPath.values());
    }

    /**
     * One route per endpoint that refuses every method it does not serve with a 405 carrying
     * {@code Allow} — Spring's {@link MethodNotAllowedException} carries exactly that field, and
     * the single error mapper renders it (RFC 9110 §15.5.6: "The origin server MUST generate an
     * {@code Allow} header field in a 405 response"). Registered ahead of the catch-all routes,
     * so a {@code PUT} to a reserved path is refused rather than stored.
     *
     * <p>Only the methods the endpoint does <em>not</em> serve are matched here; the served
     * ones fall through to the endpoint's own routes, wherever its module registered them.
     */
    RouterFunction<ServerResponse> methodNotAllowedRoutes() {
        RouterFunction<ServerResponse> routes = request -> Mono.empty();
        for (ServerEndpoint endpoint : byPath.values()) {
            routes = routes.andRoute(
                    RequestPredicates.path(endpoint.path()).and(request -> !endpoint.serves(request.method())),
                    request -> Mono.error(new MethodNotAllowedException(request.method(), endpoint.methods())));
        }
        return routes;
    }
}
