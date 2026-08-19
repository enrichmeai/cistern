package com.enrichmeai.cistern.wac;

import com.enrichmeai.cistern.core.ResourceIdentifier;

import java.net.URI;
import java.time.Instant;

import reactor.core.publisher.Flux;

/**
 * Reading the decision log back (T5.9): the two questions an owner asks of it.
 *
 * <p>Both take a half-open interval {@code [from, to)} — a receipt taken exactly at
 * {@code from} is included, one taken exactly at {@code to} is not — so that consecutive
 * windows partition the log without overlap or gap. Records come back in the order they were
 * taken.
 *
 * <p>Deliberately not the enforcement point's concern: who may ask these questions is decided
 * by the same engine as everything else ({@link RequiredAccess#forReceipts} — Control on the
 * resource), before a query is ever run.
 */
public interface DecisionQuery {

    /**
     * Every decision about exactly {@code target} — not its children — taken in
     * {@code [from, to)}.
     */
    Flux<DecisionRecord> forResource(ResourceIdentifier target, Instant from, Instant to);

    /** Every decision in {@code [from, to)} in which the agent was {@code webId}, on any target. */
    Flux<DecisionRecord> forAgent(URI webId, Instant from, Instant to);
}
