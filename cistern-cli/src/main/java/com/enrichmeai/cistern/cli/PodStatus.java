package com.enrichmeai.cistern.cli;

import java.util.Optional;

/**
 * The HTTP status codes the CLI acts on, as observed from the server ({@code docs/INTEGRATION.md}
 * §8) — a closed set, so an enum (ground rule 7). Anything else is
 * {@link CliFailure.UnexpectedStatus}.
 */
enum PodStatus {

    /** {@code GET} of an existing ACL. */
    OK(200),

    /** {@code PUT} created the ACL. */
    CREATED(201),

    /** {@code PUT} replaced the ACL. */
    NO_CONTENT(204),

    /** No credential, or an invalid one: the operation is refused. */
    UNAUTHORIZED(401),

    /** Authenticated but not holding {@code acl:Control} on the resource: refused. */
    FORBIDDEN(403),

    /** The ACL does not exist here; the effective one is an ancestor's. */
    NOT_FOUND(404),

    /** {@code If-Match} / {@code If-None-Match} failed: the ACL changed since it was read. */
    PRECONDITION_FAILED(412);

    private final int code;

    PodStatus(int code) {
        this.code = code;
    }

    int code() {
        return code;
    }

    /** Whether {@code status} is one of the two refusals the server enforces Control with. */
    boolean isRefusal() {
        return this == UNAUTHORIZED || this == FORBIDDEN;
    }

    /** Whether {@code status} is a successful write. */
    boolean isWritten() {
        return this == CREATED || this == NO_CONTENT;
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
