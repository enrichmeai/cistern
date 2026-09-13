package com.enrichmeai.cistern.wac;

import com.enrichmeai.cistern.core.vocab.Cistern;

import org.apache.jena.rdf.model.Property;

/**
 * The Cistern delegation term that narrowed a decision — what a receipt names when a
 * delegation, rather than the absence of a grant, is why an agent holds less than the WebID
 * it authenticated as (ADR 0004 §6, AD-DEL-5).
 *
 * <p>Without this a receipt cannot tell <em>"the user never had this access"</em> from
 * <em>"the delegation capped it"</em>, which is the audit value the feature exists to produce.
 * One enum-valued field rather than a boolean per dimension (AD-DEL-6 seam 3): a later term
 * adds a constant here, never a column to the decision log.
 *
 * <p>A closed set, so an enum (ground rule 7); each constant carries the vocabulary term it
 * stands for, so the receipt and the ACL cannot name the same thing two ways.
 */
public enum DelegationTerm {

    /**
     * {@code cistern:client} bound: the agent matched an authorization by WebID or class, but
     * the client the request came through is not one the authorization admits.
     */
    CLIENT(Cistern.CLIENT);

    private final Property term;

    DelegationTerm(Property term) {
        this.term = term;
    }

    /** The vocabulary term this names. */
    public Property term() {
        return term;
    }
}
