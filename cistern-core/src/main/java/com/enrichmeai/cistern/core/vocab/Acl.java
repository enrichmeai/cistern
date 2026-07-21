package com.enrichmeai.cistern.core.vocab;

import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;

/**
 * The Web Access Control vocabulary
 * (<a href="http://www.w3.org/ns/auth/acl#">http://www.w3.org/ns/auth/acl#</a>).
 *
 * <p>Per-namespace constant class (ground rule 7) beside {@link Solid} and {@link Pim}: an ACL
 * IRI is never spelled at the site that reads or writes a graph.
 *
 * <p><strong>{@code acl:origin} is deliberately absent.</strong> It is not among the matchers
 * the current WAC specification defines, the conformance harness has no assertion for it, and
 * {@code docs/ideas/agent-scoped-delegation.md} records why it is a dead letter: it is
 * conjunctive rather than a subject in its own right, it applies only to requests carrying an
 * {@code Origin} header — so every non-browser client, including every AI agent, bypasses it —
 * and Community Solid Server ignores it outright. The T5.2 ticket text still lists it; that
 * text predates the research and is stale, not a requirement being skipped.
 */
public final class Acl {

    /** The Web Access Control namespace, {@value}. */
    public static final String NS = "http://www.w3.org/ns/auth/acl#";

    // ---- the Authorization class ------------------------------------------------------

    /** {@code acl:Authorization} — the class every authorization statement belongs to. */
    public static final Resource AUTHORIZATION = ResourceFactory.createResource(NS + "Authorization");

    // ---- access modes -----------------------------------------------------------------

    /** {@code acl:Read} — "access to a class of read operations on a resource". */
    public static final Resource READ = ResourceFactory.createResource(NS + "Read");

    /** {@code acl:Write} — "access to a class of write operations, e.g. to create, delete or modify". */
    public static final Resource WRITE = ResourceFactory.createResource(NS + "Write");

    /**
     * {@code acl:Append} — "access to a class of append operations ... to add information, but
     * not remove". WAC states it is a <em>subclass of</em> {@code acl:Write}, so a grant of
     * Write satisfies a requirement for Append.
     */
    public static final Resource APPEND = ResourceFactory.createResource(NS + "Append");

    /**
     * {@code acl:Control} — "access to a class of read and write operations on an ACL
     * resource". It governs the ACL, not the resource: it implies neither Read nor Write on
     * the target itself.
     */
    public static final Resource CONTROL = ResourceFactory.createResource(NS + "Control");

    /** {@code acl:mode} — from an Authorization to one of the access modes above. */
    public static final Property MODE = ResourceFactory.createProperty(NS, "mode");

    // ---- subjects ---------------------------------------------------------------------

    /** {@code acl:agent} — "denotes an individual agent", identified by WebID. */
    public static final Property AGENT = ResourceFactory.createProperty(NS, "agent");

    /** {@code acl:agentClass} — "denotes a class of agents". */
    public static final Property AGENT_CLASS = ResourceFactory.createProperty(NS, "agentClass");

    /** {@code acl:agentGroup} — denotes a {@code vcard:Group} whose members are authorized. */
    public static final Property AGENT_GROUP = ResourceFactory.createProperty(NS, "agentGroup");

    /** {@code acl:AuthenticatedAgent} — the class of every agent that proved an identity. */
    public static final Resource AUTHENTICATED_AGENT =
            ResourceFactory.createResource(NS + "AuthenticatedAgent");

    // ---- targets ----------------------------------------------------------------------

    /** {@code acl:accessTo} — "the specific resource to which access is being granted". */
    public static final Property ACCESS_TO = ResourceFactory.createProperty(NS, "accessTo");

    /**
     * {@code acl:default} — "a container resource whose Authorization applies to lower
     * hierarchy members": the inheritable form, and the only form that applies when the
     * effective ACL was found on an ancestor.
     */
    public static final Property DEFAULT = ResourceFactory.createProperty(NS, "default");

    private Acl() {
        // constants only
    }
}
