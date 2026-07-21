package com.enrichmeai.cistern.core.vocab;

import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;

/**
 * The FOAF vocabulary (<a href="http://xmlns.com/foaf/0.1/">http://xmlns.com/foaf/0.1/</a>),
 * to the extent Web Access Control uses it.
 *
 * <p>Only {@link #AGENT} is needed: WAC names it as the {@code acl:agentClass} value meaning
 * "any agent, i.e. the public". The rest of FOAF is out of scope until something needs it.
 */
public final class Foaf {

    /** The FOAF namespace, {@value}. */
    public static final String NS = "http://xmlns.com/foaf/0.1/";

    /**
     * {@code foaf:Agent} — as an {@code acl:agentClass} value, WAC's spelling of "the public":
     * it matches every requester, authenticated or not.
     */
    public static final Resource AGENT = ResourceFactory.createResource(NS + "Agent");

    private Foaf() {
        // constants only
    }
}
