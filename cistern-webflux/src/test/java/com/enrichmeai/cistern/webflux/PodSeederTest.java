package com.enrichmeai.cistern.webflux;

import com.enrichmeai.cistern.core.Agent;
import com.enrichmeai.cistern.core.Representation;
import com.enrichmeai.cistern.core.ResourceIdentifier;
import com.enrichmeai.cistern.core.ResourceStore;
import com.enrichmeai.cistern.core.StoredResource;
import com.enrichmeai.cistern.storage.file.FileResourceStore;
import com.enrichmeai.cistern.wac.AccessControl;
import com.enrichmeai.cistern.wac.AccessMode;
import com.enrichmeai.cistern.wac.AclDiscovery;
import com.enrichmeai.cistern.wac.AclResource;
import com.enrichmeai.cistern.wac.PodProvisioner;
import com.enrichmeai.cistern.wac.WacEngine;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The boot-time seeders over the production file backend (T5.6): a fresh boot provisions every
 * configured pod; a restart — a new store instance and new seeders over the same directory, as
 * a real restart is — provisions nothing and resets nothing.
 */
class PodSeederTest {

    private static final String BASE = "http://localhost:3000";
    private static final URI OPERATOR = URI.create("https://operator.example/profile#me");
    private static final URI ALICE = URI.create("https://alice.example/profile/card#me");
    private static final URI BOB = URI.create("https://bob.example/profile/card#me");
    private static final URI ACME = URI.create("https://acme-law.example/profile#firm");

    private static final CisternProperties.Pods THREE_PODS = new CisternProperties.Pods(List.of(
            new CisternProperties.Seed("/alice/", ALICE),
            new CisternProperties.Seed("/bob/", BOB),
            new CisternProperties.Seed("/firms/acme/", ACME)));

    @TempDir
    Path storageRoot;

    private static ResourceIdentifier id(String path) {
        return new ResourceIdentifier(URI.create(BASE + path));
    }

    private static CisternProperties properties(CisternProperties.Owner owner, CisternProperties.Pods pods) {
        return new CisternProperties(BASE, null, null, owner, null, pods);
    }

    /** One "boot": a fresh store over the directory, both seeders run in their order. */
    private ResourceStore boot(CisternProperties properties) {
        ResourceStore store = new FileResourceStore(storageRoot);
        PodProvisioner provisioner = new PodProvisioner(store);
        new OwnerPodSeeder(provisioner, properties).run(new DefaultApplicationArguments());
        new PodSeeder(provisioner, properties).run(new DefaultApplicationArguments());
        return store;
    }

    private static StoredResource stored(ResourceStore store, String path) {
        return store.get(id(path)).block();
    }

    @Test
    @DisplayName("a fresh boot with three seeded pods creates three roots and three owner ACLs")
    void freshBootSeedsThreePods() {
        ResourceStore store = boot(properties(new CisternProperties.Owner(OPERATOR, "t"), THREE_PODS));

        for (String root : List.of("/alice/", "/bob/", "/firms/acme/")) {
            assertTrue(store.exists(id(root)).block(), root);
            assertTrue(store.exists(AclResource.of(id(root))).block(), root + ".acl");
        }
        assertTrue(store.exists(id("/.acl")).block(), "the operator's root ACL, as before T5.6");

        AccessControl access = new AccessControl(new AclDiscovery(store), new WacEngine());
        assertEquals(EnumSet.allOf(AccessMode.class),
                access.grantedFor(id("/alice/notes/hello"), Agent.of(ALICE)).block().modes());
        assertEquals(EnumSet.allOf(AccessMode.class),
                access.grantedFor(id("/firms/acme/"), Agent.of(ACME)).block().modes());
        assertTrue(access.grantedFor(id("/alice/"), Agent.of(BOB)).block().isDenied());
        assertTrue(access.grantedFor(id("/alice/"), Agent.ANONYMOUS).block().isDenied());
    }

    @Test
    @DisplayName("a restart provisions nothing: every ACL keeps its validators")
    void restartIsANoOp() {
        CisternProperties properties = properties(new CisternProperties.Owner(OPERATOR, "t"), THREE_PODS);
        ResourceStore first = boot(properties);
        List<StoredResource> before = List.of(
                stored(first, "/.acl"), stored(first, "/alice/.acl"),
                stored(first, "/bob/.acl"), stored(first, "/firms/acme/.acl"),
                stored(first, "/alice/"), stored(first, "/firms/acme/"));

        ResourceStore second = boot(properties);

        for (StoredResource resource : before) {
            StoredResource after = second.get(resource.identifier()).block();
            assertEquals(resource.etag(), after.etag(), resource.identifier().uri().toString());
            assertEquals(resource.lastModified(), after.lastModified(), resource.identifier().uri().toString());
        }
    }

    @Test
    @DisplayName("a restart never overwrites an ACL the owner has since changed")
    void restartKeepsANarrowedAcl() {
        CisternProperties properties = properties(new CisternProperties.Owner(OPERATOR, "t"), THREE_PODS);
        ResourceStore first = boot(properties);
        byte[] narrowed = ("@prefix acl: <http://www.w3.org/ns/auth/acl#> .\n"
                + "<#bob> a acl:Authorization ; acl:agent <" + BOB + "> ;\n"
                + "  acl:accessTo <" + BASE + "/bob/> ; acl:mode acl:Read .").getBytes(StandardCharsets.UTF_8);
        first.put(id("/bob/.acl"), new Representation(Representation.TURTLE, narrowed)).block();

        ResourceStore second = boot(properties);

        assertArrayEquals(narrowed, stored(second, "/bob/.acl").representation().data());
        AccessControl access = new AccessControl(new AclDiscovery(second), new WacEngine());
        assertFalse(access.grantedFor(id("/bob/"), Agent.of(BOB)).block().allows(AccessMode.WRITE),
                "Bob's own narrowing stands after the restart");
    }

    @Test
    @DisplayName("seeds without a configured owner still provision — the storage root just gets no ACL")
    void seedsWithoutOwner() {
        ResourceStore store = boot(properties(null, THREE_PODS));

        assertTrue(store.exists(id("/alice/.acl")).block());
        assertFalse(store.exists(id("/.acl")).block(), "nobody owns the storage root, so nothing is written there");
    }

    @Test
    @DisplayName("no seeds: the seeder does nothing and the single-owner path is untouched")
    void noSeeds() {
        ResourceStore store = boot(properties(new CisternProperties.Owner(OPERATOR, "t"), null));

        assertTrue(store.exists(id("/.acl")).block());
        assertEquals(List.of(id("/.acl")), store.children(id("/")).collectList().block(),
                "the root holds exactly what OwnerPodSeeder wrote, nothing more");
    }

    @Test
    @DisplayName("the seeders run in a fixed order: the storage root before the roots beneath it")
    void orderIsFixed() {
        assertTrue(OwnerPodSeeder.ORDER < PodSeeder.ORDER);
    }
}
