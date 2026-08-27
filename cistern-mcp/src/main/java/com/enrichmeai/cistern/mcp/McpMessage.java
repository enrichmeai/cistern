package com.enrichmeai.cistern.mcp;

/**
 * cistern-mcp's message catalogue (ground rule 7): every piece of human-readable text this
 * module produces — tool-result text, exception messages, log lines — is a template here,
 * never text inlined at a call site. Tool <em>metadata</em> (names, titles, descriptions,
 * argument descriptions) lives with the tool definitions in {@link PodTool} and
 * {@link ToolArgument}, because the MCP SDK needs it as definition constants, exactly as
 * cistern-cli keeps picocli usage text in {@code Usage}.
 *
 * <p>Templates are {@link String#format} patterns.
 */
public enum McpMessage {

    // ---------------------------------------------------------------- tool results: outcomes

    /** modes, target — what an allowed read/write/delete reports. */
    /** The Streamable HTTP door is open — logged because it widens who can reach the pod. */
    HTTP_TRANSPORT_WIRED("MCP Streamable HTTP transport serving %s; tools reach the pod at %s"),

    READ_OK("Read %s (%s, %d bytes)."),

    /** target — a non-text resource is reported, not dumped into the model's context. */
    READ_BINARY(
            "Read %s: %s, %d bytes. The content is binary and is not inlined here;"
                    + " fetch it over HTTP with your own credential if you need the bytes."),

    /** container, member count. */
    LISTED("Listed %s: %d member(s)."),

    /** member line. */
    MEMBER_LINE("  - %s"),

    /** target — a container with nothing in it. */
    LISTED_EMPTY("Listed %s: the container is empty."),

    /** target. */
    WRITTEN_CREATED("Created %s."),

    /** target. */
    WRITTEN_REPLACED("Replaced the content of %s."),

    /** target. */
    DELETED("Deleted %s."),

    /** grantee, modes, target. */
    GRANTED("Granted: %s may now %s %s."),

    /** grantee, modes, target. */
    ALREADY_GRANTED("Already granted: %s already holds %s on %s; nothing was written."),

    /** grantee, target. */
    REVOKED("Revoked: %s no longer holds anything on %s. The next request outside a"
            + " remaining grant will be refused — there is no cache to wait out."),

    /** grantee, target. */
    NOTHING_TO_REVOKE("Nothing to revoke: %s holds nothing on %s; nothing was written."),

    /** aclResource. */
    ACL_HOLDS("%s now holds:"),

    /** who, modes. */
    AUTHORIZATION_LINE("  - %s: %s"),

    /** The public, as an authorization subject. */
    ANYONE("anyone (foaf:Agent)"),

    /** target, decision count. */
    RECEIPTS("Receipts for %s: %d decision(s), newest last, one JSON object per line."),

    /** target. */
    RECEIPTS_EMPTY("Receipts for %s: no decisions recorded in the requested interval."),

    // ---------------------------------------------------------------- tool results: refusals

    /**
     * requirement description ("read on &lt;uri&gt;"), status — the honest refusal. The named
     * modes are computed by the same {@code RequiredAccess} table the server enforces with.
     */
    REFUSED("REFUSED: the pod denied %s (HTTP %d)."),

    /** mode token, resource — one requirement inside a refusal. */
    REQUIREMENT("%s on %s"),

    /** Joins two requirements of one request (a DELETE needs the resource and its parent). */
    REQUIREMENT_SEPARATOR(" and %s"),

    /** Appended to a 401 refusal: the bound credential authenticated nobody. */
    REFUSED_UNAUTHENTICATED(
            " The server did not recognise this connection's credential, so the request was"
                    + " anonymous. This is an authorization decision by the pod's owner-written"
                    + " policy, not a transport error."),

    /** Appended to a 403 refusal: authenticated, and the policy does not grant the mode. */
    REFUSED_FORBIDDEN(
            " The connection's identity is bound by the pod's configuration and its effective"
                    + " access-control policy does not grant the required mode. Do not retry, and"
                    + " do not attempt other credentials — report the refusal to the user; only"
                    + " the resource owner can change the policy."),

    // ---------------------------------------------------------------- tool results: failures

    /** resource — an ACL edit lost its conditional write twice. */
    ACL_EDIT_CONFLICT(
            "The ACL of %s kept changing between read and write; nothing was written. Retry the"
                    + " grant or revoke once the other editor is done."),

    /** resource — the write's ETag precondition failed. */
    PRECONDITION_FAILED(
            "The precondition failed: %s changed since the state your request was based on."
                    + " Re-read the resource and retry with its current ETag."),

    /** resource — the walk reached the root without finding any ACL. */
    NO_ACL("No access-control resource governs %s: the pod has not been provisioned"
            + " (no owner ACL was found up to the storage root)."),

    /** status, resource, server detail (problem+json title/detail or raw body). */
    UNEXPECTED_STATUS("The pod answered HTTP %d for %s: %s"),

    /** resource, cause. */
    TRANSPORT_FAILED("Could not reach the pod for %s: %s"),

    /** argument name. */
    ARGUMENT_MISSING("The '%s' argument is required."),

    /** argument name, value, reason. */
    ARGUMENT_INVALID("The '%s' argument is invalid (%s): %s"),

    /** value — the url argument names a resource outside the bound pod. */
    TARGET_OUTSIDE_POD(
            "'%s' is outside the pod this connection is bound to; use a path like /notes/week"
                    + " or an absolute URL under the pod's base URL."),

    /** value — list-container was pointed at a document. */
    TARGET_NOT_A_CONTAINER("'%s' does not name a container: container URLs end with '/'."),

    /** value — the modes array carried an unknown token. */
    MODE_UNKNOWN("'%s' is not an access mode; use read, write, append or control."),

    // ---------------------------------------------------------------- wiring and lifecycle

    /** env var name(s). */
    BRIDGE_ENV_MISSING(
            "cistern-mcp bridge: %s must be set in the launching connector's environment"),

    /** base url. */
    BRIDGE_STARTED("cistern-mcp bridge: serving MCP on stdio for the pod at %s"),

    BRIDGE_STOPPED("cistern-mcp bridge: input closed; shutting down"),

    /** connect base url. */
    EMBEDDED_STARTED(
            "cistern.mcp.enabled: serving MCP on this process's stdio; tool calls loop back"
                    + " over HTTP to %s through the same filter chain as every client"),

    EMBEDDED_STOPPED("cistern-mcp: MCP front door closed"),

    /** Why an enabled MCP door refuses to come up without a credential. */
    MCP_REQUIRES_CREDENTIAL(
            "cistern.mcp.enabled is set but cistern.mcp.credential is not: an MCP connection is"
                    + " bound to exactly one principal (T6.2), and a door with no credential"
                    + " would silently act as the anonymous agent");

    private final String template;

    McpMessage(String template) {
        this.template = template;
    }

    /** The message with {@code args} substituted into the template. */
    public String format(Object... args) {
        return String.format(template, args);
    }
}
