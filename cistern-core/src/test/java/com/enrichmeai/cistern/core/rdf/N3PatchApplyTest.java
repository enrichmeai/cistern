package com.enrichmeai.cistern.core.rdf;

import com.enrichmeai.cistern.core.CisternException;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.junit.jupiter.api.Test;

import static com.enrichmeai.cistern.core.rdf.N3PatchTestSupport.PREFIXES;
import static com.enrichmeai.cistern.core.rdf.N3PatchTestSupport.parse;
import static com.enrichmeai.cistern.core.rdf.N3PatchTestSupport.turtle;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Application semantics per Solid Protocol §n3-patch, "Servers MUST process a patch
 * resource against the target document as follows" — variable mapping, the
 * no-mapping/multiple-mappings 409 rule, the deletions-must-all-be-present 409 rule, and
 * the deletions-then-insertions order.
 */
class N3PatchApplyTest {

    private static final String EX = "http://www.example.org/terms#";

    private Property property(Model model, String local) {
        return model.createProperty(EX, local);
    }

    private Resource resource(Model model, String fragment) {
        return model.createResource("https://pod.example/notes/doc#" + fragment);
    }

    @Test
    void insertsOnlyPatchOnEmptyGraph() {
        // Step 1 of the processing rules: "Start from the RDF dataset in the target
        // document, or an empty RDF dataset if the target resource does not exist yet."
        Model target = ModelFactory.createDefaultModel();
        Model result = parse(PREFIXES
                + "_:p a solid:InsertDeletePatch; solid:inserts { <#s> ex:v \"1\". }.")
                .applyTo(target);
        assertEquals(1, result.size());
        assertTrue(result.contains(resource(result, "s"), property(result, "v"), "1"));
        assertEquals(0, target.size(), "the target model is never modified");
    }

    @Test
    void emptyPatchIsANoOp() {
        Model target = turtle("@prefix ex: <http://www.example.org/terms#>. <#s> ex:v \"1\".");
        Model result = parse(PREFIXES + "_:p a solid:InsertDeletePatch.").applyTo(target);
        assertNotSame(target, result);
        assertTrue(result.isIsomorphicWith(target));
    }

    @Test
    void groundDeletePresentIsRemoved() {
        Model target = turtle("""
                @prefix ex: <http://www.example.org/terms#>.
                <#s> ex:v "1" ; ex:w "2" .
                """);
        Model result = parse(PREFIXES
                + "_:p a solid:InsertDeletePatch; solid:deletes { <#s> ex:v \"1\". }.")
                .applyTo(target);
        assertEquals(1, result.size());
        assertFalse(result.contains(resource(result, "s"), property(result, "v"), "1"));
        assertTrue(result.contains(resource(result, "s"), property(result, "w"), "2"));
    }

    @Test
    void groundDeleteAbsentIsAConflict() {
        // "If the set of triples resulting from ?deletions is non-empty and the dataset
        //  does not contain all of these triples, the server MUST respond with a 409
        //  status code" (§n3-patch).
        Model target = ModelFactory.createDefaultModel();
        N3Patch patch = parse(PREFIXES
                + "_:p a solid:InsertDeletePatch; solid:deletes { <#s> ex:v \"1\". }.");
        assertThrows(CisternException.Conflict.class, () -> patch.applyTo(target));
    }

    @Test
    void partiallyPresentDeletionsAreAConflictAndNothingIsApplied() {
        // "...does not contain ALL of these triples" — one of two present is not enough.
        Model target = turtle("@prefix ex: <http://www.example.org/terms#>. <#s> ex:v \"1\".");
        N3Patch patch = parse(PREFIXES
                + "_:p a solid:InsertDeletePatch; solid:deletes { <#s> ex:v \"1\". <#s> ex:w \"2\". }.");
        assertThrows(CisternException.Conflict.class, () -> patch.applyTo(target));
        assertEquals(1, target.size(), "no partial application");
    }

    @Test
    void whereWithZeroMappingsIsAConflict() {
        // "If no such mapping exists, or if multiple mappings exist, the server MUST
        //  respond with a 409 status code" (§n3-patch).
        Model target = turtle("@prefix ex: <http://www.example.org/terms#>. <#s> ex:v \"1\".");
        N3Patch patch = parse(PREFIXES
                + "_:p a solid:InsertDeletePatch; solid:where { ?s ex:missing \"x\". }.");
        assertThrows(CisternException.Conflict.class, () -> patch.applyTo(target));
    }

    @Test
    void whereWithMultipleMappingsIsAConflict() {
        Model target = turtle("""
                @prefix ex: <http://www.example.org/terms#>.
                <#a> ex:v "1" . <#b> ex:v "1" .
                """);
        N3Patch patch = parse(PREFIXES
                + "_:p a solid:InsertDeletePatch; solid:where { ?s ex:v \"1\". }.");
        assertThrows(CisternException.Conflict.class, () -> patch.applyTo(target));
    }

    @Test
    void whereWithExactlyOneMappingPropagatesToInsertsAndDeletes() {
        // "The resulting variable mapping is propagated to the ?deletions and ?insertions
        //  formulae to obtain two sets of resulting triples" (§n3-patch).
        Model target = turtle("""
                @prefix ex: <http://www.example.org/terms#>.
                <#a> ex:key "k" ; ex:old "1" .
                """);
        Model result = parse(PREFIXES + """
                _:p a solid:InsertDeletePatch;
                  solid:where   { ?s ex:key "k". };
                  solid:deletes { ?s ex:old "1". };
                  solid:inserts { ?s ex:new "2". }.
                """).applyTo(target);
        assertEquals(2, result.size());
        assertTrue(result.contains(resource(result, "a"), property(result, "new"), "2"));
        assertFalse(result.contains(resource(result, "a"), property(result, "old"), "1"));
    }

