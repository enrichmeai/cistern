package com.enrichmeai.cistern.core.rdf;

import com.enrichmeai.cistern.core.CisternException;
import com.enrichmeai.cistern.core.Representation;
import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.Triple;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static com.enrichmeai.cistern.core.rdf.N3PatchTestSupport.DOC;
import static com.enrichmeai.cistern.core.rdf.N3PatchTestSupport.EX_PREFIX;
import static com.enrichmeai.cistern.core.rdf.N3PatchTestSupport.PREFIXES;
import static com.enrichmeai.cistern.core.rdf.N3PatchTestSupport.SOLID_PREFIX;
import static com.enrichmeai.cistern.core.rdf.N3PatchTestSupport.parse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Grammar and constraint coverage for the N3 Patch subset defined by the Solid Protocol,
 * "Modifying Resources Using N3 Patches" (https://solidproject.org/TR/protocol#n3-patch).
 * Acceptance tests exercise the documented grammar; rejection tests assert that every
 * constraint violation and every N3 construct outside the subset is BadInput.
 */
class N3PatchParserTest {

    @Nested
    class Accepts {

        @Test
        void uriIdentifiedPatchResourceWithRelativeIriResolution() {
            // "A patch resource MUST be identified by a URI or blank node" (§n3-patch).
            N3Patch patch = parse(PREFIXES + """
                    <#update> a solid:InsertDeletePatch;
                      solid:inserts { <#me> ex:knows <other>. }.
                    """);
            Triple inserted = patch.inserts().get(0);
            assertEquals("https://pod.example/notes/doc#me", inserted.getSubject().getURI());
            assertEquals("https://pod.example/notes/other", inserted.getObject().getURI());
        }

        @Test
        void anonymousBlankNodePatchResource() {
            N3Patch patch = parse(PREFIXES + "[] a solid:InsertDeletePatch; solid:inserts { <#a> ex:b \"c\". }.");
            assertEquals(1, patch.inserts().size());
        }

        @Test
        void optionalSolidPatchTypeIsAllowedAlongsideInsertDeletePatch() {
            // "A patch resource MAY contain a triple ?patch rdf:type solid:Patch" (§n3-patch).
            N3Patch patch = parse(PREFIXES + """
                    _:p a solid:Patch, solid:InsertDeletePatch;
                      solid:inserts { <#a> ex:b "c". }.
                    """);
            assertEquals(1, patch.inserts().size());
        }

        @Test
        void absentFormulaeArePresumedEmpty() {
            // "When not present, they are presumed to be the empty formula {}" (§n3-patch).
            N3Patch patch = parse(PREFIXES + "_:p a solid:InsertDeletePatch.");
            assertTrue(patch.where().isEmpty());
            assertTrue(patch.deletes().isEmpty());
            assertTrue(patch.inserts().isEmpty());
        }

        @Test
        void explicitlyEmptyFormulae() {
            N3Patch patch = parse(PREFIXES
                    + "_:p a solid:InsertDeletePatch; solid:where {}; solid:inserts {}; solid:deletes {}.");
            assertTrue(patch.where().isEmpty());
            assertTrue(patch.deletes().isEmpty());
            assertTrue(patch.inserts().isEmpty());
        }

        @Test
        void sparqlStyleDirectives() {
            N3Patch patch = parse("""
                    PREFIX solid: <http://www.w3.org/ns/solid/terms#>
                    PREFIX ex: <http://www.example.org/terms#>
                    _:p a solid:InsertDeletePatch; solid:inserts { <#a> ex:b "c". }.
                    """);
            assertEquals(1, patch.inserts().size());
        }

        @Test
        void baseDirectiveChangesRelativeIriResolution() {
            N3Patch patch = parse(PREFIXES + """
                    @base <https://other.example/data/>.
                    _:p a solid:InsertDeletePatch; solid:inserts { <thing> ex:b "c". }.
                    """);
            assertEquals("https://other.example/data/thing",
                    patch.inserts().get(0).getSubject().getURI());
        }

        @Test
        void rdfTypeSpelledWithPrefixInsteadOfA() {
            N3Patch patch = parse(PREFIXES
                    + "@prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>.\n"
                    + "_:p rdf:type solid:InsertDeletePatch; solid:inserts { <#a> ex:b \"c\". }.");
            assertEquals(1, patch.inserts().size());
        }

        @Test
        void commentsAndBlankLinesAreIgnored() {
            N3Patch patch = parse("""
                    # an N3 Patch
                    @prefix solid: <http://www.w3.org/ns/solid/terms#>. # solid terms
                    @prefix ex: <http://www.example.org/terms#>.

                    # the patch resource
                    _:p a solid:InsertDeletePatch; # required type
                      solid:inserts { <#a> ex:b "c". }. # one insertion
                    """);
            assertEquals(1, patch.inserts().size());
        }

        @Test
        void literalForms() {
            N3Patch patch = parse(PREFIXES + """
                    @prefix xsd: <http://www.w3.org/2001/XMLSchema#>.
                    _:p a solid:InsertDeletePatch; solid:inserts {
                      <#s> ex:lang "hola"@es.
                      <#s> ex:typed "5"^^xsd:int.
                      <#s> ex:int 42.
                      <#s> ex:decimal 4.2.
                      <#s> ex:double 4.2e1.
                      <#s> ex:bool true.
                      <#s> ex:escaped "a\\"b\\nc".
                      <#s> ex:long \"\"\"multi
                    line\"\"\".
                      <#s> ex:single 'sq'.
                    }.
                    """);
            List<Triple> inserts = patch.inserts();
            assertEquals(9, inserts.size());
            assertEquals("es", inserts.get(0).getObject().getLiteralLanguage());
            assertEquals("http://www.w3.org/2001/XMLSchema#int",
                    inserts.get(1).getObject().getLiteralDatatypeURI());
            assertEquals(XSDDatatype.XSDinteger, inserts.get(2).getObject().getLiteralDatatype());
            assertEquals(XSDDatatype.XSDdecimal, inserts.get(3).getObject().getLiteralDatatype());
            assertEquals(XSDDatatype.XSDdouble, inserts.get(4).getObject().getLiteralDatatype());
            assertEquals(XSDDatatype.XSDboolean, inserts.get(5).getObject().getLiteralDatatype());
            assertEquals("a\"b\nc", inserts.get(6).getObject().getLiteralLexicalForm());
            assertEquals("multi\nline", inserts.get(7).getObject().getLiteralLexicalForm());
            assertEquals("sq", inserts.get(8).getObject().getLiteralLexicalForm());
        }

        @Test
        void predicateObjectListSugarInsideFormula() {
            N3Patch patch = parse(PREFIXES + """
                    _:p a solid:InsertDeletePatch;
                      solid:where { ?s ex:a "x"; ex:b "y", "z". }.
                    """);
            assertEquals(3, patch.where().size());
        }

        @Test
        void finalDotInsideFormulaIsOptional() {
            N3Patch patch = parse(PREFIXES
                    + "_:p a solid:InsertDeletePatch; solid:where { ?s ex:a \"x\" }.");
            assertEquals(1, patch.where().size());
        }

        @Test
        void multipleStatementsInsideFormula() {
            N3Patch patch = parse(PREFIXES + """
                    _:p a solid:InsertDeletePatch;
                      solid:where { ?s ex:a "x". ?s ex:b ?v. }.
                    """);
            assertEquals(2, patch.where().size());
        }

        @Test
        void variableInPredicatePosition() {
            // Formulae consist of "triples and/or triple patterns [SPARQL11-QUERY]" (§n3-patch);
            // SPARQL triple patterns allow variables in predicate position.
            N3Patch patch = parse(PREFIXES
                    + "_:p a solid:InsertDeletePatch; solid:where { <#s> ?p \"v\". }.");
            assertTrue(patch.where().get(0).getPredicate().isVariable());
        }

        @Test
        void defaultEmptyPrefix() {
            N3Patch patch = parse(SOLID_PREFIX + "@prefix : <http://www.example.org/terms#>.\n"
                    + "_:p a solid:InsertDeletePatch; solid:inserts { :s :p \"v\". }.");
            assertEquals("http://www.example.org/terms#s",
                    patch.inserts().get(0).getSubject().getURI());
        }

        @Test
        void duplicateTypeTriplesCollapseOntoOnePatchResource() {
            N3Patch patch = parse(PREFIXES
                    + "_:p a solid:InsertDeletePatch. _:p a solid:InsertDeletePatch; solid:inserts { <#a> ex:b \"c\". }.");
            assertEquals(1, patch.inserts().size());
        }

        @Test
        void contentTypeParametersAreTolerated() {
            byte[] data = (PREFIXES + "_:p a solid:InsertDeletePatch.").getBytes(StandardCharsets.UTF_8);
            N3Patch patch = N3Patch.parse(new Representation("text/n3; charset=utf-8", data), DOC);
            assertTrue(patch.inserts().isEmpty());
        }
    }

    /**
     * Lexical/grammar errors and unparseable entities → {@link CisternException.BadInput}
     * (400). Note {@code UnprocessableEntity} is not a subtype of {@code BadInput}, so these
     * assertions genuinely enforce the split.
     */
    @Nested
    class RejectsAsBadInput {

        private void assertBadInput(String document) {
            assertThrows(CisternException.BadInput.class, () -> parse(document));
        }

        @Test
        void wrongContentType() {
            byte[] data = (PREFIXES + "_:p a solid:InsertDeletePatch.").getBytes(StandardCharsets.UTF_8);
            assertThrows(CisternException.BadInput.class,
                    () -> N3Patch.parse(new Representation(Representation.TURTLE, data), DOC));
        }

        @Test
        void nullArguments() {
            byte[] data = "x".getBytes(StandardCharsets.UTF_8);
            assertThrows(CisternException.BadInput.class, () -> N3Patch.parse(null, DOC));
            assertThrows(CisternException.BadInput.class,
                    () -> N3Patch.parse(new Representation(N3Patch.MEDIA_TYPE, null), DOC));
            assertThrows(CisternException.BadInput.class,
                    () -> N3Patch.parse(new Representation(N3Patch.MEDIA_TYPE, data), null));
        }

        @Test
        void invalidUtf8Bytes() {
            byte[] invalid = {'_', ':', 'p', ' ', (byte) 0xC3, (byte) 0x28};
            assertThrows(CisternException.BadInput.class,
                    () -> N3Patch.parse(new Representation(N3Patch.MEDIA_TYPE, invalid), DOC));
        }

        @Test
        void variablesOutsideFormulae() {
            assertBadInput(PREFIXES + "?p a solid:InsertDeletePatch.");
            assertBadInput(PREFIXES + "_:p a solid:InsertDeletePatch; solid:inserts ?x.");
        }

        @Test
        void formulaInIllegalPositionsAtDocumentLevel() {
            assertBadInput(PREFIXES + "{ ?a ex:b \"c\". } a solid:InsertDeletePatch.");
            assertBadInput(PREFIXES + "_:p { ?a ex:b \"c\". } solid:InsertDeletePatch.");
        }

        @Test
        void unsupportedN3ConstructsAtDocumentLevel() {
            // Outside any formula the "only triples and/or triple patterns" constraint does
            // not apply, so these are simply not patch documents → 400.
            assertBadInput(PREFIXES + "_:p => _:q.");
            assertBadInput(PREFIXES + "_:p <= _:q.");
            assertBadInput(PREFIXES + "_:p = _:q.");
            assertBadInput("@forAll ?x. " + PREFIXES + "_:p a solid:InsertDeletePatch.");
            assertBadInput("@forSome _:x. " + PREFIXES + "_:p a solid:InsertDeletePatch.");
            assertBadInput(PREFIXES + "[ ex:a \"b\" ] a solid:InsertDeletePatch.");
        }

        @Test
        void undeclaredPrefix() {
            assertBadInput(SOLID_PREFIX + "_:p a solid:InsertDeletePatch; solid:inserts { <#a> ex:b \"c\". }.");
        }

        @Test
        void literalAsSubjectAtDocumentLevel() {
            assertBadInput(PREFIXES + "\"lit\" a solid:InsertDeletePatch.");
            assertBadInput(PREFIXES + "42 a solid:InsertDeletePatch.");
        }

        @Test
        void unknownDirectiveInsideFormulaIsGarbageNotAnN3Construct() {
            assertBadInput(PREFIXES + "_:p a solid:InsertDeletePatch;"
                    + " solid:inserts { @nonsense x. <#a> ex:b \"c\". }.");
        }

        @Test
        void malformedSyntax() {
            assertBadInput(PREFIXES + "_:p a solid:InsertDeletePatch"); // missing final '.'
            assertBadInput(PREFIXES + "_:p a solid:InsertDeletePatch; solid:inserts { <#a> ex:b \"unterminated. }.");
            assertBadInput(PREFIXES + "_:p a <http://example.org/unterminated ; solid:inserts {}.");
            assertBadInput(PREFIXES + "_:p a solid:InsertDeletePatch; solid:inserts { <#a> ex:b \"\\uZZZZ\". }.");
            assertBadInput("@PREFIX solid: <http://www.w3.org/ns/solid/terms#>.\n_:p a solid:InsertDeletePatch.");
            assertBadInput(EX_PREFIX + "@prefix solid <http://www.w3.org/ns/solid/terms#>.\n"
                    + "_:p a solid:InsertDeletePatch.");
        }

    }

    /**
     * Documents that are well-formed N3 but breach the spec's enumerated patch constraints →
     * {@link CisternException.UnprocessableEntity} (422). §n3-patch: "Servers MUST respond
     * with a 422 status code [RFC4918] if a patch document does not satisfy all of the above
     * constraints."
     */
    @Nested
    class RejectsAsUnprocessableEntity {

        private void assertUnprocessable(String document) {
            assertThrows(CisternException.UnprocessableEntity.class, () -> parse(document));
        }

        @Test
        void documentWithoutPatchResource() {
            // "A patch document MUST contain one or more patch resources" (§n3-patch).
            // An empty or directives-only document is well-formed N3, hence 422 not 400.
            assertUnprocessable("");
            assertUnprocessable("   \n\t\n");
            assertUnprocessable("# just a comment\n");
            assertUnprocessable(PREFIXES);
        }

        @Test
        void missingInsertDeletePatchType() {
            // "A patch resource MUST contain a triple ?patch rdf:type solid:InsertDeletePatch" (§n3-patch).
            assertUnprocessable(PREFIXES + "_:p a solid:Patch; solid:inserts { <#a> ex:b \"c\". }.");
            assertUnprocessable(PREFIXES + "_:p solid:inserts { <#a> ex:b \"c\". }.");
        }

        @Test
        void multiplePatchResources() {
            // "The patch document MUST contain exactly one patch resource" (§n3-patch).
            assertUnprocessable(PREFIXES
                    + "_:p a solid:InsertDeletePatch. _:q a solid:InsertDeletePatch.");
        }

        @Test
        void duplicateFormulaTriples() {
            // "A patch resource MUST contain at most one triple of the form
            //  ?patch solid:inserts ?insertions" (§n3-patch) — and likewise deletes/where.
            assertUnprocessable(PREFIXES + "_:p a solid:InsertDeletePatch;"
                    + " solid:inserts { <#a> ex:b \"c\". }; solid:inserts { <#d> ex:e \"f\". }.");
            assertUnprocessable(PREFIXES + "_:p a solid:InsertDeletePatch;"
                    + " solid:where { ?a ex:b \"c\". }; solid:where { ?a ex:e \"f\". }.");
            assertUnprocessable(PREFIXES + "_:p a solid:InsertDeletePatch;"
                    + " solid:deletes { <#a> ex:b \"c\". }; solid:deletes { <#d> ex:e \"f\". }.");
        }

        @Test
        void formulaPredicateObjectMustBeFormula() {
            // "?deletions, ?insertions, and ?conditions MUST be non-nested cited formulae" (§n3-patch).
            assertUnprocessable(PREFIXES + "_:p a solid:InsertDeletePatch; solid:inserts <#a>.");
            assertUnprocessable(PREFIXES + "_:p a solid:InsertDeletePatch; solid:where \"x\".");
        }

        @Test
        void unexpectedPredicateOnPatchResource() {
            assertUnprocessable(PREFIXES + "_:p a solid:InsertDeletePatch; ex:comment \"hi\".");
        }

        @Test
        void extraneousTripleOutsideThePatchResource() {
            assertUnprocessable(PREFIXES + "_:p a solid:InsertDeletePatch. <#x> ex:comment \"hi\".");
        }

        @Test
        void rdfTypeOfAnotherClass() {
            assertUnprocessable(PREFIXES + "_:p a ex:Thing, solid:InsertDeletePatch.");
        }

        @Test
        void insertsVariableNotOccurringInWhere() {
            // "The ?insertions and ?deletions formulae MUST NOT contain variables that do
            //  not occur in the ?conditions formula" (§n3-patch).
            assertUnprocessable(PREFIXES + "_:p a solid:InsertDeletePatch; solid:inserts { ?x ex:b \"c\". }.");
            assertUnprocessable(PREFIXES + "_:p a solid:InsertDeletePatch;"
                    + " solid:where { ?y ex:a \"v\". }; solid:inserts { ?x ex:b ?y. }.");
        }

        @Test
        void deletesVariableNotOccurringInWhere() {
            assertUnprocessable(PREFIXES + "_:p a solid:InsertDeletePatch; solid:deletes { ?x ex:b \"c\". }.");
        }

        @Test
        void blankNodesInInsertsOrDeletes() {
            // "The ?insertions and ?deletions formulae MUST NOT contain blank nodes" (§n3-patch).
            assertUnprocessable(PREFIXES + "_:p a solid:InsertDeletePatch; solid:inserts { <#a> ex:b _:x. }.");
            assertUnprocessable(PREFIXES + "_:p a solid:InsertDeletePatch; solid:deletes { _:x ex:b \"c\". }.");
            assertUnprocessable(PREFIXES + "_:p a solid:InsertDeletePatch; solid:inserts { [] ex:b \"c\". }.");
        }

        @Test
        void blankNodesInWhere() {
            // Deliberate limitation, NOT a spec constraint: the spec forbids blank nodes only
            // in inserts/deletes, so this document is spec-well-formed. Cistern refuses it as
            // 422 ("well-formed but unprocessable") because the spec's mapping algorithm is
            // defined over variables only. Revisit with harness evidence — issue #57.
            assertUnprocessable(PREFIXES + "_:p a solid:InsertDeletePatch; solid:where { _:x ex:b ?v. }.");
            assertUnprocessable(PREFIXES + "_:p a solid:InsertDeletePatch; solid:where { <#a> ex:b _:x. }.");
        }

        // ---- recognized N3 content inside a formula that is not a triple/triple pattern ----
        // "?deletions, ?insertions and ?conditions MUST be non-nested cited formulae [N3]
        //  consisting only of triples and/or triple patterns [SPARQL11-QUERY]" (§n3-patch),
        // and a document breaching a listed constraint is answered with 422, not 400.

        @Test
        void nestedFormulaInsideFormula() {
            assertUnprocessable(PREFIXES + "_:p a solid:InsertDeletePatch;"
                    + " solid:where { ?a ex:b { ?c ex:d \"e\". }. }.");
            assertUnprocessable(PREFIXES + "_:p a solid:InsertDeletePatch;"
                    + " solid:where { { ?c ex:d \"e\". } ex:b ?a. }.");
        }

        @Test
        void collectionInsideFormula() {
            assertUnprocessable(PREFIXES + "_:p a solid:InsertDeletePatch;"
                    + " solid:inserts { <#a> ex:list ( \"1\" \"2\" ). }.");
            assertUnprocessable(PREFIXES + "_:p a solid:InsertDeletePatch;"
                    + " solid:where { ( \"1\" ) ex:b ?v. }.");
        }

        @Test
        void implicationInsideFormula() {
            assertUnprocessable(PREFIXES + "_:p a solid:InsertDeletePatch;"
                    + " solid:where { ?a => ?b. }.");
            assertUnprocessable(PREFIXES + "_:p a solid:InsertDeletePatch;"
                    + " solid:where { ?a = ?b. }.");
            assertUnprocessable(PREFIXES + "_:p a solid:InsertDeletePatch;"
                    + " solid:where { ?a <= ?b. }.");
        }

        @Test
        void n3DeclarationsAndQuantifiersInsideFormula() {
            assertUnprocessable(PREFIXES + "_:p a solid:InsertDeletePatch;"
                    + " solid:inserts { @prefix x: <http://x.example/>. <#a> ex:b \"c\". }.");
            assertUnprocessable(PREFIXES + "_:p a solid:InsertDeletePatch;"
                    + " solid:where { @forAll ?x. ?x ex:b \"c\". }.");
            assertUnprocessable(PREFIXES + "_:p a solid:InsertDeletePatch;"
                    + " solid:where { @forSome _:x. ?y ex:b \"c\". }.");
        }

        @Test
        void blankNodePropertyListInsideFormula() {
            assertUnprocessable(PREFIXES + "_:p a solid:InsertDeletePatch;"
                    + " solid:where { [ ex:a \"b\" ] ex:c ?v. }.");
            assertUnprocessable(PREFIXES + "_:p a solid:InsertDeletePatch;"
                    + " solid:where { ?s ex:c [ ex:a \"b\" ]. }.");
        }

        @Test
        void termsInRdfInvalidPositionsInsideFormula() {
            assertUnprocessable(PREFIXES + "_:p a solid:InsertDeletePatch;"
                    + " solid:where { \"lit\" ex:a ?v. }.");
            assertUnprocessable(PREFIXES + "_:p a solid:InsertDeletePatch;"
                    + " solid:where { 42 ex:a ?v. }.");
            assertUnprocessable(PREFIXES + "_:p a solid:InsertDeletePatch;"
                    + " solid:where { ?s \"lit\" ?v. }.");
            assertUnprocessable(PREFIXES + "_:p a solid:InsertDeletePatch;"
                    + " solid:where { ?s _:b ?v. }.");
        }
    }

    @Test
    void variableNodesAreJenaVariables() {
        N3Patch patch = parse(PREFIXES
                + "_:p a solid:InsertDeletePatch; solid:where { ?person ex:familyName \"Garcia\". }.");
        Node subject = patch.where().get(0).getSubject();
        assertTrue(subject.isVariable());
        assertEquals("person", subject.getName());
    }
}
