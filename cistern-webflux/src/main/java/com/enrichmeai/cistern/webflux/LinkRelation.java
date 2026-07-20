package com.enrichmeai.cistern.webflux;

import java.util.Locale;

/**
 * The link relation types Cistern reads or writes in a {@code Link} header field (RFC 8288 §3).
 * A closed set, so an enum rather than the string {@code "type"} repeated at the site that emits
 * a link and again at the site that parses one — the two must agree, and this is what makes them
 * agree by construction.
 *
 * <p>Relation names are compared case-insensitively: RFC 8288 §3.3 registers relation types in
 * lower case and defines the comparison as case-insensitive, so {@code rel=TYPE} and
 * {@code rel="type"} name the same relation.
 *
 * <p>One constant today. The neighbours are already visible in the specs Cistern implements —
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
    TYPE("type");

    private final String value;

    LinkRelation(String value) {
        this.value = value;
    }

    /** The relation name as it appears in a {@code rel} parameter. */
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
            if (candidate.toLowerCase(Locale.ROOT).equals(value)) {
                return true;
            }
        }
        return false;
    }
}
