package com.enrichmeai.cistern.core.rdf;

import com.enrichmeai.cistern.core.CisternException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Stream;

import static com.enrichmeai.cistern.core.rdf.N3PatchTestSupport.PREFIXES;
import static com.enrichmeai.cistern.core.rdf.N3PatchTestSupport.SOLID_PREFIX;
import static com.enrichmeai.cistern.core.rdf.N3PatchTestSupport.parse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * Malformed-patch fuzz suite (T1.5 DoD: "fuzz malformed patches → BadInput", refined by the
 * architect ruling on PR #56 into the two rejection codes the spec distinguishes).
 *
 * <p>Corrupted documents are generated from a valid seed and split by failure kind:
 * <ul>
 *   <li><strong>Syntax corruptions</strong> (unparseable) → {@link CisternException.BadInput}
 *       (400): hand-crafted grammar breaks, seeded truncations inside the patch statement,
 *       and seeded structural-character swaps. Out-of-subset N3 constructs at <em>document</em>
 *       level belong here too — the formula-content constraint does not govern them.</li>
 *   <li><strong>Constraint violations</strong> (well-formed N3, invalid patch) →
 *       {@link CisternException.UnprocessableEntity} (422): the document-shape constraints,
 *       plus recognized N3 content <em>inside a formula</em> that is not a plain triple or
 *       triple pattern.</li>
 * </ul>
 *
 * <p>Neither exception type is a subtype of the other, so each {@code assertThrows} enforces
 * the split rather than merely tolerating it — and still guarantees nothing else escapes.
 */
class N3PatchFuzzTest {

    /** Fixed seed — never change without re-verifying every generated case still fails. */
    private static final long SEED = 20260719L;

    /** The valid seed document (the spec's example, §n3-patch-example). */
    private static final String VALID = """
            @prefix solid: <http://www.w3.org/ns/solid/terms#>.
            @prefix ex: <http://www.example.org/terms#>.

            _:rename a solid:InsertDeletePatch;
              solid:where   { ?person ex:familyName "Garcia". };
              solid:inserts { ?person ex:givenName "Alex". };
              solid:deletes { ?person ex:givenName "Claudia". }.
            """;

    /** Start of the single patch statement, i.e. the end of the directives block. */
    private static final int PATCH_STATEMENT_START = VALID.indexOf("_:rename");

    @Test
    void seedDocumentIsValid() {
        assertEquals(1, parse(VALID).where().size());
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("syntaxCorruptions")
    void syntaxCorruptionFailsWithBadInputAndNothingElse(String label, String document) {
        assertThrows(CisternException.BadInput.class, () -> parse(document),
                () -> describeUnexpected(label, document, "BadInput"));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("constraintViolations")
    void constraintViolationFailsWithUnprocessableEntityAndNothingElse(String label, String document) {
        assertThrows(CisternException.UnprocessableEntity.class, () -> parse(document),
                () -> describeUnexpected(label, document, "UnprocessableEntity"));
    }

    private static String describeUnexpected(String label, String document, String expected) {
        try {
            parse(document);
            return label + ": expected " + expected + " but the document parsed";
        } catch (RuntimeException e) {
            return label + ": expected " + expected + " but got " + e.getClass().getSimpleName();
        }
    }

    // ------------------------------------------------------------------ BadInput (400)

    static Stream<Arguments> syntaxCorruptions() {
        List<Arguments> cases = new ArrayList<>();
        handcraftedSyntaxBreaks(cases);
        seededTruncations(cases);
        seededStructuralSwaps(cases);
        if (cases.size() < 30) {
            fail("the syntax fuzz suite must contain at least 30 cases, has " + cases.size());
        }
        return cases.stream();
    }

    private static void handcraftedSyntaxBreaks(List<Arguments> cases) {
        cases.add(arguments("missing final dot", VALID.substring(0, VALID.lastIndexOf('.'))));
        cases.add(arguments("opening brace removed", VALID.replaceFirst("\\{", "")));
        cases.add(arguments("closing brace removed", VALID.replaceFirst("\\}", "")));
        cases.add(arguments("extra closing brace", VALID + "}\n"));
        cases.add(arguments("IRI containing a space",
                "@prefix ex: <http://ex ample.org/terms#>.\n" + SOLID_PREFIX + "_:p a solid:InsertDeletePatch."));
        cases.add(arguments("unterminated IRI at end of document",
                PREFIXES + "_:p a solid:InsertDeletePatch; solid:inserts { <#a> ex:b <http://example.org/unclosed"));
        cases.add(arguments("variable as patch subject", PREFIXES + "?p a solid:InsertDeletePatch."));
        cases.add(arguments("variable outside a formula as object",
                PREFIXES + "_:p a solid:InsertDeletePatch; solid:inserts ?x."));
        // Out-of-subset N3 at DOCUMENT level: the formula-content constraint does not reach it.
        cases.add(arguments("@forAll quantifier at document level",
                "@forAll ?x. " + PREFIXES + "_:p a solid:InsertDeletePatch."));
        cases.add(arguments("@forSome quantifier at document level",
                "@forSome _:x. " + PREFIXES + "_:p a solid:InsertDeletePatch."));
        cases.add(arguments("implication as predicate at document level", PREFIXES + "_:p => _:q."));
        cases.add(arguments("reverse implication at document level", PREFIXES + "_:p <= _:q."));
        cases.add(arguments("equality as predicate at document level", PREFIXES + "_:p = _:q."));
        cases.add(arguments("undeclared prefix",
                SOLID_PREFIX + "_:p a solid:InsertDeletePatch; solid:inserts { <#a> ex:b \"c\". }."));
        cases.add(arguments("literal as statement subject at document level",
                PREFIXES + "\"lit\" a solid:InsertDeletePatch."));
        cases.add(arguments("numeric literal as statement subject at document level",
                PREFIXES + "42 a solid:InsertDeletePatch."));
        cases.add(arguments("blank node property list at document level",
                PREFIXES + "[ ex:a \"b\" ] a solid:InsertDeletePatch."));
        cases.add(arguments("unknown directive inside a formula (garbage, not an N3 construct)",
                PREFIXES + "_:p a solid:InsertDeletePatch; solid:inserts { @nonsense x. <#a> ex:b \"c\". }."));
        cases.add(arguments("unterminated string literal",
                PREFIXES + "_:p a solid:InsertDeletePatch; solid:inserts { <#a> ex:b \"unterminated. }."));
        cases.add(arguments("bad unicode escape in string",
                PREFIXES + "_:p a solid:InsertDeletePatch; solid:inserts { <#a> ex:b \"\\uZZZZ\". }."));
        cases.add(arguments("junk after the final statement", VALID + "\ngarbage"));
        cases.add(arguments("uppercase @PREFIX directive",
                "@PREFIX solid: <http://www.w3.org/ns/solid/terms#>.\n_:p a solid:InsertDeletePatch."));
        cases.add(arguments("prefix declaration without a colon",
                "@prefix solid <http://www.w3.org/ns/solid/terms#>.\n_:p a solid:InsertDeletePatch."));
        cases.add(arguments("formula as statement subject",
                PREFIXES + "{ ?a ex:b \"c\". } a solid:InsertDeletePatch."));
        cases.add(arguments("formula as predicate",
                PREFIXES + "_:p { ?a ex:b \"c\". } solid:InsertDeletePatch."));
    }

    /**
     * Truncations at seeded-random positions strictly inside the single patch statement. Every
     * such prefix ends before the statement's terminating '.', so the document is always
     * syntactically incomplete — deterministically BadInput, never a constraint violation.
     * (Truncating in the directives block is deliberately excluded: cutting exactly at a
     * directive boundary yields a well-formed directives-only document, which is a 422 and is
     * covered under {@link #constraintViolations()}.)
     */
    private static void seededTruncations(List<Arguments> cases) {
        Random random = new Random(SEED);
        int finalDot = VALID.lastIndexOf('.');
        Set<Integer> positions = new LinkedHashSet<>();
        while (positions.size() < 15) {
            positions.add(PATCH_STATEMENT_START + 1
                    + random.nextInt(finalDot - PATCH_STATEMENT_START - 1));
        }
        for (int position : positions) {
            cases.add(arguments("truncated at index " + position, VALID.substring(0, position)));
        }
    }

    /**
     * Structural-character swaps at seeded-random occurrences: unbalancing an opening brace,
     * inverting an IRI delimiter, or replacing a ';' with a '{' always breaks the grammar at
     * document level.
     *
     * <p>The {@code '}' -> '{'} swap is deliberately excluded here and covered as a 422 in
     * {@link #constraintViolations()}: it leaves the formula unclosed, so the following
     * {@code '{'} is indistinguishable from a nested formula at the detection site. See the
     * documented gap in {@code N3PatchParser}'s javadoc.
     */
    private static void seededStructuralSwaps(List<Arguments> cases) {
        Map<Character, Character> swaps = Map.of(
                '{', '}',
                '<', '>',
                '>', '<',
                ';', '{');
        List<Integer> structuralPositions = new ArrayList<>();
        for (int i = 0; i < VALID.length(); i++) {
            if (swaps.containsKey(VALID.charAt(i))) {
                structuralPositions.add(i);
            }
        }
        Random random = new Random(SEED);
        Set<Integer> chosen = new LinkedHashSet<>();
        while (chosen.size() < 10) {
            chosen.add(structuralPositions.get(random.nextInt(structuralPositions.size())));
        }
        for (int position : chosen) {
            char original = VALID.charAt(position);
            char replacement = swaps.get(original);
            String corrupted = VALID.substring(0, position) + replacement + VALID.substring(position + 1);
            cases.add(arguments(
                    "structural swap '" + original + "'->'" + replacement + "' at index " + position,
                    corrupted));
        }
    }

    // ------------------------------------------------------------ UnprocessableEntity (422)

    /**
     * Replacing a formula's closing '}' with '{' — a genuine unbalanced-brace corruption that
     * nevertheless surfaces as 422, because the unclosed formula makes the following '{' look
     * exactly like a nested formula at the point of detection. Telling the two apart would
     * require brace-balance lookahead, i.e. restructuring the parser; the architect ruling on
     * PR #56 explicitly prefers a documented narrow gap over that. Asserted here so the
     * behaviour is pinned rather than accidental.
     */
    private static void unbalancedClosingBraceSwaps(List<Arguments> cases) {
        for (int i = 0; i < VALID.length(); i++) {
            if (VALID.charAt(i) == '}') {
                cases.add(arguments(
                        "unbalanced '}'->'{' at index " + i + " (reads as a nested formula)",
                        VALID.substring(0, i) + '{' + VALID.substring(i + 1)));
            }
        }
    }

    static Stream<Arguments> constraintViolations() {
        List<Arguments> cases = new ArrayList<>();
        // "A patch document MUST contain one or more patch resources" — well-formed N3, no patch.
        cases.add(arguments("empty document", ""));
        cases.add(arguments("whitespace only", "  \n\t\n"));
        cases.add(arguments("comment only", "# nothing here\n"));
        cases.add(arguments("directives only", PREFIXES));
        cases.add(arguments("directives only, SPARQL style",
                "PREFIX solid: <http://www.w3.org/ns/solid/terms#>\n"));
        // "A patch resource MUST contain a triple ?patch rdf:type solid:InsertDeletePatch".
        cases.add(arguments("only the optional solid:Patch type",
                PREFIXES + "_:p a solid:Patch; solid:inserts { <#a> ex:b \"c\". }."));
        cases.add(arguments("wrong patch type",
                PREFIXES + "_:p a solid:SomethingElse; solid:inserts { <#a> ex:b \"c\". }."));
        cases.add(arguments("no type triple at all",
                PREFIXES + "_:p solid:inserts { <#a> ex:b \"c\". }."));
        // "The patch document MUST contain exactly one patch resource".
        cases.add(arguments("two patch resources",
                PREFIXES + "_:p a solid:InsertDeletePatch. _:q a solid:InsertDeletePatch."));
        cases.add(arguments("extraneous non-patch triple",
                PREFIXES + "_:p a solid:InsertDeletePatch. <#x> ex:comment \"hi\"."));
        cases.add(arguments("unexpected predicate on the patch resource",
                PREFIXES + "_:p a solid:InsertDeletePatch; ex:comment \"hi\"."));
        // "MUST contain at most one triple of the form ?patch solid:X ?y".
        cases.add(arguments("duplicate solid:inserts",
                PREFIXES + "_:p a solid:InsertDeletePatch;"
                        + " solid:inserts { <#a> ex:b \"c\". }; solid:inserts { <#d> ex:e \"f\". }."));
        cases.add(arguments("duplicate solid:where",
                PREFIXES + "_:p a solid:InsertDeletePatch;"
                        + " solid:where { ?a ex:b \"c\". }; solid:where { ?a ex:e \"f\". }."));
        cases.add(arguments("duplicate solid:deletes",
                PREFIXES + "_:p a solid:InsertDeletePatch;"
                        + " solid:deletes { <#a> ex:b \"c\". }; solid:deletes { <#d> ex:e \"f\". }."));
        // "?deletions, ?insertions and ?conditions MUST be non-nested cited formulae".
        cases.add(arguments("solid:inserts object is an IRI, not a formula",
                PREFIXES + "_:p a solid:InsertDeletePatch; solid:inserts <#notAFormula>."));
        cases.add(arguments("solid:where object is a literal, not a formula",
                PREFIXES + "_:p a solid:InsertDeletePatch; solid:where \"x\"."));
        // "MUST NOT contain variables that do not occur in the ?conditions formula".
        cases.add(arguments("variable in inserts not bound in where",
                PREFIXES + "_:p a solid:InsertDeletePatch; solid:inserts { ?x ex:b \"c\". }."));
        cases.add(arguments("variable in deletes not bound in where",
                PREFIXES + "_:p a solid:InsertDeletePatch; solid:deletes { ?x ex:b \"c\". }."));
        cases.add(arguments("variable in inserts absent from a non-empty where",
                PREFIXES + "_:p a solid:InsertDeletePatch;"
                        + " solid:where { ?y ex:a \"v\". }; solid:inserts { ?x ex:b ?y. }."));
        // "The ?insertions and ?deletions formulae MUST NOT contain blank nodes".
        cases.add(arguments("blank node in inserts",
                PREFIXES + "_:p a solid:InsertDeletePatch; solid:inserts { <#a> ex:b _:x. }."));
        cases.add(arguments("blank node in deletes",
                PREFIXES + "_:p a solid:InsertDeletePatch; solid:deletes { _:x ex:b \"c\". }."));
        cases.add(arguments("anonymous blank node in inserts",
                PREFIXES + "_:p a solid:InsertDeletePatch; solid:inserts { [] ex:b \"c\". }."));
        // Deliberate limitation, NOT a spec constraint — see issue #57.
        cases.add(arguments("blank node in where (deliberate limitation, issue #57)",
                PREFIXES + "_:p a solid:InsertDeletePatch; solid:where { _:x ex:b ?v. }."));
        // "...non-nested cited formulae consisting only of triples and/or triple patterns":
        // recognized N3 content inside a formula that is not a plain triple/triple pattern.
        cases.add(arguments("nested formula as object",
                PREFIXES + "_:p a solid:InsertDeletePatch; solid:where { ?a ex:knows { ?b ex:c \"d\". }. }."));
        cases.add(arguments("nested formula as subject",
                PREFIXES + "_:p a solid:InsertDeletePatch; solid:where { { ?b ex:c \"d\". } ex:k ?a. }."));
        cases.add(arguments("collection in a formula",
                PREFIXES + "_:p a solid:InsertDeletePatch; solid:inserts { <#a> ex:list ( \"1\" \"2\" ). }."));
        cases.add(arguments("collection as formula subject",
                PREFIXES + "_:p a solid:InsertDeletePatch; solid:where { ( \"1\" ) ex:b ?v. }."));
        cases.add(arguments("implication inside a formula",
                PREFIXES + "_:p a solid:InsertDeletePatch; solid:where { ?a => ?b. }."));
        cases.add(arguments("equality inside a formula",
                PREFIXES + "_:p a solid:InsertDeletePatch; solid:where { ?a = ?b. }."));
        cases.add(arguments("reverse implication inside a formula",
                PREFIXES + "_:p a solid:InsertDeletePatch; solid:where { ?a <= ?b. }."));
        cases.add(arguments("@prefix declaration inside a formula",
                PREFIXES + "_:p a solid:InsertDeletePatch;"
                        + " solid:inserts { @prefix x: <http://x.example/>. <#a> ex:b \"c\". }."));
        cases.add(arguments("@forAll quantifier inside a formula",
                PREFIXES + "_:p a solid:InsertDeletePatch; solid:where { @forAll ?x. ?x ex:b \"c\". }."));
        cases.add(arguments("blank node property list inside a formula",
                PREFIXES + "_:p a solid:InsertDeletePatch; solid:where { [ ex:a \"b\" ] ex:c ?v. }."));
        cases.add(arguments("literal as triple pattern subject",
                PREFIXES + "_:p a solid:InsertDeletePatch; solid:where { \"lit\" ex:a ?v. }."));
        cases.add(arguments("literal as triple pattern predicate",
                PREFIXES + "_:p a solid:InsertDeletePatch; solid:where { ?s \"lit\" ?v. }."));
        cases.add(arguments("blank node as triple pattern predicate",
                PREFIXES + "_:p a solid:InsertDeletePatch; solid:where { ?s _:b ?v. }."));
        unbalancedClosingBraceSwaps(cases);
        return cases.stream();
    }
}
