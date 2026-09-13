package com.enrichmeai.cistern.cli;

import com.enrichmeai.cistern.wac.AclResource;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * The folder as found on disk: its sub-folders (the containers to have), its regular files
 * (the documents to send, each with its media type and content hash), and what was passed over
 * and why. Sorted, so the plan built from it — and the transcript — is the same on every run.
 *
 * <p>Symbolic links are not followed: a link out of the folder would mirror something the
 * person never put in it, and a link within it would send one file twice under two names.
 * A file whose name would make it an ACL on the pod is not sent either — mirrored, it would
 * change who may read the folder, silently; grants are {@code cistern grant}'s job. Both are
 * reported, never quietly dropped.
 *
 * @param root       the folder, absolute and normalised
 * @param containers every sub-folder, parents first
 * @param documents  every regular file that will be sent, in path order
 * @param skipped    what was passed over, in path order
 */
record LocalTree(Path root, List<RelativePath> containers, List<Document> documents, List<Skipped> skipped) {

    /** A file to send: where it sits, where it is, what it is sent as, and what it contains. */
    record Document(RelativePath path, Path file, FileMediaType mediaType, ContentHash sha256) {
        Document {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(file, "file");
            Objects.requireNonNull(mediaType, "mediaType");
            Objects.requireNonNull(sha256, "sha256");
        }
    }

    /** An entry that is not sent, and the rule that decided so. */
    record Skipped(RelativePath path, SkipReason reason) {
        Skipped {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(reason, "reason");
        }
    }

    /** The reasons an entry is passed over — a closed set, so an enum (ground rule 7). */
    enum SkipReason {
        /** A symbolic link, to anything; links are not followed. */
        SYMBOLIC_LINK,
        /** Neither a regular file nor a folder nor a link: a socket, a device. */
        NOT_A_REGULAR_FILE,
        /** Named {@code *.acl}, which the pod would take for an access control list. */
        ACL_NAME
    }

    LocalTree {
        Objects.requireNonNull(root, "root");
        containers = List.copyOf(containers);
        documents = List.copyOf(documents);
        skipped = List.copyOf(skipped);
    }

    /**
     * Walk {@code folder}. {@code excluded} names files that are never entries — the state file
     * and its temporary — and is asked before anything else about a file.
     *
     * @throws CliFailure.LocalFolder if {@code folder} is not a folder or cannot be read
     */
    static LocalTree walk(Path folder, Predicate<Path> excluded) {
        Path root = Objects.requireNonNull(folder, "folder").toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new CliFailure.LocalFolder(CliMessage.NOT_A_DIRECTORY, folder);
        }
        List<RelativePath> containers = new ArrayList<>();
        List<Document> documents = new ArrayList<>();
        List<Skipped> skipped = new ArrayList<>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!dir.equals(root)) {
                        containers.add(RelativePath.of(root, dir, true));
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (excluded.test(file)) {
                        return FileVisitResult.CONTINUE;
                    }
                    RelativePath path = RelativePath.of(root, file, false);
                    if (attrs.isSymbolicLink()) {
                        skipped.add(new Skipped(path, SkipReason.SYMBOLIC_LINK));
                    } else if (!attrs.isRegularFile()) {
                        skipped.add(new Skipped(path, SkipReason.NOT_A_REGULAR_FILE));
                    } else if (path.value().endsWith(AclResource.SUFFIX)) {
                        skipped.add(new Skipped(path, SkipReason.ACL_NAME));
                    } else {
                        documents.add(new Document(path, file, FileMediaType.of(file), ContentHash.of(file)));
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new CliFailure.LocalFolder(CliMessage.LOCAL_UNREADABLE, root, SyncStateFile.describe(e));
        }
        containers.sort(Comparator.naturalOrder());
        documents.sort(Comparator.comparing(Document::path));
        skipped.sort(Comparator.comparing(Skipped::path));
        return new LocalTree(root, containers, documents, skipped);
    }
}
