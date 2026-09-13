package com.enrichmeai.cistern.wac;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.enrichmeai.cistern.core.Agent;
import com.enrichmeai.cistern.core.CisternException;
import com.enrichmeai.cistern.core.ResourceIdentifier;
import com.enrichmeai.cistern.core.vocab.Acl;
import com.enrichmeai.cistern.core.vocab.Cistern;
import com.enrichmeai.cistern.core.vocab.Foaf;

import java.io.StringReader;
import java.net.URI;
import java.time.Clock;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.vocabulary.RDF;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@link GrantService} against the two traps {@code docs/INTEGRATION.md} §9 names — a
 * resource-level ACL replaces inheritance, and {@code acl:default} names the container — plus
 * the merge and revoke algebra.
 *
 * <p>Every assertion about what a written ACL <em>means</em> goes through {@link WacEngine},
 * the same evaluator that enforces requests, rather than through string matching on Turtle: a
 * grant is correct if and only if the engine reads it the way the grantor meant.
 */
class GrantServiceTest {

    private static final String POD = "https://pod.example/";
    private static final String OWNER = "https://owner.example/profile/card#me";
    private static final String ALICE = "https://alice.example/profile/card#me";
    private static final String BOB = "https://bob.example/profile/card#me";

    private static final String PREFIXES =
            "@prefix acl: <http://www.w3.org/ns/auth/acl#> .\n"
                    + "@prefix foaf: <http://xmlns.com/foaf/0.1/> .\n";

    private static final ResourceIdentifier ROOT = id(POD);
    private static final ResourceIdentifier NOTES = id(POD + "notes/");
    private static final ResourceIdentifier WEEK = id(POD + "notes/week");
    private static final ResourceIdentifier DEEP = id(POD + "notes/deep/note");

    private final GrantService service = new GrantService();
    private final WacEngine engine = new WacEngine(Clock.systemUTC(), DelegationMode.DISABLED);

    private static ResourceIdentifier id(String uri) {
        return new ResourceIdentifier(URI.create(uri));
    }

    private static Model turtle(String body, ResourceIdentifier acl) {
        Model model = ModelFactory.createDefaultModel();
        model.read(new StringReader(PREFIXES + body), acl.uri().toString(), "TURTLE");
        return model;
    }

    /** The root ACL exactly as {@code OwnerPodSeeder} writes it: owner, everything, inheritable. */
    private static EffectiveAcl seededRoot(ResourceIdentifier target) {
        Model root = turtle("<#owner> a acl:Authorization ;\n"
                + "  acl:agent <" + OWNER + "> ;\n"
                + "  acl:accessTo <" + POD + "> ; acl:default <" + POD + "> ;\n"
                + "  acl:mode acl:Read, acl:Write, acl:Append, acl:Control .", AclResource.of(ROOT));
        return new EffectiveAcl(root, target.equals(ROOT) ? AclScope.ACCESS_TO : AclScope.INHERITED, ROOT);
    }

    private static EffectiveAcl own(ResourceIdentifier target, String body) {
        return new EffectiveAcl(turtle(body, AclResource.of(target)), AclScope.ACCESS_TO, target);
    }

    private static GrantRequest grant(ResourceIdentifier target, Grantee grantee, AccessMode... modes) {
        return new GrantRequest(target, grantee, EnumSet.copyOf(List.of(modes)));
    }

    private static Grantee webId(String uri) {
        return new Grantee.WebId(URI.create(uri));
    }

    /** What {@code agent} may do to {@code target} under the outcome, read the way discovery would. */
    private AccessDecision decide(GrantOutcome outcome, ResourceIdentifier target, Agent agent) {
        ResourceIdentifier governed = AclResource.governedBy(outcome.aclResource());
        AclScope scope = governed.equals(target) ? AclScope.ACCESS_TO : AclScope.INHERITED;
        return engine.decide(new EffectiveAcl(outcome.aclGraph(), scope, governed), agent);
    }

    private static Resource node(ResourceIdentifier identifier) {
        return ResourceFactory.createResource(identifier.uri().toString());
    }

    // ---- inheritance ------------------------------------------------------------------

    @Nested
    @DisplayName("A resource-level ACL replaces inheritance, so Control-holders are re-stated")
    class Inheritance {

