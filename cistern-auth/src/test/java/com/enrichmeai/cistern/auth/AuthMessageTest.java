package com.enrichmeai.cistern.auth;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Every template formats, whatever it is handed — the one way a catalogue fails at runtime. */
class AuthMessageTest {

    private static final Object[] ARGUMENTS = {"a", "b", "c", "d"};

    @ParameterizedTest
    @EnumSource(AuthMessage.class)
    void everyTemplateFormatsWithoutThrowing(AuthMessage message) {
        String formatted = assertDoesNotThrow(() -> message.format(ARGUMENTS));
        assertFalse(formatted.isBlank(), "a message with no text is not a message");
    }

    @ParameterizedTest
    @EnumSource(JwtRejectionReason.class)
    void everyReasonDescribes(JwtRejectionReason reason) {
        assertFalse(assertDoesNotThrow(() -> reason.describe(ARGUMENTS)).isBlank());
    }
}
