package com.enrichmeai.cistern.wac;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.enrichmeai.cistern.core.Agent;
import com.enrichmeai.cistern.core.CisternException;
import com.enrichmeai.cistern.core.InMemoryResourceStore;
import com.enrichmeai.cistern.core.Representation;
import com.enrichmeai.cistern.core.ResourceIdentifier;
import com.enrichmeai.cistern.core.ResourceStore;
import com.enrichmeai.cistern.core.StoredResource;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

/**
 * The JSON Lines decision log — sink and query together, over the reference in-memory store
 * (T5.9). The store is the same one every backend is checked against, so what these tests
 * observe about day files, appends and scans is what a file or object backend will do.
 */
class JsonLinesDecisionLogTest {

    private static final Instant MONDAY_NOON = Instant.parse("2026-08-17T12:00:00Z");
    private static final Instant TUESDAY_NOON = Instant.parse("2026-08-18T12:00:00Z");
    private static final Instant WEDNESDAY_NOON = Instant.parse("2026-08-19T12:00:00Z");
    private static final Instant FAR_PAST = Instant.parse("2000-01-01T00:00:00Z");
    private static final Instant FAR_FUTURE = Instant.parse("2100-01-01T00:00:00Z");

    private static final ResourceIdentifier NOTE =
            new ResourceIdentifier(URI.create("http://localhost:3737/notes/week"));
    private static final ResourceIdentifier MATTER =
            new ResourceIdentifier(URI.create("http://localhost:3737/private/matter"));
    private static final ResourceIdentifier NOTES_ACL =
            new ResourceIdentifier(URI.create("http://localhost:3737/notes/.acl"));
    private static final Agent ALICE = Agent.of(URI.create("https://alice.example/profile/card#me"));
    private static final Agent BOB = Agent.of(URI.create("https://bob.example/profile/card#me"));

    private InMemoryResourceStore store;
    private DecisionLog log;
    private JsonLinesDecisionSink sink;
    private JsonLinesDecisionQuery query;

    @BeforeEach
    void setUp() {
        store = new InMemoryResourceStore();
        log = DecisionLog.in(store);
        sink = new JsonLinesDecisionSink(log);
        query = new JsonLinesDecisionQuery(log);
    }

    @AfterEach
    void tearDown() {
        sink.close();
    }

    private static DecisionRecord allowed(Instant at, Agent agent, ResourceIdentifier target) {
        return new DecisionRecord(at, agent, target, AccessMode.READ, Outcome.ALLOWED,
                Optional.of(NOTES_ACL), RequestId.generate());
    }

    private static DecisionRecord denied(Instant at, Agent agent, ResourceIdentifier target) {
        return new DecisionRecord(at, agent, target, AccessMode.WRITE,
                agent.isAuthenticated() ? Outcome.DENIED_FORBIDDEN : Outcome.DENIED_UNAUTHENTICATED,
                Optional.empty(), RequestId.generate());
    }

    private void record(DecisionRecord... records) {
        for (DecisionRecord record : records) {
            StepVerifier.create(sink.record(record)).verifyComplete();
        }
    }

    private String contentOf(ResourceIdentifier file) {
        StoredResource stored = store.get(file).block();
        return stored == null ? null : new String(stored.representation().data(), StandardCharsets.UTF_8);
    }

    // ---- layout -----------------------------------------------------------------------

    @Nested
    @DisplayName("Layout: one JSON Lines file per UTC day under decisions/")
    class Layout {

        @Test
        @DisplayName("records taken on the same day land in the same file, one line each, in order")
        void sameDaySameFile() {
            DecisionRecord first = allowed(MONDAY_NOON, ALICE, NOTE);
            DecisionRecord second = denied(MONDAY_NOON.plusSeconds(1), Agent.ANONYMOUS, NOTE);
            record(first, second);

            ResourceIdentifier file = log.fileFor(LocalDate.of(2026, 8, 17));
            assertEquals(new ResourceIdentifier(URI.create("cistern-audit:///decisions/2026-08-17.jsonl")), file);
            String content = contentOf(file);
            assertEquals(DecisionRecordJson.toLine(first) + "\n" + DecisionRecordJson.toLine(second) + "\n",
                    content);
        }

        @Test
        @DisplayName("the day is the record's own timestamp, in UTC — not the wall clock at write time")
        void dayIsTheRecordsOwn() {
            // 23:59:59 on Monday and 00:00:00 on Tuesday, one second apart, two files.
            record(allowed(Instant.parse("2026-08-17T23:59:59Z"), ALICE, NOTE),
                    allowed(Instant.parse("2026-08-18T00:00:00Z"), ALICE, NOTE));

            assertTrue(store.exists(log.fileFor(LocalDate.of(2026, 8, 17))).block());
            assertTrue(store.exists(log.fileFor(LocalDate.of(2026, 8, 18))).block());
        }