        @Test
        @DisplayName("granting on a container that inherits writes <container>.acl with the owner re-stated")
        void ownerIsRestated() {
            GrantOutcome outcome = service.grant(seededRoot(NOTES), grant(NOTES, Grantee.PUBLIC, AccessMode.READ));

            assertEquals(AclResource.of(NOTES), outcome.aclResource());
            assertTrue(outcome.changed());
            AccessDecision owner = decide(outcome, NOTES, Agent.of(URI.create(OWNER)));
            assertEquals(EnumSet.allOf(AccessMode.class), owner.modes(), "the owner keeps everything");
            assertEquals(EnumSet.of(AccessMode.READ), decide(outcome, NOTES, Agent.ANONYMOUS).modes());
        }

        @Test
        @DisplayName("the re-stated owner is inheritable too: children of the container stay administrable")
        void restatementIsInheritable() {
            GrantOutcome outcome = service.grant(seededRoot(NOTES), grant(NOTES, Grantee.PUBLIC, AccessMode.READ));

            AccessDecision atChild = engine.decide(
                    new EffectiveAcl(outcome.aclGraph(), AclScope.INHERITED, NOTES), Agent.of(URI.create(OWNER)));
            assertTrue(atChild.allows(AccessMode.CONTROL));
            assertTrue(atChild.allows(AccessMode.WRITE));
        }

        @Test
        @DisplayName("the re-stated authorization keeps its name (<…/.acl#owner>) so the file stays legible")
        void restatementKeepsFragment() {
            GrantOutcome outcome = service.grant(seededRoot(NOTES), grant(NOTES, Grantee.PUBLIC, AccessMode.READ));

            Resource restated = outcome.aclGraph().createResource(AclResource.of(NOTES).uri() + "#owner");
            assertTrue(restated.hasProperty(RDF.type, Acl.AUTHORIZATION));
            assertTrue(restated.hasProperty(Acl.AGENT, ResourceFactory.createResource(OWNER)));
        }

        @Test
        @DisplayName("inherited authorizations WITHOUT Control are not carried down — the grant is scoped")
        void nonControlInheritedRulesAreNotPinned() {
            Model root = turtle("<#owner> a acl:Authorization ; acl:agent <" + OWNER + "> ;\n"
                    + "  acl:accessTo <" + POD + "> ; acl:default <" + POD + "> ;\n"
                    + "  acl:mode acl:Read, acl:Write, acl:Control .\n"
                    + "<#alice> a acl:Authorization ; acl:agent <" + ALICE + "> ;\n"
                    + "  acl:default <" + POD + "> ; acl:mode acl:Read .", AclResource.of(ROOT));
            EffectiveAcl inherited = new EffectiveAcl(root, AclScope.INHERITED, ROOT);

            GrantOutcome outcome = service.grant(inherited, grant(NOTES, webId(BOB), AccessMode.READ));

            assertTrue(decide(outcome, NOTES, Agent.of(URI.create(ALICE))).isDenied(),
                    "Alice's pod-wide Read does not apply under the new resource-level ACL");
            assertTrue(decide(outcome, NOTES, Agent.of(URI.create(OWNER))).allows(AccessMode.CONTROL));
            assertTrue(decide(outcome, NOTES, Agent.of(URI.create(BOB))).allows(AccessMode.READ));
        }

        @Test
        @DisplayName("an inherited Control-holder is re-stated even when the ACL is two levels up")
        void restatesFromADistantAncestor() {
            GrantOutcome outcome = service.grant(seededRoot(DEEP), grant(DEEP, webId(BOB), AccessMode.READ));

            assertEquals(AclResource.of(DEEP), outcome.aclResource());
            assertTrue(decide(outcome, DEEP, Agent.of(URI.create(OWNER))).allows(AccessMode.CONTROL));
        }

        @Test
        @DisplayName("an ACL found on the target itself is edited in place, not re-stated")
        void ownAclIsEditedInPlace() {
            EffectiveAcl current = own(NOTES, "<#owner> a acl:Authorization ; acl:agent <" + OWNER + "> ;\n"
                    + "  acl:accessTo <" + NOTES.uri() + "> ; acl:default <" + NOTES.uri() + "> ;\n"
                    + "  acl:mode acl:Read, acl:Write, acl:Append, acl:Control .\n"
                    + "<#alice> a acl:Authorization ; acl:agent <" + ALICE + "> ;\n"
                    + "  acl:accessTo <" + NOTES.uri() + "> ; acl:mode acl:Read ;\n"
                    + "  <http://www.w3.org/2000/01/rdf-schema#comment> \"hand-written\" .");

            GrantOutcome outcome = service.grant(current, grant(NOTES, webId(BOB), AccessMode.READ));

            assertTrue(decide(outcome, NOTES, Agent.of(URI.create(ALICE))).allows(AccessMode.READ),
                    "an unrelated authorization in the target's own ACL survives");
            Resource alice = outcome.aclGraph().createResource(AclResource.of(NOTES).uri() + "#alice");
            assertTrue(alice.hasProperty(org.apache.jena.vocabulary.RDFS.comment),
                    "triples the service does not understand are preserved, not normalised away");
        }

