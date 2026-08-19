package com.enrichmeai.cistern.wac;

import com.enrichmeai.cistern.core.CisternException;

import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * What a failure to record a decision means to the request that produced it (T5.9). A closed
 * set of two, so an enum (ground rule 7), and the one place the meaning of
 * {@code cistern.audit.required} is spelled out.
 *
 * <p>The policy is applied by wrapping a {@link DecisionSink} — {@link #guard} — so the
 * enforcement point sees a sink whose failures already mean what the deployment said they
 * should, and contains no error handling of its own. Neither policy touches the authorization
 * outcome: a decision is what {@code AccessControl} said it was; the only question is whether
 * it is <em>acted on</em> when it cannot be accounted for.
 */
public enum AuditPolicy {

    /**
     * Availability over completeness of the trail: a receipt that cannot be written is logged
     * at WARN and the request proceeds as decided. The default.
     */
    BEST_EFFORT {
        @Override
        Mono<Void> outcomeOf(Mono<Void> recorded, DecisionRecord record) {
            return recorded
                    .doOnError(failure -> log.warn(
                            WacMessage.DECISION_NOT_RECORDED_OUTCOME_STANDS.format(
                                    record.requestId(), record.outcome(), record.target().uri()),
                            failure))
                    .onErrorComplete();
        }
    },

    /**
     * No decision is acted on that cannot be accounted for: a receipt that cannot be written
     * fails the request closed with {@link CisternException.ServiceUnavailable} — the same
     * request may succeed later, which is what 503 says and 403 does not.
     */
    REQUIRED {
        @Override
        Mono<Void> outcomeOf(Mono<Void> recorded, DecisionRecord record) {
            return recorded
                    .doOnError(failure -> log.warn(
                            WacMessage.DECISION_NOT_RECORDED_FAILED_CLOSED.format(
                                    record.requestId(), record.outcome(), record.target().uri()),
                            failure))
                    .onErrorMap(failure -> new CisternException.ServiceUnavailable(
                            WacMessage.DECISION_NOT_RECORDED.format(record.requestId())));
        }
    };

    private static final Logger log = LoggerFactory.getLogger(AuditPolicy.class);

    /** The policy {@code cistern.audit.required} selects. */
    public static AuditPolicy of(boolean required) {
        return required ? REQUIRED : BEST_EFFORT;
    }

    /** {@code delegate}, with this policy applied to every failure it reports. */
    public DecisionSink guard(DecisionSink delegate) {
        Objects.requireNonNull(delegate, "delegate");
        return record -> outcomeOf(delegate.record(record), record);
    }

    /** What the caller sees of {@code recorded}: completion, or the failure this policy turns it into. */
    abstract Mono<Void> outcomeOf(Mono<Void> recorded, DecisionRecord record);
}
