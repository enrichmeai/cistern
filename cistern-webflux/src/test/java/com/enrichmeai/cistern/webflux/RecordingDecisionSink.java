package com.enrichmeai.cistern.webflux;

import com.enrichmeai.cistern.wac.DecisionRecord;
import com.enrichmeai.cistern.wac.DecisionSink;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import reactor.core.publisher.Mono;

/**
 * A {@link DecisionSink} that remembers every record it was handed and passes each on to a
 * real sink, so a test can assert <em>what the filter recorded</em> — one record per request,
 * with these fields — while the receipts query still reads the real log the delegate wrote.
 *
 * <p>Observation only: the delegate decides whether the record is durable, and its answer is
 * the answer the filter sees.
 */
final class RecordingDecisionSink implements DecisionSink {

    private final DecisionSink delegate;
    private final List<DecisionRecord> records = new CopyOnWriteArrayList<>();

    RecordingDecisionSink(DecisionSink delegate) {
        this.delegate = delegate;
    }

    @Override
    public Mono<Void> record(DecisionRecord record) {
        records.add(record);
        return delegate.record(record);
    }

    /** Everything handed to the sink since the last {@link #clear()}, oldest first. */
    List<DecisionRecord> records() {
        return List.copyOf(records);
    }

    void clear() {
        records.clear();
    }
}
