package com.enrichmeai.cistern.webflux;

import com.enrichmeai.cistern.core.CisternException;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Proactive negotiation (RFC 9110 §12) in isolation. The producible set for an RDF source is
 * fixed by Solid Protocol §5.5 — {@code text/turtle} and {@code application/ld+json} — and
 * never depends on how the resource is stored, which is what lets a Turtle-stored document
 * answer a JSON-LD request and a container answer at all.
 */
class ContentNegotiatorTest {

    private final ContentNegotiator negotiator = new ContentNegotiator();

    private static List<MediaType> accept(String header) {
        return MediaType.parseMediaTypes(header);
    }

    private RdfSerialization negotiate(String header) {
        return negotiator.negotiateRdf(accept(header));
    }

    // ---------------------------------------------------------------- RDF sources

    @Test
    void absentAcceptYieldsTurtle() {
        assertEquals(RdfSerialization.TURTLE, negotiator.negotiateRdf(List.of()),
                "LDP 1.0 §4.3.2.2 — Turtle when the client states no preference");
    }

    @Test
    void wildcardYieldsTurtleAsServerPreference() {
        assertEquals(RdfSerialization.TURTLE, negotiate("*/*"));
    }

    @Test
    void anExplicitRequestIsHonouredEitherWay() {
        assertAll(
                () -> assertEquals(RdfSerialization.TURTLE, negotiate("text/turtle")),
                () -> assertEquals(RdfSerialization.JSON_LD, negotiate("application/ld+json")));
    }

    @Test
    void higherQualityWins() {
        assertAll(
                () -> assertEquals(RdfSerialization.JSON_LD,
                        negotiate("text/turtle;q=0.5, application/ld+json;q=0.9")),
                () -> assertEquals(RdfSerialization.TURTLE,
                        negotiate("text/turtle;q=0.9, application/ld+json;q=0.5")));
    }

    @Test
    void equalQualityIsBrokenByServerPreference() {
        assertEquals(RdfSerialization.TURTLE, negotiate("application/ld+json;q=0.7, text/turtle;q=0.7"),
                "a tie must be deterministic, and Turtle is the pod's preferred form");
    }

    @Test
    void aMoreSpecificRangeOverridesAWildcard() {
        // RFC 9110 §12.5.1: "media ranges can be overridden by more specific media ranges".
        assertAll(
                () -> assertEquals(RdfSerialization.JSON_LD, negotiate("*/*;q=0.8, text/turtle;q=0"),
                        "the specific q=0 excludes Turtle even though */* would allow it"),
                () -> assertEquals(RdfSerialization.TURTLE, negotiate("*/*;q=0.1, text/turtle;q=0.9")));
    }

    @Test
    void typeWildcardMatchesTurtleButNotJsonLd() {
        assertEquals(RdfSerialization.TURTLE, negotiate("text/*"));
        assertEquals(RdfSerialization.JSON_LD, negotiate("application/*"));
    }

    @Test
    void charsetParametersDoNotDefeatMatching() {
        assertEquals(RdfSerialization.TURTLE, negotiate("text/turtle;charset=utf-8"));
    }

    @Test
    void nothingProducibleIsNotAcceptable() {
        assertAll(
                () -> assertThrows(CisternException.NotAcceptable.class,
                        () -> negotiate("application/xml")),
                () -> assertThrows(CisternException.NotAcceptable.class,
                        () -> negotiate("text/html, application/rdf+xml")),
                () -> assertThrows(CisternException.NotAcceptable.class,
                        () -> negotiate("*/*;q=0")),
                () -> assertThrows(CisternException.NotAcceptable.class,
                        () -> negotiate("text/turtle;q=0, application/ld+json;q=0")));
    }

    // ---------------------------------------------------------------- non-RDF sources

    @Test
    void aBinaryResourcesOwnTypeIsAcceptable() {
        assertAll(
                () -> assertDoesNotThrow(() ->
                        negotiator.requireAcceptable(List.of(), MediaType.IMAGE_PNG)),
                () -> assertDoesNotThrow(() ->
                        negotiator.requireAcceptable(accept("*/*"), MediaType.IMAGE_PNG)),
                () -> assertDoesNotThrow(() ->
                        negotiator.requireAcceptable(accept("image/*"), MediaType.IMAGE_PNG)),
                () -> assertDoesNotThrow(() ->
                        negotiator.requireAcceptable(accept("image/png"), MediaType.IMAGE_PNG)));
    }

    @Test
    void aBinaryResourceIsNeverTranscodedToSatisfyAccept() {
        assertAll(
                () -> assertThrows(CisternException.NotAcceptable.class, () ->
                        negotiator.requireAcceptable(accept("text/turtle"), MediaType.IMAGE_PNG)),
                () -> assertThrows(CisternException.NotAcceptable.class, () ->
                        negotiator.requireAcceptable(accept("*/*;q=0.5, image/png;q=0"),
                                MediaType.IMAGE_PNG)));
    }
}
