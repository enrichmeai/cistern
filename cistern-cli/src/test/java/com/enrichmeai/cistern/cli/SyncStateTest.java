package com.enrichmeai.cistern.cli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.enrichmeai.cistern.core.ResourceIdentifier;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The state file: what it remembers, how it is spelled, and what it refuses to read. */
class SyncStateTest {

    private static final ResourceIdentifier TARGET = new ResourceIdentifier(URI.create("http://127.0.0.1:3737/docs/"));
    private static final ResourceIdentifier ELSEWHERE = new ResourceIdentifier(URI.create("http://127.0.0.1:3737/other/"));
    private static final RelativePath REPORT = new RelativePath("reports/2026-Q2.md");
    private static final RelativePath REPORTS = new RelativePath("reports/");
    private static final SyncedResource.Document SENT = new SyncedResource.Document(
            new EntityTagHeader("\"abc\""), ContentHash.of("Q2".getBytes()));

    @Nested
    @DisplayName("the JSON form")
    class Json {

        @Test
        void roundTrips() {
            SyncState state = SyncState.empty(TARGET).with(REPORT, SENT).with(REPORTS, new SyncedResource.Container());

            SyncState read = SyncState.fromJson(state.toJson());

            assertEquals(state, read);
            assertEquals(TARGET, read.target());
            assertEquals(SENT, read.get(REPORT).orElseThrow());
            assertEquals(new SyncedResource.Container(), read.get(REPORTS).orElseThrow());
        }

        @Test
        void isSortedVersionedAndNewlineTerminated() {
            String json = SyncState.empty(TARGET)
                    .with(new RelativePath("z.md"), SENT)
                    .with(new RelativePath("a/"), new SyncedResource.Container())
                    .with(new RelativePath("a/b.md"), SENT)
                    .toJson();

            assertTrue(json.indexOf("\"a/\"") < json.indexOf("\"a/b.md\""), json);
            assertTrue(json.indexOf("\"a/b.md\"") < json.indexOf("\"z.md\""), json);
            assertTrue(json.contains("\"version\": " + SyncState.FORMAT_VERSION), json);
            assertTrue(json.contains("\"target\": \"" + TARGET.uri() + "\""), json);
            assertTrue(json.endsWith("\n"), "newline-terminated, as a text file should be");
        }

        @Test
        void emptyStateIsAnEmptyResourcesObject() {
            SyncState read = SyncState.fromJson(SyncState.empty(TARGET).toJson());
            assertTrue(read.resources().isEmpty());
        }

        @Test
        void withAndWithoutDoNotMutate() {
            SyncState empty = SyncState.empty(TARGET);
            SyncState one = empty.with(REPORT, SENT);

            assertTrue(empty.resources().isEmpty());
            assertEquals(SENT, one.get(REPORT).orElseThrow());
            assertTrue(one.without(REPORT).resources().isEmpty());
            assertEquals(SENT, one.get(REPORT).orElseThrow(), "without() returned a copy");
        }

        @ParameterizedTest
        @ValueSource(strings = {
            "not json at all",
            "[]",
            "{\"target\": \"T\", \"resources\": {}}",
            "{\"version\": 2, \"target\": \"T\", \"resources\": {}}",
            // Three spellings that are not the "1" this class writes, each refused by a different
            // half of the guard: intValue() truncates a fraction and wraps a long, so 1.5 and
            // 4294967297 both read as 1; isIntegral() catches 1.5 and 1.0 but not the wrap;
            // intValueExact() catches the wrap but would accept 1.0.
            "{\"version\": 1.5, \"target\": \"T\", \"resources\": {}}",
            "{\"version\": 1.0, \"target\": \"T\", \"resources\": {}}",
            "{\"version\": 4294967297, \"target\": \"T\", \"resources\": {}}",
            "{\"version\": 18446744073709551617, \"target\": \"T\", \"resources\": {}}",
            // A document entry carries etag and sha256 and nothing else.
            "{\"version\": 1, \"target\": \"T\", \"resources\": {\"a.md\": {\"etag\": \"\\\"x\\\"\", \"sha256\": \"0000000000000000000000000000000000000000000000000000000000000000\", \"mtime\": 7}}}",
            "{\"version\": \"1\", \"target\": \"T\", \"resources\": {}}",
            "{\"version\": true, \"target\": \"T\", \"resources\": {}}",
            "{\"version\": 1, \"resources\": {}}",
            "{\"version\": 1, \"target\": \"docs/\", \"resources\": {}}",
            "{\"version\": 1, \"target\": \"http://127.0.0.1:3737/docs\", \"resources\": {}}",
            "{\"version\": 1, \"target\": \"T\"}",
            "{\"version\": 1, \"target\": \"T\", \"resources\": []}",
            "{\"version\": 1, \"target\": \"T\", \"resources\": {\"reports/\": {\"etag\": \"\\\"x\\\"\"}}}",
            "{\"version\": 1, \"target\": \"T\", \"resources\": {\"a.md\": {\"etag\": \"\\\"x\\\"\"}}}",
            "{\"version\": 1, \"target\": \"T\", \"resources\": {\"a.md\": {\"etag\": \"\\\"x\\\"\", \"sha256\": \"short\"}}}",
            "{\"version\": 1, \"target\": \"T\", \"resources\": {\"a.md\": \"x\"}}",
            "{\"version\": 1, \"target\": \"T\", \"resources\": {\"/a.md\": {}}}",
            "{\"version\": 1, \"target\": \"T\", \"resources\": {\"a/../b\": {}}}"
        })
        void refusesWhatItDidNotWrite(String json) {
            // "T" stands for a well-formed target; the cases about the target spell their own.
            String text = json.replace("\"T\"", "\"" + TARGET.uri() + "\"");
            assertThrows(IllegalArgumentException.class, () -> SyncState.fromJson(text));
        }
    }

