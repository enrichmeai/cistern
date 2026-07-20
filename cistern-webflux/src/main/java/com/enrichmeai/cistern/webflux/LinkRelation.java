package com.enrichmeai.cistern.webflux;

import com.enrichmeai.cistern.core.vocab.Solid;

import java.util.Locale;

/**
 * The link relation types Cistern reads or writes in a {@code Link} header field (RFC 8288 §3).
 * A closed set, so an enum rather than the string {@code "type"} repeated at the site that emits
 * a link and again at the site that parses one — the two must agree, and this is what makes them
 * agree by construction.
 *
 * <h2>Both kinds of relation type, compared the same way</h2>
 * RFC 8288 §2.1 divides relation types into <em>registered</em> ones, which are bare tokens, and
 * <em>extension</em> ones, which are URIs: "Applications that don't wish to register a relation
 * type can use an extension relation type, which is a URI that uniquely identifies the relation
 * type." {@link #TYPE} is the first kind and {@link #STORAGE_DESCRIPTION} the second, and both
 * are rows here because on the wire they occupy the same {@code rel} parameter.
 *
 * <p>Comparison is case-insensitive for both, which is what the RFC asks of each independently:
 * registered names "MUST be compared character by character in a case-insensitive fashion"
 * (§2.1.1), and extension types "MUST be compared as strings ... in a case-insensitive fashion,
 * character by character" (§2.1.2). So {@link #comparisonValue} is folded once at construction
 * rather than the emitted {@link #value} being assumed lower case — an assumption
 * {@code solid:storageDescription} would have broken silently, since a camel-cased relation
 * would never have matched itself.
 *
 * <p>The remaining neighbours are already visible in the specs Cistern implements —
 * {@code describedby} for an LDP-NR's associated description (LDP §5.2.3.12),
 * {@code http://www.w3.org/ns/ldp#constrainedBy} (LDP §4.2.1.6), and {@code acl} (WAC) — and
 * each becomes a row here rather than a literal.
 */
enum LinkRelation {

    /**
     * {@code rel="type"} — RFC 6903 §6: "the type link relation type ... identifies a resource
     * that describes a type of the link's context". LDP §4.2.1.4 makes it the way a server
     * advertises an LDP interface, and LDP §5.2.3.4 makes it the way a client requests one on
     * {@code POST}.
     */
    TYPE("type"),

    /**
     * {@code rel="http://www.w3.org/ns/solid/terms#storageDescription"} — Solid Protocol §4.1:
     * "Servers MUST include the {@code Link} header field with
     * {@code rel="http://www.w3.org/ns/solid/terms#storageDescription"} targeting the URI of the
     * storage description resource in the response of HTTP {@code GET}, {@code HEAD} and
     * {@code OPTIONS} requests targeting a resource in a storage."
     *
     * <p>The name is the vocabulary IRI itself, read from {@link Solid} rather than written out,
     * so the relation Cistern emits and the term the specification defines are one string.
     */
    STORAGE_DESCRIPTION(Solid.STORAGE_DESCRIPTION.getURI());

    private final String value;
    private final String comparisonValue;

    LinkRelation(String value) {
        this.value = value;
        this.comparisonValue = value.toLowerCase(Locale.ROOT);
    }

    /** The relation name as it appears in a {@code rel} parameter, in its emitted spelling. */
    String value() {
        return value;
    }

    /**
     * Whether a {@code rel} parameter value names this relation. RFC 8288 §3.3 allows the
     * parameter to carry "a whitespace-separated list of relation types", so a single-token
     * comparison would miss {@code rel="type other"}.
     */
    boolean isNamedBy(String relParameterValue) {
        for (String candidate : relParameterValue.split("\\s+")) {
            if (candidate.toLowerCase(Locale.ROOT).equals(comparisonValue)) {
                return true;
            }
        }
        return false;
    }
}
