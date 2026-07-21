package com.enrichmeai.cistern.wac;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.enrichmeai.cistern.core.Agent;

import java.io.StringReader;
import java.net.URI;
import java.util.EnumSet;
import java.util.Set;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The WAC engine against the specification's own rules and the conformance harness's
 * assertions.
 *
 * <p>The fixtures are written the way the harness writes them — it builds ACLs with
 * {@code setAgentAccess} / {@code setAuthenticatedAccess} / {@code setPublicAccess} and their
 * {@code setInheritable*} counterparts, which is {@code acl:agent}, {@code acl:agentClass
 * acl:AuthenticatedAgent}, {@code acl:agentClass foaf:Agent}, and {@code acl:accessTo} versus
 * {@code acl:default}. Nothing here is invented from memory of how WAC "should" work.
 */
class WacEngineTest {

    private static final String RESOURCE = "https://pod.example/alice/notes/hello";
    private static final String CONTAINER = "https://pod.example/alice/notes/";
    private static final String ALICE = "https://alice.example/profile/card#me";
    private static final String BOB = "https://bob.example/profile/card#me";

    private static final String PREFIXES =
            "@prefix acl: <http://www.w3.org/ns/auth/acl#> .\n"
                    + "@prefix foaf: <http://xmlns.com/foaf/0.1/> .\n";

    private final WacEngine engine = new WacEngine();

    private static Model acl(String turtle) {
        Model model = ModelFactory.createDefaultModel();
        model.read(new StringReader(PREFIXES + turtle), null, "TURTLE");
        return model;
    }

    /**
     * An authorization granting {@code modes} to {@code acl:agent} Alice on the resource.
     *
     * <p>{@code modes} is given space-separated so it survives {@code @CsvSource}, which treats
     * a comma as a column break; Turtle needs an object list, so the spaces become commas here.
     */
    private static Model agentAcl(String modes) {
        return acl("<#a> a acl:Authorization ;\n"
                + "  acl:agent <" + ALICE + "> ;\n"
                + "  acl:accessTo <" + RESOURCE + "> ;\n"
                + "  acl:mode " + modes.trim().replace(" ", ", ") + " .");
    }

    private AccessDecision decideFor(Model acl, Agent agent) {
        return engine.decide(acl, URI.create(RESOURCE), agent, AclScope.ACCESS_TO);
    }

    // ---- mode algebra -----------------------------------------------------------------

    @Nested
    @DisplayName("Append is a subclass of Write (WAC §access modes)")
    class ModeImplication {

        @Test
        @DisplayName("granting Write also grants Append")
        void writeGrantsAppend() {
            AccessDecision decision = decideFor(agentAcl("acl:Write"), Agent.of(URI.create(ALICE)));

            assertTrue(decision.allows(AccessMode.WRITE));
            assertTrue(decision.allows(AccessMode.APPEND), "Append is a subclass of Write");
        }

        @Test
        @DisplayName("granting Append does NOT grant Write")
        void appendDoesNotGrantWrite() {
            AccessDecision decision = decideFor(agentAcl("acl:Append"), Agent.of(URI.create(ALICE)));

            assertTrue(decision.allows(AccessMode.APPEND));
            assertFalse(decision.allows(AccessMode.WRITE), "the subclass relation is one-way");
        }

        /**
         * The escalation this rules out: Control governs the ACL resource, not the resource.
         * WAC calls it "access to a class of read and write operations on an ACL resource".
         */
        @Test
        @DisplayName("Control grants neither Read nor Write on the resource itself")
        void controlImpliesNothing() {
            AccessDecision decision = decideFor(agentAcl("acl:Control"), Agent.of(URI.create(ALICE)));

            assertTrue(decision.allows(AccessMode.CONTROL));
            assertFalse(decision.allows(AccessMode.READ));
            assertFalse(decision.allows(AccessMode.WRITE));
            assertFalse(decision.allows(AccessMode.APPEND));
        }

        @Test
        @DisplayName("Read grants only Read")
        void readGrantsOnlyRead() {
            AccessDecision decision = decideFor(agentAcl("acl:Read"), Agent.of(URI.create(ALICE)));

            assertEquals(EnumSet.of(AccessMode.READ), decision.modes());
        }
    }

    // ---- agent matching ---------------------------------------------------------------

