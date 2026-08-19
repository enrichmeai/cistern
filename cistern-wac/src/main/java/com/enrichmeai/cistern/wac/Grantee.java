package com.enrichmeai.cistern.wac;

import com.enrichmeai.cistern.core.Agent;
import com.enrichmeai.cistern.core.vocab.Acl;
import com.enrichmeai.cistern.core.vocab.Foaf;

import java.net.URI;
import java.util.Objects;

import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.ResourceFactory;

/**
 * Who a grant is for: one WebID, or everyone.
 *
 * <p>A closed set of two shapes, so a sealed interface (ground rule 7) rather than a nullable
 * URI whose {@code null} would have to mean "the public" by convention. The two are written to
 * an ACL differently and it is the whole point of the type that a caller cannot confuse them:
 * a WebID is named by {@code acl:agent}, the public by {@code acl:agentClass foaf:Agent} —
 * WAC's spelling of "any agent", which matches every requester, authenticated or not.
 *
 * <p>Nothing else is a grantee today. {@code acl:AuthenticatedAgent} ("anyone who logged in")
 * and {@code acl:agentGroup} exist in the vocabulary but are not something the authoring
 * surface offers until a ticket asks for them — offering fewer shapes than the engine can
 * evaluate is the safe direction.
 */
public sealed interface Grantee permits Grantee.WebId, Grantee.Public {

    /** The public, as a shared constant: there is only one of it. */
    Grantee PUBLIC = new Public();

    /** The predicate an authorization uses to name this grantee. */
    Property predicate();

    /** The object that names this grantee under {@link #predicate()}. */
    RDFNode term();

    /**
     * This grantee as an {@link Agent}, for asking the engine what they hold.
     *
     * <p>The public is represented by {@link Agent#ANONYMOUS}: an anonymous request is
     * exactly a request that can be granted only what {@code foaf:Agent} grants, so its
     * decision is the public's decision.
     */
    Agent agent();

    /** A single agent, identified by WebID. */
    record WebId(URI webId) implements Grantee {

        public WebId {
            Objects.requireNonNull(webId, "webId");
            if (!webId.isAbsolute()) {
                throw new IllegalArgumentException(WacMessage.WEBID_NOT_ABSOLUTE.format(webId));
            }
        }

        @Override
        public Property predicate() {
            return Acl.AGENT;
        }

        @Override
        public RDFNode term() {
            return ResourceFactory.createResource(webId.toString());
        }

        @Override
        public Agent agent() {
            return Agent.of(webId);
        }
    }

    /** Everyone — {@code acl:agentClass foaf:Agent}. */
    record Public() implements Grantee {

        @Override
        public Property predicate() {
            return Acl.AGENT_CLASS;
        }

        @Override
        public RDFNode term() {
            return Foaf.AGENT;
        }

        @Override
        public Agent agent() {
            return Agent.ANONYMOUS;
        }
    }
}
