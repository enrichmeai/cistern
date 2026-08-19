package com.enrichmeai.cistern.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.enrichmeai.cistern.core.CisternException;
import com.enrichmeai.cistern.core.ResourceIdentifier;
import com.enrichmeai.cistern.wac.Grantee;

import java.net.URI;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import picocli.CommandLine.TypeConversionException;

/** The value types the command line is parsed into, and the one failure-to-exit-code mapping. */
class CliArgumentsTest {

    @Nested
    @DisplayName("<path> follows the server's own request-target rules")
    class Paths {

        @ParameterizedTest
        @ValueSource(strings = {"/", "/trips/", "/trips/lisbon", "/a%20b/c", "/matters/2026-114/index"})
        void accepts(String path) {
            assertEquals(path, new PodPath(path).value());
        }

        @ParameterizedTest
        @ValueSource(strings = {"trips/", "/trips//x", "/trips/../x", "/./x", "/x#frag", "/x?y=1", "/a b"})
        void rejects(String path) {
            assertThrows(IllegalArgumentException.class, () -> new PodPath(path));
        }

        @Test
        void containerIsTheTrailingSlash() {
            assertTrue(new PodPath("/trips/").isContainer());
            assertFalse(new PodPath("/trips/lisbon").isContainer());
        }

        @Test
        void converterReportsAsAUsageError() {
            assertThrows(TypeConversionException.class, () -> new PodPathConverter().convert("trips/"));
        }
    }

    @Nested
    @DisplayName("--base is the origin the identifiers are minted under")
    class Bases {

        @Test
        void trailingSlashIsInsignificant() {
            PodBase base = new PodBase(URI.create("http://127.0.0.1:3737/"));

            assertEquals(new ResourceIdentifier(URI.create("http://127.0.0.1:3737/trips/")),
                    base.resolve(new PodPath("/trips/")));
        }

        @Test
        void displaysResourcesAsPaths() {
            PodBase base = new PodBase(URI.create(PodBase.DEFAULT));

            assertEquals("/trips/.acl", base.display(new ResourceIdentifier(URI.create(PodBase.DEFAULT + "/trips/.acl"))));
            assertEquals("https://elsewhere.example/x",
                    base.display(new ResourceIdentifier(URI.create("https://elsewhere.example/x"))));
        }

        @ParameterizedTest
        @ValueSource(strings = {"127.0.0.1:3737", "ftp://pod.example", "http://pod.example/#f", "http://pod.example/?q"})
        void rejects(String base) {
            assertThrows(IllegalArgumentException.class, () -> new PodBase(URI.create(base)));
        }
    }

    @Nested
    @DisplayName("<webid|public>")
    class Grantees {

        private final GranteeConverter converter = new GranteeConverter();

        @Test
        void publicKeyword() {
            assertEquals(Grantee.PUBLIC, converter.convert("public"));
            assertEquals(Grantee.PUBLIC, converter.convert("PUBLIC"));
        }

        @Test
        void webId() {
            assertEquals(new Grantee.WebId(URI.create("https://alice.example/profile/card#me")),
                    converter.convert("https://alice.example/profile/card#me"));
        }

        @ParameterizedTest
        @ValueSource(strings = {"alice", "profile/card#me", "not a uri", ""})
        void rejects(String value) {
            assertThrows(TypeConversionException.class, () -> converter.convert(value));
        }
    }

    @Nested
    @DisplayName("Exit codes")
    class ExitCodes {

        private final ResourceIdentifier acl = new ResourceIdentifier(URI.create("http://127.0.0.1:3737/trips/.acl"));
        private final ResourceIdentifier trips = new ResourceIdentifier(URI.create("http://127.0.0.1:3737/trips/"));

        @Test
        void serverRefusalsAreTwo() {
            assertEquals(ExitCode.REFUSED, CisternCli.exitCodeFor(
                    new CliFailure.Refused(PodMethod.GET, acl, PodStatus.UNAUTHORIZED, trips)));
            assertEquals(ExitCode.REFUSED, CisternCli.exitCodeFor(
                    new CliFailure.Refused(PodMethod.PUT, acl, PodStatus.FORBIDDEN, trips)));
        }

        @Test
        void aRevokeTheServiceRefusesIsTwo() {
            assertEquals(ExitCode.REFUSED, CisternCli.exitCodeFor(new CisternException.Conflict("would drop Control")));
        }

        @Test
        void conflictIsThree() {
            assertEquals(ExitCode.CONFLICT, CisternCli.exitCodeFor(new CliFailure.Conflict(acl)));
        }

        @Test
        void everythingElseIsOne() {
            assertEquals(ExitCode.FAILURE, CisternCli.exitCodeFor(new CliFailure.NoAcl(trips)));
            assertEquals(ExitCode.FAILURE, CisternCli.exitCodeFor(new CliFailure.Transport(acl, new java.net.ConnectException())));
            assertEquals(ExitCode.FAILURE, CisternCli.exitCodeFor(new IllegalStateException()));
        }

        @Test
        void codesAreDistinctAndZeroIsOk() {
            assertEquals(0, ExitCode.OK.code());
            assertEquals(ExitCode.values().length,
                    java.util.Arrays.stream(ExitCode.values()).map(ExitCode::code).distinct().count());
        }
    }
}
