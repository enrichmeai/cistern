package com.enrichmeai.cistern.core.rdf;

import org.apache.jena.datatypes.RDFDatatype;
import org.apache.jena.datatypes.TypeMapper;
import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;

import java.util.Random;

/**
 * Deterministic, seeded generator of varied Jena models for round-trip property tests.
 * Plain {@link java.util.Random} with a fixed seed — no property-based-testing library —
 * so a failing case is reproducible from (seed, index) alone.
 *
 * <p>Coverage: absolute IRIs, blank nodes (as subjects and objects), plain string
 * literals (unicode, quotes, newlines, empty string), language-tagged strings, typed
 * literals (xsd:integer, xsd:boolean, xsd:double, xsd:dateTime, and a custom datatype
 * IRI), RDF lists (including the empty list, rdf:nil), and the empty graph (index 0).
 *
 * <p>Numeric lexical forms are XSD-canonical on purpose: JSON-LD writers may convert
 * canonical literals to native JSON numbers/booleans, and only canonical forms are
 * guaranteed to survive that conversion isomorphically.
 */
final class RdfModelGenerator {

    private static final String SUBJECT_NS = "https://data.example/things/";
    private static final String PREDICATE_NS = "https://vocab.example/terms/";
    private static final String[] PREDICATES = {"name", "knows", "count", "tag", "items", "attachedTo", "ratio"};
    private static final String[] STRINGS = {
            "plain ascii",
            "unicode héllo wörld ✓ — π ≈ 3.14159",
            "say \"hi\" and \\ escape",
            "line one\nline two\ttabbed",
            ""
    };
    private static final String[] LANGUAGE_TAGS = {"en", "fr", "de"};
    private static final String[] CANONICAL_DOUBLES = {"4.2E0", "-2.75E2", "1.0E0", "3.14159E0"};
    private static final RDFDatatype CUSTOM_DATATYPE =
            TypeMapper.getInstance().getSafeTypeByName("https://vocab.example/datatype/point2d");

    private final Random random;

    RdfModelGenerator(long seed) {
        this.random = new Random(seed);
    }

    /** Model number {@code index} in the deterministic sequence. Index 0 is the empty graph. */
    Model next(int index) {
        Model model = ModelFactory.createDefaultModel();
        if (index == 0) {
            return model;
        }
        int tripleCount = 1 + random.nextInt(20);
        for (int i = 0; i < tripleCount; i++) {
            model.add(randomSubject(model), randomPredicate(model), randomObject(model));
        }
        return model;
    }

    private Resource randomSubject(Model model) {
        if (random.nextInt(4) == 0) {
            return model.createResource(); // blank node subject
        }
        return model.createResource(SUBJECT_NS + "t" + random.nextInt(10));
    }

    private Property randomPredicate(Model model) {
        return model.createProperty(PREDICATE_NS + PREDICATES[random.nextInt(PREDICATES.length)]);
    }

    private RDFNode randomObject(Model model) {
        return switch (random.nextInt(10)) {
            case 0 -> model.createResource(SUBJECT_NS + "o" + random.nextInt(10));
            case 1 -> model.createResource(); // blank node object
            case 2 -> model.createLiteral(STRINGS[random.nextInt(STRINGS.length)]);
            case 3 -> model.createLiteral("tagged " + random.nextInt(100),
                    LANGUAGE_TAGS[random.nextInt(LANGUAGE_TAGS.length)]);
            case 4 -> model.createTypedLiteral(
                    Integer.toString(random.nextInt(2001) - 1000), XSDDatatype.XSDinteger);
            case 5 -> model.createTypedLiteral(Boolean.toString(random.nextBoolean()), XSDDatatype.XSDboolean);
            case 6 -> model.createTypedLiteral(
                    CANONICAL_DOUBLES[random.nextInt(CANONICAL_DOUBLES.length)], XSDDatatype.XSDdouble);
            case 7 -> model.createTypedLiteral(
                    "2026-07-%02dT%02d:%02d:%02dZ".formatted(
                            1 + random.nextInt(28), random.nextInt(24), random.nextInt(60), random.nextInt(60)),
                    XSDDatatype.XSDdateTime);
            case 8 -> model.createTypedLiteral(
                    random.nextInt(100) + "," + random.nextInt(100), CUSTOM_DATATYPE);
            default -> randomList(model);
        };
    }

    private RDFNode randomList(Model model) {
        int size = random.nextInt(4); // 0 (rdf:nil) to 3 elements
        RDFNode[] items = new RDFNode[size];
        for (int i = 0; i < size; i++) {
            items[i] = random.nextBoolean()
                    ? model.createResource(SUBJECT_NS + "li" + random.nextInt(10))
                    : model.createLiteral("item " + random.nextInt(100));
        }
        return model.createList(items);
    }
}
