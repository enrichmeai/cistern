package com.enrichmeai.cistern.auth;

import com.enrichmeai.cistern.core.Agent;

import java.net.URI;
import java.time.Clock;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The T4.1 verification matrix, run against tokens a real Solid identity provider issued
 * ({@code fixtures/css/}). Every assertion here is about behaviour the specification does not
 * define — §9.2/§9.3/§8.1.1 state authorization-server requirements — so the fixtures, not a
 * reading, are what these tests hold the verifier to.
 */
class SolidOidcTokenVerifierTest {

    /** The verifier a pod that trusts the captured issuer would build, judged at {@code clock}. */
    private static SolidOidcTokenVerifier trusting(Clock clock) {
        JwtVerifier verifier = new JwtVerifier(
                CssFixtures.TRUSTED, new InMemoryJwksClient(CssFixtures.jwks()), clock);
        return new SolidOidcTokenVerifier(
                issuer -> CssFixtures.ISSUER.equals(issuer) ? Optional.of(verifier) : Optional.empty());
    }

    private static SolidOidcIdentity accept(String token, Clock clock) {
        SolidOidcVerdict verdict = trusting(clock).verify(token).block();
        assertThat(verdict).isInstanceOf(SolidOidcVerdict.Accepted.class);
        return ((SolidOidcVerdict.Accepted) verdict).identity();
    }

    private static void reject(String token, Clock clock, JwtRejectionReason expected) {
        StepVerifier.create(trusting(clock).verify(token))
                .assertNext(verdict -> assertThat(verdict)
                        .isInstanceOfSatisfying(SolidOidcVerdict.Rejected.class,
                                rejected -> assertThat(rejected.reason()).isEqualTo(expected)))
                .verifyComplete();
    }

    @Nested
    @DisplayName("the captured token")
    class Valid {

        @Test
        @DisplayName("is accepted, and names the WebID CSS provisioned")
        void accepted() {
            assertThat(accept(CssFixtures.accessToken(), CssFixtures.whileValid()).webId())
                    .isEqualTo(CssFixtures.ALICE);
        }

        @Test
        @DisplayName("carries the cnf.jkt that T4.2 will hold the DPoP proof against")
        void thumbprint() {
            assertThat(accept(CssFixtures.accessToken(), CssFixtures.whileValid()).thumbprint())
                    .isNotBlank();
        }

        @Test
        @DisplayName("names its issuer, for T4.3 to hold the WebID against")
        void issuer() {
            assertThat(accept(CssFixtures.accessToken(), CssFixtures.whileValid()).issuer())
                    .isEqualTo(CssFixtures.ISSUER);
        }

        /**
         * The finding that settled issue #89: {@code client_id} is on the access token, so the
         * client is knowable at authentication. Under a client-credentials grant its value is
         * CSS's opaque credential id rather than a Client Identifier Document, and an opaque
         * string names no client a policy could match — so it reads as absent, by design.
         */
        @Test
        @DisplayName("carries client_id, and an opaque one reads as no client")
        void opaqueClientIsAbsent() {
            assertThat(CssFixtures.claims("access-token").getClaim("client_id"))
                    .isInstanceOf(String.class);
            assertThat(accept(CssFixtures.accessToken(), CssFixtures.whileValid()).client())
                    .isEmpty();
        }

        /** The audience is the literal "solid" — checking it against our own origin would fail. */
        @Test
        @DisplayName("has aud \"solid\", not this pod's origin")
        void audienceIsTheSolidLiteral() {
            assertThat(CssFixtures.claims("access-token").getAudience())
                    .containsExactly(SolidOidcTokenVerifier.SOLID_AUDIENCE);
        }

        /**
         * Verifying is not authenticating: the identity converts to an Agent only once T4.2 and
         * T4.3 have run. This asserts the conversion carries both halves when it does happen.
         */
        @Test
        @DisplayName("converts to an Agent carrying webId and client")
        void toAgent() {
            SolidOidcIdentity identity = new SolidOidcIdentity(
                    CssFixtures.ALICE, Optional.of(URI.create("https://app.example/id")),
                    CssFixtures.ISSUER, "thumbprint");
            Agent agent = identity.toAgent();
            assertThat(agent.webId()).contains(CssFixtures.ALICE);
            assertThat(agent.client()).contains(URI.create("https://app.example/id"));
            assertThat(agent.isAuthenticated()).isTrue();
        }
    }