        @Test
        @DisplayName("an effective ACL that cannot govern the target is a caller bug")
        void rejectsAnAclFromElsewhere() {
            EffectiveAcl elsewhere = new EffectiveAcl(ModelFactory.createDefaultModel(), AclScope.ACCESS_TO, NOTES);

            assertThrows(IllegalArgumentException.class,
                    () -> service.grant(elsewhere, grant(WEEK, webId(BOB), AccessMode.READ)));
        }
    }

    // ---- accessTo and default -------------------------------------------------------------

    @Nested
    @DisplayName("acl:default names the container")
    class TargetPredicates {

        @Test
        @DisplayName("a container grant carries acl:accessTo AND acl:default")
        void containerGrantCarriesBoth() {
            GrantOutcome outcome = service.grant(seededRoot(NOTES), grant(NOTES, Grantee.PUBLIC, AccessMode.READ));

            Resource authorization = outcome.aclGraph().createResource(
                    AclResource.of(NOTES).uri() + "#" + GrantService.PUBLIC_FRAGMENT);
            assertTrue(authorization.hasProperty(Acl.ACCESS_TO, node(NOTES)));
            assertTrue(authorization.hasProperty(Acl.DEFAULT, node(NOTES)));
            assertTrue(authorization.hasProperty(Acl.AGENT_CLASS, Foaf.AGENT));
        }

        @Test
        @DisplayName("a document grant carries acl:accessTo only")
        void documentGrantCarriesAccessToOnly() {
            GrantOutcome outcome = service.grant(seededRoot(WEEK), grant(WEEK, webId(BOB), AccessMode.READ));

            Resource authorization = outcome.aclGraph().createResource(
                    AclResource.of(WEEK).uri() + "#" + GrantService.AGENT_FRAGMENT);
            assertTrue(authorization.hasProperty(Acl.ACCESS_TO, node(WEEK)));
            assertFalse(authorization.hasProperty(Acl.DEFAULT));
            assertFalse(outcome.aclGraph().contains(null, Acl.DEFAULT),
                    "nothing in a document's ACL says acl:default — not even the re-stated owner");
        }

        @Test
        @DisplayName("Write is written closed under implication: Append comes with it")
        void writeCarriesAppend() {
            GrantOutcome outcome = service.grant(seededRoot(WEEK), grant(WEEK, webId(BOB), AccessMode.WRITE));

            assertEquals(EnumSet.of(AccessMode.WRITE, AccessMode.APPEND),
                    decide(outcome, WEEK, Agent.of(URI.create(BOB))).modes());
        }
    }

    // ---- merge ----------------------------------------------------------------------------

    @Nested
    @DisplayName("Granting to an existing grantee merges modes")
    class Merge {

        @Test
        @DisplayName("read then write yields one authorization with read, write, append")
        void mergesIntoOneAuthorization() {
            GrantOutcome first = service.grant(seededRoot(NOTES), grant(NOTES, webId(BOB), AccessMode.READ));
            EffectiveAcl afterFirst = new EffectiveAcl(first.aclGraph(), AclScope.ACCESS_TO, NOTES);

            GrantOutcome second = service.grant(afterFirst, grant(NOTES, webId(BOB), AccessMode.WRITE));

            assertTrue(second.changed());
            assertEquals(EnumSet.of(AccessMode.READ, AccessMode.WRITE, AccessMode.APPEND),
                    decide(second, NOTES, Agent.of(URI.create(BOB))).modes());
            long bobs = second.authorizations().stream()
                    .filter(a -> a.agents().contains(URI.create(BOB))).count();
            assertEquals(1, bobs, "merged, not duplicated");
        }

