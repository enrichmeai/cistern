package com.enrichmeai.cistern.webflux;

import com.enrichmeai.cistern.core.Representation;
import com.enrichmeai.cistern.core.ResourceIdentifier;
import com.enrichmeai.cistern.core.ResourceStore;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The OAuth protected resource metadata and its 401 challenge, over the real filter chain (T6.4).
 *
 * <p>Through {@code WebTestClient}, not by calling a handler, because {@code AuthorizationFilter}
 * owns half of each answer: it is the filter that decides the metadata path is public even under
 * enforcement, and the filter that writes the {@code resource_metadata} challenge onto a 401. A
 * handler-level test would see neither, which is exactly the split that shipped two bugs before.
 */
@SpringBootTest(properties = {
    "cistern.base-url=" + OAuthProtectedResourceHttpTest.BASE,
    "cistern.owner.web-id=" + OAuthProtectedResourceHttpTest.OWNER,
    "cistern.owner.token=" + OAuthProtectedResourceHttpTest.TOKEN,
    "cistern.auth.authorization-server=" + OAuthProtectedResourceHttpTest.AUTH_SERVER,
})
@AutoConfigureWebTestClient
class OAuthProtectedResourceHttpTest {

    static final String BASE = "http://localhost:3000";
    static final String OWNER = "https://acme-law.example/profile#firm";
    static final String TOKEN = "test-owner-token";
    static final String AUTH_SERVER = "http://localhost:8080/realms/cistern";
    private static final String METADATA_PATH = "/.well-known/oauth-protected-resource";
    private static final String METADATA_URL = BASE + METADATA_PATH;
    private static final String TURTLE = "text/turtle";

    @Autowired private WebTestClient client;
    @Autowired private ResourceStore store;

    @BeforeEach
    void seedRootAcl() {
        put("/.acl", """
                @prefix acl: <http://www.w3.org/ns/auth/acl#> .
                <#owner> a acl:Authorization ;
                    acl:agent <%s> ;
                    acl:accessTo <%s/> ; acl:default <%s/> ;
                    acl:mode acl:Read, acl:Write, acl:Append, acl:Control .
                """.formatted(OWNER, BASE, BASE));
    }

    private void put(String path, String turtle) {
        store.put(new ResourceIdentifier(URI.create(BASE + path)),
                        new Representation(TURTLE, turtle.getBytes(StandardCharsets.UTF_8)))
                .block();
    }

    // ---- the 401 challenge names the metadata (RFC 9728 §5.1, MCP authorization spec) --------

    @Test
    @DisplayName("an anonymous GET on a protected resource is 401 whose challenge carries resource_metadata")
    void anonymous401CarriesResourceMetadata() {
        put("/notes/hello", "<#a> <#b> \"c\" .");

        client.get().uri("/notes/hello").exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().value(HttpHeaders.WWW_AUTHENTICATE, challenge -> {
                    assertTrue(challenge.startsWith("Bearer "), challenge);
                    assertTrue(challenge.contains("resource_metadata=\"" + METADATA_URL + "\""), challenge);
                    assertTrue(challenge.contains("DPoP"), "the DPoP scheme is still advertised: " + challenge);
                });
    }

    // ---- the metadata document is public, even under enforcement -----------------------------

    @Test
    @DisplayName("the metadata document is served to anyone — a client reads it before it has a credential")
    void metadataIsPublicUnderEnforcement() {
        client.get().uri(METADATA_PATH).exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.resource").isEqualTo(BASE)
                .jsonPath("$.authorization_servers[0]").isEqualTo(AUTH_SERVER)
                .jsonPath("$.scopes_supported[0]").isEqualTo("webid")
                .jsonPath("$.bearer_methods_supported[0]").isEqualTo("header")
                .jsonPath("$.resource_documentation").exists();
    }

    @Test
    @DisplayName("HEAD and OPTIONS on the metadata are answered without a credential")
    void headAndOptions() {
        client.head().uri(METADATA_PATH).exchange().expectStatus().isOk();
        client.options().uri(METADATA_PATH).exchange()
                .expectStatus().isNoContent()
                .expectHeader().value(HttpHeaders.ALLOW, allow -> {
                    assertTrue(allow.contains("GET"), allow);
                    assertTrue(allow.contains("OPTIONS"), allow);
                });
    }

    // ---- a PUT to the metadata path is refused, not stored -----------------------------------

    @Test
    @DisplayName("a PUT to the metadata path is 405, and the path still serves the metadata afterwards")
    void putToMetadataPathRefused() {
        client.put().uri(METADATA_PATH)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                .header(HttpHeaders.CONTENT_TYPE, TURTLE)
                .bodyValue("<#a> <#b> \"c\" .")
                .exchange()
                .expectStatus().isEqualTo(org.springframework.http.HttpStatus.METHOD_NOT_ALLOWED)
                .expectHeader().value(HttpHeaders.ALLOW, allow -> assertTrue(allow.contains("GET"), allow));

        // Not stored: the route still answers with the generated document, not a pod resource.
        client.get().uri(METADATA_PATH).exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON);
    }
}
