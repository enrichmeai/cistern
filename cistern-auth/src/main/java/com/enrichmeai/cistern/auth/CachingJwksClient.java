package com.enrichmeai.cistern.auth;

import com.nimbusds.jose.jwk.JWKSet;

import java.net.URI;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Fetches the issuer's key set with {@link WebClient}, caches it, and refreshes it on demand
 * (T4.0, #88).
 *
 * <p>Reactive throughout: Nimbus's own {@code RemoteJWKSet} would block a request thread on
 * the fetch, which ground rule 3 forbids, so this uses Nimbus for parsing only and WebClient
 * for the wire.
 *
 * <h2>Where the keys are</h2>
 * Either the operator says ({@code cistern.auth.oidc.jwks-uri}) or the issuer does: OIDC
 * Discovery §4 puts a document at {@code {issuer}/.well-known/openid-configuration} whose
 * {@code jwks_uri} member names the key set. Discovery happens once, lazily, on the first
 * request that needs a key; a failed discovery is not remembered, so an issuer that was down at
 * boot is retried on the next request rather than never.
 *
 * <h2>Caching</h2>
 * A fetched set is served for {@link #DEFAULT_TIME_TO_LIVE}, then fetched again on the next
 * request that needs it. The TTL is why a key an issuer has <em>withdrawn</em> stops being
 * trusted here within minutes rather than until restart. Concurrent requests that all find the
 * cache cold share one fetch.
 *
 * <h2>Refresh on unknown kid, rate-limited</h2>
 * A token signed with a key the cached set lacks is what a rotation looks like, so the
 * verifier asks for a {@link #refresh()}. But an attacker can mint tokens with any {@code kid}
 * they like, so a refresh sooner than {@link #DEFAULT_MINIMUM_REFRESH_INTERVAL} after the last
 * fetch is declined and the cached set returned; the token is then rejected as unknown-key,
 * which for a real rotation self-heals on the next refresh window. Nimbus's own source builder
 * defaults to the same 30-second floor.
 */
public final class CachingJwksClient implements JwksClient {

    private static final Logger log = LoggerFactory.getLogger(CachingJwksClient.class);

    /** How long a fetched set is served before it is fetched again. Nimbus's default too. */
    public static final Duration DEFAULT_TIME_TO_LIVE = Duration.ofMinutes(5);

    /** The floor between two fetches triggered by unknown kids. Nimbus's default too. */
    public static final Duration DEFAULT_MINIMUM_REFRESH_INTERVAL = Duration.ofSeconds(30);

    /**
     * How long one fetch (discovery or keys) may take. A request that is waiting on this is
     * waiting to authenticate; five seconds is generous for a document a few kilobytes long and
     * short enough that an issuer outage does not pile up half-open requests.
     */
    static final Duration FETCH_TIMEOUT = Duration.ofSeconds(5);

    /** Reactor's "cache forever": {@code Mono.cache} treats a TTL this long as no expiry. */
    private static final Duration FOREVER = Duration.ofMillis(Long.MAX_VALUE);

    private final WebClient http;
    private final Optional<WebIdFetchPolicy> discoveryPolicy;
    private final Mono<URI> jwksUri;
    private final Duration timeToLive;
    private final Duration minimumRefreshInterval;
    private final Clock clock;

    private final AtomicReference<Cached> cache = new AtomicReference<>();
    private final AtomicReference<Mono<JWKSet>> inFlight = new AtomicReference<>();

    /** A key set and when it arrived. */
    private record Cached(JWKSet keys, Instant fetchedAt) {

        boolean isFresh(Instant now, Duration timeToLive) {
            return now.isBefore(fetchedAt.plus(timeToLive));
        }

        Duration age(Instant now) {
            return Duration.between(fetchedAt, now);
        }
    }

    /**
     * @param http                   the client to fetch with
     * @param issuer                 whose keys; its discovery document is used when
     *                               {@code jwksUri} is empty
     * @param jwksUri                where the keys are, if the operator chose to say
     * @param timeToLive             how long a fetched set is served before refetching
     * @param minimumRefreshInterval floor between fetches triggered by {@link #refresh()}
     * @param clock                  the time source, injectable so tests can move it
     */
    public CachingJwksClient(
            WebClient http,
            OidcIssuer issuer,
            Optional<URI> jwksUri,
            Duration timeToLive,
            Duration minimumRefreshInterval,
            Clock clock) {
        this(http, issuer, jwksUri, Optional.empty(), timeToLive, minimumRefreshInterval, clock);
    }

    /** With the default TTL and refresh floor. */
    public CachingJwksClient(WebClient http, OidcIssuer issuer, Optional<URI> jwksUri, Clock clock) {
        this(http, issuer, jwksUri, DEFAULT_TIME_TO_LIVE, DEFAULT_MINIMUM_REFRESH_INTERVAL, clock);
    }

    /**
     * Discovery with the discovered {@code jwks_uri} vetted by {@code discoveryPolicy} — the
     * form {@link DiscoveringIssuers} uses, where the issuer (and so the document naming the
     * key set) is chosen by an unauthenticated caller. The operator-configured constructors
     * above take no policy on purpose: a location the operator wrote into configuration is
     * trusted the way the rest of the configuration is.
     */
    public CachingJwksClient(WebClient http, OidcIssuer issuer, WebIdFetchPolicy discoveryPolicy, Clock clock) {
        this(http, issuer, Optional.empty(), Optional.of(discoveryPolicy),
                DEFAULT_TIME_TO_LIVE, DEFAULT_MINIMUM_REFRESH_INTERVAL, clock);
    }

    private CachingJwksClient(
            WebClient http,
            OidcIssuer issuer,
            Optional<URI> jwksUri,
            Optional<WebIdFetchPolicy> discoveryPolicy,
            Duration timeToLive,
            Duration minimumRefreshInterval,
            Clock clock) {
        this.http = Objects.requireNonNull(http, "http");
        Objects.requireNonNull(issuer, "issuer");
        Objects.requireNonNull(jwksUri, "jwksUri");
        this.discoveryPolicy = Objects.requireNonNull(discoveryPolicy, "discoveryPolicy");
        this.timeToLive = Objects.requireNonNull(timeToLive, "timeToLive");
        this.minimumRefreshInterval =
                Objects.requireNonNull(minimumRefreshInterval, "minimumRefreshInterval");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.jwksUri = jwksUri.map(Mono::just)
                .orElseGet(() -> discover(issuer.discoveryDocument())
                        // Success is remembered for good; a failure not at all, so the next
                        // request tries again rather than inheriting a boot-time outage.
                        .cache(uri -> FOREVER, error -> Duration.ZERO, () -> Duration.ZERO));
    }

    @Override
    public Mono<JWKSet> keys() {
        return Mono.defer(() -> {
            Cached current = cache.get();
            if (current != null && current.isFresh(clock.instant(), timeToLive)) {
                return Mono.just(current.keys());
            }
            return fetch();
        });
    }

    @Override
    public Mono<JWKSet> refresh() {
        return Mono.defer(() -> {
            Cached current = cache.get();
            if (current != null) {
                Duration age = current.age(clock.instant());
                if (age.compareTo(minimumRefreshInterval) < 0) {
                    log.debug(AuthMessage.JWKS_REFRESH_RATE_LIMITED.format(age, minimumRefreshInterval));
                    return Mono.just(current.keys());
                }
            }
            return fetch();
        });
    }

    /** One download shared by everyone who asks while it is in flight. */
    private Mono<JWKSet> fetch() {
        Mono<JWKSet> existing = inFlight.get();
        if (existing != null) {
            return existing;
        }
        AtomicReference<Mono<JWKSet>> self = new AtomicReference<>();
        Mono<JWKSet> fresh = Mono.defer(this::download)
                .doOnNext(keys -> cache.set(new Cached(keys, clock.instant())))
                // Cleared before the result is handed on (doOnSuccess/doOnError run ahead of
                // the downstream signal), so a caller that sees the answer never finds this
                // completed fetch still registered as the one in flight.
                .doOnSuccess(keys -> inFlight.compareAndSet(self.get(), null))
                .doOnError(error -> inFlight.compareAndSet(self.get(), null))
                .cache();
        self.set(fresh);
        Mono<JWKSet> witness = inFlight.compareAndExchange(null, fresh);
        return witness == null ? fresh : witness;
    }

    private Mono<JWKSet> download() {
        return jwksUri.flatMap(uri -> http.get()
                .uri(uri)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(FETCH_TIMEOUT)
                .map(body -> parseKeys(body, uri))
                .doOnNext(keys -> log.debug(AuthMessage.JWKS_LOADED.format(keys.size(), uri)))
                .onErrorMap(
                        error -> !(error instanceof JwksUnavailableException),
                        error -> new JwksUnavailableException(
                                AuthMessage.JWKS_FETCH_FAILED.format(uri, error), error)));
    }

    private static JWKSet parseKeys(String body, URI from) {
        try {
            return JWKSet.parse(body);
        } catch (ParseException e) {
            throw new JwksUnavailableException(
                    AuthMessage.JWKS_FETCH_FAILED.format(from, e.getMessage()), e);
        }
    }

    private Mono<URI> discover(URI discoveryDocument) {
        return http.get()
                .uri(discoveryDocument)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .bodyToMono(String.class)
                .timeout(FETCH_TIMEOUT)
                .map(body -> OidcProviderMetadata.parse(body, discoveryDocument).jwksUri())
                .flatMap(this::vetted)
                .onErrorMap(
                        error -> !(error instanceof JwksUnavailableException),
                        error -> new JwksUnavailableException(
                                AuthMessage.DISCOVERY_FAILED.format(discoveryDocument, error), error));
    }

    /**
     * The discovered {@code jwks_uri}, if the discovery policy allows fetching it.
     *
     * <p>That URI comes out of a document the issuer wrote. Vetting the issuer and then
     * following whatever its document advertises would leave exactly the hole the vetting
     * exists to close: a public-HTTPS issuer whose {@code jwks_uri} points this pod's next
     * GET at a private or link-local address. So the same policy rules again here — and since
     * its check resolves DNS, which blocks, it runs on {@code boundedElastic} and under the
     * same budget as the fetch it gates.
     */
    private Mono<URI> vetted(URI discovered) {
        return discoveryPolicy
                .map(policy -> Mono.fromCallable(() -> policy.refuse(discovered))
                        .subscribeOn(Schedulers.boundedElastic())
                        .timeout(FETCH_TIMEOUT)
                        .flatMap(refusal -> refusal
                                .<Mono<URI>>map(reason -> Mono.error(new JwksUnavailableException(
                                        AuthMessage.JWKS_URI_REFUSED.format(reason.describe(discovered)))))
                                .orElseGet(() -> Mono.just(discovered))))
                .orElseGet(() -> Mono.just(discovered));
    }
}
