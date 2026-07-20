package com.enrichmeai.cistern.core.vocab;

import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.ResourceFactory;

/**
 * The PIM ("personal information model") workspace vocabulary terms Cistern uses
 * (<a href="http://www.w3.org/ns/pim/space#">http://www.w3.org/ns/pim/space#</a>).
 *
 * <p>Solid Protocol §4.1 names exactly one of them normatively: the storage resource "is the
 * root container for all of its contained resources", and "Servers MUST advertise the storage
 * resource by including the HTTP {@code Link} header field with {@code rel="type"} targeting
 * {@code http://www.w3.org/ns/pim/space#Storage} when responding to storage's request URI".
 * The same IRI is the one required statement of the storage description resource — "Storage
 * description statements include the properties: {@code rdf:type} — A class whose URI is
 * {@code http://www.w3.org/ns/pim/space#Storage}".
 *
 * <h2>{@code Storage} and {@code storage} are different terms</h2>
 * The namespace contains both, they differ only in the case of one letter, and confusing them
 * is the standing hazard of this section:
 *
 * <ul>
 *   <li><b>{@link #STORAGE}</b> — {@code pim:Storage}, upper case, is a <em>class</em>. It types
 *       the storage root, and it is the target IRI of a {@code Link} whose relation is the
 *       registered {@code type} relation. It is never itself a link relation.</li>
 *   <li><b>{@code pim:storage}</b>, lower case, is a <em>predicate</em> relating an agent to a
 *       storage they own. It belongs in a WebID profile document, which is a resource Cistern
 *       stores rather than a header it emits — the conformance harness reads it from alice's
 *       profile to find her pod ({@code TestSubject.findStorage}), which is pod provisioning
 *       (T5.4), not the discovery surface. It is therefore deliberately absent from this class
 *       until something emits it; naming it here unused would be the more confusing half of the
 *       pair sitting in the codebase with no call site to explain it.</li>
 * </ul>
 *
 * <p>Jena ships no PIM vocabulary class, so these constants are defined here. They are created
 * via {@link ResourceFactory} and belong to no model, like {@code Ldp}'s.
 */
public final class Pim {

    /** The PIM workspace namespace, {@value}. */
    public static final String NS = "http://www.w3.org/ns/pim/space#";

    /**
     * {@code pim:Storage} — the class of a storage's root container (Solid Protocol §4.1).
     * Emitted as the target of a {@code Link ... rel="type"} on the root, and asserted as the
     * {@code rdf:type} of the storage in the storage description resource. Those are two
     * spellings of one fact, which is why they read the same constant.
     */
    public static final Resource STORAGE = ResourceFactory.createResource(NS + "Storage");

    private Pim() {
        // constants only
    }
}
