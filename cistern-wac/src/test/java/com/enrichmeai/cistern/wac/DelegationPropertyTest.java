package com.enrichmeai.cistern.wac;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.enrichmeai.cistern.core.Agent;
import com.enrichmeai.cistern.core.InMemoryResourceStore;
import com.enrichmeai.cistern.core.Representation;
import com.enrichmeai.cistern.core.ResourceIdentifier;
import com.enrichmeai.cistern.core.rdf.RdfIo;
import com.enrichmeai.cistern.core.vocab.Acl;
import com.enrichmeai.cistern.core.vocab.Cistern;
import com.enrichmeai.cistern.core.vocab.Foaf;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.stream.IntStream;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.vocabulary.RDF;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The two invariants ADR 0004 promises, over random ACLs and random sequences of grants
 * (T6.5). Deterministic {@link Random} per seed, in the style of
 * {@link GrantServicePropertyTest}, so a failure is reproducible from (seed, step) alone.
 *
 * <ol>
 *   <li><strong>Flag on, the cap is structural (AD-15).</strong> For every ACL, agent and
 *       client, {@code effective(agent, client) ⊆ effective(agent, no client)}; and the
 *       decision names {@link DelegationTerm#CLIENT} exactly when the inclusion is strict
 *       (AD-DEL-5), never otherwise.</li>
 *   <li><strong>Flag off, nothing changed (AD-16).</strong> For every ACL and agent, the
 *       decision equals the decision the same engine takes over the ACL with every
 *       {@code cistern:client} triple removed — the graph a server that never heard of the
 *       term would see — which in turn equals what the flag-on engine decides over that
 *       stripped graph. "Today's engine" is that stripped evaluation: the flag-off engine is
 *       byte-for-byte the pre-delegation one, so there is no second implementation to compare
 *       against, only the graph it could not have read.</li>
 * </ol>
 *
 * <p>The first half evaluates the engine over generated graphs; the second drives the same
 * invariants through the real authoring path — {@link GrantService} over
 * {@link AclDiscovery} and {@link AccessControl} on an {@link InMemoryResourceStore} — with
 * random client constraints, so what the CLI would write is what the engine caps.
 */
class DelegationPropertyTest {

    private static final String POD = "https://pod.example/";
    private static final URI OWNER = URI.create("https://owner.example/profile/card#me");
    private static final URI ALICE = URI.create("https://alice.example/profile/card#me");
    private static final URI BOB = URI.create("https://bob.example/profile/card#me");
    private static final URI CLIENT_X = URI.create("https://agents.example/claude#id");
    private static final URI CLIENT_Y = URI.create("https://agents.example/other#id");
    /** Never named by any rule: the request comes through an application nobody delegated to. */
    private static final URI CLIENT_Z = URI.create("https://agents.example/third#id");

    private static final int SEEDS = 150;
    private static final int RULES_PER_ACL_MAX = 5;
    private static final int STEPS_PER_SEED = 10;

    private static final ResourceIdentifier RESOURCE = id(POD + "notes/week");
    private static final ResourceIdentifier ELSEWHERE = id(POD + "notes/other");

    private static final List<Optional<URI>> WEB_IDS = List.of(
            Optional.of(OWNER), Optional.of(ALICE), Optional.of(BOB), Optional.empty());
    private static final List<Optional<URI>> CLIENTS = List.of(
            Optional.empty(), Optional.of(CLIENT_X), Optional.of(CLIENT_Y), Optional.of(CLIENT_Z));
    /** The client sets a rule may carry; the empty set is "unconstrained". */
    private static final List<Set<URI>> CONSTRAINTS = List.of(
            Set.of(), Set.of(), Set.of(CLIENT_X), Set.of(CLIENT_Y), Set.of(CLIENT_X, CLIENT_Y));

    private final WacEngine enabled = new WacEngine(Clock.systemUTC(), DelegationMode.ENABLED);
    private final WacEngine disabled = new WacEngine(Clock.systemUTC(), DelegationMode.DISABLED);

    private static ResourceIdentifier id(String uri) {
        return new ResourceIdentifier(URI.create(uri));
    }

    static IntStream seeds() {
        return IntStream.range(0, SEEDS);
    }

    // ---- 1 + 2 over generated graphs ----------------------------------------------------

    @ParameterizedTest(name = "seed {0}")
    @MethodSource("seeds")
    void capIsStructuralAndFlagOffIsUnchanged(int seed) {
        Random random = new Random(seed);
        Model acl = randomAcl(random);
        Model stripped = withoutClientTerms(acl);
        String at = "seed " + seed;

        for (Optional<URI> webId : WEB_IDS) {
            Agent alone = new Agent(webId, Optional.empty());
            AccessDecision aloneOn = decide(enabled, acl, alone);
            assertTrue(aloneOn.narrowedBy().isEmpty(), at + ": with no client nothing can bind");

            for (Optional<URI> client : CLIENTS) {
                Agent agent = new Agent(webId, client);
                AccessDecision on = decide(enabled, acl, agent);

                // 1. flag on: subset, and the receipt is exact
                assertTrue(aloneOn.modes().containsAll(on.modes()),
                        at + ": " + agent + " via client ⊆ alone: " + on.modes() + " ⊆ " + aloneOn.modes());
                boolean strictlyLess = !on.modes().equals(aloneOn.modes());
                assertEquals(strictlyLess, on.narrowedBy().isPresent(),
                        at + ": " + agent + " names CLIENT iff the cap removed something");
                if (!on.isDenied()) {
                    assertEquals(aloneOn.decidedBy(), on.decidedBy(), at + ": the same ACL decided");
                    assertTrue(aloneOn.authorizations().containsAll(on.authorizations()),
                            at + ": only rules alone also matched can have granted via a client");
                }

                // 2. flag off: the term is invisible
                AccessDecision off = decide(disabled, acl, agent);
                assertEquals(decide(disabled, stripped, agent), off,
                        at + ": flag off equals the decision over the ACL with cistern:client removed");
                assertEquals(decide(enabled, stripped, agent), off,
                        at + ": and with no term present the two engines agree");
                assertTrue(off.narrowedBy().isEmpty(), at + ": flag off never names a term");
                assertEquals(aloneOn.modes(), off.modes(),
                        at + ": what the person holds alone is exactly what plain WAC grants them");
            }
        }
    }

    private AccessDecision decide(WacEngine engine, Model acl, Agent agent) {
        return engine.decide(acl, RESOURCE.uri(), agent, AclScope.ACCESS_TO);
    }

    /**
     * One to five rules, each naming a random subject (a WebID, the public, any authenticated
     * agent — or, occasionally, nobody), random modes, one of two targets, and one of the client
     * sets. Built with the Jena API rather than Turtle so a rule can be assembled term by term.
     */
    private static Model randomAcl(Random random) {
        Model acl = ModelFactory.createDefaultModel();
        int rules = 1 + random.nextInt(RULES_PER_ACL_MAX);
        for (int i = 0; i < rules; i++) {
            Resource rule = acl.createResource(POD + "notes/.acl#r" + i);
            rule.addProperty(RDF.type, Acl.AUTHORIZATION);
            switch (random.nextInt(6)) {
                case 0 -> rule.addProperty(Acl.AGENT, node(OWNER));
                case 1 -> rule.addProperty(Acl.AGENT, node(ALICE));
                case 2 -> rule.addProperty(Acl.AGENT, node(BOB));
                case 3 -> rule.addProperty(Acl.AGENT_CLASS, Foaf.AGENT);
                case 4 -> rule.addProperty(Acl.AGENT_CLASS, Acl.AUTHENTICATED_AGENT);
                default -> { /* names nobody: must grant nothing, constraint or not */ }
            }
            for (AccessMode mode : randomModes(random)) {
                rule.addProperty(Acl.MODE, mode.term());
            }
            rule.addProperty(Acl.ACCESS_TO, node(random.nextBoolean() ? RESOURCE.uri() : ELSEWHERE.uri()));
            for (URI client : CONSTRAINTS.get(random.nextInt(CONSTRAINTS.size()))) {
                rule.addProperty(Cistern.CLIENT, node(client));
            }
        }
        return acl;
    }

    private static Model withoutClientTerms(Model acl) {
        Model stripped = ModelFactory.createDefaultModel();
        stripped.add(acl);
        stripped.removeAll(null, Cistern.CLIENT, null);
        return stripped;
    }

    private static Resource node(URI uri) {
        return ResourceFactory.createResource(uri.toString());
    }

    private static Set<AccessMode> randomModes(Random random) {
        Set<AccessMode> modes = EnumSet.noneOf(AccessMode.class);
        AccessMode[] all = AccessMode.values();
        do {
            modes.add(all[random.nextInt(all.length)]);
        } while (random.nextBoolean());
        return modes;
    }

    // ---- 1 through the authoring path ---------------------------------------------------

    private static final List<ResourceIdentifier> TREE = List.of(
            id(POD), id(POD + "notes/"), id(POD + "notes/week"), id(POD + "notes/deep/"), id(POD + "notes/deep/note"));
    private static final List<Grantee> GRANTEES = List.of(
            new Grantee.WebId(ALICE), new Grantee.WebId(BOB), Grantee.PUBLIC);

    @ParameterizedTest(name = "seed {0}")
    @MethodSource("seeds")
    void grantsWithClientsOnlyNarrow(int seed) {
        Random random = new Random(seed);
        Pod pod = new Pod();

        for (int step = 0; step < STEPS_PER_SEED; step++) {
            ResourceIdentifier target = TREE.get(random.nextInt(TREE.size()));
            Grantee grantee = GRANTEES.get(random.nextInt(GRANTEES.size()));
            Set<URI> clients = CONSTRAINTS.get(random.nextInt(CONSTRAINTS.size()));
            String at = "seed " + seed + " step " + step + " on " + target.uri() + " for " + grantee + " via " + clients;

            if (random.nextInt(4) > 0) {
                Set<AccessMode> modes = randomModes(random);
                GrantOutcome outcome = pod.grant(target, grantee, modes, clients);
                // What was granted is held through every named client, and alone.
                Set<AccessMode> expected = EnumSet.noneOf(AccessMode.class);
                modes.forEach(mode -> expected.addAll(mode.withImplied()));
                assertTrue(pod.decide(target, grantee.agent()).modes().containsAll(expected), at + ": alone");
                for (URI client : clients) {
                    assertTrue(pod.decide(target, via(grantee, client)).modes().containsAll(expected), at + ": via " + client);
                }
                assertFalse(pod.grant(target, grantee, modes, clients).changed(), at + ": granting again is a no-op");
                // The graph says what it means: the constraint is on the rule, beside the grantee.
                for (Authorization authorization : outcome.authorizations()) {
                    assertFalse(authorization.agents().isEmpty() && authorization.agentClasses().isEmpty(),
                            at + ": every written rule names someone in portable WAC terms");
                }
            } else {
                pod.revoke(target, grantee);
            }

            // Invariant 1, after every step, for every (agent, client) on every resource.
            for (ResourceIdentifier resource : TREE) {
                for (Optional<URI> webId : WEB_IDS) {
                    AccessDecision alone = pod.decide(resource, new Agent(webId, Optional.empty()));
                    for (Optional<URI> client : CLIENTS) {
                        AccessDecision viaClient = pod.decide(resource, new Agent(webId, client));
                        assertTrue(alone.modes().containsAll(viaClient.modes()),
                                at + ": " + webId + " via " + client + " on " + resource.uri() + " ⊆ alone");
                        assertEquals(!viaClient.modes().equals(alone.modes()), viaClient.narrowedBy().isPresent(),
                                at + ": the receipt names CLIENT iff the cap removed something");
                    }
                }
            }
            // The owner is never capped: no grant constrains them, so nothing can bind (AD-15's identity element).
            for (Optional<URI> client : CLIENTS) {
                AccessDecision owner = pod.decide(target, new Agent(Optional.of(OWNER), client));
                assertEquals(EnumSet.allOf(AccessMode.class), owner.modes(), at + ": the owner via " + client);
                assertTrue(owner.narrowedBy().isEmpty(), at);
            }
        }
    }

    private static Agent via(Grantee grantee, URI client) {
        return new Agent(grantee.agent().webId(), Optional.of(client));
    }

    /** A pod seeded like {@code OwnerPodSeeder} seeds it, with the delegation-enabled engine over it. */
    private static final class Pod {

        private final InMemoryResourceStore store = new InMemoryResourceStore();
        private final AclDiscovery discovery = new AclDiscovery(store);
        private final AccessControl accessControl =
                new AccessControl(discovery, new WacEngine(Clock.systemUTC(), DelegationMode.ENABLED));
        private final GrantService service = new GrantService();

        Pod() {
            String rootAcl = "@prefix acl: <http://www.w3.org/ns/auth/acl#> .\n"
                    + "<#owner> a acl:Authorization ;\n"
                    + "  acl:agent <" + OWNER + "> ;\n"
                    + "  acl:accessTo <" + POD + "> ; acl:default <" + POD + "> ;\n"
                    + "  acl:mode acl:Read, acl:Write, acl:Append, acl:Control .";
            store.put(AclResource.of(id(POD)),
                    new Representation(Representation.TURTLE, rootAcl.getBytes(StandardCharsets.UTF_8))).block();
        }

        GrantOutcome grant(ResourceIdentifier target, Grantee grantee, Set<AccessMode> modes, Set<URI> clients) {
            GrantOutcome outcome = service.grant(effective(target), new GrantRequest(target, grantee, modes, clients));
            persist(outcome);
            return outcome;
        }

        void revoke(ResourceIdentifier target, Grantee grantee) {
            try {
                persist(service.revoke(effective(target), new RevokeRequest(target, grantee)));
            } catch (com.enrichmeai.cistern.core.CisternException.Conflict refused) {
                // would drop Control: refused, nothing written — the invariants must still hold
            }
        }

        private void persist(GrantOutcome outcome) {
            if (outcome.changed()) {
                store.put(outcome.aclResource(), RdfIo.serialize(outcome.aclGraph(), Representation.TURTLE)).block();
            }
        }

        private EffectiveAcl effective(ResourceIdentifier target) {
            return discovery.findFor(target).block();
        }

        AccessDecision decide(ResourceIdentifier target, Agent agent) {
            return accessControl.grantedFor(target, agent).block();
        }
    }
}
