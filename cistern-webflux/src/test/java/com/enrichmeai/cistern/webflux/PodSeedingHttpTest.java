package com.enrichmeai.cistern.webflux;

import com.enrichmeai.cistern.core.Representation;
import com.enrichmeai.cistern.core.ResourceIdentifier;
import com.enrichmeai.cistern.core.ResourceStore;
import com.enrichmeai.cistern.core.StoredResource;
import com.enrichmeai.cistern.wac.AclResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code cistern.pods.seed[]} through a real boot (T5.6, #90): three pods, three owners, one
 * server — seeded by the runners the context executes, on the production file backend, and
 * then exercised over HTTP through the real filter.
 *
 * <p>Nothing here writes an ACL by hand. If the seeding did not happen, every request below is
 * refused and the test says so; that is the point of running the boot path rather than
 * pre-writing the fixture as the other authorization tests do.
 *
 * <p>Each pod owner authenticates as a service principal (T4.0), because a seeded owner is a
 * WebID and a WebID needs <em>some</em> way to prove itself. The credential hashes are
 * {@code sha256:} digests computed with {@code shasum -a 256} outside the JVM.
 */
@SpringBootTest(properties = {
    "cistern.base-url=" + PodSeedingHttpTest.BASE,
    "cistern.owner.web-id=" + PodSeedingHttpTest.OPERATOR,
    "cistern.owner.token=" + PodSeedingHttpTest.OPERATOR_TOKEN,
    "cistern.pods.seed[0].root=/alice/",
    "cistern.pods.seed[0].owner-web-id=" + PodSeedingHttpTest.ALICE,
    "cistern.pods.seed[1].root=/bob/",
    "cistern.pods.seed[1].owner-web-id=" + PodSeedingHttpTest.BOB,
    "cistern.pods.seed[2].root=/firms/acme/",
    "cistern.pods.seed[2].owner-web-id=" + PodSeedingHttpTest.ACME,
    "cistern.auth.service-principals[0].web-id=" + PodSeedingHttpTest.ALICE,
    "cistern.auth.service-principals[0].credential-hash="
            + "sha256:2f9528a634ab40900cce9639538115436933cd93a870ea33b664d4f22249795d",
    "cistern.auth.service-principals[1].web-id=" + PodSeedingHttpTest.BOB,
    "cistern.auth.service-principals[1].credential-hash="
            + "sha256:6a3e791721290f652ae1083179f6a324f5253dd96100efa8e860bf9940d0d1ca",
    "cistern.auth.service-principals[2].web-id=" + PodSeedingHttpTest.ACME,
    "cistern.auth.service-principals[2].credential-hash="
            + "sha256:3d4367ed38ce44fc9ac657b234d4610366119371474b348575ebcc2370311f14",
})
@AutoConfigureWebTestClient
class PodSeedingHttpTest {

    static final String BASE = "http://localhost:3000";
    static final String OPERATOR = "https://operator.example/profile#me";
    static final String OPERATOR_TOKEN = "operator-token-0a1b";
    static final String ALICE = "https://alice.example/profile/card#me";
    static final String BOB = "https://bob.example/profile/card#me";
    static final String ACME = "https://acme-law.example/profile#firm";
    private static final String ALICE_SECRET = "alice-secret-4b1e";
    private static final String BOB_SECRET = "bob-secret-9c2d";
    private static final String ACME_SECRET = "acme-secret-7e5f";
    private static final String TURTLE = "text/turtle";

    private static final List<String> SEEDED_ROOTS = List.of("/alice/", "/bob/", "/firms/acme/");

    private static final Path STORAGE_ROOT = createTempRoot();

    @Autowired private WebTestClient client;
    @Autowired private ResourceStore store;
    @Autowired private PodSeeder podSeeder;
    @Autowired private OwnerPodSeeder ownerPodSeeder;

    @DynamicPropertySource
    static void storageRoot(DynamicPropertyRegistry registry) {
        registry.add("cistern.storage.root", STORAGE_ROOT::toString);
    }

    private static Path createTempRoot() {
        try {
            return Files.createTempDirectory("cistern-t56-");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static ResourceIdentifier id(String path) {
        return new ResourceIdentifier(URI.create(BASE + path));
    }

    private StoredResource stored(String path) {
        return store.get(id(path)).block();
    }

    private WebTestClient.ResponseSpec getAs(String secret, String path) {
        return client.get().uri(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + secret).exchange();
    }

    private WebTestClient.ResponseSpec putAs(String secret, String path) {
        return client.put().uri(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + secret)
                .header(HttpHeaders.CONTENT_TYPE, TURTLE)
                .bodyValue("<#a> <#b> \"c\" .")
                .exchange();
    }

    // ---- what a fresh boot produces ---------------------------------------------------------

    @Test
    @DisplayName("a fresh boot creates every seeded root and its ACL — and the operator's root ACL")
    void freshBootSeedsEveryPod() {
        for (String root : SEEDED_ROOTS) {
            assertTrue(store.exists(id(root)).block(), root + " exists");
            assertTrue(store.exists(AclResource.of(id(root))).block(), root + ".acl exists");
            assertTrue(id(root).isContainer());
        }
        assertTrue(store.exists(id("/.acl")).block(), "cistern.owner still seeds the storage root");
    }

    @Test
    @DisplayName("each owner has full access to their own pod, through the real filter")
    void ownersReachTheirOwnPods() {
        getAs(ALICE_SECRET, "/alice/").expectStatus().isOk();
        getAs(BOB_SECRET, "/bob/").expectStatus().isOk();
        getAs(ACME_SECRET, "/firms/acme/").expectStatus().isOk();
        // Write, and deep: acl:default carries the grant down the subtree.
        putAs(ACME_SECRET, "/firms/acme/matters/2026-114/index").expectStatus().is2xxSuccessful();
        getAs(ACME_SECRET, "/firms/acme/matters/2026-114/index").expectStatus().isOk();
    }

    @Test
    @DisplayName("a pod is private: another seeded owner is refused, and so is the anonymous public")
    void podsAreSeparate() {
        getAs(BOB_SECRET, "/alice/").expectStatus().isForbidden();
        getAs(ALICE_SECRET, "/bob/").expectStatus().isForbidden();
        getAs(ALICE_SECRET, "/firms/acme/").expectStatus().isForbidden();
        client.get().uri("/alice/").exchange().expectStatus().isUnauthorized();
        putAs(BOB_SECRET, "/alice/notes").expectStatus().isForbidden();
    }

    /**
     * WAC: a resource-level ACL <em>replaces</em> inheritance. The storage root's owner holds
     * {@code acl:default} on {@code /}, but a seeded pod carries its own ACL naming only its
     * owner, so the operator's grant stops at the pod's boundary. That is what makes the
     * seeded pod the seeded owner's, and not merely a folder in the operator's.
     */
    @Test
    @DisplayName("the storage root's owner does not inherit into a seeded pod: its ACL replaces inheritance")
    void operatorDoesNotInheritIntoSeededPods() {
        getAs(OPERATOR_TOKEN, "/alice/").expectStatus().isForbidden();
        // Outside the seeded pods the operator's acl:default still applies.
        putAs(OPERATOR_TOKEN, "/operator/notes").expectStatus().is2xxSuccessful();
    }

    // ---- restart ------------------------------------------------------------------------------

    /**
     * A restart runs the same runners against the same store. Nothing is written a second time:
     * the ACLs keep their validators, and an ACL the owner has since narrowed stays narrowed.
     */
    @Test
    @DisplayName("running the seeders again is a no-op: validators unchanged, a narrowed ACL untouched")
    void restartIsIdempotent() {
        // Alice narrows her pod to read-only for herself (an odd thing to do, but her call).
        ResourceIdentifier aliceAcl = AclResource.of(id("/alice/"));
        byte[] narrowed = ("@prefix acl: <http://www.w3.org/ns/auth/acl#> .\n"
                + "<#me> a acl:Authorization ; acl:agent <" + ALICE + "> ;\n"
                + "  acl:accessTo <" + BASE + "/alice/> ; acl:default <" + BASE + "/alice/> ;\n"
                + "  acl:mode acl:Read .").getBytes(StandardCharsets.UTF_8);
        store.put(aliceAcl, new Representation(TURTLE, narrowed)).block();
        StoredResource bobBefore = stored("/bob/.acl");
        StoredResource acmeBefore = stored("/firms/acme/.acl");
        StoredResource rootBefore = stored("/.acl");

        ownerPodSeeder.run(new DefaultApplicationArguments());
        podSeeder.run(new DefaultApplicationArguments());

        assertArrayEquals(narrowed, stored("/alice/.acl").representation().data(),
                "a restart is not a request to reset permissions");
        assertEquals(bobBefore.etag(), stored("/bob/.acl").etag());
        assertEquals(bobBefore.lastModified(), stored("/bob/.acl").lastModified());
        assertEquals(acmeBefore.etag(), stored("/firms/acme/.acl").etag());
        assertEquals(rootBefore.etag(), stored("/.acl").etag());
        // And the narrowing is what the server enforces now.
        putAs(ALICE_SECRET, "/alice/after-restart").expectStatus().isForbidden();
        getAs(ALICE_SECRET, "/alice/").expectStatus().isOk();
    }
}
