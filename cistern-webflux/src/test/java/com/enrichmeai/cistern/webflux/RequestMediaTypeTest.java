package com.enrichmeai.cistern.webflux;

import com.enrichmeai.cistern.core.CisternException;
import com.enrichmeai.cistern.core.Representation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The write path's media-type handling, tested at its package-visible seam so the rules are
 * pinned without an HTTP stack (the arrangement {@link RequestPathsTest} uses).
 *
 * <p>What makes these worth their own class rather than only end-to-end coverage is that both
 * halves of the rule fail silently and unrecoverably if they regress:
 * <ul>
 *   <li><b>RDF types are canonicalized.</b> {@link Representation#isRdf()} compares media-type
 *       strings with {@code equals}, so a Turtle body stored under a type carrying a charset
 *       parameter is permanently misclassified as opaque binary — the resource simply stops
 *       being an RDF source.</li>
 *   <li><b>Non-RDF types are not touched.</b> Stripping a parameter loses the charset the bytes
 *       were declared with, so they are later served labelled without it and decoded wrongly.</li>
 * </ul>
 * Both are asserted on the exact stored spelling, not merely on observable behaviour.
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
        assertFalse(canonical.representationOf(new byte[0]).isRdf());
    }

    /**
     * Non-RDF types keep their parameters (architect ruling, PR #66). Canonicalization exists
     * only to decide RDF-ness and to normalize the RDF types; dropping a charset here would be
     * data loss, because the bytes would later be served labelled without it and a client would
     * decode them wrongly.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "text/plain;charset=iso-8859-1",
            "text/plain;charset=utf-16",
            "application/octet-stream",
            "image/svg+xml;charset=utf-8"})
    void preservesNonRdfTypesExactly(String declared) {
        RequestMediaType stored = canonical(declared);

        assertEquals(MediaType.parseMediaType(declared), stored.mediaType(),
                declared + " must be stored as declared, parameters intact");
        assertFalse(stored.representationOf(new byte[0]).isRdf(),
                declared + " is not an RDF type, parameters or not");
    }

    /**
     * The property that makes preserving parameters safe: {@code isRdf()} still answers
     * correctly on the stored value, because a parameterized non-RDF type is genuinely not RDF.
     */
    @Test
    void parametersDoNotConfuseRdfClassification() {
        assertTrue(canonical("text/turtle;charset=utf-8").representationOf(new byte[0]).isRdf(),
                "an RDF type is canonicalized, so it stays recognisable");
        assertFalse(canonical("text/plain;charset=utf-8").representationOf(new byte[0]).isRdf(),
                "a non-RDF type keeps its parameter and is still classified as non-RDF");
    }

    // ---------------------------------------------------------------- rejections

    /** RFC 9110 §8.3: Content-Type states what the body IS; a range states what is acceptable. */
    @ParameterizedTest
    @ValueSource(strings = {"*/*", "text/*"})
    void rejectsAMediaRange(String declared) {
        assertThrows(CisternException.BadInput.class, () -> canonical(declared));
    }

    // ---------------------------------------------------------------- invariants

    /** The record refuses to hold a media range, so no such instance can reach storage. */
    @Test
    void refusesToHoldAMediaRange() {
        assertThrows(IllegalArgumentException.class,
                () -> new RequestMediaType(MediaType.ALL));
        assertThrows(IllegalArgumentException.class,
                () -> new RequestMediaType(MediaType.parseMediaType("text/*")));
    }

    /** The body is handed to core untouched; only the media type is normalized. */
    @Test
    void carriesTheClientBytesThrough() {
        byte[] body = {1, 2, 3, 4};

        assertArrayEquals(body, canonical("application/octet-stream").representationOf(body).data());
    }
}