    @Nested
    @DisplayName("Agent matching: acl:agent, foaf:Agent, acl:AuthenticatedAgent")
    class AgentMatching {

        @Test
        @DisplayName("acl:agent matches only the named WebID")
        void namedAgentOnly() {
            Model acl = agentAcl("acl:Read");

            assertTrue(decideFor(acl, Agent.of(URI.create(ALICE))).allows(AccessMode.READ));
            assertTrue(decideFor(acl, Agent.of(URI.create(BOB))).isDenied());
            assertTrue(decideFor(acl, Agent.ANONYMOUS).isDenied());
        }

        @Test
        @DisplayName("foaf:Agent matches everyone, including anonymous — this is 'public'")
        void publicMatchesAnonymous() {
            Model acl = acl("<#a> a acl:Authorization ;\n"
                    + "  acl:agentClass foaf:Agent ;\n"
                    + "  acl:accessTo <" + RESOURCE + "> ;\n"
                    + "  acl:mode acl:Read .");

            assertTrue(decideFor(acl, Agent.ANONYMOUS).allows(AccessMode.READ));
            assertTrue(decideFor(acl, Agent.of(URI.create(BOB))).allows(AccessMode.READ));
        }

        /**
         * The distinction that makes a members-only container members-only. The harness tests
         * exactly this: an authenticated Bob gets 200, an unauthenticated public request 401.
         */
        @Test
        @DisplayName("acl:AuthenticatedAgent matches any authenticated agent but NOT anonymous")
        void authenticatedExcludesAnonymous() {
            Model acl = acl("<#a> a acl:Authorization ;\n"
                    + "  acl:agentClass acl:AuthenticatedAgent ;\n"
                    + "  acl:accessTo <" + RESOURCE + "> ;\n"
                    + "  acl:mode acl:Read .");

            assertTrue(decideFor(acl, Agent.of(URI.create(BOB))).allows(AccessMode.READ),
                    "any authenticated agent matches, not just one named");
            assertTrue(decideFor(acl, Agent.ANONYMOUS).isDenied(),
                    "an unauthenticated request must not match acl:AuthenticatedAgent");
        }
    }

    // ---- targets and scope ------------------------------------------------------------

    @Nested
    @DisplayName("acl:accessTo vs acl:default (the inheritance distinction)")
    class Targets {

        @Test
        @DisplayName("an accessTo rule does not apply when the ACL was inherited")
        void accessToDoesNotInherit() {
            Model acl = acl("<#a> a acl:Authorization ;\n"
                    + "  acl:agent <" + ALICE + "> ;\n"
                    + "  acl:accessTo <" + CONTAINER + "> ;\n"
                    + "  acl:mode acl:Read .");

            AccessDecision inherited = engine.decide(
                    acl, URI.create(RESOURCE), Agent.of(URI.create(ALICE)), AclScope.INHERITED);

            assertTrue(inherited.isDenied(),
                    "acl:accessTo names one resource; it must not leak to children");
        }

        /**
         * {@code acl:default} names the <em>container</em> — WAC: "denotes a container
         * resource whose Authorization applies to lower hierarchy members" — so the URI to
         * match an inherited rule against is the container the ACL is attached to, never the
         * child being requested. Matching the child's URI here finds nothing and silently
         * denies every inherited grant; that was a real bug in this engine, caught by the
         * end-to-end test in {@code AclDiscoveryTest} rather than by this unit test, which
         * originally passed the container while claiming to test a child.
         */
        @Test
        @DisplayName("an inherited acl:default rule matches on the container it names")
        void defaultInherits() {
            Model acl = acl("<#a> a acl:Authorization ;\n"
                    + "  acl:agent <" + ALICE + "> ;\n"
                    + "  acl:default <" + CONTAINER + "> ;\n"
                    + "  acl:mode acl:Read .");

            AccessDecision inherited = engine.decide(
                    acl, URI.create(CONTAINER), Agent.of(URI.create(ALICE)), AclScope.INHERITED);

            assertTrue(inherited.allows(AccessMode.READ));
        }

