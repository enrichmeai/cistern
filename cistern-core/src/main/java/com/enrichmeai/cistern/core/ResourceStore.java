package com.enrichmeai.cistern.core;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Storage SPI — THE seam of the project. Everything above it (HTTP, WAC, MCP) is
 * backend-agnostic; everything below it (file, r2dbc, in-memory) is protocol-agnostic.
 *
 * <p>Contract rules (enforced by the shared {@code ResourceStoreContractTest} kit that
 * every backend must extend — see ticket T1.4):
 * <ul>
 *   <li>All methods are non-blocking; no method may return null or throw synchronously
 *       for domain conditions — absence is an empty Mono, conflicts are error signals
 *       carrying {@link CisternException} subtypes.</li>
 *   <li>{@code get} on a missing resource → empty Mono (the HTTP layer maps to 404).</li>
 *   <li>{@code put} creates or replaces; it MUST create missing intermediate containers
 *       (Solid Protocol §5.3) and MUST reject a PUT that would turn a container into a
 *       document or vice versa.</li>
 *   <li>{@code delete} of a non-empty container → error (HTTP 409, Solid Protocol §5.4).
 *       Deleting a resource MUST update the parent's containment index.</li>
 *   <li>{@code children} of a non-container → error; of a missing container → empty Flux.</li>
 *   <li>Containment triples are DERIVED from {@code children} by the core layer —
 *       backends do not store or serialize them.</li>
 * </ul>
 */
public interface ResourceStore {

    Mono<StoredResource> get(ResourceIdentifier identifier);

    /** Create or replace. Returns the stored state (with fresh etag/lastModified). */
    Mono<StoredResource> put(ResourceIdentifier identifier, Representation representation);

    Mono<Void> delete(ResourceIdentifier identifier);

    /** Direct members of a container (its containment listing), non-recursive. */
    Flux<ResourceIdentifier> children(ResourceIdentifier container);

    /** True if the resource exists (used for If-None-Match:* and slug collision checks). */
    Mono<Boolean> exists(ResourceIdentifier identifier);
}
