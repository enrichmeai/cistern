package com.enrichmeai.cistern.storage.file;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * The metadata sidecar record: {@code contentType}, {@code etag} and {@code lastModified}
 * for one stored resource. Serialized as a single flat JSON object of string fields, e.g.
 * {@code {"contentType":"text/turtle","etag":"ab12…","lastModified":"2026-07-19T10:00:00Z"}}.
 *
 * <p>{@code lastModified} is persisted (not derived from file mtime) so the per-resource
 * monotonic clock guard survives process restarts and system clock regressions.
 *
 * <p>The JSON here is hand-rolled by design: the module declares no JSON library, and the
 * only ones on the classpath ({@code mvn dependency:tree}: gson, jakarta.json) are
 * transitive internals of Jena via cistern-core — coupling the storage backend to the RDF
 * layer's transitive dependencies would be an undeclared-dependency smell, and three known
 * string fields do not justify a new dependency. The writer escapes {@code "},
 * {@code \} and all control characters below U+0020 (as backslash-u escapes); the parser
 * is a minimal recursive-descent reader for exactly this shape — one object, string keys,
 * string values, full JSON string-escape support (quote, backslash, slash, b, f, n, r, t
 * and backslash-u), unknown keys ignored. Content types are client-supplied, so escaping
 * is not optional.
 */
record Sidecar(String contentType, String etag, Instant lastModified) {

    /** The sidecar's JSON field names — written by {@link #toJson}, read by {@link #fromJson}. */
    static final String FIELD_CONTENT_TYPE = "contentType";

    static final String FIELD_ETAG = "etag";

    static final String FIELD_LAST_MODIFIED = "lastModified";

    String toJson() {
        return "{" + quote(FIELD_CONTENT_TYPE) + ":" + quote(contentType)
                + "," + quote(FIELD_ETAG) + ":" + quote(etag)
                + "," + quote(FIELD_LAST_MODIFIED) + ":" + quote(lastModified.toString())
                + "}";
    }

    static Sidecar fromJson(String json) {
        Map<String, String> fields = parseFlatStringObject(json);
        String contentType = required(fields, FIELD_CONTENT_TYPE, json);
        String etag = required(fields, FIELD_ETAG, json);
        String lastModified = required(fields, FIELD_LAST_MODIFIED, json);
        return new Sidecar(contentType, etag, Instant.parse(lastModified));
    }

    private static String required(Map<String, String> fields, String key, String json) {
        String value = fields.get(key);
        if (value == null) {
            throw new IllegalArgumentException(
                    StorageFileMessage.SIDECAR_FIELD_MISSING.format(key, json));
        }
        return value;
    }

    // ------------------------------------------------------------ JSON writer

    private static String quote(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04X", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    // ------------------------------------------------------------ JSON parser

    /** Parses one {@code {"k":"v",…}} object; string values only; unknown keys kept. */
    private static Map<String, String> parseFlatStringObject(String json) {
        Cursor in = new Cursor(json);
        Map<String, String> fields = new HashMap<>();
        in.skipWhitespace();
        in.expect('{');
        in.skipWhitespace();
        if (in.peek() == '}') {
            in.next();
            return fields;
        }
        while (true) {
            in.skipWhitespace();
            String key = in.string();
            in.skipWhitespace();
            in.expect(':');
            in.skipWhitespace();
            String value = in.string();
            fields.put(key, value);
            in.skipWhitespace();
            char c = in.next();
            if (c == '}') {
                return fields;
            }
            if (c != ',') {
                throw new IllegalArgumentException(
                        StorageFileMessage.SIDECAR_SEPARATOR_EXPECTED.format(json));
            }
        }
    }

    private static final class Cursor {
        private final String text;
        private int pos;

        Cursor(String text) {
            this.text = text;
        }

        char peek() {
            if (pos >= text.length()) {
                throw new IllegalArgumentException(StorageFileMessage.SIDECAR_TRUNCATED.format(text));
            }
            return text.charAt(pos);
        }

        char next() {
            char c = peek();
            pos++;
            return c;
        }

        void expect(char expected) {
            char c = next();
            if (c != expected) {
                throw new IllegalArgumentException(
                        StorageFileMessage.SIDECAR_CHARACTER_EXPECTED.format(expected, c, text));
            }
        }

        void skipWhitespace() {
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
        }

        String string() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = next();
                if (c == '"') {
                    return sb.toString();
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                char escape = next();
                switch (escape) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        int code = 0;
                        for (int i = 0; i < 4; i++) {
                            code = code << 4 | hexDigit(next());
                        }
                        sb.append((char) code);
                    }
                    default -> throw new IllegalArgumentException(
                            StorageFileMessage.SIDECAR_ESCAPE_BAD.format(escape, text));
                }
            }
        }

        private int hexDigit(char c) {
            int v = Character.digit(c, 16);
            if (v < 0) {
                throw new IllegalArgumentException(
                        StorageFileMessage.SIDECAR_UNICODE_ESCAPE_BAD.format(text));
            }
            return v;
        }
    }
}
