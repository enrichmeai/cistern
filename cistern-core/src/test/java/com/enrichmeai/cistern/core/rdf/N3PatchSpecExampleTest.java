package com.enrichmeai.cistern.core.rdf;

import com.enrichmeai.cistern.core.CisternException;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
import org.junit.jupiter.api.Test;

import static com.enrichmeai.cistern.core.rdf.N3PatchTestSupport.parse;
import static com.enrichmeai.cistern.core.rdf.N3PatchTestSupport.turtle;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Solid Protocol's N3 Patch example, verbatim (T1.5 DoD: "the patch examples from the
 * Solid Protocol spec text pass"). The section "Modifying Resources Using N3 Patches"
 * contains exactly one example.
 */
class N3PatchSpecExampleTest {

    /**
     * Solid Protocol, "Modifying Resources Using N3 Patches", example "Applying an N3 patch"
     * (https://solidproject.org/TR/protocol#n3-patch-example), quoted verbatim:
     *
     * <pre>
     * &#64;prefix solid: &lt;http://www.w3.org/ns/solid/terms#&gt;.
     * &#64;prefix ex: &lt;http://www.example.org/terms#&gt;.
     *
     * _:rename a solid:InsertDeletePatch;
     *   solid:where   { ?person ex:familyName "Garcia". };
     *   solid:inserts { ?person ex:givenName "Alex". };
     *   solid:deletes { ?person ex:givenName "Claudia". }.
     * </pre>
     *
     * Spec text accompanying it: "This N3 Patch instructs to rename Claudia Garcia into
     * Alex Garcia, on the condition that no other Garcia family members are present in the
     * target RDF document."
     */
    private static final String SPEC_EXAMPLE = """
            @prefix solid: <http://www.w3.org/ns/solid/terms#>.
            @prefix ex: <http://www.example.org/terms#>.

            _:rename a solid:InsertDeletePatch;
              solid:where   { ?person ex:familyName "Garcia". };
              solid:inserts { ?person ex:givenName "Alex". };
              solid:deletes { ?person ex:givenName "Claudia". }.
            """;

    private static final String EX = "http://www.example.org/terms#";

    @Test
    void specExampleParsesIntoOneTriplePerFormula() {
        N3Patch patch = parse(SPEC_EXAMPLE);
        assertEquals(1, patch.where().size());
        assertEquals(1, patch.inserts().size());
        assertEquals(1, patch.deletes().size());
    }

    @Test
    void renamesClaudiaGarciaIntoAlexGarcia() {
        Model target = turtle("""
                @prefix ex: <http://www.example.org/terms#>.
                <#claudia> ex:familyName "Garcia" ;
                           ex:givenName "Claudia" .
                """);

        Model result = parse(SPEC_EXAMPLE).applyTo(target);

        Resource claudia = result.createResource("https://pod.example/notes/doc#claudia");
        Property familyName = result.createProperty(EX, "familyName");
        Property givenName = result.createProperty(EX, "givenName");
        assertTrue(result.contains(claudia, familyName, "Garcia"), "family name is untouched");
        assertTrue(result.contains(claudia, givenName, "Alex"), "solid:inserts adds the new given name");
        assertFalse(result.contains(claudia, givenName, "Claudia"), "solid:deletes removes the old given name");
        assertEquals(2, result.size());
    }

    /**
     * "on the condition that no other Garcia family members are present" — with a second
     * Garcia, multiple variable mappings satisfy the where formula, and the spec mandates:
     * "If no such mapping exists, or if multiple mappings exist, the server MUST respond
     * with a 409 status code" (§n3-patch, server-patch-n3-semantics-no-mapping).
     */
    @Test
    void secondGarciaFamilyMemberIsAConflict() {
        Model target = turtle("""
                @prefix ex: <http://www.example.org/terms#>.
                <#claudia> ex:familyName "Garcia" ; ex:givenName "Claudia" .
                <#maria>   ex:familyName "Garcia" ; ex:givenName "Maria" .
                """);

        CisternException.Conflict conflict =
                assertThrows(CisternException.Conflict.class, () -> parse(SPEC_EXAMPLE).applyTo(target));
        assertTrue(conflict.getMessage().contains("multiple"), conflict.getMessage());
    }

    /** No Garcia at all: no variable mapping exists, hence 409 semantics (same spec sentence). */
    @Test
    void absentGarciaIsAConflict() {
        Model target = turtle("""
                @prefix ex: <http://www.example.org/terms#>.
                <#bob> ex:familyName "Smith" .
                """);

        CisternException.Conflict conflict =
                assertThrows(CisternException.Conflict.class, () -> parse(SPEC_EXAMPLE).applyTo(target));
        assertTrue(conflict.getMessage().contains("no variable mapping"), conflict.getMessage());
    }

    /**
     * A Garcia exists (the where formula maps), but the mapped solid:deletes triple is
     * absent: "If the set of triples resulting from ?deletions is non-empty and the dataset
     * does not contain all of these triples, the server MUST respond with a 409 status
     * code" (§n3-patch, server-patch-n3-semantics-deletions-non-empty-all-triples).
     */
    @Test
    void garciaWithoutGivenNameClaudiaIsAConflict() {
        Model target = turtle("""
                @prefix ex: <http://www.example.org/terms#>.
                <#garcia> ex:familyName "Garcia" .
                """);

        CisternException.Conflict conflict =
                assertThrows(CisternException.Conflict.class, () -> parse(SPEC_EXAMPLE).applyTo(target));
        assertTrue(conflict.getMessage().contains("solid:deletes"), conflict.getMessage());
    }
}
