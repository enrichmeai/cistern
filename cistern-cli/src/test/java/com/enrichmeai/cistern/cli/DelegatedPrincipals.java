package com.enrichmeai.cistern.cli;

import com.enrichmeai.cistern.core.Agent;
import com.enrichmeai.cistern.webflux.auth.ChainedPrincipalResolver;
import com.enrichmeai.cistern.webflux.auth.PrincipalResolver;

import java.net.URI;
import java.util.Map;
import java.util.Optional;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Three bearer credentials that authenticate alice — alone, through the claude client, and
 * through another — contributed to the real resolver chain as a
 * {@link ChainedPrincipalResolver.Member}, the extension point cistern-auth's OIDC resolver
 * uses (T6.5).
 *
 * <p>Not a fixture of any wire format: the token-to-principal mapping a real identity provider
 * performs is proven in cistern-auth against captured Keycloak tokens
 * ({@code fixtures/keycloak-delegation}). This module's end-to-end test is about what the CLI
 * <em>writes</em> and what the server then <em>decides</em> for a (person, client) principal,
 * and cistern-auth is not on its classpath; so the principal is supplied here, at the seam the
 * architecture provides for exactly that, and every request still crosses
 * {@code AuthorizationFilter}, {@code WacEngine} and the receipt.
 */
@Configuration(proxyBeanMethods = false)
class DelegatedPrincipals {

    static final URI ALICE = URI.create("https://alice.example/profile/card#me");
    static final URI CLAUDE = URI.create("https://agents.example/claude#id");
    static final URI OTHER = URI.create("https://agents.example/other#id");

    static final String ALICE_ALONE = "t65-alice-alone";
    static final String ALICE_VIA_CLAUDE = "t65-alice-via-claude";
    static final String ALICE_VIA_OTHER = "t65-alice-via-other";

    private static final Map<String, Agent> AGENTS = Map.of(
            ALICE_ALONE, Agent.of(ALICE),
            ALICE_VIA_CLAUDE, Agent.of(ALICE, Optional.of(CLAUDE)),
            ALICE_VIA_OTHER, Agent.of(ALICE, Optional.of(OTHER)));

    /**
     * Named for the member it contributes, not for this class: registering a {@code @Configuration}
     * class as a {@code SpringApplicationBuilder} source also registers it under its own
     * decapitalized name, so a {@code @Bean} method called {@code delegatedPrincipals} would be a
     * definition of that same name and the context would refuse to start.
     */
    @Bean
    ChainedPrincipalResolver.Member delegatedPrincipalResolver() {
        return new ChainedPrincipalResolver.Member(new PrincipalResolver() {
            @Override
            public Mono<Agent> resolve(ServerWebExchange exchange) {
                return Mono.fromSupplier(() -> com.enrichmeai.cistern.webflux.auth.BearerToken
                        .from(exchange.getRequest())
                        .map(token -> AGENTS.getOrDefault(token.value(), Agent.ANONYMOUS))
                        .orElse(Agent.ANONYMOUS));
            }
        });
    }
}
