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
import java.time.Duration;
import java.util.Set;

/**
 * The fixtures captured from Keycloak 26.7.1 (see {@code fixtures/keycloak/README.md} and
 * {@code capture.sh}). Nothing here is constructed in the JVM: every token and key set is read
 * from disk exactly as the identity provider produced it.
 */
final class Fixtures {

    private static final String ROOT = "/fixtures/keycloak/";

    /** The realm's {@code iss}, verbatim from the tokens. */
    static final URI ISSUER = URI.create("http://localhost:8080/realms/cistern");

    /** The audience the {@code cistern-audience} mapper adds. */
    static final String AUDIENCE = "cistern";

    /** {@code OidcIssuer} for the fixture realm with the default one-minute skew. */
    static final OidcIssuer TRUSTED = new OidcIssuer(ISSUER, Set.of(AUDIENCE), Duration.ofSeconds(60));

    static final URI ALICE = URI.create("https://alice.example/profile/card#me");
    static final URI BOB = URI.create("https://bob.example/profile/card#me");
    static final URI VALUEDOCS_LEGAL = URI.create("https://valuedocs.co.in/apps/legal#id");
    static final URI VALUEDOCS_TAX = URI.create("https://valuedocs.co.in/apps/tax#id");

    /** {@code jwks.json}: key set 1, the key every token but {@code alice-rotated-key} is signed with. */
    static JWKSet jwks() {
        return jwkSet("jwks.json");
    }

    /** {@code jwks-rotated.json}: key sets 1 and 2 — what the issuer published after adding a key. */
    static JWKSet jwksRotated() {
        return jwkSet("jwks-rotated.json");
    }

    static String openidConfiguration() {
        return text("openid-configuration.json");
    }

    /** A token file, e.g. {@code "alice-valid"}. */
    static String token(String name) {
        return text("tokens/" + name + ".jwt").trim();
    }

    /** The token's claims, unverified — for asserting on what the fixture contains. */
    static SignedJWT parsed(String name) {
        try {
            return SignedJWT.parse(token(name));
        } catch (ParseException e) {
            throw new IllegalStateException(name, e);
        }
    }

    /** The token's claims, unverified — the fixture's own {@code exp}, {@code sub}, and so on. */
    static JWTClaimsSet claims(String name) {
        try {
            return parsed(name).getJWTClaimsSet();
        } catch (ParseException e) {
            throw new IllegalStateException(name, e);
        }
    }

    static String text(String relative) {
        try (InputStream in = Fixtures.class.getResourceAsStream(ROOT + relative)) {
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

    private Fixtures() {
    }
}
