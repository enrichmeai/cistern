package com.enrichmeai.cistern.wac;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** The correlation identifier's rules (T5.9). */
class RequestIdTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "5c1f4e2a-9b1d-4c1e-8f7a-2b3c4d5e6f70",   // UUID
        "01J5Y0M3K9Q8R7S6T5V4W3X2Y1",             // ULID
        "4bf92f3577b34da6a3ce929d0e0e4736",       // W3C trace-id
        "dGhpcyBpcyBhIHRlc3Q=",                   // base64
        "req/2026-08-19/00042",                   // path-shaped
        "a",
    })
    @DisplayName("well-formed identifiers are honoured verbatim")
    void wellFormedIsHonoured(String candidate) {
        assertEquals(candidate, RequestId.parse(candidate).orElseThrow().value());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
        "has space",
        "line\nbreak",
        "carriage\rreturn",
        "tab\tchar",
        "quote\"json",
        "brace{json}",
        "über",
    })
    @DisplayName("anything else is not an identifier — parse yields empty, never throws")
    void malformedIsRejected(String candidate) {
        assertTrue(RequestId.parse(candidate).isEmpty());
    }

    @Test
    @DisplayName("an identifier longer than the cap is refused")
    void tooLongIsRejected() {
        String tooLong = "x".repeat(RequestId.MAX_LENGTH + 1);
        assertTrue(RequestId.parse(tooLong).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new RequestId(tooLong));
        assertEquals(RequestId.MAX_LENGTH, RequestId.parse("x".repeat(RequestId.MAX_LENGTH)).orElseThrow().value().length());
    }

    @Test
    @DisplayName("a generated identifier is well-formed by construction, and fresh each time")
    void generatedIsWellFormed() {
        RequestId first = RequestId.generate();
        RequestId second = RequestId.generate();

        assertTrue(RequestId.parse(first.value()).isPresent());
        assertTrue(!first.equals(second));
    }
}
