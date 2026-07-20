package com.enrichmeai.cistern.webflux;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * RFC 9110 §13.2.2's evaluation order, exercised directly.
 *
 * <p>Precedence is the part of conditional requests that is easiest to get subtly wrong and
 * hardest to see over the wire: a request carrying two conditionals has one answer, and an
 * implementation that evaluates them in the other order usually still returns <em>a</em>
 * plausible status. So the interesting cases here are the combinations, and each one is
 * arranged so that the two fields disagree — whichever field the implementation consults first
 * decides the assertion.
 */
class PreconditionEvaluatorTest {

    private static final EntityTag CURRENT = new EntityTag("current");

    private static final Instant MODIFIED_AT = Instant.parse("2026-07-19T10:00:00Z");
    private static final Instant BEFORE = MODIFIED_AT.minus(Duration.ofDays(1));
    private static final Instant AFTER = MODIFIED_AT.plus(Duration.ofDays(1));

    private static final TargetState PRESENT =
            new TargetState.Present(Set.of(CURRENT), MODIFIED_AT);
    private static final TargetState ABSENT = new TargetState.Absent();

    private final PreconditionEvaluator evaluator = new PreconditionEvaluator();

    // ---------------------------------------------------------------- fixtures

    private static EntityTagCondition tag(String fieldValue) {
        return EntityTagCondition.parse(List.of(fieldValue));
    }

    private static EntityTagCondition none() {
        return EntityTagCondition.parse(List.of());
    }

    private static ConditionalRequest conditions(EntityTagCondition ifMatch,
                                                 Instant ifUnmodifiedSince,
                                                 EntityTagCondition ifNoneMatch,
                                                 Instant ifModifiedSince) {
        return new ConditionalRequest(ifMatch, Optional.ofNullable(ifUnmodifiedSince),
                ifNoneMatch, Optional.ofNullable(ifModifiedSince));
    }

    private PreconditionResult evaluate(HttpMethod method, TargetState state,
                                        ConditionalRequest conditions) {
        return evaluator.evaluate(conditions, method, state);
    }

    private static void assertOutcome(PreconditionOutcome expectedOutcome,
                                      ConditionalHeader expectedField, PreconditionResult actual) {
        assertEquals(expectedOutcome, actual.outcome());
        assertEquals(Optional.ofNullable(expectedField), actual.decidedBy());
    }

    // ---------------------------------------------------------------- steps 1 and 2

    @Nested
    @DisplayName("step 1: If-Match")
    class Step1 {

        @Test
        void aSatisfiedIfMatchProceeds() {
            assertOutcome(PreconditionOutcome.PROCEED, null,
                    evaluate(HttpMethod.PUT, PRESENT,
                            conditions(tag("\"current\""), null, none(), null)));
        }

        @ParameterizedTest
        @ValueSource(strings = {"GET", "HEAD", "PUT", "DELETE"})
        void aFailedIfMatchIs412ForEveryMethodIncludingReads(String methodName) {
            HttpMethod method = HttpMethod.valueOf(methodName);
            // §13.2.2 step 1 has no method qualification, and §13.1.1 explicitly allows a
            // client to make a GET conditional on If-Match to prefer a 412.
            assertOutcome(PreconditionOutcome.PRECONDITION_FAILED, ConditionalHeader.IF_MATCH,
                    evaluate(method, PRESENT, conditions(tag("\"stale\""), null, none(), null)));
        }

        @Test
        void ifMatchOnAnAbsentTargetFails() {
            assertOutcome(PreconditionOutcome.PRECONDITION_FAILED, ConditionalHeader.IF_MATCH,
                    evaluate(HttpMethod.PUT, ABSENT, conditions(tag("*"), null, none(), null)));
        }
    }

    @Nested
    @DisplayName("step 2: If-Unmodified-Since")
    class Step2 {

        @Test
        void aDateBeforeTheLastModificationFails() {
            assertOutcome(PreconditionOutcome.PRECONDITION_FAILED,
                    ConditionalHeader.IF_UNMODIFIED_SINCE,
                    evaluate(HttpMethod.PUT, PRESENT, conditions(none(), BEFORE, none(), null)));
        }

        @Test
        void theResourcesOwnDateSatisfiesItBecauseTheComparisonIsInclusive() {
            assertOutcome(PreconditionOutcome.PROCEED, null,
                    evaluate(HttpMethod.PUT, PRESENT,
                            conditions(none(), MODIFIED_AT, none(), null)));
        }

        @Test
        void itIsIgnoredWhenIfMatchIsPresent() {
            // §13.1.4: "A recipient MUST ignore If-Unmodified-Since if the request contains an
            // If-Match header field." The two disagree here, so only precedence can decide.
            assertOutcome(PreconditionOutcome.PROCEED, null,
                    evaluate(HttpMethod.PUT, PRESENT,
                            conditions(tag("\"current\""), BEFORE, none(), null)));
        }

        @Test
        void aFailingIfMatchStillWinsOverASatisfiedIfUnmodifiedSince() {
            assertOutcome(PreconditionOutcome.PRECONDITION_FAILED, ConditionalHeader.IF_MATCH,
                    evaluate(HttpMethod.PUT, PRESENT,
                            conditions(tag("\"stale\""), AFTER, none(), null)));
        }

