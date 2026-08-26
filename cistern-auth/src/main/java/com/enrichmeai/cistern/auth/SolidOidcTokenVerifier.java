package com.enrichmeai.cistern.auth;

import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.net.URI;
import java.net.URISyntaxException;
import java.text.ParseException;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Verifies a Solid-OIDC access token presented as {@code Authorization: DPoP <token>} (T4.1).
 *
 * <p>Three things make this different from the plain bearer path ({@link JwtVerifier} under
 * {@link OidcJwtPrincipalResolver}), and all three were settled by capturing a token from a
 * real Solid identity provider rather than from the specification — which, checked at
 * §9.2/§9.3/§8.1.1, states requirements for the <em>authorization</em> server and leaves
 * resource-server validation undefined. The fixtures and their provenance are in
 * {@code src/test/resources/fixtures/css/}.
 *
 * <ol>
 *   <li><strong>The issuer arrives in the token.</strong> A pod cannot enumerate the identity
 *       providers of the world, so the key set is fetched from whichever issuer the token
 *       names, through {@link Issuers}. That is safe only because the WebID is later held
 *       against the same issuer (T4.3) — <em>until that check exists, an accepted verdict here
 *       proves the issuer signed the token, not that the WebID consented to that issuer.</em>
 *   <li><strong>{@code aud} is the literal {@code "solid"}</strong>, not this server's origin.
 *       Checking the audience against our own URL, which the bearer path does because its
 *       realm has an audience mapper, rejects every real Solid-OIDC token.
 *   <li><strong>{@code client_id} rides on the access token</strong>; {@code azp} does not
 *       (Solid-OIDC mandates it on the ID token only). That is what makes the client half of
 *       {@link com.enrichmeai.cistern.core.Agent} knowable here rather than a later refactor.
 * </ol>
 *
 * <p>Yields a {@link SolidOidcIdentity}, never an authenticated agent: the DPoP proof (T4.2)
 * and the WebID's issuer check (T4.3) are both still outstanding, and each is load-bearing.
 */
public final class SolidOidcTokenVerifier {

    /** Solid-OIDC §5: the claim naming the WebID on the access token. */
    static final String WEBID_CLAIM = "webid";

    /** RFC 9068 §2.2 / the capture: the client identifier, present on a real CSS access token. */
    static final String CLIENT_ID_CLAIM = "client_id";

    /** RFC 9449 §6.1: {@code cnf} holds {@code jkt}, the DPoP key thumbprint. */
    static final String CONFIRMATION_CLAIM = "cnf";
    static final String THUMBPRINT_KEY = "jkt";

    /**
     * The audience a Solid-OIDC access token carries. A fixed literal, not a URL: CSS 7.2.0
     * emits {@code "aud": "solid"} regardless of which pod the token will be presented to.
     */
    public static final String SOLID_AUDIENCE = "solid";

    /** Supplies the verifier for an issuer named by a token, key sets cached per issuer. */
    @FunctionalInterface
    public interface Issuers {

        /** The verifier for {@code issuer}, or empty if this pod will not talk to it. */
        Optional<JwtVerifier> verifierFor(URI issuer);
    }

    private final Issuers issuers;

    public SolidOidcTokenVerifier(Issuers issuers) {
        this.issuers = Objects.requireNonNull(issuers, "issuers");
    }

