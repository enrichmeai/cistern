package com.enrichmeai.cistern.wac;

import com.enrichmeai.cistern.core.Agent;
import com.enrichmeai.cistern.core.ResourceIdentifier;

import java.util.List;
import java.util.Objects;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Discovery plus evaluation: the one entry point the HTTP layer calls.
 *
 * <p>Exists so that cistern-webflux stays thin. Deciding <em>which</em> resources a method
 * touches, finding each one's ACL and combining the verdicts is authorization logic, and it
 * belongs beside the engine rather than inside a filter.
 *
 * <p>Every method here fails closed. A missing ACL, an unparseable one, or a requirement whose
 * decision cannot be obtained all end as "not allowed"; nothing turns an error into access.
 */
public final class AccessControl {

    private final AclDiscovery discovery;
    private final WacEngine engine;

    public AccessControl(AclDiscovery discovery, WacEngine engine) {
        this.discovery = Objects.requireNonNull(discovery, "discovery");
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    /**
     * Whether {@code agent} may perform {@code method} on {@code target}.
     *
     * <p><strong>Every</strong> requirement must hold, not any — a {@code DELETE} needs Write
     * on both the resource and its parent, and satisfying one is not satisfying the request.
     *
     * @return true only if all requirements are granted; false if any is not, including when
     *     no ACL governs the resource at all
     */
    public Mono<Boolean> isAllowed(String method, ResourceIdentifier target, Agent agent) {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(agent, "agent");

        List<AccessRequirement> requirements = RequiredAccess.forRequest(method, target);
        return Flux.fromIterable(requirements)
                .concatMap(requirement -> grantedFor(requirement.target(), agent)
                        .map(decision -> decision.allows(requirement.mode())))
                // all(), so one unsatisfied requirement fails the request. An empty decision
                // stream cannot occur — grantedFor always emits — but all() over empty is
                // true, so this is stated rather than relied on.
                .all(Boolean::booleanValue)
                .defaultIfEmpty(false);
    }

    /**
     * What {@code agent} is granted on {@code target} — the value the {@code WAC-Allow} header
     * reports, and the reason {@link WacEngine} returns a set rather than a boolean.
     *
     * @return the granted modes, or {@link AccessDecision#DENIED} if no ACL governs the
     *     resource. Never empty: absence of an ACL is a denial, not a missing answer.
     */
    public Mono<AccessDecision> grantedFor(ResourceIdentifier target, Agent agent) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(agent, "agent");

        return discovery.findFor(target)
                .map(acl -> engine.decide(acl, agent))
                .defaultIfEmpty(AccessDecision.DENIED);
    }

    /**
     * The ACL resource governing {@code target}, for the {@code Link: rel="acl"} header.
     *
     * <p>Falls back to the target's own ACL URI when none exists yet, because the header
     * advertises <em>where the ACL would live</em> — a client needs that URI precisely in
     * order to create one.
     */
    public Mono<ResourceIdentifier> aclResourceFor(ResourceIdentifier target) {
        Objects.requireNonNull(target, "target");
        return discovery.findFor(target)
                .map(EffectiveAcl::aclResource)
                .defaultIfEmpty(AclResource.of(target));
    }
}
