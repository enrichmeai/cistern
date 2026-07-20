package com.enrichmeai.cistern.webflux;

import java.util.Objects;
import java.util.Optional;

/**
 * The outcome of evaluating a request's preconditions, together with the field that decided
 * it.
 *
 * <p>The field is carried because RFC 9457 §3.1.4 asks a problem {@code detail} to explain
 * <em>this</em> occurrence: "the If-Match precondition failed" tells a client which header to
 * fix and which to keep, where a bare 412 leaves it guessing between the two it may have sent.
 * It is also what keeps {@link PreconditionOutcome} a plain enum, as the ticket requires,
 * instead of growing a constant per field.
 *
 * @param outcome   what happens to the request
 * @param decidedBy the field that produced a non-{@code PROCEED} outcome; empty exactly when
 *                  the outcome is {@link PreconditionOutcome#PROCEED}, because a request that
 *                  proceeds was not decided by any one field — it satisfied all of them
 */
record PreconditionResult(PreconditionOutcome outcome, Optional<ConditionalHeader> decidedBy) {

    PreconditionResult {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(decidedBy, "decidedBy");
        if (decidedBy.isEmpty() != (outcome == PreconditionOutcome.PROCEED)) {
            throw new IllegalArgumentException(
                    "A precondition that changed the response names the field that did so: " + outcome);
        }
    }

    /** Every precondition held (or none was sent). */
    static PreconditionResult proceed() {
        return new PreconditionResult(PreconditionOutcome.PROCEED, Optional.empty());
    }

    /** {@code header} failed on a request that must not proceed → 412. */
    static PreconditionResult failed(ConditionalHeader header) {
        return new PreconditionResult(PreconditionOutcome.PRECONDITION_FAILED, Optional.of(header));
    }

    /** {@code header} failed on a {@code GET}/{@code HEAD} → 304. */
    static PreconditionResult notModified(ConditionalHeader header) {
        return new PreconditionResult(PreconditionOutcome.NOT_MODIFIED, Optional.of(header));
    }

    /**
     * The field name for a problem detail. Only ever called on a non-{@code PROCEED} result,
     * whose invariant guarantees the field is there.
     */
    String decidedByFieldName() {
        return decidedBy.orElseThrow(IllegalStateException::new).fieldName();
    }
}
