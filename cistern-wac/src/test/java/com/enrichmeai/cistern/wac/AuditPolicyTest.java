package com.enrichmeai.cistern.wac;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.enrichmeai.cistern.core.Agent;
import com.enrichmeai.cistern.core.CisternException;
import com.enrichmeai.cistern.core.ResourceIdentifier;

import java.net.URI;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** What a lost receipt means to the request, under each policy (T5.9). */
class AuditPolicyTest {

    private static final DecisionRecord RECORD = new DecisionRecord(
            Instant.parse("2026-08-19T09:00:00Z"), Agent.ANONYMOUS,
            new ResourceIdentifier(URI.create("http://localhost:3737/notes/week")),
            AccessMode.READ, Outcome.DENIED_UNAUTHENTICATED, Optional.empty(), RequestId.generate());

    private static final DecisionSink FAILING =
            record -> Mono.error(new IllegalStateException("disk full (test)"));

    @Test
    @DisplayName("BEST_EFFORT: a failed record completes — the outcome stands")
    void bestEffortSwallows() {
        StepVerifier.create(AuditPolicy.BEST_EFFORT.guard(FAILING).record(RECORD)).verifyComplete();
    }

    @Test
    @DisplayName("REQUIRED: a failed record is ServiceUnavailable — the request fails closed, retryable")
    void requiredFailsClosed() {
        StepVerifier.create(AuditPolicy.REQUIRED.guard(FAILING).record(RECORD))
                .expectError(CisternException.ServiceUnavailable.class)
                .verify();
    }

    @Test
    @DisplayName("either policy passes a successful record straight through, exactly once")
    void successPassesThrough() {
        AtomicInteger calls = new AtomicInteger();
        DecisionSink counting = record -> Mono.fromRunnable(calls::incrementAndGet);

        StepVerifier.create(AuditPolicy.BEST_EFFORT.guard(counting).record(RECORD)).verifyComplete();
        StepVerifier.create(AuditPolicy.REQUIRED.guard(counting).record(RECORD)).verifyComplete();

        assertEquals(2, calls.get());
    }

    @Test
    @DisplayName("cistern.audit.required maps onto the two policies")
    void ofRequired() {
        assertSame(AuditPolicy.REQUIRED, AuditPolicy.of(true));
        assertSame(AuditPolicy.BEST_EFFORT, AuditPolicy.of(false));
    }
}
