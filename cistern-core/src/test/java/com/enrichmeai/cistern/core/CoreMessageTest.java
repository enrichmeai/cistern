package com.enrichmeai.cistern.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the core message catalogue against a template that throws when formatted — these
 * messages quote URIs, which may carry percent-encoding, and an unescaped {@code %} in a
 * {@link String#format} template throws while the message for a 4xx is being built, turning
 * a client error into a 500.
 */
class CoreMessageTest {

    /** More arguments than any template consumes; format ignores the surplus. */
    private static final Object[] ARGUMENTS = {"a", "b", "c", "d"};

    @ParameterizedTest
    @EnumSource(CoreMessage.class)
    void everyTemplateFormatsWithoutThrowing(CoreMessage message) {
        String formatted = assertDoesNotThrow(() -> message.format(ARGUMENTS));
        assertFalse(formatted.isBlank(), "a message with no text is not a message");
    }

    @Test
    void percentEncodedUriTextSurvivesFormatting() {
        String formatted = CoreMessage.RESOURCE_NOT_FOUND.format("https://pod.example/a%20b.ttl");
        assertTrue(formatted.contains("https://pod.example/a%20b.ttl"), formatted);
    }
}
