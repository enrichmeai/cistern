package com.enrichmeai.cistern.wac;

import com.enrichmeai.cistern.core.CisternException;
import com.enrichmeai.cistern.core.ResourceIdentifier;
import com.enrichmeai.cistern.core.vocab.Acl;
import com.enrichmeai.cistern.core.vocab.Cistern;
import com.enrichmeai.cistern.core.vocab.Foaf;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.vocabulary.RDF;

/**
 * Authors grants and revocations as edits to an ACL graph (T5.7; delegation under T6.5).
 *
 * <p>Pure: no I/O, no Spring, no store. Given the ACL that governs a resource today (as
 * {@link AclDiscovery} finds it) and a request, it returns the graph that should govern the
 * resource afterwards and where to write it. The caller persists — the CLI over HTTP with the
 * caller's own credential, so that <em>the server</em> enforces {@code acl:Control}; an
 * embedding application against its {@code ResourceStore}. Keeping the transformation pure is
 * what makes its invariants property-testable without a server, and it is the invariants that
 * matter, because both mistakes an ACL author can make fail <em>silently into denial</em>:
 *
 * <ul>
 *   <li><strong>A resource-level ACL replaces inheritance.</strong> When the target inherits its
 *       ACL from an ancestor, the new {@code <target>.acl} starts by re-stating every inherited
 *       authorization that holds {@code acl:Control}, rewritten to name the target — so nobody
 *       who could administer this resource before is locked out of it after. Inherited
 *       authorizations <em>without</em> Control are not carried down: a grant on a container is
 *       a statement about that container, and pinning every ancestor rule to it would make
 *       "read this matter, not the pod" impossible to say. The outcome lists what now applies,
 *       so the narrowing is visible rather than silent.</li>
 *   <li><strong>{@code acl:default} names the container.</strong> A grant on a container always
 *       carries both {@code acl:accessTo} and {@code acl:default}, because without the second
 *       everything inside is unreachable; a grant on a document carries {@code acl:accessTo}
 *       only, because {@code acl:default} is defined for containers.</li>
 *   <li><strong>Control is never taken away here.</strong> A revoke that would remove an
 *       authorization granting Control is refused ({@link CisternException.Conflict}) rather
 *       than performed. Every agent who held Control on a resource before any sequence of
 *       grants and revokes still holds it after — the property test states exactly that.</li>
 *   <li><strong>A client constraint is written beside the grantee, never instead of it</strong>
 *       (ADR 0004, AD-DEL-1, AD-DEL-2). A {@link GrantRequest} with clients writes
 *       {@code cistern:client} triples onto an authorization that still names the grantee by
 *       {@code acl:agent} or {@code acl:agentClass}, so a server that does not read the term
 *       applies the WebID's grant as it stands. A constrained grant merges only into an
 *       authorization with exactly the same constraint: widening an unconstrained rule with a
 *       mode the owner meant for one client would grant it through every client, and adding a
 *       client to a shared rule would narrow someone else's grant. A re-stated Control-holder
 *       keeps its constraint, for the same reason it keeps its modes — carrying the rule down
 *       without it would widen it. A request with clients and no grantee is refused by
 *       {@link GrantRequest} itself, before anything here runs.</li>
 * </ul>
 *
 * <p>Edits are made to the graph, not to a lossy model of it: an authorization the owner wrote
 * by hand keeps whatever else it says ({@code acl:agentGroup}, an {@code rdfs:comment}, a mode
 * this server does not evaluate). Only the triples a request is about are added or removed.
 *
 * <p>What the outcome reports is read back through {@link WacEngine#parse(Model, AclScope,
 * DelegationMode)} with delegation {@link DelegationMode#ENABLED enabled}, whatever the server
 * that will enforce the ACL has configured: this service writes the constraint, so its report
 * of the graph names it. Whether a given server <em>evaluates</em> it is that server's
 * {@code cistern.wac.delegation.enabled}, and the CLI's option text says so.
 *
 * <p>Thread-safe and stateless; a single instance may be shared.
 */
public final class GrantService {

    /** Fragment of the authorization written for the public: {@code <target>.acl#public}. */
    static final String PUBLIC_FRAGMENT = "public";

    /** Fragment base for an authorization written for a WebID: {@code #agent}, {@code #agent-2}, … */
    static final String AGENT_FRAGMENT = "agent";

