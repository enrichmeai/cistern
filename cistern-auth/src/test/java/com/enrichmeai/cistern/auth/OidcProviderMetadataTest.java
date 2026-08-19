package com.enrichmeai.cistern.auth;

import java.net.URI;
import java.time.Duration;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Discovery, against the document Keycloak served verbatim (T4.0). */
class OidcProviderMetadataTest {

    private static final URI FROM = URI.create("http://localhost:8080/realms/cistern/.well-known/openid-configuration");

    @Test
    @DisplayName("jwks_uri is read from the real discovery document")
    void parsesJwksUri() {
        assertEquals(URI.create("http://localhost:8080/realms/cistern/protocol/openid-connect/certs"),
                OidcProviderMetadata.parse(Fixtures.openidConfiguration(), FROM).jwksUri());
    }

    @Test
    @DisplayName("the discovery URL is the issuer plus the well-known path, whatever the issuer's trailing slash")
    void discoveryUrl() {
        assertEquals(FROM, new OidcIssuer(Fixtures.ISSUER, Set.of("cistern"), Duration.ZERO).discoveryDocument());
        assertEquals(FROM, new OidcIssuer(URI.create(Fixtures.ISSUER + "/"), Set.of("cistern"), Duration.ZERO)
                .discoveryDocument());
    }

    @Test
    @DisplayName("a document without jwks_uri, or that is not JSON, is JwksUnavailableException")
    void unusableDocuments() {
        assertThrows(JwksUnavailableException.class, () -> OidcProviderMetadata.parse("{\"issuer\": \"x\"}", FROM));
        assertThrows(JwksUnavailableException.class, () -> OidcProviderMetadata.parse("<html/>", FROM));
    }
}
