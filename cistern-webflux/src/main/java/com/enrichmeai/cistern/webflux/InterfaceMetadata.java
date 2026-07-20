package com.enrichmeai.cistern.webflux;

import org.springframework.http.HttpHeaders;

/**
 * Writes a resource's LDP interface metadata onto a response: {@code Allow}, the
 * {@code Accept-Put} / {@code Accept-Post} / {@code Accept-Patch} companions, and the
 * {@code Link rel="type"} values that advertise the interaction model.
 *
 * <p>The single place any of those five headers is emitted from a {@link ResourceKind} (#60).
 * Five handlers — read (T2.1), write (T2.2), create (T2.3), patch (T2.7) and OPTIONS (T2.8) —
 * had grown the same eight lines and their own copy of {@code setIfPresent}, and
 * {@link com.enrichmeai.cistern.webflux.error.ProblemMapper} a sixth, narrower copy for the
 * {@code Allow} on a 405.
 *
 * <p>Consolidating them is not only de-duplication: Solid Protocol §5.2 requires the
 * {@code Accept-*} field values to "correspond to acceptable HTTP methods listed in
 * {@code Allow} header field value", and that is a property of a <em>response</em>, so it can
 * only be guaranteed where the response is written. Six independent copies could each satisfy
 * it today and drift tomorrow; one writer cannot. A resource kind's advertised capabilities are
 * now defined in exactly one place ({@link ResourceKind}) and emitted from exactly one place
 * (here).
 *
 * <h2>{@link ResourceKind} remains the source of truth</h2>
 * This class decides nothing about what a kind permits — it only renders what the table already
 * says. In particular {@code Allow} is {@link ResourceKind#allow()}, itself derived from the
 * same method list {@code ResourceKind.permits} answers from, so the {@code Allow} on a
 * response and the applicability of a precondition (RFC 9110 §13.2.1, T2.5) remain one fact.
 * {@code ResourceKindTest} pins that invariant.
 *
 * <p>Static rather than a Spring bean: it holds no state and has no collaborator to inject, and
 * the handlers that call it write their headers from static lambdas. A bean would add wiring
 * without adding a seam anyone needs to substitute.
 *
 * <p>Public, unlike most of this package, because {@code ProblemMapper} lives in the
 * {@code .error} subpackage and a 405 must carry the same {@code Allow} as a successful
 * response to the same resource — which is precisely the drift this class exists to prevent,
 * so it would be self-defeating to put the mapper outside its reach.
 */
public final class InterfaceMetadata {

    private InterfaceMetadata() {
        // static utility
    }

    /**
     * Writes the full interface metadata for {@code kind} — what every successful response
     * carries (Solid Protocol §5.2, LDP 1.0 §4.2.1.4).
     *
     * <p>{@code Link} values are added rather than set, so any the caller has already written
     * survive; the rest are set, so calling this twice cannot double a field value.
     */
    public static void write(HttpHeaders headers, ResourceKind kind) {
        for (String linkValue : kind.linkTypeValues()) {
            headers.add(HttpHeaders.LINK, linkValue);
        }
        writeAllow(headers, kind);
        setIfPresent(headers, HttpConstants.ACCEPT_PUT, kind.acceptPut());
        setIfPresent(headers, HttpConstants.ACCEPT_POST, kind.acceptPost());
        setIfPresent(headers, HttpHeaders.ACCEPT_PATCH, kind.acceptPatch());
    }

    /**
     * {@link #write} plus the storage-description {@code Link} of Solid Protocol §4.1: "Servers
     * MUST include the {@code Link} header field with
     * {@code rel="http://www.w3.org/ns/solid/terms#storageDescription"} targeting the URI of the
     * storage description resource in the response of HTTP {@code GET}, {@code HEAD} and
     * {@code OPTIONS} requests targeting a resource in a storage."
     *
     * <p>A separate entry point rather than a line inside {@link #write}, because §4.1 scopes the
     * requirement to those three methods. {@code write} is also what {@code PUT}, {@code POST}
     * and {@code PATCH} responses go through, and folding the link in there would put it on
     * responses the specification does not ask it of — a behaviour change wearing the clothes of
     * a refactor.
     *
     * <p>The link is a {@code String} the caller supplies rather than a {@link StorageDescription}
     * this class reaches into, keeping this a pure header writer with no bean to inject: the link
     * is a property of the <em>storage</em>, not of the resource kind, which is exactly why
     * {@link ResourceKind} does not carry it either.
     *
     * <p>Added after the {@code rel="type"} values, never set — {@code Link} is a list field
     * (RFC 8288 §3) and the interaction-model links are equally mandatory (LDP §4.2.1.4).
     */
    public static void write(HttpHeaders headers, ResourceKind kind, String storageDescriptionLink) {
        write(headers, kind);
        headers.add(HttpHeaders.LINK, storageDescriptionLink);
    }

    /**
     * Writes {@code Allow} alone — what RFC 9110 §15.5.6 makes mandatory on a 405 ("The origin
     * server MUST generate an {@code Allow} header field in a 405 response containing a list of
     * the target resource's currently supported methods") and no more.
     *
     * <p>Deliberately narrower than {@link #write}: a 405 says which methods the resource
     * supports, and adding the {@code Accept-*} companions would go further than the refusal
     * knows. RFC 5789 §3.1 makes {@code Accept-Patch}'s presence "an implicit indication that
     * PATCH is allowed on the resource", which a 405 for {@code PATCH} would then contradict.
     * The value is the same {@link ResourceKind#allow()} a successful response would carry, so
     * a refusal and a success advertise the same interface (Solid Protocol §5.2).
     */
    public static void writeAllow(HttpHeaders headers, ResourceKind kind) {
        headers.set(HttpHeaders.ALLOW, kind.allow());
    }

    /** Null means the method is absent from {@code Allow}, so the companion field is omitted. */
    private static void setIfPresent(HttpHeaders headers, String name, String value) {
        if (value != null) {
            headers.set(name, value);
        }
    }
}
