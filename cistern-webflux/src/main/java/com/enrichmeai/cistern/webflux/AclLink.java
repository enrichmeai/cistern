package com.enrichmeai.cistern.webflux;

import com.enrichmeai.cistern.core.ResourceIdentifier;
import com.enrichmeai.cistern.wac.AclResource;

/**
 * The one rendering of WAC's "ACL Resource Discovery" link ({@code Link: <…>; rel="acl"}).
 *
 * <p>Two kinds of place emit it and must agree. {@link AuthorizationFilter} sets it on the
 * exchange up front, which is what refused and error-mapped responses carry. A router handler
 * that emits its own {@code Link} values replaces that field wholesale when its
 * {@code ServerResponse} is written, so those handlers add this value to their own list —
 * same string, one source.
 */
final class AclLink {

    private AclLink() {
        // static rendering only
    }

    /**
     * The link value for a response about {@code target}: its own ACL by the
     * {@link AclResource} convention, whether or not that ACL has a representation yet. An
     * ACL resource is its own ACL — editing it is already governed by Control on the resource
     * it protects, so advertising a {@code .acl.acl} would name a resource outside the model.
     */
    static String valueFor(ResourceIdentifier target) {
        ResourceIdentifier acl = AclResource.isAcl(target) ? target : AclResource.of(target);
        return HttpConstants.link(acl.uri().toString(), LinkRelation.ACL);
    }
}
