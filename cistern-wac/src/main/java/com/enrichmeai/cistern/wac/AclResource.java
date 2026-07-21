package com.enrichmeai.cistern.wac;

import com.enrichmeai.cistern.core.ResourceIdentifier;

import java.net.URI;
import java.util.Objects;

/**
 * Where a resource's ACL lives.
 *
 * <p>The Solid Protocol does not fix a naming convention: an ACL is <em>discovered</em> by the
 * {@code Link: rel="acl"} header, and the server picks the URIs it advertises. This class is
 * that choice, in one place, so the convention cannot drift between the component that
 * advertises the link, the one that looks the ACL up, and the one that enforces who may write
 * it. The suffix is {@code .acl}, matching Community Solid Server so that a pod moved between
 * the two implementations keeps working.
 *
 * <ul>
 *   <li>a document {@code /notes/hello} → {@code /notes/hello.acl}</li>
 *   <li>a container {@code /notes/} → {@code /notes/.acl}</li>
 * </ul>
 *
 * <p>Note that a container's ACL sits <em>inside</em> the container. That is deliberate and it
 * matters for T5.3: the ACL is an auxiliary resource of the container, not a sibling, so
 * deleting a container has to account for it.
 */
public final class AclResource {

    /** The suffix that turns a resource URI into its ACL's URI. */
    public static final String SUFFIX = ".acl";

    private AclResource() {
        // static factory only
    }

    /**
     * The ACL resource governing {@code resource}.
     *
     * <p>A container keeps its ACL as a child named {@code .acl}; a document gets a sibling
     * with the suffix appended. Both fall out of appending to the URI, because a container
     * identifier already ends in a slash.
     */
    public static ResourceIdentifier of(ResourceIdentifier resource) {
        Objects.requireNonNull(resource, "resource");
        return new ResourceIdentifier(URI.create(resource.uri().toString() + SUFFIX));
    }

    /**
     * Whether {@code identifier} names an ACL resource rather than an ordinary one.
     *
     * <p>T5.3 needs this: reading or writing an ACL requires {@code acl:Control} on the
     * resource it governs, not Read or Write on the ACL itself, so the enforcement layer has
     * to recognise one when it sees it.
     */
    public static boolean isAcl(ResourceIdentifier identifier) {
        Objects.requireNonNull(identifier, "identifier");
        return identifier.uri().toString().endsWith(SUFFIX);
    }

    /**
     * The resource that {@code acl} governs — the inverse of {@link #of}.
     *
     * @throws IllegalArgumentException if {@code acl} does not name an ACL resource
     */
    public static ResourceIdentifier governedBy(ResourceIdentifier acl) {
        Objects.requireNonNull(acl, "acl");
        String uri = acl.uri().toString();
        if (!uri.endsWith(SUFFIX)) {
            throw new IllegalArgumentException(WacMessage.NOT_AN_ACL_RESOURCE.format(uri));
        }
        return new ResourceIdentifier(URI.create(uri.substring(0, uri.length() - SUFFIX.length())));
    }
}
