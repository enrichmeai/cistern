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
 * The delegation fixtures captured from Keycloak 26.7.1 (see
 * {@code fixtures/keycloak-delegation/README.md} and {@code capture.sh}): tokens whose client
 * identifier is an absolute URI, which is what the (person, client) principal of T6.5 needs and
 * what none of the T4.0 fixtures carry. Nothing here is constructed in the JVM: every token and
 * the key set are read from disk exactly as the identity provider produced them.
 */
final class DelegationFixtures {

    private static final String ROOT = "/fixtures/keycloak-delegation/";

    /** The realm's {@code iss}, verbatim from the tokens. */
    static final URI ISSUER = URI.create("http://localhost:18092/realms/cistern-delegation");

    /** The audience the {@code cistern-audience} mapper adds. */
    static final String AUDIENCE = "cistern";

    /** {@code OidcIssuer} for the fixture realm with the default one-minute skew. */
    static final OidcIssuer TRUSTED = new OidcIssuer(ISSUER, Set.of(AUDIENCE), Duration.ofSeconds(60));

    static final URI ALICE = URI.create("https://alice.example/profile/card#me");

    /** The client alice's {@code alice-via-claude} token was issued to; also that client's own WebID. */
    static final URI CLAUDE = URI.create("https://agents.example/claude#id");

    /** A second client, so "via claude" and "via other" can be told apart. */
    static final URI OTHER = URI.create("https://agents.example/other#id");

    /** {@code jwks.json}: the realm's one RS256 signing key. */
    static JWKSet jwks() {
        try {
            return JWKSet.parse(text("jwks.json"));
        } catch (ParseException e) {
            throw new IllegalStateException("jwks.json", e);
        }
    }

    /** A token file, e.g. {@code "alice-via-claude"}. */
    static String token(String name) {
        return text("tokens/" + name + ".jwt").trim();
    }

    /** The token's claims, unverified — for asserting on what the fixture contains. */
    static JWTClaimsSet claims(String name) {
        try {
            return SignedJWT.parse(token(name)).getJWTClaimsSet();
        } catch (ParseException e) {
            throw new IllegalStateException(name, e);
        }
    }

    static String text(String relative) {
        try (InputStream in = DelegationFixtures.class.getResourceAsStream(ROOT + relative)) {
            if (in == null) {
                throw new IllegalStateException("missing fixture " + relative);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private DelegationFixtures() {
    }
}
