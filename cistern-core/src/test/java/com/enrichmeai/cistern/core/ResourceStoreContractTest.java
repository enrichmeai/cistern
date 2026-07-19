package com.enrichmeai.cistern.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Executable form of the {@link ResourceStore} javadoc contract (ticket T1.2). Every
 * backend MUST extend this class and pass it unchanged — it ships in the cistern-core
 * test-jar ({@code <type>test-jar</type>}, {@code <scope>test</scope>}).
 *
 * <p>Rules encoded (kept in lock-step with the {@code ResourceStore} javadoc):
 * <ol>
 *   <li>No call throws synchronously and no method returns null — domain conditions are
 *       signals delivered on subscription.</li>
 *   <li>{@code get} on a missing resource → empty Mono.</li>
 *   <li>{@code put} creates; the stored state carries a populated etag and a
 *       second-precision {@code lastModified}; {@code get} then returns it.</li>
 *   <li>{@code put} creates missing intermediate containers (Solid Protocol §5.3):
 *       ancestors of a deep path exist as containers and containment listings reflect
 *       them.</li>
 *   <li>{@code put} replace keeps the identity; the etag changes when the representation
 *       (bytes OR content type) changes (strong validators, RFC 9110 §8.8.3). An
 *       identical rewrite is deliberately NOT asserted either way — content-hashing
 *       backends are conformant.</li>
 *   <li>Kind-flip rejection, both directions (Solid Protocol §3.1): document put while
 *       the same-name container exists → {@link CisternException.Conflict}, and vice
 *       versa.</li>
 *   <li>{@code lastModified} is non-decreasing across successive writes (equal allowed —
 *       second precision).</li>
 *   <li>{@code delete} of a document updates the parent's containment listing.</li>
 *   <li>{@code delete} of a non-empty container → {@link CisternException.Conflict};
 *       of an emptied container → success (Solid Protocol §5.4).</li>
 *   <li>{@code delete} of a missing resource → {@link CisternException.NotFound}.</li>
 *   <li>{@code children} of a non-container → {@link IllegalArgumentException} error
 *       signal; of a missing container → empty Flux.</li>
 *   <li>{@code exists} tracks the lifecycle: false → true after put → false after
 *       delete.</li>
 * </ol>
 */
public abstract class ResourceStoreContractTest {

    /** Return a FRESH, EMPTY store. Called once per test method. */
    protected abstract ResourceStore newStore();

    private ResourceStore store;

    @BeforeEach
    void freshStore() {
        store = newStore();
    }

    // ---------------------------------------------------------------- helpers

    private static final String BASE = "https://pod.example";

    /** Identifier under a stable test authority; path must start with '/'. */
    protected static ResourceIdentifier id(String path) {
        return new ResourceIdentifier(URI.create(BASE + path));
    }