        @Test
        @DisplayName("matching an inherited rule against the CHILD's URI grants nothing")
        void inheritedRuleIsNotMatchedAgainstTheChild() {
            Model acl = acl("<#a> a acl:Authorization ;\n"
                    + "  acl:agent <" + ALICE + "> ;\n"
                    + "  acl:default <" + CONTAINER + "> ;\n"
                    + "  acl:mode acl:Read .");

            AccessDecision wrong = engine.decide(
                    acl, URI.create(RESOURCE), Agent.of(URI.create(ALICE)), AclScope.INHERITED);

            assertTrue(wrong.isDenied(),
                    "acl:default names the container, so the child's URI is never in targets —"
                            + " callers must pass EffectiveAcl.source(), which decide(EffectiveAcl,"
                            + " Agent) does for them");
        }

        @Test
        @DisplayName("an acl:default rule alone does not govern the container's own ACL scope")
        void defaultIsNotAccessTo() {
            Model acl = acl("<#a> a acl:Authorization ;\n"
                    + "  acl:agent <" + ALICE + "> ;\n"
                    + "  acl:default <" + CONTAINER + "> ;\n"
                    + "  acl:mode acl:Read .");

            AccessDecision direct = engine.decide(
                    acl, URI.create(CONTAINER), Agent.of(URI.create(ALICE)), AclScope.ACCESS_TO);

            assertTrue(direct.isDenied(), "acl:default is the inheritable form only");
        }

        @Test
        @DisplayName("an authorization for a different resource never matches")
        void wrongTarget() {
            Model acl = acl("<#a> a acl:Authorization ;\n"
                    + "  acl:agent <" + ALICE + "> ;\n"
                    + "  acl:accessTo <https://pod.example/alice/other> ;\n"
                    + "  acl:mode acl:Read .");

            assertTrue(decideFor(acl, Agent.of(URI.create(ALICE))).isDenied());
        }
    }

    // ---- composition ------------------------------------------------------------------

    @Nested
    @DisplayName("Authorizations are additive and there is no deny")
    class Composition {

        @Test
        @DisplayName("modes from several matching authorizations are unioned")
        void unionOfMatches() {
            Model acl = acl("<#a> a acl:Authorization ;\n"
                    + "  acl:agent <" + ALICE + "> ;\n"
                    + "  acl:accessTo <" + RESOURCE + "> ;\n"
                    + "  acl:mode acl:Read .\n"
                    + "<#b> a acl:Authorization ;\n"
                    + "  acl:agentClass foaf:Agent ;\n"
                    + "  acl:accessTo <" + RESOURCE + "> ;\n"
                    + "  acl:mode acl:Append .");

            AccessDecision decision = decideFor(acl, Agent.of(URI.create(ALICE)));

            assertEquals(EnumSet.of(AccessMode.READ, AccessMode.APPEND), decision.modes(),
                    "one rule grants Read by WebID, another Append to the public");
        }

        /** "user access is implied by public" — the harness asserts this of WAC-Allow. */
        @Test
        @DisplayName("an authenticated agent inherits everything the public is granted")
        void authenticatedGetsPublicGrants() {
            Model acl = acl("<#a> a acl:Authorization ;\n"
                    + "  acl:agentClass foaf:Agent ;\n"
                    + "  acl:accessTo <" + RESOURCE + "> ;\n"
                    + "  acl:mode acl:Read .");

            assertTrue(decideFor(acl, Agent.of(URI.create(BOB))).allows(AccessMode.READ));
        }
    }

    // ---- deny by default --------------------------------------------------------------

    @Nested
    @DisplayName("Deny by default: nothing malformed may grant access")
    class DenyByDefault {

        @Test
        @DisplayName("an empty graph grants nothing")
        void emptyGraph() {
            assertTrue(decideFor(ModelFactory.createDefaultModel(), Agent.of(URI.create(ALICE)))
                    .isDenied());
        }

        @Test
        @DisplayName("an authorization naming no agent grants nothing (not everyone)")
        void noSubjectIsNotEveryone() {
            Model acl = acl("<#a> a acl:Authorization ;\n"
                    + "  acl:accessTo <" + RESOURCE + "> ;\n"
                    + "  acl:mode acl:Read .");

            assertTrue(decideFor(acl, Agent.of(URI.create(ALICE))).isDenied());
            assertTrue(decideFor(acl, Agent.ANONYMOUS).isDenied());
        }

