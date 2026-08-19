package com.enrichmeai.cistern.auth;

import com.enrichmeai.cistern.core.Representation;
import com.enrichmeai.cistern.core.ResourceIdentifier;
import com.enrichmeai.cistern.core.ResourceStore;
import com.enrichmeai.cistern.webflux.auth.ChainedPrincipalResolver;
import com.enrichmeai.cistern.webflux.auth.PrincipalResolver;

import java.net.URI;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The T4.0 matrix through the real filter (#88): owner token, service credential and OIDC JWT
 * all in one chain, every request decided by the same WAC engine.
 *
 * <p>Tokens and key set are the ones Keycloak issued ({@code fixtures/keycloak}); the key set is
 * served from a loopback HTTP server, which is what {@code cistern.auth.oidc.jwks-uri} points
 * at, and the issuer configured is the fixture realm's {@code iss} verbatim.
 */
@SpringBootTest(properties = {
    "cistern.base-url=" + OidcAuthorizationHttpTest.BASE,
    "cistern.owner.web-id=" + OidcAuthorizationHttpTest.OWNER,
    "cistern.owner.token=" + OidcAuthorizationHttpTest.OWNER_TOKEN,
    "cistern.auth.oidc.issuer=http://localhost:8080/realms/cistern",
    "cistern.auth.oidc.audiences=cistern",
    "cistern.auth.service-principals[0].web-id=https://valuedocs.co.in/apps/legal#id",
    "cistern.auth.service-principals[0].credential-hash="
            + "sha256:af9f6ca9c55937463513e4cb25829d6eaa89ca74ed5699c0690f13469da4c481",
})
@AutoConfigureWebTestClient
class OidcAuthorizationHttpTest {

    static final String BASE = "http://localhost:3000";
    static final String OWNER = "https://acme-law.example/profile#firm";
    static final String OWNER_TOKEN = "owner-token-3f9a";
    private static final String LEGAL_SECRET = "legal-secret-0f3c8b";
    private static final String KEYS_PATH = "/realms/cistern/protocol/openid-connect/certs";
    private static final String TURTLE = "text/turtle";
    private static final String MATTER = "/matters/2026-114/brief";

    /** The issuer's current key set — both keys, as Keycloak published after the rotation. */
    private static final FixtureServer KEYS = new FixtureServer().serve(KEYS_PATH, Fixtures.text("jwks-rotated.json"));

    @DynamicPropertySource
    static void jwksUri(DynamicPropertyRegistry registry) {
        registry.add("cistern.auth.oidc.jwks-uri", () -> KEYS.uri(KEYS_PATH).toString());
    }

    @AfterAll
    static void stopKeys() {
        KEYS.close();
    }

    @Autowired private WebTestClient client;
    @Autowired private ResourceStore store;
    @Autowired private PrincipalResolver principalResolver;

    @BeforeEach
    void seed() {
        put("/.acl", """
                @prefix acl: <http://www.w3.org/ns/auth/acl#> .
                <#owner> a acl:Authorization ;
                    acl:agent <%s> ;
                    acl:accessTo <%s/> ;
                    acl:default <%s/> ;
                    acl:mode acl:Read, acl:Write, acl:Append, acl:Control .
                """.formatted(OWNER, BASE, BASE));
        put(MATTER, "<#b> <http://purl.org/dc/terms/title> \"Brief\" .");
        // Alice (a person, by JWT) and the legal application (by JWT or by service credential —
        // one WebID either way) may read the matter. Bob and the tax application are not named.
        put("/matters/2026-114/.acl", """
                @prefix acl: <http://www.w3.org/ns/auth/acl#> .
                <#owner> a acl:Authorization ;
                    acl:agent <%1$s> ;
                    acl:accessTo <%2$s/matters/2026-114/> ;
                    acl:default <%2$s/matters/2026-114/> ;
                    acl:mode acl:Read, acl:Write, acl:Append, acl:Control .
                <#readers> a acl:Authorization ;
                    acl:agent <%3$s>, <%4$s> ;
                    acl:accessTo <%2$s/matters/2026-114/> ;
                    acl:default <%2$s/matters/2026-114/> ;
                    acl:mode acl:Read .
                """.formatted(OWNER, BASE, Fixtures.ALICE, Fixtures.VALUEDOCS_LEGAL));
    }

    private void put(String path, String turtle) {
        store.put(new ResourceIdentifier(URI.create(BASE + path)),
                        new Representation(TURTLE, turtle.getBytes(StandardCharsets.UTF_8)))
                .block();
    }

    private WebTestClient.ResponseSpec getWith(String bearer, String path) {
        return client.get().uri(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer).exchange();
    }

    private WebTestClient.ResponseSpec getWithToken(String name) {
        return getWith(Fixtures.token(name), MATTER);
    }

    @Test
    @DisplayName("the context wires the chain: local credential, service credentials, OIDC, anonymous")
    void chainIsWired() {
        ChainedPrincipalResolver chain = assertInstanceOf(ChainedPrincipalResolver.class, principalResolver);
        assertEquals(4, chain.members().size(), chain.members().toString());
        assertInstanceOf(OidcJwtPrincipalResolver.class, chain.members().get(2));
    }

    // ---- valid JWT → principal ------------------------------------------------------------

    @Test
    @DisplayName("valid JWT: alice reads the matter she is granted; WAC-Allow reports her modes")
    void validJwtIsAlice() {
        getWithToken("alice-valid").expectStatus().isOk()
                .expectHeader().value("WAC-Allow", value -> assertTrue(value.contains("user=\"read\""), value));
    }

    @Test
    @DisplayName("valid JWT under the rotated key: also alice")
    void rotatedKeyJwtIsAlice() {
        getWithToken("alice-rotated-key").expectStatus().isOk();
    }

    @Test
    @DisplayName("valid JWT, no grant: bob is authenticated and refused — 403, not 401")
    void validJwtWithoutGrantIsForbidden() {
        getWithToken("bob-valid").expectStatus().isForbidden();
    }

    @Test
    @DisplayName("alice's grant is Read: she cannot write")
    void aliceCannotWrite() {
        client.put().uri(MATTER)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + Fixtures.token("alice-valid"))
                .header(HttpHeaders.CONTENT_TYPE, TURTLE)
                .bodyValue("<#x> <#y> \"z\" .")
                .exchange().expectStatus().isForbidden();
    }

    // ---- expired / wrong-aud / bad-sig → anonymous → 401 --------------------------------------

    @Test
    @DisplayName("expired JWT: anonymous, so 401 with a challenge")
    void expiredIsUnauthorized() {
        getWithToken("alice-expired").expectStatus().isUnauthorized()
                .expectHeader().exists(HttpHeaders.WWW_AUTHENTICATE);
    }

    @Test
    @DisplayName("wrong audience: anonymous → 401")
    void wrongAudienceIsUnauthorized() {
        getWithToken("alice-wrong-audience").expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("bad signature: anonymous → 401")
    void badSignatureIsUnauthorized() {
        getWithToken("alice-bad-signature").expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("no credential at all: 401")
    void anonymousIsUnauthorized() {
        client.get().uri(MATTER).exchange().expectStatus().isUnauthorized();
    }

    // ---- the other members of the chain, unchanged ------------------------------------------

    @Test
    @DisplayName("the owner token still works, unchanged")
    void ownerTokenStillWorks() {
        getWith(OWNER_TOKEN, MATTER).expectStatus().isOk();
        put("/private/ledger", "<#l> <#p> \"o\" .");
        getWith(OWNER_TOKEN, "/private/ledger").expectStatus().isOk();
        getWith(Fixtures.token("alice-valid"), "/private/ledger").expectStatus().isForbidden();
    }

    @Test
    @DisplayName("the legal application, by service credential: its own WebID, its grant honoured")
    void serviceCredentialIsTheLegalApp() {
        getWith(LEGAL_SECRET, MATTER).expectStatus().isOk();
    }

    @Test
    @DisplayName("the legal application, by OIDC client-credentials JWT: the same WebID, the same grant")
    void legalJwtIsTheSamePrincipal() {
        getWithToken("valuedocs-legal-valid").expectStatus().isOk();
    }

    @Test
    @DisplayName("the tax application's JWT is a different WebID: legal's grant is not tax's")
    void taxJwtIsForbidden() {
        getWithToken("valuedocs-tax-valid").expectStatus().isForbidden();
    }
}
