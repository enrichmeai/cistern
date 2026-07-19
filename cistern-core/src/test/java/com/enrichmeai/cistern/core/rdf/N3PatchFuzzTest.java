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
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Malformed-patch fuzz suite (T1.5 DoD: "fuzz malformed patches → BadInput"). Structurally
 * corrupted documents are generated from a valid seed — hand-crafted structural breaks plus
 * seeded-random truncations and structural-character swaps (fixed seed, reproducible). Every
 * case must fail with {@link CisternException.BadInput} and nothing else:
 * {@code assertThrows} fails the test if any other exception type escapes the parser.
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

    @Test
    void seedDocumentIsValid() {
        assertEquals(1, parse(VALID).where().size());
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("corruptedDocuments")
    void corruptedDocumentFailsWithBadInputAndNothingElse(String label, String document) {
        assertThrows(CisternException.BadInput.class, () -> parse(document), () -> {
            try {
                parse(document);
                return label + ": expected BadInput but the document parsed";
            } catch (RuntimeException e) {
                return label + ": expected BadInput but got " + e.getClass().getName();
            }
        });
    }

    static Stream<Arguments> corruptedDocuments() {
        List<Arguments> cases = new ArrayList<>();
        handcraftedCorruptions(cases);
        seededTruncations(cases);
        seededStructuralSwaps(cases);
        if (cases.size() < 30) {
            fail("fuzz suite must contain at least 30 cases, has " + cases.size());
        }
        return cases.stream();
    }

    private static void handcraftedCorruptions(List<Arguments> cases) {
        cases.add(arguments("empty document", ""));
        cases.add(arguments("whitespace only", "  \n\t\n"));
        cases.add(arguments("comment only", "# nothing here\n"));
        cases.add(arguments("directives only", PREFIXES));
        cases.add(arguments("missing final dot", VALID.substring(0, VALID.lastIndexOf('.'))));
        cases.add(arguments("opening brace removed", VALID.replaceFirst("\\{", "")));
        cases.add(arguments("closing brace removed", VALID.replaceFirst("\\}", "")));
        cases.add(arguments("extra closing brace", VALID + "}\n"));
        cases.add(arguments("nested formula",
                PREFIXES + "_:p a solid:InsertDeletePatch; solid:where { ?a ex:knows { ?b ex:c \"d\". }. }."));
        cases.add(arguments("IRI containing a space",
                "@prefix ex: <http://ex ample.org/terms#>.\n" + SOLID_PREFIX + "_:p a solid:InsertDeletePatch."));
        cases.add(arguments("unterminated IRI at end of document",
                PREFIXES + "_:p a solid:InsertDeletePatch; solid:inserts { <#a> ex:b <http://example.org/unclosed"));
        cases.add(arguments("only the optional solid:Patch type",
                PREFIXES + "_:p a solid:Patch; solid:inserts { <#a> ex:b \"c\". }."));
        cases.add(arguments("wrong patch type",
                PREFIXES + "_:p a solid:SomethingElse; solid:inserts { <#a> ex:b \"c\". }."));
        cases.add(arguments("no type triple at all",
                PREFIXES + "_:p solid:inserts { <#a> ex:b \"c\". }."));
        cases.add(arguments("two patch resources",
                PREFIXES + "_:p a solid:InsertDeletePatch. _:q a solid:InsertDeletePatch."));
        cases.add(arguments("duplicate solid:inserts",
                PREFIXES + "_:p a solid:InsertDeletePatch;"
                        + " solid:inserts { <#a> ex:b \"c\". }; solid:inserts { <#d> ex:e \"f\". }."));
        cases.add(arguments("solid:inserts object is an IRI, not a formula",
                PREFIXES + "_:p a solid:InsertDeletePatch; solid:inserts <#notAFormula>."));
        cases.add(arguments("variable in inserts not bound in where",
                PREFIXES + "_:p a solid:InsertDeletePatch; solid:inserts { ?x ex:b \"c\". }."));
        cases.add(arguments("variable in deletes not bound in where",
                PREFIXES + "_:p a solid:InsertDeletePatch; solid:deletes { ?x ex:b \"c\". }."));
        cases.add(arguments("blank node in inserts",
                PREFIXES + "_:p a solid:InsertDeletePatch; solid:inserts { <#a> ex:b _:x. }."));
        cases.add(arguments("blank node in deletes",
                PREFIXES + "_:p a solid:InsertDeletePatch; solid:deletes { _:x ex:b \"c\". }."));
        cases.add(arguments("blank node in where (unsupported strictness)",
                PREFIXES + "_:p a solid:InsertDeletePatch; solid:where { _:x ex:b ?v. }."));
        cases.add(arguments("variable as patch subject",
                PREFIXES + "?p a solid:InsertDeletePatch."));
        cases.add(arguments("variable outside a formula as object",
                PREFIXES + "_:p a solid:InsertDeletePatch; solid:inserts ?x."));
        cases.add(arguments("@forAll quantifier",
                "@forAll ?x. " + PREFIXES + "_:p a solid:InsertDeletePatch."));
        cases.add(arguments("collection in a formula",
                PREFIXES + "_:p a solid:InsertDeletePatch; solid:inserts { <#a> ex:list ( \"1\" \"2\" ). }."));
        cases.add(arguments("implication as predicate", PREFIXES + "_:p => _:q."));
        cases.add(arguments("reverse implication as predicate", PREFIXES + "_:p <= _:q."));
        cases.add(arguments("undeclared prefix",
                SOLID_PREFIX + "_:p a solid:InsertDeletePatch; solid:inserts { <#a> ex:b \"c\". }."));
        cases.add(arguments("literal as statement subject",
                PREFIXES + "\"lit\" a solid:InsertDeletePatch."));
        cases.add(arguments("numeric literal as statement subject",
                PREFIXES + "42 a solid:InsertDeletePatch."));
        cases.add(arguments("blank node property list with content",
                PREFIXES + "[ ex:a \"b\" ] a solid:InsertDeletePatch."));
        cases.add(arguments("unterminated string literal",
                PREFIXES + "_:p a solid:InsertDeletePatch; solid:inserts { <#a> ex:b \"unterminated. }."));
        cases.add(arguments("bad unicode escape in string",
                PREFIXES + "_:p a solid:InsertDeletePatch; solid:inserts { <#a> ex:b \"\\uZZZZ\". }."));
        cases.add(arguments("junk after the final statement", VALID + "\ngarbage"));
        cases.add(arguments("prefix directive inside a formula",
                PREFIXES + "_:p a solid:InsertDeletePatch;"
                        + " solid:inserts { @prefix x: <http://x.example/>. <#a> ex:b \"c\". }."));
        cases.add(arguments("uppercase @PREFIX directive",
                "@PREFIX solid: <http://www.w3.org/ns/solid/terms#>.\n_:p a solid:InsertDeletePatch."));
        cases.add(arguments("extraneous non-patch triple",
                PREFIXES + "_:p a solid:InsertDeletePatch. <#x> ex:comment \"hi\"."));
    }

    /**
     * Truncations at seeded-random positions strictly before the final '.' of the seed:
     * every such prefix is either a directives-only document (no patch resource) or cuts
     * the single patch statement short — both BadInput.
     */
    private static void seededTruncations(List<Arguments> cases) {
        Random random = new Random(SEED);
        int finalDot = VALID.lastIndexOf('.');
        Set<Integer> positions = new LinkedHashSet<>();
        while (positions.size() < 15) {
            positions.add(1 + random.nextInt(finalDot - 1));
        }
        for (int position : positions) {
            cases.add(arguments("truncated at index " + position, VALID.substring(0, position)));
        }
    }

    /**
     * Structural-character swaps at seeded-random occurrences: unbalancing a brace,
     * inverting an IRI delimiter, or replacing a ';' with a '{' always breaks the grammar.
     */
    private static void seededStructuralSwaps(List<Arguments> cases) {
        Map<Character, Character> swaps = Map.of(
                '{', '}',
                '}', '{',
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
}