    /** Fragment base for a re-stated Control-holder whose original had no fragment of its own. */
    static final String CONTROL_FRAGMENT = "control";

    private static final String FRAGMENT_SEPARATOR = "#";
    private static final String DISAMBIGUATOR_SEPARATOR = "-";
    private static final int FIRST_DISAMBIGUATOR = 2;

    /** The predicates that say <em>who</em> an authorization is for. */
    private static final List<Property> SUBJECT_PREDICATES =
            List.of(Acl.AGENT, Acl.AGENT_CLASS, Acl.AGENT_GROUP);

    /** The predicates that say <em>what</em> an authorization covers. */
    private static final List<Property> TARGET_PREDICATES = List.of(Acl.ACCESS_TO, Acl.DEFAULT);

    /**
     * What is carried over when an inherited Control-holder is re-stated on the target: who,
     * what modes, and every Cistern constraint — re-stating a rule without its constraint would
     * widen it (AD-15).
     */
    private static final List<Property> RESTATED_PREDICATES =
            List.of(Acl.AGENT, Acl.AGENT_CLASS, Acl.AGENT_GROUP, Acl.MODE, Cistern.CLIENT);

    private static final Comparator<Resource> STABLE_ORDER = Comparator.comparing(Resource::toString);

    /**
     * The ACL that should govern {@code request.target()} once the grant is applied.
     *
     * <p>Merges into the grantee's existing authorization for the target when there is one
     * that names exactly this grantee, exactly this target and exactly this client constraint;
     * otherwise adds a new one. WAC composes authorizations additively, so a shared
     * authorization ("Alice and Bob may read") is left alone and the grantee gets their own —
     * the effective modes are the union either way, and widening a rule that also names someone
     * else would be a grant to them. The same holds across constraints: a rule for "Alice via
     * one client" and a rule for "Alice" are different grants, and each keeps its own.
     *
     * @param current the ACL that governs the target today, as discovery found it — the
     *                target's own, or an ancestor's under {@link AclScope#INHERITED}
     * @param request what to grant
     * @return the graph to write at {@code <target>.acl}, or (unchanged) the ACL as it stands if
     *     the grantee already held every requested mode there under the same constraint
     * @throws IllegalArgumentException if {@code current} cannot govern the target
     */
    public GrantOutcome grant(EffectiveAcl current, GrantRequest request) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(request, "request");
        ResourceIdentifier target = request.target();
        requireGoverns(current, target);

        ResourceIdentifier aclResource = AclResource.of(target);
        Model acl = baseline(current, target, aclResource);
        Resource targetNode = acl.createResource(target.uri().toString());
        Set<RDFNode> clientTerms = clientTerms(request);
        boolean changed = !isOwn(current, target);

