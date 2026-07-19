package com.enrichmeai.cistern.storage.file;

import com.enrichmeai.cistern.core.Representation;
import com.enrichmeai.cistern.core.ResourceIdentifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.test.StepVerifier;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Structural collision-proofing (T1.3 design constraint 3): client resource names that
 * look exactly like the store's internal files must round-trip as ordinary resources and
 * must never touch the real internal files — plus URL-encoded, space and Unicode names.
 */
class FileResourceStoreNamingTest {

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

    private void putOk(ResourceIdentifier identifier, Representation representation) {
        StepVerifier.create(store.put(identifier, representation))
                .expectNextCount(1)
                .verifyComplete();
    }

    private void assertRoundTrip(ResourceIdentifier identifier, Representation representation) {
        StepVerifier.create(store.get(identifier))
                .assertNext(stored -> {
                    assertEquals(identifier, stored.identifier());
                    assertEquals(representation.contentType(), stored.representation().contentType());
                    assertArrayEquals(representation.data(), stored.representation().data());
                })
                .verifyComplete();
    }

    private void assertChildren(ResourceIdentifier container, Set<ResourceIdentifier> expected) {
        StepVerifier.create(store.children(container).collectList())
                .assertNext(listed -> assertEquals(expected, new HashSet<>(listed)))
                .verifyComplete();
    }

    // ------------------------------------------------- internal-name impersonators

    @Test
    void documentNamedLikeMetaDirectoryRoundTripsWithoutCorruptingInternals() {
        Representation rep = turtle("<#i> a <#Impersonator> .");
        putOk(id("/anchor.ttl"), turtle("<#a> a <#Anchor> ."));   // commits "/" and its .meta
        putOk(id("/.meta"), rep);

        assertRoundTrip(id("/.meta"), rep);
        assertChildren(id("/"), Set.of(id("/anchor.ttl"), id("/.meta")));

        // Stored under an encoded name; the REAL internal .meta directory is untouched.
        assertTrue(Files.isRegularFile(tempDir.resolve("%2Emeta")), "expected encoded content file");
        assertTrue(Files.isDirectory(tempDir.resolve(FileResourceStore.META_DIR)));
        assertTrue(Files.isRegularFile(
                tempDir.resolve(FileResourceStore.META_DIR).resolve(FileResourceStore.SELF_JSON)));

        // Deleting the impersonator leaves the internal metadata (and the sibling) intact.
        StepVerifier.create(store.delete(id("/.meta"))).verifyComplete();
        StepVerifier.create(store.exists(id("/.meta"))).expectNext(false).verifyComplete();
        assertTrue(Files.isDirectory(tempDir.resolve(FileResourceStore.META_DIR)));
        assertRoundTrip(id("/anchor.ttl"), turtle("<#a> a <#Anchor> ."));
    }

    @Test
    void containerNamedLikeMetaDirectoryRoundTrips() {
        Representation rep = turtle("<#c> a <#Child> .");
        putOk(id("/.meta/inner.ttl"), rep);

        StepVerifier.create(store.exists(id("/.meta/"))).expectNext(true).verifyComplete();
        assertRoundTrip(id("/.meta/inner.ttl"), rep);
        assertChildren(id("/"), Set.of(id("/.meta/")));
        assertChildren(id("/.meta/"), Set.of(id("/.meta/inner.ttl")));
        assertTrue(Files.isDirectory(tempDir.resolve("%2Emeta")), "expected encoded directory");
    }

    @Test
    void documentsNamedLikeTmpAndSelfFilesRoundTrip() {
        Representation tmpRep = turtle("<#t> a <#Tmp> .");
        Representation selfRep = turtle("<#s> a <#Self> .");
        putOk(id("/.tmp-123"), tmpRep);
        putOk(id("/x/.self.json"), selfRep);

        assertRoundTrip(id("/.tmp-123"), tmpRep);
        assertRoundTrip(id("/x/.self.json"), selfRep);
        assertChildren(id("/x/"), Set.of(id("/x/.self.json")));

        // Both live under encoded (dot-escaped) names, distinct from any internal file.
        assertTrue(Files.isRegularFile(tempDir.resolve("%2Etmp-123")));
        assertTrue(Files.isRegularFile(tempDir.resolve("x").resolve("%2Eself.json")));
    }

