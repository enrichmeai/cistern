package com.enrichmeai.cistern.webflux;

import com.enrichmeai.cistern.core.ldp.InteractionModel;
import com.enrichmeai.cistern.core.ldp.Ldp;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LinkHeader} against the shapes RFC 8288 §3 actually allows.
 *
 * <p>Each case here is one reason a substring search for the container IRI would give the wrong
 * answer, which is why T2.3 parses the field instead: commas inside a target or a quoted
 * parameter, several link-values in one field, several field lines, unquoted parameter values,
 * case variation, and relation lists.
 */
class LinkHeaderTest {

    private static final String BASIC_CONTAINER = Ldp.BASIC_CONTAINER.getURI();
    private static final String RESOURCE = Ldp.RESOURCE.getURI();

    private static List<String> types(String... fieldValues) {
        return LinkHeader.targetsWithRelation(List.of(fieldValues), LinkRelation.TYPE);
    }

    private static InteractionModel model(String... fieldValues) {
        return InteractionModel.forTypeIris(types(fieldValues));
    }

    // ---------------------------------------------------------------- the ordinary cases

    @Test
    @DisplayName("the header a client actually sends to make a container")
    void readsASingleTypeLink() {
        assertEquals(List.of(BASIC_CONTAINER), types("<" + BASIC_CONTAINER + ">; rel=\"type\""));
        assertEquals(InteractionModel.BASIC_CONTAINER,
                model("<" + BASIC_CONTAINER + ">; rel=\"type\""));
    }

    @Test
    @DisplayName("no Link at all means the default model")
    void defaultsWithNoHeader() {
        assertEquals(List.of(), types());
        assertEquals(InteractionModel.RESOURCE, model());
    }

    /** RFC 8288 §3: a parameter value is a {@code token / quoted-string} — both are the same request. */
    @Test
    @DisplayName("rel=type and rel=\"type\" are the same relation")
    void acceptsAnUnquotedParameterValue() {
        assertEquals(List.of(BASIC_CONTAINER), types("<" + BASIC_CONTAINER + ">; rel=type"));
    }

    /** Parameter names and relation types are compared case-insensitively (RFC 8288 §3, §3.3). */
    @Test
    @DisplayName("REL=TYPE names the same relation")
    void isCaseInsensitive() {
        assertEquals(List.of(BASIC_CONTAINER), types("<" + BASIC_CONTAINER + ">; REL=\"TYPE\""));
    }

    /** RFC 8288 §3.3: rel carries "a whitespace-separated list of relation types". */
    @Test
    @DisplayName("a relation list containing type counts as type")
    void readsARelationList() {
        assertEquals(List.of(BASIC_CONTAINER),
                types("<" + BASIC_CONTAINER + ">; rel=\"describedby type\""));
    }

    // ---------------------------------------------------------------- lists

    @Test
    @DisplayName("several link-values in one field are all read")
    void readsSeveralValuesInOneField() {
        List<String> targets = types(
                "<" + RESOURCE + ">; rel=\"type\", <" + BASIC_CONTAINER + ">; rel=\"type\"");

        assertEquals(List.of(RESOURCE, BASIC_CONTAINER), targets);
        assertEquals(InteractionModel.BASIC_CONTAINER, model(
                "<" + RESOURCE + ">; rel=\"type\", <" + BASIC_CONTAINER + ">; rel=\"type\""),
                "the most specific requested model wins");
    }

    @Test
    @DisplayName("several Link field lines are all read")
    void readsSeveralFieldLines() {
        assertEquals(InteractionModel.BASIC_CONTAINER,
                model("<" + RESOURCE + ">; rel=\"type\"",
                        "<" + BASIC_CONTAINER + ">; rel=\"type\""));
    }

    // ---------------------------------------------------------------- why substring matching fails

    /**
     * A comma inside the URI-Reference belongs to the target, not to the list. Splitting the
     * field on commas would produce two broken values here.
     */
    @Test
    @DisplayName("a comma inside <> does not split the list")
    void keepsCommasInsideATarget() {
        assertEquals(List.of("https://vocab.example/a,b"),
                types("<https://vocab.example/a,b>; rel=\"type\""));
    }

