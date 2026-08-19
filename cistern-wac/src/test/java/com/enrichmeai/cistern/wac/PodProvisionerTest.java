package com.enrichmeai.cistern.wac;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.enrichmeai.cistern.core.Agent;
import com.enrichmeai.cistern.core.InMemoryResourceStore;
import com.enrichmeai.cistern.core.Representation;
import com.enrichmeai.cistern.core.ResourceIdentifier;
import com.enrichmeai.cistern.core.StoredResource;
import com.enrichmeai.cistern.core.rdf.RdfIo;
import com.enrichmeai.cistern.core.vocab.Acl;
import com.enrichmeai.cistern.core.vocab.Foaf;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.apache.jena.rdf.model.Model;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import reactor.test.StepVerifier;

/**
 * Pod provisioning (T5.6): a root container plus the ACL that makes somebody its owner,
 * created together, idempotently, and never over the top of an ACL that is already there.
 *
 * <p>The ACL-shape assertions read the written bytes back through the real machinery —
 * {@link RdfIo} to parse, {@link WacEngine} to interpret, {@link AclDiscovery} and
 * {@link AccessControl} to enforce — rather than pattern-matching Turtle: what matters is what
 * the engine will grant, not how the file is laid out.
 */
class PodProvisionerTest {

    private static final String BASE = "https://pod.example";
    private static final URI ACME = URI.create("https://acme-law.example/profile#firm");
    private static final URI ALICE = URI.create("https://alice.example/profile/card#me");
    private static final URI BOB = URI.create("https://bob.example/profile/card#me");

    private static final Set<AccessMode> ALL_MODES = EnumSet.allOf(AccessMode.class);

    private InMemoryResourceStore store;
    private PodProvisioner provisioner;

    @BeforeEach
    void setUp() {
        store = new InMemoryResourceStore();
        provisioner = new PodProvisioner(store);
    }

    private static ResourceIdentifier id(String path) {
        return new ResourceIdentifier(URI.create(BASE + path));
    }

    private static PodSpec pod(String rootPath, URI owner) {
        return new PodSpec(id(rootPath), owner);
    }

    private StoredResource stored(ResourceIdentifier identifier) {
        return store.get(identifier).block();
    }

    private void write(ResourceIdentifier identifier, String turtle) {
        store.put(identifier, new Representation(Representation.TURTLE,
                turtle.getBytes(StandardCharsets.UTF_8))).block();
    }

    // ---- creating ---------------------------------------------------------------------

    @Nested
    @DisplayName("provisioning a pod that is not there")
    class Creating {

        @Test
        @DisplayName("creates the root container and its owner ACL, and says so")
        void createsContainerAndAcl() {
            PodSpec spec = pod("/firms/acme/", ACME);

            StepVerifier.create(provisioner.provision(spec))
                    .expectNext(new PodProvisioned.Created(spec.root(), id("/firms/acme/.acl")))
                    .verifyComplete();

            assertTrue(store.exists(spec.root()).block(), "the root container exists");
            assertTrue(store.exists(spec.acl()).block(), "the root's ACL exists");
            assertEquals(Representation.TURTLE, stored(spec.root()).representation().contentType(),
                    "a container is an RDF source");
            assertEquals(0, stored(spec.root()).representation().data().length,
                    "a fresh root has no client-authored triples; containment is derived on read");
        }

        @Test
        @DisplayName("the storage root itself can be a pod — the single-owner boot case")
        void storageRoot() {
            PodSpec spec = pod("/", ALICE);

            StepVerifier.create(provisioner.provision(spec))
                    .expectNext(new PodProvisioned.Created(spec.root(), id("/.acl")))
                    .verifyComplete();

            assertTrue(store.exists(id("/.acl")).block());
        }

        @Test
        @DisplayName("a container that exists without an ACL is completed, not refused")
        void completesAnUnsecuredContainer() {
            write(id("/firms/acme/"), "<> <http://purl.org/dc/terms/title> \"Acme\" .");
            String etagBefore = stored(id("/firms/acme/")).etag();
            PodSpec spec = pod("/firms/acme/", ACME);

            StepVerifier.create(provisioner.provision(spec))
                    .expectNext(new PodProvisioned.Created(spec.root(), spec.acl()))
                    .verifyComplete();

            assertEquals(etagBefore, stored(id("/firms/acme/")).etag(),
                    "the container's own triples are left exactly as they were");
            assertTrue(store.exists(spec.acl()).block());
        }

