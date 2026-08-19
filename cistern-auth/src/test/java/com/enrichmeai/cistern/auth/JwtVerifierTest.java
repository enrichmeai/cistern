package com.enrichmeai.cistern.auth;

import com.nimbusds.jose.JWSAlgorithm;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The verifier against tokens Keycloak actually issued (T4.0, #88). Every rejection is asserted
 * by its typed reason, so each test proves the check it names is the one that fired.
 *
 * <p>The system clock is fine for the valid tokens (the fixture realm issues ten-year tokens,
 * see {@code capture.sh}) and for the expired one (a 60-second token captured 2026-08-19); the
 * skew tests pin the clock instead.
 */
class JwtVerifierTest {

    private static final Clock NOW = Clock.systemUTC();

    private static JwtVerifier verifier(JwksClient keys) {
        return new JwtVerifier(Fixtures.TRUSTED, keys, NOW);
    }

    private static JwtVerdict.Rejected expectRejected(JwtVerifier verifier, String token, JwtRejectionReason reason) {
        JwtVerdict verdict = verifier.verify(token).block();
        JwtVerdict.Rejected rejected = assertInstanceOf(JwtVerdict.Rejected.class, verdict, String.valueOf(verdict));
        assertEquals(reason, rejected.reason(), rejected.detail());
        return rejected;
    }

    // ---- accepted -----------------------------------------------------------------------

    @Test
    @DisplayName("alice's token: signature against key set 1, iss, aud, exp all pass")
    void validTokenIsAccepted() {
        StepVerifier.create(verifier(new InMemoryJwksClient(Fixtures.jwks())).verify(Fixtures.token("alice-valid")))
                .assertNext(verdict -> {
                    JwtVerdict.Accepted accepted = assertInstanceOf(JwtVerdict.Accepted.class, verdict);
                    assertEquals(Fixtures.ISSUER.toString(), accepted.claims().getIssuer());
                    assertTrue(accepted.claims().getAudience().contains(Fixtures.AUDIENCE));
                    assertEquals(Fixtures.ALICE.toString(), accepted.claims().getClaim("webid"));
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("client-credentials tokens of the two applications are accepted, with their own webid claims")
    void serviceAccountTokensAreAccepted() {
        JwtVerifier verifier = verifier(new InMemoryJwksClient(Fixtures.jwks()));
        JwtVerdict.Accepted legal = assertInstanceOf(JwtVerdict.Accepted.class,
                verifier.verify(Fixtures.token("valuedocs-legal-valid")).block());
        JwtVerdict.Accepted tax = assertInstanceOf(JwtVerdict.Accepted.class,
                verifier.verify(Fixtures.token("valuedocs-tax-valid")).block());
        assertEquals(Fixtures.VALUEDOCS_LEGAL.toString(), legal.claims().getClaim("webid"));
        assertEquals(Fixtures.VALUEDOCS_TAX.toString(), tax.claims().getClaim("webid"));
    }

    // ---- the DoD matrix -----------------------------------------------------------------

    @Test
    @DisplayName("expired: a 60-second token, long past")
    void expiredTokenIsRejected() {
        expectRejected(verifier(new InMemoryJwksClient(Fixtures.jwks())),
                Fixtures.token("alice-expired"), JwtRejectionReason.EXPIRED);
    }

    @Test
    @DisplayName("wrong audience: a token from a client without the audience mapper carries only aud=account")
    void wrongAudienceIsRejected() {
        JwtVerdict.Rejected rejected = expectRejected(verifier(new InMemoryJwksClient(Fixtures.jwks())),
                Fixtures.token("alice-wrong-audience"), JwtRejectionReason.AUDIENCE_MISMATCH);
        assertTrue(rejected.detail().contains("account"), rejected.detail());
    }

    @Test
    @DisplayName("bad signature: the kid is known, and the signature does not verify against it")
    void badSignatureIsRejected() {
        expectRejected(verifier(new InMemoryJwksClient(Fixtures.jwks())),
                Fixtures.token("alice-bad-signature"), JwtRejectionReason.SIGNATURE_INVALID);
    }

    @Test
    @DisplayName("wrong issuer: the same token is not for a pod that trusts a different issuer")
    void wrongIssuerIsRejected() {
        OidcIssuer other = new OidcIssuer(
                URI.create("https://id.other.example/realms/cistern"), Set.of(Fixtures.AUDIENCE), Duration.ZERO);
        JwtVerifier verifier = new JwtVerifier(other, new InMemoryJwksClient(Fixtures.jwks()), NOW);

        expectRejected(verifier, Fixtures.token("alice-valid"), JwtRejectionReason.ISSUER_MISMATCH);
    }

    @Test
    @DisplayName("not a JWT: the owner's token or a service secret ends up here when nothing earlier claimed it")
    void nonJwtIsMalformed() {
        expectRejected(verifier(new InMemoryJwksClient(Fixtures.jwks())), "owner-token-3f9a", JwtRejectionReason.MALFORMED);
        expectRejected(verifier(new InMemoryJwksClient(Fixtures.jwks())), "a.b", JwtRejectionReason.MALFORMED);
    }

    // ---- key rotation -------------------------------------------------------------------

    @Test
    @DisplayName("rotation: an unknown kid triggers one refresh, and the token verifies against the new set")
    void unknownKidRefreshesAndVerifies() {
        InMemoryJwksClient keys = new InMemoryJwksClient(Fixtures.jwks(), Fixtures.jwksRotated());

        StepVerifier.create(verifier(keys).verify(Fixtures.token("alice-rotated-key")))
                .assertNext(verdict -> assertInstanceOf(JwtVerdict.Accepted.class, verdict, String.valueOf(verdict)))
                .verifyComplete();
        assertEquals(1, keys.refreshCalls.get(), "exactly one refresh for an unknown kid");
    }

    @Test
    @DisplayName("an unknown kid the issuer still does not publish after a refresh is KEY_UNKNOWN, not an error")
    void unknownKidAfterRefreshIsRejected() {
        InMemoryJwksClient keys = new InMemoryJwksClient(Fixtures.jwks());

        JwtVerdict.Rejected rejected = expectRejected(verifier(keys),
                Fixtures.token("alice-rotated-key"), JwtRejectionReason.KEY_UNKNOWN);
        assertEquals(1, keys.refreshCalls.get());
        assertTrue(rejected.detail().contains(Fixtures.parsed("alice-rotated-key").getHeader().getKeyID()));
    }

    @Test
    @DisplayName("a known kid never triggers a refresh")
    void knownKidDoesNotRefresh() {
        InMemoryJwksClient keys = new InMemoryJwksClient(Fixtures.jwks());
        verifier(keys).verify(Fixtures.token("alice-valid")).block();
        assertEquals(0, keys.refreshCalls.get());
    }

    // ---- the issuer is down -------------------------------------------------------------

    @Test
    @DisplayName("keys unavailable: a verdict, never an error — the request goes on as anonymous")
    void keysUnavailableIsAVerdict() {
        JwtVerifier verifier = verifier(InMemoryJwksClient.unavailable("connection refused"));

        StepVerifier.create(verifier.verify(Fixtures.token("alice-valid")))
                .assertNext(verdict -> {
                    JwtVerdict.Rejected rejected = assertInstanceOf(JwtVerdict.Rejected.class, verdict);
                    assertEquals(JwtRejectionReason.KEYS_UNAVAILABLE, rejected.reason());
                    assertTrue(rejected.detail().contains("connection refused"), rejected.detail());
                })
                .verifyComplete();
    }

    // ---- clock skew ---------------------------------------------------------------------

    private static Clock fixed(Instant instant) {
        return Clock.fixed(instant, ZoneOffset.UTC);
    }

    @Test
    @DisplayName("within skew after exp is still valid; beyond it is expired")
    void skewAroundExpiry() {
        Instant exp = Fixtures.claims("alice-expired").getExpirationTime().toInstant();
        Duration skew = Duration.ofSeconds(60);
        OidcIssuer trusted = new OidcIssuer(Fixtures.ISSUER, Set.of(Fixtures.AUDIENCE), skew);

        JwtVerifier justInside = new JwtVerifier(trusted, new InMemoryJwksClient(Fixtures.jwks()),
                fixed(exp.plus(skew).minusSeconds(1)));
        assertInstanceOf(JwtVerdict.Accepted.class, justInside.verify(Fixtures.token("alice-expired")).block());

        JwtVerifier atTheEdge = new JwtVerifier(trusted, new InMemoryJwksClient(Fixtures.jwks()),
                fixed(exp.plus(skew)));
        expectRejected(atTheEdge, Fixtures.token("alice-expired"), JwtRejectionReason.EXPIRED);
    }

    @Test
    @DisplayName("zero skew: valid until exp exactly, expired at exp")
    void zeroSkew() {
        Instant exp = Fixtures.claims("alice-expired").getExpirationTime().toInstant();
        OidcIssuer strict = new OidcIssuer(Fixtures.ISSUER, Set.of(Fixtures.AUDIENCE), Duration.ZERO);

        assertInstanceOf(JwtVerdict.Accepted.class, new JwtVerifier(strict, new InMemoryJwksClient(Fixtures.jwks()),
                fixed(exp.minusSeconds(1))).verify(Fixtures.token("alice-expired")).block());
        expectRejected(new JwtVerifier(strict, new InMemoryJwksClient(Fixtures.jwks()), fixed(exp)),
                Fixtures.token("alice-expired"), JwtRejectionReason.EXPIRED);
    }

    @Test
    @DisplayName("only asymmetric algorithms are accepted: no HMAC, so a JWKS key can never be a shared secret")
    void hmacIsNotAccepted() {
        assertTrue(JwtVerifier.ACCEPTED_ALGORITHMS.contains(JWSAlgorithm.RS256));
        assertTrue(JwtVerifier.ACCEPTED_ALGORITHMS.contains(JWSAlgorithm.ES256));
        assertFalse(JwtVerifier.ACCEPTED_ALGORITHMS.contains(JWSAlgorithm.HS256));
    }
}
