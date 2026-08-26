package com.enrichmeai.cistern.auth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.factories.DefaultJWSVerifierFactory;
import com.nimbusds.jose.jwk.AsymmetricJWK;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Objects;

/**
 * Checks that a DPoP proof binds a request to the key its access token names (T4.2), following
 * the twelve numbered steps of RFC 9449 §4.3 in order.
 *
 * <p>RFC 9449 enumerates the resource-server checks in full, so this is written against its
 * text step by step, each naming the {@link DpopRejectionReason} it fails with, with the
 * captured fixtures used to confirm the shape a real client actually sends.
 *
 * <p>Two steps are deliberately absent. Step 10 (server-provided {@code nonce}) applies only
 * once this server issues {@code DPoP-Nonce} challenges, which it does not; if that arrives,
 * it is a step here and a constant in the reason enum. Step 3's "all required claims" is not a
 * separate pass but the {@link DpopRejectionReason#CLAIM_MISSING} each later step raises when
 * the claim it needs is absent, which reports the missing claim by name rather than a blanket
 * failure.
 *
 * <p><strong>A valid proof does not authenticate a request.</strong> It proves the caller
 * holds the key the token is bound to; whether the token's WebID trusts its issuer is T4.3,
 * and nothing may build an {@code Agent} before both hold.
 */
public final class DpopValidator {

    /** RFC 9449 §4.2: the proof's {@code typ}. */
    public static final JOSEObjectType DPOP_TYPE = new JOSEObjectType("dpop+jwt");

    /** Asymmetric signatures only (§4.3 step 5); {@code none} never parses as a SignedJWT. */
    public static final java.util.Set<JWSAlgorithm> ACCEPTED_ALGORITHMS = JwtVerifier.ACCEPTED_ALGORITHMS;

    static final String CLAIM_HTM = "htm";
    static final String CLAIM_HTU = "htu";
    static final String CLAIM_ATH = "ath";
    static final String CLAIM_JTI = "jti";
    static final String CLAIM_IAT = "iat";

    private final Duration acceptanceWindow;
    private final JtiReplayCache replayCache;
    private final Clock clock;
    private final DefaultJWSVerifierFactory verifiers = new DefaultJWSVerifierFactory();

