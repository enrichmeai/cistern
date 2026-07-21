package com.enrichmeai.cistern.wac;

import com.enrichmeai.cistern.core.ResourceIdentifier;

import java.util.Objects;

import org.apache.jena.rdf.model.Model;

/**
 * The ACL that governs a resource, and how it came to govern it.
 *
 * <p>The scope is carried alongside the graph rather than recomputed, because it is not
 * derivable from the graph: the same document means different things depending on whether it
 * was found on the resource ({@code acl:accessTo} applies) or inherited from an ancestor
 * ({@code acl:default} applies). Discovery is the only component that knows which happened, so
 * it says so here and {@link WacEngine} cannot guess wrong.
 *
 * @param graph  the parsed ACL
 * @param scope  whether {@code graph} was found on the resource or inherited
 * @param source the resource whose ACL this is — the target itself, or the ancestor it was
 *               inherited from. Kept for the audit trail: naming the policy resource that
 *               decided a request is what makes "which agent read what, under which grant"
 *               answerable, and it is far cheaper to carry now than to reconstruct later.
 */
public record EffectiveAcl(Model graph, AclScope scope, ResourceIdentifier source) {

    public EffectiveAcl {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(source, "source");
    }

    /** The ACL resource this was read from — the URI to advertise as {@code Link: rel="acl"}. */
    public ResourceIdentifier aclResource() {
        return AclResource.of(source);
    }
}
