package com.enrichmeai.cistern.webflux;

import com.enrichmeai.cistern.core.Representation;
import com.enrichmeai.cistern.core.ResourceIdentifier;
import com.enrichmeai.cistern.core.ResourceStore;
import com.enrichmeai.cistern.core.StoredResource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A {@link ResourceStore} that records every mutation asked of it and can be <em>sealed</em>,
 * after which any attempt to mutate fails the test outright.
 *
 * <h2>Why T2.5's DoD needs this and not an after-the-fact state check</h2>
 * The ticket's requirement is that a failed precondition never reaches the store. Asserting
 * afterwards that the resource still holds its old bytes does not establish that: an
 * implementation that wrote the new representation and then restored the old one would pass
 * such a check, as would one that created and deleted an intermediate container on the way, and
 * both would have taken a lock, bumped a modification time, and — on a backend with an audit
 * log or a replication stream — published a write that never should have existed. What has to
 * be observed is the <em>invocation</em>, which is what this records.
 *
 * <p>Reads are deliberately not restricted. Evaluating a precondition necessarily reads the
 * target's current validators (RFC 9110 §13.1), so {@code get}, {@code children} and
 * {@code exists} pass straight through; it is {@code put} and {@code delete} that must not
 * happen.
 *
 * <p>Delegates to the production {@code FileResourceStore} rather than reimplementing storage,
 * so the tests still run against real backend behaviour (ground rule 6) and this class adds
 * observation only.
 */
final class MutationRecordingResourceStore implements ResourceStore {

    /** What a recorded mutation was — the two methods that change stored state. */
    enum MutationKind {
        PUT,
        DELETE
    }

    /** One mutation the store was asked to perform, whether or not it was allowed to. */
    record Mutation(MutationKind kind, ResourceIdentifier target) {
    }

    private static final String SEALED_MESSAGE =
            "T2.5: the store was mutated (%s <%s>) while sealed — a precondition must be"
                    + " evaluated BEFORE any store mutation (RFC 9110 §13.2.1)";

    private final ResourceStore delegate;
    private final List<Mutation> mutations = new CopyOnWriteArrayList<>();

    private volatile boolean sealed;

    MutationRecordingResourceStore(ResourceStore delegate) {
        this.delegate = delegate;
    }

    // ---------------------------------------------------------------- observation

    /**
     * Runs {@code request} with the store sealed: while it runs, any {@code put} or
     * {@code delete} is recorded <em>and</em> fails with an {@link AssertionError}, so a
     * regression surfaces as a failing test rather than as a quietly different status code.
     *
     * <p>Clears the recording first, so {@link #recordedMutations()} afterwards describes this
     * request alone and no test depends on another's fixtures.
     */
    void whileSealed(Runnable request) {
        mutations.clear();
        sealed = true;
        try {
            request.run();
        } finally {
            sealed = false;
        }
    }

    /** Every mutation attempted since the last {@link #whileSealed} began. */
    List<Mutation> recordedMutations() {
        return List.copyOf(mutations);
    }

    // ---------------------------------------------------------------- ResourceStore

    @Override
    public Mono<StoredResource> get(ResourceIdentifier identifier) {
        return delegate.get(identifier);
    }

    @Override
    public Mono<StoredResource> put(ResourceIdentifier identifier, Representation representation) {
        return Mono.defer(() -> record(MutationKind.PUT, identifier)
                ? Mono.error(sealedFailure(MutationKind.PUT, identifier))
                : delegate.put(identifier, representation));
    }

    @Override
    public Mono<Void> delete(ResourceIdentifier identifier) {
        return Mono.defer(() -> record(MutationKind.DELETE, identifier)
                ? Mono.error(sealedFailure(MutationKind.DELETE, identifier))
                : delegate.delete(identifier));
    }

    @Override
    public Flux<ResourceIdentifier> children(ResourceIdentifier container) {
        return delegate.children(container);
    }

    @Override
    public Mono<Boolean> exists(ResourceIdentifier identifier) {
        return delegate.exists(identifier);
    }

    /** Records the attempt and reports whether the store was sealed against it. */
    private boolean record(MutationKind kind, ResourceIdentifier target) {
        mutations.add(new Mutation(kind, target));
        return sealed;
    }

    private static AssertionError sealedFailure(MutationKind kind, ResourceIdentifier target) {
        return new AssertionError(SEALED_MESSAGE.formatted(kind, target.uri()));
    }
}
