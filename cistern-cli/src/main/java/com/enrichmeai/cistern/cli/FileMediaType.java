package com.enrichmeai.cistern.cli;

import com.enrichmeai.cistern.core.rdf.RdfMediaType;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * The media type a local file is sent as, decided by its extension — a closed set, so an enum
 * (ground rule 7), with {@link #OCTET_STREAM} for every extension the set does not name.
 *
 * <p>The set is the document types a company's folder is likely to hold, not a registry. An
 * extension not listed is sent as opaque bytes, which the server stores and serves back
 * verbatim, so nothing is lost but the label. The two RDF types are here because the server
 * parses what arrives under them (Solid Protocol §5.5 names both) and a {@code .ttl} in a
 * folder is meant as Turtle; the CLI itself never parses either (ground rule 5). Their spelling
 * is {@link RdfMediaType}'s, so it cannot drift from the server's.
 *
 * <p>No {@code charset} parameter on the text types: the server keeps a non-RDF type exactly as
 * declared, and a parameter this tool cannot vouch for would be served back as a promise.
 */
enum FileMediaType {

    MARKDOWN("text/markdown", "md", "markdown"),
    PLAIN_TEXT("text/plain", "txt", "text"),
    CSV("text/csv", "csv"),
    HTML("text/html", "html", "htm"),
    JSON("application/json", "json"),
    XML("application/xml", "xml"),
    PDF("application/pdf", "pdf"),
    PNG("image/png", "png"),
    JPEG("image/jpeg", "jpg", "jpeg"),
    GIF("image/gif", "gif"),
    SVG("image/svg+xml", "svg"),
    WEBP("image/webp", "webp"),
    WORD("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx"),
    SPREADSHEET("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx"),
    PRESENTATION("application/vnd.openxmlformats-officedocument.presentationml.presentation", "pptx"),
    ZIP("application/zip", "zip"),

    /** Sent as Turtle, which the server parses and validates; never parsed here. */
    TURTLE(RdfMediaType.TURTLE.contentType(), "ttl"),

    /** Sent as JSON-LD, which the server parses and validates; never parsed here. */
    JSON_LD(RdfMediaType.JSON_LD.contentType(), "jsonld"),

    /** Everything else: opaque bytes, stored and served back as they were sent. */
    OCTET_STREAM("application/octet-stream");

    private static final char EXTENSION_SEPARATOR = '.';

    private final String contentType;
    private final List<String> extensions;

    FileMediaType(String contentType, String... extensions) {
        this.contentType = contentType;
        this.extensions = List.of(extensions);
    }

    /** The {@code Content-Type} field value. */
    String contentType() {
        return contentType;
    }

    /** The type for {@code file}, by its extension. */
    static FileMediaType of(Path file) {
        return of(file.getFileName().toString());
    }

    /**
     * The type for a file called {@code fileName}, by its extension, compared case-insensitively.
     * A name with no extension — including a dotfile such as {@code .env}, whose leading dot
     * is not a separator — is {@link #OCTET_STREAM}.
     */
    static FileMediaType of(String fileName) {
        int dot = fileName.lastIndexOf(EXTENSION_SEPARATOR);
        if (dot <= 0 || dot == fileName.length() - 1) {
            return OCTET_STREAM;
        }
        String extension = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        for (FileMediaType type : values()) {
            if (type.extensions.contains(extension)) {
                return type;
            }
        }
        return OCTET_STREAM;
    }
}
