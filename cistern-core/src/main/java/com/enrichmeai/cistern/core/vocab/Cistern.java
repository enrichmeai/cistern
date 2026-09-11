package com.enrichmeai.cistern.core.vocab;

import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.ResourceFactory;

/**
 * Cistern's own vocabulary
 * (<a href="https://enrichmeai.com/ns/cistern#">https://enrichmeai.com/ns/cistern#</a>): the
 * terms a delegation adds to a Web Access Control authorization (ADR 0004, AD-DEL-3).
 *
 * <p>Per-namespace constant class (ground rule 7) beside {@link Acl}, {@link Solid}, {@link Foaf}
 * and {@link Pim}. The namespace is the one {@code docs/INTEGRATION.md} §6.3 fixes.
 *
 * <p><strong>Every term here only reduces.</strong> An authorization's distinguishing term is
 * portable WAC — {@code acl:agent} naming a WebID, or {@code acl:agentClass} — and a Cistern
 * term narrows what that term already grants; it is never a way to be granted (AD-DEL-1,
 * AD-DEL-2). A server that does not read this namespace ignores the terms, and what remains is
 * the grant the owner accepted as permanent. That is why the terms are Cistern's own rather than
 * borrowed: {@code acl:origin} is a browser concept and not an OAuth client identifier (see
 * {@link Acl}), and a bare {@code acp:client} inside a WAC {@code acl:Authorization} is neither
 * ACP nor WAC — a server running ACP would not read the document and one running WAC ignores
 * the term.
 *
 * <p>{@code cistern:validUntil} joins this class under T5.8 (#92), which is also where the
 * engine learns to evaluate it; declaring a term the engine does not read would be a grant
 * that silently never expires.
 */
public final class Cistern {

    /** The Cistern namespace, {@value}. */
    public static final String NS = "https://enrichmeai.com/ns/cistern#";

    /** The conventional prefix label for {@link #NS}, {@value}, for graphs this server writes. */
    public static final String PREFIX = "cistern";

    /**
     * {@code cistern:client} — from an authorization to a client (an OAuth client identifier,
     * as an absolute URI) through which the agent it names may exercise it.
     *
     * <p>Profiles the Access Control Policy {@code acp:client} matcher, restricted to depth 1:
     * the object is the client identifier the access token carries ({@code client_id}), the
     * match is on that identifier alone, and a matched client may not delegate onwards. A later
     * ACP evaluator adopts these semantics rather than renegotiating them.
     *
     * <p>Several {@code cistern:client} triples on one authorization name alternatives; none
     * means the authorization is unconstrained — AD-15's identity element, without which every
     * request an application makes as itself would be refused.
     */
    public static final Property CLIENT = ResourceFactory.createProperty(NS, "client");

    private Cistern() {
        // constants only
    }
}
