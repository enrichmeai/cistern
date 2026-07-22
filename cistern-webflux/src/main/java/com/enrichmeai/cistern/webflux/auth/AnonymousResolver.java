package com.enrichmeai.cistern.webflux.auth;

import com.enrichmeai.cistern.core.Agent;

import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Authenticates nobody: every request is {@link Agent#ANONYMOUS}.
 *
 * <p>The default when no owner is configured. That leaves a fresh server able to serve
 * whatever its ACLs grant the public and nothing else, which is the correct posture — the
 * alternative, a built-in credential, would be a well-known password shipped in the jar.
 *
 * <p>It is also the honest behaviour for a pod with no authentication configured at all:
 * anonymous is not a degraded mode, it is who the requester actually is.
 */
public final class AnonymousResolver implements PrincipalResolver {

    @Override
    public Mono<Agent> resolve(ServerWebExchange exchange) {
        return Mono.just(Agent.ANONYMOUS);
    }
}
