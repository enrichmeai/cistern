package com.enrichmeai.cistern.wac;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.enrichmeai.cistern.core.Agent;
import com.enrichmeai.cistern.core.InMemoryResourceStore;
import com.enrichmeai.cistern.core.Representation;
import com.enrichmeai.cistern.core.ResourceIdentifier;

import java.net.URI;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

/**
 * ACL discovery against WAC's stated algorithm: "If resource has an associated aclResource
 * with a representation, return aclResource. Otherwise, repeat using the container resource."
 */
class AclDiscoveryTest {

    private static final String ROOT = "https://pod.example/";
    private static final String ALICE = "https://alice.example/profile/card#me";

    private InMemoryResourceStore store;
    private AclDiscovery discovery;

    @BeforeEach
    void setUp() {
        store = new InMemoryResourceStore();
        discovery = new AclDiscovery(store);
    }

    private static ResourceIdentifier id(String uri) {
        return new ResourceIdentifier(URI.create(uri));
    }

    /** Write an ACL granting Alice Read on {@code target}, via {@code predicate}. */
    private void writeAcl(String governedResource, String predicate, String target) {
        String turtle = "@prefix acl: <http://www.w3.org/ns/auth/acl#> .\n"
                + "<#owner> a acl:Authorization ;\n"
                + "  acl:agent <" + ALICE + "> ;\n"
                + "  acl:" + predicate + " <" + target + "> ;\n"
                + "  acl:mode acl:Read .";
        writeRaw(governedResource, turtle);
    }

    private void writeRaw(String governedResource, String body) {
        ResourceIdentifier acl = AclResource.of(id(governedResource));
        store.put(acl, new Representation("text/turtle", body.getBytes(StandardCharsets.UTF_8)))
                .block();
    }

    // ---- the naming convention --------------------------------------------------------

    @Nested
    @DisplayName("ACL resource naming")
    class Naming {

        @Test
        @DisplayName("a document's ACL is a sibling with the suffix appended")
        void documentAcl() {
            assertEquals(id(ROOT + "notes/hello.acl"), AclResource.of(id(ROOT + "notes/hello")));
        }

        @Test
        @DisplayName("a container's ACL lives inside the container")
        void containerAcl() {
            assertEquals(id(ROOT + "notes/.acl"), AclResource.of(id(ROOT + "notes/")));
        }

        @Test
        @DisplayName("the mapping round-trips")
        void roundTrip() {
            ResourceIdentifier resource = id(ROOT + "notes/hello");

            assertEquals(resource, AclResource.governedBy(AclResource.of(resource)));
        }

        @Test
        @DisplayName("an ACL resource is recognisable, so T5.3 can require Control for it")
        void recognisesAcl() {
            assertTrue(AclResource.isAcl(id(ROOT + "notes/hello.acl")));
            assertTrue(!AclResource.isAcl(id(ROOT + "notes/hello")));
        }

        @Test
        @DisplayName("asking what a non-ACL governs is a caller bug")
        void governedByRejectsNonAcl() {
            assertThrows(IllegalArgumentException.class,
                    () -> AclResource.governedBy(id(ROOT + "notes/hello")));
        }
    }

    // ---- the walk ---------------------------------------------------------------------

    @Nested
    @DisplayName("The effective-ACL walk")
    class Walk {

