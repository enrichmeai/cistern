package com.enrichmeai.cistern.webflux;

import org.springframework.http.HttpHeaders;

import java.util.Arrays;
import java.util.List;

/**
 * Every response header field Cistern emits, enumerated for
 * {@code Access-Control-Expose-Headers} (T2.8).
 *
 * <h2>Why this list exists at all</h2>
 * By default the Fetch standard lets a cross-origin script read only the
 * <em>CORS-safelisted response headers</em>; everything else is invisible to the app even
 * though it arrived on the wire. Solid's protocol lives almost entirely in those invisible
 * headers — the validator a conditional write needs ({@code ETag}), the URI of a resource just
 * created ({@code Location}), the interaction model ({@code Link}), what the agent may do
 * ({@code WAC-Allow}), what the resource accepts ({@code Allow}, {@code Accept-*}). A
 * browser-based Solid app that cannot read them cannot speak the protocol.
 *
 * <p>Solid Protocol §8.1 therefore makes it a requirement rather than a courtesy: "The server
 * MUST make all used response headers readable for the Solid app through
 * {@code Access-Control-Expose-Headers}", and — the reason this is an enumeration and not a
 * {@code "*"} — "servers SHOULD explicitly enumerate all used response header fields under
 * {@code Access-Control-Expose-Headers} rather than resorting to {@code *}".
 *
 * <p>An enum rather than a comma-joined literal (ground rule 7): each entry carries the
 * reason it is here, and the set is one declaration that the CORS configuration renders,
 * so a header added to a response later has exactly one place to be registered.
 *
 * <h2>Scope</h2>
 * "All used response headers" is meant literally, so entries that the Fetch safelist already
 * exposes ({@link #CONTENT_TYPE}, {@link #CONTENT_LENGTH}, {@link #LAST_MODIFIED}) are listed
 * too. They are redundant to a browser and free on the wire, and listing them keeps this enum
 * a truthful inventory of what Cistern emits rather than a diff against a safelist that Fetch
 * may revise.
 */
enum ExposedResponseHeader {

    /**
     * The strong validator (RFC 9110 §8.8.3). Without it a browser app can never make a
     * conditional write, so every lost-update guard T2.5 built is unreachable from a Solid app.
     */
    ETAG(HttpHeaders.ETAG),

    /** Second-precision modification date (RFC 9110 §8.8.2). Already Fetch-safelisted. */
    LAST_MODIFIED(HttpHeaders.LAST_MODIFIED),

    /**
     * The interaction model and resource type (LDP §4.2.1.4, Solid Protocol §4.2). A client
     * distinguishes a container from a document by reading this.
     */
    LINK(HttpHeaders.LINK),

    /**
     * The URI of the resource a {@code POST} just created (LDP §5.2.3.1). Unreadable means the
     * app cannot address what it just made — the single most damaging omission in this list.
     */
    LOCATION(HttpHeaders.LOCATION),

    /** The methods the target resource supports (Solid Protocol §5.2, RFC 9110 §10.2.1). */
    ALLOW(HttpHeaders.ALLOW),

    /** Media types acceptable in a {@code PUT} (Solid Protocol §5.2, §12.1.1). */
    ACCEPT_PUT(HttpConstants.ACCEPT_PUT),

    /** Media types acceptable in a {@code POST} (LDP §7.1.2). */
    ACCEPT_POST(HttpConstants.ACCEPT_POST),

    /** Media types acceptable in a {@code PATCH} (RFC 5789, Solid Protocol §5.2). */
    ACCEPT_PATCH(HttpHeaders.ACCEPT_PATCH),

    /**
     * The agent's access modes on this resource (WAC §5). Emitted from T3.x; listed now so the
     * CORS contract does not have to change when the WAC ticket lands.
     */
    WAC_ALLOW(HttpConstants.WAC_ALLOW),

    /** Which request headers selected this representation (RFC 9110 §12.5.5). */
    VARY(HttpHeaders.VARY),

    /** The media type of the representation served. Already Fetch-safelisted. */
    CONTENT_TYPE(HttpHeaders.CONTENT_TYPE),

    /** Body length, set explicitly so a {@code HEAD} reports it. Already Fetch-safelisted. */
    CONTENT_LENGTH(HttpHeaders.CONTENT_LENGTH);

    private static final List<String> FIELD_NAMES =
            Arrays.stream(values()).map(ExposedResponseHeader::fieldName).toList();

    private final String fieldName;

    ExposedResponseHeader(String fieldName) {
        this.fieldName = fieldName;
    }

    String fieldName() {
        return fieldName;
    }

    /**
     * The whole set, in declaration order — what the CORS configuration hands to
     * {@code Access-Control-Expose-Headers}. Derived from {@link #values()}, so adding a
     * constant is the only edit an added response header needs.
     */
    static List<String> fieldNames() {
        return FIELD_NAMES;
    }
}
