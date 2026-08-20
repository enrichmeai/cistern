package com.enrichmeai.cistern.mcp;

import java.util.Optional;

/**
 * The HTTP status codes the MCP front door acts on, as observed from the server
 * ({@code docs/INTEGRATION.md} §8) — a closed set, so an enum (ground rule 7). Anything else
 * surfaces as {@link PodProblem.Unexpected}, carrying the server's own problem document.
 */
enum PodStatus {

    /** A successful {@code GET}. */
    OK(200),

    /** {@code PUT} created the resource. */
    CREATED(201),

    /** {@code PUT} replaced it, or {@code DELETE} removed it. */
    NO_CONTENT(204),

    /** The bound credential authenticated nobody: the request was anonymous and refused. */
    UNAUTHORIZED(401),

    /** Authenticated, and the effective ACL does not grant the required mode: refused. */
    FORBIDDEN(403),

    /** Nothing there — for an ACL fetch, "the effective ACL is an ancestor's". */
    NOT_FOUND(404),

    /** {@code If-Match} / {@code If-None-Match} failed: the resource changed since it was read. */
    PRECONDITION_FAILED(412);

    private final int code;

    PodStatus(int code) {
        this.code = code;
    }

    int code() {
        return code;
    }

    /** Whether {@code this} is one of the two refusals WAC enforcement answers with. */
    boolean isRefusal() {
        return this == UNAUTHORIZED || this == FORBIDDEN;
    }

    static Optional<PodStatus> of(int code) {
        for (PodStatus status : values()) {
            if (status.code == code) {
                return Optional.of(status);
            }
        }
        return Optional.empty();
    }
}