    @Nested
    @DisplayName("the file in the folder")
    class OnDisk {

        @TempDir
        Path folder;

        @Test
        void absentIsEmptyAndNotCreatedUntilSomethingIsRemembered() {
            SyncStateFile file = SyncStateFile.in(folder, TARGET);

            assertTrue(file.current().resources().isEmpty());
            assertFalse(Files.exists(folder.resolve(SyncStateFile.NAME)));
            assertTrue(file.owns(folder.resolve(SyncStateFile.NAME)));
            assertTrue(file.owns(folder.resolve(SyncStateFile.NAME + ".tmp")));
            assertFalse(file.owns(folder.resolve("notes.md")));
        }

        @Test
        void rememberAndForgetArePersistedImmediately() throws IOException {
            SyncStateFile file = SyncStateFile.in(folder, TARGET);

            file.remember(REPORTS, new SyncedResource.Container());
            file.remember(REPORT, SENT);
            assertEquals(file.current(), SyncState.fromJson(Files.readString(folder.resolve(SyncStateFile.NAME))));
            assertEquals(file.current(), SyncStateFile.in(folder, TARGET).current(), "a fresh read sees the same");

            file.forget(REPORT);
            assertTrue(SyncStateFile.in(folder, TARGET).current().get(REPORT).isEmpty());
            assertFalse(Files.exists(folder.resolve(SyncStateFile.NAME + ".tmp")), "the temporary is moved, not left");
        }

        @Test
        void aFolderMirrorsIntoOnePlace() {
            SyncStateFile.in(folder, TARGET).remember(REPORTS, new SyncedResource.Container());

            CliFailure.LocalFolder failure = assertThrows(CliFailure.LocalFolder.class,
                    () -> SyncStateFile.in(folder, ELSEWHERE));

            assertEquals(CliMessage.STATE_FILE_OTHER_TARGET.format(folder.resolve(SyncStateFile.NAME), TARGET.uri(), ELSEWHERE.uri()),
                    failure.getMessage());
            assertEquals(ExitCode.FAILURE, CisternCli.exitCodeFor(failure));
        }

        /**
         * A folder can arrive from somewhere else. A planted link where the state file — or its
         * temporary — goes would otherwise have this class read, or overwrite, whatever it points
         * at: {@code Files.writeString} through a symlink writes the target, measured.
         */
        @Test
        void neverReadsOrWritesThroughASymbolicLink() throws IOException {
            Path elsewhere = Files.writeString(folder.resolve("private.txt"), "a private file\n");
            Path state = folder.resolve(SyncStateFile.NAME);
            Files.createSymbolicLink(state, elsewhere);

            CliFailure.LocalFolder onRead = assertThrows(CliFailure.LocalFolder.class,
                    () -> SyncStateFile.in(folder, TARGET));
            assertEquals(CliMessage.STATE_FILE_IS_A_LINK.format(state), onRead.getMessage());
            assertEquals("a private file\n", Files.readString(elsewhere), "not read, and certainly not written");

            Files.delete(state);
            SyncStateFile file = SyncStateFile.in(folder, TARGET);
            Files.createSymbolicLink(folder.resolve(SyncStateFile.NAME + ".tmp"), elsewhere);

            CliFailure.LocalFolder onWrite = assertThrows(CliFailure.LocalFolder.class,
                    () -> file.remember(REPORT, SENT));
            assertEquals(CliMessage.STATE_FILE_IS_A_LINK.format(folder.resolve(SyncStateFile.NAME + ".tmp")),
                    onWrite.getMessage());
            assertEquals("a private file\n", Files.readString(elsewhere), "the link's target is untouched");
            assertEquals(ExitCode.FAILURE, CisternCli.exitCodeFor(onWrite));
        }

        /**
         * {@code Files.readString}/{@code writeString} are specified as UTF-8 whatever the
         * platform default is, and the stream write in {@code save()} says UTF-8 explicitly. A
         * non-ASCII path is the case that would show a lapse either way.
         */
        @Test
        void nonAsciiPathsRoundTripThroughTheFile() throws IOException {
            RelativePath accented = new RelativePath("rapports/résumé — été.md");
            SyncStateFile file = SyncStateFile.in(folder, TARGET);

            file.remember(accented, SENT);

            assertArrayEquals(Files.readAllBytes(folder.resolve(SyncStateFile.NAME)),
                    Files.readString(folder.resolve(SyncStateFile.NAME)).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    "the bytes on disk are UTF-8");
            assertEquals(SENT, SyncStateFile.in(folder, TARGET).current().get(accented).orElseThrow());
        }

        @Test
        void aFileItCannotReadIsAFailureThatNamesIt() throws IOException {
            Path state = folder.resolve(SyncStateFile.NAME);
            Files.writeString(state, "{\"version\": 99}");

            CliFailure.LocalFolder failure = assertThrows(CliFailure.LocalFolder.class, () -> SyncStateFile.in(folder, TARGET));

            assertTrue(failure.getMessage().startsWith(CliMessage.STATE_FILE_MALFORMED.format(state, "")
                    .substring(0, state.toString().length())), failure.getMessage());
            assertTrue(failure.getMessage().contains(CliMessage.STATE_FILE_VERSION.format(99, SyncState.FORMAT_VERSION)),
                    failure.getMessage());
            assertEquals(ExitCode.FAILURE, CisternCli.exitCodeFor(failure));
        }
    }
}