        @Test
        @DisplayName("granting what is already held changes nothing and says so")
        void idempotent() {
            GrantOutcome first = service.grant(seededRoot(NOTES), grant(NOTES, webId(BOB), AccessMode.READ));
            EffectiveAcl afterFirst = new EffectiveAcl(first.aclGraph(), AclScope.ACCESS_TO, NOTES);

            GrantOutcome again = service.grant(afterFirst, grant(NOTES, webId(BOB), AccessMode.READ));

            assertFalse(again.changed());
            assertTrue(again.aclGraph().isIsomorphicWith(first.aclGraph()));
        }

        @Test
        @DisplayName("a hand-written authorization naming the grantee AND someone else is not widened")
        void sharedAuthorizationIsNotWidened() {
            EffectiveAcl current = own(NOTES, "<#owner> a acl:Authorization ; acl:agent <" + OWNER + "> ;\n"
                    + "  acl:accessTo <" + NOTES.uri() + "> ; acl:default <" + NOTES.uri() + "> ;\n"
                    + "  acl:mode acl:Read, acl:Write, acl:Append, acl:Control .\n"
                    + "<#pair> a acl:Authorization ; acl:agent <" + ALICE + ">, <" + BOB + "> ;\n"
                    + "  acl:accessTo <" + NOTES.uri() + "> ; acl:default <" + NOTES.uri() + "> ; acl:mode acl:Read .");

            GrantOutcome outcome = service.grant(current, grant(NOTES, webId(BOB), AccessMode.WRITE));

            assertTrue(decide(outcome, NOTES, Agent.of(URI.create(BOB))).allows(AccessMode.WRITE));
            assertFalse(decide(outcome, NOTES, Agent.of(URI.create(ALICE))).allows(AccessMode.WRITE),
                    "Alice shares Bob's read rule; she must not inherit his write");
        }
    }

    // ---- revoke ---------------------------------------------------------------------------

    @Nested
    @DisplayName("Revoke removes only that grantee's authorization for this target")
    class Revoke {

        @Test
        @DisplayName("revoking the public after the demo's grant leaves the owner alone in the ACL")
        void revokesThePublic() {
            GrantOutcome granted = service.grant(seededRoot(NOTES), grant(NOTES, Grantee.PUBLIC, AccessMode.READ));
            EffectiveAcl afterGrant = new EffectiveAcl(granted.aclGraph(), AclScope.ACCESS_TO, NOTES);

            GrantOutcome revoked = service.revoke(afterGrant, new RevokeRequest(NOTES, Grantee.PUBLIC));

            assertTrue(revoked.changed());
            assertTrue(decide(revoked, NOTES, Agent.ANONYMOUS).isDenied());
            assertEquals(EnumSet.allOf(AccessMode.class), decide(revoked, NOTES, Agent.of(URI.create(OWNER))).modes());
            assertEquals(1, revoked.authorizations().size());
        }

        @Test
        @DisplayName("revoking a grantee who holds nothing changes nothing")
        void revokeOfNothingIsANoOp() {
            GrantOutcome outcome = service.revoke(seededRoot(NOTES), new RevokeRequest(NOTES, webId(BOB)));

            assertFalse(outcome.changed());
            assertEquals(AclResource.of(ROOT), outcome.aclResource(), "no resource-level ACL is created");
        }

        @Test
        @DisplayName("revoking an inherited (non-Control) grant pins a resource-level ACL without the grantee")
        void revokeOfInheritedGrantPins() {
            Model root = turtle("<#owner> a acl:Authorization ; acl:agent <" + OWNER + "> ;\n"
                    + "  acl:accessTo <" + POD + "> ; acl:default <" + POD + "> ;\n"
                    + "  acl:mode acl:Read, acl:Write, acl:Append, acl:Control .\n"
                    + "<#public> a acl:Authorization ; acl:agentClass foaf:Agent ;\n"
                    + "  acl:default <" + POD + "> ; acl:mode acl:Read .", AclResource.of(ROOT));

            GrantOutcome outcome = service.revoke(
                    new EffectiveAcl(root, AclScope.INHERITED, ROOT), new RevokeRequest(NOTES, Grantee.PUBLIC));

            assertTrue(outcome.changed());
            assertEquals(AclResource.of(NOTES), outcome.aclResource());
            assertTrue(decide(outcome, NOTES, Agent.ANONYMOUS).isDenied());
            assertTrue(decide(outcome, NOTES, Agent.of(URI.create(OWNER))).allows(AccessMode.CONTROL));
        }

