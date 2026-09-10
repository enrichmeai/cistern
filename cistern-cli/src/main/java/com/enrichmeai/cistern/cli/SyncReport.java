package com.enrichmeai.cistern.cli;

import com.enrichmeai.cistern.wac.AclResource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What {@code cistern sync} prints: one line per action — planned, for a dry run; done, as the
 * run goes — and a closing count. Resources are shown as paths on the server, media types as
 * sent, so the transcript reads as the requests it stands for.
 */
final class SyncReport {

    private final PodBase base;

    SyncReport(PodBase base) {
        this.base = Objects.requireNonNull(base, "base");
    }

    /** {@code --dry-run}: every action as it would be, then what that adds up to. */
    List<String> plan(SyncPlan plan, Path folder, PodPath target) {
        List<String> lines = new ArrayList<>();
        for (SyncAction action : plan.actions()) {
            lines.add(planned(action));
        }
        lines.add(CliMessage.SYNC_DRY_RUN.format(folder, target.value(),
                plan.creates(), plan.replaces(), plan.deletes(), plan.unchanged().size()));
        leftOnPod(plan).ifPresent(lines::add);
        return lines;
    }

    /** The line for one step, as it completes. */
    String step(SyncStep step) {
        String where = base.display(step.action().resource());
        return switch (step.action()) {
            case SyncAction.CreateContainer _ -> switch (step.effect()) {
                case PRESENT -> CliMessage.SYNC_PRESENT_CONTAINER.format(where);
                default -> CliMessage.SYNC_CREATED_CONTAINER.format(where);
            };
            case SyncAction.Create create -> CliMessage.SYNC_CREATED.format(where, create.document().mediaType().contentType());
            case SyncAction.Replace replace -> CliMessage.SYNC_REPLACED.format(where, replace.document().mediaType().contentType());
            case SyncAction.DeleteDocument _, SyncAction.DeleteContainer _ -> switch (step.effect()) {
                case ABSENT -> CliMessage.SYNC_ABSENT.format(where);
                default -> CliMessage.SYNC_DELETED.format(where);
            };
        };
    }

    /** The closing lines once every step is done. */
    List<String> summary(SyncPlan plan, Path folder, PodPath target) {
        List<String> lines = new ArrayList<>();
        lines.add(plan.isEmpty()
                ? CliMessage.SYNC_NOTHING_TO_SEND.format(folder, target.value(), plan.unchanged().size())
                : CliMessage.SYNC_SUMMARY.format(folder, target.value(),
                        plan.creates(), plan.replaces(), plan.deletes(), plan.unchanged().size()));
        leftOnPod(plan).ifPresent(lines::add);
        return lines;
    }

    /** The warning for an entry the walk passed over. */
    String skipped(LocalTree.Skipped skipped) {
        return switch (skipped.reason()) {
            case SYMBOLIC_LINK -> CliMessage.SYNC_SKIPPED_SYMLINK.format(skipped.path());
            case NOT_A_REGULAR_FILE -> CliMessage.SYNC_SKIPPED_SPECIAL.format(skipped.path());
            case ACL_NAME -> CliMessage.SYNC_SKIPPED_ACL.format(skipped.path(), AclResource.SUFFIX);
        };
    }

    private String planned(SyncAction action) {
        String where = base.display(action.resource());
        return switch (action) {
            case SyncAction.CreateContainer _ -> CliMessage.SYNC_PLAN_CREATE_CONTAINER.format(where);
            case SyncAction.Create create -> CliMessage.SYNC_PLAN_CREATE.format(where, create.document().mediaType().contentType());
            case SyncAction.Replace replace -> CliMessage.SYNC_PLAN_REPLACE.format(where, replace.document().mediaType().contentType());
            case SyncAction.DeleteDocument _, SyncAction.DeleteContainer _ -> CliMessage.SYNC_PLAN_DELETE.format(where);
        };
    }

    private static Optional<String> leftOnPod(SyncPlan plan) {
        return plan.leftOnPod().isEmpty()
                ? Optional.empty()
                : Optional.of(CliMessage.SYNC_LEFT_ON_POD.format(plan.leftOnPod().size(), Usage.DELETE_OPTION));
    }
}
