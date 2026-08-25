package com.enrichmeai.cistern.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("A DPoP proof binds a request to the key its access token names (RFC 9449 §4.3)")
class DpopValidatorTest {

    private static final Duration WINDOW = Duration.ofSeconds(60);
    private static final URI TARGET = URI.create("http://localhost:3939/alice/private/note.ttl");
    private static final String METHOD = "GET";

    private static Clock atProofTime() {
        return Clock.fixed(CssFixtures.dpopIssuedAt(), ZoneOffset.UTC);
    }

    private static DpopValidator validator(Clock clock) {
        return new DpopValidator(WINDOW, new JtiReplayCache(WINDOW, clock), clock);
    }

    private static DpopRequest request() {
        return new DpopRequest(METHOD, TARGET, Optional.of(CssFixtures.accessToken()));
    }

    private static DpopRejectionReason reasonOf(DpopVerdict verdict) {
        assertThat(verdict).isInstanceOf(DpopVerdict.Rejected.class);
        return ((DpopVerdict.Rejected) verdict).reason();
    }

    @Test
    @DisplayName("the captured proof is accepted, and reports the key the token is bound to")
    void acceptsTheCapturedProof() {
        DpopVerdict verdict = validator(atProofTime()).validate(CssFixtures.dpopProof(), request());

        assertThat(verdict).isInstanceOf(DpopVerdict.Accepted.class);
        assertThat(((DpopVerdict.Accepted) verdict).thumbprint())
                .isEqualTo(CssFixtures.boundThumbprint());
    }

    @Nested
    @DisplayName("each numbered step rejects on its own reason")
    class Steps {

        @Test
        @DisplayName("step 1: more than one DPoP header")
        void repeatedHeader() {
            String proof = CssFixtures.dpopProof();
            assertThat(reasonOf(validator(atProofTime()).validate(List.of(proof, proof), request())))
                    .isEqualTo(DpopRejectionReason.HEADER_REPEATED);
        }

        @Test
        @DisplayName("step 1: no DPoP header at all")
        void noHeader() {
            assertThat(reasonOf(validator(atProofTime()).validate(List.of(), request())))
                    .isEqualTo(DpopRejectionReason.HEADER_REPEATED);
        }

        @Test
        @DisplayName("step 2: not a JWT")
        void malformed() {
            assertThat(reasonOf(validator(atProofTime()).validate("not-a-jwt", request())))
                    .isEqualTo(DpopRejectionReason.MALFORMED);
        }

        @Test
        @DisplayName("step 4: typ is not dpop+jwt")
        void wrongType() {
            assertThat(reasonOf(validator(atProofTime())
                    .validate(CssFixtures.dpop("wrong-typ"), request())))
                    .isEqualTo(DpopRejectionReason.TYP_UNEXPECTED);
        }

        @Test
        @DisplayName("step 6: the signature does not verify against the key it carries")
        void badSignature() {
            assertThat(reasonOf(validator(atProofTime())
                    .validate(CssFixtures.dpop("bad-signature"), request())))
                    .isEqualTo(DpopRejectionReason.SIGNATURE_INVALID);
        }

        @Test
        @DisplayName("step 7: a jwk carrying private key material is refused")
        void privateKeyInHeader() {
            DpopVerdict verdict = validator(atProofTime())
                    .validate(CssFixtures.dpop("private-jwk"), request());

            // The requirement is enforced, but by Nimbus rather than by us: JWSHeader.parse
            // refuses a non-public key in the jwk parameter, so the proof never reaches the
            // validator's own check and the reason is MALFORMED. Asserted on the detail so
            // this proves step 7 actually ran, rather than that something merely failed.
            assertThat(reasonOf(verdict)).isEqualTo(DpopRejectionReason.MALFORMED);
            assertThat(((DpopVerdict.Rejected) verdict).detail())
                    .containsIgnoringCase("non-public key");
        }

        @Test
        @DisplayName("step 8: htm is for another method")
        void wrongMethod() {
            assertThat(reasonOf(validator(atProofTime())
                    .validate(CssFixtures.dpop("wrong-htm"), request())))
                    .isEqualTo(DpopRejectionReason.HTM_MISMATCH);
        }

        @Test
        @DisplayName("step 9: htu is for another target")
        void wrongTarget() {
            assertThat(reasonOf(validator(atProofTime())
                    .validate(CssFixtures.dpop("wrong-htu"), request())))
                    .isEqualTo(DpopRejectionReason.HTU_MISMATCH);
        }

