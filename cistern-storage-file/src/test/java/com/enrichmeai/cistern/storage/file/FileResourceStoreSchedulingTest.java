package com.enrichmeai.cistern.storage.file;

import com.enrichmeai.cistern.core.Representation;
import com.enrichmeai.cistern.core.ResourceIdentifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.test.StepVerifier;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Design constraint 1 (T1.3): all file I/O is blocking, so every operation must execute
 * on {@code Schedulers.boundedElastic()} — never on the subscribing thread. Signals are
 * emitted on the thread that ran the work, so asserting the emission thread proves it.
 */
class FileResourceStoreSchedulingTest {

    private static final String BASE = "https://pod.example";

    @TempDir
    Path tempDir;

    private FileResourceStore store;

    @BeforeEach
    void newStore() {
        store = new FileResourceStore(tempDir);
    }

    private static ResourceIdentifier id(String path) {
        return new ResourceIdentifier(URI.create(BASE + path));
    }

    private static Representation turtle(String content) {
        return new Representation(Representation.TURTLE, content.getBytes(StandardCharsets.UTF_8));
    }

    private static void assertBoundedElastic(String thread, String operation) {
        assertTrue(thread.startsWith("boundedElastic"),
                operation + " must run on boundedElastic, ran on: " + thread);
    }

    @Test
    void allOperationsRunOnBoundedElastic() {
        ResourceIdentifier doc = id("/threaded.ttl");

        StepVerifier.create(store.put(doc, turtle("<#t> a <#T> ."))
                        .map(stored -> Thread.currentThread().getName()))
                .assertNext(thread -> assertBoundedElastic(thread, "put"))
                .verifyComplete();

        StepVerifier.create(store.get(doc)
                        .map(stored -> Thread.currentThread().getName()))
                .assertNext(thread -> assertBoundedElastic(thread, "get"))
                .verifyComplete();

        StepVerifier.create(store.exists(doc)
                        .map(found -> Thread.currentThread().getName()))
                .assertNext(thread -> assertBoundedElastic(thread, "exists"))
                .verifyComplete();

        StepVerifier.create(store.children(id("/"))
                        .map(child -> Thread.currentThread().getName()))
                .assertNext(thread -> assertBoundedElastic(thread, "children"))
                .verifyComplete();

        AtomicReference<String> deleteThread = new AtomicReference<>();
        StepVerifier.create(store.delete(doc)
                        .doOnSuccess(ignored -> deleteThread.set(Thread.currentThread().getName())))
                .verifyComplete();
        assertBoundedElastic(deleteThread.get(), "delete");
    }
}
