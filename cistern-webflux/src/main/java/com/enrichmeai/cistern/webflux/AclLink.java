package com.enrichmeai.cistern.webflux;

import com.enrichmeai.cistern.core.ResourceIdentifier;
import com.enrichmeai.cistern.wac.AclResource;

/**
 * The one rendering of WAC's "ACL Resource Discovery" link ({@code Link: <…>; rel="acl"}).
 *
 * <p>Which ACL a target maps to is {@link AclResource#aclFor}'s decision; this class only
 * renders it through {@link HttpConstants#link}. There is exactly one emitter — the
 * {@code beforeCommit} hook {@link AuthorizationFilter} registers — so no handler needs to
 * know this link exists, and a handler that writes its own {@code Link} values cannot clobber
 * it: the hook runs after whatever the handler finally wrote (the {@code OriginVaryFilter}
 * shape, and for the same reason).
 */
final class AclLink {

    private AclLink() {
        // static rendering only
    }

    /**
     * The link value for a response about {@code target} — the ACL governing it, whether or
     * not that ACL has a representation yet (WAC asks for the location, not a promise of a
     * document).
     */
    static String valueFor(ResourceIdentifier target) {
        return HttpConstants.link(AclResource.aclFor(target).uri().toString(), LinkRelation.ACL);
    }
}
