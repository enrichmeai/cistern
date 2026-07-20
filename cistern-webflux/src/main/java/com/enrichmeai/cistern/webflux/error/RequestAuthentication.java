package com.enrichmeai.cistern.webflux.error;

import org.springframework.web.server.ServerWebExchange;

/**
 * The single seam by which the error mapper decides <strong>401 vs 403</strong> for
 * {@link com.enrichmeai.cistern.core.CisternException.AccessDenied}.
 *
 * <p>Solid Protocol §2.1: <em>"When a client does not provide valid credentials when
 * requesting a resource that requires it, servers MUST send a response with a {@code 401}
 * status code"</em>. A denial against an agent that <em>did</em> authenticate is a {@code 403}
 * — re-authenticating would not help. WAC (see {@code docs/ARCHITECTURE.md}, decision 5)
 * states the same rule: "No effective ACL grants the mode → 403 (401 if unauthenticated)".
 *
 * <p><strong>There is no authentication layer yet</strong> — Solid-OIDC / DPoP validation is
 * Phase 4 (`cistern-auth`), and this module does not fake it. The contract is therefore
 * expressed as an exchange attribute rather than as a credential check:
 * {@link #AUTHENTICATED_AGENT_ATTRIBUTE} is absent today, so every {@code AccessDenied}
 * currently maps to 401. When the Phase 4 authentication {@code WebFilter} lands and
 * publishes the authenticated agent's WebID under that attribute, the 403 branch starts
 * firing with no change to this module. Alternatively, replace the
 * {@link ExchangeAttributeRequestAuthentication} bean with an implementation that consults
 * whatever the auth module ends up storing.
 *
 * @see ExchangeAttributeRequestAuthentication
 */
@FunctionalInterface
public interface RequestAuthentication {

    /**
     * Exchange attribute under which the Phase 4 authentication filter is expected to
     * publish the authenticated agent (its WebID). Absent ⇒ the request presented no
     * usable credentials.
     */
    String AUTHENTICATED_AGENT_ATTRIBUTE = "com.enrichmeai.cistern.auth.AUTHENTICATED_AGENT";

    /**
     * @return {@code true} iff the request was made with credentials the server accepted.
     *         Must not perform I/O: it is consulted while rendering an error response, on
     *         state the request pipeline has already resolved.
     */
    boolean isAuthenticated(ServerWebExchange exchange);
}
