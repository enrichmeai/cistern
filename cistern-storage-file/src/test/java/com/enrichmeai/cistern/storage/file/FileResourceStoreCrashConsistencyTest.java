package com.enrichmeai.cistern.storage.file;

import com.enrichmeai.cistern.core.Representation;
import com.enrichmeai.cistern.core.ResourceIdentifier;
import com.enrichmeai.cistern.core.StoredResource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Kill-mid-write DoD (T1.3): crash debris planted by hand — orphan tmp files, content
 * without a commit record, records without content, half-created directories, torn
 * replaces — is ignored or healed by every read path, and the next write self-heals.
 */
class FileResourceStoreCrashConsistencyTest {

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

    private void assertChildren(ResourceIdentifier container, Set<ResourceIdentifier> expected) {
        StepVerifier.create(store.children(container).collectList())
                .assertNext(listed -> assertEquals(expected, new HashSet<>(listed)))
                .verifyComplete();
    }

    private void assertInvisible(ResourceIdentifier identifier) {
        StepVerifier.create(store.get(identifier)).verifyComplete();
        StepVerifier.create(store.exists(identifier)).expectNext(false).verifyComplete();
    }

    // ------------------------------------------------------------- orphan tmp files

    @Test
    void plantedOrphanTmpFilesAreIgnoredEverywhere() throws IOException {
        ResourceIdentifier doc = id("/a/doc.ttl");
        Representation rep = turtle("<#d> a <#Doc> .");
        putOk(doc, rep);

        // Simulate kills mid-write at every level: container dirs and .meta dirs.
        Files.write(tempDir.resolve(".tmp-crashed1"), "junk".getBytes(StandardCharsets.UTF_8));
        Files.write(tempDir.resolve("a").resolve(".tmp-crashed2"), "junk".getBytes(StandardCharsets.UTF_8));
        Files.write(tempDir.resolve("a").resolve(FileResourceStore.META_DIR).resolve(".tmp-crashed3"),
                "junk".getBytes(StandardCharsets.UTF_8));
        Files.write(tempDir.resolve(FileResourceStore.META_DIR).resolve(".tmp-crashed4"),
                "junk".getBytes(StandardCharsets.UTF_8));

        // get / children / exists ignore them entirely.
        StepVerifier.create(store.get(doc))
                .assertNext(stored -> assertArrayEquals(rep.data(), stored.representation().data()))
                .verifyComplete();
        assertChildren(id("/"), Set.of(id("/a/")));
        assertChildren(id("/a/"), Set.of(doc));
        StepVerifier.create(store.exists(doc)).expectNext(true).verifyComplete();
        StepVerifier.create(store.exists(id("/a/"))).expectNext(true).verifyComplete();
    }

    // ------------------------------------------- crash between the two commit steps

    @Test
    void contentFileWithoutCommitRecordIsInvisible() throws IOException {
        putOk(id("/anchor.ttl"), turtle("<#a> a <#Anchor> ."));

        // CREATE crashed after the content move, before the sidecar move.
        Files.write(tempDir.resolve("ghost.ttl"), "<#g> a <#Ghost> .".getBytes(StandardCharsets.UTF_8));

        assertInvisible(id("/ghost.ttl"));
        assertChildren(id("/"), Set.of(id("/anchor.ttl")));
    }

    @Test
    void commitRecordWithoutContentIsInvisible() throws IOException {
        putOk(id("/anchor.ttl"), turtle("<#a> a <#Anchor> ."));

        // Debris: a sidecar whose content file never landed (external tampering / partial delete).
        Files.write(tempDir.resolve(FileResourceStore.META_DIR).resolve("phantom.ttl.json"),
                new Sidecar("text/turtle", "0".repeat(64), java.time.Instant.parse("2026-01-01T00:00:00Z"))
                        .toJson().getBytes(StandardCharsets.UTF_8));

        assertInvisible(id("/phantom.ttl"));
        assertChildren(id("/"), Set.of(id("/anchor.ttl")));
    }