    @Test
    void joinAcrossPatternsBindsConsistently() {
        Model target = turtle("""
                @prefix ex: <http://www.example.org/terms#>.
                <#s1> ex:a "x" ; ex:b "x" .
                <#s2> ex:a "y" ; ex:b "z" .
                """);
        Model result = parse(PREFIXES + """
                _:p a solid:InsertDeletePatch;
                  solid:where   { ?s ex:a ?v. ?s ex:b ?v. };
                  solid:inserts { ?s ex:c ?v. }.
                """).applyTo(target);
        assertTrue(result.contains(resource(result, "s1"), property(result, "c"), "x"),
                "only <#s1> satisfies both patterns with a consistent ?v");
        assertEquals(5, result.size());
    }

    @Test
    void crossProductOfIndependentPatternsCountsAsMultipleMappings() {
        Model target = turtle("""
                @prefix ex: <http://www.example.org/terms#>.
                <#a1> ex:p "1" . <#a2> ex:p "1" . <#b> ex:q "2" .
                """);
        N3Patch patch = parse(PREFIXES
                + "_:p a solid:InsertDeletePatch; solid:where { ?a ex:p \"1\". ?b ex:q \"2\". }.");
        assertThrows(CisternException.Conflict.class, () -> patch.applyTo(target),
                "two bindings for ?a times one for ?b = two mappings");
    }

    @Test
    void repeatedVariableWithinOnePatternMustBindConsistently() {
        Model target = turtle("""
                @prefix ex: <http://www.example.org/terms#>.
                <#a> ex:knows <#a> .
                <#a> ex:knows <#b> .
                """);
        Model result = parse(PREFIXES + """
                _:p a solid:InsertDeletePatch;
                  solid:where   { ?x ex:knows ?x. };
                  solid:inserts { ?x ex:selfAware true. }.
                """).applyTo(target);
        assertTrue(result.contains(resource(result, "a"), property(result, "selfAware")),
                "only the reflexive triple binds ?x consistently — one mapping");
    }

    @Test
    void duplicatedPatternsDoNotDoubleCountMappings() {
        Model target = turtle("@prefix ex: <http://www.example.org/terms#>. <#a> ex:v \"1\".");
        Model result = parse(PREFIXES + """
                _:p a solid:InsertDeletePatch;
                  solid:where   { ?s ex:v "1". ?s ex:v "1". };
                  solid:inserts { ?s ex:w "2". }.
                """).applyTo(target);
        assertTrue(result.contains(resource(result, "a"), property(result, "w"), "2"),
                "the duplicate pattern yields the same single mapping, not a conflict");
    }

    @Test
    void variableInPredicatePositionBinds() {
        Model target = turtle("@prefix ex: <http://www.example.org/terms#>. <#s> ex:q \"v\".");
        Model result = parse(PREFIXES + """
                _:p a solid:InsertDeletePatch;
                  solid:where   { <#s> ?p "v". };
                  solid:inserts { <#t> ?p "w". }.
                """).applyTo(target);
        assertTrue(result.contains(resource(result, "t"), property(result, "q"), "w"));
    }

    @Test
    void insertingAnAlreadyPresentTripleIsIdempotent() {
        Model target = turtle("@prefix ex: <http://www.example.org/terms#>. <#s> ex:v \"1\".");
        Model result = parse(PREFIXES
                + "_:p a solid:InsertDeletePatch; solid:inserts { <#s> ex:v \"1\". }.")
                .applyTo(target);
        assertEquals(1, result.size());
    }

    @Test
    void deleteThenInsertOfTheSameTripleLeavesItPresent() {
        // "The combination of deletions followed by insertions then forms the new resource
        //  state" (§n3-patch) — deletions are applied first, so a triple in both sets survives.
        Model target = turtle("@prefix ex: <http://www.example.org/terms#>. <#s> ex:v \"1\".");
        Model result = parse(PREFIXES + """
                _:p a solid:InsertDeletePatch;
                  solid:deletes { <#s> ex:v "1". };
                  solid:inserts { <#s> ex:v "1". }.
                """).applyTo(target);
        assertEquals(1, result.size());
        assertTrue(result.contains(resource(result, "s"), property(result, "v"), "1"));
    }

    @Test
    void successfulApplicationLeavesTargetUntouchedAndKeepsPrefixes() {
        Model target = turtle("@prefix ex: <http://www.example.org/terms#>. <#s> ex:v \"1\".");
        Model result = parse(PREFIXES
                + "_:p a solid:InsertDeletePatch; solid:deletes { <#s> ex:v \"1\". }.")
                .applyTo(target);
        assertEquals(1, target.size(), "the input model is not mutated");
        assertEquals(0, result.size());
        assertEquals(EX, result.getNsPrefixURI("ex"), "prefix mappings carry over to the result");
    }

    @Test
    void nullTargetIsAProgrammingErrorNotBadInput() {
        // Error split: applying never signals BadInput; a null target is an NPE.
        N3Patch patch = parse(PREFIXES + "_:p a solid:InsertDeletePatch.");
        assertThrows(NullPointerException.class, () -> patch.applyTo(null));
    }
}
