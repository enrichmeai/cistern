package com.enrichmeai.cistern.auth;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

/**
 * The Solid-OIDC fixtures captured from the Community Solid Server 7.2.0 (see
 * {@code fixtures/css/README.md} and {@code capture.mjs}). Nothing here is constructed in the
 * JVM: the access token, the key set and the DPoP proofs are read from disk exactly as CSS
 * produced them, and the four negatives are the stated derivations the README lists.
 *
 * <p>CSS issues 600-second tokens and its lifetime is not settable from the CLI, so the valid
 * and expired cases are the <em>same</em> captured token judged at two clocks — {@link
 * #whileValid()} and {@link #afterExpiry()} — rather than a second capture that would have
 * rotted by the next build.
 */
final class CssFixtures {

    private static final String ROOT = "/fixtures/css/";

    /** The issuer CSS put in {@code iss} — note the trailing slash. */
    static final URI ISSUER = URI.create("http://localhost:3939/");

    /** The WebID the captured pod was provisioned with. */
    static final URI ALICE = URI.create("http://localhost:3939/alice/profile/card#me");

    /**
     * {@code OidcIssuer} for the captured issuer. The audience is the literal
     * {@link SolidOidcTokenVerifier#SOLID_AUDIENCE} CSS emits, not this pod's origin.
     */
    static final OidcIssuer TRUSTED = new OidcIssuer(
            ISSUER, Set.of(SolidOidcTokenVerifier.SOLID_AUDIENCE), Duration.ofSeconds(60));

    /** {@code jwks.json}: CSS's single ES256 signing key. */
    static JWKSet jwks() {
        return jwkSet("jwks.json");
    }

    /** {@code jwks-foreign.json}: the locally generated key the derived negatives are signed with. */
    static JWKSet jwksForeign() {
        return jwkSet("jwks-foreign.json");
    }

    /** A token file, e.g. {@code "access-token"} or {@code "access-token-wrong-key"}. */
    static String token(String name) {
        return text(name + ".jwt").trim();
    }

    /** The captured access token, verbatim. */
    static String accessToken() {
        return token("access-token");
    }

    static JWTClaimsSet claims(String name) {
        try {
            return SignedJWT.parse(token(name)).getJWTClaimsSet();
        } catch (ParseException e) {
            throw new IllegalStateException(name, e);
        }
    }

    /** A clock one second after the captured token's {@code iat}: it is live. */
    static Clock whileValid() {
        return at(claims("access-token").getIssueTime().toInstant().plusSeconds(1));
    }

    /**
     * A clock past the captured token's {@code exp} <em>and</em> past the skew the trusted
     * issuer allows, so the expiry is what rejects it rather than the tolerance.
     */
    static Clock afterExpiry() {
        return at(claims("access-token").getExpirationTime().toInstant()
                .plus(TRUSTED.clockSkew()).plusSeconds(1));
    }

    private static Clock at(Instant instant) {
        return Clock.fixed(instant, ZoneOffset.UTC);
    }

    static String text(String relative) {
        try (InputStream in = CssFixtures.class.getResourceAsStream(ROOT + relative)) {
            if (in == null) {
                throw new IllegalStateException("missing fixture " + relative);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static JWKSet jwkSet(String relative) {
        try {
            return JWKSet.parse(text(relative));
        } catch (ParseException e) {
            throw new IllegalStateException(relative, e);
        }
    }

    private CssFixtures() {
    }
}
