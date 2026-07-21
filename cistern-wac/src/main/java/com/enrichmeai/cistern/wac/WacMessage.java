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
                    + " permissive ancestor");

    private final String template;

    WacMessage(String template) {
        this.template = template;
    }

    /** This message with {@code args} substituted. */
    public String format(Object... args) {
        return String.format(template, args);
    }
}
