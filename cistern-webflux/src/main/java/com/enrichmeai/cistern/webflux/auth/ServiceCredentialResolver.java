package com.enrichmeai.cistern.webflux.auth;

import com.enrichmeai.cistern.core.Agent;

import java.util.Objects;

import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Recognises a service principal by its credential (T4.0, #88).
 *
 * <p>The application presents its secret as {@code Authorization: Bearer <secret>} — the same
 * field and scheme as the owner's local credential, so an HTTP client needs nothing new — and
 * the {@link ServicePrincipalRegistry} answers whose it is. The resulting {@link Agent} carries
 * the application's own WebID, so a grant to {@code valuedocs-legal} is a grant to it alone.
 *
 * <p>Like {@link LocalCredentialResolver}, this is a shared secret and carries no proof of
 * possession. It is appropriate for an application on a private network talking to its own
 * pod server; the OIDC resolver (cistern-auth) is the one for identities issued by someone
 * else.
 */
public final class ServiceCredentialResolver implements PrincipalResolver {

    private final ServicePrincipalRegistry registry;

    public ServiceCredentialResolver(ServicePrincipalRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override
    public Mono<Agent> resolve(ServerWebExchange exchange) {
        return Mono.fromSupplier(() -> BearerToken.from(exchange.getRequest())
                .flatMap(token -> registry.byCredential(token.value()))
                .map(principal -> Agent.of(principal.webId()))
                .orElse(Agent.ANONYMOUS));
    }
}
