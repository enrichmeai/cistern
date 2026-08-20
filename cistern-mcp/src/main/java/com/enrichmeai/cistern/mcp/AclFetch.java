package com.enrichmeai.cistern.mcp;

import java.util.Objects;

import org.apache.jena.rdf.model.Model;

/**
 * What a {@code GET <resource>.acl} came back with: the ACL and its validator, or nothing
 * there. Sealed so the walk in {@link RemoteAclDiscovery} pattern-matches rather than testing
 * a nullable — the same shape as cistern-cli's, because it is the same wire conversation.
 */
sealed interface AclFetch permits AclFetch.Found, AclFetch.Absent {

    /**
     * The ACL exists. An empty graph is still "found" — an empty ACL denies everyone and
     * terminates the walk, exactly as the server's own {@code AclDiscovery} treats it.
     */
    record Found(Model graph, EntityTagHeader etag) implements AclFetch {
        public Found {
            Objects.requireNonNull(graph, "graph");
            Objects.requireNonNull(etag, "etag");
        }
    }

    /** 404: no ACL at this level; the walk continues with the parent. */
    record Absent() implements AclFetch {
    }
}