    /** A comma inside a quoted parameter value is data, and so is a semicolon. */
    @Test
    @DisplayName("a comma or semicolon inside a quoted parameter is data")
    void keepsCommasInsideAQuotedParameter() {
        List<String> targets = types(
                "<" + RESOURCE + ">; title=\"a, b; c\"; rel=\"type\", <" + BASIC_CONTAINER
                        + ">; rel=\"type\"");

        assertEquals(List.of(RESOURCE, BASIC_CONTAINER), targets);
    }

    /**
     * The case that matters most for T2.3: the container's IRI appears in the header as a
     * <em>value</em> of some other parameter, and a substring search would wrongly create a
     * container.
     */
    @Test
    @DisplayName("the container IRI in a non-type link does not request a container")
    void ignoresTheIriUnderAnotherRelation() {
        assertEquals(InteractionModel.RESOURCE,
                model("<" + BASIC_CONTAINER + ">; rel=\"describedby\""));
        assertEquals(InteractionModel.RESOURCE,
                model("<https://vocab.example/Note>; rel=\"type\"; title=\"" + BASIC_CONTAINER + "\""));
    }

    @Test
    @DisplayName("a link with no rel parameter requests nothing")
    void ignoresALinkWithNoRelation() {
        assertEquals(List.of(), types("<" + BASIC_CONTAINER + ">"));
        assertEquals(List.of(), types("<" + BASIC_CONTAINER + ">; title=\"x\""));
    }

    // ---------------------------------------------------------------- tolerance

    /**
     * A malformed value costs the values around it nothing, and never costs the request: a POST
     * whose Link cannot be parsed still creates something, and Location says what.
     */
    @Test
    @DisplayName("garbage is skipped, and the readable values survive")
    void toleratesMalformedValues() {
        assertEquals(List.of(BASIC_CONTAINER),
                types("not-a-link, <" + BASIC_CONTAINER + ">; rel=\"type\""));
        assertEquals(List.of(BASIC_CONTAINER),
                types("<" + BASIC_CONTAINER + ">; rel=\"type\", <unterminated"));
    }

    @Test
    @DisplayName("nothing in a degenerate header throws")
    void toleratesDegenerateInput() {
        for (String degenerate : new String[]{"", " ", ",", ";", "<>", "<x>;", "<x>; rel",
                "<x>; rel=", "<x>;;;", "<x>; rel=\"unterminated"}) {
            assertTrue(types(degenerate).size() <= 1, "must not throw or invent values: " + degenerate);
        }
    }

    /** An empty target is not a type IRI Cistern knows, so it changes nothing. */
    @Test
    @DisplayName("an empty target does not request a container")
    void ignoresAnEmptyTarget() {
        assertEquals(InteractionModel.RESOURCE, model("<>; rel=\"type\""));
    }

    // ---------------------------------------------------------------- what Cistern emits

    /**
     * The parser must read Cistern's own output: {@link HttpConstants#linkType} builds the type
     * links T2.1 and T2.2 send, and a round trip proves the emitting and parsing sides agree on
     * the relation name rather than each holding their own copy of it.
     */
    @Test
    @DisplayName("what this server emits is what this parser reads")
    void roundTripsCisternsOwnLinkValues() {
        String emitted = HttpConstants.linkType(RESOURCE) + ", "
                + HttpConstants.linkType(BASIC_CONTAINER);

        assertEquals(List.of(RESOURCE, BASIC_CONTAINER), types(emitted));
    }

    // ---------------------------------------------------------------- parameters

    @Test
    @DisplayName("parameters are exposed with quotes removed and escapes resolved")
    void parsesParameters() {
        List<LinkHeader.Value> values =
                LinkHeader.parse(List.of("<https://x.example/>; title=\"a \\\"quoted\\\" word\"; rel=type"));

        assertEquals(1, values.size());
        assertEquals("https://x.example/", values.get(0).target());
        assertEquals(List.of(new LinkHeader.Parameter("title", "a \"quoted\" word"),
                        new LinkHeader.Parameter("rel", "type")),
                values.get(0).parameters());
    }
}
