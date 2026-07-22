package com.enrichmeai.cistern.webflux;

import com.enrichmeai.cistern.core.CisternException;
import com.enrichmeai.cistern.core.ResourceIdentifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The request-target rules, without an HTTP stack: which paths name a resource and which are
 * refused at the edge. Refusals are {@link CisternException.BadInput} (→ 400) rather than
 * anything the storage backend would signal (→ 500) — see {@link RequestPaths} for why each
 * shape is unrepresentable.
 */
class RequestPathsTest {

    private static final String BASE = "https://pod.example";

    /** CORS settings are irrelevant to path resolution, so the record's defaults stand in. */
    private final RequestPaths paths = new RequestPaths(new CisternProperties(
            BASE, new CisternProperties.Storage(Path.of("./data")), null, null));

    private ResourceIdentifier resolve(String rawPath) {
        return paths.identifierFor(rawPath);
    }

    // ---------------------------------------------------------------- resolution

    @Test
    void resolvesAgainstTheConfiguredBaseNotTheRequestHost() {
        assertEquals(BASE + "/notes/a.ttl", resolve("/notes/a.ttl").uri().toString());
    }

    @Test
    void rootPathIsTheStorageRootContainer() {
        ResourceIdentifier root = resolve("/");
        assertEquals(BASE + "/", root.uri().toString());
        assertTrue(root.isContainer());
    }

    @Test
    void trailingSlashDecidesContainerVersusDocument() {
        assertTrue(resolve("/notes/").isContainer(), "Solid Protocol §3.1");
        assertFalse(resolve("/notes").isContainer());
        assertFalse(resolve("/notes").equals(resolve("/notes/")),
                "the two are distinct resources, never aliases");
    }

    @Test
    void percentEncodingIsPreservedRatherThanDecoded() {
        // RFC 3986 §2.2 — %20 and a literal space are not interchangeable, and the storage
        // backend keys resources by raw segment, so decoding here would merge distinct URIs.
        assertEquals(BASE + "/notes/a%20b.ttl", resolve("/notes/a%20b.ttl").uri().toString());
    }

    @Test
    void queryStringIsNotPartOfResourceIdentity() {
        // identifierFor(String) receives the raw path only; this pins that a query cannot
        // sneak into the identifier and fork storage into /a.ttl and /a.ttl?v=1.
        assertEquals(BASE + "/a.ttl", resolve("/a.ttl").uri().toString());
    }

    // ---------------------------------------------------------------- refusals

    @ParameterizedTest
    @ValueSource(strings = {"/a%2Fb", "/a%2fb", "/x/a%2Fb/y", "/a%2Fb/"})
    void encodedSlashInASegmentIsRefused(String rawPath) {
        CisternException.BadInput error =
                assertThrows(CisternException.BadInput.class, () -> resolve(rawPath));
        assertTrue(error.getMessage().contains("%2F"), error.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"//", "/a//b", "/a//", "//a"})
    void emptySegmentsAreRefused(String rawPath) {
        assertThrows(CisternException.BadInput.class, () -> resolve(rawPath));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/.", "/..", "/a/../b", "/a/./b", "/a/.."})
    void dotSegmentsAreRefusedRatherThanSilentlyCollapsed(String rawPath) {
        assertThrows(CisternException.BadInput.class, () -> resolve(rawPath),
                "collapsing them would make two spellings of one resource");
    }

    @Test
    void relativeTargetIsRefused() {
        assertThrows(CisternException.BadInput.class, () -> resolve("notes/a.ttl"));
    }

    @Test
    void malformedPercentEscapeIsTheClientsErrorNotTheServers() {
        assertThrows(CisternException.BadInput.class, () -> resolve("/a%ZZb"));
    }

    @Test
    void aSegmentNamedLikeAnEncodedSlashButNotOneIsFine() {
        // %252F is an encoded '%2F' — one literal segment, no slash ambiguity.
        assertEquals(BASE + "/a%252Fb", resolve("/a%252Fb").uri().toString());
    }
}