        @Test
        @DisplayName("an authorization naming no target grants nothing (not everything)")
        void noTargetIsNotEverything() {
            Model acl = acl("<#a> a acl:Authorization ;\n"
                    + "  acl:agent <" + ALICE + "> ;\n"
                    + "  acl:mode acl:Read .");

            assertTrue(decideFor(acl, Agent.of(URI.create(ALICE))).isDenied());
        }

        @Test
        @DisplayName("triples using acl: predicates without the Authorization type are ignored")
        void untypedIsNotAnAuthorization() {
            Model acl = acl("<#a> acl:agent <" + ALICE + "> ;\n"
                    + "  acl:accessTo <" + RESOURCE + "> ;\n"
                    + "  acl:mode acl:Read .");

            assertTrue(decideFor(acl, Agent.of(URI.create(ALICE))).isDenied());
        }

        @Test
        @DisplayName("an unrecognised mode is ignored rather than granting anything")
        void unknownModeIgnored() {
            Model acl = acl("<#a> a acl:Authorization ;\n"
                    + "  acl:agent <" + ALICE + "> ;\n"
                    + "  acl:accessTo <" + RESOURCE + "> ;\n"
                    + "  acl:mode <http://example.org/acl#Superuser> .");

            assertTrue(decideFor(acl, Agent.of(URI.create(ALICE))).isDenied());
        }

        @Test
        @DisplayName("an unrecognised agent class is ignored rather than matching")
        void unknownAgentClassIgnored() {
            Model acl = acl("<#a> a acl:Authorization ;\n"
                    + "  acl:agentClass <http://example.org/acl#Everyone> ;\n"
                    + "  acl:accessTo <" + RESOURCE + "> ;\n"
                    + "  acl:mode acl:Read .");

            assertTrue(decideFor(acl, Agent.ANONYMOUS).isDenied());
        }

        /** One malformed triple must not deny what other valid authorizations grant. */
        @Test
        @DisplayName("a malformed authorization does not suppress a valid one")
        void malformedDoesNotSuppressValid() {
            Model acl = acl("<#bad> a acl:Authorization ;\n"
                    + "  acl:agent <" + ALICE + "> ;\n"
                    + "  acl:accessTo <" + RESOURCE + "> ;\n"
                    + "  acl:mode <http://example.org/acl#Nonsense> .\n"
                    + "<#good> a acl:Authorization ;\n"
                    + "  acl:agent <" + ALICE + "> ;\n"
                    + "  acl:accessTo <" + RESOURCE + "> ;\n"
                    + "  acl:mode acl:Read .");

            assertTrue(decideFor(acl, Agent.of(URI.create(ALICE))).allows(AccessMode.READ));
        }
    }

    // ---- WAC-Allow rendering ----------------------------------------------------------

    /**
     * The mode lists the harness expects to see in {@code WAC-Allow}. Its own table marks the
     * {@code read/write} row as an inexact match precisely because append appears alongside —
     * that row is the reason {@link AccessMode#withImplied()} exists.
     */
    @ParameterizedTest(name = "{0} renders as \"{1}\"")
    @CsvSource({
        "acl:Read,                        read",
        "acl:Read acl:Control,            read control",
        "acl:Read acl:Write,              read write append",
        "acl:Read acl:Append,             read append",
        "acl:Read acl:Write acl:Append,   read write append",
    })
    @DisplayName("WAC-Allow mode lists match the harness's expectations")
    void headerModes(String modes, String expected) {
        AccessDecision decision = decideFor(agentAcl(modes), Agent.of(URI.create(ALICE)));

        assertEquals(expected, decision.toHeaderModes());
    }

    // ---- invariants -------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(AccessMode.class)
    @DisplayName("every mode round-trips through its IRI")
    void modesRoundTrip(AccessMode mode) {
        assertEquals(mode, AccessMode.fromIri(mode.iri()).orElseThrow());
    }

    @Test
    @DisplayName("a decision is immutable — its modes cannot be widened after the fact")
    void decisionIsImmutable() {
        AccessDecision decision = decideFor(agentAcl("acl:Read"), Agent.of(URI.create(ALICE)));
        Set<AccessMode> modes = decision.modes();

        org.junit.jupiter.api.Assertions.assertThrows(
                UnsupportedOperationException.class, () -> modes.add(AccessMode.WRITE));
    }
}
