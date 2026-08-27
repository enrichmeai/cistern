package com.enrichmeai.cistern.auth;

import com.enrichmeai.cistern.webflux.CisternProperties;
import com.enrichmeai.cistern.webflux.auth.ChainedPrincipalResolver;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.Set;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Wires Solid-OIDC authentication into the request path (T4.4).
 *
 * <p><strong>Off unless asked for</strong> — {@code cistern.auth.solid.enabled=true}. This is
 * the switch that lets a pod accept credentials from identity providers it has never been
 * configured with, which is what interoperable Solid requires and also a considerable change
 * in posture for a single-owner pod that wants none of it. An operator should choose that,
 * not inherit it from an upgrade.
 *
 * <p>Registered as a {@link ChainedPrincipalResolver.Member} rather than a
 * {@code PrincipalResolver}, for the reason {@link CisternAuthConfiguration} gives: a bean of
 * the bare type would satisfy a {@code @ConditionalOnMissingBean} elsewhere and silently
 * replace the owner's credential and the service principals instead of joining them.
 */
@Configuration
@ConditionalOnProperty(prefix = "cistern.auth.solid", name = "enabled", havingValue = "true")
public class CisternSolidOidcConfiguration {

    private static final Logger log = LoggerFactory.getLogger(CisternSolidOidcConfiguration.class);

    /** How long an {@code iat} may be either side of now (RFC 9449 §4.3 step 11). */
    static final Duration PROOF_ACCEPTANCE_WINDOW = Duration.ofSeconds(60);

    /** How long a verified WebID → issuer authorisation is trusted before re-fetching. */
    static final Duration WEBID_CACHE_TTL = Duration.ofMinutes(5);

    @Bean
    @ConditionalOnMissingBean
    public JtiReplayCache jtiReplayCache() {
        // Retention matches the acceptance window: outside it the proof is already rejected on
        // time, so a forgotten jti opens no replay.
        return new JtiReplayCache(PROOF_ACCEPTANCE_WINDOW, Clock.systemUTC());
    }

    /**
     * The fetch policy, plus whatever origins {@code cistern.auth.solid.webid.trusted-origins}
     * names.
     *
     * <p>Logged at WARN whenever the list is non-empty, and named in full. An operator who
     * opened an origin for a conformance run and forgot is told on every boot rather than
     * discovering it from an incident — the whole value of naming origins instead of flipping
     * a switch is that the trust is visible, and a log nobody prints is not visible.
     */
    @Bean
    @ConditionalOnMissingBean
    public WebIdFetchPolicy webIdFetchPolicy(
            @Value("${cistern.auth.solid.webid.trusted-origins:}") String trustedOrigins) {
        // Split here rather than letting @Value convert to a Set: it does not reliably split a
        // comma-separated value, and a two-origin list arrived as one string — which
        // canonicalOrigin then turned into a single unusable entry. Bound as a String and
        // split explicitly, the behaviour is the same whether the value comes from a file, an
        // environment variable or the command line.
        Set<String> configured = java.util.Arrays.stream(trustedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (!configured.isEmpty()) {
            log.warn(AuthMessage.WEBID_ORIGINS_TRUSTED.format(configured.size(), configured));
        }
        return WebIdFetchPolicy.trusting(configured);
    }

    @Bean
    @ConditionalOnMissingBean
    public WebIdIssuers webIdIssuers(WebIdFetchPolicy policy, ObjectProvider<WebClient.Builder> builders) {
        WebClient http = builders.getIfAvailable(WebClient::builder).build();
        return new WebIdIssuerVerifier(http, policy, WEBID_CACHE_TTL, Clock.systemUTC());
    }

    /** How far apart this pod's clock and an issuer's may be (RFC 7519 §4.1.4). */
    static final Duration CLOCK_SKEW = Duration.ofSeconds(60);

    @Bean
    @ConditionalOnMissingBean
    public SolidOidcTokenVerifier.Issuers discoveringIssuers(
            WebIdFetchPolicy policy, ObjectProvider<WebClient.Builder> builders) {
        WebClient http = builders.getIfAvailable(WebClient::builder).build();
        return new DiscoveringIssuers(http, policy, CLOCK_SKEW, Clock.systemUTC());
    }

    /**
     * The Solid-OIDC resolver, as a member of the chain.
     *
     * <p>Discovery is safe here only because the WebID check is behind it: this trusts any
     * issuer that answers over public HTTPS, and what stops that mattering is that the WebID
     * must then name it. Remove {@link WebIdIssuers} from this composition and it becomes an
     * open door.
     */
    @Bean
    public ChainedPrincipalResolver.Member solidOidcPrincipalResolverMember(
            CisternProperties properties, WebIdIssuers webIds, JtiReplayCache replayCache,
            SolidOidcTokenVerifier.Issuers issuers) {
        Clock clock = Clock.systemUTC();
        SolidOidcTokenVerifier tokens = new SolidOidcTokenVerifier(issuers);
        DpopValidator proofs = new DpopValidator(PROOF_ACCEPTANCE_WINDOW, replayCache, clock);
        URI baseUrl = URI.create(properties.baseUrl());

        log.info(AuthMessage.SOLID_RESOLVER_WIRED.format(baseUrl, PROOF_ACCEPTANCE_WINDOW, WEBID_CACHE_TTL));
        return new ChainedPrincipalResolver.Member(
                new SolidOidcPrincipalResolver(tokens, proofs, webIds, baseUrl));
    }
}
