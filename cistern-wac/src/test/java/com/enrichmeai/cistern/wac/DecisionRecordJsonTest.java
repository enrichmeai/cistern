package com.enrichmeai.cistern.wac;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.enrichmeai.cistern.core.Agent;
import com.enrichmeai.cistern.core.ResourceIdentifier;

import java.net.URI;
import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The receipt's wire format: one line, every field, round-tripping exactly (T5.9). */
class DecisionRecordJsonTest {

    private static final Instant AT = Instant.parse("2026-08-19T09:12:03.421Z");
    private static final ResourceIdentifier TARGET =
            new ResourceIdentifier(URI.create("http://localhost:3737/notes/week"));
    private static final ResourceIdentifier ACL =
            new ResourceIdentifier(URI.create("http://localhost:3737/notes/.acl"));
    private static final Agent ALICE = Agent.of(URI.create("https://alice.example/profile/card#me"));
    private static final RequestId REQUEST = new RequestId("5c1f4e2a-9b1d-4c1e-8f7a-2b3c4d5e6f70");

    @Test
    @DisplayName("an allowed record round-trips, and names its policy")
    void allowedRoundTrips() {
        DecisionRecord record = new DecisionRecord(
                AT, ALICE, TARGET, AccessMode.READ, Outcome.ALLOWED, Optional.of(ACL), REQUEST, Optional.empty());

        String line = DecisionRecordJson.toLine(record);

        assertFalse(line.contains("\n"), "one record, one line: " + line);
        assertEquals(Optional.of(record), DecisionRecordJson.parse(line));
        for (DecisionField field : DecisionField.values()) {
            assertEquals(field.isAlwaysPresent(), line.contains("\"" + field.key() + "\""),
                    "every always-present field is on the line, and nothing else: " + field);
        }
    }

    @Test
    @DisplayName("an anonymous denial round-trips with null agent and null decidedBy")
    void anonymousDenialRoundTrips() {
        DecisionRecord record = new DecisionRecord(
                AT, Agent.ANONYMOUS, TARGET, AccessMode.WRITE, Outcome.DENIED_UNAUTHENTICATED,
                Optional.empty(), REQUEST, Optional.empty());

        String line = DecisionRecordJson.toLine(record);

        assertTrue(line.contains("\"" + DecisionField.AGENT.key() + "\":null"), line);
        assertTrue(line.contains("\"" + DecisionField.DECIDED_BY.key() + "\":null"), line);
        assertEquals(Optional.of(record), DecisionRecordJson.parse(line));
    }

    @Test
    @DisplayName("the exact wire shape is pinned — a format is a contract")
    void wireShapeIsPinned() {
        DecisionRecord record = new DecisionRecord(
                AT, ALICE, TARGET, AccessMode.CONTROL, Outcome.DENIED_FORBIDDEN, Optional.empty(), REQUEST, Optional.empty());

        assertEquals("{\"at\":\"2026-08-19T09:12:03.421Z\","
                        + "\"agent\":\"https://alice.example/profile/card#me\","
                        + "\"target\":\"http://localhost:3737/notes/week\","
                        + "\"required\":\"CONTROL\","
                        + "\"outcome\":\"DENIED_FORBIDDEN\","
                        + "\"decidedBy\":null,"
                        + "\"requestId\":\"5c1f4e2a-9b1d-4c1e-8f7a-2b3c4d5e6f70\"}",
                DecisionRecordJson.toLine(record));
    }

    // ---- the narrowing term (T6.5, AD-DEL-5) ----------------------------------------------

    @Test
    @DisplayName("a decision a delegation narrowed carries the term as one more member, last")
    void narrowedWireShapeIsPinned() {
        DecisionRecord record = new DecisionRecord(
                AT, ALICE, TARGET, AccessMode.WRITE, Outcome.DENIED_FORBIDDEN, Optional.empty(), REQUEST,
                Optional.of(DelegationTerm.CLIENT));

        String line = DecisionRecordJson.toLine(record);

        assertEquals("{\"at\":\"2026-08-19T09:12:03.421Z\","
                        + "\"agent\":\"https://alice.example/profile/card#me\","
                        + "\"target\":\"http://localhost:3737/notes/week\","
                        + "\"required\":\"WRITE\","
                        + "\"outcome\":\"DENIED_FORBIDDEN\","
                        + "\"decidedBy\":null,"
                        + "\"requestId\":\"5c1f4e2a-9b1d-4c1e-8f7a-2b3c4d5e6f70\","
                        + "\"narrowedBy\":\"CLIENT\"}",
                line);
        assertEquals(Optional.of(record), DecisionRecordJson.parse(line));
    }

