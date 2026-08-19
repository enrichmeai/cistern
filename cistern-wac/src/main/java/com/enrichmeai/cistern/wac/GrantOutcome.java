package com.enrichmeai.cistern.wac;

import com.enrichmeai.cistern.core.ResourceIdentifier;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

import org.apache.jena.rdf.model.Model;

/**
 * What an ACL should say after a grant or revoke, and where it should be written.
 *
 * <p>{@link GrantService} is pure: it does not write. This is its answer, and the caller — the
 * CLI over HTTP, or an embedding application against its own store — is the one that persists
 * {@link #aclGraph()} at {@link #aclResource()}. Persisting is only meaningful when
 * {@link #changed()} is true; when it is false the outcome describes the ACL as it already
 * stands (a revoke of a grantee who held nothing, a grant of modes already held) and writing
 * it back would be a no-op with a fresh ETag.
 *
 * @param aclResource    the ACL resource to write — {@code <target>.acl} whenever something
 *                       changed, or the ACL that already governs the target when nothing did
 * @param authorizations the authorizations that govern the target itself after the operation
 *                       (those naming it by {@code acl:accessTo}, or by {@code acl:default}
 *                       when the target still inherits); what a caller reports back
 * @param aclGraph       the full graph to persist, or the governing graph as it stands
 * @param changed        whether the graph differs from what governs the target today
 */
public record GrantOutcome(
        ResourceIdentifier aclResource, Set<Authorization> authorizations, Model aclGraph, boolean changed) {

    public GrantOutcome {
        Objects.requireNonNull(aclResource, "aclResource");
        Objects.requireNonNull(authorizations, "authorizations");
        Objects.requireNonNull(aclGraph, "aclGraph");
        authorizations = Collections.unmodifiableSet(new LinkedHashSet<>(authorizations));
    }
}
