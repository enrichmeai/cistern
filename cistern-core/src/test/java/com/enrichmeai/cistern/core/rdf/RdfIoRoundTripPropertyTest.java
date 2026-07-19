package com.enrichmeai.cistern.core.rdf;

import com.enrichmeai.cistern.core.Representation;
import com.enrichmeai.cistern.core.ResourceIdentifier;
import org.apache.jena.rdf.model.Model;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.net.URI;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round-trip property tests (T1.1 DoD): for a deterministic sequence of generated models,
 * Turtle → parse → JSON-LD → parse must yield a graph isomorphic to the original, and the
 * same in the other direction. No PBT library — seeded {@link RdfModelGenerator}; a
 * failure names the (seed, index) pair, which reproduces the exact model.
 */
class RdfIoRoundTripPropertyTest {

    /** Fixed seed — never change without re-verifying the whole sequence still passes. */
    private static final long SEED = 20260719L;
    private static final int CASES = 40;
    private static final ResourceIdentifier RESOURCE =
            new ResourceIdentifier(URI.create("https://pod.example/alice/notes/note1"));

    @TestFactory
    Stream<DynamicTest> turtleToJsonLdRoundTripIsIsomorphic() {
        RdfModelGenerator generator = new RdfModelGenerator(SEED);
        return IntStream.range(0, CASES).mapToObj(index -> {
            Model original = generator.next(index);
            return DynamicTest.dynamicTest(
                    "seed=" + SEED + " index=" + index + " (" + original.size() + " triples) turtle->jsonld",
                    () -> assertRoundTrip(original, Representation.TURTLE, Representation.JSON_LD));
        });
    }

    @TestFactory
    Stream<DynamicTest> jsonLdToTurtleRoundTripIsIsomorphic() {
        RdfModelGenerator generator = new RdfModelGenerator(SEED);
        return IntStream.range(0, CASES).mapToObj(index -> {
            Model original = generator.next(index);
            return DynamicTest.dynamicTest(
                    "seed=" + SEED + " index=" + index + " (" + original.size() + " triples) jsonld->turtle",
                    () -> assertRoundTrip(original, Representation.JSON_LD, Representation.TURTLE));
        });
    }

    private static void assertRoundTrip(Model original, String firstType, String secondType) {
        Representation first = RdfIo.serialize(original, firstType);
        Model afterFirst = RdfIo.parse(first, RESOURCE);
        Representation second = RdfIo.serialize(afterFirst, secondType);
        Model afterSecond = RdfIo.parse(second, RESOURCE);
        assertTrue(afterSecond.isIsomorphicWith(original),
                () -> "Round trip " + firstType + " -> " + secondType + " lost isomorphism.\n"
                        + "Original (" + original.size() + " triples):\n" + turtle(original)
                        + "\nAfter round trip (" + afterSecond.size() + " triples):\n" + turtle(afterSecond));
    }

    private static String turtle(Model model) {
        return new String(RdfIo.serialize(model, Representation.TURTLE).data(),
                java.nio.charset.StandardCharsets.UTF_8);
    }
}
