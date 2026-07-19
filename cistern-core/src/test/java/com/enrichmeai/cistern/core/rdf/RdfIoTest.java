package com.enrichmeai.cistern.core.rdf;

import com.enrichmeai.cistern.core.CisternException;
import com.enrichmeai.cistern.core.Representation;
import com.enrichmeai.cistern.core.ResourceIdentifier;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.shared.JenaException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** Fixed-corpus tests for {@link RdfIo}: base resolution, failure modes, serialization. */
class RdfIoTest {

    private static final ResourceIdentifier RESOURCE =
            new ResourceIdentifier(URI.create("https://pod.example/alice/notes/note1"));
    private static final String VOCAB = "https://vocab.example/terms/";

    private static Representation turtle(String text) {
        return new Representation(Representation.TURTLE, text.getBytes(StandardCharsets.UTF_8));
    }

    private static Representation jsonLd(String text) {
        return new Representation(Representation.JSON_LD, text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Runs the action and asserts the ONLY thing that escapes is BadInput — in particular
     * no Jena exception type (RiotException extends JenaException) leaks through (T1.1 DoD).
     * Returns the exception so callers can also assert on the message.
     */
    private static CisternException.BadInput assertBadInputOnly(Runnable action) {
        try {
            action.run();
        } catch (Throwable t) {
            assertFalse(t instanceof JenaException,
                    "Jena exception leaked through RdfIo: " + t.getClass().getName());
            assertTrue(t instanceof CisternException.BadInput,
                    "Expected CisternException.BadInput but got " + t.getClass().getName());
            CisternException.BadInput badInput = (CisternException.BadInput) t;
            assertNotNull(badInput.getMessage(), "BadInput must carry a useful message");
            assertFalse(badInput.getMessage().isBlank(), "BadInput must carry a useful message");
            return badInput;
        }
        return fail("Expected CisternException.BadInput but nothing was thrown");
    }

    @Nested
    class RelativeUriResolution {

        @Test
        void turtleRelativeUrisResolveAgainstTheResourceUri() {
            Model model = RdfIo.parse(turtle("""
                    @prefix ex: <%s> .
                    <> ex:title "Note 1" .
                    <attachment.png> ex:attachedTo <> .
                    <#section1> ex:title "Intro" .
                    </shared/thing> ex:attachedTo <../other/note2> .
                    """.formatted(VOCAB)), RESOURCE);

            assertTrue(model.containsResource(model.createResource("https://pod.example/alice/notes/note1")),
                    "<> must resolve to the resource URI itself");
            assertTrue(model.containsResource(model.createResource("https://pod.example/alice/notes/attachment.png")));
            assertTrue(model.containsResource(model.createResource("https://pod.example/alice/notes/note1#section1")));
            assertTrue(model.containsResource(model.createResource("https://pod.example/shared/thing")));
            assertTrue(model.containsResource(model.createResource("https://pod.example/alice/other/note2")));
        }

        @Test
        void jsonLdRelativeIdsResolveAgainstTheResourceUri() {
            Model model = RdfIo.parse(jsonLd("""
                    {
                      "@id": "",
                      "%sattachedTo": { "@id": "attachment.png" },
                      "%sknows": { "@id": "../" }
                    }
                    """.formatted(VOCAB, VOCAB)), RESOURCE);

            assertTrue(model.containsResource(model.createResource("https://pod.example/alice/notes/note1")),
                    "empty @id must resolve to the resource URI itself");
            assertTrue(model.containsResource(model.createResource("https://pod.example/alice/notes/attachment.png")));
            assertTrue(model.containsResource(model.createResource("https://pod.example/alice/")));
        }
    }

    @Nested
    class MalformedInput {

        @Test
        void malformedTurtleMissingObjectIsBadInput() {
            assertBadInputOnly(() -> RdfIo.parse(
                    turtle("<https://a.example/s> <https://a.example/p> "), RESOURCE));
        }

        @Test
        void malformedTurtlePlainProseIsBadInput() {
            assertBadInputOnly(() -> RdfIo.parse(
                    turtle("this is not turtle at all."), RESOURCE));
        }

        @Test
        void turtleWithSpaceInIriIsBadInput() {
            assertBadInputOnly(() -> RdfIo.parse(
                    turtle("<https://a.example/a b> <https://a.example/p> \"x\" ."), RESOURCE));
        }

        @Test
        void malformedJsonIsBadInput() {
            assertBadInputOnly(() -> RdfIo.parse(jsonLd("{ this is not json"), RESOURCE));
        }

        @Test
        void validJsonButInvalidJsonLdIsBadInput() {
            // @id must be a string — valid JSON, invalid JSON-LD
            assertBadInputOnly(() -> RdfIo.parse(
                    jsonLd("{\"@id\": 42, \"" + VOCAB + "name\": \"x\"}"), RESOURCE));
        }

        @Test
        void truncatedTurtleBytesAreBadInput() {
            byte[] whole = ("<https://pod.example/alice/notes/note1> <" + VOCAB + "name> "
                    + "\"a rather long literal value that guarantees the cut lands inside it\" .")
                    .getBytes(StandardCharsets.UTF_8);
            byte[] truncated = Arrays.copyOf(whole, whole.length / 2);
            assertBadInputOnly(() -> RdfIo.parse(
                    new Representation(Representation.TURTLE, truncated), RESOURCE));
        }

        @Test
        void truncatedJsonLdBytesAreBadInput() {
            byte[] whole = ("{\"@id\": \"https://pod.example/alice/notes/note1\", \""
                    + VOCAB + "name\": \"a rather long literal value for the truncation cut\"}")
                    .getBytes(StandardCharsets.UTF_8);
            byte[] truncated = Arrays.copyOf(whole, whole.length / 2);
            assertBadInputOnly(() -> RdfIo.parse(
                    new Representation(Representation.JSON_LD, truncated), RESOURCE));
        }

        @Test
        void invalidUtf8BytesAreBadInput() {
            assertBadInputOnly(() -> RdfIo.parse(
                    new Representation(Representation.TURTLE,
                            new byte[]{(byte) 0xC3, (byte) 0x28, (byte) 0xFF, (byte) 0xFE}),
                    RESOURCE));
        }

        @Test
        void emptyBytesAreAValidEmptyTurtleDocumentButInvalidJsonLd() {
            Model empty = RdfIo.parse(turtle(""), RESOURCE);
            assertTrue(empty.isEmpty(), "empty Turtle document is a valid empty graph");
            assertBadInputOnly(() -> RdfIo.parse(jsonLd(""), RESOURCE));
        }
    }

    @Nested
    class ContentTypeHandling {

        @Test
        void unsupportedContentTypeIsBadInputAndNamesTheOffender() {
            CisternException.BadInput e = assertBadInputOnly(() -> RdfIo.parse(
                    new Representation("application/rdf+xml", "<rdf/>".getBytes(StandardCharsets.UTF_8)),
                    RESOURCE));
            assertTrue(e.getMessage().contains("application/rdf+xml"),
                    "message should name the unsupported type, was: " + e.getMessage());
            assertTrue(e.getMessage().contains(Representation.TURTLE)
                            && e.getMessage().contains(Representation.JSON_LD),
                    "message should name the supported types, was: " + e.getMessage());
        }

        @Test
        void nonRdfContentTypeIsBadInput() {
            assertBadInputOnly(() -> RdfIo.parse(
                    new Representation("text/plain", "hello".getBytes(StandardCharsets.UTF_8)), RESOURCE));
        }

        @Test
        void nullContentTypeIsBadInput() {
            assertBadInputOnly(() -> RdfIo.parse(
                    new Representation(null, "<a> <b> <c> .".getBytes(StandardCharsets.UTF_8)), RESOURCE));
        }

        @Test
        void contentTypeParametersAndCaseAreTolerated() {
            Model model = RdfIo.parse(new Representation("Text/Turtle; charset=UTF-8",
                    "<> <https://vocab.example/terms/title> \"x\" .".getBytes(StandardCharsets.UTF_8)),
                    RESOURCE);
            assertEquals(1, model.size());
        }
    }

    @Nested
    class NullSafety {

        @Test
        void nullRepresentationIsBadInput() {
            assertBadInputOnly(() -> RdfIo.parse(null, RESOURCE));
        }

        @Test
        void nullDataIsBadInput() {
            assertBadInputOnly(() -> RdfIo.parse(
                    new Representation(Representation.TURTLE, null), RESOURCE));
        }

        @Test
        void nullBaseResourceIsBadInput() {
            assertBadInputOnly(() -> RdfIo.parse(turtle("<a> <b> <c> ."), null));
        }

        @Test
        void serializeNullModelIsBadInput() {
            assertBadInputOnly(() -> RdfIo.serialize(null, Representation.TURTLE));
        }

        @Test
        void serializeNullContentTypeIsBadInput() {
            Model model = RdfIo.parse(turtle(""), RESOURCE);
            assertBadInputOnly(() -> RdfIo.serialize(model, null));
        }
    }

    @Nested
    class Serialization {

        @Test
        void serializesTurtleWithCanonicalContentTypeAndAbsoluteUris() {
            Model model = RdfIo.parse(turtle("<> <" + VOCAB + "title> \"Note 1\" ."), RESOURCE);
            Representation out = RdfIo.serialize(model, "text/turtle; charset=utf-8");
            assertEquals(Representation.TURTLE, out.contentType(),
                    "serialized content type must be the canonical bare media type");
            String text = new String(out.data(), StandardCharsets.UTF_8);
            assertTrue(text.contains("https://pod.example/alice/notes/note1"),
                    "output must carry absolute URIs, was:\n" + text);
        }

        @Test
        void serializesJsonLdThatParsesBackIsomorphically() {
            Model model = RdfIo.parse(turtle("""
                    @prefix ex: <%s> .
                    <> ex:title "Note 1" ; ex:count 3 .
                    """.formatted(VOCAB)), RESOURCE);
            Representation out = RdfIo.serialize(model, Representation.JSON_LD);
            assertEquals(Representation.JSON_LD, out.contentType());
            assertTrue(RdfIo.parse(out, RESOURCE).isIsomorphicWith(model));
        }

        @Test
        void serializeToUnsupportedContentTypeIsBadInput() {
            Model model = RdfIo.parse(turtle(""), RESOURCE);
            CisternException.BadInput e =
                    assertBadInputOnly(() -> RdfIo.serialize(model, "application/n-triples"));
            assertTrue(e.getMessage().contains("application/n-triples"),
                    "message should name the unsupported type, was: " + e.getMessage());
        }
    }
}
