package com.enrichmeai.cistern.auth;

import org.slf4j.event.Level;

/**
 * Why a DPoP proof did not bind a request to its access token (T4.2), one constant per
 * numbered step of RFC 9449 §4.3.
 *
 * <p>Enumerated for the same reason {@link JwtRejectionReason} is: a test that can only see
 * "rejected" cannot tell whether the check it meant to exercise ran, and with twelve steps
 * that is not a theoretical worry. Naming each step also keeps the validator honest about
 * which ones it actually performs — a step with no constant is a step nobody wrote.
 *
 * <p>Levels: a replayed {@code jti} and a thumbprint mismatch are the two that mean somebody
 * may be replaying a captured proof, so they are WARN. A full replay cache is an operational
 * fault (WARN) — the request is refused, and refusing is the safe direction. Everything else
 * is an integration being debugged (INFO), or routine malformity (DEBUG).
 */
public enum DpopRejectionReason {

    /** §4.3 step 1: more than one DPoP header field. */
    HEADER_REPEATED(AuthMessage.DPOP_HEADER_REPEATED, Level.INFO),

    /** §4.3 step 2: not a well-formed JWT. */
    MALFORMED(AuthMessage.DPOP_MALFORMED, Level.DEBUG),

    /** §4.3 step 3: a required claim of §4.2 is absent. */
    CLAIM_MISSING(AuthMessage.DPOP_CLAIM_MISSING, Level.INFO),

    /** §4.3 step 4: {@code typ} is not {@code dpop+jwt}. */
    TYP_UNEXPECTED(AuthMessage.DPOP_TYP_UNEXPECTED, Level.INFO),

    /** §4.3 step 5: {@code alg} is symmetric, {@code none}, or otherwise unacceptable. */
    ALGORITHM_NOT_ACCEPTED(AuthMessage.DPOP_ALG_NOT_ACCEPTED, Level.INFO),

    /** §4.3 step 6: the signature does not verify against the embedded {@code jwk}. */
    SIGNATURE_INVALID(AuthMessage.DPOP_SIGNATURE_INVALID, Level.INFO),

    /** §4.3 step 7: the embedded {@code jwk} carries private key material. */
    JWK_HAS_PRIVATE_KEY(AuthMessage.DPOP_JWK_HAS_PRIVATE_KEY, Level.WARN),

    /** §4.3 step 8: {@code htm} is not this request's method. */
    HTM_MISMATCH(AuthMessage.DPOP_HTM_MISMATCH, Level.INFO),

    /** §4.3 step 9: {@code htu} is not this request's target. */
    HTU_MISMATCH(AuthMessage.DPOP_HTU_MISMATCH, Level.INFO),

    /** §4.3 step 11: {@code iat} is outside the acceptance window. */
    IAT_OUTSIDE_WINDOW(AuthMessage.DPOP_IAT_OUTSIDE_WINDOW, Level.INFO),

    /** §4.3 step 11: this {@code jti} has already been presented inside the window. */
    JTI_REPLAYED(AuthMessage.DPOP_JTI_REPLAYED, Level.WARN),

    /** The replay cache is at its bound: uniqueness cannot be guaranteed, so refuse. */
    REPLAY_CACHE_FULL(AuthMessage.DPOP_REPLAY_CACHE_FULL, Level.WARN),

    /** §4.3 step 12: an access token was presented, but the proof carries no {@code ath}. */
    ATH_MISSING(AuthMessage.DPOP_ATH_MISSING, Level.INFO),

    /** §4.3 step 12: {@code ath} does not hash to the presented access token. */
    ATH_MISMATCH(AuthMessage.DPOP_ATH_MISMATCH, Level.WARN),

    /** §4.3 step 12: the proof key is not the key the access token is bound to. */
    THUMBPRINT_MISMATCH(AuthMessage.DPOP_THUMBPRINT_MISMATCH, Level.WARN);

    private final AuthMessage message;
    private final Level level;

    DpopRejectionReason(AuthMessage message, Level level) {
        this.message = message;
        this.level = level;
    }

    /** This reason with {@code args} substituted into its catalogue entry. */
    public String describe(Object... args) {
        return message.format(args);
    }

    /** The level this rejection is logged at. */
    public Level level() {
        return level;
    }
}
