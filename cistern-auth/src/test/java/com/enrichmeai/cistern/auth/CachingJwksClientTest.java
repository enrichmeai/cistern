package com.enrichmeai.cistern.auth;

import com.nimbusds.jose.jwk.JWKSet;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The key-set client over the wire (T4.0, #88): fetch, cache, TTL, refresh with a rate limit,
 * and discovery — against the documents Keycloak actually served, on a loopback HTTP server.
 */
class CachingJwksClientTest {

    private static final String KEYS_PATH = "/realms/cistern/protocol/openid-connect/certs";
    private static final String DISCOVERY_PATH = "/realms/cistern/.well-known/openid-configuration";
    private static final Duration TTL = Duration.ofMinutes(5);
    private static final Duration MIN_REFRESH = Duration.ofSeconds(30);

    private final FixtureServer server = new FixtureServer().serve(KEYS_PATH, Fixtures.text("jwks.json"));

    /** A clock the test moves by hand. */
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-08-19T00:10:00Z");

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private final MutableClock clock = new MutableClock();

    @AfterEach
    void stop() {
        server.close();
    }

    private CachingJwksClient client(Optional<URI> jwksUri) {
        OidcIssuer issuer = new OidcIssuer(server.uri("/realms/cistern"), Set.of("cistern"), Duration.ZERO);
        return new CachingJwksClient(WebClient.create(), issuer, jwksUri, TTL, MIN_REFRESH, clock);
    }

    private CachingJwksClient pinned() {
        return client(Optional.of(server.uri(KEYS_PATH)));
    }

    private static String kid(JWKSet set, int index) {
        return set.getKeys().get(index).getKeyID();
    }

    @Test
    @DisplayName("fetches and parses the published key set")
    void fetchesKeys() {
        StepVerifier.create(pinned().keys())
                .assertNext(keys -> {
                    assertEquals(Fixtures.jwks().size(), keys.size());
                    assertEquals(kid(Fixtures.jwks(), 0), kid(keys, 0));
                })
                .verifyComplete();
        assertEquals(1, server.hits(KEYS_PATH));
    }

    @Test
    @DisplayName("within the TTL the cached set is served — one request, many callers")
    void cachesWithinTtl() {
        CachingJwksClient client = pinned();
        client.keys().block();
        clock.advance(TTL.minusSeconds(1));
        client.keys().block();
        client.keys().block();
        assertEquals(1, server.hits(KEYS_PATH));
    }

    @Test
    @DisplayName("past the TTL the set is fetched again — a withdrawn key stops being trusted within minutes")
    void refetchesAfterTtl() {
        CachingJwksClient client = pinned();
        client.keys().block();
        clock.advance(TTL);
        client.keys().block();
        assertEquals(2, server.hits(KEYS_PATH));
    }

    @Test
    @DisplayName("refresh() sooner than the minimum interval is declined and serves the cached set")
    void refreshIsRateLimited() {
        CachingJwksClient client = pinned();
        JWKSet first = client.keys().block();
        server.serve(KEYS_PATH, Fixtures.text("jwks-rotated.json"));

        clock.advance(MIN_REFRESH.minusSeconds(1));
        JWKSet limited = client.refresh().block();
        assertNotNull(first);
        assertNotNull(limited);
        assertEquals(first.size(), limited.size(), "still the old set");
        assertEquals(1, server.hits(KEYS_PATH));

        clock.advance(Duration.ofSeconds(1));
        JWKSet refreshed = client.refresh().block();
        assertNotNull(refreshed);
        assertEquals(Fixtures.jwksRotated().size(), refreshed.size(), "now the rotated set");
        assertEquals(2, server.hits(KEYS_PATH));
    }

    @Test
    @DisplayName("refresh() on a cold cache fetches; the interval only applies once something is cached")
    void refreshOnColdCacheFetches() {
        StepVerifier.create(pinned().refresh()).expectNextCount(1).verifyComplete();
        assertEquals(1, server.hits(KEYS_PATH));
    }

    @Test
    @DisplayName("concurrent cold-start callers share one fetch")
    void concurrentCallersShareOneFetch() {
        CachingJwksClient client = pinned();
        Mono.zip(client.keys(), client.keys(), client.keys()).block();
        assertEquals(1, server.hits(KEYS_PATH));
    }

    @Test
    @DisplayName("without a configured jwks-uri, the key set is discovered from the issuer's well-known document")
    void discoversFromIssuer() {
        // The captured document names Keycloak's own port; the test server stands in for it,
        // so the one member this client reads is pointed at the test server. Everything else
        // in the document is verbatim.
        String discovery = Fixtures.openidConfiguration().replace(
                "http://localhost:8080" + KEYS_PATH, server.uri(KEYS_PATH).toString());
        assertTrue(discovery.contains(server.uri(KEYS_PATH).toString()), "rewrite must have applied");
        server.serve(DISCOVERY_PATH, discovery);

        CachingJwksClient client = client(Optional.empty());
        StepVerifier.create(client.keys()).expectNextCount(1).verifyComplete();
        clock.advance(TTL);
        client.keys().block();

        assertEquals(1, server.hits(DISCOVERY_PATH), "discovery happens once");
        assertEquals(2, server.hits(KEYS_PATH));
    }

    /** The discovery-with-vetting form {@link DiscoveringIssuers} builds, policy included. */
    private CachingJwksClient discovering(WebIdFetchPolicy policy) {
        OidcIssuer issuer = new OidcIssuer(server.uri("/realms/cistern"), Set.of("cistern"), Duration.ZERO);
        return new CachingJwksClient(WebClient.create(), issuer, policy, clock);
    }

    private void serveDiscoveryNaming(String jwksUri) {
        String discovery = Fixtures.openidConfiguration().replace(
                "http://localhost:8080" + KEYS_PATH, jwksUri);
        assertTrue(discovery.contains(jwksUri), "rewrite must have applied");
        server.serve(DISCOVERY_PATH, discovery);
    }

    /**
     * The SSRF at the follow: the issuer URI can be vetted and public, yet the document it
     * serves names where the next GET goes. Without this check a public-HTTPS issuer routes
     * the pod's own request at metadata services or anything else on the inside.
     */
    @Test
    @DisplayName("a discovered jwks_uri the fetch policy refuses is an error, not a fetch")
    void discoveredJwksUriIsVetted() {
        serveDiscoveryNaming("http://169.254.169.254/keys");

        StepVerifier.create(discovering(WebIdFetchPolicy.defaults()).keys())
                .expectErrorSatisfies(error -> {
                    assertTrue(error instanceof JwksUnavailableException, "the one expected failure");
                    assertNotNull(error.getMessage());
                    assertTrue(error.getMessage().contains("refuses"), error.getMessage());
                })
                .verify();
    }

    @Test
    @DisplayName("a public-https jwks_uri resolving to a private address is refused the same way")
    void discoveredJwksUriOnPrivateAddressIsRefused() {
        serveDiscoveryNaming("https://keys.internal.example/jwks");
        WebIdFetchPolicy policy = new WebIdFetchPolicy(WebIdFetchPolicy.DEFAULT_TIMEOUT,
                WebIdFetchPolicy.DEFAULT_MAX_REDIRECTS, WebIdFetchPolicy.DEFAULT_MAX_BODY_BYTES,
                host -> new java.net.InetAddress[] {java.net.InetAddress.getByName("10.0.0.7")}, java.util.Set.of());

        StepVerifier.create(discovering(policy).keys())
                .expectError(JwksUnavailableException.class)
                .verify();
    }

    @Test
    @DisplayName("a failed discovery is not remembered: the next request tries again")
    void failedDiscoveryIsRetried() {
        CachingJwksClient client = client(Optional.empty());
        StepVerifier.create(client.keys()).expectError(JwksUnavailableException.class).verify();

        server.serve(DISCOVERY_PATH, Fixtures.openidConfiguration().replace(
                "http://localhost:8080" + KEYS_PATH, server.uri(KEYS_PATH).toString()));
        StepVerifier.create(client.keys()).expectNextCount(1).verifyComplete();
    }

    @Test
    @DisplayName("an unreachable key set is JwksUnavailableException, the one failure the verifier expects")
    void unreachableIsUnavailable() {
        server.remove(KEYS_PATH);
        StepVerifier.create(pinned().keys()).expectError(JwksUnavailableException.class).verify();
    }

    @Test
    @DisplayName("a body that is not a key set is JwksUnavailableException too")
    void garbageIsUnavailable() {
        server.serve(KEYS_PATH, "<html>maintenance</html>");
        StepVerifier.create(pinned().keys()).expectError(JwksUnavailableException.class).verify();
    }

    @Test
    @DisplayName("after a failed fetch the next call fetches again rather than replaying the failure")
    void failureIsNotCached() {
        server.remove(KEYS_PATH);
        CachingJwksClient client = pinned();
        StepVerifier.create(client.keys()).expectError(JwksUnavailableException.class).verify();
        server.serve(KEYS_PATH, Fixtures.text("jwks.json"));
        StepVerifier.create(client.keys()).expectNextCount(1).verifyComplete();
    }
}
