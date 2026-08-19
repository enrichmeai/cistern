package com.enrichmeai.cistern.wac;

import com.enrichmeai.cistern.core.ResourceIdentifier;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * "Let {@code grantee} do {@code modes} to {@code target}" — the input to
 * {@link GrantService#grant}.
 *
 * <p>Modes are stored closed under implication (a Write grant carries Append, see
 * {@link AccessMode#withImplied()}), so the request already says exactly what the resulting
 * authorization will say and no later step can forget the rule.
 *
 * <p>There is no expiry here. {@code docs/INTEGRATION.md} §6.3 sketches a {@code validUntil}
 * component; it belongs to T5.8 (#92), which also has to teach the engine to honour it, and a
 * field the engine ignores would be a grant that silently never expires. Left out rather than
 * accepted-and-ignored.
 *
 * @param target  the resource the grant is on — a container (the grant applies to it and, via
 *                {@code acl:default}, to everything inside it) or a document
 * @param grantee who is granted
 * @param modes   what is granted; at least one
 */
public record GrantRequest(ResourceIdentifier target, Grantee grantee, Set<AccessMode> modes) {

    public GrantRequest {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(grantee, "grantee");
        Objects.requireNonNull(modes, "modes");
        if (modes.isEmpty()) {
            throw new IllegalArgumentException(WacMessage.GRANT_WITHOUT_MODES.format(target.uri()));
        }
        if (AclResource.isAcl(target)) {
            throw new IllegalArgumentException(WacMessage.TARGET_IS_AN_ACL.format(target.uri()));
        }
        Set<AccessMode> closed = EnumSet.noneOf(AccessMode.class);
        for (AccessMode mode : modes) {
            closed.addAll(mode.withImplied());
        }
        modes = Collections.unmodifiableSet(closed);
    }
}
