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

    /** who, clients — a grantee constrained to particular clients ({@code cistern:client}). */
    VIA_CLIENTS("%s via %s"),

    /** A container target in a verdict: path, so the reader sees the trailing slash. */
    TARGET_CONTAINER("%s and everything inside it"),

    /** root, owner, aclResource, modes — a pod was provisioned. */
    POD_CREATED("Created pod %s owned by %s: %s grants %s on this container and everything inside it."),

    /** root, aclResource — the pod was already there; nothing written. */
    POD_ALREADY_EXISTS("Already a pod: %s has an ACL (%s), which is left as it is; nothing written."),

    // ---- sync: the plan (--dry-run) -------------------------------------------------------

    /** resource path */
    SYNC_PLAN_CREATE_CONTAINER("  create   %s"),

    /** resource path, media type */
    SYNC_PLAN_CREATE("  create   %s (%s)"),

    /** resource path, media type */
    SYNC_PLAN_REPLACE("  replace  %s (%s)"),

    /** resource path */
    SYNC_PLAN_DELETE("  delete   %s"),

    /** folder, target, creates, replaces, deletes, unchanged */
    SYNC_DRY_RUN("Dry run: %s → %s would create %d, replace %d, delete %d; %d unchanged. Nothing sent, nothing remembered."),

    // ---- sync: the transcript --------------------------------------------------------------

    /** resource path */
    SYNC_CREATED_CONTAINER("  created  %s"),

    /** resource path — the container was already there; left as it is */
    SYNC_PRESENT_CONTAINER("  present  %s (already on the pod; left as it is)"),

    /** resource path, media type */
    SYNC_CREATED("  created  %s (%s)"),

    /** resource path, media type */
    SYNC_REPLACED("  replaced %s (%s)"),

    /** resource path */
    SYNC_DELETED("  deleted  %s"),

    /** resource path — it was already gone */
    SYNC_ABSENT("  absent   %s (already gone from the pod)"),

    /** folder, target, creates, replaces, deletes, unchanged */
    SYNC_SUMMARY("Synced %s → %s: %d created, %d replaced, %d deleted; %d unchanged."),

    /** folder, target, unchanged — the plan was empty; no request was made */
    SYNC_NOTHING_TO_SEND("Nothing to send: %s already matches %s (%d unchanged)."),

    /** count, the --delete option — remembered resources gone locally, left in place */
    SYNC_LEFT_ON_POD("%d on the pod with no local counterpart, left as they are (pass %s to remove them)."),

    // ---- warnings (stderr) -----------------------------------------------------------------

    /** env var name */
    NO_CREDENTIAL(
            "No credential given (pass --token or set %s); the request will be anonymous and"
                    + " the server will refuse it"),

    /** relative path */
    SYNC_SKIPPED_SYMLINK("Skipped %s: symbolic links are not followed"),

    /** relative path */
    SYNC_SKIPPED_SPECIAL("Skipped %s: not a regular file"),

    /** relative path, the ACL suffix */
    SYNC_SKIPPED_ACL(
            "Skipped %s: a name ending in '%s' would be an access control list on the pod;"
                    + " grants are written with 'cistern grant'"),

    // ---- failures (stderr, non-zero exit) --------------------------------------------------

    /** method, uri, status, target — an ACL could not be read or written */
    REFUSED(
            "Refused: %s %s answered HTTP %d. Reading or writing the ACL of %s requires acl:Control"
                    + " there, and the server enforces that — this tool cannot"),

    /** method, uri, status — a resource (not an ACL) could not be written */
    REFUSED_RESOURCE(
            "Refused: %s %s answered HTTP %d. Writing it requires acl:Write there, and the server"
                    + " enforces that — this tool cannot"),

    /** method, uri, status — a resource (not an ACL) could not be read */
    REFUSED_RESOURCE_READ(
            "Refused: %s %s answered HTTP %d. Reading it requires acl:Read there, and the server"
                    + " enforces that — this tool cannot"),

    /** method, uri, status — a resource (not an ACL) could not be deleted */
    REFUSED_RESOURCE_DELETE(
            "Refused: %s %s answered HTTP %d. Deleting it requires acl:Write on it and on its"
                    + " container, and the server enforces that — this tool cannot"),

    /** uri */
    CONFLICT(
            "Conflict: %s changed while it was being edited (HTTP 412, and again after re-reading)."
                    + " Nothing was written; run the command again"),

    /** uri — a create-only sync write found the resource already on the pod */
    SYNC_CONFLICT_EXISTS(
            "Conflict: %s is already on the pod, and this folder has never sent it (HTTP 412);"
                    + " not written. The pod's copy stands: fetch it and reconcile, or remove it"
                    + " from the pod, then run again"),

    /** uri, state file name — a replace or delete found the pod's copy changed since it was sent */
    SYNC_CONFLICT_CHANGED(
            "Conflict: %s changed on the pod since this folder last sent it (HTTP 412); not"
                    + " written. The pod's copy stands: fetch it (GET, and note its ETag),"
                    + " reconcile locally, set that ETag as the entry's \"etag\" in %s, then run"
                    + " again"),

    /** uri — a container DELETE answered 409 */
    CONTAINER_NOT_EMPTY(
            "Not deleted: %s still holds resources this folder never sent (HTTP 409); left as it"
                    + " is. Remove them on the pod, or keep the folder locally"),

    /** target */
    NO_ACL_TO_THE_ROOT(
            "No ACL governs %s or any ancestor up to the storage root. The pod has no owner ACL —"
                    + " is cistern.owner.web-id set on the server?"),

    /** method, uri, status, body */
    UNEXPECTED_STATUS("Unexpected response: %s %s answered HTTP %d%s"),

    /** uri, cause */
    TRANSPORT("Could not reach %s: %s"),

    /** uri, header name */
    MISSING_ETAG("%s was served without an %s, so no later write to it could be conditional; stopping"),

    /** value, keyword */
    INVALID_GRANTEE("'%s' is neither the word '%s' nor an absolute WebID URI"),

    /** value */
    INVALID_CLIENT("'%s' is not a client identifier: an absolute URI is needed"),

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

    /** value — a sync target that is not a container path */
    INVALID_TARGET_CONTAINER("'%s' is not a container path: a path ending in '/' is needed"),

    /** value — a relative path with a leading '/', an empty, '.' or '..' segment */
    INVALID_RELATIVE_PATH(
            "'%s' cannot name an entry under the folder: no leading '/', no empty, '.' or '..' segment"),

    /** value — not sixty-four lower-case hex digits */
    INVALID_CONTENT_HASH("'%s' is not a SHA-256 in lower-case hex"),

    /** algorithm — the JVM lacks it; not a user error */
    DIGEST_UNAVAILABLE("This JVM has no %s digest"),

    /** folder */
    NOT_A_DIRECTORY("'%s' is not a folder"),

    /** path, cause */
    LOCAL_UNREADABLE("Could not read %s: %s"),

    /** path, cause */
    STATE_FILE_UNWRITABLE("Could not write %s: %s"),

    /** path, detail — the state file exists but is not this tool's shape */
    STATE_FILE_MALFORMED(
            "%s cannot be read as this tool's sync state (%s). Move it aside to start afresh;"
                    + " the next run then treats every file as never sent"),

    /** parser detail */
    STATE_FILE_NOT_JSON("not JSON: %s"),

    /** version found, version expected */
    STATE_FILE_VERSION("version %s, this tool reads %d"),

    /** field name */
    STATE_FILE_NO_FIELD("no '%s' field of the right type"),

    /** value, detail */
    STATE_FILE_BAD_TARGET("target '%s' is not a container on a server: %s"),

    /** path, target on file, target asked for */
    STATE_FILE_OTHER_TARGET(
            "%s says this folder was last sent to %s, not %s; a folder mirrors into one place."
                    + " Move the state file aside to send it somewhere else as well"),

    /** key, detail */
    STATE_FILE_BAD_ENTRY("entry '%s': %s"),

    STATE_FILE_ENTRY_NOT_OBJECT("not an object"),

    STATE_FILE_CONTAINER_HAS_FIELDS("a container entry carries no fields"),

    /** etag field, sha256 field */
    STATE_FILE_DOCUMENT_FIELDS("a document entry needs string '%s' and '%s'"),

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
