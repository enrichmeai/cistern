package com.enrichmeai.cistern.core.ldp;

import com.enrichmeai.cistern.core.CisternException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Slug}'s sanitization rules — the security-relevant half of T2.3.
 *
 * <p>No specification defines this sanitization (RFC 5023 §9.7 says only that a server "MAY
 * alter the header value before using it"; LDP §5.2.3.10 "adds no new requirements"), so these
 * tests are the specification: they pin every rule the class javadoc states, and in particular
 * every way a hostile hint might try to escape its single path segment.
 */
class SlugTest {

    /** The name a slug produces is the whole of what the caller may use. */
    private static String sanitized(String fieldValue) {
        return Slug.from(fieldValue).map(Slug::value).orElse("");
    }

    // ---------------------------------------------------------------- the happy path

    @Test
    @DisplayName("an already-clean hint is used verbatim")
    void keepsACleanHint() {
        assertEquals("notes.ttl", sanitized("notes.ttl"));
        assertEquals("My-Note_2~final", sanitized("My-Note_2~final"));
    }

    @Test
    @DisplayName("case is preserved: RFC 5023 permits changing it, and nothing requires it")
    void preservesCase() {
        assertEquals("ReadMe", sanitized("ReadMe"));
    }

    // ---------------------------------------------------------------- escaping the segment

    /**
     * The rule the whole type exists for: whatever arrives, the result is one path segment that
     * cannot climb out of the container it will be appended to.
     */
    @ParameterizedTest(name = "[{index}] {0} is not a way out")
    @ValueSource(strings = {
            "../etc/passwd",
            "..%2F..%2Fetc%2Fpasswd",
            "..%252F..%252Fpasswd",
            "/absolute",
            "a/b/c",
            "a\\b",
            ".",
            "..",
            "....",
            "./.././",
    })
    void neverProducesASeparatorOrATraversal(String hostile) {
        String name = sanitized(hostile);

        assertFalse(name.contains("/"), "no path separator may survive: " + name);
        assertFalse(name.contains("\\"), "no backslash may survive: " + name);
        assertFalse(name.contains("%"), "no percent may survive a single decode: " + name);
        assertFalse(name.equals(".") || name.equals(".."), "dot segments must not survive");
        assertFalse(name.startsWith("."), "a name may not start with a dot: " + name);
    }

    /**
     * Percent-encoding is reversed exactly once. {@code %252F} is the encoding of the literal
     * text {@code %2F}, so one decode yields text and a second would yield a separator — this
     * pins that the second never happens.
     */
    @Test
    @DisplayName("percent-decoding happens once, so nesting cannot smuggle a separator back in")
    void decodesExactlyOnce() {
        assertEquals("a-2Fb", sanitized("a%252Fb"));
        assertEquals("a-b", sanitized("a%2Fb"));
    }

    @Test
    @DisplayName("a percent-encoded UTF-8 hint decodes, then loses what cannot be a name")
    void decodesUtf8() {
        // RFC 5023 §9.7.2's own example: "The Beach at S%C3%A8te".
        assertEquals("The-Beach-at-S-te", sanitized("The Beach at S%C3%A8te"));
    }

    // ---------------------------------------------------------------- shaping

    @ParameterizedTest(name = "[{index}] \"{0}\" → \"{1}\"")
    @CsvSource({
            "'my note.ttl',      my-note.ttl",
            "'a   b',            a-b",
            "'a---b',            a-b",
            "'-leading',         leading",
            "'trailing-',        trailing",
            "'.hidden',          hidden",
            "'trailing.',        trailing",
            "'a!@#$^&*()b',      a-b",
            "'héllo',            h-llo",
            "'~tilde_ok-1.2',    ~tilde_ok-1.2",
    })
    void shapesTheName(String fieldValue, String expected) {
        assertEquals(expected, sanitized(fieldValue));
    }

    @Test
    @DisplayName("a name is bounded, and truncation does not leave a ragged edge")
    void boundsTheLength() {
        String name = sanitized("x".repeat(Slug.MAX_LENGTH * 3));
        assertEquals(Slug.MAX_LENGTH, name.length());

        // Truncating mid-run must not end the name on the replacement character.
        String truncatedOnAReplacement = sanitized("y".repeat(Slug.MAX_LENGTH - 1) + " tail");
        assertFalse(truncatedOnAReplacement.endsWith("-"),
                "trimming runs after truncation: " + truncatedOnAReplacement);
    }

    // ---------------------------------------------------------------- nothing usable

    /**
     * Cistern's stated rule for a hint that sanitizes away: it is ignored, not refused. RFC 5023
     * §9.7 — "Servers MAY choose to ignore the Slug entity-header" — and LDP §5.2.3.8 then
     * covers the outcome as creation "in the absence of a client hint".
     */
    @ParameterizedTest(name = "[{index}] \"{0}\" leaves no usable name")
    @ValueSource(strings = {"", "   ", "\t", "/", "///", "..", ".", "...", "%2F", "-", "---", "!!!"})
    void yieldsNoHintWhenNothingSurvives(String unusable) {
        assertTrue(Slug.from(unusable).isEmpty(),
                "a slug that sanitizes to nothing must be ignored, not refused");
    }

    @Test
    @DisplayName("no Slug header at all is simply no hint")
    void absentHeaderIsNoHint() {
        assertEquals(Optional.empty(), Slug.from(null));
    }

    // ---------------------------------------------------------------- malformed headers

    /**
     * The one case that is a 400 rather than an ignored hint: RFC 5023 §9.7.1's grammar is
     * {@code slugtext = %x20-7E | LWS}, so a control character makes the header itself
     * malformed — the shape of a header-injection attempt, which is refused rather than
     * quietly cleaned up.
     */
    @ParameterizedTest(name = "[{index}] control character 0x{0} makes the header malformed")
    @ValueSource(ints = {0x00, 0x01, 0x0A, 0x0D, 0x1F, 0x7F})
    void refusesControlCharacters(int controlCharacter) {
        String malformed = "a" + (char) controlCharacter + "b";

        assertThrows(CisternException.BadInput.class, () -> Slug.from(malformed));
    }

    @Test
    @DisplayName("horizontal tab is inside the grammar (LWS) and is sanitized, not refused")
    void acceptsTab() {
        assertEquals("a-b", sanitized("a\tb"));
    }

    @ParameterizedTest(name = "[{index}] \"{0}\" is a malformed percent-escape")
    @ValueSource(strings = {"a%", "a%2", "a%zz", "a%2z", "%"})
    void refusesMalformedEscapes(String malformed) {
        assertThrows(CisternException.BadInput.class, () -> Slug.from(malformed));
    }

    // ---------------------------------------------------------------- the invariant itself

    /**
     * The record's own constructor is the last line of defence: a {@code Slug} handed to
     * {@code LdpService} is safe to append to a container URI because no other kind of value can
     * be constructed, whatever route it came by.
     */
    @ParameterizedTest(name = "[{index}] \"{0}\" is not a constructible Slug")
    @ValueSource(strings = {"", "a/b", "a%2Fb", "..", ".hidden", "trailing.", "-lead", "trail-",
            "a b", "a+b"})
    void constructorRejectsAnythingUnsanitized(String invalid) {
        assertThrows(IllegalArgumentException.class, () -> new Slug(invalid));
    }

    @Test
    void constructorRejectsNullAndOverlongNames() {
        assertThrows(IllegalArgumentException.class, () -> new Slug(null));
        assertThrows(IllegalArgumentException.class, () -> new Slug("x".repeat(Slug.MAX_LENGTH + 1)));
    }

    @Test
    @DisplayName("every name from() produces satisfies the constructor's invariant")
    void factoryAndInvariantAgree() {
        for (String fieldValue : new String[]{"my note", "../a", "%C3%A8", "...x...", "a" + "-".repeat(9) + "b"}) {
            Slug.from(fieldValue).ifPresent(slug -> assertEquals(slug, new Slug(slug.value())));
        }
    }
}