        @Test
        @DisplayName("day files are typed as newline-delimited JSON")
        void dayFileMediaType() {
            record(allowed(MONDAY_NOON, ALICE, NOTE));

            assertEquals(DecisionLog.MEDIA_TYPE,
                    store.get(log.fileFor(MONDAY_NOON)).block().representation().contentType());
        }

        @Test
        @DisplayName("the day file's name round-trips through the log's naming")
        void dayNameRoundTrips() {
            LocalDate day = LocalDate.of(2026, 8, 19);
            assertEquals(Optional.of(day), log.dayOf(log.fileFor(day)));
            assertTrue(log.dayOf(new ResourceIdentifier(
                    URI.create("cistern-audit:///decisions/README.txt"))).isEmpty());
            assertTrue(log.dayOf(new ResourceIdentifier(
                    URI.create("cistern-audit:///decisions/2026-13-99.jsonl"))).isEmpty());
        }

        @Test
        @DisplayName("the log root must be a container")
        void rootMustBeAContainer() {
            assertThrows(IllegalArgumentException.class, () -> new DecisionLog(
                    store, new ResourceIdentifier(URI.create("cistern-audit:///audit"))));
        }
    }

    // ---- append semantics -------------------------------------------------------------

    @Nested
    @DisplayName("Append: serialized, truthful, isolated")
    class Append {

        @Test
        @DisplayName("overlapping appends lose nothing — every one of 200 concurrent records is on disk")
        void concurrentAppendsAreSerialized() {
            List<DecisionRecord> records = IntStream.range(0, 200)
                    .mapToObj(i -> allowed(MONDAY_NOON.plusMillis(i), ALICE, NOTE))
                    .toList();

            // Subscribe to all of them at once, from many threads, and wait for all.
            StepVerifier.create(Flux.fromIterable(records)
                            .flatMap(record -> sink.record(record).subscribeOn(Schedulers.parallel()), 64))
                    .verifyComplete();

            String content = contentOf(log.fileFor(MONDAY_NOON));
            assertEquals(200, content.split("\n").length, "no line was lost to a lost update");
            for (DecisionRecord record : records) {
                assertTrue(content.contains(DecisionRecordJson.toLine(record)),
                        "missing: " + record.requestId());
            }
        }

        @Test
        @DisplayName("a store failure surfaces to THAT record's caller — and the next append still works")
        void storeFailureIsReportedAndIsolated() {
            AtomicBoolean fail = new AtomicBoolean(true);
            ResourceStore flaky = new ResourceStore() {
                @Override public Mono<StoredResource> get(ResourceIdentifier id) { return store.get(id); }
                @Override public Mono<StoredResource> put(ResourceIdentifier id, Representation r) {
                    return fail.get()
                            ? Mono.error(new CisternException.Conflict("disk full, or so we pretend"))
                            : store.put(id, r);
                }
                @Override public Mono<Void> delete(ResourceIdentifier id) { return store.delete(id); }
                @Override public Flux<ResourceIdentifier> children(ResourceIdentifier c) { return store.children(c); }
                @Override public Mono<Boolean> exists(ResourceIdentifier id) { return store.exists(id); }
            };
            try (JsonLinesDecisionSink flakySink = new JsonLinesDecisionSink(DecisionLog.in(flaky))) {
                StepVerifier.create(flakySink.record(allowed(MONDAY_NOON, ALICE, NOTE)))
                        .expectError(CisternException.Conflict.class)
                        .verify();

                fail.set(false);
                DecisionRecord next = allowed(MONDAY_NOON.plusSeconds(1), ALICE, NOTE);
                StepVerifier.create(flakySink.record(next)).verifyComplete();
                assertEquals(DecisionRecordJson.toLine(next) + "\n", contentOf(log.fileFor(MONDAY_NOON)));
            }
        }

        @Test
        @DisplayName("record() does not complete until the store has the bytes")
        void completionIsDurable() {
            DecisionRecord record = allowed(MONDAY_NOON, ALICE, NOTE);

            StepVerifier.create(sink.record(record)
                            .then(Mono.fromSupplier(() -> contentOf(log.fileFor(MONDAY_NOON)))))
                    .expectNext(DecisionRecordJson.toLine(record) + "\n")
                    .verifyComplete();
        }

