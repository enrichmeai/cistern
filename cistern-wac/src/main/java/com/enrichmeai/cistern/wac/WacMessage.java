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
    MALFORMED_AGENT_IRI("Ignoring malformed acl:agent IRI <%s> in the effective ACL");

    private final String template;

    WacMessage(String template) {
        this.template = template;
    }

    /** This message with {@code args} substituted. */
    public String format(Object... args) {
        return String.format(template, args);
    }
}
