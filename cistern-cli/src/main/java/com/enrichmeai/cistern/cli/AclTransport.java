package com.enrichmeai.cistern.cli;

import com.enrichmeai.cistern.core.ResourceIdentifier;

import org.apache.jena.rdf.model.Model;
import reactor.core.publisher.Mono;

/**
 * The two things the editor needs from the server: read an ACL, write an ACL. {@link PodClient}
 * is the implementation over HTTP; the seam exists so the write path — including the
 * conditional-write retry — can be exercised against a real server with a concurrent editor
 * interposed, without a mocked wire.
 */
interface AclTransport {

    /** {@code GET} the ACL resource {@code acl}: its graph and validator, or {@link AclFetch.Absent}. */
    Mono<AclFetch> fetch(ResourceIdentifier acl);

    /**
     * {@code PUT} {@code graph} at {@code acl} under {@code precondition}. Completes when written;
     * {@link CliFailure.Conflict} if the precondition failed.
     */
    Mono<Void> put(ResourceIdentifier acl, Model graph, WritePrecondition precondition);
}