        Optional<Resource> existing = ownAuthorization(acl, request.grantee(), targetNode, clientTerms);
        Resource authorization;
        if (existing.isPresent()) {
            authorization = existing.get();
        } else {
            if (request.isClientConstrained()) {
                acl.setNsPrefix(Cistern.PREFIX, Cistern.NS);
            }
            authorization = newAuthorization(acl, aclResource, request.grantee(), targetNode, clientTerms);
            changed = true;
        }
        for (AccessMode mode : request.modes()) {
            if (!authorization.hasProperty(Acl.MODE, mode.term())) {
                authorization.addProperty(Acl.MODE, mode.term());
                changed = true;
            }
        }
        if (target.isContainer() && !authorization.hasProperty(Acl.DEFAULT, targetNode)) {
            authorization.addProperty(Acl.DEFAULT, targetNode);
            changed = true;
        }
        return changed ? written(aclResource, acl, target) : unchanged(current);
    }

    /**
     * The ACL that should govern {@code request.target()} once the grantee's authorizations for
     * it are removed.
     *
     * <p>Removes exactly the grantee's authority on exactly this target and nothing else —
     * constrained and unconstrained alike, since a revoke takes back everything the grantee
     * was granted there. An authorization that also names other agents keeps them; one that
     * also covers other resources keeps covering them for the grantee — the graph is split so
     * that every other (agent, resource) pair reads the same before and after.
     *
     * @return the graph to write at {@code <target>.acl}, or (unchanged) the ACL as it stands if
     *     the grantee held nothing on the target
     * @throws CisternException.Conflict if any authorization being revoked grants Control
     * @throws IllegalArgumentException  if {@code current} cannot govern the target
     */
    public GrantOutcome revoke(EffectiveAcl current, RevokeRequest request) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(request, "request");
        ResourceIdentifier target = request.target();
        requireGoverns(current, target);
        Grantee grantee = request.grantee();

        // What the grantee holds today is read from the effective ACL under its own scope: for
        // an inherited ACL that is the ancestor's acl:default rules, which the re-stated
        // baseline below deliberately does not carry.
        Resource sourceNode = ResourceFactory.createResource(current.source().uri().toString());
        List<Resource> held = naming(current.graph(), grantee, sourceNode, targetPredicates(current));
        if (held.isEmpty()) {
            return unchanged(current);
        }
        for (Resource authorization : held) {
            if (authorization.hasProperty(Acl.MODE, AccessMode.CONTROL.term())) {
                throw new CisternException.Conflict(
                        WacMessage.REVOKE_WOULD_DROP_CONTROL.format(grantee.term(), target.uri()));
            }
        }

        ResourceIdentifier aclResource = AclResource.of(target);
        Model acl = baseline(current, target, aclResource);
        if (isOwn(current, target)) {
            Resource targetNode = acl.createResource(target.uri().toString());
            for (Resource authorization : naming(acl, grantee, targetNode, TARGET_PREDICATES)) {
                withdraw(acl, aclResource, authorization, grantee, targetNode);
            }
        }
        // Inherited: the baseline re-states only Control-holders, none of which names the
        // grantee (that was refused above), so the grantee is simply not in the new ACL.
        return written(aclResource, acl, target);
    }

    // ---- baseline: what the new <target>.acl starts from -------------------------------

    /**
     * A copy of the target's own ACL if it has one; otherwise a fresh ACL for the target that
     * re-states every inherited Control-holder (see the class note).
     */
    private static Model baseline(EffectiveAcl current, ResourceIdentifier target, ResourceIdentifier aclResource) {
        Model acl = ModelFactory.createDefaultModel();
        acl.setNsPrefixes(current.graph().getNsPrefixMap());
        acl.setNsPrefix(Acl.PREFIX, Acl.NS);
        acl.setNsPrefix(Foaf.PREFIX, Foaf.NS);
        if (isOwn(current, target)) {
            acl.add(current.graph());
            return acl;
        }
        Resource sourceNode = ResourceFactory.createResource(current.source().uri().toString());
        Resource targetNode = acl.createResource(target.uri().toString());
        for (Resource inherited : authorizations(current.graph())) {
            if (!inherited.hasProperty(Acl.DEFAULT, sourceNode)
                    || !inherited.hasProperty(Acl.MODE, AccessMode.CONTROL.term())) {
                continue;
            }
            Resource restated = acl.createResource(
                    fresh(acl, aclResource, fragmentOf(inherited).orElse(CONTROL_FRAGMENT)));
            restated.addProperty(RDF.type, Acl.AUTHORIZATION);
            for (Property predicate : RESTATED_PREDICATES) {
                inherited.listProperties(predicate).forEachRemaining(
                        statement -> restated.addProperty(predicate, statement.getObject()));
            }
            restated.addProperty(Acl.ACCESS_TO, targetNode);
            if (target.isContainer()) {
                restated.addProperty(Acl.DEFAULT, targetNode);
            }
        }
        return acl;
    }

    private static boolean isOwn(EffectiveAcl current, ResourceIdentifier target) {
        return current.source().equals(target);
    }

    /** Which predicates say "applies to the target" in {@code current}, given how it was found. */
    private static List<Property> targetPredicates(EffectiveAcl current) {
        return current.scope() == AclScope.INHERITED ? List.of(Acl.DEFAULT) : TARGET_PREDICATES;
    }

    /**
     * Discovery reports the ACL as the target's own (scope {@code ACCESS_TO}, source = target)
     * or as an ancestor's ({@code INHERITED}, source = that ancestor). Anything else is a caller
     * mixing up two lookups, and building an ACL on it would write rules for the wrong resource.
     */
    private static void requireGoverns(EffectiveAcl current, ResourceIdentifier target) {
        boolean governs = switch (current.scope()) {
            case ACCESS_TO -> current.source().equals(target);
            case INHERITED -> isProperAncestor(current.source(), target);
        };
        if (!governs) {
            throw new IllegalArgumentException(WacMessage.EFFECTIVE_ACL_DOES_NOT_GOVERN.format(
                    current.source().uri(), current.scope(), target.uri()));
        }
    }

    private static boolean isProperAncestor(ResourceIdentifier candidate, ResourceIdentifier target) {
        Optional<ResourceIdentifier> ancestor = target.parent();
        while (ancestor.isPresent()) {
            if (ancestor.get().equals(candidate)) {
                return true;
            }
            ancestor = ancestor.get().parent();
        }
        return false;
    }

    // ---- reading authorizations out of a graph ------------------------------------------

    /** Every {@code acl:Authorization} subject, in a stable order. */
    private static List<Resource> authorizations(Model acl) {
        List<Resource> subjects = new ArrayList<>();
        acl.listResourcesWithProperty(RDF.type, Acl.AUTHORIZATION).forEachRemaining(subjects::add);
        subjects.sort(STABLE_ORDER);
        return subjects;
    }

    /**
     * The authorizations that name {@code grantee} and cover {@code target} through any of
     * {@code predicates} — everything the grantee holds on the target, shared or not,
     * constrained or not.
     */
    private static List<Resource> naming(Model acl, Grantee grantee, Resource target, List<Property> predicates) {
        List<Resource> found = new ArrayList<>();
        for (Resource authorization : authorizations(acl)) {
            if (authorization.hasProperty(grantee.predicate(), grantee.term())
                    && predicates.stream().anyMatch(p -> authorization.hasProperty(p, target))) {
                found.add(authorization);
            }
        }
        return found;
    }

    /**
     * The authorization that is the grantee's <em>own</em> for {@code target} under exactly
     * {@code clientTerms}: names exactly this grantee (no other agent, class or group), exactly
     * this target ({@code acl:accessTo} the target and nothing else; {@code acl:default} the
     * target or nothing), and exactly these clients (none, for an unconstrained request). Only
     * such an authorization can safely be widened by adding a mode.
     */
    private static Optional<Resource> ownAuthorization(
            Model acl, Grantee grantee, Resource target, Set<RDFNode> clientTerms) {
        for (Resource authorization : authorizations(acl)) {
            if (namesOnly(authorization, grantee)
                    && objects(authorization, Acl.ACCESS_TO).equals(Set.of(target))
                    && Set.of(target).containsAll(objects(authorization, Acl.DEFAULT))
                    && objects(authorization, Cistern.CLIENT).equals(clientTerms)) {
                return Optional.of(authorization);
            }
        }
        return Optional.empty();
    }

    private static boolean namesOnly(Resource authorization, Grantee grantee) {
        for (Property predicate : SUBJECT_PREDICATES) {
            Set<RDFNode> named = objects(authorization, predicate);
            Set<RDFNode> expected = predicate.equals(grantee.predicate()) ? Set.of(grantee.term()) : Set.of();
            if (!named.equals(expected)) {
                return false;
            }
        }
        return true;
    }

    private static Set<RDFNode> objects(Resource subject, Property predicate) {
        Set<RDFNode> objects = new LinkedHashSet<>();
        subject.listProperties(predicate).forEachRemaining(statement -> objects.add(statement.getObject()));
        return objects;
    }

    /** The request's clients as the terms an authorization names them by. */
    private static Set<RDFNode> clientTerms(GrantRequest request) {
        Set<RDFNode> terms = new LinkedHashSet<>();
        for (URI client : request.clients()) {
            terms.add(ResourceFactory.createResource(client.toString()));
        }
        return terms;
    }

    // ---- writing --------------------------------------------------------------------------

    private static Resource newAuthorization(
            Model acl, ResourceIdentifier aclResource, Grantee grantee, Resource target, Set<RDFNode> clientTerms) {
        Resource authorization = acl.createResource(fresh(acl, aclResource, fragmentOf(grantee)));
        authorization.addProperty(RDF.type, Acl.AUTHORIZATION);
        authorization.addProperty(grantee.predicate(), grantee.term());
        authorization.addProperty(Acl.ACCESS_TO, target);
        for (RDFNode client : clientTerms) {
            authorization.addProperty(Cistern.CLIENT, client);
        }
        return authorization;
    }

    /**
     * Take the grantee's authority on {@code target} out of {@code authorization}, leaving every
     * other (agent, resource) pair it grants exactly as it was.
     *
     * <p>Four cases, by whether the authorization also names someone else and whether it also
     * covers something else: remove it whole; remove only the grantee from it; remove only the
     * target from it; or split it — the others keep the original, the grantee gets a copy
     * without this target. A client constraint travels with whichever side keeps the rule.
     */
    private static void withdraw(
            Model acl, ResourceIdentifier aclResource, Resource authorization, Grantee grantee, Resource target) {
        boolean namesOthers = !namesOnly(authorization, grantee);
        boolean coversOthers = TARGET_PREDICATES.stream()
                .anyMatch(p -> !Set.of(target).containsAll(objects(authorization, p)));

        if (!namesOthers && !coversOthers) {
            acl.removeAll(authorization, null, null);
            return;
        }
        if (namesOthers && coversOthers) {
            // The grantee's share of this rule, minus the target, under a fresh name — built
            // before the original is touched so it copies what the grantee actually held.
            Resource remainder = acl.createResource(fresh(acl, aclResource, fragmentOf(grantee)));
            List<Statement> original = authorization.listProperties().toList();
            for (Statement statement : original) {
                Property predicate = statement.getPredicate();
                boolean namesSomeoneElse = SUBJECT_PREDICATES.contains(predicate)
                        && !(predicate.equals(grantee.predicate()) && statement.getObject().equals(grantee.term()));
                boolean coversTarget = TARGET_PREDICATES.contains(predicate) && statement.getObject().equals(target);
                if (!namesSomeoneElse && !coversTarget) {
                    remainder.addProperty(predicate, statement.getObject());
                }
            }
        }
        if (namesOthers) {
            acl.removeAll(authorization, grantee.predicate(), grantee.term());
        } else {
            for (Property predicate : TARGET_PREDICATES) {
                acl.removeAll(authorization, predicate, target);
            }
        }
    }

    /** The fragment base an authorization written for {@code grantee} is named under. */
    private static String fragmentOf(Grantee grantee) {
        return switch (grantee) {
            case Grantee.WebId _ -> AGENT_FRAGMENT;
            case Grantee.Public _ -> PUBLIC_FRAGMENT;
        };
    }

    /** The fragment of a URI-named authorization ({@code <…/.acl#owner>} → {@code owner}), if any. */
    private static Optional<String> fragmentOf(Resource authorization) {
        if (!authorization.isURIResource()) {
            return Optional.empty();
        }
        String uri = authorization.getURI();
        int hash = uri.lastIndexOf(FRAGMENT_SEPARATOR);
        if (hash < 0 || hash == uri.length() - 1) {
            return Optional.empty();
        }
        return Optional.of(uri.substring(hash + 1));
    }

    /**
     * {@code <aclResource>#<base>}, or {@code #<base>-2}, {@code #<base>-3}, … — the first name
     * not already used in {@code acl}. Deterministic given the graph, so the same sequence of
     * operations always writes the same file.
     */
    private static String fresh(Model acl, ResourceIdentifier aclResource, String base) {
        String prefix = aclResource.uri().toString() + FRAGMENT_SEPARATOR + base;
        String candidate = prefix;
        for (int n = FIRST_DISAMBIGUATOR; acl.containsResource(acl.createResource(candidate)); n++) {
            candidate = prefix + DISAMBIGUATOR_SEPARATOR + n;
        }
        return candidate;
    }

    // ---- outcomes -------------------------------------------------------------------------

    private static GrantOutcome written(ResourceIdentifier aclResource, Model acl, ResourceIdentifier target) {
        Set<Authorization> governing = new LinkedHashSet<>();
        for (Authorization authorization : WacEngine.parse(acl, AclScope.ACCESS_TO, DelegationMode.ENABLED)) {
            if (authorization.covers(target.uri())) {
                governing.add(authorization);
            }
        }
        return new GrantOutcome(aclResource, governing, acl, true);
    }

    private static GrantOutcome unchanged(EffectiveAcl current) {
        Set<Authorization> governing = new LinkedHashSet<>();
        for (Authorization authorization
                : WacEngine.parse(current.graph(), current.scope(), DelegationMode.ENABLED)) {
            if (authorization.covers(current.source().uri())) {
                governing.add(authorization);
            }
        }
        return new GrantOutcome(current.aclResource(), governing, current.graph(), false);
    }
}
