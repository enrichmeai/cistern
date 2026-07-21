package com.enrichmeai.cistern.wac;

import com.enrichmeai.cistern.core.Agent;
import com.enrichmeai.cistern.core.vocab.Acl;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.apache.jena.rdf.model.Model;
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
 * <p>Thread-safe and stateless; a single instance may be shared.
 */
public final class WacEngine {

    private static final Logger log = LoggerFactory.getLogger(WacEngine.class);

    /**
     * What {@code agent} may do to {@code target}, according to {@code effectiveAcl}.
     *
     * @param effectiveAcl the ACL graph that governs the target, as located by ACL discovery
     * @param target       the resource being accessed
     * @param agent        the requester; {@link Agent#ANONYMOUS} for an unauthenticated request
     * @param scope        whether the ACL was found on the resource or inherited from an ancestor
     * @return the granted modes, closed under implication; {@link AccessDecision#DENIED} if none
     */
    public AccessDecision decide(Model effectiveAcl, URI target, Agent agent, AclScope scope) {
        Objects.requireNonNull(effectiveAcl, "effectiveAcl");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(agent, "agent");
        Objects.requireNonNull(scope, "scope");

        AccessDecision decision = AccessDecision.DENIED;
        for (Authorization authorization : parse(effectiveAcl, scope)) {
            if (authorization.covers(target) && authorization.matches(agent)) {
                decision = decision.union(new AccessDecision(authorization.modes()));
            }
        }
        if (decision.isDenied() && log.isDebugEnabled()) {
            log.debug(WacMessage.NO_APPLICABLE_AUTHORIZATION.format(target, scope));
        }
        return decision;
    }

    /**
     * Every {@code acl:Authorization} in {@code acl}, read under {@code scope}.
     *
     * <p>Exposed because ACL discovery and diagnostics both want to see the parsed rules
     * without re-implementing the reading, and because it makes the parse independently
     * testable from the evaluation.
     */
    public List<Authorization> parse(Model acl, AclScope scope) {
        Objects.requireNonNull(acl, "acl");
        Objects.requireNonNull(scope, "scope");

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
                        // a malformed ACL public.
                        return;
                    }
                    authorizations.add(new Authorization(modes, agents, agentClasses, targets));
                });
        return authorizations;
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
     * make one bad triple deny access that other, valid authorizations grant.
     */
    private static Set<URI> uris(
            Resource authorization, org.apache.jena.rdf.model.Property predicate, WacMessage onMalformed) {
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
