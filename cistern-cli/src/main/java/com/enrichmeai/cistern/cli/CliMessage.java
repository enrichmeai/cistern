package com.enrichmeai.cistern.cli;

/**
 * cistern-cli's message catalogue (ground rule 7): every line the command prints and every
 * failure it reports is a template here, never text at the call site.
 *
 * <p>Templates are {@link String#format} patterns. Command and option <em>usage</em> text is
 * in {@link Usage} instead, because picocli needs it as annotation constants.
 */
public enum CliMessage {

    // ---- verdicts (stdout) -----------------------------------------------------------------

    /** grantee, modes, target */
    GRANTED("Granted: %s may now %s %s."),

    /** grantee, modes, target */
    ALREADY_GRANTED("Already granted: %s already holds %s on %s; nothing written."),

    /** grantee, target */
    REVOKED("Revoked: %s no longer holds anything on %s."),

    /** grantee, target */
    NOTHING_TO_REVOKE("Nothing to revoke: %s holds nothing on %s; nothing written."),

    /** aclResource */
    ACL_HOLDS("%s now holds:"),

    /** aclResource, target */
    ACL_HOLDS_INHERITED("%s (which %s inherits) holds:"),

    /** who, modes, scope */
    AUTHORIZATION_LINE("  - %s: %s%s"),

    /** Suffix for an authorization that also applies to everything under a container. */
    SCOPE_INHERITABLE(" — this container and everything inside it"),

    /** Suffix for an authorization that applies to the resource itself only. */
    SCOPE_RESOURCE_ONLY(" — this resource only"),

    /** Suffix for an authorization inherited from an ancestor container. */
    SCOPE_INHERITED(" — inherited"),

    /** Suffix for an authorization on a document, which has no inside. */
    SCOPE_DOCUMENT(" — this document"),

    /** How the public is named in a verdict. */
    ANYONE("anyone"),

    /** How {@code acl:AuthenticatedAgent} is named in a verdict. */
    ANY_AUTHENTICATED_AGENT("any authenticated agent"),

    /** A container target in a verdict: path, so the reader sees the trailing slash. */
    TARGET_CONTAINER("%s and everything inside it"),

    /** root, owner, aclResource, modes — a pod was provisioned. */
    POD_CREATED("Created pod %s owned by %s: %s grants %s on this container and everything inside it."),

    /** root, aclResource — the pod was already there; nothing written. */
    POD_ALREADY_EXISTS("Already a pod: %s has an ACL (%s), which is left as it is; nothing written."),

    // ---- warnings (stderr) -----------------------------------------------------------------

    /** env var name */
    NO_CREDENTIAL(
            "No credential given (pass --token or set %s); the request will be anonymous and"
                    + " the server will refuse it"),

    // ---- failures (stderr, non-zero exit) --------------------------------------------------

    /** method, uri, status, target — an ACL could not be read or written */
    REFUSED(
            "Refused: %s %s answered HTTP %d. Reading or writing the ACL of %s requires acl:Control"
                    + " there, and the server enforces that — this tool cannot"),

    /** method, uri, status — a resource (not an ACL) could not be written */
    REFUSED_RESOURCE(
            "Refused: %s %s answered HTTP %d. Creating it requires acl:Write there, and the server"
                    + " enforces that — this tool cannot"),

    /** uri */
    CONFLICT(
            "Conflict: %s changed while it was being edited (HTTP 412, and again after re-reading)."
                    + " Nothing was written; run the command again"),

    /** target */
    NO_ACL_TO_THE_ROOT(
            "No ACL governs %s or any ancestor up to the storage root. The pod has no owner ACL —"
                    + " is cistern.owner.web-id set on the server?"),

    /** method, uri, status, body */
    UNEXPECTED_STATUS("Unexpected response: %s %s answered HTTP %d%s"),

    /** uri, cause */
    TRANSPORT("Could not reach %s: %s"),

    /** uri, header name */
    MISSING_ETAG("%s was served without an %s, so it cannot be edited safely; not writing"),

    /** value, keyword */
    INVALID_GRANTEE("'%s' is neither the word '%s' nor an absolute WebID URI"),

    /** value */
    INVALID_PATH(
            "'%s' is not a pod path: it must start with '/', name no '.' or '..' segments and no"
                    + " empty segment, and carry no fragment or query"),

    /** value */
    INVALID_BASE("'%s' is not a server URL: an absolute http(s) URL without fragment or query is needed"),

    /** value — a pod root that is not a container path */
    INVALID_ROOT("'%s' is not a pod root: a container path, ending in '/', is needed"),

    /** value — an owner that is not a WebID */
    INVALID_OWNER("'%s' is not a WebID: an absolute URI is needed"),

    /** The revoke was refused by the grant service (its own message follows). */
    REVOKE_REFUSED("Refused: %s"),

    /** Anything else. */
    FAILED("Failed: %s"),

    /** Body separator for {@link #UNEXPECTED_STATUS} when the server sent one. */
    BODY_SEPARATOR(": %s"),

    /** header name — a blank header value handed to a value type; a caller bug */
    BLANK_HEADER_VALUE("An %s value must not be blank"),

    /** Joins the items of a list in prose: modes, agents. */
    LIST_SEPARATOR(", ");

    private final String template;

    CliMessage(String template) {
        this.template = template;
    }

    /** This message with {@code args} substituted. */
    public String format(Object... args) {
        return String.format(template, args);
    }
}