        @Test
        void itIsIgnoredWhenTheTargetHasNoModificationDate() {
            // §13.1.4: "A recipient MUST ignore the If-Unmodified-Since header field if the
            // resource does not have a modification date available."
            assertOutcome(PreconditionOutcome.PROCEED, null,
                    evaluate(HttpMethod.PUT, ABSENT, conditions(none(), BEFORE, none(), null)));
        }
    }

    // ---------------------------------------------------------------- steps 3 and 4

    @Nested
    @DisplayName("step 3: If-None-Match")
    class Step3 {

        @ParameterizedTest
        @ValueSource(strings = {"GET", "HEAD"})
        void aMatchIs304ForAReadMethod(String methodName) {
            HttpMethod method = HttpMethod.valueOf(methodName);
            assertOutcome(PreconditionOutcome.NOT_MODIFIED, ConditionalHeader.IF_NONE_MATCH,
                    evaluate(method, PRESENT,
                            conditions(none(), null, tag("\"current\""), null)));
        }

        @ParameterizedTest
        @ValueSource(strings = {"PUT", "DELETE", "POST", "PATCH"})
        void aMatchIs412ForEveryOtherMethod(String methodName) {
            HttpMethod method = HttpMethod.valueOf(methodName);
            assertOutcome(PreconditionOutcome.PRECONDITION_FAILED,
                    ConditionalHeader.IF_NONE_MATCH,
                    evaluate(method, PRESENT,
                            conditions(none(), null, tag("\"current\""), null)));
        }

        @Test
        void wildcardOnAnAbsentTargetProceedsWhichIsTheCreateOnlyGuard() {
            assertOutcome(PreconditionOutcome.PROCEED, null,
                    evaluate(HttpMethod.PUT, ABSENT, conditions(none(), null, tag("*"), null)));
        }

        @Test
        void wildcardOnAPresentTargetIs412() {
            assertOutcome(PreconditionOutcome.PRECONDITION_FAILED,
                    ConditionalHeader.IF_NONE_MATCH,
                    evaluate(HttpMethod.PUT, PRESENT, conditions(none(), null, tag("*"), null)));
        }

        @Test
        void aSatisfiedIfMatchDoesNotShortCircuitIfNoneMatch() {
            // §13.2.2 step 1: "if true, continue to step 3" — not "return". Both fields hold
            // here in opposite directions, so a short-circuiting implementation answers 200.
            assertOutcome(PreconditionOutcome.NOT_MODIFIED, ConditionalHeader.IF_NONE_MATCH,
                    evaluate(HttpMethod.GET, PRESENT,
                            conditions(tag("\"current\""), null, tag("\"current\""), null)));
        }

        @Test
        void aFailingIfMatchIsEvaluatedBeforeAMatchingIfNoneMatch() {
            // The order test in the other direction: step 1 fails, so step 3's 304 is never
            // reached. Answering 304 here would tell a client its copy is current when the
            // server has just told it the opposite.
            assertOutcome(PreconditionOutcome.PRECONDITION_FAILED, ConditionalHeader.IF_MATCH,
                    evaluate(HttpMethod.GET, PRESENT,
                            conditions(tag("\"stale\""), null, tag("\"current\""), null)));
        }
    }

    @Nested
    @DisplayName("step 4: If-Modified-Since")
    class Step4 {

        @Test
        void aDateAtOrAfterTheLastModificationIs304() {
            assertOutcome(PreconditionOutcome.NOT_MODIFIED, ConditionalHeader.IF_MODIFIED_SINCE,
                    evaluate(HttpMethod.GET, PRESENT,
                            conditions(none(), null, none(), MODIFIED_AT)));
        }

        @Test
        void anOlderDateProceeds() {
            assertOutcome(PreconditionOutcome.PROCEED, null,
                    evaluate(HttpMethod.GET, PRESENT, conditions(none(), null, none(), BEFORE)));
        }

        @Test
        void itIsIgnoredWhenIfNoneMatchIsPresent() {
            // §13.1.3: "A recipient MUST ignore If-Modified-Since if the request contains an
            // If-None-Match header field." The two disagree, so precedence decides.
            assertOutcome(PreconditionOutcome.PROCEED, null,
                    evaluate(HttpMethod.GET, PRESENT,
                            conditions(none(), null, tag("\"stale\""), MODIFIED_AT)));
        }

        @ParameterizedTest
        @ValueSource(strings = {"PUT", "DELETE", "POST", "PATCH"})
        void itIsIgnoredForAnyMethodThatIsNotGetOrHead(String methodName) {
            HttpMethod method = HttpMethod.valueOf(methodName);
            // §13.1.3: "A recipient MUST ignore the If-Modified-Since header field if ... the
            // request method is neither GET nor HEAD."
            assertOutcome(PreconditionOutcome.PROCEED, null,
                    evaluate(method, PRESENT, conditions(none(), null, none(), MODIFIED_AT)));
        }
    }

    // ---------------------------------------------------------------- step 6

    @Test
    void aRequestWithNoConditionalsProceeds() {
        assertOutcome(PreconditionOutcome.PROCEED, null,
                evaluate(HttpMethod.PUT, PRESENT, conditions(none(), null, none(), null)));
    }

    @Test
    void aRequestWithNoConditionalsIsRecognisedAsEmpty() {
        // The flag that keeps an unconditional write from paying for a state lookup.
        assertEquals(true, conditions(none(), null, none(), null).isEmpty());
        assertEquals(false, conditions(none(), null, tag("*"), null).isEmpty());
        assertEquals(false, conditions(none(), BEFORE, none(), null).isEmpty());
    }
}
