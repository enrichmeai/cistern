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
                CliFailure.UnexpectedStatus, CliFailure.MissingValidator, CliFailure.Transport,
                CliFailure.ContainerNotEmpty, CliFailure.LocalFolder {

    private CliFailure(String message) {
        super(message);
    }

    private CliFailure(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * 401 or 403: the server enforced access and refused. Exit {@link ExitCode#REFUSED}. Two
     * constructors, because the explanation differs: an ACL is read or written under
     * {@code acl:Control} on the resource it governs; a resource is read under {@code acl:Read},
     * written under {@code acl:Write}, and deleted under {@code acl:Write} on it and on its
     * container — which mode was wanted follows from the method.
     */
    static final class Refused extends CliFailure {
        /** An ACL ({@code uri}) governing {@code target} could not be read or written. */
        Refused(PodMethod method, ResourceIdentifier uri, PodStatus status, ResourceIdentifier target) {
            super(CliMessage.REFUSED.format(method, uri.uri(), status.code(), target.uri()));
        }

        /** A resource ({@code uri}, not an ACL) could not be read, written or deleted. */
        Refused(PodMethod method, ResourceIdentifier uri, PodStatus status) {
            super(switch (method) {
                case GET, HEAD -> CliMessage.REFUSED_RESOURCE_READ.format(method, uri.uri(), status.code());
                case PUT -> CliMessage.REFUSED_RESOURCE.format(method, uri.uri(), status.code());
                case DELETE -> CliMessage.REFUSED_RESOURCE_DELETE.format(method, uri.uri(), status.code());
            });
        }
    }

    /**
     * 412. Exit {@link ExitCode#CONFLICT}. Two constructors, because what the person should do
     * differs: an ACL edit has already been retried once from a fresh read, and running the
     * command again is the answer; a sync write is never retried, the pod's copy stands, and the
     * answer depends on which precondition failed — a create found something already there, a
     * replace or delete found it changed.
     */
    static final class Conflict extends CliFailure {
        /** The ACL kept changing across the one retry. */
        Conflict(ResourceIdentifier acl) {
            super(CliMessage.CONFLICT.format(acl.uri()));
        }

        /** A sync write of {@code resource} under {@code failed} was refused by the server. */
        Conflict(ResourceIdentifier resource, WritePrecondition failed) {
            super(switch (failed) {
                case WritePrecondition.IfNoneMatchAny _ -> CliMessage.SYNC_CONFLICT_EXISTS.format(resource.uri());
                case WritePrecondition.IfMatch _ -> CliMessage.SYNC_CONFLICT_CHANGED.format(resource.uri(), SyncStateFile.NAME);
            });
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
        MissingValidator(ResourceIdentifier resource) {
            super(CliMessage.MISSING_ETAG.format(resource.uri(), HttpHeaderName.ETAG.fieldName()));
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

    /**
     * 409 on a container's {@code DELETE}: it still holds members this folder never sent, so the
     * server keeps it (Solid Protocol §5.4). Exit {@link ExitCode#FAILURE} — not a refusal of
     * the caller, and not a conflict a re-run resolves; someone has to decide about those
     * members.
     */
    static final class ContainerNotEmpty extends CliFailure {
        ContainerNotEmpty(ResourceIdentifier container) {
            super(CliMessage.CONTAINER_NOT_EMPTY.format(container.uri()));
        }
    }

    /**
     * The folder side of a sync could not be used: not a folder, unreadable, or a state file
     * this tool cannot read. Exit {@link ExitCode#FAILURE}; nothing has been sent.
     */
    static final class LocalFolder extends CliFailure {
        LocalFolder(CliMessage message, Object... args) {
            super(message.format(args));
        }
    }
}
