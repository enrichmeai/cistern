package com.enrichmeai.cistern.wac;

import com.enrichmeai.cistern.core.Agent;
import com.enrichmeai.cistern.core.ResourceIdentifier;
import com.enrichmeai.cistern.core.vocab.Acl;
import com.enrichmeai.cistern.core.vocab.Cistern;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.vocabulary.RDF;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Web Access Control engine: given an effective ACL graph, a target and an agent, decide
 * what that agent may do.
 *
 * <p>Deliberately narrow. It does <em>not</em> find the effective ACL (that is T5.1 discovery,
 * which walks the container hierarchy), does not know about HTTP methods or status codes (that
 * is T5.3 enforcement), and does not fetch anything. It is pure evaluation over a graph, so it
 * is exhaustively testable without a server, a store or a network — which is the point, because
 * an authorization bug is not the kind you want to find from an integration test.
 *
 * <p><strong>Deny by default.</strong> WAC has no deny rule: "access is granted when conforming
 * Authorizations are matched, otherwise access is denied". So every path that fails to match
 * returns {@link AccessDecision#DENIED}, including a malformed or empty graph. Nothing here can
 * turn a parse failure into access.
 *
 * <p><strong>Additive.</strong> Matching authorizations are unioned, per "granted by one or
 * more Authorizations". A second authorization can only ever widen the result.
 *
 * <p><strong>A delegation only narrows</strong> (ADR 0004; AD-14, AD-15, AD-16). With
 * {@link DelegationMode#ENABLED} the engine also reads Cistern's {@code cistern:client} term
 * and evaluates it as an intersection, {@code effective = accessFor(agent) ∩ accessFor(client)}:
 * {@code accessFor(agent)} is what the portable terms grant, and {@code accessFor(client)} is
 * unconstrained unless an authorization names clients. A client match is therefore never a way
 * to be allowed in — {@link Authorization#matches} decides that alone — and the result is a
 * subset of what the same WebID holds with no client, structurally rather than by review. When
 * the intersection removes something, the decision names the term that bound
 * ({@link AccessDecision#narrowedBy()}), so a receipt can tell "never had it" from "the
 * delegation capped it". With {@link DelegationMode#DISABLED} — the default — the term is not
 * read at all, and every decision is the one this engine took before delegation existed, which
 * is what keeps the extension invisible to the conformance harness (AD-16).
 *
 * <p>Both {@code decide} overloads funnel into one private narrowing path ({@link #narrow});
 * neither applies a delegation term itself (AD-DEL-6 seam 2). The {@link Clock} is a
 * constructor collaborator, not a {@code decide} parameter (seam 1): T5.8 (#92) evaluates
 * {@code cistern:validUntil} against it on the same path, and nothing in this module calls
 * {@code Instant.now()}.
 *
 * <p>Thread-safe and stateless beyond its configuration; a single instance may be shared.
 */
public final class WacEngine {

    private static final Logger log = LoggerFactory.getLogger(WacEngine.class);

    /** How a blank-node authorization is named in a log line: it has no IRI to print. */
    private static final String BLANK_SUBJECT = "[]";

    private final Clock clock;
    private final DelegationMode delegation;

    /**
     * @param clock      the time delegation terms are judged against. T5.8 reads it; the client
     *                   dimension does not, but the collaborator is fixed here so that adding
     *                   expiry never changes this signature
     * @param delegation whether Cistern's delegation terms are read and evaluated —
     *                   {@code cistern.wac.delegation.enabled}
     */
    public WacEngine(Clock clock, DelegationMode delegation) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.delegation = Objects.requireNonNull(delegation, "delegation");
    }

    /** Whether this engine reads and evaluates delegation terms. */
    public DelegationMode delegation() {
        return delegation;
    }

    /** The clock delegation terms are judged against. */
    public Clock clock() {
        return clock;
    }

    /**
     * What {@code agent} may do to the resource {@code acl} was discovered for.
     *
     * <p>The preferred entry point, because {@link EffectiveAcl} already carries the two facts
     * that have to agree — the scope and the resource the ACL is attached to — so they cannot
     * be passed inconsistently.
     */
    public AccessDecision decide(EffectiveAcl acl, Agent agent) {
        Objects.requireNonNull(acl, "acl");
        return decide(acl.graph(), acl.source().uri(), agent, acl.scope());
    }

    /**
     * What {@code agent} may do, according to {@code effectiveAcl}.
     *
     * <p><strong>{@code aclSubject} is the resource the ACL is <em>attached to</em>, which is
     * not always the resource being requested.</strong> WAC defines {@code acl:default} on the
     * <em>container</em> — "denotes a container resource whose Authorization applies to lower
     * hierarchy members" — so an inherited authorization names the ancestor, never the child.
     * Matching an inherited rule against the requested child's URI finds nothing and denies
     * every inherited grant; matching an {@code acl:accessTo} rule against an ancestor would
     * leak it downwards. Both mistakes are silent, which is why {@link #decide(EffectiveAcl,
     * Agent)} exists and this overload spells the parameter out.
     *
     * <p>Discovery makes the two cases uniform: it reports {@code source} as the target itself
     * when the ACL was the resource's own, and as the ancestor when inherited. So the correct
     * value is always {@code EffectiveAcl.source()}.
     *
     * <p>The decision names its policy (T5.9): the ACL resource is {@link AclResource#of} the
     * subject — the same derivation {@link EffectiveAcl#aclResource()} makes, so the two
     * overloads cannot name different resources for the same evaluation — and the matched
     * authorizations are the {@code acl:Authorization} subjects whose rules applied, unioned in
     * the order they were read. Nothing matched is {@link AccessDecision#DENIED}, which names
     * nothing: WAC has no deny rule, so there is no rule to blame a refusal on.
     *
     * @param effectiveAcl the ACL graph, as located by ACL discovery
     * @param aclSubject   the resource the ACL is attached to
     * @param agent        the requester; {@link Agent#ANONYMOUS} for an unauthenticated request
     * @param scope        whether the ACL was found on the resource or inherited from an ancestor
     * @return the granted modes, closed under implication, with the ACL and rules that granted
     *     them; {@link AccessDecision#DENIED} if none
     */
    public AccessDecision decide(Model effectiveAcl, URI aclSubject, Agent agent, AclScope scope) {
        Objects.requireNonNull(effectiveAcl, "effectiveAcl");
        Objects.requireNonNull(aclSubject, "aclSubject");
        Objects.requireNonNull(agent, "agent");
        Objects.requireNonNull(scope, "scope");
        return narrow(parse(effectiveAcl, scope), aclSubject, agent, scope);
    }

    /**
     * The one narrowing path (AD-DEL-6 seam 2): the union WAC defines, intersected with what
     * the delegation terms admit.
     *
     * <p>Two sets are accumulated over the authorizations that cover the subject and name the
     * agent. {@code portable} is what the WebID holds on its own — the union of every such
     * rule, which is the whole answer under plain WAC and under {@link DelegationMode#DISABLED}.
     * {@code granted} is the union of only those rules that also {@link Authorization#admits
     * admit} the client the request came through. {@code granted ⊆ portable} by construction,
     * and the two differ exactly when a delegation capped something — which is when the
     * decision names {@link DelegationTerm#CLIENT} as the term that bound (AD-DEL-5). A rule
     * excluded by its client constraint that granted nothing the other rules did not is not a
     * narrowing: the agent holds the same modes either way, and a receipt that said otherwise
     * would blame the delegation for a refusal it did not cause.
     */
    private AccessDecision narrow(
            List<Authorization> authorizations, URI aclSubject, Agent agent, AclScope scope) {
        // Additive: matching authorizations are unioned ("granted by one or more
        // Authorizations"), so a second rule can only ever widen the result — and every rule
        // that contributed is named, not just the first.
        Set<AccessMode> portable = EnumSet.noneOf(AccessMode.class);
        Set<AccessMode> granted = EnumSet.noneOf(AccessMode.class);
        Set<URI> matched = new LinkedHashSet<>();
        for (Authorization authorization : authorizations) {
            if (!authorization.covers(aclSubject) || !authorization.matches(agent)) {
                continue;
            }
            portable.addAll(authorization.modes());
            if (authorization.admits(agent)) {
                granted.addAll(authorization.modes());
                authorization.subject().ifPresent(matched::add);
            } else if (log.isDebugEnabled()) {
                log.debug(WacMessage.CLIENT_NOT_PERMITTED.format(
                        authorization.subject().map(URI::toString).orElse(BLANK_SUBJECT),
                        authorization.clients(), agent.client().map(URI::toString).orElse(BLANK_SUBJECT)));
            }
        }
        // granted ⊆ portable always; strictly smaller means the client dimension bound.
        Optional<DelegationTerm> narrowedBy = granted.equals(portable)
                ? Optional.empty()
                : Optional.of(DelegationTerm.CLIENT);
        if (granted.isEmpty() && log.isDebugEnabled()) {
            log.debug(WacMessage.NO_APPLICABLE_AUTHORIZATION.format(aclSubject, scope));
        }
        return AccessDecision.of(
                granted, AclResource.of(new ResourceIdentifier(aclSubject)), matched, narrowedBy);
    }

    /**
     * Every {@code acl:Authorization} in {@code acl}, read under {@code scope} and this engine's
     * {@link #delegation() delegation mode}.
     *
     * <p>Exposed because ACL discovery and diagnostics both want to see the parsed rules
     * without re-implementing the reading, and because it makes the parse independently
     * testable from the evaluation.
     */
    public List<Authorization> parse(Model acl, AclScope scope) {
        return parse(acl, scope, delegation);
    }

    /**
     * Every {@code acl:Authorization} in {@code acl}, read under {@code scope}, with
     * {@code cistern:client} read into {@link Authorization#clients()} only when
     * {@code delegation} is {@link DelegationMode#ENABLED enabled}.
     *
     * <p>Static, and parameterised on the mode, because reading a graph is a pure function of
     * the graph and needs no clock: the authoring surface ({@link GrantService}) reads what it
     * writes through this, with delegation always on, so that a report of an ACL names the
     * constraint the owner put in it — whether or not the server it will be sent to has
     * switched evaluation on.
     */
    public static List<Authorization> parse(Model acl, AclScope scope, DelegationMode delegation) {
        Objects.requireNonNull(acl, "acl");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(delegation, "delegation");

        List<Authorization> authorizations = new ArrayList<>();
        // Only subjects explicitly typed acl:Authorization count. WAC describes authorizations
        // as instances of that class, and requiring the type keeps unrelated triples that
        // happen to use acl: predicates from being read as grants.
        acl.listResourcesWithProperty(RDF.type, Acl.AUTHORIZATION)
                .forEachRemaining(subject -> {
                    Set<URI> targets = uris(subject, scope.targetPredicate(),
                            WacMessage.MALFORMED_TARGET_IRI);
                    if (targets.isEmpty()) {
                        // Names no resource under this scope, so it grants nothing here. This
                        // is the normal case for an acl:default rule read as ACCESS_TO, and
                        // vice versa — not an error.
                        return;
                    }
                    Set<AccessMode> modes = modes(subject);
                    if (modes.isEmpty()) {
                        return;
                    }
                    Set<URI> agents = uris(subject, Acl.AGENT, WacMessage.MALFORMED_AGENT_IRI);
                    Set<AgentClass> agentClasses = agentClasses(subject);
                    if (agents.isEmpty() && agentClasses.isEmpty()) {
                        // Names no subject, so there is nobody it could grant to. Treating an
                        // authorization with no agent as matching everyone would silently make
                        // a malformed ACL public — and an authorization whose only term is
                        // cistern:client is exactly this case: the client is a constraint on a
                        // grant, never a grantee (AD-DEL-1, AD-DEL-2), so it grants nothing.
                        return;
                    }
                    // With delegation off the term is not read, so no authorization is ever
                    // constrained and the engine is byte-for-byte the pre-delegation one.
                    Set<URI> clients = Set.of();
                    if (delegation.isEnabled()) {
                        clients = uris(subject, Cistern.CLIENT, WacMessage.MALFORMED_CLIENT_IRI);
                        if (clients.isEmpty() && subject.hasProperty(Cistern.CLIENT)) {
                            // The owner constrained this rule, and none of the constraint can
                            // be read (a literal, a malformed IRI). Skipping the bad values would
                            // leave an EMPTY set, which means unconstrained — a broken constraint
                            // turned into a wildcard, the one widening AD-15 forbids. So the
                            // rule contributes nothing: fail closed, as AD-DEL-4 has an
                            // unreadable validUntil do.
                            log.debug(WacMessage.CLIENT_CONSTRAINT_UNREADABLE.format(
                                    subjectIri(subject).map(URI::toString).orElse(BLANK_SUBJECT)));
                            return;
                        }
                    }
                    authorizations.add(new Authorization(
                            subjectIri(subject), modes, agents, agentClasses, targets, clients));
                });
        return authorizations;
    }

    /**
     * The authorization's own IRI, if it has one. A blank-node subject is legal WAC and grants
     * exactly as a named one does; it simply cannot be named in a receipt. A subject IRI that
     * does not parse as a {@link URI} is treated the same way — the rule still applies, its name
     * is just not recordable — because refusing to evaluate it would let a syntactic quirk in a
     * fragment identifier deny access the owner meant to grant.
     */
    private static Optional<URI> subjectIri(Resource subject) {
        if (!subject.isURIResource()) {
            return Optional.empty();
        }
        String iri = subject.getURI();
        try {
            return Optional.of(new URI(iri));
        } catch (URISyntaxException e) {
            log.debug(WacMessage.MALFORMED_AUTHORIZATION_IRI.format(iri));
            return Optional.empty();
        }
    }

    /** Granted modes, expanded so that Write carries Append (see {@link AccessMode}). */
    private static Set<AccessMode> modes(Resource authorization) {
        Set<AccessMode> modes = EnumSet.noneOf(AccessMode.class);
        for (Statement statement : authorization.listProperties(Acl.MODE).toList()) {
            RDFNode object = statement.getObject();
            if (!object.isURIResource()) {
                continue;
            }
            String iri = object.asResource().getURI();
            AccessMode.fromIri(iri).ifPresentOrElse(
                    mode -> modes.addAll(mode.withImplied()),
                    () -> log.debug(WacMessage.UNKNOWN_ACCESS_MODE.format(iri)));
        }
        return modes;
    }

    /** Agent classes named by {@code acl:agentClass}. */
    private static Set<AgentClass> agentClasses(Resource authorization) {
        Set<AgentClass> classes = EnumSet.noneOf(AgentClass.class);
        for (Statement statement : authorization.listProperties(Acl.AGENT_CLASS).toList()) {
            RDFNode object = statement.getObject();
            if (!object.isURIResource()) {
                continue;
            }
            String iri = object.asResource().getURI();
            AgentClass.fromIri(iri).ifPresentOrElse(
                    classes::add,
                    () -> log.debug(WacMessage.UNKNOWN_AGENT_CLASS.format(iri)));
        }
        return classes;
    }

    /**
     * URI objects of {@code predicate}. A value that is not a URI resource, or not parseable as
     * a URI, is skipped rather than failing the parse — it can then match nothing, which is the
     * safe direction. Failing the whole evaluation would be worse in one specific way: it would
     * make one bad triple deny access that other, valid authorizations grant. For
     * {@code cistern:client} the safe direction is the same one: a constraint that cannot be
     * read is a constraint nobody satisfies, never a wildcard.
     */
    private static Set<URI> uris(Resource authorization, Property predicate, WacMessage onMalformed) {
        Set<URI> uris = new LinkedHashSet<>();
        for (Statement statement : authorization.listProperties(predicate).toList()) {
            RDFNode object = statement.getObject();
            if (!object.isURIResource()) {
                continue;
            }
            String iri = object.asResource().getURI();
            try {
                uris.add(new URI(iri));
            } catch (URISyntaxException e) {
                log.debug(onMalformed.format(iri));
            }
        }
        return uris;
    }
}
