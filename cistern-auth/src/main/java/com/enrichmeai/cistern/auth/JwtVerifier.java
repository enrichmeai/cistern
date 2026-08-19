package com.enrichmeai.cistern.auth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.factories.DefaultJWSVerifierFactory;
import com.nimbusds.jose.jwk.AsymmetricJWK;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKMatcher;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.text.ParseException;
import java.time.Clock;
import java.time.Instant;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import reactor.core.publisher.Mono;

/**
 * Decides whether a bearer JWT may be trusted (T4.0, #88): signature against the issuer's
 * published keys, then {@code iss}, {@code aud}, {@code exp} and {@code nbf}.
 *
 * <p>Nimbus does the parsing and the cryptography; the claim checks are written out here
 * rather than delegated to its {@code DefaultJWTClaimsVerifier} so that each failure has a
 * typed {@link JwtRejectionReason} — a test that can only see "anonymous" cannot tell whether
 * the check it meant to exercise ran — and so that the clock is the one this class was given.
 *
 * <p>Never signals an error. Every failure, including an issuer that cannot be reached, is a
 * {@link JwtVerdict.Rejected}; the request goes on as anonymous and WAC decides what anonymous
 * may do. That is the {@code PrincipalResolver} contract, and it is what keeps a public
 * resource readable during an issuer outage.
 */
public final class JwtVerifier {

    /**
     * Asymmetric signature families only. A JWKS publishes public keys; an HMAC algorithm
     * would ask us to treat one of them as a shared secret, which is the classic key-confusion
     * attack, and {@code none} is refused by {@code SignedJWT.parse} before we get here.
     */
    public static final Set<JWSAlgorithm> ACCEPTED_ALGORITHMS = acceptedAlgorithms();

    private final OidcIssuer issuer;
    private final JwksClient keys;
    private final Clock clock;
    private final DefaultJWSVerifierFactory verifiers = new DefaultJWSVerifierFactory();

