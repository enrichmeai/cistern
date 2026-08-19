package com.enrichmeai.cistern.wac;

import com.enrichmeai.cistern.core.ResourceIdentifier;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Reference {@link DecisionSink} and {@link DecisionQuery} for tests (test scope, shipped in
 * the cistern-wac test-jar): every record kept in memory, in the order it arrived, queryable
 * by the same two questions the JSON Lines log answers.
 *
 * <p>Exists so a test above this module can assert <em>what was recorded</em> — exactly one
 * record per request through the filter, say — without a filesystem, and so the query surface
 * can be exercised against known records. It is not a fake of the file log's behaviour: it has
 * no day files and never fails. Tests of the file log itself use the real
 * {@link JsonLinesDecisionSink} over an in-memory {@code ResourceStore}.
 */
public final class InMemoryDecisionSink implements DecisionSink, DecisionQuery {

    private final List<DecisionRecord> records = new CopyOnWriteArrayList<>();

    @Override
    public Mono<Void> record(DecisionRecord record) {
        return Mono.fromRunnable(() -> records.add(record));
    }

    @Override
    public Flux<DecisionRecord> forResource(ResourceIdentifier target, Instant from, Instant to) {
        return Flux.fromIterable(records)
                .filter(record -> record.target().equals(target))
                .filter(record -> within(record, from, to));
    }

    @Override
    public Flux<DecisionRecord> forAgent(URI webId, Instant from, Instant to) {
        return Flux.fromIterable(records)
                .filter(record -> record.agent().webId().filter(webId::equals).isPresent())
                .filter(record -> within(record, from, to));
    }

    /** Everything recorded so far, oldest first. */
    public List<DecisionRecord> records() {
        return List.copyOf(records);
    }

    /** Forget everything, so a test starts from nothing. */
    public void clear() {
        records.clear();
    }

    private static boolean within(DecisionRecord record, Instant from, Instant to) {
        return !record.at().isBefore(from) && record.at().isBefore(to);
    }
}
