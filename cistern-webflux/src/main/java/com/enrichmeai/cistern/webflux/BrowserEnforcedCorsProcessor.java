package com.enrichmeai.cistern.webflux;

import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.cors.reactive.DefaultCorsProcessor;

/**
 * {@link DefaultCorsProcessor} that never writes a rejection itself.
 *
 * <p>Spring's default answers any failed CORS check with a 403 and a {@code text/plain}
 * body, from inside the processor — which in an outermost CORS filter would decide requests
 * before identity resolution ever runs. Cistern wants neither half: for an <em>actual</em>
 * request the Fetch model's own enforcement is the absence of
 * {@code Access-Control-Allow-Origin} (the browser refuses; the pod still authorizes,
 * records a receipt, echoes X-Request-Id and advertises the acl link), and for a preflight
 * the refusal belongs to {@link CorsFirstWebFilter}, which holds the exchange and writes a
 * bare 403 status — no free-text body outside the one error mapper.
 *
 * <p>So {@code rejectRequest} is a no-op; the boolean returned by {@code process} still says
 * whether the checks passed, and the filter acts on it where the request context lives.
 */
final class BrowserEnforcedCorsProcessor extends DefaultCorsProcessor {

    @Override
    protected void rejectRequest(ServerHttpResponse response) {
        // Decided by CorsFirstWebFilter: absence of headers for actual requests, a bare
        // 403 for failed preflights. Never a body from here.
    }
}
