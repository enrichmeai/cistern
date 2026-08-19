package com.enrichmeai.cistern.wac;

/**
 * cistern-wac's message catalogue (ground rule 7): no message text is inlined at a throw or
 * log site. Plain Java — this module takes no Spring dependency, as cistern-core does not.
 *
 * <p>Templates are {@link String#format} patterns.
 */
public enum WacMessage {

    /** An ACL graph was parsed but produced no usable authorization. Logged, never thrown. */
    NO_APPLICABLE_AUTHORIZATION(
            "No authorization in the effective ACL covers <%s> under scope %s; denying by"
                    + " default (WAC defines no deny rule — an unmatched request is simply"
                    + " ungranted)"),

    /** An {@code acl:mode} this server does not implement. Ignored, so it grants nothing. */
    UNKNOWN_ACCESS_MODE("Ignoring unrecognised acl:mode <%s> in the effective ACL"),

    /** An {@code acl:agentClass} this server does not implement. Ignored, so it grants nothing. */
    UNKNOWN_AGENT_CLASS("Ignoring unrecognised acl:agentClass <%s> in the effective ACL"),

    /** A target IRI that is not a syntactically valid URI. Ignored, so it matches nothing. */
    MALFORMED_TARGET_IRI("Ignoring malformed target IRI <%s> in the effective ACL"),

    /** An {@code acl:agent} IRI that is not a syntactically valid URI. Ignored. */
    MALFORMED_AGENT_IRI("Ignoring malformed acl:agent IRI <%s> in the effective ACL"),

    /** {@code AclResource.governedBy} called with something that is not an ACL — a caller bug. */
    NOT_AN_ACL_RESOURCE("Not an ACL resource (expected a '" + AclResource.SUFFIX + "' suffix): <%s>"),

    /**
     * The walk reached the storage root without finding an ACL. WAC requires the root's ACL to
     * exist, so this is a misconfigured pod — and it denies everything, including to the owner,
     * which is worth saying loudly rather than leaving as a silent 403.
     */
    NO_ACL_TO_THE_ROOT(
            "No ACL found for <%s> or any ancestor up to the storage root. WAC requires the root"
                    + " container's ACL to exist and to grant acl:Control; until one does, every"
                    + " request is denied by default"),

    /** An ACL resource exists but is not parseable RDF. Denies, rather than granting. */
    UNPARSEABLE_ACL(
            "The ACL resource <%s> is not parseable RDF; denying by default rather than"
                    + " continuing the walk, since a broken ACL must not fall through to a more"
                    + " permissive ancestor"),

    // ---- grant authoring (T5.7) -------------------------------------------------------

    /** A {@link Grantee.WebId} built from a relative reference — a caller bug. */
    WEBID_NOT_ABSOLUTE("A WebID must be an absolute URI: <%s>"),

    /** A {@link GrantRequest} with no modes — nothing would be granted, so it is a caller bug. */
    GRANT_WITHOUT_MODES("A grant on <%s> must name at least one access mode"),

    /**
     * A grant or revoke aimed at an ACL resource itself. Access to an ACL is governed by
     * {@code acl:Control} on the resource it protects, so an ACL's own ACL is not a thing.
     */
    TARGET_IS_AN_ACL(
            "<%s> is an ACL resource; grant or revoke on the resource it governs instead"),

    /**
     * The effective ACL handed to the grant service does not govern the target: found on the
     * target it must be the target's own, and found above it must be on an ancestor container.
     */
    EFFECTIVE_ACL_DOES_NOT_GOVERN(
            "The effective ACL was found on <%s> under scope %s, which cannot govern <%s>"),

    /**
     * A revoke that would remove an authorization granting {@code acl:Control}. Refused: taking
     * Control away is how an owner is locked out of a subtree, and the whole reason this
     * service exists is that a resource-level ACL replaces inheritance silently. Removing
     * Control is a deliberate edit of the ACL, not a one-line revoke.
     */
    REVOKE_WOULD_DROP_CONTROL(
            "Refusing to revoke <%s> on <%s>: that authorization grants acl:Control, and revoking"
                    + " Control could lock the owner out of the resource. Edit the ACL"
                    + " deliberately instead"),

    // ---- provisioning (T5.6) ----------------------------------------------------------

    /**
     * A {@code PodSpec} whose root is not a container. A pod is a subtree, and only a
     * container's ACL can carry the {@code acl:default} that makes the subtree inherit.
     */
    POD_ROOT_NOT_A_CONTAINER(
            "A pod root must be a container (URI path ending in '/'): <%s>");

    private final String template;

    WacMessage(String template) {
        this.template = template;
    }

    /** This message with {@code args} substituted. */
    public String format(Object... args) {
        return String.format(template, args);
    }
}
