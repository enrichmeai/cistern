package com.enrichmeai.cistern.core;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reference {@link ResourceStore} for tests (test scope, shipped in the cistern-core
 * test-jar). Concurrent-map based, content-hash etags (SHA-256 over content type +
 * bytes, so an identical rewrite keeps its etag — allowed by the contract). Exists to
 * prove the {@link ResourceStoreContractTest} kit is satisfiable and to back tests in
 * higher layers without touching disk.
 *
 * <p>Mutations synchronize on the store so containment invariants stay coherent; the
 * work is pure in-memory computation, so this never blocks meaningfully.
 */
public final class InMemoryResourceStore implements ResourceStore {

    private record Entry(Representation representation, String etag, Instant lastModified) {
    }

    private final Map<ResourceIdentifier, Entry> resources = new ConcurrentHashMap<>();
    private final Object lock = new Object();

    @Override
    public Mono<StoredResource> get(ResourceIdentifier identifier) {
        return Mono.defer(() -> Mono.justOrEmpty(
                Optional.ofNullable(resources.get(identifier))
                        .map(entry -> toStored(identifier, entry))));
    }

    @Override
    public Mono<StoredResource> put(ResourceIdentifier identifier, Representation representation) {
        return Mono.fromCallable(() -> doPut(identifier, representation));
    }

    @Override
    public Mono<Void> delete(ResourceIdentifier identifier) {
        return Mono.fromRunnable(() -> doDelete(identifier));
    }

    @Override
    public Flux<ResourceIdentifier> children(ResourceIdentifier container) {
        return Flux.defer(() -> {
            if (!container.isContainer()) {
                return Flux.error(new IllegalArgumentException(
                        "children() requires a container identifier (trailing slash): " + container.uri()));
            }
            List<ResourceIdentifier> members = new ArrayList<>();
            synchronized (lock) {
                for (ResourceIdentifier candidate : resources.keySet()) {
                    if (candidate.parent().filter(container::equals).isPresent()) {
                        members.add(candidate);
                    }
                }
            }
            return Flux.fromIterable(members);
        });
    }

    @Override
    public Mono<Boolean> exists(ResourceIdentifier identifier) {
        return Mono.defer(() -> Mono.just(resources.containsKey(identifier)));
    }

    // ---------------------------------------------------------------- internals

    private StoredResource doPut(ResourceIdentifier identifier, Representation representation) {
        synchronized (lock) {
            // Validate the ENTIRE chain before creating anything: a failed put must
            // mutate nothing observable (contract rule; no partial intermediate chains).
            List<ResourceIdentifier> missing = missingAncestors(identifier);
            rejectKindFlip(identifier);
            missing.forEach(this::rejectKindFlip);

            for (ResourceIdentifier ancestor : missing) {
                resources.put(ancestor, newEntry(
                        new Representation(Representation.TURTLE, new byte[0]), null));
            }
            Entry previous = resources.get(identifier);
            Entry entry = newEntry(representation, previous);
            resources.put(identifier, entry);
            return toStored(identifier, entry);
        }
    }

    private void doDelete(ResourceIdentifier identifier) {
        synchronized (lock) {
            if (!resources.containsKey(identifier)) {
                throw new CisternException.NotFound("No such resource: " + identifier.uri());
            }
            if (identifier.isContainer() && hasMembers(identifier)) {
                throw new CisternException.Conflict(
                        "Container is not empty: " + identifier.uri());
            }
            resources.remove(identifier);
        }
    }

    /** One NAME cannot be both container and document (Solid Protocol §3.1). */
    private void rejectKindFlip(ResourceIdentifier identifier) {
        oppositeKind(identifier).ifPresent(sibling -> {
            if (resources.containsKey(sibling)) {
                throw new CisternException.Conflict(
                        identifier.uri() + " conflicts with existing " + sibling.uri()
                                + ": one name cannot be both a container and a document");
            }
        });
    }

    /** {@code /foo} for {@code /foo/} and vice versa; empty for the root container. */
    private static Optional<ResourceIdentifier> oppositeKind(ResourceIdentifier identifier) {
        URI uri = identifier.uri();
        String path = uri.getPath();
        if (path == null || path.isEmpty() || path.equals("/")) {
            return Optional.empty();
        }
        String flipped = path.endsWith("/") ? path.substring(0, path.length() - 1) : path + "/";
        return Optional.of(new ResourceIdentifier(uri.resolve(flipped)));
    }

    /** Missing ancestors of the identifier, root first (Solid Protocol §5.3). */
    private List<ResourceIdentifier> missingAncestors(ResourceIdentifier identifier) {
        Deque<ResourceIdentifier> chain = new ArrayDeque<>();
        Optional<ResourceIdentifier> ancestor = identifier.parent();
        while (ancestor.isPresent() && !resources.containsKey(ancestor.get())) {
            chain.addFirst(ancestor.get());
            ancestor = ancestor.get().parent();
        }
        return List.copyOf(chain);
    }

    private boolean hasMembers(ResourceIdentifier container) {
        return resources.keySet().stream()
                .anyMatch(candidate -> candidate.parent().filter(container::equals).isPresent());
    }

    private static Entry newEntry(Representation representation, Entry previous) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        if (previous != null && now.isBefore(previous.lastModified())) {
            now = previous.lastModified();   // monotonic even under clock skew
        }
        return new Entry(representation, etagFor(representation), now);
    }

    /** Content hash: changes whenever bytes or content type change; stable otherwise. */
    private static String etagFor(Representation representation) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(representation.contentType().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(representation.data());
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM without SHA-256", e);
        }
    }

    private static StoredResource toStored(ResourceIdentifier identifier, Entry entry) {
        return new StoredResource(identifier, entry.representation(), entry.etag(), entry.lastModified());
    }
}
