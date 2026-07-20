package com.enrichmeai.cistern.webflux;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A parser for the {@code Link} header field, RFC 8288 §3.
 *
 * <h2>Why this is a parser and not a {@code contains()}</h2>
 * {@code Link} is a structured field, and every part of its structure defeats substring
 * matching:
 *
 * <pre>{@code
 *   Link       = #link-value
 *   link-value = "<" URI-Reference ">" *( OWS ";" OWS link-param )
 *   link-param = token BWS [ "=" BWS ( token / quoted-string ) ]
 * }</pre>
 *
 * <ul>
 *   <li>A field can carry several link-values separated by commas, and the header itself can be
 *       sent as several field lines — so one {@code Link} is a <em>list</em>.</li>
 *   <li>Both the URI-Reference (inside {@code <>}) and a quoted parameter value may contain a
 *       comma, so splitting on commas splits real values apart.</li>
 *   <li>A parameter value may be a bare token or a quoted-string with backslash escapes, so
 *       {@code rel="type"} and {@code rel=type} are the same request and neither spelling can be
 *       privileged.</li>
 *   <li>Parameter names and relation types are case-insensitive.</li>
 * </ul>
 *
 * <p>The concrete consequence for T2.3: a client sending
 * {@code Link: <https://example/a,b>; rel="type"} or
 * {@code Link: <https://vocab.example/Note>; title="not a, type"} must not be read as having
 * requested a container, and a client sending
 * {@code Link: <http://www.w3.org/ns/ldp#Resource>; rel="type", <http://www.w3.org/ns/ldp#BasicContainer>; rel="type"}
 * must be. A substring search for the container IRI gets the first two wrong.
 *
 * <h2>Tolerant of garbage, by design</h2>
 * A link-value this parser cannot make sense of is skipped and the rest of the field is still
 * read. RFC 8288 §3 takes that line throughout — "unregistered link relation types ... MUST be
 * ignored", "parameters ... that are not understood MUST be ignored" — and the alternative is
 * worse here: refusing the whole request over an unparseable {@code Link} would make a header
 * that is optional on {@code POST} able to fail a create. A client whose {@code Link} was not
 * understood learns what was actually made from the mandatory {@code Location} header.
 */
final class LinkHeader {

    private static final char TARGET_OPEN = '<';
    private static final char TARGET_CLOSE = '>';
    private static final char VALUE_SEPARATOR = ',';
    private static final char PARAMETER_SEPARATOR = ';';
    private static final char PARAMETER_ASSIGNMENT = '=';
    private static final char QUOTE = '"';
    private static final char ESCAPE = '\\';

    /** The parameter naming a link's relation types (RFC 8288 §3.3). */
    private static final String REL_PARAMETER = "rel";

    /**
     * One parsed link-value: its target IRI and the parameters that qualify it.
     *
     * @param target     the URI-Reference between {@code <} and {@code >}, verbatim
     * @param parameters the link-params in the order they appeared; a valueless param has a
     *                   {@code null} value, which RFC 8288's ABNF permits
     */
    record Value(String target, List<Parameter> parameters) {

        /** Whether this link declares {@code relation} in its {@code rel} parameter. */
        boolean hasRelation(LinkRelation relation) {
            for (Parameter parameter : parameters) {
                if (parameter.isNamed(REL_PARAMETER) && parameter.value() != null
                        && relation.isNamedBy(parameter.value())) {
                    return true;
                }
            }
            return false;
        }
    }

    /** One link-param. Names are case-insensitive (RFC 8288 §3), so comparison goes through {@link #isNamed}. */
    record Parameter(String name, String value) {

        boolean isNamed(String candidate) {
            return name.toLowerCase(Locale.ROOT).equals(candidate);
        }
    }

    /**
     * The target IRIs of every link in {@code fieldValues} carrying the given relation.
     *
     * @param fieldValues every {@code Link} field line on the request, each of which may itself
     *                    hold a comma-separated list
     */
    static List<String> targetsWithRelation(List<String> fieldValues, LinkRelation relation) {
        List<String> targets = new ArrayList<>();
        for (Value value : parse(fieldValues)) {
            if (value.hasRelation(relation)) {
                targets.add(value.target());
            }
        }
        return targets;
    }

    /** Every link-value across every field line, in order. */
    static List<Value> parse(List<String> fieldValues) {
        List<Value> values = new ArrayList<>();
        for (String fieldValue : fieldValues) {
            if (fieldValue != null) {
                parseInto(fieldValue, values);
            }
        }
        return values;
    }

    // ---------------------------------------------------------------- the scanner

    /**
     * Scans one field line. The cursor only ever moves forward, and every branch that cannot
     * make progress advances to the next top-level comma, so a malformed value costs the values
     * around it nothing.
     */
    private static void parseInto(String fieldValue, List<Value> values) {
        int i = 0;
        while (i < fieldValue.length()) {
            i = skipWhitespaceAndSeparators(fieldValue, i);
            if (i >= fieldValue.length()) {
                return;
            }
            if (fieldValue.charAt(i) != TARGET_OPEN) {
                i = skipToNextValue(fieldValue, i);
                continue;
            }
            int close = fieldValue.indexOf(TARGET_CLOSE, i + 1);
            if (close < 0) {
                // No closing '>': the remainder cannot be segmented into link-values at all,
                // because any comma after this point might belong to the unterminated target.
                return;
            }
            String target = fieldValue.substring(i + 1, close).trim();
            List<Parameter> parameters = new ArrayList<>();
            i = parseParameters(fieldValue, close + 1, parameters);
            values.add(new Value(target, List.copyOf(parameters)));
        }
    }

    /** Reads {@code *( OWS ";" OWS link-param )} and returns the index of the value separator. */
    private static int parseParameters(String fieldValue, int from, List<Parameter> parameters) {
        int i = from;
        while (i < fieldValue.length()) {
            i = skipWhitespace(fieldValue, i);
            if (i >= fieldValue.length() || fieldValue.charAt(i) == VALUE_SEPARATOR) {
                return i;
            }
            if (fieldValue.charAt(i) != PARAMETER_SEPARATOR) {
                return skipToNextValue(fieldValue, i);
            }
            i = parseParameter(fieldValue, i + 1, parameters);
        }
        return i;
    }

    /** Reads one {@code token BWS [ "=" BWS ( token / quoted-string ) ]}. */
    private static int parseParameter(String fieldValue, int from, List<Parameter> parameters) {
        int i = skipWhitespace(fieldValue, from);
        int nameStart = i;
        while (i < fieldValue.length() && !isParameterNameEnd(fieldValue.charAt(i))) {
            i++;
        }
        String name = fieldValue.substring(nameStart, i).trim();
        i = skipWhitespace(fieldValue, i);
        if (i >= fieldValue.length() || fieldValue.charAt(i) != PARAMETER_ASSIGNMENT) {
            if (!name.isEmpty()) {
                parameters.add(new Parameter(name, null));
            }
            return i;
        }
        i = skipWhitespace(fieldValue, i + 1);
        if (i >= fieldValue.length()) {
            // "rel=" with nothing after it: a named parameter with an empty value, not a crash.
            if (!name.isEmpty()) {
                parameters.add(new Parameter(name, ""));
            }
            return i;
        }
        StringBuilder value = new StringBuilder();
        i = fieldValue.charAt(i) == QUOTE
                ? readQuotedString(fieldValue, i, value)
                : readToken(fieldValue, i, value);
        if (!name.isEmpty()) {
            parameters.add(new Parameter(name, value.toString()));
        }
        return i;
    }

    /**
     * Reads a quoted-string, unescaping {@code \x} to {@code x} (RFC 9110 §5.6.4). Reading this
     * inline is what keeps a comma or a semicolon inside the quotes from being mistaken for
     * structure.
     */
    private static int readQuotedString(String fieldValue, int from, StringBuilder value) {
        int i = from + 1;
        while (i < fieldValue.length()) {
            char c = fieldValue.charAt(i);
            if (c == ESCAPE && i + 1 < fieldValue.length()) {
                value.append(fieldValue.charAt(i + 1));
                i += 2;
                continue;
            }
            if (c == QUOTE) {
                return i + 1;
            }
            value.append(c);
            i++;
        }
        return i;
    }

    /** Reads a bare token — everything up to the next structural character or whitespace. */
    private static int readToken(String fieldValue, int from, StringBuilder value) {
        int i = from;
        while (i < fieldValue.length() && !isParameterNameEnd(fieldValue.charAt(i))) {
            value.append(fieldValue.charAt(i));
            i++;
        }
        return i;
    }

    private static boolean isParameterNameEnd(char c) {
        return c == PARAMETER_ASSIGNMENT || c == PARAMETER_SEPARATOR || c == VALUE_SEPARATOR
                || isWhitespace(c);
    }

    private static int skipWhitespace(String fieldValue, int from) {
        int i = from;
        while (i < fieldValue.length() && isWhitespace(fieldValue.charAt(i))) {
            i++;
        }
        return i;
    }

    private static int skipWhitespaceAndSeparators(String fieldValue, int from) {
        int i = from;
        while (i < fieldValue.length()
                && (isWhitespace(fieldValue.charAt(i)) || fieldValue.charAt(i) == VALUE_SEPARATOR)) {
            i++;
        }
        return i;
    }

    /** Past the next top-level comma: how the scanner recovers from a value it cannot read. */
    private static int skipToNextValue(String fieldValue, int from) {
        int comma = fieldValue.indexOf(VALUE_SEPARATOR, from);
        return comma < 0 ? fieldValue.length() : comma + 1;
    }

    /** RFC 9110 §5.6.3 OWS: space and horizontal tab. */
    private static boolean isWhitespace(char c) {
        return c == ' ' || c == '\t';
    }

    private LinkHeader() {
        // static parser
    }
}
