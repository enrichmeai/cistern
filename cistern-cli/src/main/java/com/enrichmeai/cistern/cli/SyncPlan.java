package com.enrichmeai.cistern.cli;

import com.enrichmeai.cistern.core.ResourceIdentifier;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * What a sync will do, decided entirely from the folder and the state file — no request is
 * made to plan, which is what lets {@code --dry-run} print it and send nothing.
 *
 * <p>The order is the brief's: containers first, parents before children, then documents,
 * then — only with {@code --delete} — removals, children before their containers. Within each
 * group the order is the {@link RelativePath}'s, so the same folder always yields the same
 * plan.
 *
 * <p>The pod is not consulted, so "unchanged" means <em>unchanged here since it was sent</em>.
 * A copy edited on the pod is left alone until the local file changes too, and then the
 * {@code If-Match} the write carries is what finds out — a 412, reported as a conflict, never
 * overwritten. That is the division of labour: the state file remembers, the server decides.
 *
 * @param actions   what will be done, in order
 * @param unchanged documents that will not be sent, because their content is what was sent
 * @param leftOnPod entries remembered but gone locally, left in place because {@code --delete}
 *                  was not given; empty when it was
 */
record SyncPlan(List<SyncAction> actions, List<RelativePath> unchanged, List<RelativePath> leftOnPod) {

    SyncPlan {
        actions = List.copyOf(actions);
        unchanged = List.copyOf(unchanged);
        leftOnPod = List.copyOf(leftOnPod);
    }

    /**
     * The plan that makes {@code target} on {@code base} match {@code tree}, given what
     * {@code state} says was sent before.
     *
     * @param delete whether resources remembered but gone locally are to be removed
     */
    static SyncPlan of(LocalTree tree, SyncState state, PodBase base, PodPath target, boolean delete) {
        Objects.requireNonNull(tree, "tree");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(target, "target");
        List<SyncAction> actions = new ArrayList<>();
        List<RelativePath> unchanged = new ArrayList<>();
        Set<RelativePath> present = new HashSet<>();

        for (RelativePath container : tree.containers()) {
            present.add(container);
            if (state.get(container).isEmpty()) {
                actions.add(new SyncAction.CreateContainer(container, base.resolve(container.under(target))));
            }
        }
        for (LocalTree.Document document : tree.documents()) {
            present.add(document.path());
            ResourceIdentifier resource = base.resolve(document.path().under(target));
            Optional<SyncedResource.Document> sent = state.get(document.path())
                    .filter(SyncedResource.Document.class::isInstance)
                    .map(SyncedResource.Document.class::cast);
            if (sent.isEmpty()) {
                // Never sent — or sent as a container of this name, which on the pod is a
                // different resource (Solid Protocol §3.1), so still a create.
                actions.add(new SyncAction.Create(document, resource));
            } else if (sent.get().sha256().equals(document.sha256())) {
                unchanged.add(document.path());
            } else {
                actions.add(new SyncAction.Replace(document, resource, sent.get().etag()));
            }
        }

        List<RelativePath> gone = state.resources().keySet().stream()
                .filter(path -> !present.contains(path))
                .sorted(Comparator.reverseOrder())
                .toList();
        if (!delete) {
            return new SyncPlan(actions, unchanged, gone);
        }
        for (RelativePath path : gone) {
            ResourceIdentifier resource = base.resolve(path.under(target));
            actions.add(switch (state.get(path).orElseThrow()) {
                case SyncedResource.Document document -> new SyncAction.DeleteDocument(path, resource, document.etag());
                case SyncedResource.Container _ -> new SyncAction.DeleteContainer(path, resource);
            });
        }
        return new SyncPlan(actions, unchanged, List.of());
    }

    /** Whether there is nothing to send. */
    boolean isEmpty() {
        return actions.isEmpty();
    }

    /** How many actions are of {@code kind}. */
    long count(Class<? extends SyncAction> kind) {
        return actions.stream().filter(kind::isInstance).count();
    }

    /** Containers and documents to create. */
    long creates() {
        return count(SyncAction.CreateContainer.class) + count(SyncAction.Create.class);
    }

    long replaces() {
        return count(SyncAction.Replace.class);
    }

    /** Documents and containers to delete. */
    long deletes() {
        return count(SyncAction.DeleteDocument.class) + count(SyncAction.DeleteContainer.class);
    }
}
