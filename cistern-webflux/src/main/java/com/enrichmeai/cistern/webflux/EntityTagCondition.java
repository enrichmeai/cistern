package com.enrichmeai.cistern.webflux;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The parsed value of an {@code If-Match} or {@code If-None-Match} field, together with the
 * per-field evaluation rules RFC 9110 §13.1.1 and §13.1.2 define for it.
 *
 * <p>Both fields share one grammar — {@code "*" / #entity-tag} — and both are evaluated in
 * three numbered steps whose <em>shape</em> is identical; only the sense of the answer
 * differs. So the four cases here are exactly the four states a received field can be in,
 * and each evaluation method is a total {@code switch} over them, which is what makes it
 * impossible to implement one of the RFC's steps and quietly forget another:
 *
 * <table>
 *   <caption>The permitted cases against the RFC's own numbered steps</caption>
 *   <tr><th>Case</th><th>§13.1.1 (If-Match)</th><th>§13.1.2 (If-None-Match)</th></tr>
 *   <tr><td>{@link NotPresent}</td><td colspan="2">the step is skipped entirely (§13.2.2)</td></tr>
 *   <tr><td>{@link AnyRepresentation}</td><td>step 1</td><td>step 1</td></tr>
 *   <tr><td>{@link Listed}</td><td>step 2</td><td>step 2</td></tr>
 *   <tr><td>{@link Unparseable}</td><td>step 3 ("Otherwise…")</td><td>step 3 ("Otherwise…")</td></tr>
 * </table>
 *
 * <h2>A malformed field value is not a 400</h2>
 * Cistern does not reject an unparseable {@code If-Match}/{@code If-None-Match} with 400,
 * because the RFC already says what to do with one and it is not that. Step 3 of each
 * evaluation — "Otherwise, the condition is false" for {@code If-Match}, "Otherwise, the
 * condition is true" for {@code If-None-Match} — is reached precisely when the field value is
 * neither {@code *} nor a list of entity tags, which is the definition of malformed. Following
 * it gives a syntactically broken {@code If-Match} a 412 (the safe answer: the client's claim
 * about current state could not be established) and lets a broken {@code If-None-Match} through
 * (the safe answer: nothing was shown to match, so no cached copy may be revalidated). Both
 * are {@link Unparseable}, so the choice is made once, here, rather than at four call sites.
 *
 * <p>An empty field value parses as {@code Listed} with no tags and is behaviourally identical
 * to {@link Unparseable} under both evaluations — nothing can match an empty list — so no
 * special case exists for it.
 */
sealed interface EntityTagCondition {

    /** RFC 9110 §13.1.1/§13.1.2: the {@code "*"} alternative of the field's grammar. */
    String WILDCARD = "*";

    /** RFC 9110 §8.8.3: {@code opaque-tag = DQUOTE *etagc DQUOTE}. */
    char DQUOTE = '"';

    /** RFC 9110 §5.6.1: the separator of a {@code #rule} list. */
    char LIST_DELIMITER = ',';

    /** {@code etagc = %x21 / %x23-7E / obs-text}, as inclusive code-point bounds. */
    int ETAGC_EXCLAMATION_MARK = 0x21;
    int ETAGC_PRINTABLE_LOW = 0x23;
    int ETAGC_PRINTABLE_HIGH = 0x7E;
    int OBS_TEXT_LOW = 0x80;
    int OBS_TEXT_HIGH = 0xFF;

    /** Stands in for an empty tag list in a problem detail, where "" would read as a tag. */
    String NO_ENTITY_TAGS = "(no entity tags)";

    /** The client did not send this field. §13.2.2 skips the step that would evaluate it. */
    record NotPresent() implements EntityTagCondition {
    }

    /** {@code *} — a condition about the <em>existence</em> of any current representation. */
    record AnyRepresentation() implements EntityTagCondition {
    }

    /** A list of entity tags, possibly empty, in the order the client wrote them. */
    record Listed(List<ClientEntityTag> tags) implements EntityTagCondition {
        public Listed {
            tags = List.copyOf(tags);
        }
    }

    /** Neither {@code *} nor a well-formed list: the "Otherwise" branch of both evaluations. */
    record Unparseable(String fieldValue) implements EntityTagCondition {
    }

    /** Whether the client sent this field at all — the guard §13.2.2's steps are worded with. */
    default boolean isPresent() {
        return !(this instanceof NotPresent);
    }

    // ---------------------------------------------------------------- evaluation

    /**
     * RFC 9110 §13.1.1, verbatim: "1. If the field value is '*', the condition is true if the
     * origin server has a current representation for the target resource. 2. If the field value
     * is a list of entity tags, the condition is true if any of the listed tags match the entity
     * tag of the selected representation. 3. Otherwise, the condition is false."
     *
     * <p>Matching uses {@link ClientEntityTag#matchesStrongly}, because "an origin server MUST
     * use the strong comparison function when comparing entity tags for If-Match".
     *
     * <p><b>"the selected representation", for a method that selects none.</b> A {@code PUT} or
     * {@code DELETE} carries no {@code Accept}, so nothing picks one of an RDF source's two
     * representations (Turtle and JSON-LD have different bytes and therefore, by §8.8.1,
     * different strong validators). The state handed in is accordingly the tag set of <em>every
     * current representation</em> (see {@link TargetState#acrossAllRepresentations}), and a
     * match against any of them satisfies the condition. That is the field's own definition
     * rather than a liberty taken with it: §13.1.1's opening sentence makes the field
     * conditional on the server "having a current representation of the target resource that has
     * an entity tag matching a member of the list of entity tags provided". Any other reading
     * would fail every {@code If-Match} a client formed from a {@code GET}, since it would have
     * to guess which of the two the server considered selected.
     */
    default boolean satisfiedBy(TargetState state) {
        return switch (this) {
            case NotPresent ignored -> true;                       // step skipped, not evaluated
            case AnyRepresentation ignored -> state instanceof TargetState.Present;
            case Listed listed -> anyMatch(listed, state, true);
            case Unparseable ignored -> false;                     // step 3
        };
    }

    /**
     * RFC 9110 §13.1.2, verbatim: "1. If the field value is '*', the condition is false if the
     * origin server has a current representation for the target resource. 2. If the field value
     * is a list of entity tags, the condition is false if one of the listed tags matches the
     * entity tag of the selected representation. 3. Otherwise, the condition is true."
     *
     * <p>Matching uses {@link ClientEntityTag#matchesWeakly}, because "a recipient MUST use the
     * weak comparison function when comparing entity tags for If-None-Match".
     *
     * <p>Unlike {@link #satisfiedBy}, the tag list here is only ever evaluated against the one
     * representation a {@code GET}/{@code HEAD} selected: the point of a false result is a 304,
     * which tells the client the copy it holds is current, and that is a claim about a specific
     * set of bytes. {@code *} needs no representation at all — it is the create-only guard
     * §13.1.2 describes, "to prevent an unsafe request method (e.g., PUT) from inadvertently
     * modifying an existing representation of the target resource when the client believes that
     * the resource does not have a current representation".
     */
    default boolean notSatisfiedBy(TargetState state) {
        return switch (this) {
            case NotPresent ignored -> true;                       // step skipped, not evaluated
            case AnyRepresentation ignored -> !(state instanceof TargetState.Present);
            case Listed listed -> !anyMatch(listed, state, false);
            case Unparseable ignored -> true;                      // step 3
        };
    }

    private static boolean anyMatch(Listed listed, TargetState state, boolean strong) {
        if (!(state instanceof TargetState.Present present)) {
            return false;
        }
        return listed.tags().stream().anyMatch(received -> present.currentTags().stream()
                .anyMatch(current -> strong
                        ? received.matchesStrongly(current)
                        : received.matchesWeakly(current)));
    }

    // ---------------------------------------------------------------- parsing

    /**
     * Parses the field's value from every line the client sent for it. Repeated field lines are
     * combined into one comma-separated list first, as RFC 9110 §5.3 requires ("a recipient MAY
     * combine multiple field lines within that field section into one field line ... by
     * appending each subsequent field line value ... separated by a comma").
     *
     * @param fieldValues every value received for this field; empty if it was not sent
     */
    static EntityTagCondition parse(List<String> fieldValues) {
        if (fieldValues.isEmpty()) {
            return new NotPresent();
        }
        String combined = String.join(HttpConstants.LIST_SEPARATOR, fieldValues);
        if (combined.strip().equals(WILDCARD)) {
            return new AnyRepresentation();
        }
        // §13.1.1/§13.1.2: "a field with a list value containing '*' and other values ... is
        // syntactically invalid", which the scanner reports by refusing '*' inside a list.
        List<ClientEntityTag> tags = scan(combined);
        return tags == null ? new Unparseable(combined) : new Listed(tags);
    }

    /**
     * Scans {@code #entity-tag} (RFC 9110 §5.6.1, §8.8.3), returning {@code null} if the value
     * is not one.
     *
     * <p>Hand-written rather than a {@code split(",")} because a comma is a legal
     * {@code etagc} ({@code %x23-7E} covers {@code %x2C}), so {@code If-Match: "a,b"} is one tag
     * and not two — splitting would silently invent tags that were never sent and compare
     * against them.
     */
    private static List<ClientEntityTag> scan(String value) {
        List<ClientEntityTag> tags = new ArrayList<>();
        int at = 0;
        int end = value.length();
        while (true) {
            at = skipListDelimiters(value, at);
            if (at == end) {
                return tags;
            }
            boolean weak = value.startsWith(ClientEntityTag.WEAK_PREFIX, at);
            if (weak) {
                at += ClientEntityTag.WEAK_PREFIX.length();
            }
            if (at == end || value.charAt(at) != DQUOTE) {
                return null;
            }
            int closingQuote = value.indexOf(DQUOTE, at + 1);
            if (closingQuote < 0) {
                return null;
            }
            String opaqueTag = value.substring(at + 1, closingQuote);
            if (!isOpaqueTag(opaqueTag)) {
                return null;
            }
            tags.add(new ClientEntityTag(opaqueTag, weak));
            at = skipOptionalWhitespace(value, closingQuote + 1);
            if (at != end && value.charAt(at) != LIST_DELIMITER) {
                return null;                        // trailing junk after a well-formed tag
            }
        }
    }

    /** {@code etagc = %x21 / %x23-7E / obs-text} — no controls, no {@code DQUOTE}, no DEL. */
    private static boolean isOpaqueTag(String opaqueTag) {
        return opaqueTag.chars().allMatch(character ->
                character == ETAGC_EXCLAMATION_MARK
                        || (character >= ETAGC_PRINTABLE_LOW && character <= ETAGC_PRINTABLE_HIGH)
                        || (character >= OBS_TEXT_LOW && character <= OBS_TEXT_HIGH));
    }

    /** RFC 9110 §5.6.1 tolerates empty list elements, so commas and OWS are both skipped. */
    private static int skipListDelimiters(String value, int from) {
        int at = from;
        while (at < value.length()
                && (value.charAt(at) == LIST_DELIMITER || isOptionalWhitespace(value.charAt(at)))) {
            at++;
        }
        return at;
    }

    private static int skipOptionalWhitespace(String value, int from) {
        int at = from;
        while (at < value.length() && isOptionalWhitespace(value.charAt(at))) {
            at++;
        }
        return at;
    }

    /** {@code OWS = *( SP / HTAB )} — RFC 9110 §5.6.3. */
    private static boolean isOptionalWhitespace(char character) {
        return character == ' ' || character == '\t';
    }

    // ---------------------------------------------------------------- diagnostics

    /** How this condition reads back to the client in a 412 problem detail. */
    default String describe() {
        return switch (this) {
            case NotPresent ignored -> "";
            case AnyRepresentation ignored -> WILDCARD;
            case Listed listed -> listed.tags().isEmpty()
                    ? NO_ENTITY_TAGS
                    : listed.tags().stream().map(ClientEntityTag::fieldValue)
                            .collect(Collectors.joining(HttpConstants.LIST_SEPARATOR));
            case Unparseable unparseable -> unparseable.fieldValue();
        };
    }
}
