package com.enrichmeai.cistern.webflux;

import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Guarantees {@code Vary: Origin} on every response (T2.8).
 *
 * <h2>The requirement</h2>
 * Solid Protocol §8.1: "the server MUST set the {@code Access-Control-Allow-Origin} header field
 * value to the valid {@code Origin} header field value from the request and list {@code Origin}
 * in the {@code Vary} header field value." The two halves are one rule — because Cistern echoes
 * the request's origin, the response genuinely differs per origin, and a shared cache told
 * otherwise may hand a response bearing
 * {@code Access-Control-Allow-Origin: https://a.example} to a request from
 * {@code https://b.example}. The browser would then either wrongly refuse a legitimate read or
 * wrongly permit one; {@code Vary} is what stops the cache conflating them.
 *
 * <h2>Why a filter rather than a line in each handler</h2>
 * Spring's CORS processor already writes {@code Vary: Origin} before the handler runs, but the
 * functional {@code ServerResponse} writes its own header map over the response's, so a handler
 * that sets {@code Vary} at all replaces it. That is not hypothetical: a cross-origin
 * {@code GET} of an RDF source arrived carrying {@code Vary: Accept} alone, the {@code Origin}
 * entry gone — observed by curl against the running server before this class existed, and
 * pinned since by {@code CorsHttpTest}.
 *
 * <p>Two different concerns legitimately contribute to one field: content negotiation owns
 * {@code Vary: Accept} (RFC 9110 §12.5.5) and CORS owns {@code Vary: Origin} (§8.1). Making
 * each handler remember the other's entry would be the drift this codebase avoids everywhere
 * else, and would leave the rule to be re-remembered by every handler a later ticket adds. So
 * the CORS layer asserts its own entry, once, at the last moment a response can still be
 * changed — {@code beforeCommit} — where it sees whatever the handler finally wrote and unions
 * rather than overwrites.
 *
 * <p>Unconditional because it is unconditionally true: every response can carry an echoed
 * {@code Access-Control-Allow-Origin}, so every response varies by {@code Origin}. Responses
 * that already list it — anything that never reached a handler, such as a preflight — are left
 * alone rather than given a duplicate entry.
 */
public class OriginVaryFilter implements WebFilter {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpResponse response = exchange.getResponse();
        response.beforeCommit(() -> {
            HttpHeaders headers = response.getHeaders();
            if (!variesByOrigin(headers)) {
                headers.add(HttpHeaders.VARY, HttpHeaders.ORIGIN);
            }
            return Mono.empty();
        });
        return chain.filter(exchange);
    }

    /**
     * Whether {@code Origin} is already listed. {@link HttpHeaders#getVary()} splits the
     * comma-delimited list of RFC 9110 §5.6.1 across however many field lines it arrived on, so
     * this sees the entries themselves rather than the punctuation. Field names are
     * case-insensitive (RFC 9110 §5.1), hence the case-insensitive comparison.
     */
    private static boolean variesByOrigin(HttpHeaders headers) {
        return headers.getVary().stream().anyMatch(HttpHeaders.ORIGIN::equalsIgnoreCase);
    }
}
