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
 * what it grants, and which resources it covers.
 *
 * <p>A record rather than a bag of maps (ground rule 7) so the shape is checkable at a glance,
 * and immutable so an authorization cannot be widened after it was parsed.
 *
 * @param subject      the IRI of the authorization's subject — {@code <#owner>}, say — so a
 *                     decision can name the rule that granted it (T5.9); empty when the subject
 *                     is a blank node, which WAC permits and which then has no name to record
 * @param modes        granted modes, already closed under implication by {@link AccessMode#withImplied()}
 * @param agents       WebIDs named by {@code acl:agent}
 * @param agentClasses classes named by {@code acl:agentClass}
 * @param targets      resources named by the predicate matching the scope this was parsed under
 */
public record Authorization(
        Optional<URI> subject,
        Set<AccessMode> modes,
        Set<URI> agents,
        Set<AgentClass> agentClasses,
        Set<URI> targets) {

    public Authorization {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(modes, "modes");
        Objects.requireNonNull(agents, "agents");
        Objects.requireNonNull(agentClasses, "agentClasses");
        Objects.requireNonNull(targets, "targets");
        modes = modes.isEmpty()
                ? Collections.emptySet()
                : Collections.unmodifiableSet(EnumSet.copyOf(modes));
        agentClasses = agentClasses.isEmpty()
                ? Collections.emptySet()
                : Collections.unmodifiableSet(EnumSet.copyOf(agentClasses));
        agents = Collections.unmodifiableSet(new LinkedHashSet<>(agents));
        targets = Collections.unmodifiableSet(new LinkedHashSet<>(targets));
    }

    /** An authorization with no name — the shape a blank-node subject, or a builder that has not minted one, produces. */
    public Authorization(
            Set<AccessMode> modes, Set<URI> agents, Set<AgentClass> agentClasses, Set<URI> targets) {
        this(Optional.empty(), modes, agents, agentClasses, targets);
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
     * Whether this authorization speaks about {@code agent}, by WebID or by class.
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
}