    /** AD-16: with no delegation the line is the pre-delegation line — and such a line still parses. */
    @Test
    @DisplayName("a line written before the term existed parses, with no term")
    void preDelegationLineParses() {
        String line = "{\"at\":\"2026-08-19T09:12:03Z\",\"agent\":null,\"target\":\"http://x/y\","
                + "\"required\":\"READ\",\"outcome\":\"DENIED_UNAUTHENTICATED\",\"decidedBy\":null,\"requestId\":\"r\"}";

        DecisionRecord record = DecisionRecordJson.parse(line).orElseThrow();

        assertTrue(record.narrowedBy().isEmpty());
        assertEquals(line, DecisionRecordJson.toLine(record), "and writes back byte-identical");
    }

    @Test
    @DisplayName("an explicit null term reads as no term")
    void nullTermIsNoTerm() {
        String line = "{\"at\":\"2026-08-19T09:12:03Z\",\"agent\":null,\"target\":\"http://x/y\","
                + "\"required\":\"READ\",\"outcome\":\"ALLOWED\",\"decidedBy\":\"http://x/.acl\",\"requestId\":\"r\","
                + "\"narrowedBy\":null}";

        assertTrue(DecisionRecordJson.parse(line).orElseThrow().narrowedBy().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "",
        "not json",
        "[]",
        "{}",
        "{\"at\":\"2026-08-19T09:12:03Z\"}",
        // outcome misspelt
        "{\"at\":\"2026-08-19T09:12:03Z\",\"agent\":null,\"target\":\"http://x/y\",\"required\":\"READ\",\"outcome\":\"ALLOWEDD\",\"decidedBy\":null,\"requestId\":\"r\"}",
        // agent is a number
        "{\"at\":\"2026-08-19T09:12:03Z\",\"agent\":42,\"target\":\"http://x/y\",\"required\":\"READ\",\"outcome\":\"ALLOWED\",\"decidedBy\":null,\"requestId\":\"r\"}",
        // target is relative
        "{\"at\":\"2026-08-19T09:12:03Z\",\"agent\":null,\"target\":\"/y\",\"required\":\"READ\",\"outcome\":\"ALLOWED\",\"decidedBy\":null,\"requestId\":\"r\"}",
        // bad instant
        "{\"at\":\"yesterday\",\"agent\":null,\"target\":\"http://x/y\",\"required\":\"READ\",\"outcome\":\"ALLOWED\",\"decidedBy\":null,\"requestId\":\"r\"}",
        // a denial that names a policy — an invariant the record itself refuses
        "{\"at\":\"2026-08-19T09:12:03Z\",\"agent\":null,\"target\":\"http://x/y\",\"required\":\"READ\",\"outcome\":\"DENIED_UNAUTHENTICATED\",\"decidedBy\":\"http://x/.acl\",\"requestId\":\"r\"}",
        // a narrowing term this server does not know
        "{\"at\":\"2026-08-19T09:12:03Z\",\"agent\":null,\"target\":\"http://x/y\",\"required\":\"READ\",\"outcome\":\"ALLOWED\",\"decidedBy\":\"http://x/.acl\",\"requestId\":\"r\",\"narrowedBy\":\"PURPOSE\"}",
        // a narrowing term that is not a string
        "{\"at\":\"2026-08-19T09:12:03Z\",\"agent\":null,\"target\":\"http://x/y\",\"required\":\"READ\",\"outcome\":\"ALLOWED\",\"decidedBy\":\"http://x/.acl\",\"requestId\":\"r\",\"narrowedBy\":1}",
    })
    @DisplayName("a line that is not a record parses to empty, never throws")
    void malformedIsEmpty(String line) {
        assertTrue(DecisionRecordJson.parse(line).isEmpty(), line);
    }
}
