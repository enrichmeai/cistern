package com.enrichmeai.cistern.auth;

import org.slf4j.event.Level;

/**
 * Why a bearer JWT authenticated nobody (T4.0, #88).
 *
 * <p>An enum, because the ways a token can fail are a closed set the verifier enumerates, and
 * because a test that can only observe "anonymous" cannot tell an expired token from a forged
 * one — and so cannot tell whether the check it means to exercise ran at all. Each reason
 * carries the catalogue entry that describes it and the level it is logged at: an
 * unreachable issuer is an operational fault (WARN), a bearer that is not a JWT at all is
 * routine (DEBUG — the owner's token and service credentials pass through this resolver when
 * they were not recognised earlier), and the rest are worth an operator's attention when an
 * integration is being debugged (INFO).
 */
public enum JwtRejectionReason {

    /** Not three base64url segments, or a header that will not parse. */
    MALFORMED(AuthMessage.REASON_MALFORMED, Level.DEBUG),

    /** A JWS algorithm outside {@link JwtVerifier#ACCEPTED_ALGORITHMS} — HMAC, or none. */
    ALGORITHM_NOT_ACCEPTED(AuthMessage.REASON_ALGORITHM_NOT_ACCEPTED, Level.INFO),

    /** No published key matches the header, even after refreshing the key set. */
    KEY_UNKNOWN(AuthMessage.REASON_KEY_UNKNOWN, Level.INFO),

    /** The key set could not be fetched: the token may be fine, but cannot be checked. */
    KEYS_UNAVAILABLE(AuthMessage.REASON_KEYS_UNAVAILABLE, Level.WARN),

    /** A matching key was found and the signature does not verify against it. */
    SIGNATURE_INVALID(AuthMessage.REASON_SIGNATURE_INVALID, Level.INFO),

    ISSUER_MISMATCH(AuthMessage.REASON_ISSUER_MISMATCH, Level.INFO),
    AUDIENCE_MISMATCH(AuthMessage.REASON_AUDIENCE_MISMATCH, Level.INFO),
    EXPIRY_MISSING(AuthMessage.REASON_EXPIRY_MISSING, Level.INFO),
    EXPIRED(AuthMessage.REASON_EXPIRED, Level.INFO),
    NOT_YET_VALID(AuthMessage.REASON_NOT_YET_VALID, Level.INFO),

    /** The token verified, but the configured {@link WebIdMapping} found no WebID in it. */
    WEBID_MISSING(AuthMessage.REASON_WEBID_MISSING, Level.INFO),

    /** The mapping produced something that is not an absolute URI. */
    WEBID_INVALID(AuthMessage.REASON_WEBID_INVALID, Level.INFO),

    /**
     * A Solid-OIDC token with no {@code cnf.jkt}. It verified, but it is bound to no key,
     * so possession of it is the only thing holding it — which is what DPoP exists to stop.
     */
    CONFIRMATION_MISSING(AuthMessage.REASON_CONFIRMATION_MISSING, Level.INFO),

    /**
     * The token names an issuer this pod will not talk to. Distinct from
     * {@link #ISSUER_MISMATCH}, which is a verified token whose {@code iss} is not the one
     * its verifier was built for: separate constants because they fail at different points
     * and a test asserting one must not pass on the other.
     */
    ISSUER_UNTRUSTED(AuthMessage.REASON_ISSUER_UNTRUSTED, Level.INFO),

    /** {@code iss} is missing or is not an absolute URI, so no issuer can be asked. */
    ISSUER_INVALID(AuthMessage.REASON_ISSUER_INVALID, Level.INFO),

    /** An exception the verifier did not anticipate. Never a 500: the request is anonymous. */
    VERIFICATION_ERROR(AuthMessage.REASON_VERIFICATION_ERROR, Level.WARN);

    private final AuthMessage message;
    private final Level level;

    JwtRejectionReason(AuthMessage message, Level level) {
        this.message = message;
        this.level = level;
    }

    /** The detail text for this reason, with {@code args} substituted. */
    public String describe(Object... args) {
        return message.format(args);
    }

    /** The level a rejection for this reason is logged at. */
    public Level level() {
        return level;
    }
}
