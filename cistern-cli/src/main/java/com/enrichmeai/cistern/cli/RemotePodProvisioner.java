package com.enrichmeai.cistern.cli;

import com.enrichmeai.cistern.wac.PodProvisioned;
import com.enrichmeai.cistern.wac.PodProvisioner;
import com.enrichmeai.cistern.wac.PodSpec;

import java.util.Objects;

import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/**
 * {@code PodProvisioner}'s sequence, over HTTP with the caller's credential: is there an ACL on
 * the root already? If so, that is the pod, leave it. If not, create the root container (only
 * if it is not there) and write the owner ACL (only if nobody has written one meanwhile). The
 * ACL is {@link PodProvisioner#ownerAclGraph} — the same graph boot-time seeding writes — so a
 * pod made from the command line and a pod made from configuration are the same pod.
 *
 * <p>The server decides whether the caller may: creating the root needs {@code acl:Write}
 * there, writing its ACL needs {@code acl:Control}, both inherited from the closest ancestor
 * ACL, and a caller without them is refused with 401/403 exactly as they would be doing it by
 * hand. There is no privileged path.
 *
 * <p>Both writes are create-only ({@code If-None-Match: *}), so a container someone has
 * described is never emptied and an ACL someone has written is never replaced — the same rule
 * as the server-side provisioner, enforced by the server rather than by trust in the read that
 * preceded the write. If the ACL write finds one has appeared since the read (412), the whole
 * sequence is retried once from a fresh read, which then reports
 * {@link PodProvisioned.AlreadyExists}; a 412 that survives that is a {@link CliFailure.Conflict}.
 */
final class RemotePodProvisioner {

    private final PodTransport client;

    RemotePodProvisioner(PodTransport client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    /**
     * Provision the pod {@code spec} describes.
     *
     * @return {@link PodProvisioned.Created} if the owner ACL was written;
     *     {@link PodProvisioned.AlreadyExists} if the root already had one, in which case
     *     nothing was written
     */
    Mono<PodProvisioned> provision(PodSpec spec) {
        Objects.requireNonNull(spec, "spec");
        return Mono.defer(() -> client.fetch(spec.acl())
                        .flatMap(fetched -> switch (fetched) {
                            case AclFetch.Found _ -> Mono.<PodProvisioned>just(
                                    new PodProvisioned.AlreadyExists(spec.root()));
                            case AclFetch.Absent _ -> client.createContainer(spec.root())
                                    .then(client.put(spec.acl(), PodProvisioner.ownerAclGraph(spec),
                                            new WritePrecondition.IfNoneMatchAny()))
                                    .thenReturn(new PodProvisioned.Created(spec.root(), spec.acl()));
                        }))
                // The same one-retry policy as the editor: a lost race is re-read once, then reported.
                .retryWhen(Retry.max(AclEditor.RETRIES_ON_CONFLICT)
                        .filter(CliFailure.Conflict.class::isInstance)
                        .onRetryExhaustedThrow((retrySpec, signal) -> signal.failure()));
    }
}
