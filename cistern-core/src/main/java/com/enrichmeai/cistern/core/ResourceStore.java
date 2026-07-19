package com.enrichmeai.cistern.core;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Storage SPI — THE seam of the project. Everything above it (HTTP, WAC, MCP) is
 * backend-agnostic; everything below it (file, r2dbc, in-memory) is protocol-agnostic.
 *
 * <p>Contract rules (enforced by the shared {@code ResourceStoreContractTest} kit — the
 * T1.2 test-jar — which every backend must extend):
 * <ul>
 *   <li>All methods are non-blocking; no method may return null or throw synchronously —
 *       every domain condition is delivered as a signal on subscription. Absence on read
 *       is an empty Mono; domain failures are error signals carrying
 *       {@link CisternException} subtypes.</li>
 *   <li>{@code get} on a missing resource → empty Mono (the HTTP layer maps to 404).</li>
 *   <li>{@code put} creates or replaces, and MUST create missing intermediate containers
 *       (Solid Protocol §5.3). The returned {@link StoredResource} carries a populated
 *       etag and a second-precision {@code lastModified} instant.</li>
 *   <li>Kind-flip rejection, both directions: {@code /foo} and {@code /foo/} are distinct
 *       identifiers, but one name cannot be both (Solid Protocol §3.1) — {@code put} of
 *       document {@code /foo} while container {@code /foo/} exists →
 *       {@link CisternException.Conflict}, and vice versa.</li>
 *   <li>ETags are strong validators (RFC 9110 §8.8.3): the etag MUST change whenever the
 *       representation (bytes or content type) changes across writes. A rewrite with an
 *       identical representation is NOT required to change it — content-hashing backends
 *       are conformant.</li>
 *   <li>{@code lastModified} is non-decreasing across successive writes to the same
 *       resource (equal allowed — second precision).</li>
 *   <li>{@code delete} of a missing resource → error signal
 *       {@link CisternException.NotFound} (mapped to 404 in T2.6): {@code Mono<Void>}
 *       cannot distinguish absence from success by emptiness.</li>
 *   <li>{@code delete} of a non-empty container → {@link CisternException.Conflict}
 *       (HTTP 409, Solid Protocol §5.4). Deleting a resource MUST update the parent's
 *       containment index.</li>
 *   <li>{@code children} of a non-container → {@link IllegalArgumentException} error
 *       signal (a caller programming error, not a domain condition — still signalled,
 *       never thrown synchronously); of a missing container → empty Flux.</li>
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
