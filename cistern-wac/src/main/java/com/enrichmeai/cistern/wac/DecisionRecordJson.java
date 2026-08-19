package com.enrichmeai.cistern.wac;

import com.enrichmeai.cistern.core.Agent;
import com.enrichmeai.cistern.core.ResourceIdentifier;

import java.net.URI;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.StringJoiner;

import org.apache.jena.atlas.json.JSON;
import org.apache.jena.atlas.json.JsonException;
import org.apache.jena.atlas.json.JsonObject;
import org.apache.jena.atlas.json.JsonValue;
import org.apache.jena.atlas.json.io.JSWriter;

/**
 * {@link DecisionRecord} ⇄ one line of JSON (T5.9). The on-disk and on-the-wire format of a
 * receipt, in one place, so the sink and the query cannot drift.
 *
 * <p>One flat object per record, every {@link DecisionField} present in declaration order,
 * string-valued or {@code null}; no nesting, no numbers, no arrays. For example:
 *
 * <pre>
 * {"at":"2026-08-19T09:12:03.421Z","agent":null,"target":"http://localhost:3737/notes/week","required":"READ","outcome":"DENIED_UNAUTHENTICATED","decidedBy":null,"requestId":"5c1f…"}
 * </pre>
 *
 * <p>Read and escaped with Jena's own JSON support ({@code org.apache.jena.atlas.json}), which
 * this module already declares through {@code jena-arq}: a second JSON library for seven string
 * fields would be a dependency for its own sake, and hand-rolling a parser or an escaper is how
 * encoding bugs are born. The line itself is assembled here, member by member, so that it is
 * compact and its member order is {@link DecisionField}'s — one shape, every line.
 *
 * <p>Reading is strict: a line that is not an object, or is missing a field, or holds a value
 * that does not parse as what the field means, is not a record. {@link #parse} reports that as
 * empty rather than throwing, and the caller decides how loudly to say so — a scan over a
 * day's log should not abort on one damaged line, but it should not pretend the line was fine
 * either.
 */
public final class DecisionRecordJson {

    /** JSON's null literal, for an absent agent or policy. */
    private static final String NULL = "null";

    private static final String MEMBER_SEPARATOR = ",";
    private static final String KEY_VALUE_SEPARATOR = ":";
    private static final String OBJECT_START = "{";
    private static final String OBJECT_END = "}";

    private DecisionRecordJson() {
        // static codec only
    }

    /** {@code record} as a single line of JSON, without a trailing line separator. */
    public static String toLine(DecisionRecord record) {
        Objects.requireNonNull(record, "record");
        StringJoiner object = new StringJoiner(MEMBER_SEPARATOR, OBJECT_START, OBJECT_END);
        object.add(member(DecisionField.AT, record.at().toString()));
        object.add(member(DecisionField.AGENT, record.agent().webId().map(URI::toString)));
        object.add(member(DecisionField.TARGET, record.target().uri().toString()));
        object.add(member(DecisionField.REQUIRED, record.required().name()));
        object.add(member(DecisionField.OUTCOME, record.outcome().name()));
        object.add(member(DecisionField.DECIDED_BY, record.decidedBy().map(acl -> acl.uri().toString())));
        object.add(member(DecisionField.REQUEST_ID, record.requestId().value()));
        return object.toString();
    }

    private static String member(DecisionField field, String value) {
        return member(field, Optional.of(value));
    }

    /** {@code "key":"value"}, or {@code "key":null} — the key is always present. */
    private static String member(DecisionField field, Optional<String> value) {
        return JSWriter.outputQuotedString(field.key()) + KEY_VALUE_SEPARATOR
                + value.map(JSWriter::outputQuotedString).orElse(NULL);
    }

    /**
     * The record {@code line} holds, or empty if {@code line} is not a well-formed record.
     */
    public static Optional<DecisionRecord> parse(String line) {
        Objects.requireNonNull(line, "line");
        try {
            JsonObject object = JSON.parse(line);
            return Optional.of(new DecisionRecord(
                    Instant.parse(requiredString(object, DecisionField.AT)),
                    optionalString(object, DecisionField.AGENT)
                            .map(URI::create).map(Agent::of).orElse(Agent.ANONYMOUS),
                    new ResourceIdentifier(URI.create(requiredString(object, DecisionField.TARGET))),
                    AccessMode.valueOf(requiredString(object, DecisionField.REQUIRED)),
                    Outcome.valueOf(requiredString(object, DecisionField.OUTCOME)),
                    optionalString(object, DecisionField.DECIDED_BY)
                            .map(URI::create).map(ResourceIdentifier::new),
                    new RequestId(requiredString(object, DecisionField.REQUEST_ID))));
        } catch (JsonException | IllegalArgumentException | DateTimeException e) {
            // JsonException: not JSON, or not an object. IllegalArgumentException covers a
            // missing or non-string member (below), an unknown enum name, a malformed URI
            // (URI.create) or identifier, a malformed request id, and a record that breaks
            // its own invariant. DateTimeException: a bad instant. All mean the same thing to
            // the caller: not a record.
            return Optional.empty();
        }
    }

    /** The field's string value; a missing, null or non-string member is a malformed record. */
    private static String requiredString(JsonObject object, DecisionField field) {
        return optionalString(object, field).orElseThrow(() -> new IllegalArgumentException(
                WacMessage.DECISION_FIELD_MISSING.format(field.key())));
    }

    /** The field's string value, or empty for a JSON {@code null}; a missing or non-string member is malformed. */
    private static Optional<String> optionalString(JsonObject object, DecisionField field) {
        JsonValue value = object.get(field.key());
        if (value == null) {
            throw new IllegalArgumentException(WacMessage.DECISION_FIELD_MISSING.format(field.key()));
        }
        if (value.isNull()) {
            return Optional.empty();
        }
        if (!value.isString()) {
            throw new IllegalArgumentException(WacMessage.DECISION_FIELD_NOT_A_STRING.format(field.key()));
        }
        return Optional.of(value.getAsString().value());
    }
}