    @Nested
    @DisplayName("is rejected")
    class Rejected {

        @Test
        @DisplayName("when the same token is judged after its expiry")
        void expired() {
            reject(CssFixtures.accessToken(), CssFixtures.afterExpiry(), JwtRejectionReason.EXPIRED);
        }

        @Test
        @DisplayName("when signed by a key the issuer does not publish")
        void wrongKey() {
            reject(CssFixtures.token("access-token-wrong-key"), CssFixtures.whileValid(),
                    JwtRejectionReason.KEY_UNKNOWN);
        }

        @Test
        @DisplayName("when it names an issuer this pod will not talk to")
        void untrustedIssuer() {
            reject(CssFixtures.token("access-token-wrong-issuer"), CssFixtures.whileValid(),
                    JwtRejectionReason.ISSUER_UNTRUSTED);
        }

        /**
         * The other issuer failure, and the one the DoD's "wrong-issuer" case names: a pod that
         * <em>does</em> hand out a verifier still refuses a token whose {@code iss} is not the
         * one that verifier was built for. Reached only by trusting the issuer far enough to
         * verify — which is why {@link #untrustedIssuer()} above cannot stand in for it.
         */
        @Test
        @DisplayName("when iss is not the issuer its verifier was built for")
        void issuerMismatch() {
            JwtVerifier builtForCss = new JwtVerifier(
                    CssFixtures.TRUSTED, new InMemoryJwksClient(CssFixtures.jwksForeign()),
                    CssFixtures.whileValid());
            SolidOidcTokenVerifier verifier =
                    new SolidOidcTokenVerifier(issuer -> Optional.of(builtForCss));
            StepVerifier.create(verifier.verify(CssFixtures.token("access-token-wrong-issuer")))
                    .assertNext(verdict -> assertThat(verdict)
                            .isInstanceOfSatisfying(SolidOidcVerdict.Rejected.class,
                                    rejected -> assertThat(rejected.reason())
                                            .isEqualTo(JwtRejectionReason.ISSUER_MISMATCH)))
                    .verifyComplete();
        }

        @Test
        @DisplayName("when the signature was tampered with")
        void badSignature() {
            reject(CssFixtures.token("access-token-bad-signature"), CssFixtures.whileValid(),
                    JwtRejectionReason.SIGNATURE_INVALID);
        }

        @Test
        @DisplayName("when it is not a JWT at all")
        void malformed() {
            reject("not-a-jwt", CssFixtures.whileValid(), JwtRejectionReason.MALFORMED);
        }
    }

    /** A pod that trusts nobody accepts nothing — the issuer arriving in the token is not trust. */
    @Test
    @DisplayName("an untrusted issuer is refused before any key is fetched")
    void unknownIssuer() {
        SolidOidcTokenVerifier verifier = new SolidOidcTokenVerifier(issuer -> Optional.empty());
        StepVerifier.create(verifier.verify(CssFixtures.accessToken()))
                .assertNext(verdict -> assertThat(verdict)
                        .isInstanceOfSatisfying(SolidOidcVerdict.Rejected.class,
                                rejected -> assertThat(rejected.reason())
                                        .isEqualTo(JwtRejectionReason.ISSUER_UNTRUSTED)))
                .verifyComplete();
    }

    /** {@code iss} that is not an absolute URI names no issuer to ask, and says so as itself. */
    @Test
    @DisplayName("a token with no usable iss is rejected as invalid, not as untrusted")
    void unusableIssuer() {
        String token = CssFixtures.token("access-token-unusable-issuer");
        StepVerifier.create(new SolidOidcTokenVerifier(issuer -> Optional.empty()).verify(token))
                .assertNext(verdict -> assertThat(verdict)
                        .isInstanceOfSatisfying(SolidOidcVerdict.Rejected.class,
                                rejected -> assertThat(rejected.reason())
                                        .isEqualTo(JwtRejectionReason.ISSUER_INVALID)))
                .verifyComplete();
    }
}