        @Test
        @DisplayName("a resource's own ACL wins, and applies under ACCESS_TO")
        void ownAclWins() {
            writeAcl(ROOT + "notes/hello", "accessTo", ROOT + "notes/hello");
            writeAcl(ROOT + "notes/", "default", ROOT + "notes/");

            StepVerifier.create(discovery.findFor(id(ROOT + "notes/hello")))
                    .assertNext(acl -> {
                        assertEquals(AclScope.ACCESS_TO, acl.scope());
                        assertEquals(id(ROOT + "notes/hello"), acl.source());
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("with no ACL of its own, the nearest ancestor's applies under INHERITED")
        void inheritsFromParent() {
            writeAcl(ROOT + "notes/", "default", ROOT + "notes/");

            StepVerifier.create(discovery.findFor(id(ROOT + "notes/hello")))
                    .assertNext(acl -> {
                        assertEquals(AclScope.INHERITED, acl.scope());
                        assertEquals(id(ROOT + "notes/"), acl.source());
                    })
                    .verifyComplete();
        }

        /** "the closest container resource (heading towards the root)". */
        @Test
        @DisplayName("the CLOSEST ancestor with an ACL wins, not the root")
        void closestAncestorWins() {
            writeAcl(ROOT, "default", ROOT);
            writeAcl(ROOT + "a/b/", "default", ROOT + "a/b/");

            StepVerifier.create(discovery.findFor(id(ROOT + "a/b/c/d/deep")))
                    .assertNext(acl -> assertEquals(id(ROOT + "a/b/"), acl.source()))
                    .verifyComplete();
        }

        @Test
        @DisplayName("a deep chain falls back to the root when nothing nearer has an ACL")
        void deepChainToRoot() {
            writeAcl(ROOT, "default", ROOT);

            StepVerifier.create(discovery.findFor(id(ROOT + "a/b/c/d/e/f/g/deep")))
                    .assertNext(acl -> {
                        assertEquals(id(ROOT), acl.source());
                        assertEquals(AclScope.INHERITED, acl.scope());
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("the root's own ACL applies to the root under ACCESS_TO")
        void rootOwnAcl() {
            writeAcl(ROOT, "accessTo", ROOT);

            StepVerifier.create(discovery.findFor(id(ROOT)))
                    .assertNext(acl -> assertEquals(AclScope.ACCESS_TO, acl.scope()))
                    .verifyComplete();
        }

        @Test
        @DisplayName("no ACL anywhere yields empty — which the caller must read as deny")
        void noAclAnywhere() {
            StepVerifier.create(discovery.findFor(id(ROOT + "a/b/orphan")))
                    .verifyComplete();
        }

        @Test
        @DisplayName("the walk advertises the ACL URI for the Link: rel=\"acl\" header")
        void advertisesAclUri() {
            writeAcl(ROOT + "notes/", "default", ROOT + "notes/");

            StepVerifier.create(discovery.findFor(id(ROOT + "notes/hello")))
                    .assertNext(acl -> assertEquals(id(ROOT + "notes/.acl"), acl.aclResource()))
                    .verifyComplete();
        }
    }

    // ---- failing closed ---------------------------------------------------------------

    @Nested
    @DisplayName("A broken or empty ACL must not fall through to a more permissive ancestor")
    class FailsClosed {

        /**
         * The escalation this rules out: a corrupt ACL on the resource, with a generous
         * {@code acl:default} above it. Continuing the walk would silently swap the owner's
         * intended rules for the ancestor's.
         */
        @Test
        @DisplayName("an unparseable ACL errors rather than inheriting from the parent")
        void unparseableDoesNotFallThrough() {
            writeRaw(ROOT + "notes/hello", "this is not turtle {{{");
            writeAcl(ROOT + "notes/", "default", ROOT + "notes/");

            StepVerifier.create(discovery.findFor(id(ROOT + "notes/hello")))
                    .verifyError();
        }

        /**
         * An empty ACL is a deliberate statement — it grants nobody anything. WAC's condition
         * is "has an associated aclResource with a representation", so it terminates the walk;
         * treating it as absent would let the parent's defaults leak back in.
         */
        @Test
        @DisplayName("an empty ACL terminates the walk and denies everyone")
        void emptyAclTerminatesTheWalk() {
            writeRaw(ROOT + "notes/hello", "");
            writeAcl(ROOT + "notes/", "default", ROOT + "notes/");

            StepVerifier.create(discovery.findFor(id(ROOT + "notes/hello")))
                    .assertNext(acl -> {
                        assertEquals(id(ROOT + "notes/hello"), acl.source(),
                                "the empty ACL is the effective one, not the parent's");
                        assertTrue(new WacEngine()
                                .decide(acl, Agent.of(URI.create(ALICE)))
                                .isDenied());
                    })
                    .verifyComplete();
        }
    }

    // ---- end to end with the engine ---------------------------------------------------

    @Test
    @DisplayName("discovery and evaluation together: an inherited acl:default grants a child")
    void discoveryFeedsTheEngine() {
        writeAcl(ROOT + "notes/", "default", ROOT + "notes/");
        WacEngine engine = new WacEngine();

        StepVerifier.create(discovery.findFor(id(ROOT + "notes/hello"))
                        .map(acl -> engine.decide(acl, Agent.of(URI.create(ALICE)))))
                .assertNext(decision -> assertTrue(decision.allows(AccessMode.READ)))
                .verifyComplete();
    }

    /**
     * The mirror of the above, and the reason {@link AclScope} is carried rather than assumed:
     * the same graph read as ACCESS_TO grants nothing, because {@code acl:default} is the
     * inheritable form only.
     */
    @Test
    @DisplayName("an inherited acl:accessTo rule does NOT leak down to a child")
    void accessToDoesNotLeakDown() {
        writeAcl(ROOT + "notes/", "accessTo", ROOT + "notes/");
        WacEngine engine = new WacEngine();

        StepVerifier.create(discovery.findFor(id(ROOT + "notes/hello"))
                        .map(acl -> engine.decide(acl, Agent.of(URI.create(ALICE)))))
                .assertNext(decision -> assertTrue(decision.isDenied(),
                        "the container's accessTo rule governs the container alone"))
                .verifyComplete();
    }
}