    protected static Representation turtle(String content) {
        return new Representation(Representation.TURTLE, content.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Rule 1: the call itself must not throw synchronously — errors travel through the
     * publisher. Also rejects null returns.
     */
    private static <P> P call(Supplier<P> spiCall) {
        P publisher = assertDoesNotThrow(spiCall::get,
                "SPI calls must never throw synchronously — signal errors through the publisher");
        assertNotNull(publisher, "SPI calls must never return null");
        return publisher;
    }

    private void assertChildren(ResourceIdentifier container, Set<ResourceIdentifier> expected) {
        StepVerifier.create(call(() -> store.children(container)).collectList())
                .assertNext(listed -> assertEquals(expected, new HashSet<>(listed),
                        "children(" + container.uri() + ")"))
                .verifyComplete();
    }

    // ---------------------------------------------------------------- get

    @Test
    void getOnMissingResourceCompletesEmpty() {
        StepVerifier.create(call(() -> store.get(id("/absent.ttl"))))
                .verifyComplete();
    }

    // ---------------------------------------------------------------- put

    @Test
    void putThenGetReturnsStoredResourceWithMetadata() {
        ResourceIdentifier doc = id("/note.ttl");
        Representation rep = turtle("<#it> a <#Note> .");

        StepVerifier.create(call(() -> store.put(doc, rep)))
                .assertNext(stored -> {
                    assertEquals(doc, stored.identifier(), "put must keep the identity");
                    assertNotNull(stored.etag(), "etag must be populated");
                    assertFalse(stored.etag().isBlank(), "etag must be populated");
                    assertSecondPrecision(stored.lastModified());
                })
                .verifyComplete();

        StepVerifier.create(call(() -> store.get(doc)))
                .assertNext(stored -> {
                    assertEquals(doc, stored.identifier());
                    assertEquals(rep.contentType(), stored.representation().contentType());
                    assertArrayEquals(rep.data(), stored.representation().data());
                    assertNotNull(stored.etag());
                    assertSecondPrecision(stored.lastModified());
                })
                .verifyComplete();
    }

    @Test
    void putCreatesMissingIntermediateContainers() {
        // Solid Protocol §5.3: "Servers MUST create intermediate containers and include
        // corresponding containment triples in container representations derived from
        // the URI path component of PUT and PATCH requests."
        ResourceIdentifier deep = id("/a/b/c.ttl");
        StepVerifier.create(call(() -> store.put(deep, turtle("<#x> a <#Y> ."))))
                .expectNextCount(1)
                .verifyComplete();

        for (String ancestor : new String[] {"/", "/a/", "/a/b/"}) {
            StepVerifier.create(call(() -> store.exists(id(ancestor))))
                    .expectNext(true)
                    .as("ancestor container must exist: " + ancestor)
                    .verifyComplete();
        }

        // Intermediate containers are real resources: get is consistent with exists.
        StepVerifier.create(call(() -> store.get(id("/a/"))))
                .assertNext(stored -> {
                    assertEquals(id("/a/"), stored.identifier());
                    assertTrue(stored.identifier().isContainer());
                    assertNotNull(stored.etag());
                    assertSecondPrecision(stored.lastModified());
                })
                .verifyComplete();

        assertChildren(id("/"), Set.of(id("/a/")));
        assertChildren(id("/a/"), Set.of(id("/a/b/")));
        assertChildren(id("/a/b/"), Set.of(deep));
    }

    @Test
    void putReplaceKeepsIdentityAndChangesEtagWhenBytesDiffer() {
        ResourceIdentifier doc = id("/replace-me.ttl");
        StoredResource v1 = putAndReturn(doc, turtle("<#v> <#is> 1 ."));
        StoredResource v2 = putAndReturn(doc, turtle("<#v> <#is> 2 ."));

        assertEquals(doc, v2.identifier(), "replace must keep the identity");
        assertNotEquals(v1.etag(), v2.etag(),
                "etag MUST change when the representation bytes change (RFC 9110 strong validator)");

        StepVerifier.create(call(() -> store.get(doc)))
                .assertNext(stored -> assertArrayEquals(
                        "<#v> <#is> 2 .".getBytes(StandardCharsets.UTF_8),
                        stored.representation().data()))
                .verifyComplete();
    }

    @Test
    void putReplaceChangesEtagWhenContentTypeDiffers() {
        ResourceIdentifier doc = id("/typed");
        byte[] sameBytes = "{}".getBytes(StandardCharsets.UTF_8);
        StoredResource v1 = putAndReturn(doc, new Representation("text/plain", sameBytes));
        StoredResource v2 = putAndReturn(doc, new Representation(Representation.JSON_LD, sameBytes));

        assertNotEquals(v1.etag(), v2.etag(),
                "etag MUST change when the content type changes, even with identical bytes");
    }

    @Test
    void putDocumentRejectedWhenContainerOfSameNameExists() {
        // Solid Protocol §3.1: "If two URIs differ only in the trailing slash, and the
        // server has associated a resource with one of them, then the other URI MUST NOT
        // correspond to another resource."
        putAndReturn(id("/foo/child.ttl"), turtle("<#c> a <#Child> ."));   // creates /foo/

        StepVerifier.create(call(() -> store.put(id("/foo"), turtle("<#d> a <#Doc> ."))))
                .expectError(CisternException.Conflict.class)
                .verify();
    }

    @Test
    void putContainerRejectedWhenDocumentOfSameNameExists() {
        putAndReturn(id("/bar"), turtle("<#d> a <#Doc> ."));

        StepVerifier.create(call(() -> store.put(id("/bar/"), turtle(""))))
                .expectError(CisternException.Conflict.class)
                .verify();
    }

    @Test
    void lastModifiedNonDecreasingAcrossWrites() {
        ResourceIdentifier doc = id("/clock.ttl");
        Instant t1 = putAndReturn(doc, turtle("<#v> <#is> 1 .")).lastModified();
        Instant t2 = putAndReturn(doc, turtle("<#v> <#is> 2 .")).lastModified();
        Instant t3 = putAndReturn(doc, turtle("<#v> <#is> 3 .")).lastModified();

        assertFalse(t2.isBefore(t1), "lastModified went backwards: " + t1 + " -> " + t2);
        assertFalse(t3.isBefore(t2), "lastModified went backwards: " + t2 + " -> " + t3);
    }

    // ---------------------------------------------------------------- delete

    @Test
    void deleteDocumentUpdatesParentContainmentAndGetIsEmpty() {
        ResourceIdentifier x = id("/c/x.ttl");
        ResourceIdentifier y = id("/c/y.ttl");
        putAndReturn(x, turtle("<#x> a <#X> ."));
        putAndReturn(y, turtle("<#y> a <#Y> ."));

        StepVerifier.create(call(() -> store.delete(x)))
                .verifyComplete();

        assertChildren(id("/c/"), Set.of(y));
        StepVerifier.create(call(() -> store.get(x))).verifyComplete();
        StepVerifier.create(call(() -> store.exists(x))).expectNext(false).verifyComplete();
    }

    @Test
    void deleteNonEmptyContainerSignalsConflict() {
        // Solid Protocol §5.4: "If the container contains resources, the server MUST
        // respond with the 409 status code and response body describing the error."
        ResourceIdentifier child = id("/full/x.ttl");
        putAndReturn(child, turtle("<#x> a <#X> ."));

        StepVerifier.create(call(() -> store.delete(id("/full/"))))
                .expectError(CisternException.Conflict.class)
                .verify();

        // The failed delete must not have mutated anything.
        StepVerifier.create(call(() -> store.exists(id("/full/")))).expectNext(true).verifyComplete();
        StepVerifier.create(call(() -> store.exists(child))).expectNext(true).verifyComplete();
    }

    @Test
    void deleteEmptiedContainerSucceeds() {
        // Solid Protocol §5.4: "When a DELETE request targets a container, the server
        // MUST delete the container if it contains no resources."
        ResourceIdentifier child = id("/tmp/x.ttl");
        putAndReturn(child, turtle("<#x> a <#X> ."));

        StepVerifier.create(call(() -> store.delete(child))).verifyComplete();
        StepVerifier.create(call(() -> store.delete(id("/tmp/")))).verifyComplete();

        StepVerifier.create(call(() -> store.exists(id("/tmp/")))).expectNext(false).verifyComplete();
        assertChildren(id("/"), Set.of());
    }

    @Test
    void deleteMissingResourceSignalsNotFound() {
        StepVerifier.create(call(() -> store.delete(id("/never-existed.ttl"))))
                .expectError(CisternException.NotFound.class)
                .verify();
    }

    // ---------------------------------------------------------------- children

    @Test
    void childrenOfNonContainerSignalsIllegalArgument() {
        ResourceIdentifier doc = id("/doc.ttl");
        putAndReturn(doc, turtle("<#d> a <#Doc> ."));

        // The call must not throw synchronously (rule 1); the error arrives as a signal.
        Flux<ResourceIdentifier> children = call(() -> store.children(doc));
        StepVerifier.create(children)
                .expectError(IllegalArgumentException.class)
                .verify();
    }

    @Test
    void childrenOfMissingContainerIsEmpty() {
        StepVerifier.create(call(() -> store.children(id("/no-such-container/"))))
                .verifyComplete();
    }

    // ---------------------------------------------------------------- exists

    @Test
    void existsTracksLifecycle() {
        ResourceIdentifier doc = id("/life.ttl");

        StepVerifier.create(call(() -> store.exists(doc))).expectNext(false).verifyComplete();
        putAndReturn(doc, turtle("<#l> a <#Life> ."));
        StepVerifier.create(call(() -> store.exists(doc))).expectNext(true).verifyComplete();
        StepVerifier.create(call(() -> store.delete(doc))).verifyComplete();
        StepVerifier.create(call(() -> store.exists(doc))).expectNext(false).verifyComplete();
    }

    // ---------------------------------------------------------------- plumbing

    /** Execute a put via StepVerifier (no .block()) and hand back the emitted state. */
    private StoredResource putAndReturn(ResourceIdentifier identifier, Representation representation) {
        StoredResource[] captured = new StoredResource[1];
        StepVerifier.create(call(() -> store.put(identifier, representation)))
                .assertNext(stored -> captured[0] = stored)
                .verifyComplete();
        assertNotNull(captured[0], "put must emit the stored state");
        return captured[0];
    }

    private static void assertSecondPrecision(Instant lastModified) {
        assertNotNull(lastModified, "lastModified must be populated");
        assertEquals(0, lastModified.getNano(),
                "lastModified must be second-precision (HTTP date resolution): " + lastModified);
    }

    private static void assertArrayEquals(byte[] expected, byte[] actual) {
        org.junit.jupiter.api.Assertions.assertArrayEquals(expected, actual);
    }
}
