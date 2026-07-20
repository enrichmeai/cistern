package com.enrichmeai.cistern.storage.file;

import com.enrichmeai.cistern.core.CisternException;
import com.enrichmeai.cistern.core.Representation;
import com.enrichmeai.cistern.core.ResourceIdentifier;
import com.enrichmeai.cistern.core.ResourceStore;
import com.enrichmeai.cistern.core.StoredResource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * File-per-resource {@link ResourceStore}: containers are directories, documents are
 * regular files, metadata lives in JSON sidecars (T1.3). Plain Java + Reactor — no Spring
 * in this module; Spring wiring happens in the starter (T7.1). All file I/O is blocking,
 * so every operation runs on {@link Schedulers#boundedElastic()}; nothing touches the
 * filesystem on the subscribing thread.
 *
 * <h2>On-disk layout</h2>
 *
 * <pre>
 * root/                              ← container "/"
 *   .meta/                           ← INTERNAL: metadata for "/" and its document children
 *     .self.content                  ← "/"'s stored representation bytes
 *     .self.json                     ← "/"'s sidecar {contentType, etag, lastModified} = commit record
 *     note.ttl.json                  ← sidecar for document /note.ttl = its commit record
 *   note.ttl                         ← content bytes of document /note.ttl
 *   a/                               ← container /a/ (same shape, recursively)
 *     .meta/
 *       .self.content
 *       .self.json
 *       b%20c.ttl.json
 *     b%20c.ttl                      ← content bytes of document /a/b%20c.ttl
 *   .tmp-&lt;uuid&gt;                      ← INTERNAL: in-flight atomic write (ignored by reads)
 * </pre>
 *
 * <p>Client resource names are mapped through {@link DiskNames}: each raw URI path segment
 * is encoded so that (a) the mapping is injective, and (b) <b>no encoded name can start
 * with a dot</b>. Everything internal ({@code .meta/}, {@code .tmp-*}, {@code .self.*}) is
 * dot-prefixed, so a client resource literally named {@code .meta}, {@code .tmp-x} or
 * {@code .self.json} is stored as {@code %2Emeta} etc. and round-trips as a normal
 * resource — internal files are structurally unreachable from the client namespace.
 * Content types and etags are read ONLY from sidecars, never guessed from file extensions.
 *
 * <h2>Commit protocol and crash consistency</h2>
 *
 * <p>A resource exists iff its <b>commit record</b> exists: the sidecar
 * ({@code .meta/&lt;name&gt;.json}) for documents, {@code .meta/.self.json} inside the
 * directory for containers. Every file is written as tmp-then-{@code ATOMIC_MOVE} in the
 * same directory. The two-step commit is ordered <b>content first, sidecar second</b>;
 * delete is the mirror image, <b>sidecar first, content second</b>. Consequences of a
 * crash at any point:
 *
 * <ul>
 *   <li>Crash before any move: an orphan {@code .tmp-*} file — dot-prefixed, ignored by
 *       every read path.</li>
 *   <li>Crash between content move and sidecar move on CREATE: an orphan content file (or
 *       directory) with no commit record — invisible to {@code get}/{@code children}/
 *       {@code exists}, and silently replaced by the next successful write to that
 *       name.</li>
 *   <li>Crash between content move and sidecar move on REPLACE: new bytes under the old
 *       sidecar. The read path detects this (the stored etag no longer equals
 *       SHA-256(contentType, bytes)) and heals it by rewriting the sidecar with the
 *       recomputed etag — the resource stays consistent as (old contentType, new bytes);
 *       {@code lastModified} keeps its stored value until the next write.</li>
 *   <li>Crash mid-delete: the commit record is already gone, so the resource is gone;
 *       leftover content is orphan debris, invisible and cleaned up by the next write to
 *       that name.</li>
 * </ul>
 *
 * <h2>Metadata semantics</h2>
 *
 * <ul>
 *   <li>ETag: hex SHA-256 over {@code contentType + 0x00 + bytes} — a strong validator
 *       that changes whenever the representation (bytes or media type) changes; identical
 *       rewrites keep their etag, which the contract permits.</li>
 *   <li>{@code lastModified}: second precision, persisted in the sidecar and guarded
 *       monotonic per resource — {@code max(now, previous)} — so it never goes backwards
 *       even if the system clock does, across restarts included.</li>
 *   <li>Fresh-store root: the configured root directory existing does not make
 *       {@code exists("/")} true; the root container exists only once a write has
 *       committed it (first put creates it as an intermediate).</li>
 * </ul>
 *
 * <h2>Scope notes (documented divergences, architect-visible)</h2>
 *
 * <ul>
 *   <li>The store keys resources by URI <em>path</em> only; scheme/authority of returned
 *       child identifiers is inherited from the queried container (single-pod
 *       backend).</li>
 *   <li>Raw path segments containing an encoded slash ({@code %2F}) are rejected with an
 *       {@link IllegalArgumentException} signal: they make raw and decoded slash structure
 *       disagree, which {@code ResourceIdentifier}'s decoded-path semantics cannot
 *       represent. The HTTP layer is expected to reject or normalize them.</li>
 *   <li>Case-insensitive or Unicode-normalizing filesystems (e.g. default APFS) can alias
 *       resource names that differ only by ASCII letter case; non-ASCII is escaped to
 *       ASCII so Unicode normalization does not alias. Same limitation as the reference
 *       implementation.</li>
 * </ul>
 */
public final class FileResourceStore implements ResourceStore {

    static final String META_DIR = ".meta";
    static final String SELF_JSON = ".self.json";
    static final String SELF_CONTENT = ".self.content";
    static final String TMP_PREFIX = ".tmp-";
    private static final String SIDECAR_SUFFIX = ".json";
    private static final byte[] EMPTY = new byte[0];

    private final Path root;
    private final Object lock = new Object();

    public FileResourceStore(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    // ---------------------------------------------------------------- SPI

    @Override
    public Mono<StoredResource> get(ResourceIdentifier identifier) {
        return Mono.fromCallable(() -> doGet(identifier))          // null → empty Mono
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<StoredResource> put(ResourceIdentifier identifier, Representation representation) {
        return Mono.fromCallable(() -> doPut(identifier, representation))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Void> delete(ResourceIdentifier identifier) {
        return Mono.fromCallable(() -> {
                    doDelete(identifier);
                    return Boolean.TRUE;
                })
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    @Override
    public Flux<ResourceIdentifier> children(ResourceIdentifier container) {
        return Mono.fromCallable(() -> doChildren(container))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(Flux::fromIterable);
    }

    @Override
    public Mono<Boolean> exists(ResourceIdentifier identifier) {
        return Mono.fromCallable(() -> doExists(identifier))
                .subscribeOn(Schedulers.boundedElastic());
    }

    // ---------------------------------------------------------------- get

    private StoredResource doGet(ResourceIdentifier identifier) throws IOException {
        synchronized (lock) {
            Path target = diskPath(rawSegments(identifier));
            Path content = identifier.isContainer() ? selfContent(target) : target;
            Path sidecar = identifier.isContainer() ? selfJson(target) : documentSidecar(target);
            if (!Files.isRegularFile(content) || !Files.isRegularFile(sidecar)) {
                return null;                       // fromCallable: null → empty Mono
            }
            return readCommitted(identifier, content, sidecar);
        }
    }

    /** Reads a committed resource, healing a torn replace (stale etag) if detected. */
    private StoredResource readCommitted(ResourceIdentifier identifier, Path content, Path sidecarPath)
            throws IOException {
        Sidecar sidecar = Sidecar.fromJson(Files.readString(sidecarPath, StandardCharsets.UTF_8));
        byte[] bytes = Files.readAllBytes(content);
        String expected = etagFor(sidecar.contentType(), bytes);
        if (!expected.equals(sidecar.etag())) {
            // Crash landed between the content move and the sidecar move of a replace:
            // bytes are newer than the sidecar. Heal to the consistent state
            // (stored contentType, current bytes) with a correct strong validator.
            sidecar = new Sidecar(sidecar.contentType(), expected, sidecar.lastModified());
            writeAtomically(sidecarPath, sidecar.toJson().getBytes(StandardCharsets.UTF_8));
        }
        return new StoredResource(identifier,
                new Representation(sidecar.contentType(), bytes), sidecar.etag(), sidecar.lastModified());
    }

    // ---------------------------------------------------------------- put

    private StoredResource doPut(ResourceIdentifier identifier, Representation representation)
            throws IOException {
        synchronized (lock) {
            List<String> segments = rawSegments(identifier);
            Path target = diskPath(segments);

            // ---- validate the WHOLE chain before creating anything: a failed put must
            // ---- mutate nothing observable (contract; Solid Protocol §3.1 vs §5.3).
            List<Path> missingAncestors = new ArrayList<>();
            for (int len = 0; len < segments.size(); len++) {
                Path ancestor = diskPath(segments.subList(0, len));
                if (committedContainer(ancestor)) {
                    continue;
                }
                if (!ancestor.equals(root) && committedDocument(ancestor)) {
                    throw new CisternException.Conflict(
                            StorageFileMessage.INTERMEDIATE_CONTAINER_BLOCKED.format(
                                    ancestor.getFileName()));
                }
                missingAncestors.add(ancestor);
            }
            Sidecar previous;
            if (identifier.isContainer()) {
                if (!target.equals(root) && committedDocument(target)) {
                    throw new CisternException.Conflict(
                            StorageFileMessage.CONTAINER_NAME_TAKEN_BY_DOCUMENT.format(
                                    identifier.uri()));
                }
                previous = committedContainer(target) ? readSidecar(selfJson(target)) : null;
            } else {
                if (committedContainer(target)) {
                    throw new CisternException.Conflict(
                            StorageFileMessage.DOCUMENT_NAME_TAKEN_BY_CONTAINER.format(
                                    identifier.uri()));
                }
                previous = committedDocument(target) ? readSidecar(documentSidecar(target)) : null;
            }

            // ---- mutate: intermediates root-first (§5.3), then the target.
            for (Path ancestor : missingAncestors) {
                commitContainer(ancestor, EMPTY,
                        new Sidecar(Representation.TURTLE, etagFor(Representation.TURTLE, EMPTY),
                                monotonicNow(null)));
            }
            Instant lastModified = monotonicNow(previous);
            String etag = etagFor(representation.contentType(), representation.data());
            Sidecar sidecar = new Sidecar(representation.contentType(), etag, lastModified);
            if (identifier.isContainer()) {
                commitContainer(target, representation.data(), sidecar);
            } else {
                commitDocument(target, representation.data(), sidecar);
            }
            return new StoredResource(identifier, representation, etag, lastModified);
        }
    }

    /** Container commit: mkdir, then content, then {@code .self.json} (the commit record). */
    private void commitContainer(Path dir, byte[] bytes, Sidecar sidecar) throws IOException {
        if (!dir.equals(root) && Files.isRegularFile(dir)) {
            // Uncommitted file squatting on the name (crash debris) — validated above
            // that no committed document lives here, so it is safe to clear.
            Files.delete(dir);
            Files.deleteIfExists(documentSidecar(dir));
        }
        Files.createDirectories(dir.resolve(META_DIR));
        writeAtomically(selfContent(dir), bytes);
        writeAtomically(selfJson(dir), sidecar.toJson().getBytes(StandardCharsets.UTF_8));
    }

    /** Document commit: content first, sidecar (the commit record) second. */
    private void commitDocument(Path file, byte[] bytes, Sidecar sidecar) throws IOException {
        if (Files.isDirectory(file)) {
            // Uncommitted directory squatting on the name (crash debris) — validated
            // above that no committed container lives here, so it is safe to clear.
            deleteRecursively(file);
        }
        Files.createDirectories(file.getParent().resolve(META_DIR));
        writeAtomically(file, bytes);
        writeAtomically(documentSidecar(file), sidecar.toJson().getBytes(StandardCharsets.UTF_8));
    }

    // ---------------------------------------------------------------- delete

    private void doDelete(ResourceIdentifier identifier) throws IOException {
        synchronized (lock) {
            Path target = diskPath(rawSegments(identifier));
            if (identifier.isContainer()) {
                if (!committedContainer(target)) {
                    throw new CisternException.NotFound(
                            StorageFileMessage.CONTAINER_NOT_FOUND.format(identifier.uri()));
                }
                if (hasCommittedChildren(target)) {
                    throw new CisternException.Conflict(
                            StorageFileMessage.CONTAINER_NOT_EMPTY.format(identifier.uri()));
                }
                Files.delete(selfJson(target));            // decommit — resource ceases to exist
                if (target.equals(root)) {
                    cleanDirectoryContents(root);          // the root dir itself is the deployer's
                } else {
                    deleteRecursively(target);             // only internal/orphan debris remains
                }
            } else {
                if (!committedDocument(target)) {
                    throw new CisternException.NotFound(
                            StorageFileMessage.RESOURCE_NOT_FOUND.format(identifier.uri()));
                }
                Files.delete(documentSidecar(target));     // decommit first…
                Files.deleteIfExists(target);              // …then the content bytes
            }
        }
    }

    // ---------------------------------------------------------------- children / exists

    private List<ResourceIdentifier> doChildren(ResourceIdentifier container) throws IOException {
        if (!container.isContainer()) {
            throw new IllegalArgumentException(
                    StorageFileMessage.CHILDREN_REQUIRES_CONTAINER.format(container.uri()));
        }
        synchronized (lock) {
            Path dir = diskPath(rawSegments(container));
            if (!committedContainer(dir)) {
                return List.of();                          // missing container → empty
            }
            List<ResourceIdentifier> members = new ArrayList<>();
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
                for (Path entry : entries) {
                    String name = entry.getFileName().toString();
                    if (DiskNames.isInternal(name)) {
                        continue;                          // .meta, .tmp-*: never client-visible
                    }
                    if (Files.isDirectory(entry) && committedContainer(entry)) {
                        members.add(childOf(container, DiskNames.decode(name), true));
                    } else if (Files.isRegularFile(entry) && committedDocument(entry)) {
                        members.add(childOf(container, DiskNames.decode(name), false));
                    }
                    // Anything without a commit record is crash debris: ignored.
                }
            }
            return List.copyOf(members);
        }
    }

    private boolean doExists(ResourceIdentifier identifier) {
        synchronized (lock) {
            Path target = diskPath(rawSegments(identifier));
            return identifier.isContainer() ? committedContainer(target) : committedDocument(target);
        }
    }

    // ---------------------------------------------------------------- commit records

    /** A container exists iff its directory carries the {@code .self.json} commit record. */
    private boolean committedContainer(Path dir) {
        return Files.isDirectory(dir) && Files.isRegularFile(selfJson(dir));
    }

    /** A document exists iff content file AND sidecar commit record are both present. */
    private boolean committedDocument(Path file) {
        return Files.isRegularFile(file) && Files.isRegularFile(documentSidecar(file));
    }

    private boolean hasCommittedChildren(Path dir) throws IOException {
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                if (DiskNames.isInternal(name)) {
                    continue;
                }
                if ((Files.isDirectory(entry) && committedContainer(entry))
                        || (Files.isRegularFile(entry) && committedDocument(entry))) {
                    return true;
                }
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- path mapping

    /**
     * Raw URI path → segments. Uses the RAW path so the on-disk name preserves the exact
     * URI spelling (percent-escapes included) and distinct URIs stay distinct.
     */
    private static List<String> rawSegments(ResourceIdentifier identifier) {
        String raw = identifier.uri().getRawPath();
        if (raw == null || !raw.startsWith("/")) {
            throw new IllegalArgumentException(
                    StorageFileMessage.PATH_NOT_ABSOLUTE.format(identifier.uri()));
        }
        String body = raw.substring(1);
        if (body.isEmpty()) {
            return List.of();                              // the root container "/"
        }
        if (identifier.isContainer()) {
            if (!body.endsWith("/")) {
                throw new IllegalArgumentException(
                        StorageFileMessage.CONTAINER_PATH_UNTERMINATED.format(identifier.uri()));
            }
            body = body.substring(0, body.length() - 1);
            if (body.isEmpty()) {
                throw new IllegalArgumentException(
                        StorageFileMessage.PATH_SEGMENT_EMPTY.format(identifier.uri()));
            }
        }
        String[] parts = body.split("/", -1);
        for (String part : parts) {
            if (part.isEmpty()) {
                throw new IllegalArgumentException(
                        StorageFileMessage.PATH_SEGMENT_EMPTY.format(identifier.uri()));
            }
            if (part.toUpperCase(java.util.Locale.ROOT).contains("%2F")) {
                throw new IllegalArgumentException(
                        StorageFileMessage.PATH_SEGMENT_ENCODED_SLASH.format(identifier.uri()));
            }
        }
        return List.of(parts);
    }

    private Path diskPath(List<String> segments) {
        Path path = root;
        for (String segment : segments) {
            path = path.resolve(DiskNames.encode(segment));
        }
        Path normalized = path.normalize();
        if (!normalized.startsWith(root)) {                // structurally impossible; belt & braces
            throw new IllegalStateException(StorageFileMessage.PATH_ESCAPED_ROOT.format(path));
        }
        return normalized;
    }

    private ResourceIdentifier childOf(ResourceIdentifier container, String rawSegment, boolean isContainer) {
        return new ResourceIdentifier(
                URI.create(container.uri().toString() + rawSegment + (isContainer ? "/" : "")));
    }

    private static Path selfJson(Path dir) {
        return dir.resolve(META_DIR).resolve(SELF_JSON);
    }

    private static Path selfContent(Path dir) {
        return dir.resolve(META_DIR).resolve(SELF_CONTENT);
    }

    private static Path documentSidecar(Path file) {
        return file.getParent().resolve(META_DIR).resolve(file.getFileName() + SIDECAR_SUFFIX);
    }

    // ---------------------------------------------------------------- plumbing

    private static Sidecar readSidecar(Path sidecarPath) throws IOException {
        return Sidecar.fromJson(Files.readString(sidecarPath, StandardCharsets.UTF_8));
    }

    /** tmp file in the target's directory, then {@code ATOMIC_MOVE} onto the target. */
    private static void writeAtomically(Path target, byte[] bytes) throws IOException {
        Path tmp = target.resolveSibling(TMP_PREFIX + UUID.randomUUID());
        Files.write(tmp, bytes);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(path)) {
                for (Path entry : entries) {
                    deleteRecursively(entry);
                }
            }
        }
        Files.deleteIfExists(path);
    }

    private static void cleanDirectoryContents(Path dir) throws IOException {
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
            for (Path entry : entries) {
                deleteRecursively(entry);
            }
        }
    }

    /** Second precision, guarded monotonic per resource even under clock regression. */
    private static Instant monotonicNow(Sidecar previous) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        if (previous != null && now.isBefore(previous.lastModified())) {
            return previous.lastModified();
        }
        return now;
    }

    /** Hex SHA-256 over contentType + 0x00 + bytes: strong validator (RFC 9110 §8.8.3). */
    private static String etagFor(String contentType, byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(contentType.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(bytes);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(StorageFileMessage.DIGEST_ALGORITHM_MISSING.format(), e);
        }
    }
}
