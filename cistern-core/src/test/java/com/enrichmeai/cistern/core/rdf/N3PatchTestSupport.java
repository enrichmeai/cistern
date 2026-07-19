package com.enrichmeai.cistern.core.rdf;

import com.enrichmeai.cistern.core.Representation;
import com.enrichmeai.cistern.core.ResourceIdentifier;
import org.apache.jena.rdf.model.Model;

import java.net.URI;
import java.nio.charset.StandardCharsets;

/** Shared fixtures for the N3 Patch test suite. */
final class N3PatchTestSupport {

    /** The resource being patched; base URI for all relative IRI resolution in tests. */
    static final ResourceIdentifier DOC =
            new ResourceIdentifier(URI.create("https://pod.example/notes/doc"));

    static final String SOLID_PREFIX = "@prefix solid: <http://www.w3.org/ns/solid/terms#>.\n";
    static final String EX_PREFIX = "@prefix ex: <http://www.example.org/terms#>.\n";
    static final String PREFIXES = SOLID_PREFIX + EX_PREFIX;

    private N3PatchTestSupport() {
    }

    static N3Patch parse(String n3Document) {
        return N3Patch.parse(
                new Representation(N3Patch.MEDIA_TYPE, n3Document.getBytes(StandardCharsets.UTF_8)), DOC);
    }

    static Model turtle(String turtleDocument) {
        return RdfIo.parse(
                new Representation(Representation.TURTLE, turtleDocument.getBytes(StandardCharsets.UTF_8)), DOC);
    }
}
