package com.enrichmeai.cistern.core.ldp;

import com.enrichmeai.cistern.core.CisternException;
import com.enrichmeai.cistern.core.InMemoryResourceStore;
import com.enrichmeai.cistern.core.Representation;
import com.enrichmeai.cistern.core.ResourceIdentifier;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T2.4's core write path: {@link LdpService#delete(ResourceIdentifier)} — Solid Protocol §5.4.
 *
 * <p>What is pinned here is the one rule the service adds on top of the storage contract (the
 * storage root is undeletable, §5.4), the outcomes it inherits from the store (404 for a
 * missing resource, 409 for a non-empty container), and the consequence the ticket calls out
 * as its own requirement: after a document is deleted the parent container no longer lists it.
 * That last one is architecturally free — containment is derived from {@code children()} at
 * read time — but "free by construction" is a claim, so it is asserted rather than assumed.
 */
class LdpServiceDeleteTest {

    private static final String BASE = "https://pod.example";

    private InMemoryResourceStore store;
    private LdpService service;

    @BeforeEach
    void freshService() {
        store = new InMemoryResourceStore();
        service = new LdpService(store);
        put(id("/"), turtle(""));
        put(id("/notes/"), turtle(""));
        put(id("/notes/a.ttl"), turtle("<> <https://vocab.example/k> \"v\" ."));
        put(id("/notes/b.ttl"), turtle("<> <https://vocab.example/k> \"w\" ."));
    }

    // ---------------------------------------------------------------- helpers

    private static ResourceIdentifier id(String path) {
        return new ResourceIdentifier(URI.create(BASE + path));
    }

    private static Representation turtle(String content) {
        return new Representation(Representation.TURTLE, content.getBytes(StandardCharsets.UTF_8));
    }

    private void put(ResourceIdentifier identifier, Representation representation) {
        StepVerifier.create(store.put(identifier, representation))
                .expectNextCount(1)
                .verifyComplete();
    }

    private void deleteSucceeds(ResourceIdentifier identifier) {
        StepVerifier.create(service.delete(identifier)).verifyComplete();
    }

    private void deleteSignals(ResourceIdentifier identifier, Class<? extends Throwable> expected) {
        StepVerifier.create(service.delete(identifier)).expectError(expected).verify();
    }

    /** StepVerifier, never {@code .block()} — the house rule holds in tests too (ground rule 3). */
    private boolean exists(ResourceIdentifier identifier) {
        AtomicReference<Boolean> kept = new AtomicReference<>();
        StepVerifier.create(store.exists(identifier).doOnNext(kept::set))
                .expectNextCount(1)
                .verifyComplete();
        return Boolean.TRUE.equals(kept.get());
    }

    /** The {@code ldp:contains} objects of a container, as read back through the service. */
    private Set<String> containment(ResourceIdentifier container) {
        AtomicReference<ResourceView> kept = new AtomicReference<>();
        StepVerifier.create(service.read(container).doOnNext(kept::set))
                .expectNextCount(1)
                .verifyComplete();
        ResourceView.Rdf rdf = assertInstanceOf(ResourceView.Rdf.class, kept.get(),
                "a container always reads back as an RDF source");
        Resource subject = rdf.graph().createResource(container.uri().toString());
        return rdf.graph().listStatements(subject, Ldp.CONTAINS, (RDFNode) null)
                .toList().stream()
                .map(statement -> statement.getObject().asResource().getURI())
                .collect(Collectors.toSet());
    }

    // ---------------------------------------------------------------- documents

    @Test
    @DisplayName("deleting a document completes empty and the resource is gone")
    void documentIsRemoved() {
        deleteSucceeds(id("/notes/a.ttl"));

        assertFalse(exists(id("/notes/a.ttl")), "the deleted document must be gone from the store");
        StepVerifier.create(service.read(id("/notes/a.ttl")))
                .expectError(CisternException.NotFound.class)
                .verify();
    }

    @Test
    @DisplayName("Solid Protocol §5.4: the parent's containment triple goes with the resource")
    void parentContainmentDropsTheDeletedChild() {
        assertTrue(containment(id("/notes/")).contains(BASE + "/notes/a.ttl"),
                "arrange: the parent lists the child before the delete");

        deleteSucceeds(id("/notes/a.ttl"));

        assertEquals(Set.of(BASE + "/notes/b.ttl"), containment(id("/notes/")),
                "§5.4: 'when a contained resource is deleted, the server MUST also remove the"
                        + " corresponding containment triple' — the sibling must survive it");
    }

    @Test
    @DisplayName("deleting a resource leaves its parent container itself in place")
    void parentSurvivesTheDeleteOfItsChild() {
        deleteSucceeds(id("/notes/a.ttl"));

        assertTrue(exists(id("/notes/")), "only the target is removed, never its ancestors");
    }