    @Test
    void halfCreatedContainerDirectoryIsInvisibleAndSelfHealsOnNextWrite() throws IOException {
        putOk(id("/anchor.ttl"), turtle("<#a> a <#Anchor> ."));

        // Intermediate-container creation crashed after mkdir, before .self.json landed.
        Files.createDirectories(tempDir.resolve("half").resolve(FileResourceStore.META_DIR));

        assertInvisible(id("/half/"));
        assertChildren(id("/"), Set.of(id("/anchor.ttl")));

        // The name is free: a document PUT of the same name self-heals the debris...
        Representation rep = turtle("<#h> a <#Healed> .");
        putOk(id("/half"), rep);
        StepVerifier.create(store.get(id("/half")))
                .assertNext(stored -> assertArrayEquals(rep.data(), stored.representation().data()))
                .verifyComplete();
        assertChildren(id("/"), Set.of(id("/anchor.ttl"), id("/half")));
        assertTrue(Files.isRegularFile(tempDir.resolve("half")), "debris dir replaced by content file");
    }

    @Test
    void tornReplaceIsHealedOnRead() throws IOException, NoSuchAlgorithmException {
        ResourceIdentifier doc = id("/torn.ttl");
        putOk(doc, turtle("<#v> <#is> 1 ."));

        // REPLACE crashed after the content move, before the sidecar move:
        // new bytes on disk, stale sidecar (old etag).
        byte[] newBytes = "<#v> <#is> 2 .".getBytes(StandardCharsets.UTF_8);
        Files.write(tempDir.resolve("torn.ttl"), newBytes);

        String healedEtag = sha256Hex("text/turtle", newBytes);
        StepVerifier.create(store.get(doc))
                .assertNext(stored -> {
                    assertArrayEquals(newBytes, stored.representation().data());
                    assertEquals("text/turtle", stored.representation().contentType());
                    assertEquals(healedEtag, stored.etag(),
                            "etag must be healed to match the surviving representation");
                })
                .verifyComplete();

        // The heal is persisted: the sidecar on disk now carries the recomputed etag.
        Sidecar onDisk = Sidecar.fromJson(Files.readString(
                tempDir.resolve(FileResourceStore.META_DIR).resolve("torn.ttl.json"),
                StandardCharsets.UTF_8));
        assertEquals(healedEtag, onDisk.etag());
    }

    @Test
    void debrisFromPartialDeleteIsInvisibleAndNameIsReusable() throws IOException {
        ResourceIdentifier doc = id("/fleeting.ttl");
        putOk(doc, turtle("<#f> a <#Fleeting> ."));

        // DELETE crashed after removing the sidecar (decommit), before removing content.
        Files.delete(tempDir.resolve(FileResourceStore.META_DIR).resolve("fleeting.ttl.json"));

        assertInvisible(doc);
        assertChildren(id("/"), Set.of());

        // The name is immediately reusable.
        Representation reborn = turtle("<#f> a <#Reborn> .");
        putOk(doc, reborn);
        StepVerifier.create(store.get(doc))
                .assertNext(stored -> assertArrayEquals(reborn.data(), stored.representation().data()))
                .verifyComplete();
    }

    // ------------------------------------------------------------- restart survival

    @Test
    void storeSurvivesRestartOverTheSameRoot() {
        ResourceIdentifier doc = id("/a/b/persisted.ttl");
        Representation rep = turtle("<#p> a <#Persisted> .");
        StoredResource[] first = new StoredResource[1];
        StepVerifier.create(store.put(doc, rep))
                .assertNext(stored -> first[0] = stored)
                .verifyComplete();
        assertNotNull(first[0]);

        FileResourceStore reopened = new FileResourceStore(tempDir);
        StepVerifier.create(reopened.get(doc))
                .assertNext(stored -> {
                    assertArrayEquals(rep.data(), stored.representation().data());
                    assertEquals(first[0].etag(), stored.etag());
                    assertEquals(first[0].lastModified(), stored.lastModified());
                })
                .verifyComplete();
        StepVerifier.create(reopened.exists(id("/a/b/"))).expectNext(true).verifyComplete();
        StepVerifier.create(reopened.children(id("/a/")).collectList())
                .assertNext(listed -> assertEquals(Set.of(id("/a/b/")), new HashSet<>(listed)))
                .verifyComplete();

        // lastModified stays monotonic across instances (persisted, not clock-derived).
        StepVerifier.create(reopened.put(doc, turtle("<#p> a <#Replaced> .")))
                .assertNext(stored -> assertFalse(stored.lastModified().isBefore(first[0].lastModified())))
                .verifyComplete();
    }

    private static String sha256Hex(String contentType, byte[] bytes) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(contentType.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update(bytes);
        return HexFormat.of().formatHex(digest.digest());
    }
}