        @Test
        @DisplayName("revoking an authorization that grants Control is refused")
        void refusesToDropControl() {
            EffectiveAcl current = seededRoot(NOTES);

            assertThrows(CisternException.Conflict.class,
                    () -> service.revoke(current, new RevokeRequest(NOTES, webId(OWNER))));
        }

        @Test
        @DisplayName("a shared authorization loses only the revoked agent")
        void sharedAuthorizationKeepsTheOthers() {
            EffectiveAcl current = own(NOTES, "<#owner> a acl:Authorization ; acl:agent <" + OWNER + "> ;\n"
                    + "  acl:accessTo <" + NOTES.uri() + "> ; acl:default <" + NOTES.uri() + "> ;\n"
                    + "  acl:mode acl:Read, acl:Write, acl:Append, acl:Control .\n"
                    + "<#pair> a acl:Authorization ; acl:agent <" + ALICE + ">, <" + BOB + "> ;\n"
                    + "  acl:accessTo <" + NOTES.uri() + "> ; acl:default <" + NOTES.uri() + "> ; acl:mode acl:Read .");

            GrantOutcome outcome = service.revoke(current, new RevokeRequest(NOTES, webId(BOB)));

            assertTrue(decide(outcome, NOTES, Agent.of(URI.create(BOB))).isDenied());
            assertTrue(decide(outcome, NOTES, Agent.of(URI.create(ALICE))).allows(AccessMode.READ));
        }

        @Test
        @DisplayName("an authorization covering other resources too keeps covering them for the grantee")
        void multiTargetAuthorizationKeepsTheOtherTargets() {
            ResourceIdentifier other = id(POD + "notes/other");
            EffectiveAcl current = own(NOTES, "<#owner> a acl:Authorization ; acl:agent <" + OWNER + "> ;\n"
                    + "  acl:accessTo <" + NOTES.uri() + "> ; acl:default <" + NOTES.uri() + "> ;\n"
                    + "  acl:mode acl:Read, acl:Write, acl:Append, acl:Control .\n"
                    + "<#bob> a acl:Authorization ; acl:agent <" + BOB + "> ;\n"
                    + "  acl:accessTo <" + NOTES.uri() + ">, <" + other.uri() + "> ; acl:mode acl:Read .");

            GrantOutcome outcome = service.revoke(current, new RevokeRequest(NOTES, webId(BOB)));

            assertTrue(decide(outcome, NOTES, Agent.of(URI.create(BOB))).isDenied());
            assertTrue(engine.decide(outcome.aclGraph(), other.uri(), Agent.of(URI.create(BOB)), AclScope.ACCESS_TO)
                    .allows(AccessMode.READ), "the rule for the other resource is untouched");
        }

        @Test
        @DisplayName("shared AND multi-target: the others keep the original, the grantee keeps the rest")
        void splitsAFullyEntangledAuthorization() {
            ResourceIdentifier other = id(POD + "notes/other");
            EffectiveAcl current = own(NOTES, "<#owner> a acl:Authorization ; acl:agent <" + OWNER + "> ;\n"
                    + "  acl:accessTo <" + NOTES.uri() + "> ; acl:default <" + NOTES.uri() + "> ;\n"
                    + "  acl:mode acl:Read, acl:Write, acl:Append, acl:Control .\n"
                    + "<#pair> a acl:Authorization ; acl:agent <" + ALICE + ">, <" + BOB + "> ;\n"
                    + "  acl:accessTo <" + NOTES.uri() + ">, <" + other.uri() + "> ; acl:mode acl:Read .");

            GrantOutcome outcome = service.revoke(current, new RevokeRequest(NOTES, webId(BOB)));

            Agent alice = Agent.of(URI.create(ALICE));
            Agent bob = Agent.of(URI.create(BOB));
            assertTrue(decide(outcome, NOTES, bob).isDenied());
            assertTrue(decide(outcome, NOTES, alice).allows(AccessMode.READ));
            assertTrue(engine.decide(outcome.aclGraph(), other.uri(), alice, AclScope.ACCESS_TO).allows(AccessMode.READ));
            assertTrue(engine.decide(outcome.aclGraph(), other.uri(), bob, AclScope.ACCESS_TO).allows(AccessMode.READ));
        }
    }

    // ---- requests -------------------------------------------------------------------------

