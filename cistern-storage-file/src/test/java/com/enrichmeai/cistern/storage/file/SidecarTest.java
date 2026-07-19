package com.enrichmeai.cistern.storage.file;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SidecarTest {

    @Test
    void roundTripsPlainFields() {
        Sidecar sidecar = new Sidecar("text/turtle", "ab12cd", Instant.parse("2026-07-19T10:00:00Z"));
        assertEquals(sidecar, Sidecar.fromJson(sidecar.toJson()));
    }

    @Test
    void roundTripsHostileContentType() {
        // Content types are client-supplied: quotes, backslashes and control chars must survive.
        Sidecar sidecar = new Sidecar("text/plain; charset=\"utf-8\"; note=\\weird\n",
                "ff00", Instant.parse("2026-01-02T03:04:05Z"));
        assertEquals(sidecar, Sidecar.fromJson(sidecar.toJson()));
    }

    @Test
    void parsesWithWhitespaceAndUnknownKeys() {
        String json = """
                {
                  "contentType" : "text/turtle",
                  "somethingNew" : "ignored",
                  "etag" : "abc",
                  "lastModified" : "2026-07-19T10:00:00Z"
                }
                """;
        assertEquals(new Sidecar("text/turtle", "abc", Instant.parse("2026-07-19T10:00:00Z")),
                Sidecar.fromJson(json));
    }

    @Test
    void parsesStandardStringEscapes() {
        String json = "{\"contentType\":\"a\\/b\\u0041\\t\",\"etag\":\"e\",\"lastModified\":\"2026-07-19T10:00:00Z\"}";
        assertEquals("a/bA\t", Sidecar.fromJson(json).contentType());
    }

    @Test
    void rejectsMissingFieldsAndMalformedJson() {
        assertThrows(IllegalArgumentException.class,
                () -> Sidecar.fromJson("{\"contentType\":\"text/turtle\"}"));
        assertThrows(IllegalArgumentException.class, () -> Sidecar.fromJson("{\"a\":\"b\""));
        assertThrows(IllegalArgumentException.class, () -> Sidecar.fromJson("not json at all"));
    }
}
