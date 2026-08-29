package com.enrichmeai.cistern.webflux;

import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.CorsProcessor;
import org.springframework.web.cors.reactive.CorsUtils;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

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
 *
 * <p>Public, deliberately: the CORS bean is {@code @ConditionalOnMissingBean}, and an
 * embedder replacing it with a plain {@code CorsWebFilter} would silently land at lowest
 * precedence and reinstate the opaque-refusal bug. Extending (or instantiating) this class
 * keeps the ordering; replacing it with anything unordered is the trap.
 *
 * <p>Running first must not mean deciding first: with restricted
 * {@code cistern.cors.allowed-origins}, Spring's default answers a disallowed-origin
 * <em>actual</em> request with a bare 403 — flipping an anonymous caller's 401, skipping the
 * receipt the decision log promises for every deny, and dropping the X-Request-Id echo. So
 * this filter pairs with {@link BrowserEnforcedCorsProcessor} and continues the chain for
 * every actual request: a disallowed origin simply gets no CORS headers, which is the Fetch
 * model's own enforcement, and the pod still authorizes, records and correlates. Only
 * preflights are answered (or refused) here — they exist for this layer alone, and the
 * receipts suite pins that they are not recorded.
 */
public class CorsFirstWebFilter extends CorsWebFilter implements Ordered {

    /**
     * Ahead of {@link AuthorizationFilter#ORDER} by a deliberate gap: CORS is protocol
     * plumbing for every response, refusals included, so nothing that can short-circuit the
     * chain may run before it.
     */
    public static final int ORDER = AuthorizationFilter.ORDER - 10;

    private final CorsConfigurationSource source;
    private final CorsProcessor processor;

    public CorsFirstWebFilter(CorsConfigurationSource source) {
        this(source, new BrowserEnforcedCorsProcessor());
    }

    /** For embedders that need their own {@code CorsProcessor} (e.g. problem-body 403s). */
    public CorsFirstWebFilter(CorsConfigurationSource source, CorsProcessor processor) {
        super(source, processor);
        this.source = source;
        this.processor = processor;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        CorsConfiguration configuration = this.source.getCorsConfiguration(exchange);
        boolean valid = this.processor.process(configuration, exchange);
        if (CorsUtils.isPreFlightRequest(request)) {
            // Answered from configuration either way: a grant, or a bare 403 — status only,
            // written here rather than by the processor (see BrowserEnforcedCorsProcessor).
            if (!valid) {
                exchange.getResponse().setStatusCode(org.springframework.http.HttpStatus.FORBIDDEN);
            }
            return exchange.getResponse().setComplete();
        }
        // Actual requests ALWAYS continue — see the class comment: absence of CORS headers
        // is the Fetch-layer refusal, and the pod's own decision still gets made and kept.
        return chain.filter(exchange);
    }
}
