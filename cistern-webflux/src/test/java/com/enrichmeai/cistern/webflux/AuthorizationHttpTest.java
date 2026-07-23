package com.enrichmeai.cistern.webflux;

import com.enrichmeai.cistern.core.Representation;
import com.enrichmeai.cistern.core.ResourceIdentifier;
import com.enrichmeai.cistern.core.ResourceStore;

import java.net.URI;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * WAC enforcement over HTTP (T5.3).
 *
 * <p>The status codes come from the conformance harness's {@code protected-operation}
 * assertions, which distinguish the two refusals everywhere: an unauthenticated request is
 * <strong>401</strong>, an authenticated one that is not permitted is <strong>403</strong>.
 *
 * <p>The headline assertion is {@link #anonymousCannotDelete()}. Before this ticket an
 * anonymous {@code DELETE} returned 204 and removed the resource — the defect behind ADR 0001
 * and the reason nothing could be deployed.
 */
@SpringBootTest(properties = {
    "cistern.base-url=" + AuthorizationHttpTest.BASE,
    "cistern.owner.web-id=" + AuthorizationHttpTest.OWNER,
    "cistern.owner.token=" + AuthorizationHttpTest.TOKEN,
})
@AutoConfigureWebTestClient
class AuthorizationHttpTest {

    static final String BASE = "http://localhost:3000";
    static final String OWNER = "https://alice.example/profile/card#me";
    static final String TOKEN = "test-token";
    private static final String BEARER = "Bearer " + TOKEN;
    private static final String TURTLE = "text/turtle";

    @Autowired private WebTestClient client;
    @Autowired private ResourceStore store;

    @BeforeEach
    void seedRootAcl() {
        // The seeder runs as an ApplicationRunner, which @SpringBootTest does not execute;
        // written here so the fixture matches what a real boot produces.
        put("/.acl", """
                @prefix acl: <http://www.w3.org/ns/auth/acl#> .
                <#owner> a acl:Authorization ;
                    acl:agent <%s> ;
                    acl:accessTo <%s/> ;
                    acl:default <%s/> ;
                    acl:mode acl:Read, acl:Write, acl:Append, acl:Control .
                """.formatted(OWNER, BASE, BASE));
    }

    /** Write straight to the store, bypassing HTTP — fixtures must not depend on enforcement. */
    private void put(String path, String turtle) {
        store.put(new ResourceIdentifier(URI.create(BASE + path)),
                        new Representation(TURTLE, turtle.getBytes(StandardCharsets.UTF_8)))
                .block();
    }

    private WebTestClient.RequestHeadersSpec<?> asOwner(WebTestClient.RequestHeadersSpec<?> spec) {
        return spec.header(HttpHeaders.AUTHORIZATION, BEARER);
    }

    // ---- the defect this ticket exists to fix -----------------------------------------

    @Test
    @DisplayName("an anonymous DELETE is refused — it used to return 204 and destroy the resource")
    void anonymousCannotDelete() {
        put("/notes/hello", "<#a> <#b> \"c\" .");

        client.delete().uri("/notes/hello").exchange().expectStatus().isUnauthorized();

        // And the resource is still there.
        asOwner(client.get().uri("/notes/hello")).exchange().expectStatus().isOk();
    }

    @Test
    @DisplayName("an anonymous write is refused")
    void anonymousCannotWrite() {
        client.put().uri("/notes/evil").header(HttpHeaders.CONTENT_TYPE, TURTLE)
                .bodyValue("<#a> <#b> \"c\" .")
                .exchange().expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("an anonymous read of a private resource is refused")
    void anonymousCannotRead() {
        put("/notes/hello", "<#a> <#b> \"c\" .");

        client.get().uri("/notes/hello").exchange().expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("a 401 carries WWW-Authenticate, so a client knows to authenticate")
    void unauthenticatedIsChallenged() {
        client.get().uri("/notes/hello").exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().exists(HttpHeaders.WWW_AUTHENTICATE);
    }

    // ---- the owner can work ------------------------------------------------------------

    /**
     * Asserts 2xx rather than 201: whether a write creates or replaces is
     * {@code ResourceCreateHttpTest}'s subject, and the store outlives a single test class, so
     * pinning 201 here makes this fail on a second run for a reason that has nothing to do
     * with authorization.
     */
    @Test
    @DisplayName("the owner may write and read, via the seeded root ACL's acl:default")
    void ownerHasFullAccess() {
        asOwner(client.put().uri("/notes/mine").header(HttpHeaders.CONTENT_TYPE, TURTLE)
                        .bodyValue("<#a> <#b> \"c\" ."))
                .exchange().expectStatus().is2xxSuccessful();

        asOwner(client.get().uri("/notes/mine")).exchange().expectStatus().isOk();
    }

    @Test
    @DisplayName("a bad credential authenticates nobody, so it is 401 rather than 403")
    void badCredentialIsAnonymous() {
        put("/notes/hello", "<#a> <#b> \"c\" .");

        client.get().uri("/notes/hello")
                .header(HttpHeaders.AUTHORIZATION, "Bearer wrong")
                .exchange().expectStatus().isUnauthorized();
    }

    // ---- 403: authenticated, but not permitted -----------------------------------------

    /**
     * The distinction the harness insists on. A resource with its own ACL that does not name
     * the owner: discovery stops there (own ACL wins over the root's {@code acl:default}), the
     * owner matches nothing, and an <em>authenticated</em> agent who is refused gets 403 —
     * retrying with the same credential will not help, which 401 would wrongly imply.
     */
    @Test
    @DisplayName("an authenticated agent with no grant gets 403, not 401")
    void authenticatedButUnauthorisedIsForbidden() {
        put("/private/secret", "<#a> <#b> \"c\" .");
        put("/private/secret.acl", """
                @prefix acl: <http://www.w3.org/ns/auth/acl#> .
                <#bob> a acl:Authorization ;
                    acl:agent <https://bob.example/profile/card#me> ;
                    acl:accessTo <%s/private/secret> ;
                    acl:mode acl:Read .
                """.formatted(BASE));

        asOwner(client.get().uri("/private/secret")).exchange().expectStatus().isForbidden();
    }

    // ---- public access ------------------------------------------------------------------

    @Test
    @DisplayName("foaf:Agent makes a resource readable without any credential")
    void publicResourceIsReadableAnonymously() {
        put("/public/notice", "<#a> <#b> \"c\" .");
        put("/public/notice.acl", """
                @prefix acl: <http://www.w3.org/ns/auth/acl#> .
                @prefix foaf: <http://xmlns.com/foaf/0.1/> .
                <#public> a acl:Authorization ;
                    acl:agentClass foaf:Agent ;
                    acl:accessTo <%s/public/notice> ;
                    acl:mode acl:Read .
                """.formatted(BASE));

        client.get().uri("/public/notice").exchange().expectStatus().isOk();
    }

    @Test
    @DisplayName("a public grant of Read does not carry Write")
    void publicReadIsNotPublicWrite() {
        put("/public/notice", "<#a> <#b> \"c\" .");
        put("/public/notice.acl", """
                @prefix acl: <http://www.w3.org/ns/auth/acl#> .
                @prefix foaf: <http://xmlns.com/foaf/0.1/> .
                <#public> a acl:Authorization ;
                    acl:agentClass foaf:Agent ;
                    acl:accessTo <%s/public/notice> ;
                    acl:mode acl:Read .
                """.formatted(BASE));

        client.put().uri("/public/notice").header(HttpHeaders.CONTENT_TYPE, TURTLE)
                .bodyValue("<#x> <#y> \"z\" .")
                .exchange().expectStatus().isUnauthorized();
    }

    // ---- WAC-Allow -----------------------------------------------------------------------

    @Test
    @DisplayName("GET advertises WAC-Allow with the user's and the public's modes")
    void wacAllowOnGet() {
        put("/notes/hello", "<#a> <#b> \"c\" .");

        asOwner(client.get().uri("/notes/hello")).exchange()
                .expectStatus().isOk()
                .expectHeader().value(HttpConstants.WAC_ALLOW, value -> {
                    org.junit.jupiter.api.Assertions.assertTrue(
                            value.contains("user=\"read write append control\""), value);
                    org.junit.jupiter.api.Assertions.assertTrue(
                            value.contains("public=\"\""), value);
                });
    }

    @Test
    @DisplayName("HEAD advertises WAC-Allow too")
    void wacAllowOnHead() {
        put("/notes/hello", "<#a> <#b> \"c\" .");

        asOwner(client.head().uri("/notes/hello")).exchange()
                .expectStatus().isOk()
                .expectHeader().exists(HttpConstants.WAC_ALLOW);
    }
}
