package com.enrichmeai.cistern.webflux.error;

import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

/**
 * Default {@link RequestAuthentication}: a request counts as authenticated iff something
 * upstream published an agent under {@link #AUTHENTICATED_AGENT_ATTRIBUTE}.
 *
 * <p>Nothing publishes that attribute yet — authentication is Phase 4 — so this reports
 * "no credentials presented" for every request today, and {@code AccessDenied} maps to 401.
 * That is the honest answer for a server with no authentication layer, not a stub: the
 * predicate is real, the attribute is simply never set.
 */
@Component
class ExchangeAttributeRequestAuthentication implements RequestAuthentication {

    @Override
    public boolean isAuthenticated(ServerWebExchange exchange) {
        return exchange.getAttribute(AUTHENTICATED_AGENT_ATTRIBUTE) != null;
    }
}
