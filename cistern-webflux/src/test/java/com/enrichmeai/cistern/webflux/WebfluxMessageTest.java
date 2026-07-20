package com.enrichmeai.cistern.webflux;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the message catalogue against the one way it can fail at runtime: a template that
 * throws when formatted. These messages describe malformed request targets, so several of
 * them quote percent-encoded text — and an unescaped {@code %2F} in a {@link String#format}
 * template is an {@code UnknownFormatConversionException}, thrown while building the message
 * for a 400 and so turning a client error into a 500. Cheap to pin, expensive to discover.
 */
class WebfluxMessageTest {

    /** More arguments than any template consumes; format ignores the surplus. */
    private static final Object[] ARGUMENTS = {"a", "b", "c", "d"};

    @ParameterizedTest
    @EnumSource(WebfluxMessage.class)
    void everyTemplateFormatsWithoutThrowing(WebfluxMessage message) {
        String formatted = assertDoesNotThrow(() -> message.format(ARGUMENTS));
        assertFalse(formatted.isBlank(), "a message with no text is not a message");
    }

    @Test
    void percentEncodedTextInATemplateIsEscaped() {
        String formatted = WebfluxMessage.TARGET_ENCODED_SLASH.format("/a%2Fb");
        assertTrue(formatted.contains("%2F"),
                "the literal %% escape must survive formatting: " + formatted);
        assertTrue(formatted.contains("/a%2Fb"), "the offending target must be quoted back");
    }

    @Test
    void argumentsAreSubstitutedInOrder() {
        assertEquals("Malformed request target /a: bad escape",
                WebfluxMessage.TARGET_MALFORMED.format("/a", "bad escape"));
    }
}