    @Test
    void documentNamedLikeAnotherDocumentsSidecarDoesNotCollide() {
        Representation note = turtle("<#n> a <#Note> .");
        Representation fake = new Representation("application/json", "{\"not\":\"a sidecar\"}"
                .getBytes(StandardCharsets.UTF_8));
        putOk(id("/note.ttl"), note);
        putOk(id("/note.ttl.json"), fake);          // exactly the sidecar name of /note.ttl

        assertRoundTrip(id("/note.ttl"), note);
        assertRoundTrip(id("/note.ttl.json"), fake);
        assertChildren(id("/"), Set.of(id("/note.ttl"), id("/note.ttl.json")));

        // Deleting /note.ttl removes ITS sidecar but must not touch /note.ttl.json.
        StepVerifier.create(store.delete(id("/note.ttl"))).verifyComplete();
        assertRoundTrip(id("/note.ttl.json"), fake);
        assertChildren(id("/"), Set.of(id("/note.ttl.json")));
    }

    // ------------------------------------------------- encoded / space / unicode names

    @Test
    void percentEncodedNamesRoundTripExactly() {
        Representation space = turtle("<#s> a <#Space> .");
        Representation qmark = turtle("<#q> a <#QMark> .");
        putOk(id("/enc%20space.ttl"), space);
        putOk(id("/q%3Fmark.ttl"), qmark);

        assertRoundTrip(id("/enc%20space.ttl"), space);
        assertRoundTrip(id("/q%3Fmark.ttl"), qmark);
        // children() must reconstruct the EXACT original identifiers (raw path preserved).
        assertChildren(id("/"), Set.of(id("/enc%20space.ttl"), id("/q%3Fmark.ttl")));

        // The '%' of the URI escape is itself escaped on disk (injective mapping).
        assertTrue(Files.isRegularFile(tempDir.resolve("enc%2520space.ttl")));
    }

    @Test
    void containerWithEncodedSpaceInNameRoundTrips() {
        Representation rep = turtle("<#d> a <#Doc> .");
        putOk(id("/my%20dir/doc.ttl"), rep);

        StepVerifier.create(store.exists(id("/my%20dir/"))).expectNext(true).verifyComplete();
        assertRoundTrip(id("/my%20dir/doc.ttl"), rep);
        assertChildren(id("/"), Set.of(id("/my%20dir/")));
        assertChildren(id("/my%20dir/"), Set.of(id("/my%20dir/doc.ttl")));
    }

    @Test
    void unicodeNamesRoundTripExactly() {
        Representation raw = turtle("<#u> a <#Unicode> .");
        Representation escaped = turtle("<#e> a <#Escaped> .");
        putOk(id("/ünïcode✓.ttl"), raw);              // raw Unicode in the URI
        putOk(id("/u%C3%BC.ttl"), escaped);           // percent-encoded UTF-8 stays distinct

        assertRoundTrip(id("/ünïcode✓.ttl"), raw);
        assertRoundTrip(id("/u%C3%BC.ttl"), escaped);
        assertChildren(id("/"), Set.of(id("/ünïcode✓.ttl"), id("/u%C3%BC.ttl")));
    }

    @Test
    void distinctRawSpellingsAreDistinctResources() {
        Representation plain = turtle("<#p> a <#Plain> .");
        Representation encoded = turtle("<#e> a <#Encoded> .");
        putOk(id("/ab.ttl"), plain);
        putOk(id("/%61b.ttl"), encoded);              // decodes to "ab.ttl" but is a distinct URI

        assertRoundTrip(id("/ab.ttl"), plain);
        assertRoundTrip(id("/%61b.ttl"), encoded);
        assertChildren(id("/"), Set.of(id("/ab.ttl"), id("/%61b.ttl")));
    }
}
