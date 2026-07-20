package com.enrichmeai.cistern.webflux;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The {@code #entity-tag} grammar of RFC 9110 §8.8.3 and the per-field evaluation rules of
 * §13.1.1 and §13.1.2, exercised without an HTTP stack.
 *
 * <p>Worth testing at this level rather than only over the wire: the interesting inputs are
 * malformed ones, and a malformed field is indistinguishable from a well-formed one that
 * happens not to match if all you can see is the status code.
 */
class EntityTagConditionTest {

    private static final EntityTag CURRENT = new EntityTag("current");
    private static final EntityTag OTHER = new EntityTag("other");

    private static final TargetState PRESENT =
            new TargetState.Present(Set.of(CURRENT), Instant.EPOCH);
    private static final TargetState ABSENT = new TargetState.Absent();

    private static EntityTagCondition parse(String fieldValue) {
        return EntityTagCondition.parse(List.of(fieldValue));
    }

    @Nested
    @DisplayName("parsing (RFC 9110 §8.8.3, §5.6.1)")
    class Parsing {

        @Test
        void anAbsentFieldIsNotPresent() {
            assertInstanceOf(EntityTagCondition.NotPresent.class,
                    EntityTagCondition.parse(List.of()));
            assertFalse(EntityTagCondition.parse(List.of()).isPresent());
        }

        @Test
        void aWildcardIsTheAnyRepresentationAlternative() {
            assertInstanceOf(EntityTagCondition.AnyRepresentation.class, parse("*"));
            assertInstanceOf(EntityTagCondition.AnyRepresentation.class, parse("  *  "));
        }

        @Test
        void aStrongTagParsesAsStrong() {
            assertEquals(new EntityTagCondition.Listed(List.of(new ClientEntityTag("abc", false))),
                    parse("\"abc\""));
        }

        @Test
        void aWeakTagKeepsItsWeakFlag() {
            assertEquals(new EntityTagCondition.Listed(List.of(new ClientEntityTag("abc", true))),
                    parse("W/\"abc\""));
        }

        @Test
        void aListParsesInOrderWithOptionalWhitespace() {
            assertEquals(new EntityTagCondition.Listed(List.of(
                            new ClientEntityTag("a", false),
                            new ClientEntityTag("b", true),
                            new ClientEntityTag("c", false))),
                    parse("\"a\",\tW/\"b\" ,  \"c\""));
        }

        @Test
        void aCommaInsideAnOpaqueTagIsPartOfTheTagAndNotASeparator() {
            // etagc covers %x23-7E, which includes ',' (%x2C). Splitting on commas would turn
            // this one tag into two that the client never sent — and then compare against them.
            assertEquals(new EntityTagCondition.Listed(List.of(new ClientEntityTag("a,b", false))),
                    parse("\"a,b\""));
        }

        @Test
        void repeatedFieldLinesCombineIntoOneList() {
            // RFC 9110 §5.3: multiple field lines of the same field combine, comma-separated.
            assertEquals(new EntityTagCondition.Listed(List.of(
                            new ClientEntityTag("a", false), new ClientEntityTag("b", false))),
                    EntityTagCondition.parse(List.of("\"a\"", "\"b\"")));
        }

        @Test
        void anEmptyOpaqueTagIsWellFormedAndCanNeverMatch() {
            // opaque-tag = DQUOTE *etagc DQUOTE — zero characters is legal. EntityTag rejects a
            // blank value, so nothing the server can serve will ever equal it.
            EntityTagCondition condition = parse("\"\"");

            assertEquals(new EntityTagCondition.Listed(List.of(new ClientEntityTag("", false))),
                    condition);
            assertFalse(condition.satisfiedBy(PRESENT));
        }

        @Test
        void anEmptyFieldValueIsAnEmptyList() {
            assertEquals(new EntityTagCondition.Listed(List.of()), parse(""));
        }

        @ParameterizedTest(name = "[{index}] {0}")
        @ValueSource(strings = {
                "unquoted",              // no DQUOTE at all
                "\"unterminated",        // no closing DQUOTE
                "w/\"abc\"",             // weak is %s"W/" — case-sensitive
                "W/abc",                 // weak prefix without an opaque-tag
                "*, \"a\"",              // §13.1.1: "*" mixed with values is syntactically invalid
                "\"a\", *",
                "\"a\" trailing",        // junk after a well-formed tag
                "\"a\"\"b\""             // two tags with no separator
        })
        void aValueThatIsNeitherWildcardNorAListIsUnparseable(String fieldValue) {
            assertInstanceOf(EntityTagCondition.Unparseable.class, parse(fieldValue));
        }

        @ParameterizedTest(name = "[{index}] opaque-tag containing 0x{0}")
        @ValueSource(ints = {0x00, 0x01, 0x20, 0x22, 0x7F})
        void aCharacterOutsideEtagcMakesTheTagUnparseable(int codePoint) {
            // etagc = %x21 / %x23-7E / obs-text. NUL and SOH are controls, SP (%x20) and DEL
            // (%x7F) are excluded by the ranges, and DQUOTE (%x22) would terminate the tag.
            // Built here rather than written into the source, where a control character would
            // be invisible to a reviewer.
            assertInstanceOf(EntityTagCondition.Unparseable.class,
                    parse("\"a" + (char) codePoint + "b\""));
        }
    }

    @Nested
    @DisplayName("If-Match (RFC 9110 §13.1.1)")
    class IfMatch {

        @Test
        void wildcardIsTrueOnlyWhenAResourceExists() {
            assertTrue(parse("*").satisfiedBy(PRESENT));
            assertFalse(parse("*").satisfiedBy(ABSENT));
        }

        @Test
        void aListIsTrueIfAnyListedTagMatches() {
            assertTrue(parse("\"other\", \"current\"").satisfiedBy(PRESENT));
            assertFalse(parse("\"other\", \"another\"").satisfiedBy(PRESENT));
        }

        @Test
        void aTagNeverMatchesAnAbsentResource() {
            assertFalse(parse("\"current\"").satisfiedBy(ABSENT));
        }

        @Test
        void anUnparseableValueIsFalse() {
            // Step 3: "Otherwise, the condition is false" → 412, not 400 and not a pass.
            assertFalse(parse("garbage").satisfiedBy(PRESENT));
        }

        @Test
        void anAbsentFieldIsVacuouslySatisfiedSoItsStepIsSkipped() {
            assertTrue(EntityTagCondition.parse(List.of()).satisfiedBy(ABSENT));
        }

        @Test
        void aWeakTagNeverSatisfiesIfMatch() {
            // "An origin server MUST use the strong comparison function ... for If-Match."
            assertFalse(parse("W/\"current\"").satisfiedBy(PRESENT));
        }

        @Test
        void anyCurrentRepresentationCanSatisfyAMethodThatSelectsNone() {
            TargetState both = new TargetState.Present(Set.of(CURRENT, OTHER), Instant.EPOCH);

            assertTrue(parse("\"other\"").satisfiedBy(both));
            assertTrue(parse("\"current\"").satisfiedBy(both));
        }
    }

    @Nested
    @DisplayName("If-None-Match (RFC 9110 §13.1.2)")
    class IfNoneMatch {

        @Test
        void wildcardIsTrueOnlyWhenNothingExists() {
            assertFalse(parse("*").notSatisfiedBy(PRESENT));
            assertTrue(parse("*").notSatisfiedBy(ABSENT));
        }

        @Test
        void aListIsFalseIfOneListedTagMatches() {
            assertFalse(parse("\"other\", \"current\"").notSatisfiedBy(PRESENT));
            assertTrue(parse("\"other\", \"another\"").notSatisfiedBy(PRESENT));
        }

        @Test
        void anUnparseableValueIsTrue() {
            // Step 3: "Otherwise, the condition is true" — the opposite sense to If-Match.
            assertTrue(parse("garbage").notSatisfiedBy(PRESENT));
        }

        @Test
        void aWeakTagStillMatches() {
            // "A recipient MUST use the weak comparison function ... for If-None-Match."
            assertFalse(parse("W/\"current\"").notSatisfiedBy(PRESENT));
        }
    }

    @Nested
    @DisplayName("comparison functions (RFC 9110 §8.8.3.2, Table 3)")
    class ComparisonFunctions {

        /**
         * The RFC's own worked table, transcribed. Cistern only ever holds strong validators,
         * so the {@code W/"1"} vs {@code W/"1"} row is expressed with a strong current tag —
         * which is the case that actually arises and the one both columns must still agree on.
         */
        @ParameterizedTest(name = "[{index}] received {0} vs current \"{1}\" — strong={2} weak={3}")
        @CsvSource({
                "W/\"1\", 1, false, true",
                "W/\"1\", 2, false, false",
                "\"1\",   1, true,  true",
                "\"1\",   2, false, false"
        })
        void strongAndWeakComparisonAgreeWithTheSpecTable(String received, String currentValue,
                                                          boolean strong, boolean weak) {
            ClientEntityTag tag = ((EntityTagCondition.Listed) parse(received)).tags().getFirst();
            EntityTag current = new EntityTag(currentValue);

            assertEquals(strong, tag.matchesStrongly(current), "strong comparison");
            assertEquals(weak, tag.matchesWeakly(current), "weak comparison");
        }
    }
}