    /** The verdict on {@code token}. Never empty, never an error. */
    public Mono<SolidOidcVerdict> verify(String token) {
        Objects.requireNonNull(token, "token");
        return Mono.defer(() -> {
            URI issuer;
            try {
                issuer = declaredIssuer(SignedJWT.parse(token).getJWTClaimsSet());
            } catch (ParseException e) {
                return rejected(JwtRejectionReason.MALFORMED, e.getMessage());
            } catch (URISyntaxException e) {
                return rejected(JwtRejectionReason.ISSUER_INVALID, e.getInput());
            }
            if (issuer == null) {
                return rejected(JwtRejectionReason.ISSUER_INVALID, (Object) null);
            }
            // The claims read above are unverified — they only say which issuer to ask. The
            // verifier that issuer supplies is what decides whether any of them may be trusted.
            // Asking is a blocking call (the fetch policy resolves DNS before trusting the
            // issuer's address), so it runs on boundedElastic, never the event loop.
            return Mono.fromCallable(() -> issuers.verifierFor(issuer))
                    .subscribeOn(Schedulers.boundedElastic())
                    .flatMap(found -> found
                            .map(verifier -> verifier.verify(token).map(this::identify))
                            .orElseGet(() -> rejected(JwtRejectionReason.ISSUER_UNTRUSTED, issuer)));
        });
    }

    private SolidOidcVerdict identify(JwtVerdict verdict) {
        if (verdict instanceof JwtVerdict.Rejected rejected) {
            return SolidOidcVerdict.Rejected.of(rejected);
        }
        JWTClaimsSet claims = ((JwtVerdict.Accepted) verdict).claims();
        URI webId;
        try {
            webId = absoluteUri(stringClaim(claims, WEBID_CLAIM));
        } catch (URISyntaxException e) {
            return SolidOidcVerdict.Rejected.of(JwtRejectionReason.WEBID_INVALID, e.getInput());
        }
        if (webId == null) {
            return SolidOidcVerdict.Rejected.of(JwtRejectionReason.WEBID_MISSING,
                    AuthMessage.WEBID_CLAIM_ABSENT.format(WEBID_CLAIM));
        }
        String thumbprint = thumbprint(claims);
        if (thumbprint == null || thumbprint.isBlank()) {
            return SolidOidcVerdict.Rejected.of(JwtRejectionReason.CONFIRMATION_MISSING);
        }
        URI issuer;
        try {
            issuer = declaredIssuer(claims);
        } catch (URISyntaxException e) {
            return SolidOidcVerdict.Rejected.of(JwtRejectionReason.ISSUER_INVALID, e.getInput());
        }
        return new SolidOidcVerdict.Accepted(
                new SolidOidcIdentity(webId, client(claims), issuer, thumbprint));
    }

    /**
     * The {@code client_id} claim when it is an absolute URI.
     *
     * <p>Empty when the claim is absent, and equally when it is present but opaque — a
     * client-credentials grant puts its own credential id here, which names no client anything
     * downstream could dereference or match a policy against. Treating that as "no client" is
     * the reason {@code Agent.client()} is an {@code Optional<URI>} and not a {@code String}.
     */
    private static Optional<URI> client(JWTClaimsSet claims) {
        try {
            return Optional.ofNullable(absoluteUri(stringClaim(claims, CLIENT_ID_CLAIM)));
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
    }

    private static String thumbprint(JWTClaimsSet claims) {
        Object confirmation = claims.getClaim(CONFIRMATION_CLAIM);
        if (!(confirmation instanceof Map<?, ?> map)) {
            return null;
        }
        Object jkt = map.get(THUMBPRINT_KEY);
        return jkt instanceof String value ? value : null;
    }

    private static URI declaredIssuer(JWTClaimsSet claims) throws URISyntaxException {
        return absoluteUri(claims.getIssuer());
    }

    private static String stringClaim(JWTClaimsSet claims, String name) {
        Object value = claims.getClaim(name);
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    /** @throws URISyntaxException if {@code value} is neither null nor an absolute URI */
    private static URI absoluteUri(String value) throws URISyntaxException {
        if (value == null) {
            return null;
        }
        URI uri = new URI(value);
        if (!uri.isAbsolute()) {
            throw new URISyntaxException(value, AuthMessage.REASON_WEBID_INVALID.format(value));
        }
        return uri;
    }

    private static Mono<SolidOidcVerdict> rejected(JwtRejectionReason reason, Object... args) {
        return Mono.just(SolidOidcVerdict.Rejected.of(reason, args));
    }
}
