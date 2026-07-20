package com.enrichmeai.cistern.core.ldp;

import com.enrichmeai.cistern.core.CisternException;
import com.enrichmeai.cistern.core.CoreMessage;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * A client's name hint for a resource being created by {@code POST}, reduced to a form that is
 * safe to use as exactly one URI path segment.
 *
 * <h2>What the specs actually say</h2>
 * Less than one might expect, which is why every rule below is stated rather than assumed:
 *
 * <ul>
 *   <li><b>RFC 5023 §9.7</b> (the {@code Slug} header's definition, which LDP adopts):
 *       "Slug is an HTTP entity-header whose presence in a POST to a Collection constitutes a
 *       request by the client to use the header's value as part of any URIs that would normally
 *       be used to retrieve the to-be-created Entry or Media Resources." It is explicit that the
 *       server stays in charge: "Servers MAY choose to ignore the Slug entity-header. Servers
 *       MAY alter the header value before using it. For instance, a server might filter out some
 *       characters or replace accented letters with non-accented ones, replace spaces with
 *       underscores, change case, and so on."</li>
 *   <li><b>LDP 1.0 §5.2.3.10</b>: "LDP servers MAY allow clients to suggest the URI for a
 *       resource created through POST, using the HTTP Slug header as defined in [RFC5023].
 *       <em>LDP adds no new requirements to this usage</em>, so its presence functions as a
 *       client hint to the server providing a desired string to be incorporated into the
 *       server's final choice of resource URI."</li>
 *   <li><b>Solid Protocol</b> mentions {@code Slug} only in its changelog, which records the
 *       requirement being dropped in v0.11.0: "Remove requirement as the Slug header field is
 *       not widely implemented and should not be used to match any resource."</li>
 * </ul>
 *
 * <p>So: honouring a slug is optional, altering it is explicitly permitted, and <b>no
 * specification defines the sanitization</b>. The rules are therefore Cistern's own, written
 * down here rather than left implicit in a regular expression at a call site.
 *
 * <h2>The rules</h2>
 * <ol>
 *   <li><b>Grammar first.</b> RFC 5023 §9.7.1 gives {@code slugtext = %x20-7E | LWS}. A field
 *       value carrying a control character (anything below {@code %x20} other than a tab, or
 *       {@code %x7F}) is outside that grammar and is refused with
 *       {@link CisternException.BadInput} — 400, matching what {@code CisternException.BadInput}
 *       has always documented for an invalid slug. Control characters are the header-injection
 *       shaped input, and quietly accepting them is worse than refusing them. Bytes at or above
 *       {@code %x80} are tolerated (many clients send raw UTF-8 rather than percent-encoding it)
 *       and are removed by rule 3.</li>
 *   <li><b>Decoded exactly once.</b> RFC 5023 §9.7.1: "The field value is the percent-encoded
 *       value of the UTF-8 encoding of the character sequence to be included ... To consume the
 *       field value, first reverse the percent encoding, then run the resulting octet sequence
 *       through a UTF-8 decoding process." A malformed escape ({@code %2}, {@code %zz}) is
 *       {@link CisternException.BadInput}. Decoding <em>once</em> is the load-bearing word:
 *       {@code %252F} decodes to the text {@code %2F} and is then sanitized as text, so no
 *       amount of nesting can reintroduce a separator.</li>
 *   <li><b>Allowlisted, not denylisted.</b> Every character outside the RFC 3986 §2.3
 *       <em>unreserved</em> set ({@code ALPHA / DIGIT / "-" / "." / "_" / "~"}) becomes
 *       {@code -}. This is a closed rule: a denylist would have to anticipate every dangerous
 *       octet ({@code /}, {@code \}, {@code %2F}, {@code NUL}, {@code :}, a homoglyph), while an
 *       allowlist admits only characters that are safe in a path segment <em>and</em> need no
 *       percent-encoding, so the resulting identifier is well-formed by construction.</li>
 *   <li><b>Runs collapse, edges are trimmed.</b> Consecutive {@code -} become one; leading and
 *       trailing {@code -} and {@code .} are removed. This is what makes path traversal
 *       impossible rather than merely unlikely: {@code .} and {@code ..} are all-dot names, so
 *       trimming reduces them to nothing, and a slug can never name the container itself
 *       ({@code .}), its parent ({@code ..}), or a dotfile.</li>
 *   <li><b>Bounded.</b> Truncated to {@value #MAX_LENGTH} characters before trimming, so a
 *       megabyte-long hint cannot become a megabyte-long file name.</li>
 *   <li><b>Nothing left means no hint.</b> A slug that sanitizes to the empty string — {@code /},
 *       {@code ../}, {@code %2F}, {@code "   "}, {@code "..."} — yields
 *       {@link Optional#empty()}, and the caller mints a name as if no {@code Slug} had been
 *       sent. Rationale: RFC 5023 says outright that a server may ignore the header, and LDP
 *       §5.2.3.8 covers the outcome ("LDP servers SHOULD assign the URI for the resource to be
 *       created using server application specific rules <em>in the absence of a client hint</em>")
 *       — an unusable hint is no hint. Refusing the whole request instead would turn an
 *       explicitly-ignorable header into a hard failure, and the client is told the name that
 *       was actually used by the mandatory {@code Location} header either way.</li>
 * </ol>
 *
 * <p>The distinction between rule 1 (400) and rule 6 (ignored) is deliberate and is the one
 * judgement call here: a control character or a broken escape makes the <em>header</em>
 * malformed, while {@code Slug: ../} is a perfectly well-formed header carrying a preference
 * this server will not act on.
 *
 * <p><b>Not yet covered:</b> names reserved by other parts of the protocol — an ACL or
 * description auxiliary resource's suffix — cannot be excluded here until auxiliary resources
 * exist (Phase 4). When they do, that exclusion belongs in this record's invariant.
 *
 * @param value the sanitized name: one path segment, non-empty, unreserved characters only
 */
public record Slug(String value) {

    /**
     * Longest name a slug may produce. Bounded well below the 255-byte limit that common file
     * systems place on a single name component, since a backend may add a suffix of its own
     * (the file store's metadata sidecar) to whatever this yields.
     */
    public static final int MAX_LENGTH = 128;

    /** What every character outside the unreserved set becomes. */
    private static final char REPLACEMENT = '-';

    /** Trimmed from both ends: the characters that would make a dotfile or a ragged name. */
    private static final String TRIMMED_EDGE_CHARACTERS = ".-";

    /** RFC 3986 §2.3 unreserved punctuation; the alphanumerics are tested separately. */
    private static final String UNRESERVED_PUNCTUATION = "-._~";

    private static final char PERCENT = '%';
    private static final int ESCAPE_LENGTH = 2;
    private static final int HEX_RADIX = 16;
    private static final int NIBBLE_BITS = 4;

    /** RFC 5023 §9.7.1: {@code slugtext = %x20-7E | LWS} — the bounds of the printable range. */
    private static final char FIRST_PRINTABLE = 0x20;
    private static final char DELETE = 0x7F;
    private static final char TAB = 0x09;

    public Slug {
        if (value == null || value.isEmpty() || value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(CoreMessage.SLUG_NOT_A_NAME.format(value));
        }
        for (int i = 0; i < value.length(); i++) {
            if (!isUnreserved(value.charAt(i))) {
                throw new IllegalArgumentException(CoreMessage.SLUG_NOT_A_NAME.format(value));
            }
        }
        if (isTrimmedEdge(value.charAt(0)) || isTrimmedEdge(value.charAt(value.length() - 1))) {
            throw new IllegalArgumentException(CoreMessage.SLUG_NOT_A_NAME.format(value));
        }
    }

    /**
     * The sanitized form of a {@code Slug} field value, per the rules in the class javadoc.
     *
     * @param fieldValue the raw header value, or {@code null} when the client sent no
     *                   {@code Slug} at all
     * @return the usable name, or {@link Optional#empty()} when there was no hint or nothing
     *         survived sanitization — in both cases the server mints a name instead
     * @throws CisternException.BadInput if the field value breaks RFC 5023 §9.7.1's grammar
     *                                   (a control character) or carries a malformed
     *                                   percent-escape (→ 400 via the single error mapper)
     */
    public static Optional<Slug> from(String fieldValue) {
        if (fieldValue == null) {
            return Optional.empty();
        }
        String sanitized = sanitize(decode(rejectControlCharacters(fieldValue)));
        return sanitized.isEmpty() ? Optional.empty() : Optional.of(new Slug(sanitized));
    }

    // ---------------------------------------------------------------- internals

    /** Rule 1: RFC 5023 §9.7.1's grammar admits printable ASCII, tab, and nothing else below it. */
    private static String rejectControlCharacters(String fieldValue) {
        for (int i = 0; i < fieldValue.length(); i++) {
            char c = fieldValue.charAt(i);
            if ((c < FIRST_PRINTABLE && c != TAB) || c == DELETE) {
                throw new CisternException.BadInput(CoreMessage.SLUG_MALFORMED.format((int) c));
            }
        }
        return fieldValue;
    }

    /**
     * Rule 2: reverse the percent-encoding once, then decode the octets as UTF-8. Invalid UTF-8
     * decodes to U+FFFD rather than throwing, and rule 3 then removes it — the octets came from
     * a name hint, so there is nothing to salvage and nothing to fail over.
     */
    private static String decode(String fieldValue) {
        if (fieldValue.indexOf(PERCENT) < 0) {
            return fieldValue;
        }
        ByteArrayOutputStream octets = new ByteArrayOutputStream(fieldValue.length());
        for (int i = 0; i < fieldValue.length(); i++) {
            char c = fieldValue.charAt(i);
            if (c != PERCENT) {
                writeUtf8(octets, c);
                continue;
            }
            if (i + ESCAPE_LENGTH >= fieldValue.length()) {
                throw new CisternException.BadInput(
                        CoreMessage.SLUG_ESCAPE_MALFORMED.format(fieldValue.substring(i)));
            }
            String escape = fieldValue.substring(i + 1, i + 1 + ESCAPE_LENGTH);
            int high = Character.digit(escape.charAt(0), HEX_RADIX);
            int low = Character.digit(escape.charAt(1), HEX_RADIX);
            if (high < 0 || low < 0) {
                throw new CisternException.BadInput(
                        CoreMessage.SLUG_ESCAPE_MALFORMED.format(PERCENT + escape));
            }
            octets.write((high << NIBBLE_BITS) | low);
            i += ESCAPE_LENGTH;
        }
        return octets.toString(StandardCharsets.UTF_8);
    }

    /** A character the client sent literally, re-encoded so the octet stream stays UTF-8. */
    private static void writeUtf8(ByteArrayOutputStream octets, char c) {
        for (byte b : String.valueOf(c).getBytes(StandardCharsets.UTF_8)) {
            octets.write(b);
        }
    }

    /** Rules 3 to 5: allowlist, collapse runs, bound the length, trim the edges. */
    private static String sanitize(String decoded) {
        StringBuilder name = new StringBuilder(Math.min(decoded.length(), MAX_LENGTH));
        for (int i = 0; i < decoded.length() && name.length() < MAX_LENGTH; i++) {
            char c = decoded.charAt(i);
            if (isUnreserved(c)) {
                name.append(c);
            } else if (name.length() > 0 && name.charAt(name.length() - 1) != REPLACEMENT) {
                name.append(REPLACEMENT);
            }
        }
        return trimEdges(name);
    }

    /** Rule 4: a name may not begin or end with the characters that make it ragged or hidden. */
    private static String trimEdges(StringBuilder name) {
        int start = 0;
        int end = name.length();
        while (start < end && isTrimmedEdge(name.charAt(start))) {
            start++;
        }
        while (end > start && isTrimmedEdge(name.charAt(end - 1))) {
            end--;
        }
        return name.substring(start, end);
    }

    private static boolean isUnreserved(char c) {
        return (c >= 'a' && c <= 'z')
                || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9')
                || UNRESERVED_PUNCTUATION.indexOf(c) >= 0;
    }

    private static boolean isTrimmedEdge(char c) {
        return TRIMMED_EDGE_CHARACTERS.indexOf(c) >= 0;
    }
}
