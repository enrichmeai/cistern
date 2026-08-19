package com.enrichmeai.cistern.cli;

import com.enrichmeai.cistern.core.Agent;
import com.enrichmeai.cistern.core.ResourceIdentifier;
import com.enrichmeai.cistern.wac.AccessMode;
import com.enrichmeai.cistern.wac.AclResource;
import com.enrichmeai.cistern.wac.AclScope;
import com.enrichmeai.cistern.wac.AgentClass;
import com.enrichmeai.cistern.wac.Authorization;
import com.enrichmeai.cistern.wac.GrantOutcome;
import com.enrichmeai.cistern.wac.Grantee;
import com.enrichmeai.cistern.wac.PodProvisioned;
import com.enrichmeai.cistern.wac.PodProvisioner;
import com.enrichmeai.cistern.wac.PodSpec;
import com.enrichmeai.cistern.wac.WacEngine;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.StringJoiner;

/**
 * What a command prints: a one-line verdict, then what the governing ACL now says, in plain
 * language — because the point of the tool is that the owner can read the rule they just wrote
 * without knowing what an {@code acl:default} triple is.
 *
 * <p>Everything reported is read back off the graph the service produced, through the same
 * {@link WacEngine} the server enforces with; nothing is echoed from the request. So if the
 * report says "anyone: read — this container and everything inside it", that is what the engine
 * will decide.
 */
final class AclReport {

    private final PodBase base;
    private final WacEngine engine = new WacEngine();

    AclReport(PodBase base) {
        this.base = Objects.requireNonNull(base, "base");
    }

    /** The lines for a grant of {@code modes} to {@code grantee} on {@code target}. */
    List<String> grant(GrantOutcome outcome, ResourceIdentifier target, Grantee grantee, Set<AccessMode> modes) {
        List<String> lines = new ArrayList<>();
        lines.add(outcome.changed()
                ? CliMessage.GRANTED.format(name(grantee), modes(modes), targetWords(target))
                : CliMessage.ALREADY_GRANTED.format(name(grantee), modes(modes), base.display(target)));
        lines.addAll(holdings(outcome, target));
        return lines;
    }

    /** The lines for a revoke of {@code grantee} on {@code target}. */
    List<String> revoke(GrantOutcome outcome, ResourceIdentifier target, Grantee grantee) {
        List<String> lines = new ArrayList<>();
        lines.add(outcome.changed()
                ? CliMessage.REVOKED.format(name(grantee), base.display(target))
                : CliMessage.NOTHING_TO_REVOKE.format(name(grantee), base.display(target)));
        lines.addAll(holdings(outcome, target));
        return lines;
    }

    /**
     * The lines for provisioning {@code spec}. What a created pod's ACL grants is read back off
     * the graph the provisioner writes, through the engine, like every other report here.
     */
    List<String> provisioned(PodProvisioned outcome, PodSpec spec) {
        return switch (outcome) {
            case PodProvisioned.Created created -> List.of(CliMessage.POD_CREATED.format(
                    base.display(created.root()), spec.ownerWebId(), base.display(created.acl()),
                    modes(ownerModes(spec))));
            case PodProvisioned.AlreadyExists existing -> List.of(CliMessage.POD_ALREADY_EXISTS.format(
                    base.display(existing.root()), base.display(spec.acl())));
        };
    }

    /** What the written ACL grants its owner on the root — asked of the engine, not assumed. */
    private Set<AccessMode> ownerModes(PodSpec spec) {
        return engine.decide(PodProvisioner.ownerAclGraph(spec), spec.root().uri(),
                Agent.of(spec.ownerWebId()), AclScope.ACCESS_TO).modes();
    }

    // ---- what the ACL says ---------------------------------------------------------------

    private List<String> holdings(GrantOutcome outcome, ResourceIdentifier target) {
        ResourceIdentifier governed = AclResource.governedBy(outcome.aclResource());
        boolean own = governed.equals(target);
        List<String> lines = new ArrayList<>();
        lines.add(own
                ? CliMessage.ACL_HOLDS.format(base.display(outcome.aclResource()))
                : CliMessage.ACL_HOLDS_INHERITED.format(base.display(outcome.aclResource()), base.display(target)));
        Set<Authorization> inheritable = inheritable(outcome, governed);
        // In a stable order — the graph has none — so two runs print the same report.
        List<Authorization> ordered = new ArrayList<>(outcome.authorizations());
        ordered.sort(Comparator.comparing(AclReport::who).thenComparing(a -> modes(a.modes())));
        for (Authorization authorization : ordered) {
            lines.add(CliMessage.AUTHORIZATION_LINE.format(
                    who(authorization), modes(authorization.modes()), scope(authorization, own, target, inheritable)));
        }
        return lines;
    }

    /**
     * The authorizations that also apply below {@code governed}: those the engine would read
     * under {@link AclScope#INHERITED}. An authorization naming the container by both
     * {@code acl:accessTo} and {@code acl:default} parses to the same record either way, so
     * membership here is exactly "the same rule reaches the children".
     */
    private Set<Authorization> inheritable(GrantOutcome outcome, ResourceIdentifier governed) {
        return Set.copyOf(engine.parse(outcome.aclGraph(), AclScope.INHERITED).stream()
                .filter(authorization -> authorization.covers(governed.uri()))
                .toList());
    }

    private static String scope(Authorization authorization, boolean own, ResourceIdentifier target,
                                Set<Authorization> inheritable) {
        if (!own) {
            return CliMessage.SCOPE_INHERITED.format();
        }
        if (!target.isContainer()) {
            return CliMessage.SCOPE_DOCUMENT.format();
        }
        return inheritable.contains(authorization)
                ? CliMessage.SCOPE_INHERITABLE.format()
                : CliMessage.SCOPE_RESOURCE_ONLY.format();
    }

    // ---- words -----------------------------------------------------------------------------

    private String targetWords(ResourceIdentifier target) {
        return target.isContainer()
                ? CliMessage.TARGET_CONTAINER.format(base.display(target))
                : base.display(target);
    }

    private static String name(Grantee grantee) {
        return switch (grantee) {
            case Grantee.WebId webId -> webId.webId().toString();
            case Grantee.Public _ -> CliMessage.ANYONE.format();
        };
    }

    private static String who(Authorization authorization) {
        StringJoiner names = new StringJoiner(CliMessage.LIST_SEPARATOR.format());
        for (AgentClass agentClass : authorization.agentClasses()) {
            names.add(switch (agentClass) {
                case PUBLIC -> CliMessage.ANYONE.format();
                case AUTHENTICATED -> CliMessage.ANY_AUTHENTICATED_AGENT.format();
            });
        }
        for (URI agent : authorization.agents()) {
            names.add(agent.toString());
        }
        return names.toString();
    }

    /** Modes in declaration order, by their lower-case names — the same tokens {@code WAC-Allow} uses. */
    private static String modes(Set<AccessMode> modes) {
        StringJoiner words = new StringJoiner(CliMessage.LIST_SEPARATOR.format());
        for (AccessMode mode : AccessMode.values()) {
            if (modes.contains(mode)) {
                words.add(mode.headerToken());
            }
        }
        return words.toString();
    }
}
