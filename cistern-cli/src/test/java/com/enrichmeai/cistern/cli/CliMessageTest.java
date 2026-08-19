package com.enrichmeai.cistern.cli;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Guards the CLI catalogue the way {@code CoreMessageTest} guards core's: every template must
 * format, because most of them quote URIs, which may carry percent-encoding — and a template
 * that throws while a failure is being reported hides the failure.
 */
class CliMessageTest {

    /** More arguments than any template consumes; format ignores the surplus. {@code %d} slots get ints. */
    private static final Object[] ARGUMENTS = {"a", "b", 3, "d"};

    @ParameterizedTest
    @EnumSource(CliMessage.class)
    void everyTemplateFormatsWithoutThrowing(CliMessage message) {
        String formatted = assertDoesNotThrow(() -> message.format(ARGUMENTS));
        assertFalse(formatted.isBlank(), "a message with no text is not a message");
    }

    @Test
    void percentEncodedUriTextSurvivesFormatting() {
        String formatted = CliMessage.NO_ACL_TO_THE_ROOT.format("https://pod.example/a%20b/");
        assertTrue(formatted.contains("https://pod.example/a%20b/"), formatted);
    }
}