    @Nested
    @DisplayName("Requests are validated on construction")
    class Requests {

        @Test
        @DisplayName("a grant needs at least one mode")
        void grantNeedsModes() {
            assertThrows(IllegalArgumentException.class,
                    () -> new GrantRequest(NOTES, Grantee.PUBLIC, Set.of()));
        }

        @Test
        @DisplayName("an ACL resource is not a grant target")
        void aclIsNotATarget() {
            assertThrows(IllegalArgumentException.class,
                    () -> new GrantRequest(AclResource.of(NOTES), Grantee.PUBLIC, Set.of(AccessMode.READ)));
            assertThrows(IllegalArgumentException.class,
                    () -> new RevokeRequest(AclResource.of(NOTES), Grantee.PUBLIC));
        }

        @Test
        @DisplayName("a WebID grantee must be absolute")
        void webIdMustBeAbsolute() {
            assertThrows(IllegalArgumentException.class, () -> new Grantee.WebId(URI.create("card#me")));
        }

        /**
         * AD-DEL-1 at the authoring boundary. {@code Grantee} is sealed to a WebID or the public,
         * so there is no client shape to hand in; the one way to ask for a client-only grant is to
         * name nobody, and that is refused with the reason rather than as a null-pointer failure.
         */
        @Test
        @DisplayName("a client-only grant — clients but no grantee — is refused, with the catalogue reason")
        void clientOnlyGrantIsRefused() {
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> new GrantRequest(NOTES, null, Set.of(AccessMode.READ), Set.of(CLIENT_X)));

            assertEquals(WacMessage.GRANT_WITHOUT_GRANTEE.format(NOTES.uri()), refused.getMessage());
        }

        @Test
        @DisplayName("a client must be an absolute URI")
        void clientMustBeAbsolute() {
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                    () -> new GrantRequest(NOTES, webId(BOB), Set.of(AccessMode.READ), Set.of(URI.create("claude#id"))));

