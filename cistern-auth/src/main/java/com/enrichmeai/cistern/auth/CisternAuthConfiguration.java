package com.enrichmeai.cistern.auth;

import com.enrichmeai.cistern.webflux.CisternProperties;
import com.enrichmeai.cistern.webflux.auth.ChainedPrincipalResolver;

import java.time.Clock;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Spring wiring for the OIDC JWT resolver (T4.0, #88). Active only when
 * {@code cistern.auth.oidc.issuer} is set: an unconfigured server trusts no issuer, exactly as
 * it ships no owner credential.
 *
 * <p>Contributes to cistern-webflux's resolver chain rather than replacing it — the resolver
 * is exposed as a {@link ChainedPrincipalResolver.Member}, never as a {@code PrincipalResolver}
 * bean of its own, because a bean of that type would satisfy the chain's
 * {@code @ConditionalOnMissingBean} and silently switch the owner's and the service principals'
 * credentials off. The chain fixes the position: after the local secrets, before anonymous.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "cistern.auth.oidc", name = "issuer")
public class CisternAuthConfiguration {

    private static final Logger log = LoggerFactory.getLogger(CisternAuthConfiguration.class);

    /** The trusted issuer, from configuration. */
    @Bean
    @ConditionalOnMissingBean
    public OidcIssuer oidcIssuer(CisternProperties properties) {
        CisternProperties.Oidc oidc = properties.auth().oidc();
        return new OidcIssuer(oidc.issuer(), oidc.audiences(), oidc.clockSkew());
    }

    /** How a token's claims name a WebID: {@code webid-claim} or {@code webid-template}. */
    @Bean
    @ConditionalOnMissingBean
    public WebIdMapping webIdMapping(CisternProperties properties) {
        CisternProperties.Oidc oidc = properties.auth().oidc();
        return oidc.webidTemplate() != null
                ? new WebIdMapping.Template(oidc.webidTemplate())
                : new WebIdMapping.Claim(oidc.webidClaim());
    }

    /**
     * The issuer's keys, fetched with Boot's {@code WebClient.Builder} when one is available
     * (so an embedder's customisations — proxies, TLS — apply) and a plain builder otherwise.
     */
    @Bean
    @ConditionalOnMissingBean
    public JwksClient jwksClient(
            CisternProperties properties, OidcIssuer issuer, ObjectProvider<WebClient.Builder> builders) {
        WebClient http = builders.getIfAvailable(WebClient::builder).build();
        return new CachingJwksClient(
                http, issuer, Optional.ofNullable(properties.auth().oidc().jwksUri()), Clock.systemUTC());
    }

    /** The resolver, as a member of the chain. See the class comment for why not a bean of its own. */
    @Bean
    public ChainedPrincipalResolver.Member oidcJwtPrincipalResolverMember(
            OidcIssuer issuer, WebIdMapping mapping, JwksClient keys) {
        log.info(AuthMessage.OIDC_RESOLVER_WIRED.format(
                issuer.issuer(), issuer.audiences(), mapping, issuer.clockSkew()));
        return new ChainedPrincipalResolver.Member(
                new OidcJwtPrincipalResolver(issuer, mapping, keys, Clock.systemUTC()));
    }
}
