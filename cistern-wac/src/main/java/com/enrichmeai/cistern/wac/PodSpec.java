package com.enrichmeai.cistern.wac;

import com.enrichmeai.cistern.core.ResourceIdentifier;

import java.net.URI;
import java.util.Objects;

/**
 * What {@link PodProvisioner} is asked to bring into being: a root container and the WebID of
 * the agent who owns it.
 *
 * <p>A record with rules rather than two loose arguments (ground rule 7): the root must be a
 * container — a pod is a subtree, and only a container's ACL can carry the {@code acl:default}
 * that makes the subtree inherit — and the owner must be an absolute URI, because a relative
 * WebID would be resolved against the ACL document and silently name nobody who exists.
 *
 * <p>The root may be the storage root itself ({@code /}), which is what the single-owner boot
 * path uses, or any container beneath it — {@code /firms/acme/}, {@code /alice/} — which is
 * what makes one server host several pods with several owners.
 *
 * @param root       the pod's root container
 * @param ownerWebId the agent granted full access to the root and everything under it
 */
public record PodSpec(ResourceIdentifier root, URI ownerWebId) {

    public PodSpec {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(ownerWebId, "ownerWebId");
        if (!root.isContainer()) {
            throw new IllegalArgumentException(WacMessage.POD_ROOT_NOT_A_CONTAINER.format(root.uri()));
        }
        if (!ownerWebId.isAbsolute()) {
            throw new IllegalArgumentException(
                    WacMessage.WEBID_NOT_ABSOLUTE.format(ownerWebId));
        }
    }

    /** The ACL resource the pod's owner authorization is written to. */
    public ResourceIdentifier acl() {
        return AclResource.of(root);
    }
}
