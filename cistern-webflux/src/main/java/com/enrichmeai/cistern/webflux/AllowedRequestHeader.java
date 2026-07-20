package com.enrichmeai.cistern.webflux;

import org.springframework.http.HttpHeaders;

import java.util.Arrays;
import java.util.List;

/**
 * Every request header field a Solid client may send, enumerated for
 * {@code Access-Control-Allow-Headers} (T2.8).
 *
 * <h2>Why an enumeration and not {@code *}</h2>
 * Two independent reasons, and the first is decisive:
 *
 * <ul>
 *   <li><b>The Fetch standard's wildcard does not cover {@code Authorization}.</b> Fetch
 *       special-cases it: a preflight response of {@code Access-Control-Allow-Headers: *} does
 *       <em>not</em> authorise the {@code Authorization} header, which must be named
 *       explicitly. Since Solid-OIDC (§6) authenticates with exactly that header, a wildcard
 *       configuration would let every unauthenticated request through and fail every
 *       authenticated one — the confusing half-broken state this enum exists to prevent.</li>
 *   <li><b>Solid Protocol §8.1 asks for it.</b> "A server MUST implement the CORS protocol
 *       such that, to the extent possible, the browser allows Solid apps to send any request
 *       and combination of request headers to the server", and "Servers SHOULD also explicitly
 *       list {@code Accept} under {@code Access-Control-Allow-Headers}".</li>
 * </ul>
 *
 * <p>An enum rather than a comma-joined literal (ground rule 7): each entry records which part
 * of the protocol sends it, so the question "may this list be shortened?" has an answer per
 * row instead of being a guess about client behaviour.
 */
enum AllowedRequestHeader {

    /**
     * Solid-OIDC §6 — the DPoP-bound access token. Named explicitly because Fetch excludes
     * {@code Authorization} from the {@code *} wildcard; see the class comment.
     */
    AUTHORIZATION(HttpHeaders.AUTHORIZATION),

    /** RFC 9449 §7.1 — the proof-of-possession JWT that binds the token to the client's key. */
    DPOP(HttpConstants.DPOP),

    /**
     * Solid Protocol §2.1 requires a content-bearing write to declare its media type. Not
     * safelisted for Cistern's purposes: Fetch safelists {@code Content-Type} only for three
     * form/plain-text values, and every RDF type Cistern accepts falls outside them.
     */
    CONTENT_TYPE(HttpHeaders.CONTENT_TYPE),

    /** RFC 5023 §9.7 / LDP §5.2.3.10 — the client's name hint on a {@code POST} (T2.3). */
    SLUG(HttpConstants.SLUG),

    /** LDP §5.2.3.4 — the interaction model requested for a {@code POST}ed child (T2.3). */
    LINK(HttpHeaders.LINK),

    /** RFC 9110 §13.1.1 — the lost-update guard on a conditional write (T2.5). */
    IF_MATCH(HttpHeaders.IF_MATCH),

    /** RFC 9110 §13.1.2 — the create-only guard, and the cache revalidator (T2.5). */
    IF_NONE_MATCH(HttpHeaders.IF_NONE_MATCH),

    /** RFC 9110 §13.1.3 — date-based cache revalidation (T2.5). */
    IF_MODIFIED_SINCE(HttpHeaders.IF_MODIFIED_SINCE),

    /** RFC 9110 §13.1.4 — the date-based lost-update guard (T2.5). */
    IF_UNMODIFIED_SINCE(HttpHeaders.IF_UNMODIFIED_SINCE),

    /**
     * Proactive negotiation between the RDF serializations of Solid Protocol §5.5. Safelisted
     * by Fetch, but §8.1 says to list it anyway: "Servers SHOULD also explicitly list
     * {@code Accept} under {@code Access-Control-Allow-Headers}".
     */
    ACCEPT(HttpHeaders.ACCEPT);

    private static final List<String> FIELD_NAMES =
            Arrays.stream(values()).map(AllowedRequestHeader::fieldName).toList();

    private final String fieldName;

    AllowedRequestHeader(String fieldName) {
        this.fieldName = fieldName;
    }

    String fieldName() {
        return fieldName;
    }

    /**
     * The whole set, in declaration order — what the CORS configuration hands to
     * {@code Access-Control-Allow-Headers}. Derived from {@link #values()}, so a header a
     * future ticket teaches clients to send is registered by adding one constant.
     */
    static List<String> fieldNames() {
        return FIELD_NAMES;
    }
}
