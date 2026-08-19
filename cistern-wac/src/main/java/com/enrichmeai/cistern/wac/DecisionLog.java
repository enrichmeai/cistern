package com.enrichmeai.cistern.wac;

import com.enrichmeai.cistern.core.ResourceIdentifier;
import com.enrichmeai.cistern.core.ResourceStore;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.Optional;

/**
 * The JSON Lines decision log (T5.9): which store it lives in, where in that store, and how a
 * moment in time maps to a file.
 *
 * <p>Layout, under {@link #root}:
 *
 * <pre>
 * decisions/
 *   2026-08-18.jsonl        ← every decision taken on 18 August 2026, UTC, one JSON object per line
 *   2026-08-19.jsonl
 * </pre>
 *
 * <p>One file per UTC day: bounded by construction, named so a human with {@code ls} finds
 * the day they want, and partitioned by the same clock the records themselves carry, so a
 * record is in the file its {@code at} says it is in — never in the file that was current when
 * it happened to be written.
 *
 * <h2>Written through the storage SPI, outside the pod's URI space</h2>
 * The log is written through a {@link ResourceStore}, so it inherits whatever durability and
 * atomicity the backend gives pod content, and a new backend (object storage, T1.6) carries the
 * audit trail with it for free. But it is <strong>not</strong> pod content: it must not appear
 * in a container listing, must not be readable with Read or deletable with Write, and must not
 * be addressable by an HTTP path at all. So it lives in its own URI space — {@link #SCHEME}, a
 * scheme no request can name — in a store whose root the deployment places beside the pod's
 * (by default {@code <storage root>/.cistern/}). The file backend never lists a dot-prefixed
 * directory and encodes a client's leading dot as {@code %2E}, so from the pod's side the
 * subtree is structurally unreachable rather than merely hidden. The only way to read it is the
 * Control-protected receipts query, which returns decisions, never bytes.
 *
 * @param store the store the log is written through
 * @param root  the container in {@code store} under which {@code decisions/} sits — a
 *              container identifier, so it ends in {@code /}
 */
public record DecisionLog(ResourceStore store, ResourceIdentifier root) {

    /**
     * The URI scheme of the log's own space. Not {@code http}: an identifier under the pod's
     * base URL would look addressable, and this one must not be.
     */
    public static final String SCHEME = "cistern-audit";

    /** The default root: the whole of the log's URI space. */
    public static final URI DEFAULT_ROOT = URI.create(SCHEME + ":///");

    /** The container, under the root, that holds the day files. */
    public static final String DECISIONS_SEGMENT = "decisions";

    /** JSON Lines, and the suffix that says so. */
    public static final String FILE_SUFFIX = ".jsonl";

    /** The media type of a day file, and of a receipts response: newline-delimited JSON. */
    public static final String MEDIA_TYPE = "application/x-ndjson";

    /** The record separator of JSON Lines: a bare line feed, never {@code \r\n}. */
    public static final String LINE_SEPARATOR = "\n";

    /** {@code YYYY-MM-DD}, ISO 8601, which sorts lexically as it sorts chronologically. */
    public static final DateTimeFormatter DAY = DateTimeFormatter.ISO_LOCAL_DATE;

    private static final String CONTAINER_SUFFIX = "/";

    public DecisionLog {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(root, "root");
        if (!root.isContainer()) {
            throw new IllegalArgumentException(WacMessage.DECISION_LOG_ROOT_NOT_CONTAINER.format(root.uri()));
        }
    }

    /** The log's default root in {@code store}. */
    public static DecisionLog in(ResourceStore store) {
        return new DecisionLog(store, new ResourceIdentifier(DEFAULT_ROOT));
    }

    /** The {@code decisions/} container. */
    public ResourceIdentifier decisions() {
        return new ResourceIdentifier(URI.create(root.uri() + DECISIONS_SEGMENT + CONTAINER_SUFFIX));
    }

    /** The day file that holds decisions taken on {@code day}. */
    public ResourceIdentifier fileFor(LocalDate day) {
        Objects.requireNonNull(day, "day");
        return new ResourceIdentifier(URI.create(decisions().uri() + DAY.format(day) + FILE_SUFFIX));
    }

    /** The day file that holds a decision taken {@code at}. */
    public ResourceIdentifier fileFor(Instant at) {
        return fileFor(dayOf(at));
    }

    /** The UTC calendar day {@code at} falls on — the partition key. */
    public static LocalDate dayOf(Instant at) {
        return LocalDate.ofInstant(Objects.requireNonNull(at, "at"), ZoneOffset.UTC);
    }

    /**
     * The day a member of {@link #decisions()} holds, read back from its name; empty for
     * anything in the container that is not a well-formed day file. Empty rather than an error
     * because a listing may legitimately contain things the log did not write — a backend's own
     * artefacts, an operator's note — and a scan should step over them, not stop.
     */
    public Optional<LocalDate> dayOf(ResourceIdentifier file) {
        Objects.requireNonNull(file, "file");
        String uri = file.uri().toString();
        String prefix = decisions().uri().toString();
        if (!uri.startsWith(prefix) || !uri.endsWith(FILE_SUFFIX)) {
            return Optional.empty();
        }
        String name = uri.substring(prefix.length(), uri.length() - FILE_SUFFIX.length());
        try {
            return Optional.of(LocalDate.parse(name, DAY));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }
}
