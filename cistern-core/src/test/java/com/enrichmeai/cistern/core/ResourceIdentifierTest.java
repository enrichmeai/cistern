package com.enrichmeai.cistern.core;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit coverage for {@link ResourceIdentifier}, focused on {@link ResourceIdentifier#parent()}
 * ancestor walking. Plain-ASCII behaviour must be unchanged; percent-encoded segments must be
 * carried through verbatim rather than decoded and re-parsed (issue #54).
 */
class ResourceIdentifierTest {

    private static ResourceIdentifier id(String uri) {
        return new ResourceIdentifier(URI.create(uri));
    }

    /** Asserts {@code child.parent()} is present and its raw form is exactly {@code expectedUri}. */
    private static void assertParentUri(String child, String expectedUri) {
        Optional<ResourceIdentifier> parent = id(child).parent();
        assertTrue(parent.isPresent(), "parent() must be present for " + child);
        assertEquals(URI.create(expectedUri), parent.get().uri(),
                "parent(" + child + ") URI");
        assertEquals(expectedUri, parent.get().uri().toString(),
                "parent(" + child + ") raw string form");
    }

    // ---------------------------------------------------------------- plain ASCII (unchanged)

    @Test
    void documentParentIsItsContainer() {
        assertParentUri("https://pod.example/a/b/c.ttl", "https://pod.example/a/b/");
    }

    @Test
    void containerParentIsItsEnclosingContainer() {
        assertParentUri("https://pod.example/a/b/", "https://pod.example/a/");
    }

    @Test
    void nestedDocumentWalksOneLevel() {
        assertParentUri("https://pod.example/a/b/c/d.ttl", "https://pod.example/a/b/c/");
    }

    @Test
    void topLevelDocumentParentIsRoot() {
        assertParentUri("https://pod.example/note.ttl", "https://pod.example/");
    }

    @Test
    void topLevelContainerParentIsRoot() {
        assertParentUri("https://pod.example/a/", "https://pod.example/");
    }

    @Test
    void rootHasNoParent() {
        assertTrue(id("https://pod.example/").parent().isEmpty(), "root '/' has no parent");
    }

    @Test
    void emptyPathHasNoParent() {
        assertTrue(id("https://pod.example").parent().isEmpty(), "authority-only URI has no parent");
    }

    @Test
    void parentWalkTerminatesAtRoot() {
        Optional<ResourceIdentifier> p = id("https://pod.example/a/b/c.ttl").parent();
        assertEquals(URI.create("https://pod.example/a/b/"), p.orElseThrow().uri());
        p = p.orElseThrow().parent();
        assertEquals(URI.create("https://pod.example/a/"), p.orElseThrow().uri());
        p = p.orElseThrow().parent();
        assertEquals(URI.create("https://pod.example/"), p.orElseThrow().uri());
        assertTrue(p.orElseThrow().parent().isEmpty(), "walk must terminate at root");
    }

    // ---------------------------------------------------------------- issue #54 repro

    @Test
    void issue54Repro_percentEncodedSpaceDoesNotThrow() {
        // The exact repro from issue #54: decoded path "/my dir/" is an illegal URI
        // reference, so the old resolve(String) threw. parent() must not throw and must
        // keep the %20 spelling.
        ResourceIdentifier doc = id("https://pod.example/my%20dir/doc.ttl");
        Optional<ResourceIdentifier> parent = assertDoesNotThrow(doc::parent);
        assertEquals(URI.create("https://pod.example/my%20dir/"), parent.orElseThrow().uri());
        assertEquals("/my%20dir/", parent.orElseThrow().uri().getRawPath(),
                "%20 must survive verbatim, not be decoded to a space");
        assertTrue(parent.orElseThrow().isContainer());
    }

    // ---------------------------------------------------------------- encoded octets at the leaf

    @Test
    void percentEncodedSpaceAtLeafPreservesEncoding() {
        assertParentUri("https://pod.example/dir/my%20file.ttl", "https://pod.example/dir/");
    }

    @Test
    void percentEncodedDotAtLeafIsNotADotSegment() {
        // %2E must NOT be treated as a "." dot-segment; the leaf is a normal document.
        assertParentUri("https://pod.example/dir/fi%2Ele.ttl", "https://pod.example/dir/");
    }

    @Test
    void percentEncodedUnicodeAtLeafPreservesEncoding() {
        assertParentUri("https://pod.example/dir/fi%C3%A9le.ttl", "https://pod.example/dir/");
    }

    @Test
    void literalPercentAtLeafPreservesEncoding() {
        // %25 is a literal '%' in the name; the raw spelling must survive.
        assertParentUri("https://pod.example/dir/50%25off.ttl", "https://pod.example/dir/");
    }

    // ---------------------------------------------------------------- encoded octets intermediate

    @Test
    void percentEncodedSpaceAtIntermediatePreservesEncoding() {
        assertParentUri("https://pod.example/my%20dir/sub/c.ttl", "https://pod.example/my%20dir/sub/");
    }

    @Test
    void encodedIntermediateWalkKeepsEveryRawSpelling() {
        // Unicode (%C3%A9) at the TOP intermediate, encoded dot (%2E) at the mid
        // intermediate, encoded space (%20) at the leaf — the walk must keep every raw
        // spelling and never decode-then-reparse at any level.
        ResourceIdentifier leaf = id("https://pod.example/caf%C3%A9/sub%2Edir/fi%20le.ttl");

        ResourceIdentifier mid = leaf.parent().orElseThrow();
        assertEquals(URI.create("https://pod.example/caf%C3%A9/sub%2Edir/"), mid.uri());
        assertEquals("/caf%C3%A9/sub%2Edir/", mid.uri().getRawPath());

        ResourceIdentifier top = mid.parent().orElseThrow();
        assertEquals(URI.create("https://pod.example/caf%C3%A9/"), top.uri());
        assertEquals("/caf%C3%A9/", top.uri().getRawPath());

        ResourceIdentifier root = top.parent().orElseThrow();
        assertEquals(URI.create("https://pod.example/"), root.uri());
        assertTrue(root.parent().isEmpty(), "encoded walk must still terminate at root");
    }

    @Test
    void literalPercentAtIntermediatePreservesEncoding() {
        assertParentUri("https://pod.example/50%25off/item.ttl", "https://pod.example/50%25off/");
    }

    // ---------------------------------------------------------------- query string (preserved)

    @Test
    void queryStringIsPreservedOnParent() {
        // A query-bearing identifier is legal today (constructor only forbids fragments).
        // The reconstruction preserves scheme/authority/query exactly, unlike the old
        // resolve(String) which dropped the query.
        ResourceIdentifier doc = id("https://pod.example/a/b/c.ttl?v=2");
        ResourceIdentifier parent = doc.parent().orElseThrow();
        assertEquals(URI.create("https://pod.example/a/b/?v=2"), parent.uri());
        assertEquals("v=2", parent.uri().getRawQuery(), "query must be carried across verbatim");
    }

    @Test
    void queryStringWithEncodedPathIsPreserved() {
        ResourceIdentifier doc = id("https://pod.example/my%20dir/doc.ttl?a=b%20c");
        ResourceIdentifier parent = doc.parent().orElseThrow();
        assertEquals(URI.create("https://pod.example/my%20dir/?a=b%20c"), parent.uri());
        assertEquals("/my%20dir/", parent.uri().getRawPath());
        assertEquals("a=b%20c", parent.uri().getRawQuery());
    }
}
