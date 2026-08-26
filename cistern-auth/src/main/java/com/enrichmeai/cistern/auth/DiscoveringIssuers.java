package com.enrichmeai.cistern.auth;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.web.reactive.function.client.WebClient;

/**
 * Supplies a verifier for whichever issuer a token names, discovering its keys on demand
 * (the half of T4.1 that was deferred until there was something to anchor trust to).
 *
 * <p>It was deferred deliberately. Fetching a discovery document from an issuer named by an
 * unauthenticated caller is the same server-side request forgery primitive as dereferencing a
 * WebID, and until T4.3 existed there was nothing on the other side of it: any issuer that
 * answered could mint a token for anybody. That is now closed — a token verified here is still
 * refused unless the WebID it names authorises this issuer — so discovery is safe to do, and
 * necessary, because a pod cannot enumerate the identity providers of the world.
 *
 * <p>Two guards, both load-bearing:
 *
 * <ul>
 *   <li><strong>The same {@link WebIdFetchPolicy}</strong> the WebID fetch uses. An issuer of
 *       {@code https://169.254.169.254/} is refused here for exactly the reason it is refused
 *       there, and refusing costs nothing: an issuer that cannot be reached over public HTTPS
 *       could not have issued a token any WebID will vouch for.
 *   <li><strong>A bound on how many issuers are remembered.</strong> Each distinct {@code iss}
 *       costs a cached verifier and a discovery fetch, and {@code iss} is attacker-chosen — so
 *       without a bound a stream of tokens naming fresh issuers is unbounded memory and
 *       unbounded outbound requests. At the bound this refuses, which fails closed.
 * </ul>
 */
public final class DiscoveringIssuers implements SolidOidcTokenVerifier.Issuers {

    /** Distinct issuers remembered before this stops accepting new ones. */
    public static final int DEFAULT_MAXIMUM_ISSUERS = 512;

    private final WebClient http;
    private final WebIdFetchPolicy policy;
    private final Duration clockSkew;
    private final int maximumIssuers;
    private final Clock clock;
    private final ConcurrentHashMap<URI, JwtVerifier> verifiers = new ConcurrentHashMap<>();

    public DiscoveringIssuers(WebClient http, WebIdFetchPolicy policy, Duration clockSkew,
                              int maximumIssuers, Clock clock) {
        this.http = Objects.requireNonNull(http, "http");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.clockSkew = Objects.requireNonNull(clockSkew, "clockSkew");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (maximumIssuers <= 0) {
            throw new IllegalArgumentException(AuthMessage.ISSUER_BOUND_INVALID.format(maximumIssuers));
        }
        this.maximumIssuers = maximumIssuers;
    }

    public DiscoveringIssuers(WebClient http, WebIdFetchPolicy policy, Duration clockSkew, Clock clock) {
        this(http, policy, clockSkew, DEFAULT_MAXIMUM_ISSUERS, clock);
    }

    @Override
    public Optional<JwtVerifier> verifierFor(URI issuer) {
        if (issuer == null || policy.refuse(issuer).isPresent()) {
            return Optional.empty();
        }
        JwtVerifier known = verifiers.get(issuer);
        if (known != null) {
            return Optional.of(known);
        }
        if (verifiers.size() >= maximumIssuers) {
            return Optional.empty();
        }
        // computeIfAbsent rather than get-then-put: two requests naming the same new issuer
        // would otherwise build two key clients and make two discovery fetches.
        return Optional.of(verifiers.computeIfAbsent(issuer, this::build));
    }

    /**
     * The verifier for one issuer.
     *
     * <p>Cheap: {@link CachingJwksClient} discovers the {@code jwks_uri} and fetches keys
     * lazily, on the first token that needs them, so constructing this does no I/O and cannot
     * block the caller.
     */
    private JwtVerifier build(URI issuer) {
        OidcIssuer configured = new OidcIssuer(
                issuer, Set.of(SolidOidcTokenVerifier.SOLID_AUDIENCE), clockSkew);
        return new JwtVerifier(
                configured, new CachingJwksClient(http, configured, Optional.empty(), clock), clock);
    }

    /** How many issuers are currently remembered. */
    public int size() {
        return verifiers.size();
    }
}
