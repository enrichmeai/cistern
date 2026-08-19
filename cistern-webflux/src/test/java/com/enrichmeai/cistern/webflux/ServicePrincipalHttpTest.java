package com.enrichmeai.cistern.webflux;

import com.enrichmeai.cistern.core.Representation;
import com.enrichmeai.cistern.core.ResourceIdentifier;
import com.enrichmeai.cistern.core.ResourceStore;
import com.enrichmeai.cistern.webflux.auth.ChainedPrincipalResolver;
import com.enrichmeai.cistern.webflux.auth.PrincipalResolver;

import java.net.URI;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Service principals through the real filter (T4.0, #88): two applications, two WebIDs, two
 * credentials — and a grant to one is not a grant to the other.
 *
 * <p>The credential hashes are {@code sha256:} digests computed with {@code shasum -a 256}
 * outside the JVM, so this test also pins the at-rest format an operator produces from a shell.
 */
@SpringBootTest(properties = {
    "cistern.base-url=" + ServicePrincipalHttpTest.BASE,
    "cistern.owner.web-id=" + ServicePrincipalHttpTest.OWNER,
    "cistern.owner.token=" + ServicePrincipalHttpTest.OWNER_TOKEN,
    "cistern.auth.service-principals[0].web-id=" + ServicePrincipalHttpTest.LEGAL,
    "cistern.auth.service-principals[0].credential-hash="
            + "sha256:af9f6ca9c55937463513e4cb25829d6eaa89ca74ed5699c0690f13469da4c481",
    "cistern.auth.service-principals[1].web-id=" + ServicePrincipalHttpTest.TAX,
    "cistern.auth.service-principals[1].credential-hash="
            + "sha256:a6944068fa09a27c3d4ed2bf53c1a452c7c8fb1199e4a07549081720504053ec",
})
@AutoConfigureWebTestClient
class ServicePrincipalHttpTest {

    static final String BASE = "http://localhost:3000";
    static final String OWNER = "https://acme-law.example/profile#firm";
    static final String OWNER_TOKEN = "owner-token-3f9a";
    static final String LEGAL = "https://valuedocs.co.in/apps/legal#id";
    static final String TAX = "https://valuedocs.co.in/apps/tax#id";
    private static final String LEGAL_SECRET = "legal-secret-0f3c8b";
    private static final String TAX_SECRET = "tax-secret-71ad2e";
    private static final String TURTLE = "text/turtle";

    @Autowired private WebTestClient client;
    @Autowired private ResourceStore store;
    @Autowired private PrincipalResolver principalResolver;

    @BeforeEach
    void seed() {
        // Root: owner only, as OwnerPodSeeder would write it.
        put("/.acl", """
                @prefix acl: <http://www.w3.org/ns/auth/acl#> .
                <#owner> a acl:Authorization ;
                    acl:agent <%s> ;
                    acl:accessTo <%s/> ;
                    acl:default <%s/> ;
                    acl:mode acl:Read, acl:Write, acl:Append, acl:Control .
                """.formatted(OWNER, BASE, BASE));
        // A matter the legal application may read; the tax application is not named.
        put("/matters/2026-114/brief", "<#b> <http://purl.org/dc/terms/title> \"Brief\" .");
        put("/matters/2026-114/.acl", """
                @prefix acl: <http://www.w3.org/ns/auth/acl#> .
                <#owner> a acl:Authorization ;
                    acl:agent <%1$s> ;
                    acl:accessTo <%2$s/matters/2026-114/> ;
                    acl:default <%2$s/matters/2026-114/> ;
                    acl:mode acl:Read, acl:Write, acl:Append, acl:Control .
                <#legal> a acl:Authorization ;
                    acl:agent <%3$s> ;
                    acl:accessTo <%2$s/matters/2026-114/> ;
                    acl:default <%2$s/matters/2026-114/> ;
                    acl:mode acl:Read .
                """.formatted(OWNER, BASE, LEGAL));
    }

    private void put(String path, String turtle) {
        store.put(new ResourceIdentifier(URI.create(BASE + path)),
                        new Representation(TURTLE, turtle.getBytes(StandardCharsets.UTF_8)))
                .block();
    }

    private WebTestClient.ResponseSpec getAs(String secret, String path) {
        return client.get().uri(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + secret).exchange();
    }

    @Test
    @DisplayName("the resolver in the context is the chain, not a single resolver")
    void resolverIsTheChain() {
        assertInstanceOf(ChainedPrincipalResolver.class, principalResolver);
    }

    @Test
    @DisplayName("the legal application's credential resolves to the legal WebID: its grant is honoured")
    void legalCanReadItsMatter() {
        getAs(LEGAL_SECRET, "/matters/2026-114/brief").expectStatus().isOk()
                .expectHeader().value(HttpConstants.WAC_ALLOW, value ->
                        org.junit.jupiter.api.Assertions.assertTrue(
                                value.contains("user=\"read\""), value));
    }

    @Test
    @DisplayName("the tax application's credential resolves to a DIFFERENT WebID: legal's grant is not tax's")
    void taxIsForbiddenWhereLegalIsGranted() {
        // 403, not 401: tax authenticated — as itself — and is not named by any rule here.
        getAs(TAX_SECRET, "/matters/2026-114/brief").expectStatus().isForbidden();
    }

    @Test
    @DisplayName("Read is Read: the legal application cannot write to the matter")
    void legalCannotWrite() {
        client.put().uri("/matters/2026-114/brief")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + LEGAL_SECRET)
                .header(HttpHeaders.CONTENT_TYPE, TURTLE)
                .bodyValue("<#x> <#y> \"z\" .")
                .exchange().expectStatus().isForbidden();
    }

    @Test
    @DisplayName("neither application is granted anything outside the matter")
    void nothingOutsideTheGrant() {
        put("/private/ledger", "<#l> <#p> \"o\" .");
        getAs(LEGAL_SECRET, "/private/ledger").expectStatus().isForbidden();
        getAs(TAX_SECRET, "/private/ledger").expectStatus().isForbidden();
    }

    @Test
    @DisplayName("the owner's local credential is unchanged by the chain")
    void ownerStillWorks() {
        getAs(OWNER_TOKEN, "/matters/2026-114/brief").expectStatus().isOk();
        put("/private/ledger", "<#l> <#p> \"o\" .");
        getAs(OWNER_TOKEN, "/private/ledger").expectStatus().isOk();
    }

    @Test
    @DisplayName("a wrong secret authenticates nobody: 401, with a challenge")
    void wrongSecretIsAnonymous() {
        getAs("not-a-credential", "/matters/2026-114/brief").expectStatus().isUnauthorized()
                .expectHeader().exists(HttpHeaders.WWW_AUTHENTICATE);
    }

    @Test
    @DisplayName("the hash is not the credential")
    void theHashDoesNotAuthenticate() {
        getAs("sha256:af9f6ca9c55937463513e4cb25829d6eaa89ca74ed5699c0690f13469da4c481",
                "/matters/2026-114/brief").expectStatus().isUnauthorized();
    }
}
