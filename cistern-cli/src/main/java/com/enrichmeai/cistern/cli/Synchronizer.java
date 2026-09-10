package com.enrichmeai.cistern.cli;

import com.enrichmeai.cistern.core.ResourceIdentifier;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Objects;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Perform a {@link SyncPlan}, in its order, one request at a time, remembering each step in the
 * state file the moment it succeeds — so a run that stops three files in has recorded those
 * three, and the next run picks up at the fourth.
 *
 * <p>Every write is conditional and the server is the judge. A create is
 * {@code If-None-Match: *}; a replace and a document delete are {@code If-Match} on the
 * validator the state file holds; and a 412 to any of them surfaces as
 * {@link CliFailure.Conflict} — the pod's copy stands, nothing is retried, the run stops and
 * says which resource and why. The one unconditional request is a container's
 * {@code DELETE}: no validator is recorded for a container (see
 * {@link SyncedResource.Container}) and the server refuses to delete one that still has
 * members (Solid Protocol §5.4, 409), so there is nothing a precondition would protect.
 *
 * <p>A document's validator is taken from the {@code PUT} response where the server sends one
 * — it does for a non-RDF source, whose bytes it keeps verbatim — and asked for with a
 * {@code HEAD} where it does not: RFC 9110 §9.3.4 forbids a validator on the response to a
 * {@code PUT} whose content the server transformed, which is every Turtle or JSON-LD document.
 * Either way the state file ends up holding what a later {@code If-Match} needs.
 *
 * <p>The local file is read and the state file written inside the chain. This is a
 * command-line tool with one thing to do; ground rule 3 is about request threads on the
 * server, and there are none here.
 */
final class Synchronizer {

    private final PodTransport client;

    Synchronizer(PodTransport client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    /** Perform {@code plan}; one {@link SyncStep} per action as it completes, in plan order. */
    Flux<SyncStep> sync(SyncPlan plan, SyncStateFile state) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(state, "state");
        return Flux.fromIterable(plan.actions())
                .concatMap(action -> perform(action, state));
    }

    private Mono<SyncStep> perform(SyncAction action, SyncStateFile state) {
        return switch (action) {
            case SyncAction.CreateContainer create -> client.createContainer(create.resource())
                    .map(Synchronizer::effectOf)
                    .doOnNext(_ -> state.remember(create.path(), new SyncedResource.Container()))
                    .map(effect -> new SyncStep(action, effect));
            case SyncAction.Create create -> send(create.document(), create.resource(),
                    new WritePrecondition.IfNoneMatchAny(), state)
                    .thenReturn(new SyncStep(action, SyncEffect.CREATED));
            case SyncAction.Replace replace -> send(replace.document(), replace.resource(),
                    new WritePrecondition.IfMatch(replace.etag()), state)
                    .thenReturn(new SyncStep(action, SyncEffect.REPLACED));
            case SyncAction.DeleteDocument delete -> client
                    .delete(delete.resource(), new WritePrecondition.IfMatch(delete.etag()))
                    .map(Synchronizer::effectOf)
                    .doOnNext(_ -> state.forget(delete.path()))
                    .map(effect -> new SyncStep(action, effect));
            case SyncAction.DeleteContainer delete -> client.deleteContainer(delete.resource())
                    .map(Synchronizer::effectOf)
                    .doOnNext(_ -> state.forget(delete.path()))
                    .map(effect -> new SyncStep(action, effect));
        };
    }

    /** Read, {@code PUT}, obtain the validator, remember. */
    private Mono<Void> send(LocalTree.Document document, ResourceIdentifier resource,
                            WritePrecondition precondition, SyncStateFile state) {
        return Mono.fromCallable(() -> read(document))
                .flatMap(bytes -> client.put(resource, bytes, document.mediaType(), precondition))
                .flatMap(validator -> validator.map(Mono::just).orElseGet(() -> client.validator(resource)))
                .doOnNext(etag -> state.remember(document.path(), new SyncedResource.Document(etag, document.sha256())))
                .then();
    }

    private static byte[] read(LocalTree.Document document) {
        try {
            return Files.readAllBytes(document.file());
        } catch (IOException e) {
            throw new CliFailure.LocalFolder(CliMessage.LOCAL_UNREADABLE, document.file(), SyncStateFile.describe(e));
        }
    }

    private static SyncEffect effectOf(ContainerCreation creation) {
        return switch (creation) {
            case CREATED -> SyncEffect.CREATED;
            case ALREADY_THERE -> SyncEffect.PRESENT;
        };
    }

    private static SyncEffect effectOf(Deletion deletion) {
        return switch (deletion) {
            case DELETED -> SyncEffect.DELETED;
            case ALREADY_ABSENT -> SyncEffect.ABSENT;
        };
    }
}