        @Test
        @DisplayName("after close(), a record is refused rather than silently dropped")
        void closedSinkRefuses() {
            sink.close();

            StepVerifier.create(sink.record(allowed(MONDAY_NOON, ALICE, NOTE)))
                    .expectError()
                    .verify();
        }
    }

    // ---- query ------------------------------------------------------------------------

    @Nested
    @DisplayName("Query: by resource, by agent, within [from, to), in order")
    class Query {

        private DecisionRecord mondayNote;
        private DecisionRecord mondayMatter;
        private DecisionRecord tuesdayNoteByBob;
        private DecisionRecord tuesdayNoteAnonymous;
        private DecisionRecord wednesdayNote;

        @BeforeEach
        void seed() {
            mondayNote = allowed(MONDAY_NOON, ALICE, NOTE);
            mondayMatter = allowed(MONDAY_NOON.plusSeconds(1), ALICE, MATTER);
            tuesdayNoteByBob = denied(TUESDAY_NOON, BOB, NOTE);
            tuesdayNoteAnonymous = denied(TUESDAY_NOON.plusSeconds(1), Agent.ANONYMOUS, NOTE);
            wednesdayNote = allowed(WEDNESDAY_NOON, ALICE, NOTE);
            record(mondayNote, mondayMatter, tuesdayNoteByBob, tuesdayNoteAnonymous, wednesdayNote);
        }

        @Test
        @DisplayName("forResource returns exactly that resource's records, oldest first, across days")
        void forResource() {
            StepVerifier.create(query.forResource(NOTE, FAR_PAST, FAR_FUTURE))
                    .expectNext(mondayNote, tuesdayNoteByBob, tuesdayNoteAnonymous, wednesdayNote)
                    .verifyComplete();
        }

        @Test
        @DisplayName("forResource is exact — a child or a sibling is another resource")
        void forResourceIsExact() {
            StepVerifier.create(query.forResource(MATTER, FAR_PAST, FAR_FUTURE))
                    .expectNext(mondayMatter)
                    .verifyComplete();
            StepVerifier.create(query.forResource(
                            new ResourceIdentifier(URI.create("http://localhost:3737/notes/")), FAR_PAST, FAR_FUTURE))
                    .verifyComplete();
        }

        @Test
        @DisplayName("forAgent returns that agent's records on any resource — and never the anonymous ones")
        void forAgent() {
            StepVerifier.create(query.forAgent(ALICE.webId().orElseThrow(), FAR_PAST, FAR_FUTURE))
                    .expectNext(mondayNote, mondayMatter, wednesdayNote)
                    .verifyComplete();
            StepVerifier.create(query.forAgent(BOB.webId().orElseThrow(), FAR_PAST, FAR_FUTURE))
                    .expectNext(tuesdayNoteByBob)
                    .verifyComplete();
        }

        @Test
        @DisplayName("[from, to) is half-open: from is in, to is out")
        void intervalIsHalfOpen() {
            StepVerifier.create(query.forResource(NOTE, TUESDAY_NOON, WEDNESDAY_NOON))
                    .expectNext(tuesdayNoteByBob, tuesdayNoteAnonymous)
                    .verifyComplete();
            StepVerifier.create(query.forResource(NOTE, TUESDAY_NOON, TUESDAY_NOON))
                    .verifyComplete();
        }

        @Test
        @DisplayName("a damaged line is skipped, and the good lines around it are still returned")
        void damagedLineIsSkipped() {
            ResourceIdentifier tuesday = log.fileFor(TUESDAY_NOON);
            String content = contentOf(tuesday);
            String[] lines = content.split("\n");
            String damaged = lines[0] + "\n" + "{this is not a record\n" + lines[1] + "\n";
            store.put(tuesday, new Representation(DecisionLog.MEDIA_TYPE, damaged.getBytes(StandardCharsets.UTF_8)))
                    .block();

            StepVerifier.create(query.forResource(NOTE, TUESDAY_NOON, WEDNESDAY_NOON))
                    .expectNext(tuesdayNoteByBob, tuesdayNoteAnonymous)
                    .verifyComplete();
        }

        @Test
        @DisplayName("an empty log answers with nothing, not an error")
        void emptyLog() {
            JsonLinesDecisionQuery emptyQuery = new JsonLinesDecisionQuery(DecisionLog.in(new InMemoryResourceStore()));

            StepVerifier.create(emptyQuery.forResource(NOTE, FAR_PAST, FAR_FUTURE)).verifyComplete();
            StepVerifier.create(emptyQuery.forAgent(ALICE.webId().orElseThrow(), FAR_PAST, FAR_FUTURE)).verifyComplete();
        }
    }
}
