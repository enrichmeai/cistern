package com.enrichmeai.cistern.wac;

import com.enrichmeai.cistern.core.vocab.Acl;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

import org.apache.jena.rdf.model.Resource;

/**
 * A Web Access Control access mode. A closed set, so an enum rather than strings (ground
 * rule 7) — the same constants serve graph parsing, enforcement, and the {@code WAC-Allow}
 * header.
 *
 * <p>The one piece of logic here is the mode algebra, and it is asymmetric in a way that is
 * easy to get wrong in both directions:
 *
 * <ul>
 *   <li><strong>Append ⊂ Write.</strong> WAC: "{@code acl:Append} is a subclass of
 *       {@code acl:Write}". So granting Write also grants Append. The conformance harness
 *       depends on this — its {@code WAC-Allow} table marks a {@code read/write} grant as
 *       <em>not</em> an exact match precisely because append appears too.</li>
 *   <li><strong>Control implies nothing.</strong> It is "access to a class of read and write
 *       operations on an <em>ACL resource</em>" — the ACL, not the resource. A holder of
 *       Control alone may not read the resource itself. Conflating the two is the classic WAC
 *       privilege-escalation bug, and it is why this is spelled out rather than assumed.</li>
 * </ul>
 */
public enum AccessMode {

    /** {@code acl:Read} — view the contents. */
    READ(Acl.READ),

    /**
     * {@code acl:Write} — create, delete or modify. Implies {@link #APPEND}, which is its
     * subclass.
     */
    WRITE(Acl.WRITE),

    /** {@code acl:Append} — add information but not remove. */
    APPEND(Acl.APPEND),

    /** {@code acl:Control} — read and write the resource's ACL. Implies no access to the resource. */
    CONTROL(Acl.CONTROL);

    private final String iri;

    AccessMode(Resource term) {
        this.iri = term.getURI();
    }

    /** The {@code acl:} IRI naming this mode. */
    public String iri() {
        return iri;
    }

    /**
     * The mode named by {@code iri}, or empty if it names none. Empty rather than an
     * exception: an ACL graph may legitimately carry vocabulary this server does not
     * implement, and an unknown mode must be ignored rather than fail the whole evaluation —
     * silently widening access is the danger, and ignoring grants nothing.
     */
    public static Optional<AccessMode> fromIri(String iri) {
        for (AccessMode mode : values()) {
            if (mode.iri.equals(iri)) {
                return Optional.of(mode);
            }
        }
        return Optional.empty();
    }

    /**
     * This mode plus everything it implies — {@code WRITE} expands to {@code {WRITE, APPEND}},
     * every other mode to itself.
     *
     * <p>Expanding when an authorization is <em>parsed</em>, rather than comparing with
     * implication rules at every check, means the granted set is closed under implication by
     * construction: callers can then use plain set containment and cannot forget the rule.
     */
    public Set<AccessMode> withImplied() {
        if (this == WRITE) {
            return Collections.unmodifiableSet(EnumSet.of(WRITE, APPEND));
        }
        return Collections.unmodifiableSet(EnumSet.of(this));
    }

    /** Lower-case name as it appears in a {@code WAC-Allow} header value. */
    public String headerToken() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
