package com.enrichmeai.cistern.wac;

import com.enrichmeai.cistern.core.Agent;

import java.net.URI;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * One {@code acl:Authorization} lifted out of an ACL graph: which rule it is, who it names,
 * what it grants, which resources it covers, and — when the owner wrote a delegation — through
 * which clients it may be exercised.
 *
 * <p>A record rather than a bag of maps (ground rule 7) so the shape is checkable at a glance,
 * and immutable so an authorization cannot be widened after it was parsed.
 *
 * <p><strong>The client is a constraint, never a grantee</strong> (ADR 0004 §3, AD-DEL-2).
 * {@link #matches(Agent)} is portable Web Access Control and decides <em>who</em> this rule
 * speaks about; {@link #admits(Agent)} is Cistern's {@code cistern:client} term and can only
 * exclude a request that {@code matches} already admitted. The two are kept as separate
 * predicates so the engine can tell "no rule names you" from "a rule names you, but not through
 * that client" — which is what the receipt has to say (AD-DEL-5).
 *
 * @param subject      the IRI of the authorization's subject — {@code <#owner>}, say — so a
 *                     decision can name the rule that granted it (T5.9); empty when the subject
 *                     is a blank node, which WAC permits and which then has no name to record
 * @param modes        granted modes, already closed under implication by {@link AccessMode#withImplied()}
 * @param agents       WebIDs named by {@code acl:agent}
 * @param agentClasses classes named by {@code acl:agentClass}
 * @param targets      resources named by the predicate matching the scope this was parsed under
 * @param clients      client identifiers named by {@code cistern:client}; <strong>empty means
 *                     unconstrained</strong>, never denied (AD-15's identity element), and is
 *                     what every authorization reads as while
 *                     {@link DelegationMode#DISABLED delegation is off}
 */
public record Authorization(
        Optional<URI> subject,
        Set<AccessMode> modes,
        Set<URI> agents,
        Set<AgentClass> agentClasses,
        Set<URI> targets,
        Set<URI> clients) {

    public Authorization {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(modes, "modes");
        Objects.requireNonNull(agents, "agents");
        Objects.requireNonNull(agentClasses, "agentClasses");
        Objects.requireNonNull(targets, "targets");
        Objects.requireNonNull(clients, "clients");
        modes = modes.isEmpty()
                ? Collections.emptySet()
                : Collections.unmodifiableSet(EnumSet.copyOf(modes));
        agentClasses = agentClasses.isEmpty()
                ? Collections.emptySet()
                : Collections.unmodifiableSet(EnumSet.copyOf(agentClasses));
        agents = Collections.unmodifiableSet(new LinkedHashSet<>(agents));
        targets = Collections.unmodifiableSet(new LinkedHashSet<>(targets));
        clients = Collections.unmodifiableSet(new LinkedHashSet<>(clients));
    }

    /**
     * An unconstrained authorization with no name — the shape a blank-node subject, or a
     * builder that has not minted one, produces.
     */
    public Authorization(
            Set<AccessMode> modes, Set<URI> agents, Set<AgentClass> agentClasses, Set<URI> targets) {
        this(Optional.empty(), modes, agents, agentClasses, targets, Set.of());
    }

    /**
     * Whether this authorization speaks about {@code target}.
     *
     * <p>Compared as {@link URI} rather than as text so that equivalent spellings of the same
     * resource agree. An authorization naming no target matches nothing: WAC grants access by
     * naming a resource, so a statement that names none has granted nothing, and treating it
     * as a wildcard would turn a malformed ACL into a public one.
     */
    public boolean covers(URI target) {
        return targets.contains(Objects.requireNonNull(target, "target"));
    }

    /**
     * Whether this authorization speaks about {@code agent}, by WebID or by class — the
     * portable WAC match, and the only way to be named by a rule.
     *
     * <p>An anonymous agent can still match — via {@code foaf:Agent} — which is how public
     * resources work. It cannot match {@code acl:agent}, having no WebID to compare.
     */
    public boolean matches(Agent agent) {
        Objects.requireNonNull(agent, "agent");
        for (AgentClass agentClass : agentClasses) {
            if (agentClass.matches(agent)) {
                return true;
            }
        }
        return agent.webId().map(agents::contains).orElse(false);
    }

    /**
     * Whether the client {@code agent} came through is one this authorization may be exercised
     * through — the {@code cistern:client} constraint (ADR 0004; AD-15).
     *
     * <p>Only ever narrows. An unconstrained authorization admits every request. A request that
     * names no client at all is the agent acting as themselves, whom every authorization naming
     * them admits: strip the Cistern term and the rule is a plain grant to that WebID, which is
     * exactly what a server that does not read the term applies. A constrained authorization
     * excludes a request that names a client it does not list. Says nothing about <em>who</em>
     * is asking; that is {@link #matches(Agent)}, and a caller checks it first.
     */
    public boolean admits(Agent agent) {
        Objects.requireNonNull(agent, "agent");
        if (clients.isEmpty()) {
            return true;
        }
        return agent.client().map(clients::contains).orElse(true);
    }

    /** Whether the owner constrained this authorization to particular clients. */
    public boolean isClientConstrained() {
        return !clients.isEmpty();
    }
}
