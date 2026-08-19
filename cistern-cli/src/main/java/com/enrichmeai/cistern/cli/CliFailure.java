package com.enrichmeai.cistern.cli;

import com.enrichmeai.cistern.core.ResourceIdentifier;

import java.util.Objects;

/**
 * The ways a command can fail, as a closed hierarchy so that the one place that turns a failure
 * into an exit code ({@link CisternCli}) switches over it exhaustively. Every message is a
 * {@link CliMessage} template.
 */
sealed class CliFailure extends RuntimeException
        permits CliFailure.Refused, CliFailure.Conflict, CliFailure.NoAcl,
                CliFailure.UnexpectedStatus, CliFailure.MissingValidator, CliFailure.Transport {

    private CliFailure(String message) {
        super(message);
    }

    private CliFailure(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * 401 or 403: the server enforced access and refused. Exit {@link ExitCode#REFUSED}. Two
     * constructors, because the explanation differs: an ACL is read or written under
     * {@code acl:Control} on the resource it governs; a resource is created under
     * {@code acl:Write}.
     */
    static final class Refused extends CliFailure {
        /** An ACL ({@code uri}) governing {@code target} could not be read or written. */
        Refused(PodMethod method, ResourceIdentifier uri, PodStatus status, ResourceIdentifier target) {
            super(CliMessage.REFUSED.format(method, uri.uri(), status.code(), target.uri()));
        }

        /** A resource ({@code uri}, not an ACL) could not be written. */
        Refused(PodMethod method, ResourceIdentifier uri, PodStatus status) {
            super(CliMessage.REFUSED_RESOURCE.format(method, uri.uri(), status.code()));
        }
    }

    /** 412 after the one retry: the ACL kept changing. Exit {@link ExitCode#CONFLICT}. */
    static final class Conflict extends CliFailure {
        Conflict(ResourceIdentifier acl) {
            super(CliMessage.CONFLICT.format(acl.uri()));
        }
    }

    /** The walk reached the storage root without finding an ACL — an unseeded pod. */
    static final class NoAcl extends CliFailure {
        NoAcl(ResourceIdentifier target) {
            super(CliMessage.NO_ACL_TO_THE_ROOT.format(target.uri()));
        }
    }

    /** A status this tool has no rule for. */
    static final class UnexpectedStatus extends CliFailure {
        UnexpectedStatus(PodMethod method, ResourceIdentifier uri, int status, String body) {
            super(CliMessage.UNEXPECTED_STATUS.format(method, uri.uri(), status,
                    body == null || body.isBlank() ? "" : CliMessage.BODY_SEPARATOR.format(body.strip())));
        }
    }

    /** A 200 without an {@code ETag}: no validator, so no safe conditional write. */
    static final class MissingValidator extends CliFailure {
        MissingValidator(ResourceIdentifier acl) {
            super(CliMessage.MISSING_ETAG.format(acl.uri(), HttpHeaderName.ETAG.fieldName()));
        }
    }

    /** The server could not be reached at all. */
    static final class Transport extends CliFailure {
        Transport(ResourceIdentifier uri, Throwable cause) {
            super(CliMessage.TRANSPORT.format(uri.uri(), describe(cause)), cause);
        }

        private static String describe(Throwable cause) {
            Objects.requireNonNull(cause, "cause");
            return cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
        }
    }
}
