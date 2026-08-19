package com.enrichmeai.cistern.wac;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.enrichmeai.cistern.core.Agent;
import com.enrichmeai.cistern.core.CisternException;
import com.enrichmeai.cistern.core.InMemoryResourceStore;
import com.enrichmeai.cistern.core.Representation;
import com.enrichmeai.cistern.core.ResourceIdentifier;
import com.enrichmeai.cistern.core.rdf.RdfIo;
import com.enrichmeai.cistern.core.vocab.Acl;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.IntStream;

import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.vocabulary.RDF;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The invariants T5.7 promises, over random sequences of grants and revokes on random targets
 * in a small tree — driven end to end through the real components: {@link AclDiscovery} finds
 * the effective ACL in an {@link InMemoryResourceStore}, {@link GrantService} transforms it,
 * the result is serialised through {@link RdfIo} and stored, and {@link AccessControl} says
 * what everyone may now do. Deterministic {@link Random} per seed, in the style of
 * {@code RdfModelGenerator}, so a failure is reproducible from (seed, step) alone.
 *
 * <ol>
 *   <li><strong>Control is never lost.</strong> Every (agent, resource) pair that held Control
 *       before an operation still holds it after — whether the operation was a grant, a revoke,
 *       or a refused revoke.</li>
 *   <li><strong>Container grants carry {@code acl:accessTo} and {@code acl:default}; document
 *       grants never carry {@code acl:default}.</strong></li>
 *   <li>A grant takes effect: the grantee holds at least the requested modes on the target, and
 *       on every descendant the target's ACL governs.</li>
 *   <li>A revoke takes effect: no authorization governing the target names the grantee.</li>
 *   <li>Granting again what was just granted changes nothing.</li>
 * </ol>
 */
class GrantServicePropertyTest {

    private static final String POD = "https://pod.example/";
    private static final URI OWNER = URI.create("https://owner.example/profile/card#me");
    private static final URI ALICE = URI.create("https://alice.example/profile/card#me");
    private static final URI BOB = URI.create("https://bob.example/profile/card#me");

    private static final int SEEDS = 120;
    private static final int STEPS_PER_SEED = 12;

    /** A small tree: containers and documents at several depths, so grants and inheritance interleave. */
    private static final List<ResourceIdentifier> TREE = List.of(
            id(POD),
            id(POD + "a/"), id(POD + "a/x"), id(POD + "a/b/"), id(POD + "a/b/y"),
            id(POD + "c/"), id(POD + "c/z"),
            id(POD + "w"));

    private static final List<Grantee> GRANTEES = List.of(
            new Grantee.WebId(OWNER), new Grantee.WebId(ALICE), new Grantee.WebId(BOB), Grantee.PUBLIC);

    private static final List<Agent> AGENTS = List.of(
            Agent.of(OWNER), Agent.of(ALICE), Agent.of(BOB), Agent.ANONYMOUS);

    private static ResourceIdentifier id(String uri) {
        return new ResourceIdentifier(URI.create(uri));
    }

    static IntStream seeds() {
        return IntStream.range(0, SEEDS);
    }

    @ParameterizedTest(name = "seed {0}")
    @MethodSource("seeds")
    void invariantsHoldOverRandomSequences(int seed) {
        Random random = new Random(seed);
        Pod pod = new Pod();

        for (int step = 0; step < STEPS_PER_SEED; step++) {
            ResourceIdentifier target = TREE.get(random.nextInt(TREE.size()));
            Grantee grantee = GRANTEES.get(random.nextInt(GRANTEES.size()));
            Map<Pair, Boolean> controlBefore = pod.controlHolders();
            String at = "seed " + seed + " step " + step + " on " + target.uri() + " for " + grantee;

            if (random.nextBoolean()) {
                Set<AccessMode> modes = randomModes(random);
                GrantOutcome outcome = pod.grant(target, grantee, modes);
                assertGrantShape(outcome, target, grantee, at);
                assertGrantEffective(pod, target, grantee, modes, at);
                assertFalse(pod.grant(target, grantee, modes).changed(), at + ": granting again is a no-op");
            } else {
                boolean revoked = pod.revoke(target, grantee);
                if (revoked) {
                    assertRevokeEffective(pod, target, grantee, at);
                }
            }
            assertControlKept(controlBefore, pod.controlHolders(), at);
        }
    }

    // ---- the properties ---------------------------------------------------------------

    private static void assertControlKept(Map<Pair, Boolean> before, Map<Pair, Boolean> after, String at) {
        before.forEach((pair, held) -> {
            if (held) {
                assertTrue(after.get(pair), at + ": " + pair + " held Control before and must still");
            }
        });
    }

