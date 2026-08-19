package com.enrichmeai.cistern.wac;

import com.enrichmeai.cistern.core.Representation;
import com.enrichmeai.cistern.core.ResourceIdentifier;
import com.enrichmeai.cistern.core.StoredResource;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * The append-only JSON Lines decision log (T5.9): one {@link DecisionRecordJson} line per
 * record, appended to the {@link DecisionLog#fileFor(java.time.Instant) day file} the record's
 * own timestamp selects, written through the storage SPI.
 *
 * <h2>Append, on a store that cannot append</h2>
 * {@code ResourceStore} has {@code put} — create or replace, atomically — and no append.
 * Rather than widen the SPI (a contract-kit change every backend would have to pass, in a
 * ticket about receipts), v1 appends by <strong>read-modify-write</strong>: fetch the day
 * file, add the line, put the whole file back. Stated plainly, the cost is one rewrite of the
 * day's log per decision, which grows linearly through the day; on the file backend that is a
 * write-tmp-and-rename of a few hundred kilobytes by evening for a busy pod, and nothing for a
 * quiet one. If that ever shows on a latency graph, the fix is an {@code append} on the SPI or
 * an hourly partition — both local to this class and {@link DecisionLog}.
 *
 * <h2>Serialized, so overlapping requests cannot lose each other's lines</h2>
 * Read-modify-write is only correct if no two writes interleave: two requests that both read
 * the file, both add a line, and both put it back would keep one line and drop the other.
 * Every append therefore goes through one in-process queue drained by a single subscriber
 * ({@code concatMap}), so appends happen strictly one after another, on the store's own
 * scheduler, without a lock and without blocking the caller. This is the single-writer
 * assumption made concrete: one Cistern process owns one log; a second process writing the
 * same directory would interleave with this one, and nothing here can prevent that.
 *
 * <h2>Truthful completion</h2>
 * {@link #record} completes only when the store has acknowledged the put, and errors if it did
 * not. It does not complete on enqueue. That is what lets {@code cistern.audit.required} mean
 * what it says: a caller that waits on the returned {@code Mono} knows whether the receipt is
 * durable before it lets the request proceed. Whether to wait is the caller's choice; the sink
 * only refuses to lie about it.
 *
 * <p>Implements {@link AutoCloseable} so a container that owns it can drain the queue at
 * shutdown: pending appends complete, then the drain stops. A record offered after
 * {@link #close()} errors — the sink is gone, and saying so is better than silently dropping
 * the last receipts of a process's life.
 */
public final class JsonLinesDecisionSink implements DecisionSink, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(JsonLinesDecisionSink.class);

    /**
     * How long an emitter spins when the queue is momentarily contended by another emitter.
     * Contention is measured in nanoseconds — it is two threads offering to the same
     * single-consumer queue — so this bounds pathological cases without ever mattering in the
     * normal one.
     */
    private static final Duration EMIT_CONTENTION_LIMIT = Duration.ofSeconds(1);

    private static final byte[] NO_BYTES = new byte[0];

    private final DecisionLog decisionLog;
    private final Sinks.Many<Pending> queue = Sinks.many().unicast().onBackpressureBuffer();

    /** One record and the promise its caller is waiting on. */
    private record Pending(DecisionRecord record, Sinks.One<Void> done) {
    }

    public JsonLinesDecisionSink(DecisionLog decisionLog) {
        this.decisionLog = Objects.requireNonNull(decisionLog, "decisionLog");
        // The single consumer. concatMap, never flatMap: strictly one append at a time. The
        // subscription lives as long as the queue: it ends when close() completes the queue
        // and the last pending append has been written.
        queue.asFlux()
                .concatMap(pending -> append(pending.record())
                        .doOnSuccess(ignored -> pending.done().tryEmitEmpty())
                        .onErrorResume(failure -> {
                            // The failure belongs to this record's caller, not to the drain:
                            // report it there and keep draining, or one bad write would stop
                            // every receipt after it.
                            pending.done().tryEmitError(failure);
                            return Mono.empty();
                        }))
                .subscribe();
    }

    @Override
    public Mono<Void> record(DecisionRecord record) {
        Objects.requireNonNull(record, "record");
        return Mono.defer(() -> {
            Pending pending = new Pending(record, Sinks.one());
            Sinks.EmitResult offered = offer(pending);
            if (offered.isFailure()) {
                // The sink is closed, or the queue is not taking records. Said out loud:
                // Sinks.Many.emitNext would have dropped a record on a terminated sink and
                // returned normally, and a receipt that vanishes silently is the one failure
                // mode this class must not have.
                return Mono.error(new IllegalStateException(
                        WacMessage.DECISION_SINK_REFUSED.format(record.requestId(), offered)));
            }
            return pending.done().asMono();
        });
    }

    /**
     * Offer {@code pending} to the queue, spinning briefly through {@code FAIL_NON_SERIALIZED}
     * — two request threads emitting at the same instant — and returning any other result to
     * the caller. {@code tryEmitNext} rather than {@code emitNext}: the latter swallows a
     * terminated sink, and this class needs to see it.
     */
    private Sinks.EmitResult offer(Pending pending) {
        long deadline = System.nanoTime() + EMIT_CONTENTION_LIMIT.toNanos();
        Sinks.EmitResult result = queue.tryEmitNext(pending);
        while (result == Sinks.EmitResult.FAIL_NON_SERIALIZED && System.nanoTime() < deadline) {
            Thread.onSpinWait();
            result = queue.tryEmitNext(pending);
        }
        return result;
    }

    /**
     * Read the day file, add the line, write it back. Runs on the drain, so it is never
     * concurrent with itself.
     */
    private Mono<Void> append(DecisionRecord record) {
        // Mono.defer: anything that throws while building the append — a serialization
        // surprise, say — must become this record's error, not the drain's death.
        return Mono.defer(() -> {
            ResourceIdentifier file = decisionLog.fileFor(record.at());
            byte[] line = (DecisionRecordJson.toLine(record) + DecisionLog.LINE_SEPARATOR)
                    .getBytes(StandardCharsets.UTF_8);
            return decisionLog.store().get(file)
                    .map(StoredResource::representation)
                    .map(Representation::data)
                    .defaultIfEmpty(NO_BYTES)
                    .flatMap(existing -> decisionLog.store().put(
                            file, new Representation(DecisionLog.MEDIA_TYPE, concat(existing, line))))
                    .doOnError(failure -> log.warn(WacMessage.DECISION_APPEND_FAILED.format(
                            file.uri(), record.requestId()), failure))
                    .then();
        });
    }

    private static byte[] concat(byte[] head, byte[] tail) {
        byte[] joined = new byte[head.length + tail.length];
        System.arraycopy(head, 0, joined, 0, head.length);
        System.arraycopy(tail, 0, joined, head.length, tail.length);
        return joined;
    }

    /** Stop accepting records; pending appends still complete, then the drain ends. */
    @Override
    public void close() {
        queue.tryEmitComplete();
    }
}
