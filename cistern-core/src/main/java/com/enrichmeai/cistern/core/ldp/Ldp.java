package com.enrichmeai.cistern.core.ldp;

import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;

/**
 * The W3C Linked Data Platform vocabulary terms Cistern uses
 * (<a href="https://www.w3.org/ns/ldp#">https://www.w3.org/ns/ldp#</a>).
 *
 * <p>Solid containers correspond to LDP Basic Containers (Solid Protocol §4.2:
 * "The representation and behaviour of containers in Solid corresponds to LDP Basic
 * Container and MUST be supported by server"). Per the LDP class hierarchy
 * (LDP 1.0, https://www.w3.org/TR/ldp/), {@code ldp:BasicContainer} is a subclass of
 * {@code ldp:Container}, which is a subclass of {@code ldp:Resource} (via
 * {@code ldp:RDFSource}).
 *
 * <p>Jena ships no LDP vocabulary class (see {@code org.apache.jena.vocabulary}), so
 * these constants are defined here. Constants are created via {@link ResourceFactory}
 * and belong to no model.
 */
public final class Ldp {

    /** The LDP namespace, {@value}. */
    public static final String NS = "http://www.w3.org/ns/ldp#";

    /** {@code ldp:Resource} — every HTTP resource an LDP server manages. */
    public static final Resource RESOURCE = ResourceFactory.createResource(NS + "Resource");

    /** {@code ldp:Container} — an LDP resource that lists membership. */
    public static final Resource CONTAINER = ResourceFactory.createResource(NS + "Container");

    /** {@code ldp:BasicContainer} — the container kind Solid mandates (Solid Protocol §4.2). */
    public static final Resource BASIC_CONTAINER = ResourceFactory.createResource(NS + "BasicContainer");

    /** {@code ldp:contains} — the server-managed containment predicate. */
    public static final Property CONTAINS = ResourceFactory.createProperty(NS, "contains");

    private Ldp() {
        // constants only
    }
}
