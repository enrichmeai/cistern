package com.enrichmeai.cistern.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.enrichmeai.cistern.core.CisternException;
import com.enrichmeai.cistern.core.ResourceIdentifier;
import com.enrichmeai.cistern.wac.Grantee;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import picocli.CommandLine;
import picocli.CommandLine.ParseResult;
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
    @DisplayName("--root and --owner (pod create)")
    class PodRoots {

        private final PodRootConverter roots = new PodRootConverter();
        private final WebIdConverter owners = new WebIdConverter();

        @ParameterizedTest
        @ValueSource(strings = {"/", "/firms/acme/", "/alice/", " /bob/ "})
        void rootAcceptsContainers(String root) {
            assertTrue(roots.convert(root).isContainer());
        }

        @ParameterizedTest
        @ValueSource(strings = {"/firms/acme", "firms/acme/", "/a//b/", "/a/../b/", "/x#f/", ""})
        void rootRejectsAnythingElse(String root) {
            TypeConversionException e = assertThrows(TypeConversionException.class, () -> roots.convert(root));
            assertEquals(CliMessage.INVALID_ROOT.format(root), e.getMessage());
        }

        @Test
        void ownerIsAnAbsoluteWebId() {
            assertEquals(URI.create("https://acme-law.example/profile#firm"),
                    owners.convert(" https://acme-law.example/profile#firm "));
        }

        @ParameterizedTest
        @ValueSource(strings = {"profile#firm", "acme", "not a uri", "", "public"})
        void ownerRejectsAnythingElse(String owner) {
            TypeConversionException e = assertThrows(TypeConversionException.class, () -> owners.convert(owner));
            assertEquals(CliMessage.INVALID_OWNER.format(owner), e.getMessage());
        }
    }

    @Nested
    @DisplayName("sync <local-dir> <pod-path> [--dry-run] [--delete]")
    class Sync {

        private final PodPath docs = new PodPath("/firms/acme/docs/");

        @Test
        void relativePathsAreEncodedOnceUnderTheTarget() {
            assertEquals("/firms/acme/docs/reports/caf%C3%A9%20menu%231.md",
                    new RelativePath("reports/caf\u00e9 menu#1.md").under(docs).value());
            assertEquals("/firms/acme/docs/a%3Fb%25c.txt", new RelativePath("a?b%c.txt").under(docs).value());
            assertEquals("/firms/acme/docs/reports/", new RelativePath("reports/").under(docs).value());
            assertEquals("/firms/acme/docs/2026-Q2.md", new RelativePath("2026-Q2.md").under(docs).value(),
                    "what needs no encoding is untouched");
        }

        @Test
        void aTargetThatIsNotAContainerCannotBeMintedUnder() {
            assertThrows(IllegalArgumentException.class, () -> new RelativePath("a.md").under(new PodPath("/docs")));
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "/a", "a//b", "./a", "a/../b", "..", ".", "a/./b"})
        void relativePathRejects(String value) {
            assertThrows(IllegalArgumentException.class, () -> new RelativePath(value));
        }

        @Test
        void containersSortBeforeTheirMembersAndKnowTheirParent() {
            List<RelativePath> sorted = Stream.of("reports/2026-Q2.md", "reports/", "a-b/", "a/", "a/b/", "a/b/c.md")
                    .map(RelativePath::new)
                    .sorted()
                    .toList();

            assertEquals(List.of("a-b/", "a/", "a/b/", "a/b/c.md", "reports/", "reports/2026-Q2.md"),
                    sorted.stream().map(RelativePath::value).toList());
            assertEquals("reports/", new RelativePath("reports/2026-Q2.md").parent().orElseThrow().value());
            assertEquals("a/", new RelativePath("a/b/").parent().orElseThrow().value());
            assertTrue(new RelativePath("reports/").parent().isEmpty());
            assertTrue(new RelativePath("reports/").isContainer());
            assertFalse(new RelativePath("reports/2026-Q2.md").isContainer());
        }

        @Test
        void mediaTypeFollowsTheExtensionCaseInsensitively() {
            assertEquals(FileMediaType.MARKDOWN, FileMediaType.of("2026-Q2.md"));
            assertEquals(FileMediaType.PDF, FileMediaType.of("CONTRACT.PDF"));
            assertEquals(FileMediaType.CSV, FileMediaType.of("payroll/2026-08.csv"));
            assertEquals(FileMediaType.TURTLE, FileMediaType.of("index.ttl"));
            assertEquals("text/turtle", FileMediaType.TURTLE.contentType());
            assertEquals(FileMediaType.JSON_LD, FileMediaType.of("card.jsonld"));
            assertEquals(FileMediaType.JSON, FileMediaType.of("config.json"));
            assertEquals(FileMediaType.OCTET_STREAM, FileMediaType.of("notes.xyz"), "unknown extension");
            assertEquals(FileMediaType.OCTET_STREAM, FileMediaType.of("README"), "no extension");
            assertEquals(FileMediaType.OCTET_STREAM, FileMediaType.of(".env"), "a dotfile has no extension");
            assertEquals(FileMediaType.OCTET_STREAM, FileMediaType.of("archive.tar.gz"), "only the last extension counts");
            assertEquals(FileMediaType.OCTET_STREAM, FileMediaType.of("trailing."), "an empty extension");
            assertEquals("application/octet-stream", FileMediaType.OCTET_STREAM.contentType());
        }

        @Test
        void contentHashIsSha256InLowerHex() {
            assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                    ContentHash.of("abc".getBytes()).hex());
            assertThrows(IllegalArgumentException.class, () -> new ContentHash("BA7816BF"));
            assertThrows(IllegalArgumentException.class, () -> new ContentHash(""));
        }

        @Test
        void theTargetMustBeAContainer() {
            ContainerPathConverter converter = new ContainerPathConverter();

            assertEquals(docs, converter.convert(" /firms/acme/docs/ "));
            for (String bad : List.of("/firms/acme/docs", "docs/", "/a//b/", "")) {
                TypeConversionException e = assertThrows(TypeConversionException.class, () -> converter.convert(bad));
                assertEquals(CliMessage.INVALID_TARGET_CONTAINER.format(bad), e.getMessage());
            }
        }

        @Test
        void optionsParseIntoTheCommand() {
            ParseResult parsed = new CommandLine(new CisternCli())
                    .parseArgs("sync", "./docs", "/firms/acme/docs/", "--dry-run", "--delete");
            SyncCommand sync = (SyncCommand) parsed.subcommand().commandSpec().userObject();

            assertEquals(Path.of("./docs"), sync.localDir);
            assertEquals(docs, sync.target);
            assertTrue(sync.dryRun);
            assertTrue(sync.delete);
        }

        @Test
        void bothFlagsAreOffByDefault() {
            ParseResult parsed = new CommandLine(new CisternCli()).parseArgs("sync", "./docs", "/firms/acme/docs/");
            SyncCommand sync = (SyncCommand) parsed.subcommand().commandSpec().userObject();

            assertFalse(sync.dryRun);
            assertFalse(sync.delete);
        }

        @Test
        void helpListsTheCommandAndItsOptions() {
            CommandLine cistern = new CommandLine(new CisternCli());

            assertTrue(cistern.getUsageMessage().contains(Usage.SYNC_NAME), cistern.getUsageMessage());
            String sync = cistern.getSubcommands().get(Usage.SYNC_NAME).getUsageMessage();
            assertTrue(sync.contains(Usage.DRY_RUN_OPTION), sync);
            assertTrue(sync.contains(Usage.DELETE_OPTION), sync);
            assertTrue(sync.contains(Usage.LOCAL_DIR_PARAM), sync);
            // picocli wraps at line-break opportunities inside a long token, so compare without whitespace.
            assertTrue(sync.replaceAll("\\s+", "").contains(SyncStateFile.NAME), sync);
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
            assertEquals(ExitCode.REFUSED, CisternCli.exitCodeFor(
                    new CliFailure.Refused(PodMethod.PUT, trips, PodStatus.FORBIDDEN)), "a refused container create");
        }

        @Test
        void refusalsAreExplainedByWhatWasAsked() {
            assertEquals(CliMessage.REFUSED.format(PodMethod.PUT, acl.uri(), PodStatus.FORBIDDEN.code(), trips.uri()),
                    new CliFailure.Refused(PodMethod.PUT, acl, PodStatus.FORBIDDEN, trips).getMessage());
            assertEquals(CliMessage.REFUSED_RESOURCE.format(PodMethod.PUT, trips.uri(), PodStatus.FORBIDDEN.code()),
                    new CliFailure.Refused(PodMethod.PUT, trips, PodStatus.FORBIDDEN).getMessage());
        }

        @Test
        void aReadOrDeleteRefusalIsExplainedByItsMode() {
            assertEquals(CliMessage.REFUSED_RESOURCE_READ.format(PodMethod.HEAD, trips.uri(), PodStatus.FORBIDDEN.code()),
                    new CliFailure.Refused(PodMethod.HEAD, trips, PodStatus.FORBIDDEN).getMessage());
            assertEquals(CliMessage.REFUSED_RESOURCE_DELETE.format(PodMethod.DELETE, trips.uri(), PodStatus.UNAUTHORIZED.code()),
                    new CliFailure.Refused(PodMethod.DELETE, trips, PodStatus.UNAUTHORIZED).getMessage());
        }

        @Test
        void aSyncConflictIsThreeAndSaysWhichPreconditionFailed() {
            ResourceIdentifier note = new ResourceIdentifier(URI.create("http://127.0.0.1:3737/docs/note.md"));
            CliFailure.Conflict exists = new CliFailure.Conflict(note, new WritePrecondition.IfNoneMatchAny());
            CliFailure.Conflict changed = new CliFailure.Conflict(note, new WritePrecondition.IfMatch(new EntityTagHeader("\"x\"")));

            assertEquals(ExitCode.CONFLICT, CisternCli.exitCodeFor(exists));
            assertEquals(ExitCode.CONFLICT, CisternCli.exitCodeFor(changed));
            assertEquals(CliMessage.SYNC_CONFLICT_EXISTS.format(note.uri()), exists.getMessage());
            assertEquals(CliMessage.SYNC_CONFLICT_CHANGED.format(note.uri(), SyncStateFile.NAME), changed.getMessage());
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
            assertEquals(ExitCode.FAILURE, CisternCli.exitCodeFor(new CliFailure.ContainerNotEmpty(trips)));
            assertEquals(ExitCode.FAILURE, CisternCli.exitCodeFor(new CliFailure.LocalFolder(CliMessage.NOT_A_DIRECTORY, "x")));
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
