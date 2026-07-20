package com.enrichmeai.cistern.webflux;

import com.enrichmeai.cistern.core.CisternException;
import com.enrichmeai.cistern.core.Representation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The write path's media-type canonicalization, tested at its package-visible seam so the rules
 * are pinned without an HTTP stack (the arrangement {@link RequestPathsTest} uses).
 *
 * <p>What makes these worth their own class rather than only end-to-end coverage: the whole
 * point of canonicalization is that {@link Representation#isRdf()} compares media-type strings
 * with {@code equals}, so a resource stored under a type that differs by so much as a charset
 * parameter is permanently misclassified as opaque binary. That is a silent, unrecoverable
 * failure — the resource simply stops being an RDF source — so the exact stored spelling is
 * asserted here, not merely the observable behaviour.
 */
class RequestMediaTypeTest {

    private static RequestMediaType canonical(String declared) {
        return RequestMediaType.of(MediaType.parseMediaType(declared));
    }

    // ---------------------------------------------------------------- RDF types

    /**
     * The architect note on issue #13: {@code text/turtle;charset=utf-8} must be stored as
     * {@code text/turtle}, or the resource is never served as RDF again.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "text/turtle",
            "text/turtle;charset=utf-8",
            "text/turtle; charset=UTF-8",
            "TEXT/TURTLE",
            "Text/Turtle;charset=utf-8"})
    void canonicalizesEverySpellingOfTurtle(String declared) {
        RequestMediaType canonical = canonical(declared);

        assertEquals(Representation.TURTLE, canonical.contentType());
        assertTrue(canonical.representationOf(new byte[0]).isRdf(),
                declared + " must be stored as an RDF source");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "application/ld+json",
            "application/ld+json;charset=utf-8",
            "APPLICATION/LD+JSON"})
    void canonicalizesEverySpellingOfJsonLd(String declared) {
        RequestMediaType canonical = canonical(declared);

        assertEquals(Representation.JSON_LD, canonical.contentType());
        assertTrue(canonical.representationOf(new byte[0]).isRdf());
    }

    /** The RDF decision comes from {@link RdfSerialization} — one table, not two. */
    @Test
    void rdfTypesResolveThroughTheSerializationEnum() {
        assertEquals(RdfSerialization.TURTLE,
                RdfSerialization.forMediaType(MediaType.parseMediaType("text/turtle;charset=utf-8"))
                        .orElseThrow());
        assertEquals(RdfSerialization.JSON_LD,
                RdfSerialization.forMediaType(MediaType.parseMediaType("application/ld+json"))
                        .orElseThrow());
        assertTrue(RdfSerialization.forMediaType(MediaType.IMAGE_PNG).isEmpty());
    }

    // ---------------------------------------------------------------- non-RDF types

    /** A non-RDF type keeps its identity and stays non-RDF. */
    @Test
    void preservesANonRdfType() {
        RequestMediaType canonical = canonical("image/png");

        assertEquals(MediaType.IMAGE_PNG_VALUE, canonical.contentType());
        assertTrue(!canonical.representationOf(new byte[0]).isRdf());
    }

    /**
     * Parameters are dropped for non-RDF types too — a uniform rule, and a deliberate,
     * documented fidelity loss (the declared charset does not survive). See the class javadoc
     * of {@link RequestMediaType} and issue #60.
     */
    @Test
    void dropsParametersFromNonRdfTypesToo() {
        assertEquals("text/plain", canonical("text/plain;charset=iso-8859-1").contentType());
    }

    // ---------------------------------------------------------------- rejections

    /** RFC 9110 §8.3: Content-Type states what the body IS; a range states what is acceptable. */
    @ParameterizedTest
    @ValueSource(strings = {"*/*", "text/*"})
    void rejectsAMediaRange(String declared) {
        assertThrows(CisternException.BadInput.class, () -> canonical(declared));
    }

    // ---------------------------------------------------------------- invariants

    /** The record refuses to hold a non-canonical value, so no such instance can exist. */
    @Test
    void refusesToHoldAParameterizedType() {
        assertThrows(IllegalArgumentException.class,
                () -> new RequestMediaType(MediaType.parseMediaType("text/turtle;charset=utf-8")));
    }

    /** The body is handed to core untouched; only the media type is normalized. */
    @Test
    void carriesTheClientBytesThrough() {
        byte[] body = {1, 2, 3, 4};

        assertArrayEquals(body, canonical("application/octet-stream").representationOf(body).data());
    }
}
