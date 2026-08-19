package com.enrichmeai.cistern.wac;

import reactor.core.publisher.Mono;

/**
 * Where decisions go (T5.9). The enforcement point hands every {@link DecisionRecord} — allow
 * and deny alike — to exactly one of these, and what happens to it from there is the sink's
 * business: appended to a file, kept in memory for a test, shipped somewhere else.
 *
 * <p>The contract is small on purpose:
 *
 * <ul>
 *   <li>{@link #record} completes when the record is <em>durably</em> recorded, or errors if
 *       it could not be. It never completes early on a best-effort basis: whether a failed
 *       write should fail the request is the caller's decision
 *       ({@code cistern.audit.required}), and the caller can only take it if the sink tells the
 *       truth.</li>
 *   <li>Non-blocking. A sink that touches disk does so on a scheduler fit for it, never on the
 *       thread that subscribed.</li>
 *   <li>Safe for concurrent callers. Requests overlap; the sink serializes what needs
 *       serializing.</li>
 * </ul>
 */
@FunctionalInterface
public interface DecisionSink {

    /**
     * Record {@code record}.
     *
     * @return completes once the record is recorded; errors if it was not
     */
    Mono<Void> record(DecisionRecord record);
}
