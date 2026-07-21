package com.enrichmeai.cistern.wac;

import com.enrichmeai.cistern.core.Agent;
import com.enrichmeai.cistern.core.vocab.Acl;
import com.enrichmeai.cistern.core.vocab.Foaf;

import java.util.Optional;

import org.apache.jena.rdf.model.Resource;

/**
 * The classes of agent that {@code acl:agentClass} can name. A closed set of two, so an enum
 * (ground rule 7) — and keeping them distinct matters, because collapsing them into "everyone"
 * is exactly the bug that makes a members-only container public.
 */
public enum AgentClass {

    /**
     * {@code foaf:Agent} — WAC's spelling of the public: "allows access to any agent". Matches
     * every requester, authenticated or not.
     */
    PUBLIC(Foaf.AGENT) {
        @Override
        public boolean matches(Agent agent) {
            return true;
        }
    },

    /**
     * {@code acl:AuthenticatedAgent} — any agent that proved an identity, and no anonymous
     * one. The distinction from {@link #PUBLIC} is the whole point of the class.
     */
    AUTHENTICATED(Acl.AUTHENTICATED_AGENT) {
        @Override
        public boolean matches(Agent agent) {
            return agent.isAuthenticated();
        }
    };

    private final String iri;

    AgentClass(Resource term) {
        this.iri = term.getURI();
    }

    /** The IRI naming this class. */
    public String iri() {
        return iri;
    }

    /** Whether {@code agent} belongs to this class. */
    public abstract boolean matches(Agent agent);

    /**
     * The class named by {@code iri}, or empty if it names none this server implements. Empty
     * rather than an exception, for the same reason as {@link AccessMode#fromIri}: an unknown
     * agent class must grant nothing, not fail the evaluation.
     */
    public static Optional<AgentClass> fromIri(String iri) {
        for (AgentClass agentClass : values()) {
            if (agentClass.iri.equals(iri)) {
                return Optional.of(agentClass);
            }
        }
        return Optional.empty();
    }
}