        @Test
        @DisplayName("step 9: the query string is ignored on both sides")
        void queryIgnored() {
            DpopRequest withQuery = new DpopRequest(
                    METHOD, URI.create(TARGET + "?v=2#frag"), Optional.of(CssFixtures.accessToken()));

            assertThat(validator(atProofTime()).validate(CssFixtures.dpopProof(), withQuery))
                    .isInstanceOf(DpopVerdict.Accepted.class);
        }

        @Test
        @DisplayName("step 11: an iat older than the window")
        void tooOld() {
            Clock late = Clock.fixed(CssFixtures.dpopIssuedAt().plus(WINDOW).plusSeconds(1), ZoneOffset.UTC);
            assertThat(reasonOf(validator(late).validate(CssFixtures.dpopProof(), request())))
                    .isEqualTo(DpopRejectionReason.IAT_OUTSIDE_WINDOW);
        }

        @Test
        @DisplayName("step 11: an iat further in the future than the window allows")
        void tooNew() {
            Clock early = Clock.fixed(CssFixtures.dpopIssuedAt().minus(WINDOW).minusSeconds(1), ZoneOffset.UTC);
            assertThat(reasonOf(validator(early).validate(CssFixtures.dpopProof(), request())))
                    .isEqualTo(DpopRejectionReason.IAT_OUTSIDE_WINDOW);
        }

        @Test
        @DisplayName("step 12: ath does not hash to the presented token")
        void wrongAth() {
            assertThat(reasonOf(validator(atProofTime())
                    .validate(CssFixtures.dpop("wrong-ath"), request())))
                    .isEqualTo(DpopRejectionReason.ATH_MISMATCH);
        }

        @Test
        @DisplayName("step 12: a token was presented but the proof carries no ath")
        void missingAth() {
            assertThat(reasonOf(validator(atProofTime())
                    .validate(CssFixtures.dpop("no-ath"), request())))
                    .isEqualTo(DpopRejectionReason.ATH_MISSING);
        }

        @Test
        @DisplayName("step 12: the proof key is not the key the token is bound to")
        void thumbprintMismatch() {
            // Correct in every other respect — htm, htu and ath all match — and signed by a
            // key the access token never named. The thumbprint is the only check left to fail,
            // so this cannot pass for the wrong reason.
            assertThat(reasonOf(validator(atProofTime())
                    .validate(CssFixtures.dpop("foreign-key"), request())))
                    .isEqualTo(DpopRejectionReason.THUMBPRINT_MISMATCH);
        }
    }

    @Nested
    @DisplayName("replay")
    class Replay {

        @Test
        @DisplayName("the same proof twice is refused the second time")
        void secondUseIsRefused() {
            DpopValidator validator = validator(atProofTime());

            assertThat(validator.validate(CssFixtures.dpopProof(), request()))
                    .isInstanceOf(DpopVerdict.Accepted.class);
            assertThat(reasonOf(validator.validate(CssFixtures.dpopProof(), request())))
                    .isEqualTo(DpopRejectionReason.JTI_REPLAYED);
        }

        @Test
        @DisplayName("a proof that fails the token binding does not burn its jti")
        void rejectedProofDoesNotConsumeItsJti() {
            Clock clock = atProofTime();
            JtiReplayCache cache = new JtiReplayCache(WINDOW, clock);
            DpopValidator validator = new DpopValidator(WINDOW, cache, clock);

            validator.validate(CssFixtures.dpop("wrong-ath"), request());

            assertThat(cache.size())
                    .describedAs("a bad request must not lock out the legitimate retry")
                    .isZero();
        }

        @Test
        @DisplayName("under concurrency exactly one of many identical proofs wins")
        void onlyOneConcurrentUseWins() throws Exception {
            int racers = 32;
            DpopValidator validator = validator(atProofTime());
            ExecutorService pool = Executors.newFixedThreadPool(racers);
            try {
                List<Callable<DpopVerdict>> attempts = IntStream.range(0, racers)
                        .<Callable<DpopVerdict>>mapToObj(i ->
                                () -> validator.validate(CssFixtures.dpopProof(), request()))
                        .toList();

                long accepted = pool.invokeAll(attempts).stream()
                        .map(DpopValidatorTest::get)
                        .filter(DpopVerdict.Accepted.class::isInstance)
                        .count();

                assertThat(accepted)
                        .describedAs("check-then-insert must be atomic, or a stolen proof works twice")
                        .isEqualTo(1);
            } finally {
                pool.shutdownNow();
            }
        }
    }

    private static DpopVerdict get(Future<DpopVerdict> future) {
        try {
            return future.get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