    public JwtVerifier(OidcIssuer issuer, JwksClient keys, Clock clock) {
        this.issuer = Objects.requireNonNull(issuer, "issuer");
        this.keys = Objects.requireNonNull(keys, "keys");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** The verdict on {@code token}. Never empty, never an error. */
    public Mono<JwtVerdict> verify(String token) {
        Objects.requireNonNull(token, "token");
        return Mono.defer(() -> {
            SignedJWT jwt;
            try {
                jwt = SignedJWT.parse(token);
            } catch (ParseException e) {
                return rejected(JwtRejectionReason.MALFORMED, e.getMessage());
            }
            JWSHeader header = jwt.getHeader();
            if (!ACCEPTED_ALGORITHMS.contains(header.getAlgorithm())) {
                return rejected(JwtRejectionReason.ALGORITHM_NOT_ACCEPTED,
                        header.getAlgorithm(), ACCEPTED_ALGORITHMS);
            }
            return candidateKeys(header)
                    .map(candidates -> checkSignature(jwt, candidates)
                            .orElseGet(() -> checkClaims(jwt)))
                    // The one failure this class expects from outside itself. Anything else
                    // is a bug — but the contract is still "never an error", so it too becomes
                    // a verdict, at WARN, rather than a 500 for the caller.
                    .onErrorResume(JwksUnavailableException.class, e ->
                            rejected(JwtRejectionReason.KEYS_UNAVAILABLE, e.getMessage()))
                    .onErrorResume(e -> rejected(JwtRejectionReason.VERIFICATION_ERROR, e));
        });
    }

    /**
     * The published keys that could have signed this token, refreshing the set once if none
     * of the cached ones could — the shape of a key rotation.
     */
    private Mono<List<JWK>> candidateKeys(JWSHeader header) {
        JWKSelector selector = new JWKSelector(JWKMatcher.forJWSHeader(header));
        return keys.keys()
                .map(selector::select)
                .flatMap(matched -> matched.isEmpty()
                        ? keys.refresh().map(selector::select)
                        : Mono.just(matched));
    }

    /** Empty if the signature verifies against one of {@code candidates}. */
    private Optional<JwtVerdict> checkSignature(SignedJWT jwt, List<JWK> candidates) {
        String kid = jwt.getHeader().getKeyID();
        if (candidates.isEmpty()) {
            return Optional.of(JwtVerdict.Rejected.of(JwtRejectionReason.KEY_UNKNOWN, kid));
        }
        for (JWK candidate : candidates) {
            if (verifies(jwt, candidate)) {
                return Optional.empty();
            }
        }
        return Optional.of(JwtVerdict.Rejected.of(JwtRejectionReason.SIGNATURE_INVALID, kid));
    }

    private boolean verifies(SignedJWT jwt, JWK candidate) {
        if (!(candidate instanceof AsymmetricJWK asymmetric)) {
            return false;
        }
        try {
            JWSVerifier verifier = verifiers.createJWSVerifier(jwt.getHeader(), asymmetric.toPublicKey());
            return jwt.verify(verifier);
        } catch (JOSEException e) {
            // A key of the wrong type or size for the algorithm: not this key, try the next.
            return false;
        }
    }

    /**
     * {@code iss}, {@code aud}, {@code exp}, {@code nbf} — in that order, so the first failure
     * reported is the one an integrator most needs to hear about (the wrong issuer or audience
     * is a configuration mismatch; expiry is just time).
     */
    private JwtVerdict checkClaims(SignedJWT jwt) {
        JWTClaimsSet claims;
        try {
            claims = jwt.getJWTClaimsSet();
        } catch (ParseException e) {
            return JwtVerdict.Rejected.of(JwtRejectionReason.MALFORMED, e.getMessage());
        }
        String expectedIssuer = issuer.issuer().toString();
        if (!expectedIssuer.equals(claims.getIssuer())) {
            return JwtVerdict.Rejected.of(
                    JwtRejectionReason.ISSUER_MISMATCH, claims.getIssuer(), expectedIssuer);
        }
        List<String> audience = claims.getAudience();
        if (Collections.disjoint(audience, issuer.audiences())) {
            return JwtVerdict.Rejected.of(
                    JwtRejectionReason.AUDIENCE_MISMATCH, audience, issuer.audiences());
        }
        Instant now = clock.instant();
        Date expiry = claims.getExpirationTime();
        if (expiry == null) {
            return JwtVerdict.Rejected.of(JwtRejectionReason.EXPIRY_MISSING);
        }
        Instant expiresAt = expiry.toInstant();
        // Valid while now < exp + skew (RFC 7519 §4.1.4, with the tolerance it allows for).
        if (!now.isBefore(expiresAt.plus(issuer.clockSkew()))) {
            return JwtVerdict.Rejected.of(
                    JwtRejectionReason.EXPIRED, expiresAt, now, issuer.clockSkew());
        }
        Date notBefore = claims.getNotBeforeTime();
        if (notBefore != null) {
            Instant validFrom = notBefore.toInstant();
            // Valid once now >= nbf - skew (RFC 7519 §4.1.5).
            if (now.isBefore(validFrom.minus(issuer.clockSkew()))) {
                return JwtVerdict.Rejected.of(
                        JwtRejectionReason.NOT_YET_VALID, validFrom, now, issuer.clockSkew());
            }
        }
        return new JwtVerdict.Accepted(claims);
    }

    private static Mono<JwtVerdict> rejected(JwtRejectionReason reason, Object... args) {
        return Mono.just(JwtVerdict.Rejected.of(reason, args));
    }

    private static Set<JWSAlgorithm> acceptedAlgorithms() {
        Set<JWSAlgorithm> accepted = new HashSet<>();
        accepted.addAll(JWSAlgorithm.Family.RSA);
        accepted.addAll(JWSAlgorithm.Family.EC);
        return Set.copyOf(accepted);
    }
}
