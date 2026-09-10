package com.enrichmeai.cistern.cli;

import com.enrichmeai.cistern.core.ResourceIdentifier;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
     * if it is not. A file that is there but cannot be read as a state, or that describes a
     * sync to somewhere else, is a {@link CliFailure.LocalFolder}: moving it aside is the way to
     * start afresh, and the message says so — guessing would send every file again under
     * {@code If-None-Match: *} and fail on the first that exists, and using another target's
     * validators would send {@code If-Match} for resources this target never received.
     */
    static SyncStateFile in(Path folder, ResourceIdentifier target) {
        Path path = Objects.requireNonNull(folder, "folder").resolve(NAME);
        Objects.requireNonNull(target, "target");
        if (!Files.exists(path)) {
            return new SyncStateFile(path, SyncState.empty(target));
        }
        String text;
        try {
            text = Files.readString(path);
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

    private void save() {
        try {
            Files.writeString(temporary, state.toJson());
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new CliFailure.LocalFolder(CliMessage.STATE_FILE_UNWRITABLE, path, describe(e));
        }
    }

    static String describe(IOException e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }
}