        @Test
        @DisplayName("several pods, several owners, one store")
        void severalPods() {
            List<PodSpec> pods = List.of(
                    pod("/alice/", ALICE), pod("/bob/", BOB), pod("/firms/acme/", ACME));

            for (PodSpec spec : pods) {
                StepVerifier.create(provisioner.provision(spec))
                        .assertNext(outcome -> assertInstanceOf(PodProvisioned.Created.class, outcome))
                        .verifyComplete();
            }

            AccessControl access = new AccessControl(new AclDiscovery(store), new WacEngine());
            // Each owner has everything on their own pod ...
            assertEquals(ALL_MODES, access.grantedFor(id("/alice/notes/hello"), Agent.of(ALICE)).block().modes());
            assertEquals(ALL_MODES, access.grantedFor(id("/bob/"), Agent.of(BOB)).block().modes());
            assertEquals(ALL_MODES, access.grantedFor(id("/firms/acme/matters/1/"), Agent.of(ACME)).block().modes());
            // ... and nothing on anyone else's.
            assertTrue(access.grantedFor(id("/bob/"), Agent.of(ALICE)).block().isDenied());
            assertTrue(access.grantedFor(id("/alice/notes/hello"), Agent.of(BOB)).block().isDenied());
            assertTrue(access.grantedFor(id("/firms/acme/"), Agent.of(ALICE)).block().isDenied());
        }
    }

    // ---- idempotence and never-overwrite ------------------------------------------------

    @Nested
    @DisplayName("provisioning a pod that is already there")
    class AlreadyThere {

        @Test
        @DisplayName("a second run is a no-op reporting AlreadyExists")
        void secondRunIsNoOp() {
            PodSpec spec = pod("/firms/acme/", ACME);
            provisioner.provision(spec).block();
            StoredResource aclBefore = stored(spec.acl());
            StoredResource rootBefore = stored(spec.root());

            StepVerifier.create(provisioner.provision(spec))
                    .expectNext(new PodProvisioned.AlreadyExists(spec.root()))
                    .verifyComplete();

            assertEquals(aclBefore.etag(), stored(spec.acl()).etag());
            assertEquals(aclBefore.lastModified(), stored(spec.acl()).lastModified());
            assertEquals(rootBefore.etag(), stored(spec.root()).etag());
        }

        @Test
        @DisplayName("an existing ACL is never overwritten — not even one that names a different owner")
        void neverOverwritesAnExistingAcl() {
            PodSpec spec = pod("/firms/acme/", ACME);
            // The owner has since narrowed the pod: only Alice may read, nobody else anything.
            String narrowed = "@prefix acl: <http://www.w3.org/ns/auth/acl#> .\n"
                    + "<#alice> a acl:Authorization ; acl:agent <" + ALICE + "> ;\n"
                    + "  acl:accessTo <" + spec.root().uri() + "> ; acl:mode acl:Read .";
            write(spec.acl(), narrowed);
            byte[] bytesBefore = stored(spec.acl()).representation().data();

            StepVerifier.create(provisioner.provision(spec))
                    .expectNext(new PodProvisioned.AlreadyExists(spec.root()))
                    .verifyComplete();

            assertArrayEquals(bytesBefore, stored(spec.acl()).representation().data(),
                    "provisioning is not a request to reset permissions");
            AccessControl access = new AccessControl(new AclDiscovery(store), new WacEngine());
            assertTrue(access.grantedFor(spec.root(), Agent.of(ACME)).block().isDenied(),
                    "the would-be owner gained nothing: the narrowed ACL still stands");
        }

        @Test
        @DisplayName("an ACL written by hand before the container exists still counts as 'already there'")
        void aclWithoutContainer() {
            // The store creates the container as an intermediate when the ACL is put, so this
            // is the same state as a fully provisioned pod: an ACL is present, leave it alone.
            PodSpec spec = pod("/firms/acme/", ACME);
            write(spec.acl(), "@prefix acl: <http://www.w3.org/ns/auth/acl#> . <#x> a acl:Authorization .");

            StepVerifier.create(provisioner.provision(spec))
                    .expectNext(new PodProvisioned.AlreadyExists(spec.root()))
                    .verifyComplete();
        }
    }

    // ---- the ACL shape --------------------------------------------------------------------

    /**
     * The contract on what a provisioned pod's ACL grants: the owner, everything, on the root
     * and beneath it — and nobody else anything.
     */
    @Nested
    @DisplayName("the owner ACL's shape")
    class AclShape {

        private final PodSpec spec = pod("/firms/acme/", ACME);
        private final WacEngine engine = new WacEngine();

        private Model writtenAcl() {
            provisioner.provision(spec).block();
            return RdfIo.parse(stored(spec.acl()).representation(), spec.acl());
        }

