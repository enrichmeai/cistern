package com.enrichmeai.cistern.mcp;

import com.enrichmeai.cistern.core.ResourceIdentifier;
import com.enrichmeai.cistern.wac.AccessRequirement;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Everything that can go wrong between a tool call and the pod, as a closed set (ground
 * rule 7). These signal through the reactive chain and are rendered into tool results in
 * exactly one place — {@link ToolResults#problem} — so the MCP layer has one translator, the
 * way cistern-webflux has one error mapper (ground rule 4).
 *
 * <p>{@link Refused} is the important one: a 401/403 from the server is an authorization
 * decision, not an error, and it is carried with the target so the rendered result can name
 * the resource and the modes the server required.
 */
abstract sealed class PodProblem extends RuntimeException
        permits PodProblem.Refused, PodProblem.PreconditionFailed, PodProblem.Unexpected,
        PodProblem.NoAcl, PodProblem.AclEditConflict, PodProblem.BadArgument,
        PodProblem.Transport {

    private PodProblem(String message) {
        super(message);
    }

    private PodProblem(String message, Throwable cause) {
        super(message, cause);
    }

    /** The server refused (401/403): WAC said no. The one outcome that is not a failure. */
    static final class Refused extends PodProblem {

        private final ResourceIdentifier target;
        private final PodStatus status;
        private final List<AccessRequirement> requirements;

        /**
         * @param requirements what the refused request needed, from the server's own
         *        {@code RequiredAccess} table — so the rendered refusal names exactly the
         *        modes the server checked, not a guess
         */
        Refused(ResourceIdentifier target, PodStatus status, List<AccessRequirement> requirements) {
            super(status.name());
            this.target = Objects.requireNonNull(target, "target");
            this.status = status;
            this.requirements = List.copyOf(requirements);
            if (!status.isRefusal()) {
                throw new IllegalArgumentException(status.name());
            }
        }

        /** The resource named in the request the server refused. */
        ResourceIdentifier target() {
            return target;
        }

        PodStatus status() {
            return status;
        }

        /** The mode(s) the server required, on which resource(s). */
        List<AccessRequirement> requirements() {
            return requirements;
        }
    }

    /** 412: the resource changed between the read the request was based on and the write. */
    static final class PreconditionFailed extends PodProblem {

        private final ResourceIdentifier target;

        PreconditionFailed(ResourceIdentifier target) {
            super(McpMessage.PRECONDITION_FAILED.format(target.uri()));
            this.target = target;
        }

        ResourceIdentifier target() {
            return target;
        }
    }

    /** Any status the front door has no rule for, carried with the server's own body. */
    static final class Unexpected extends PodProblem {

        private final ResourceIdentifier target;
        private final int status;
        private final byte[] body;
        private final Optional<String> contentType;

        Unexpected(ResourceIdentifier target, int status, byte[] body, Optional<String> contentType) {
            super(McpMessage.UNEXPECTED_STATUS.format(status, target.uri(), contentType.orElse("")));
            this.target = target;
            this.status = status;
            this.body = body;
            this.contentType = contentType;
        }

        ResourceIdentifier target() {
            return target;
        }

        int status() {
            return status;
        }

        byte[] body() {
            return body;
        }

        Optional<String> contentType() {
            return contentType;
        }
    }

    /** The ACL walk reached the storage root without finding one: the pod was never seeded. */
    static final class NoAcl extends PodProblem {

        NoAcl(ResourceIdentifier target) {
            super(McpMessage.NO_ACL.format(target.uri()));
        }
    }

    /** A grant/revoke lost its conditional write even after the one re-read-and-retry. */
    static final class AclEditConflict extends PodProblem {

        AclEditConflict(ResourceIdentifier acl) {
            super(McpMessage.ACL_EDIT_CONFLICT.format(acl.uri()));
        }
    }

    /** A tool argument that is missing, malformed, or names something outside the pod. */
    static final class BadArgument extends PodProblem {

        BadArgument(String message) {
            super(message);
        }
    }

    /** The pod could not be reached at all: not a decision, a wire failure. */
    static final class Transport extends PodProblem {

        Transport(ResourceIdentifier target, Throwable cause) {
            super(McpMessage.TRANSPORT_FAILED.format(target.uri(), String.valueOf(cause)), cause);
        }
    }
}
