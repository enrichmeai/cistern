package com.enrichmeai.cistern.storage.file;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the storage-file message catalogue against a template that throws when formatted —
 * the counterpart of {@code CoreMessageTest}, and needed here for a sharper reason: these
 * messages quote <em>disk names</em>, which are percent-escaped by construction
 * ({@link DiskNames}), so a stray {@code %} in a template would throw while the message for
 * a conflict was being built and turn a 409 into a 500.
 */
class StorageFileMessageTest {

    /** More arguments than any template consumes; format ignores the surplus. */
    private static final Object[] ARGUMENTS = {"a", "b", "c", "d"};

    @ParameterizedTest
    @EnumSource(StorageFileMessage.class)
    void everyTemplateFormatsWithoutThrowing(StorageFileMessage message) {
        String formatted = assertDoesNotThrow(() -> message.format(ARGUMENTS));
        assertFalse(formatted.isBlank(), "a message with no text is not a message");
    }

    /**
     * A percent-escaped disk name is an argument, not part of the template, so it must reach
     * the message intact rather than being read as a format specifier.
     */
    @Test
    void percentEscapedDiskNameSurvivesFormatting() {
        String formatted = StorageFileMessage.DISK_NAME_ESCAPE_TRUNCATED.format("%2Emeta%2");
        assertTrue(formatted.contains("%2Emeta%2"), formatted);
    }
}
