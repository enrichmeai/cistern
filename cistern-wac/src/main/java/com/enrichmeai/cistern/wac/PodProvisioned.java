package com.enrichmeai.cistern.wac;

import com.enrichmeai.cistern.core.ResourceIdentifier;

import java.util.Objects;

/**
 * What {@link PodProvisioner#provision} did. A closed set of two outcomes, so a sealed type
 * (ground rule 7): a caller switches over it exhaustively and cannot mistake "left alone" for
 * "created" — the two log differently, and a CLI reports them differently.
 *
 * <p>There is no failure member. A pod that cannot be provisioned — a document sitting where
 * the container should go, a store that will not write — is signalled as an error through the
 * reactive chain, as everything else in this module is; success has two shapes, failure has
 * one channel.
 */
public sealed interface PodProvisioned permits PodProvisioned.Created, PodProvisioned.AlreadyExists {

    /** The pod's root container, whichever way things went. */
    ResourceIdentifier root();

    /**
     * The pod was brought into being: the root container exists (created here, or already
     * there without an ACL) and its owner ACL was written.
     *
     * @param root the pod's root container
     * @param acl  the ACL resource that now carries the owner's authorization
     */
    record Created(ResourceIdentifier root, ResourceIdentifier acl) implements PodProvisioned {

        public Created {
            Objects.requireNonNull(root, "root");
            Objects.requireNonNull(acl, "acl");
        }
    }

    /**
     * The root already had an ACL, so nothing was written — not the container, not the ACL.
     * An existing ACL is the owner's current policy, and provisioning is not a request to
     * reset it.
     *
     * @param root the pod's root container
     */
    record AlreadyExists(ResourceIdentifier root) implements PodProvisioned {

        public AlreadyExists {
            Objects.requireNonNull(root, "root");
        }
    }
}