    @Test
    @DisplayName("a missing resource is a NotFound signal, not a silent success")
    void missingResourceSignalsNotFound() {
        deleteSignals(id("/notes/nope.ttl"), CisternException.NotFound.class);
    }

    @Test
    @DisplayName("deleting the same resource twice: the second attempt is a NotFound")
    void secondDeleteIsNotFound() {
        deleteSucceeds(id("/notes/a.ttl"));
        deleteSignals(id("/notes/a.ttl"), CisternException.NotFound.class);
    }

    // ---------------------------------------------------------------- containers

    @Test
    @DisplayName("Solid Protocol §5.4: a non-empty container is refused with Conflict (409)")
    void nonEmptyContainerSignalsConflict() {
        deleteSignals(id("/notes/"), CisternException.Conflict.class);

        assertTrue(exists(id("/notes/")), "a refused delete mutates nothing");
        assertTrue(exists(id("/notes/a.ttl")), "least of all its members");
    }

    @Test
    @DisplayName("Solid Protocol §5.4: an empty container is deleted")
    void emptyContainerIsRemoved() {
        deleteSucceeds(id("/notes/a.ttl"));
        deleteSucceeds(id("/notes/b.ttl"));

        deleteSucceeds(id("/notes/"));

        assertFalse(exists(id("/notes/")), "a container with no members must be deletable");
        assertEquals(Set.of(), containment(id("/")),
                "and it must leave the storage root's containment listing");
    }

    // ---------------------------------------------------------------- root protection

    @Test
    @DisplayName("Solid Protocol §5.4: DELETE on the storage root signals MethodNotAllowed (405)")
    void storageRootIsUndeletable() {
        deleteSignals(id("/"), CisternException.MethodNotAllowed.class);

        assertTrue(exists(id("/")), "the root container must survive the refusal");
        assertTrue(exists(id("/notes/")), "and so must everything under it");
    }

    @Test
    @DisplayName("the root is refused even when it is empty — emptiness is not the rule")
    void emptyStorageRootIsStillUndeletable() {
        InMemoryResourceStore emptyStore = new InMemoryResourceStore();
        LdpService onEmptyPod = new LdpService(emptyStore);
        StepVerifier.create(emptyStore.put(id("/"), turtle("")))
                .expectNextCount(1)
                .verifyComplete();

        StepVerifier.create(onEmptyPod.delete(id("/")))
                .expectError(CisternException.MethodNotAllowed.class)
                .verify();
    }

    @Test
    @DisplayName("the root is refused before the store is consulted, so a bare pod 405s not 404s")
    void rootRefusalPrecedesTheStore() {
        LdpService onVirginPod = new LdpService(new InMemoryResourceStore());

        // Nothing has ever been written, so the store would answer NotFound. The 405 must win:
        // §5.4 makes the root's undeletability a property of WHICH resource is addressed, not
        // of whether it happens to exist.
        StepVerifier.create(onVirginPod.delete(id("/")))
                .expectError(CisternException.MethodNotAllowed.class)
                .verify();
    }

    @Test
    @DisplayName("an empty path names the same root as '/' and is refused identically")
    void emptyPathIsTheSameStorageRoot() {
        ResourceIdentifier emptyPath = new ResourceIdentifier(URI.create(BASE));

        assertTrue(emptyPath.isStorageRoot(),
                "RFC 3986 §6.2.3: an empty path is equivalent to '/', so it is the same root");
        deleteSignals(emptyPath, CisternException.MethodNotAllowed.class);
    }

    @Test
    @DisplayName("nothing below the root is the root")
    void onlyTheRootIsTheRoot() {
        assertTrue(id("/").isStorageRoot());
        assertFalse(id("/notes/").isStorageRoot(), "a top-level container is not the root");
        assertFalse(id("/a.ttl").isStorageRoot(), "nor is a top-level document");
        assertFalse(id("/notes/a.ttl").isStorageRoot());
    }

    // ---------------------------------------------------------------- reactive contract

    @Test
    @DisplayName("no signal escapes as a synchronous throw, root refusal included")
    void nothingThrowsBeforeSubscription() {
        assertDoesNotThrow(() -> service.delete(id("/")),
                "assembling the Mono must not throw: the refusal is an error signal (ground rule 3)");
        assertDoesNotThrow(() -> service.delete(id("/notes/nope.ttl")));
    }

    @Test
    @DisplayName("delete is cold: nothing is removed until something subscribes")
    void deleteIsCold() {
        service.delete(id("/notes/a.ttl"));

        assertTrue(exists(id("/notes/a.ttl")),
                "an unsubscribed Mono must not have touched the store");
    }
}