        @Test
        @DisplayName("exactly one authorization, for the owner, granting all four modes")
        void oneAuthorizationAllModes() {
            List<Authorization> onRoot = engine.parse(writtenAcl(), AclScope.ACCESS_TO);

            assertEquals(1, onRoot.size(), "one owner, one authorization");
            Authorization owner = onRoot.get(0);
            assertEquals(Set.of(ACME), owner.agents());
            assertEquals(ALL_MODES, owner.modes(), "Read, Write, Append and Control — everything");
            assertEquals(Set.of(spec.root().uri()), owner.targets());
        }

        @Test
        @DisplayName("acl:accessTo AND acl:default: the root itself and everything under it")
        void accessToAndDefault() {
            Model acl = writtenAcl();

            List<Authorization> onRoot = engine.parse(acl, AclScope.ACCESS_TO);
            List<Authorization> inherited = engine.parse(acl, AclScope.INHERITED);
            assertEquals(1, onRoot.size(), "acl:accessTo names the root");
            assertEquals(1, inherited.size(), "acl:default names the root, so children inherit");
            assertEquals(onRoot.get(0).modes(), inherited.get(0).modes());
            assertEquals(Set.of(spec.root().uri()), inherited.get(0).targets(),
                    "acl:default names the container, as WAC defines it — not the children");
        }

        @Test
        @DisplayName("nothing to foaf:Agent, nothing to any agent class: a new pod is private")
        void noPublicGrant() {
            Model acl = writtenAcl();

            for (AclScope scope : AclScope.values()) {
                for (Authorization authorization : engine.parse(acl, scope)) {
                    assertTrue(authorization.agentClasses().isEmpty(),
                            "no agent class under " + scope + ": " + authorization);
                }
            }
            assertFalse(acl.containsResource(Foaf.AGENT), "foaf:Agent does not appear at all");
            assertFalse(acl.contains(null, Acl.AGENT_CLASS, (org.apache.jena.rdf.model.RDFNode) null),
                    "no acl:agentClass statement of any kind");
        }

        @ParameterizedTest(name = "an anonymous request is denied {0} on the root")
        @EnumSource(AccessMode.class)
        void anonymousDenied(AccessMode mode) {
            EffectiveAcl effective = new EffectiveAcl(writtenAcl(), AclScope.ACCESS_TO, spec.root());
            assertFalse(engine.decide(effective, Agent.ANONYMOUS).allows(mode));
        }

        @Test
        @DisplayName("through discovery: the owner holds every mode on the root and on a deep descendant")
        void enforcedThroughDiscovery() {
            provisioner.provision(spec).block();
            AccessControl access = new AccessControl(new AclDiscovery(store), engine);

            assertEquals(ALL_MODES, access.grantedFor(spec.root(), Agent.of(ACME)).block().modes());
            assertEquals(ALL_MODES,
                    access.grantedFor(id("/firms/acme/matters/2026-114/contract.pdf"), Agent.of(ACME))
                            .block().modes());
            assertTrue(access.grantedFor(spec.root(), Agent.of(ALICE)).block().isDenied());
            assertTrue(access.grantedFor(spec.root(), Agent.ANONYMOUS).block().isDenied());
        }

        @Test
        @DisplayName("the static rendering is the bytes the provisioner writes")
        void staticRenderingMatches() {
            provisioner.provision(spec).block();
            assertArrayEquals(PodProvisioner.ownerAcl(spec).data(),
                    stored(spec.acl()).representation().data(),
                    "a CLI writing ownerAcl() over HTTP writes exactly what boot seeding writes");
        }
    }

    // ---- the spec's rules -----------------------------------------------------------------

    @Nested
    @DisplayName("PodSpec")
    class Spec {

        @Test
        @DisplayName("the root must be a container: only a container's ACL can carry acl:default")
        void rootMustBeContainer() {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> new PodSpec(id("/firms/acme"), ACME));
            assertEquals(WacMessage.POD_ROOT_NOT_A_CONTAINER.format(BASE + "/firms/acme"), e.getMessage());
        }

        @Test
        @DisplayName("the owner must be an absolute WebID")
        void ownerMustBeAbsolute() {
            assertThrows(IllegalArgumentException.class,
                    () -> new PodSpec(id("/firms/acme/"), URI.create("profile#firm")));
        }

        @Test
        @DisplayName("the ACL resource follows the one naming convention")
        void aclFollowsConvention() {
            assertEquals(AclResource.of(id("/firms/acme/")), pod("/firms/acme/", ACME).acl());
        }
    }
}
