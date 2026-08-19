package com.enrichmeai.cistern.auth;

import com.enrichmeai.cistern.core.Agent;
import com.enrichmeai.cistern.webflux.auth.BearerToken;
import com.enrichmeai.cistern.webflux.auth.PrincipalResolver;

import java.time.Clock;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Authenticates a request by a JWT from the trusted OIDC issuer (T4.0, #88).
 *
 * <p>{@code Authorization: Bearer <JWT>} → {@link JwtVerifier} → {@link WebIdMapping} →
 * {@link Agent}. Phase 4-lite: this is a plain bearer JWT from one configured issuer, which is
 * what an application's own identity provider (Keycloak, Auth0, an in-house one) hands out and
 * what lets many humans use one pod today. It is <strong>not</strong> Solid-OIDC — no DPoP
 * proof, no {@code Authorization: DPoP} scheme, no dereferencing of the WebID to check the
 * issuer is one it names — and it does not pretend to be; T4.1–T4.4 add those behind the same
 * {@link PrincipalResolver} seam.
 *
 * <p>Honours the seam's contract to the letter: a bearer that is not a JWT, a JWT that does
 * not verify, or an issuer that cannot be reached all resolve to {@link Agent#ANONYMOUS} and
 * are logged (at a level chosen by the {@link JwtRejectionReason}); nothing here signals an
 * error, so a bad token is a 401 where a grant was needed and nothing at all where it was not.
 */
public final class OidcJwtPrincipalResolver implements PrincipalResolver {

    private static final Logger log = LoggerFactory.getLogger(OidcJwtPrincipalResolver.class);

    private final JwtVerifier verifier;
    private final WebIdMapping mapping;

    /**
     * @param issuer  whose tokens are trusted, and on what terms
     * @param mapping how a verified token's claims name a WebID
     * @param keys    the issuer's published keys
     * @param clock   the time {@code exp} and {@code nbf} are judged against
     */
    public OidcJwtPrincipalResolver(OidcIssuer issuer, WebIdMapping mapping, JwksClient keys, Clock clock) {
        this(new JwtVerifier(issuer, keys, clock), mapping);
    }

    OidcJwtPrincipalResolver(JwtVerifier verifier, WebIdMapping mapping) {
        this.verifier = Objects.requireNonNull(verifier, "verifier");
        this.mapping = Objects.requireNonNull(mapping, "mapping");
    }

    @Override
    public Mono<Agent> resolve(ServerWebExchange exchange) {
        return Mono.justOrEmpty(BearerToken.from(exchange.getRequest()))
                .flatMap(token -> verifier.verify(token.value()))
                .map(this::agentFor)
                .defaultIfEmpty(Agent.ANONYMOUS);
    }

    private Agent agentFor(JwtVerdict verdict) {
        return switch (verdict) {
            case JwtVerdict.Rejected rejected -> anonymous(rejected);
            case JwtVerdict.Accepted accepted -> switch (mapping.webIdOf(accepted.claims())) {
                case WebIdMapping.Result.Unmapped unmapped ->
                        anonymous(JwtVerdict.Rejected.of(unmapped.reason(), unmapped.detail()));
                case WebIdMapping.Result.WebId webId -> {
                    log.debug(AuthMessage.TOKEN_ACCEPTED.format(
                            webId.uri(), accepted.claims().getIssuer(), accepted.claims().getSubject()));
                    yield Agent.of(webId.uri());
                }
            };
        };
    }

    private static Agent anonymous(JwtVerdict.Rejected rejected) {
        log.atLevel(rejected.reason().level())
                .log(AuthMessage.TOKEN_REJECTED.format(rejected.reason(), rejected.detail()));
        return Agent.ANONYMOUS;
    }
}
