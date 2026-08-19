package com.enrichmeai.cistern.webflux;

import com.enrichmeai.cistern.core.ResourceIdentifier;
import com.enrichmeai.cistern.wac.PodSpec;

import java.net.URI;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The {@code cistern.pods.seed[]} records (T5.6): what binds, and what is refused at bind time. */
class CisternPropertiesPodsTest {

    private static final String BASE = "http://localhost:3000";
    private static final URI OPERATOR = URI.create("https://operator.example/profile#me");
    private static final URI ACME = URI.create("https://acme-law.example/profile#firm");
    private static final URI ALICE = URI.create("https://alice.example/profile/card#me");

    private static CisternProperties.Seed seed(String root, URI owner) {
        return new CisternProperties.Seed(root, owner);
    }

    @Test
    @DisplayName("nothing configured: no pods, no error")
    void unconfiguredDefaults() {
        CisternProperties properties = new CisternProperties(null, null, null, null, null, null);
        assertTrue(properties.pods().seed().isEmpty());
        assertTrue(properties.pods().specsUnder(properties.baseUrl()).isEmpty());
    }

    @Test
    @DisplayName("a root is a path under the base URL, in configuration order")
    void rootsResolveUnderTheBase() {
        CisternProperties.Pods pods = new CisternProperties.Pods(
                List.of(seed("/firms/acme/", ACME), seed("/alice/", ALICE), seed("/", OPERATOR)));

        List<PodSpec> specs = pods.specsUnder(BASE);

        assertEquals(List.of(
                        new PodSpec(new ResourceIdentifier(URI.create(BASE + "/firms/acme/")), ACME),
                        new PodSpec(new ResourceIdentifier(URI.create(BASE + "/alice/")), ALICE),
                        new PodSpec(new ResourceIdentifier(URI.create(BASE + "/")), OPERATOR)),
                specs);
    }

    @Test
    @DisplayName("surrounding whitespace on a root is insignificant")
    void rootIsTrimmed() {
        assertEquals("/alice/", seed("  /alice/ ", ALICE).root());
    }

    @Test
    @DisplayName("an entry needs both root and owner")
    void bothHalvesRequired() {
        assertThrows(IllegalArgumentException.class, () -> seed(null, ALICE));
        assertThrows(IllegalArgumentException.class, () -> seed(" ", ALICE));
        assertThrows(IllegalArgumentException.class, () -> seed("/alice/", null));
    }

    @ParameterizedTest(name = "\"{0}\" is not a container path")
    @ValueSource(strings = {"alice/", "/alice", "alice", "/a//b/", "http://localhost:3000/alice/"})
    void rootMustBeAContainerPath(String root) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> seed(root, ALICE));
        assertEquals(WebfluxMessage.POD_SEED_ROOT_NOT_A_CONTAINER_PATH.format(root), e.getMessage());
    }

    @ParameterizedTest(name = "\"{0}\" has a dot segment")
    @ValueSource(strings = {"/./", "/a/../b/", "/../", "/a/./"})
    void rootMustBeNormalized(String root) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> seed(root, ALICE));
        assertEquals(WebfluxMessage.POD_SEED_ROOT_NOT_NORMALIZED.format(root), e.getMessage());
    }

    @Test
    @DisplayName("a root that is not a URI under the base is refused with the reason")
    void rootMustFormAUri() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> seed("/with space/", ALICE).specUnder(BASE));
        assertTrue(e.getMessage().startsWith("cistern.pods.seed[].root /with space/ does not form a valid URI"),
                e.getMessage());
    }

    @Test
    @DisplayName("the owner must be an absolute WebID")
    void ownerMustBeAbsolute() {
        assertThrows(IllegalArgumentException.class, () -> seed("/alice/", URI.create("profile/card#me")));
    }

    @Test
    @DisplayName("the same root twice is a contradiction, refused rather than resolved")
    void duplicateRootRefused() {
        CisternProperties.Pods pods = new CisternProperties.Pods(
                List.of(seed("/alice/", ALICE), seed("/alice/", ACME)));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> pods.specsUnder(BASE));
        assertEquals(WebfluxMessage.POD_SEED_ROOT_DUPLICATED.format("/alice/"), e.getMessage());
    }

    @Test
    @DisplayName("bound together: the outer record validates the seeds against the base URL")
    void outerRecordValidates() {
        CisternProperties.Pods pods = new CisternProperties.Pods(
                List.of(seed("/alice/", ALICE), seed("/alice/", ACME)));
        assertThrows(IllegalArgumentException.class,
                () -> new CisternProperties(BASE, null, null, null, null, pods));
    }

    @Test
    @DisplayName("a seed for the storage root naming a different owner than cistern.owner is refused")
    void storageRootSeedMustAgreeWithOwner() {
        CisternProperties.Owner owner = new CisternProperties.Owner(OPERATOR, "token");
        CisternProperties.Pods contradiction = new CisternProperties.Pods(List.of(seed("/", ALICE)));

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new CisternProperties(BASE, null, null, owner, null, contradiction));
        assertEquals(WebfluxMessage.POD_SEED_ROOT_CONTRADICTS_OWNER.format(ALICE, OPERATOR), e.getMessage());
    }

    @Test
    @DisplayName("a seed for the storage root naming the same owner is redundant but allowed")
    void storageRootSeedMayRestateOwner() {
        CisternProperties.Owner owner = new CisternProperties.Owner(OPERATOR, "token");
        CisternProperties.Pods restated = new CisternProperties.Pods(List.of(seed("/", OPERATOR)));

        CisternProperties properties = new CisternProperties(BASE, null, null, owner, null, restated);

        assertEquals(1, properties.pods().seed().size());
    }

    @Test
    @DisplayName("without a configured owner, a seed may own the storage root")
    void storageRootSeedWithoutOwner() {
        CisternProperties.Pods pods = new CisternProperties.Pods(List.of(seed("/", ALICE)));

        CisternProperties properties = new CisternProperties(BASE, null, null, null, null, pods);

        assertTrue(properties.pods().specsUnder(BASE).get(0).root().isStorageRoot());
    }
}