    /**
     * @param acceptanceWindow how far either side of now an {@code iat} may fall (§4.3 step 11;
     *                         the RFC allows a proof slightly in the future for clock offset)
     * @param replayCache      remembers jtis for at least {@code acceptanceWindow}
     */
    public DpopValidator(Duration acceptanceWindow, JtiReplayCache replayCache, Clock clock) {
        this.acceptanceWindow = Objects.requireNonNull(acceptanceWindow, "acceptanceWindow");
        this.replayCache = Objects.requireNonNull(replayCache, "replayCache");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * The verdict on the proofs a request carried.
     *
     * @param proofs the {@code DPoP} header values, in order — §4.3 step 1 requires exactly one
     */
    public DpopVerdict validate(List<String> proofs, DpopRequest request) {
        Objects.requireNonNull(proofs, "proofs");
        Objects.requireNonNull(request, "request");
        if (proofs.size() != 1) {
            return DpopVerdict.Rejected.of(DpopRejectionReason.HEADER_REPEATED, proofs.size());
        }
        return validate(proofs.get(0), request);
    }

    /** The verdict on a single proof. */
    public DpopVerdict validate(String proof, DpopRequest request) {
        SignedJWT jwt;
        JWTClaimsSet claims;
        try {                                                        // step 2
            jwt = SignedJWT.parse(proof);
            claims = jwt.getJWTClaimsSet();
        } catch (ParseException e) {
            return DpopVerdict.Rejected.of(DpopRejectionReason.MALFORMED, e.getMessage());
        }
        JWSHeader header = jwt.getHeader();

        if (!DPOP_TYPE.equals(header.getType())) {                   // step 4
            return DpopVerdict.Rejected.of(DpopRejectionReason.TYP_UNEXPECTED, header.getType());
        }
        if (!ACCEPTED_ALGORITHMS.contains(header.getAlgorithm())) {  // step 5
            return DpopVerdict.Rejected.of(
                    DpopRejectionReason.ALGORITHM_NOT_ACCEPTED, header.getAlgorithm(), ACCEPTED_ALGORITHMS);
        }
        JWK key = header.getJWK();
        if (key == null) {
            return DpopVerdict.Rejected.of(DpopRejectionReason.CLAIM_MISSING, "jwk");
        }
        // Step 7. Unreachable through Nimbus, which refuses a non-public key in the jwk header
        // during JWSHeader.parse — so a proof carrying private material fails as MALFORMED
        // above, and DpopValidatorTest asserts on that detail so the requirement stays tested.
        // Kept because the step is normative and this class should not depend on a parser
        // detail for a security property: swap the parser and the check is already here.
        if (key.isPrivate()) {
            return DpopVerdict.Rejected.of(DpopRejectionReason.JWK_HAS_PRIVATE_KEY);
        }
        if (!verifies(jwt, key)) {                                   // step 6
            return DpopVerdict.Rejected.of(DpopRejectionReason.SIGNATURE_INVALID);
        }

        String htm = stringClaim(claims, CLAIM_HTM);                 // step 8
        if (htm == null) {
            return DpopVerdict.Rejected.of(DpopRejectionReason.CLAIM_MISSING, CLAIM_HTM);
        }
        if (!htm.equals(request.method())) {
            return DpopVerdict.Rejected.of(DpopRejectionReason.HTM_MISMATCH, htm, request.method());
        }

        String htu = stringClaim(claims, CLAIM_HTU);                 // step 9
        if (htu == null) {
            return DpopVerdict.Rejected.of(DpopRejectionReason.CLAIM_MISSING, CLAIM_HTU);
        }
        if (!sameTarget(htu, request.target())) {
            return DpopVerdict.Rejected.of(DpopRejectionReason.HTU_MISMATCH, htu, request.target());
        }

        Date issuedAt = claims.getIssueTime();                       // step 11
        if (issuedAt == null) {
            return DpopVerdict.Rejected.of(DpopRejectionReason.CLAIM_MISSING, CLAIM_IAT);
        }
        Instant created = issuedAt.toInstant();
        Instant now = clock.instant();
        if (created.isBefore(now.minus(acceptanceWindow)) || created.isAfter(now.plus(acceptanceWindow))) {
            return DpopVerdict.Rejected.of(
                    DpopRejectionReason.IAT_OUTSIDE_WINDOW, created, now, acceptanceWindow);
        }

        String thumbprint;
        try {
            thumbprint = key.computeThumbprint().toString();         // RFC 7638
        } catch (JOSEException e) {
            return DpopVerdict.Rejected.of(DpopRejectionReason.SIGNATURE_INVALID);
        }

        // Step 12 before the replay claim: a proof that fails the token binding should not
        // consume its jti, or one bad request would lock out the legitimate retry.
        if (request.accessToken().isPresent()) {
            String token = request.accessToken().get();
            String ath = stringClaim(claims, CLAIM_ATH);
            if (ath == null) {
                return DpopVerdict.Rejected.of(DpopRejectionReason.ATH_MISSING);
            }
            if (!MessageDigest.isEqual(ath.getBytes(StandardCharsets.US_ASCII),
                    hashOf(token).getBytes(StandardCharsets.US_ASCII))) {
                return DpopVerdict.Rejected.of(DpopRejectionReason.ATH_MISMATCH);
            }
            String bound = boundThumbprint(token);
            if (bound == null) {
                return DpopVerdict.Rejected.of(DpopRejectionReason.THUMBPRINT_MISMATCH, thumbprint, "nothing");
            }
            if (!MessageDigest.isEqual(bound.getBytes(StandardCharsets.US_ASCII),
                    thumbprint.getBytes(StandardCharsets.US_ASCII))) {
                return DpopVerdict.Rejected.of(DpopRejectionReason.THUMBPRINT_MISMATCH, thumbprint, bound);
            }
        }

        String jti = stringClaim(claims, CLAIM_JTI);                 // step 11, replay half
        if (jti == null) {
            return DpopVerdict.Rejected.of(DpopRejectionReason.CLAIM_MISSING, CLAIM_JTI);
        }
        return switch (replayCache.claim(jti)) {
            case REPLAYED -> DpopVerdict.Rejected.of(DpopRejectionReason.JTI_REPLAYED, jti);
            case FULL -> DpopVerdict.Rejected.of(
                    DpopRejectionReason.REPLAY_CACHE_FULL, JtiReplayCache.DEFAULT_MAXIMUM_ENTRIES);
            case FRESH -> new DpopVerdict.Accepted(thumbprint);
        };
    }

    /** RFC 9449 §4.2: {@code ath} is base64url(SHA-256(ASCII(access token))). */
    static String hashOf(String accessToken) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(accessToken.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);   // SHA-256 is mandated by the platform
        }
    }

    /** The {@code cnf.jkt} of an access token, or null if it carries none. */
    private static String boundThumbprint(String accessToken) {
        try {
            Object confirmation = SignedJWT.parse(accessToken).getJWTClaimsSet()
                    .getClaim(SolidOidcTokenVerifier.CONFIRMATION_CLAIM);
            if (confirmation instanceof java.util.Map<?, ?> map
                    && map.get(SolidOidcTokenVerifier.THUMBPRINT_KEY) instanceof String jkt) {
                return jkt;
            }
            return null;
        } catch (ParseException e) {
            return null;
        }
    }

    /**
     * §4.3 step 9 compares {@code htu} to the target "ignoring any query and fragment parts".
     * {@link DpopRequest} has already stripped those from its side; this strips the claim's,
     * since a client may sign the full URL it dialled.
     */
    private static boolean sameTarget(String htu, URI target) {
        try {
            return DpopRequest.withoutQueryOrFragment(new URI(htu)).equals(target);
        } catch (java.net.URISyntaxException e) {
            return false;
        }
    }

    private boolean verifies(SignedJWT jwt, JWK key) {
        if (!(key instanceof AsymmetricJWK asymmetric)) {
            return false;
        }
        try {
            JWSVerifier verifier = verifiers.createJWSVerifier(jwt.getHeader(), asymmetric.toPublicKey());
            return jwt.verify(verifier);
        } catch (JOSEException e) {
            return false;
        }
    }

    private static String stringClaim(JWTClaimsSet claims, String name) {
        Object value = claims.getClaim(name);
        return value instanceof String text && !text.isBlank() ? text : null;
    }
}
