package com.enrichmeai.cistern.webflux;

import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

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
            addOriginToVary(response.getHeaders());
            return Mono.empty();
        });
        return chain.filter(exchange);
    }

    /**
     * Rebuilds {@code Vary} as ONE comma-joined field line, with {@code Origin} added unless
     * already listed.
     *
     * <p>One line rather than one-per-entry, because the two RFC 9110 §5.6.1-equivalent forms
     * are not equivalent to consumers: a reader that takes the first {@code Vary} line — the
     * conformance harness's header matcher does, and it is not alone — sees only the first
     * entry of a multi-line answer. Verified against a live run: {@code Vary: Accept} +
     * {@code Vary: Origin} on two lines read back as just {@code Accept}. Folding is
     * idempotent, so a response that already lists {@code Origin} is normalised, not doubled.
     *
     * <p>Written as "replace the field with a new list" rather than the obvious
     * {@code headers.add(VARY, ORIGIN)} because {@code add} mutates the existing value list in
     * place, and that list is not always mutable: when a functional {@code ServerResponse}
     * supplies the header, the list it copies in is immutable, so {@code add} raises
     * {@code UnsupportedOperationException} — a 500 on every response a handler set {@code Vary}
     * on. It is also environment-dependent, which is the dangerous part: Reactor Netty's header
     * adapter hands back a mutable list, so the server ran fine under curl while the test suite
     * failed. Copying first is correct in both.
     */
    private static void addOriginToVary(HttpHeaders headers) {
        // getVary() splits the entries across however many field lines and commas they
        // arrived on, so this sees the list itself rather than the punctuation.
        List<String> entries = new ArrayList<>(headers.getVary());
        if (entries.stream().noneMatch(HttpHeaders.ORIGIN::equalsIgnoreCase)) {
            entries.add(HttpHeaders.ORIGIN);
        }
        headers.put(HttpHeaders.VARY, List.of(String.join(HttpConstants.LIST_SEPARATOR, entries)));
    }
}
