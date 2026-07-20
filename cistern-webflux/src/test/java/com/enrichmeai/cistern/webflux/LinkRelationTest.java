package com.enrichmeai.cistern.webflux;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The property that keeps {@link LinkRelation}'s emit side and parse side in step.
 *
 * <p>The two are used together — {@code HttpConstants.link} writes {@link LinkRelation#value()}
 * into a header and {@link LinkHeader} matches an incoming {@code rel} with
 * {@link LinkRelation#isNamedBy} — so a relation that does not recognise its own emitted spelling
 * is a silent round-trip failure. That is not hypothetical: comparison folds case, and until T2.9
 * every constant happened to be lower case already, so a camel-cased extension relation type like
 * {@code solid:storageDescription} would have been emitted correctly and then never matched.
 */
class LinkRelationTest {

    /**
     * RFC 8288 §2.1.1 for registered names and §2.1.2 for extension types both require
     * case-insensitive comparison, so every row must match itself in any case.
     */
    @ParameterizedTest
    @EnumSource(LinkRelation.class)
    @DisplayName("every relation recognises its own emitted spelling, in any case")
    void everyRelationNamesItself(LinkRelation relation) {
        for (String spelling : List.of(relation.value(),
                relation.value().toLowerCase(Locale.ROOT),
                relation.value().toUpperCase(Locale.ROOT))) {
            assertTrue(relation.isNamedBy(spelling),
                    relation + " must recognise the spelling '" + spelling + "'");
        }
    }

    /** RFC 8288 §3.3: {@code rel} may carry "a whitespace-separated list of relation types". */
    @ParameterizedTest
    @EnumSource(LinkRelation.class)
    @DisplayName("a relation is found among a whitespace-separated list")
    void aRelationIsFoundInAList(LinkRelation relation) {
        assertTrue(relation.isNamedBy("https://vocab.example/other " + relation.value()));
        assertTrue(relation.isNamedBy(relation.value() + " https://vocab.example/other"));
    }

    @ParameterizedTest
    @EnumSource(LinkRelation.class)
    @DisplayName("no relation is named by a different one")
    void relationsDoNotCollide(LinkRelation relation) {
        for (LinkRelation other : LinkRelation.values()) {
            if (other != relation) {
                assertFalse(relation.isNamedBy(other.value()),
                        relation + " must not be named by " + other);
            }
        }
    }

    /**
     * The round trip through the production writer and parser: what
     * {@code HttpConstants.link} emits is what {@link LinkHeader} reads back, for both kinds of
     * relation type. An extension relation type is a URI containing characters that are not
     * {@code tchar}, so an unquoted {@code rel} would not survive this.
     */
    @ParameterizedTest
    @EnumSource(LinkRelation.class)
    @DisplayName("an emitted Link parses back to the same target and relation")
    void emittedLinksParseBack(LinkRelation relation) {
        String target = "https://pod.example/some/resource";

        List<String> targets = LinkHeader.targetsWithRelation(
                List.of(HttpConstants.link(target, relation)), relation);

        assertTrue(targets.equals(List.of(target)),
                "round trip through " + relation + " produced " + targets);
    }

    /**
     * Several link-values in one field line, which is how a {@code Link} field carrying both a
     * type link and the storage-description link may reach a client (RFC 8288 §3 — the field is a
     * comma-separated list, and a proxy is free to fold Cistern's separate field lines into one).
     */
    @Test
    @DisplayName("a folded field line still yields each relation separately")
    void aFoldedFieldLineIsReadCorrectly() {
        String folded = String.join(", ",
                HttpConstants.linkType("http://www.w3.org/ns/pim/space#Storage"),
                HttpConstants.link("https://pod.example/.well-known/solid",
                        LinkRelation.STORAGE_DESCRIPTION));

        assertTrue(LinkHeader.targetsWithRelation(List.of(folded), LinkRelation.TYPE)
                .equals(List.of("http://www.w3.org/ns/pim/space#Storage")));
        assertTrue(LinkHeader.targetsWithRelation(List.of(folded), LinkRelation.STORAGE_DESCRIPTION)
                .equals(List.of("https://pod.example/.well-known/solid")));
    }
}
