package com.enrichmeai.cistern.cli;

import com.enrichmeai.cistern.core.ResourceIdentifier;

import java.io.StringReader;
import java.io.StringWriter;
import java.net.URI;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;

import jakarta.json.Json;
import jakarta.json.JsonException;
import jakarta.json.JsonNumber;
import jakarta.json.JsonObject;
import jakarta.json.JsonObjectBuilder;
import jakarta.json.JsonReader;
import jakarta.json.JsonString;
import jakarta.json.JsonValue;
import jakarta.json.JsonWriter;
import jakarta.json.stream.JsonGenerator;

/**
 * Everything this folder has sent to the pod, as of the last successful step: the memory
 * between runs. Immutable; {@link SyncStateFile} holds the current one and persists each change.
 *
 * <p>Its JSON form is the state file's content — the container everything was sent to under
 * {@value #TARGET_FIELD}, then one object per resource under {@value #RESOURCES_FIELD}, keyed
 * by {@link RelativePath}, a document carrying {@value #ETAG_FIELD} and {@value #SHA256_FIELD},
 * a container (key ending in {@code /}) carrying nothing — behind a {@value #VERSION_FIELD} so
 * a later shape can be told apart from a damaged file. Keys are written sorted, so two runs
 * that sent the same things write the same bytes, and the file is readable by the person whose
 * folder it sits in: on a conflict the message tells them which entry to correct.
 *
 * <p>The target is part of the state because the validators are only true of that one place:
 * a folder remembered as sent to {@code /firms/acme/docs/} knows nothing about
 * {@code /firms/acme/other/}, and a run there would otherwise carry {@code If-Match} tags for
 * resources that were never sent. {@link SyncStateFile} refuses the mismatch.
 *
 * @param target    the container on the server every entry is relative to
 * @param resources what has been sent, by relative path; never null values
 */
record SyncState(ResourceIdentifier target, SortedMap<RelativePath, SyncedResource> resources) {

    /** The shape this class writes; a file with another version is refused rather than guessed at. */
    static final int FORMAT_VERSION = 1;

    private static final String VERSION_FIELD = "version";
    private static final String TARGET_FIELD = "target";
    private static final String RESOURCES_FIELD = "resources";
    private static final String ETAG_FIELD = "etag";
    private static final String SHA256_FIELD = "sha256";
    private static final String LINE_END = "\n";
    private static final Map<String, ?> PRETTY = Map.of(JsonGenerator.PRETTY_PRINTING, true);

    SyncState {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(resources, "resources");
        if (!target.isContainer()) {
            throw new IllegalArgumentException(CliMessage.INVALID_TARGET_CONTAINER.format(target.uri()));
        }
        resources = Collections.unmodifiableSortedMap(new TreeMap<>(resources));
    }

    /** Nothing sent to {@code target} yet — a folder synced there for the first time. */
    static SyncState empty(ResourceIdentifier target) {
        return new SyncState(target, new TreeMap<>());
    }

    Optional<SyncedResource> get(RelativePath path) {
        return Optional.ofNullable(resources.get(path));
    }

    SyncState with(RelativePath path, SyncedResource resource) {
        TreeMap<RelativePath, SyncedResource> copy = new TreeMap<>(resources);
        copy.put(Objects.requireNonNull(path, "path"), Objects.requireNonNull(resource, "resource"));
        return new SyncState(target, copy);
    }

    SyncState without(RelativePath path) {
        TreeMap<RelativePath, SyncedResource> copy = new TreeMap<>(resources);
        copy.remove(Objects.requireNonNull(path, "path"));
        return new SyncState(target, copy);
    }

    // ---- the file's content ----------------------------------------------------------------

    /** This state as the file's text: pretty-printed, keys sorted, newline-terminated. */
    String toJson() {
        JsonObjectBuilder entries = Json.createObjectBuilder();
        resources.forEach((path, resource) -> entries.add(path.value(), switch (resource) {
            case SyncedResource.Document document -> Json.createObjectBuilder()
                    .add(ETAG_FIELD, document.etag().value())
                    .add(SHA256_FIELD, document.sha256().hex());
            case SyncedResource.Container _ -> Json.createObjectBuilder();
        }));
        JsonObject root = Json.createObjectBuilder()
                .add(VERSION_FIELD, FORMAT_VERSION)
                .add(TARGET_FIELD, target.uri().toString())
                .add(RESOURCES_FIELD, entries)
                .build();
        StringWriter text = new StringWriter();
        try (JsonWriter writer = Json.createWriterFactory(PRETTY).createWriter(text)) {
            writer.writeObject(root);
        }
        return text + LINE_END;
    }

