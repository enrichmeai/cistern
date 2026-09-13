package com.enrichmeai.cistern.cli;

import com.enrichmeai.cistern.core.ResourceIdentifier;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/**
 * {@code <local-dir>/.cistern-sync.json}: the {@link SyncState} on disk, rewritten after every
 * step that succeeds, so a run cut short — a lost connection, a refusal three files in — leaves
 * a file that says exactly what reached the pod, and the next run continues from there rather
 * than sending everything again.
 *
 * <p>Written to a sibling temporary file and moved into place atomically, so a crash mid-write
 * cannot leave a half-written state that the next run would refuse to read. Both files are
 * {@link #owns owned} by this class and excluded from the walk: the memory of a sync is not a
 * document to be synced.
 *
 * <p><strong>Neither file is read nor written through a symbolic link.</strong> The rest of the
 * folder is already walked without following links ({@link LocalTree}), and this class is held
 * to the same rule so that one command does not refuse to <em>send</em> a link while happily
 * reading its own memory through one.
 *
 * <p>The two halves are not equally exposed, which is worth stating because only one of them
 * looks it. The {@code ATOMIC_MOVE} onto {@link #path} is safe on its own: a move replaces a
 * symlink at the destination rather than writing through it (measured). The write to
 * {@link #temporary} is not — {@code Files.writeString} through a planted
 * {@code .cistern-sync.json.tmp} link overwrites whatever it points at (measured: the target
 * file's contents were replaced). So the temporary is refused if it is a link, and opened with
 * {@link LinkOption#NOFOLLOW_LINKS} so that one appearing between the check and the open fails
 * rather than being followed. The read of {@link #path} is refused for the consistency reason
 * above.
 *
 * <p>Mutable by design — it <em>is</em> the run's memory — and touched by one sequential chain,
 * so there is nothing to synchronise.
 */
final class SyncStateFile {

    /** The file's name in the folder it describes. */
    static final String NAME = ".cistern-sync.json";

    private static final String TEMPORARY_SUFFIX = ".tmp";

    private final Path path;
    private final Path temporary;
    private SyncState state;

    private SyncStateFile(Path path, SyncState state) {
        this.path = path;
        this.temporary = path.resolveSibling(NAME + TEMPORARY_SUFFIX);
        this.state = state;
    }

    /**
     * The state file in {@code folder} for a sync to {@code target}: read if it is there, empty
     * if it is not — and "not there" means {@link NoSuchFileException} from the read itself,
     * never {@code Files.exists}, which answers false when it cannot stat at all (a folder
     * without execute permission, say) and would turn an unreadable memory into an empty one.
     * That is the same silent restart the malformed case below refuses, and for the same
     * reason. A file that is there but cannot be read as a state, or that describes a
     * sync to somewhere else, is a {@link CliFailure.LocalFolder}: moving it aside is the way to
     * start afresh, and the message says so — guessing would send every file again under
     * {@code If-None-Match: *} and fail on the first that exists, and using another target's
     * validators would send {@code If-Match} for resources this target never received.
     */
    static SyncStateFile in(Path folder, ResourceIdentifier target) {
        Path path = Objects.requireNonNull(folder, "folder").resolve(NAME);
        Objects.requireNonNull(target, "target");
        refuseLink(path);
        String text;
        try {
            text = Files.readString(path);
        } catch (NoSuchFileException e) {
            return new SyncStateFile(path, SyncState.empty(target));
        } catch (IOException e) {
            throw new CliFailure.LocalFolder(CliMessage.LOCAL_UNREADABLE, path, describe(e));
        }
        SyncState state;
        try {
            state = SyncState.fromJson(text);
        } catch (IllegalArgumentException e) {
            throw new CliFailure.LocalFolder(CliMessage.STATE_FILE_MALFORMED, path, e.getMessage());
        }
        if (!state.target().equals(target)) {
            throw new CliFailure.LocalFolder(CliMessage.STATE_FILE_OTHER_TARGET, path, state.target().uri(), target.uri());
        }
        return new SyncStateFile(path, state);
    }

    Path path() {
        return path;
    }

    /** What has been sent, as of the last step that succeeded. */
    SyncState current() {
        return state;
    }

    /** Whether {@code file} is this class's — the state file or its temporary — and so never synced. */
    boolean owns(Path file) {
        return path.equals(file) || temporary.equals(file);
    }

    /** {@code path} reached the pod as {@code resource}: remember it, on disk, now. */
    void remember(RelativePath path, SyncedResource resource) {
        state = state.with(path, resource);
        save();
    }

    /** {@code path} is gone from the pod: forget it, on disk, now. */
    void forget(RelativePath path) {
        state = state.without(path);
        save();
    }

    /**
     * The move replaces {@link #path} itself rather than following it, so only the temporary
     * needs guarding — and it needs both halves, the check being advisory and the open being
     * what actually holds.
     *
     * <p>UTF-8 is named here only because these bytes go through a stream. The
     * {@code Files.writeString(Path, CharSequence, OpenOption...)} this replaced was already
     * UTF-8: that overload is specified to use it whatever the platform default is (measured
     * under {@code -Dfile.encoding=US-ASCII} and {@code ISO-8859-1}), as is
     * {@code Files.readString(Path)} on the way back in.
     */
    private void save() {
        refuseLink(temporary);
        try {
            try (OutputStream out = Files.newOutputStream(temporary, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS)) {
                out.write(state.toJson().getBytes(StandardCharsets.UTF_8));
            }
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new CliFailure.LocalFolder(CliMessage.STATE_FILE_UNWRITABLE, path, describe(e));
        }
    }

    private static void refuseLink(Path file) {
        if (Files.isSymbolicLink(file)) {
            throw new CliFailure.LocalFolder(CliMessage.STATE_FILE_IS_A_LINK, file);
        }
    }

    static String describe(IOException e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }
}
