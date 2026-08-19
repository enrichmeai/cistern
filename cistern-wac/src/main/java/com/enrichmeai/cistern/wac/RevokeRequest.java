package com.enrichmeai.cistern.wac;

import com.enrichmeai.cistern.core.ResourceIdentifier;

import java.util.Objects;

/**
 * "Take back everything {@code grantee} was granted on {@code target}" — the input to
 * {@link GrantService#revoke}.
 *
 * <p>Whole authorizations, not individual modes: a revoke is meant to be the one-line,
 * one-request act the demo turns on ("she can take it back in the time it takes to delete a
 * line"), and a partial revoke is a grant of the remainder — which is what {@link GrantRequest}
 * is for.
 *
 * @param target  the resource the grant was on
 * @param grantee whose grant is revoked
 */
public record RevokeRequest(ResourceIdentifier target, Grantee grantee) {

    public RevokeRequest {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(grantee, "grantee");
        if (AclResource.isAcl(target)) {
            throw new IllegalArgumentException(WacMessage.TARGET_IS_AN_ACL.format(target.uri()));
        }
    }
}