    /**
     * The state a file's text describes.
     *
     * @throws IllegalArgumentException if the text is not this class's shape; the message says
     *     what was wrong, for {@link CliMessage#STATE_FILE_MALFORMED} to quote
     */
    static SyncState fromJson(String json) {
        JsonObject root;
        try (JsonReader reader = Json.createReader(new StringReader(json))) {
            root = reader.readObject();
        } catch (JsonException | IllegalStateException e) {
            throw new IllegalArgumentException(CliMessage.STATE_FILE_NOT_JSON.format(e.getMessage()), e);
        }
        JsonValue version = root.get(VERSION_FIELD);
        // isIntegral as well as the value: JsonNumber.intValue() truncates, so a version of 1.5
        // would otherwise read as 1 and a file this class never wrote would be treated as its own.
        if (!(version instanceof JsonNumber number) || !number.isIntegral() || number.intValue() != FORMAT_VERSION) {
            throw new IllegalArgumentException(
                    CliMessage.STATE_FILE_VERSION.format(version == null ? null : version.toString(), FORMAT_VERSION));
        }
        if (!(root.get(TARGET_FIELD) instanceof JsonString target)) {
            throw new IllegalArgumentException(CliMessage.STATE_FILE_NO_FIELD.format(TARGET_FIELD));
        }
        if (!(root.get(RESOURCES_FIELD) instanceof JsonObject entries)) {
            throw new IllegalArgumentException(CliMessage.STATE_FILE_NO_FIELD.format(RESOURCES_FIELD));
        }
        TreeMap<RelativePath, SyncedResource> resources = new TreeMap<>();
        entries.forEach((key, value) -> resources.put(pathOf(key), resourceOf(key, value)));
        return new SyncState(targetOf(target.getString()), resources);
    }

    private static ResourceIdentifier targetOf(String text) {
        try {
            ResourceIdentifier target = new ResourceIdentifier(URI.create(text));
            if (!target.isContainer()) {
                throw new IllegalArgumentException(CliMessage.INVALID_TARGET_CONTAINER.format(text));
            }
            return target;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(CliMessage.STATE_FILE_BAD_TARGET.format(text, e.getMessage()), e);
        }
    }

    private static RelativePath pathOf(String key) {
        try {
            return new RelativePath(key);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(CliMessage.STATE_FILE_BAD_ENTRY.format(key, e.getMessage()), e);
        }
    }

    private static SyncedResource resourceOf(String key, JsonValue value) {
        if (!(value instanceof JsonObject fields)) {
            throw new IllegalArgumentException(
                    CliMessage.STATE_FILE_BAD_ENTRY.format(key, CliMessage.STATE_FILE_ENTRY_NOT_OBJECT.format()));
        }
        boolean container = new RelativePath(key).isContainer();
        if (container) {
            if (!fields.isEmpty()) {
                throw new IllegalArgumentException(
                        CliMessage.STATE_FILE_BAD_ENTRY.format(key, CliMessage.STATE_FILE_CONTAINER_HAS_FIELDS.format()));
            }
            return new SyncedResource.Container();
        }
        if (!(fields.get(ETAG_FIELD) instanceof JsonString etag)
                || !(fields.get(SHA256_FIELD) instanceof JsonString sha256)) {
            throw new IllegalArgumentException(CliMessage.STATE_FILE_BAD_ENTRY.format(
                    key, CliMessage.STATE_FILE_DOCUMENT_FIELDS.format(ETAG_FIELD, SHA256_FIELD)));
        }
        try {
            return new SyncedResource.Document(new EntityTagHeader(etag.getString()), new ContentHash(sha256.getString()));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(CliMessage.STATE_FILE_BAD_ENTRY.format(key, e.getMessage()), e);
        }
    }
}
