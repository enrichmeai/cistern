package com.enrichmeai.cistern.webflux;

import com.enrichmeai.cistern.core.ResourceIdentifier;
import com.enrichmeai.cistern.core.ResourceStore;
import com.enrichmeai.cistern.wac.AclResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The production shape (ADR 0002, T7.7): {@code cistern.owner.web-id} set, {@code cistern.owner.token}
 * <em>unset</em>, and a hashed service credential as the way in. Enforcement is on, the root ACL is
 * seeded for the owner's WebID, the credential authenticates, and there is no local bearer token
 * anywhere — nothing plaintext at rest, and nothing that "Bearer <anything>" could match.
 */
@SpringBootTest(properties = {
    "cistern.base-url=" + OwnerWithoutTokenHttpTest.BASE,
    "cistern.owner.web-id=" + OwnerWithoutTokenHttpTest.OWNER,
    "cistern.auth.service-principals[0].web-id=" + OwnerWithoutTokenHttpTest.LEGAL,
    "cistern.auth.service-principals[0].credential-hash="
            + "sha256:af9f6ca9c55937463513e4cb25829d6eaa89ca74ed5699c0690f13469da4c481",
})
@AutoConfigureWebTestClient
class OwnerWithoutTokenHttpTest {

    static final String BASE = "http://localhost:3000";
    static final String OWNER = "https://valuedocs.co.in/profile#admin";
    static final String LEGAL = "https://valuedocs.co.in/apps/legal#id";
    private static final String LEGAL_SECRET = "legal-secret-0f3c8b";
    private static final String NOT_A_CREDENTIAL = "owner-token-that-was-never-configured";

    private static final Path STORAGE_ROOT = createTempRoot();

    @Autowired private WebTestClient client;
    @Autowired private ResourceStore store;
    @Autowired private ApplicationContext context;
    @Autowired private CisternProperties properties;

    @DynamicPropertySource
    static void storageRoot(DynamicPropertyRegistry registry) {
        registry.add("cistern.storage.root", STORAGE_ROOT::toString);
    }

    private static Path createTempRoot() {
        try {
            return Files.createTempDirectory("cistern-t77-owner-");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static ResourceIdentifier id(String path) {
        return new ResourceIdentifier(URI.create(BASE + path));
    }

    @Test
    @DisplayName("enforcement is on and the root ACL is seeded for the owner — no token required")
    void enforcedAndSeededWithoutAToken() {
        assertTrue(properties.owner().isNamed());
        assertFalse(properties.owner().hasLocalCredential(), "the whole point: no local token");
        assertTrue(context.containsBean("cisternAuthorizationFilter"), "the WebID alone registers enforcement");
        assertTrue(store.exists(AclResource.of(id("/"))).block(), "the root ACL is seeded for the owner's WebID");
    }

    @Test
    @DisplayName("anonymous is refused with 401")
    void anonymousIsRefused() {
        client.get().uri("/").exchange().expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("the service credential authenticates — and is judged by the root ACL: 403, not 401")
    void serviceCredentialAuthenticates() {
        client.get().uri("/")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + LEGAL_SECRET)
                .exchange().expectStatus().isForbidden();
    }

    @Test
    @DisplayName("no bearer token is the owner's: with no local credential configured, none can be presented")
    void noBearerTokenIsTheOwners() {
        client.get().uri("/")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + NOT_A_CREDENTIAL)
                .exchange().expectStatus().isUnauthorized();
    }
}
