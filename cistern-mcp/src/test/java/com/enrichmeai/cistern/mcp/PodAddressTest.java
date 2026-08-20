package com.enrichmeai.cistern.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.enrichmeai.cistern.core.ResourceIdentifier;

import java.net.URI;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The one place a model-supplied {@code url} becomes a target: its rules, exhaustively. */
class PodAddressTest {

    private static final URI POD = URI.create("http://localhost:3737");
    private static final URI CONNECT = URI.create("http://127.0.0.1:52801");

    private final PodAddress same = PodAddress.of(POD);
    private final PodAddress split = new PodAddress(CONNECT, POD);

    @Test
    @DisplayName("a path resolves under the pod base and is requested at the connect base")
    void pathResolves() {
        PodAddress.PodTarget target = split.resolve("/notes/week");
        assertEquals(POD + "/notes/week", target.identifier().uri().toString());
        assertEquals(CONNECT + "/notes/week", target.requestUri().toString());
    }

    @Test
    @DisplayName("an absolute URL under either base is accepted; the identifier is always the pod's")
    void absoluteUnderEitherBase() {
        assertEquals(POD + "/notes/", split.resolve(POD + "/notes/").identifier().uri().toString());
        assertEquals(POD + "/notes/", split.resolve(CONNECT + "/notes/").identifier().uri().toString());
        assertEquals(POD + "/a", same.resolve(POD + "/a").identifier().uri().toString());
    }

    @Test
    @DisplayName("anything outside the pod is refused before any request is made")
    void outsideThePod() {
        assertThrows(PodProblem.BadArgument.class, () -> same.resolve("https://elsewhere.example/x"));
        assertThrows(PodProblem.BadArgument.class, () -> same.resolve("notes/week"));
        assertThrows(PodProblem.BadArgument.class, () -> same.resolve(POD.toString()),
                "the bare origin names no resource");
    }

    @Test
    @DisplayName("a target the identifier rules reject (a fragment) is a bad argument")
    void malformedTargets() {
        // Dot segments are deliberately NOT rejected here: RequestPaths refuses them at the
        // server's edge with a 400 problem document, and that answer — the server's — is the
        // one the tool result carries. Local checks cover only what never leaves the process.
        assertThrows(PodProblem.BadArgument.class, () -> same.resolve("/a#frag"));
    }

    @Test
    @DisplayName("requestUriFor maps a pod identifier onto the connect base — ACL edits included")
    void requestUriFor() {
        ResourceIdentifier acl = new ResourceIdentifier(URI.create(POD + "/notes/.acl"));
        assertEquals(CONNECT + "/notes/.acl", split.requestUriFor(acl).toString());
    }

    @Test
    @DisplayName("trailing slashes on a configured base are insignificant")
    void trailingSlashTrimmed() {
        PodAddress trimmed = PodAddress.of(URI.create(POD + "/"));
        assertEquals(POD + "/x", trimmed.resolve("/x").identifier().uri().toString());
    }
}
