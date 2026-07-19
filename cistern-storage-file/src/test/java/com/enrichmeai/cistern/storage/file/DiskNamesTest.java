package com.enrichmeai.cistern.storage.file;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiskNamesTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "plain-name_1.ttl",
            ".meta", ".self.json", ".self.content", ".tmp-abc", ".", "..", "...",
            "enc%20space.ttl", "%2F", "%252F", "q%3Fmark", "100%",
            "ünïcode✓", "naïve.ttl", "日本語",
            "a b c", "trailing.", "-dash", "_underscore"
    })
    void encodeDecodeRoundTripsExactly(String rawSegment) {
        assertEquals(rawSegment, DiskNames.decode(DiskNames.encode(rawSegment)));
    }

    @ParameterizedTest
    @ValueSource(strings = {".meta", ".self.json", ".tmp-xyz", ".", "..", ".hidden"})
    void encodedNamesNeverStartWithDot(String rawSegment) {
        String encoded = DiskNames.encode(rawSegment);
        assertFalse(encoded.startsWith("."),
                "encoded name must never collide with internal dot-files: " + encoded);
        assertFalse(DiskNames.isInternal(encoded));
    }

    @Test
    void internalNamesAreDotPrefixed() {
        assertTrue(DiskNames.isInternal(FileResourceStore.META_DIR));
        assertTrue(DiskNames.isInternal(FileResourceStore.SELF_JSON));
        assertTrue(DiskNames.isInternal(FileResourceStore.SELF_CONTENT));
        assertTrue(DiskNames.isInternal(FileResourceStore.TMP_PREFIX + "uuid"));
    }

    @Test
    void encodingIsInjectiveOnLookalikes() {
        // A client name that IS someone's encoded form must encode differently.
        assertNotEquals(DiskNames.encode("a b"), DiskNames.encode("a%20b"));
        assertNotEquals(DiskNames.encode("%2Emeta"), DiskNames.encode(".meta"));
        assertNotEquals(DiskNames.encode("%2F"), DiskNames.encode("%252F"));
    }

    @Test
    void encodedOutputIsAsciiAndSlashFree() {
        String encoded = DiskNames.encode("ü/✓ .."); // '/' can't occur in real segments; belt & braces
        assertTrue(encoded.chars().allMatch(c -> c < 0x80), "must be pure ASCII: " + encoded);
        assertFalse(encoded.contains("/"), "must never contain a path separator: " + encoded);
    }

    @Test
    void dotsAreKeptWhenNotLeading() {
        assertEquals("note.ttl", DiskNames.encode("note.ttl"));
        assertEquals("%2Emeta", DiskNames.encode(".meta"));
        assertEquals("%2E.", DiskNames.encode(".."));
    }
}