    /** Property 2, read straight off the graph the service asked to have written. */
    private static void assertGrantShape(GrantOutcome outcome, ResourceIdentifier target, Grantee grantee, String at) {
        if (!outcome.changed()) {
            return;
        }
        Resource targetNode = ResourceFactory.createResource(target.uri().toString());
        List<Resource> own = new ArrayList<>();
        outcome.aclGraph().listResourcesWithProperty(RDF.type, Acl.AUTHORIZATION).forEachRemaining(subject -> {
            if (subject.hasProperty(grantee.predicate(), grantee.term()) && subject.hasProperty(Acl.ACCESS_TO, targetNode)) {
                own.add(subject);
            }
        });
        assertFalse(own.isEmpty(), at + ": the grantee has an authorization naming the target by acl:accessTo");
        for (Resource authorization : own) {
            assertEquals(target.isContainer(), authorization.hasProperty(Acl.DEFAULT, targetNode),
                    at + ": acl:default present iff the target is a container");
        }
        if (!target.isContainer()) {
            assertFalse(outcome.aclGraph().contains(null, Acl.DEFAULT),
                    at + ": a document's ACL says acl:default nowhere");
        }
    }

    /** Property 3, evaluated the way a request would be. */
    private static void assertGrantEffective(
            Pod pod, ResourceIdentifier target, Grantee grantee, Set<AccessMode> modes, String at) {
        Set<AccessMode> expected = EnumSet.noneOf(AccessMode.class);
        modes.forEach(mode -> expected.addAll(mode.withImplied()));
        assertTrue(pod.decide(target, grantee.agent()).modes().containsAll(expected),
                at + ": the grantee holds the requested modes on the target");
        if (target.isContainer()) {
            for (ResourceIdentifier descendant : TREE) {
                if (descendant.equals(target) || !descendant.uri().toString().startsWith(target.uri().toString())) {
                    continue;
                }
                if (pod.governedBy(descendant).equals(target)) {
                    assertTrue(pod.decide(descendant, grantee.agent()).modes().containsAll(expected),
                            at + ": the grant reaches " + descendant.uri() + " through acl:default");
                }
            }
        }
    }

    /** Property 4: nothing governing the target names the grantee any more. */
    private static void assertRevokeEffective(Pod pod, ResourceIdentifier target, Grantee grantee, String at) {
        for (Authorization authorization : pod.governing(target)) {
            boolean names = switch (grantee) {
                case Grantee.WebId webId -> authorization.agents().contains(webId.webId());
                case Grantee.Public _ -> authorization.agentClasses().contains(AgentClass.PUBLIC);
            };
            assertFalse(names, at + ": " + authorization + " still names the revoked grantee");
        }
    }

    private static Set<AccessMode> randomModes(Random random) {
        Set<AccessMode> modes = EnumSet.noneOf(AccessMode.class);
        AccessMode[] all = AccessMode.values();
        do {
            modes.add(all[random.nextInt(all.length)]);
        } while (random.nextBoolean());
        return modes;
    }

    private record Pair(Agent agent, ResourceIdentifier resource) {
    }

    /** A pod: a store seeded like {@code OwnerPodSeeder} seeds it, and the real discovery + engine over it. */
    private static final class Pod {

        private final InMemoryResourceStore store = new InMemoryResourceStore();
        private final AclDiscovery discovery = new AclDiscovery(store);
        private final WacEngine engine = new WacEngine();
        private final AccessControl accessControl = new AccessControl(discovery, engine);
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

        GrantOutcome grant(ResourceIdentifier target, Grantee grantee, Set<AccessMode> modes) {
            GrantOutcome outcome = service.grant(effective(target), new GrantRequest(target, grantee, modes));
            persist(outcome);
            return outcome;
        }

        /** @return whether the revoke was performed (false when refused for holding Control) */
        boolean revoke(ResourceIdentifier target, Grantee grantee) {
            try {
                persist(service.revoke(effective(target), new RevokeRequest(target, grantee)));
                return true;
            } catch (CisternException.Conflict refused) {
                return false;
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

        ResourceIdentifier governedBy(ResourceIdentifier target) {
            return effective(target).source();
        }

        AccessDecision decide(ResourceIdentifier target, Agent agent) {
            return accessControl.grantedFor(target, agent).block();
        }

        List<Authorization> governing(ResourceIdentifier target) {
            EffectiveAcl acl = effective(target);
            return engine.parse(acl.graph(), acl.scope()).stream()
                    .filter(authorization -> authorization.covers(acl.source().uri()))
                    .toList();
        }

        Map<Pair, Boolean> controlHolders() {
            Map<Pair, Boolean> holders = new HashMap<>();
            for (ResourceIdentifier resource : TREE) {
                for (Agent agent : AGENTS) {
                    holders.put(new Pair(agent, resource), decide(resource, agent).allows(AccessMode.CONTROL));
                }
            }
            return holders;
        }
    }
}