            assertEquals(WacMessage.CLIENT_NOT_ABSOLUTE.format("claude#id"), refused.getMessage());
        }

        @Test
        @DisplayName("the three-argument request is unconstrained")
        void threeArgumentRequestIsUnconstrained() {
            assertFalse(grant(NOTES, webId(BOB), AccessMode.READ).isClientConstrained());
            assertTrue(new GrantRequest(NOTES, webId(BOB), Set.of(AccessMode.READ), Set.of(CLIENT_X)).isClientConstrained());
        }
    }

    // ---- the client constraint (T6.5, ADR 0004) -------------------------------------------

    private static final URI CLIENT_X = URI.create("https://agents.example/claude#id");
    private static final URI CLIENT_Y = URI.create("https://agents.example/other#id");

    private static GrantRequest grantVia(ResourceIdentifier target, Grantee grantee, URI client, AccessMode... modes) {
        return new GrantRequest(target, grantee, EnumSet.copyOf(List.of(modes)), Set.of(client));
    }

    private static Agent via(String webId, URI client) {
        return Agent.of(URI.create(webId), Optional.of(client));
    }

    @Nested
    @DisplayName("A client constraint is written beside the grantee, and only narrows")
    class Clients {

        /** The delegation-enabled engine — what a server with the flag on decides. */
        private final WacEngine enabledEngine = new WacEngine(Clock.systemUTC(), DelegationMode.ENABLED);

        private AccessDecision decideEnabled(GrantOutcome outcome, ResourceIdentifier target, Agent agent) {
            ResourceIdentifier governed = AclResource.governedBy(outcome.aclResource());
            AclScope scope = governed.equals(target) ? AclScope.ACCESS_TO : AclScope.INHERITED;
            return enabledEngine.decide(new EffectiveAcl(outcome.aclGraph(), scope, governed), agent);
        }

        @Test
        @DisplayName("cistern:client is written on the grantee's own authorization, with the namespace declared")
        void writesTheConstraintBesideTheGrantee() {
            GrantOutcome outcome = service.grant(seededRoot(NOTES), grantVia(NOTES, webId(BOB), CLIENT_X, AccessMode.READ));

            Resource authorization = outcome.aclGraph().createResource(
                    AclResource.of(NOTES).uri() + "#" + GrantService.AGENT_FRAGMENT);
            assertTrue(authorization.hasProperty(Acl.AGENT, ResourceFactory.createResource(BOB)), "the grantee, in portable WAC");
            assertTrue(authorization.hasProperty(Cistern.CLIENT, ResourceFactory.createResource(CLIENT_X.toString())));
            assertEquals(Cistern.NS, outcome.aclGraph().getNsPrefixURI(Cistern.PREFIX), "so the file reads as cistern:client");

            assertTrue(decideEnabled(outcome, NOTES, via(BOB, CLIENT_X)).allows(AccessMode.READ));
            assertTrue(decideEnabled(outcome, NOTES, Agent.of(URI.create(BOB))).allows(AccessMode.READ), "bob alone");
            AccessDecision viaY = decideEnabled(outcome, NOTES, via(BOB, CLIENT_Y));
            assertTrue(viaY.isDenied());
            assertEquals(Optional.of(DelegationTerm.CLIENT), viaY.narrowedBy());
        }

        @Test
        @DisplayName("the outcome reports the constraint: the read-back names the clients")
        void outcomeNamesTheClients() {
            GrantOutcome outcome = service.grant(seededRoot(NOTES), grantVia(NOTES, webId(BOB), CLIENT_X, AccessMode.READ));

            Authorization bobs = outcome.authorizations().stream()
                    .filter(a -> a.agents().contains(URI.create(BOB))).findFirst().orElseThrow();
            assertEquals(Set.of(CLIENT_X), bobs.clients());
            assertTrue(bobs.isClientConstrained());
        }

        @Test
        @DisplayName("an unconstrained grant declares no cistern prefix and writes no cistern:client")
        void unconstrainedGrantIsUntouched() {
            GrantOutcome outcome = service.grant(seededRoot(NOTES), grant(NOTES, webId(BOB), AccessMode.READ));

            assertFalse(outcome.aclGraph().contains(null, Cistern.CLIENT));
            assertEquals(null, outcome.aclGraph().getNsPrefixURI(Cistern.PREFIX));
        }

        @Test
        @DisplayName("a constrained grant does not widen the grantee's unconstrained one: two rules, not one")
        void constrainedGrantDoesNotWidenTheUnconstrainedRule() {
            GrantOutcome first = service.grant(seededRoot(NOTES), grant(NOTES, webId(BOB), AccessMode.READ));
            EffectiveAcl afterFirst = new EffectiveAcl(first.aclGraph(), AclScope.ACCESS_TO, NOTES);

            GrantOutcome second = service.grant(afterFirst, grantVia(NOTES, webId(BOB), CLIENT_X, AccessMode.WRITE));

            long bobs = second.authorizations().stream().filter(a -> a.agents().contains(URI.create(BOB))).count();
            assertEquals(2, bobs, "read for bob; write for bob via X");
            assertEquals(EnumSet.of(AccessMode.READ, AccessMode.WRITE, AccessMode.APPEND),
                    decideEnabled(second, NOTES, via(BOB, CLIENT_X)).modes());
            AccessDecision viaY = decideEnabled(second, NOTES, via(BOB, CLIENT_Y));
            assertEquals(EnumSet.of(AccessMode.READ), viaY.modes(), "via Y: the unconstrained read only");
            assertEquals(Optional.of(DelegationTerm.CLIENT), viaY.narrowedBy());
        }

        @Test
        @DisplayName("an unconstrained grant does not merge into the grantee's constrained rule either")
        void unconstrainedGrantDoesNotMergeIntoTheConstrainedRule() {
            GrantOutcome first = service.grant(seededRoot(NOTES), grantVia(NOTES, webId(BOB), CLIENT_X, AccessMode.READ));
            EffectiveAcl afterFirst = new EffectiveAcl(first.aclGraph(), AclScope.ACCESS_TO, NOTES);

            GrantOutcome second = service.grant(afterFirst, grant(NOTES, webId(BOB), AccessMode.WRITE));

            long bobs = second.authorizations().stream().filter(a -> a.agents().contains(URI.create(BOB))).count();
            assertEquals(2, bobs);
            assertEquals(EnumSet.of(AccessMode.WRITE, AccessMode.APPEND), decideEnabled(second, NOTES, via(BOB, CLIENT_Y)).modes(),
                    "via Y: write from the unconstrained rule, no read — that one is via X only");
            assertEquals(EnumSet.of(AccessMode.READ, AccessMode.WRITE, AccessMode.APPEND),
                    decideEnabled(second, NOTES, Agent.of(URI.create(BOB))).modes(), "bob alone holds both");
        }

        @Test
        @DisplayName("the same constraint merges: read via X then write via X is one rule")
        void sameConstraintMerges() {
            GrantOutcome first = service.grant(seededRoot(NOTES), grantVia(NOTES, webId(BOB), CLIENT_X, AccessMode.READ));
            EffectiveAcl afterFirst = new EffectiveAcl(first.aclGraph(), AclScope.ACCESS_TO, NOTES);

            GrantOutcome second = service.grant(afterFirst, grantVia(NOTES, webId(BOB), CLIENT_X, AccessMode.WRITE));

            assertTrue(second.changed());
            long bobs = second.authorizations().stream().filter(a -> a.agents().contains(URI.create(BOB))).count();
            assertEquals(1, bobs, "merged, not duplicated");
            assertEquals(EnumSet.of(AccessMode.READ, AccessMode.WRITE, AccessMode.APPEND),
                    decideEnabled(second, NOTES, via(BOB, CLIENT_X)).modes());
            assertFalse(service.grant(new EffectiveAcl(second.aclGraph(), AclScope.ACCESS_TO, NOTES),
                    grantVia(NOTES, webId(BOB), CLIENT_X, AccessMode.WRITE)).changed(), "idempotent");
        }

        /** Re-stating a rule without its constraint would widen it — the one thing a delegation may not do. */
        @Test
        @DisplayName("a re-stated Control-holder keeps its client constraint")
        void restatedControlHolderKeepsItsConstraint() {
            Model root = turtle("@prefix cistern: <" + Cistern.NS + "> .\n"
                    + "<#owner> a acl:Authorization ; acl:agent <" + OWNER + "> ;\n"
                    + "  acl:accessTo <" + POD + "> ; acl:default <" + POD + "> ;\n"
                    + "  acl:mode acl:Read, acl:Write, acl:Append, acl:Control .\n"
                    + "<#admin> a acl:Authorization ; acl:agent <" + ALICE + "> ; cistern:client <" + CLIENT_X + "> ;\n"
                    + "  acl:default <" + POD + "> ; acl:mode acl:Control .", AclResource.of(ROOT));

            GrantOutcome outcome = service.grant(
                    new EffectiveAcl(root, AclScope.INHERITED, ROOT), grant(NOTES, webId(BOB), AccessMode.READ));

            assertTrue(decideEnabled(outcome, NOTES, via(ALICE, CLIENT_X)).allows(AccessMode.CONTROL), "still administers via X");
            assertTrue(decideEnabled(outcome, NOTES, Agent.of(URI.create(ALICE))).allows(AccessMode.CONTROL), "and alone");
            AccessDecision viaY = decideEnabled(outcome, NOTES, via(ALICE, CLIENT_Y));
            assertFalse(viaY.allows(AccessMode.CONTROL), "not via Y: the constraint came down with the rule");
            assertEquals(Optional.of(DelegationTerm.CLIENT), viaY.narrowedBy());
        }

        @Test
        @DisplayName("revoke takes back the constrained grant too — a revoke is everything the grantee held there")
        void revokeRemovesConstrainedRules() {
            GrantOutcome granted = service.grant(seededRoot(NOTES), grantVia(NOTES, webId(BOB), CLIENT_X, AccessMode.READ));
            EffectiveAcl afterGrant = new EffectiveAcl(granted.aclGraph(), AclScope.ACCESS_TO, NOTES);

            GrantOutcome revoked = service.revoke(afterGrant, new RevokeRequest(NOTES, webId(BOB)));

            assertTrue(revoked.changed());
            assertEquals(AccessDecision.DENIED, decideEnabled(revoked, NOTES, via(BOB, CLIENT_X)),
                    "denied outright — nothing left to cap");
            assertFalse(revoked.aclGraph().contains(null, Cistern.CLIENT));
        }

        @Test
        @DisplayName("what the server with the flag off decides: the constraint is not read, and bob via Y reads")
        void flagOffIgnoresWhatWasWritten() {
            GrantOutcome outcome = service.grant(seededRoot(NOTES), grantVia(NOTES, webId(BOB), CLIENT_X, AccessMode.READ));

            assertTrue(decide(outcome, NOTES, via(BOB, CLIENT_Y)).allows(AccessMode.READ),
                    "the test class's engine is DISABLED: a plain-WAC server applies the WebID's grant as it stands");
        }
    }
}
