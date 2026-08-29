package com.enrichmeai.cistern.webflux;

import org.springframework.core.Ordered;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.CorsWebFilter;

/**
 * {@link CorsWebFilter}, ordered ahead of {@link AuthorizationFilter}.
 *
 * <p>An unordered {@code CorsWebFilter} bean sorts to lowest precedence, which puts it
 * <em>behind</em> authorization — and {@code AuthorizationFilter.refuse()} completes the
 * response without continuing the chain, so every 401/403 left the server with no CORS
 * fields at all. Verified against a live run: a refused cross-origin {@code GET} carried
 * neither {@code Access-Control-Allow-Origin} nor {@code Access-Control-Expose-Headers}.
 *
 * <p>That is precisely the case Solid Protocol §8.1 cares about: a browser app must be able
 * to read the CORS fields on a refusal — the conformance suite's simple-request scenarios
 * assert {@code Access-Control-Allow-Origin} on anonymous requests whose expected status is
 * 401. Running first also means preflights are answered from configuration before
 * authorization sees them, which is the behaviour {@code AuthorizationFilter}'s own
 * preflight exemption already assumes.
 */
final class CorsFirstWebFilter extends CorsWebFilter implements Ordered {

    /**
     * Ahead of {@link AuthorizationFilter#ORDER} by a deliberate gap: CORS is protocol
     * plumbing for every response, refusals included, so nothing that can short-circuit the
     * chain may run before it.
     */
    public static final int ORDER = AuthorizationFilter.ORDER - 10;

    CorsFirstWebFilter(CorsConfigurationSource source) {
        super(source);
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
