package com.enrichmeai.cistern.webflux.auth;

import com.enrichmeai.cistern.core.Agent;

import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Turns a request into the {@link Agent} making it.
 *
 * <p>The seam between authentication and authorization, and the reason Phase 5 can be useful
 * before Phase 4 exists. The WAC engine needs to know <em>who</em> is asking; it does not care
 * <em>how</em> that was established. Separating the two means a self-hosted single-owner pod
 * needs only a credential it already has, while Solid-OIDC — which exists for interoperating
 * with the wider Solid world and for passing the conformance harness — can arrive later behind
 * the same interface without touching the enforcement path.
 *
 * <p>See {@code docs/ideas/first-user-path.md}. This is deliberately <strong>not</strong> an
 * authorization bypass: every implementation produces the same {@code Agent}, and every
 * request is evaluated by the same engine against the same owner-authored rules. Only the
 * proof of identity differs. A resolver that skipped enforcement would be a back door and
 * would break ARCHITECTURE decision 6.
 *
 * <p>Implementations must <strong>never</strong> signal an error for a bad or absent
 * credential: they return {@link Agent#ANONYMOUS}, and WAC decides what an anonymous agent may
 * do. Failing the request here would make an unauthenticated read of a public resource
 * impossible.
 */
public interface PrincipalResolver {

    /**
     * The agent making {@code exchange}.
     *
     * @return the authenticated agent, or {@link Agent#ANONYMOUS}. Never empty, never an error.
     */
    Mono<Agent> resolve(ServerWebExchange exchange);
}
