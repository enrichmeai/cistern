package com.enrichmeai.cistern.wac;

import com.enrichmeai.cistern.core.Representation;
import com.enrichmeai.cistern.core.ResourceIdentifier;
import com.enrichmeai.cistern.core.ResourceStore;
import com.enrichmeai.cistern.core.rdf.RdfIo;
import com.enrichmeai.cistern.core.vocab.Acl;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.RDF;
import reactor.core.publisher.Mono;

/**
 * Brings a pod into being: a root container plus the ACL that makes somebody its owner.
 *
 * <p>Without the second half, WAC makes a new container inert rather than secure — the engine
 * denies by default, the container has no ACL, so every request against it is refused,
 * including the owner's, and there is no way in to write the ACL that would let anyone in. The
 * two have to be created together, and WAC requires the same of the storage root: "The ACL
 * resource of the root container MUST include an Authorization allowing the {@code acl:Control}
 * access privilege." This is the one place that pairing is spelled, whether the pod is the
 * storage root seeded for a single owner at boot, one of several roots seeded from
 * configuration, or a matter container an application provisions for a client.
 *
 * <h2>The ACL it writes</h2>
 * Full access — every {@link AccessMode} — for the owner, on the root and everything under it:
 * {@code acl:accessTo} for the root itself <em>and</em> {@code acl:default} so descendants
 * inherit, because the two are separate statements and granting only the first would leave
 * every child unreachable. Nothing is granted to {@code foaf:Agent}: a new pod is private until
 * its owner says otherwise. That is the whole point of the exercise, and it is what makes an
 * anonymous {@code DELETE} return 401 instead of 204.
 *
 * <h2>Idempotent, and it never overwrites</h2>
 * A root that already has an ACL is reported as {@link PodProvisioned.AlreadyExists} and left
 * exactly as it was — its ACL <em>and</em> its container. Rewriting the ACL on every boot, or
 * on every run of a provisioning command, would silently undo any narrowing the owner had since
 * applied; a restart is not a request to reset permissions. A root that exists without an ACL
 * (a container someone made with {@code PUT} and never secured) is completed rather than
 * refused: its ACL is written and it is reported as {@link PodProvisioned.Created}, since that
 * is when it became a pod.
 *
 * <h2>Boundaries</h2>
 * The only I/O is through {@link ResourceStore}; nothing here knows about HTTP, Spring or
 * configuration. "Does the ACL exist?" and "write it" are two store calls, so two provisioners
 * racing on one absent root can both write — the same known, tracked race as
 * {@code LdpService.put}, confined here to boot and to an operator's command line, and
 * harmless in outcome: both write the same ACL.
 */
public final class PodProvisioner {

    /** Fragment naming the owner's authorization inside the ACL document: {@code <acl>#owner}. */
    static final String OWNER_AUTHORIZATION_FRAGMENT = "#owner";

    /** What an owner holds: every mode there is, Control included — the modes closed over themselves. */
    static final Set<AccessMode> OWNER_MODES = Collections.unmodifiableSet(EnumSet.allOf(AccessMode.class));

    /**
     * A container with no client-authored triples: what {@code PUT} creates for a missing
     * intermediate, and so what a freshly provisioned root looks like until its owner
     * describes it. Containment is derived on read and never stored, so empty is complete.
     */
    private static final Representation EMPTY_CONTAINER = new Representation(Representation.TURTLE, new byte[0]);

    private final ResourceStore store;

    public PodProvisioner(ResourceStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    /**
     * Provision the pod {@code spec} describes.
     *
     * @return {@link PodProvisioned.Created} if the owner ACL was written (creating the root
     *     container first if it was absent); {@link PodProvisioned.AlreadyExists} if the root
     *     already had an ACL, in which case nothing was written. Store failures — a document
     *     occupying the root's name, a backend that cannot write — propagate as error signals.
     */
    public Mono<PodProvisioned> provision(PodSpec spec) {
        Objects.requireNonNull(spec, "spec");
        ResourceIdentifier root = spec.root();
        ResourceIdentifier acl = spec.acl();
        return store.exists(acl)
                .flatMap(aclExists -> aclExists
                        ? Mono.<PodProvisioned>just(new PodProvisioned.AlreadyExists(root))
                        : ensureContainer(root)
                                .then(store.put(acl, ownerAcl(spec)))
                                .thenReturn(new PodProvisioned.Created(root, acl)));
    }

    /** Create the root container if it is absent; leave it untouched if it is there. */
    private Mono<Void> ensureContainer(ResourceIdentifier root) {
        return store.exists(root)
                .flatMap(exists -> exists
                        ? Mono.<Void>empty()
                        : store.put(root, EMPTY_CONTAINER).then());
    }

    /**
     * The owner ACL for {@code spec}, serialized as Turtle — {@link #ownerAclGraph} written out.
     * This is what {@link #provision} stores.
     */
    public static Representation ownerAcl(PodSpec spec) {
        return RdfIo.serialize(ownerAclGraph(spec), Representation.TURTLE);
    }

    /**
     * The owner ACL for {@code spec} as a graph: one {@code acl:Authorization}, named
     * {@code <acl>#owner}, granting {@link #OWNER_MODES} to the owner on the root
     * ({@code acl:accessTo}) and on everything beneath it ({@code acl:default}). Built from the
     * {@link Acl} vocabulary rather than spelled as text.
     *
     * <p>Public and static because it is the pod's ACL <em>shape</em>, and a caller that
     * writes it through some other channel than a {@link ResourceStore} — the command-line
     * tool putting it over HTTP with the caller's own credential — must write the same graph
     * this class would, not a second rendering of it.
     */
    public static Model ownerAclGraph(PodSpec spec) {
        Objects.requireNonNull(spec, "spec");
        Model model = ModelFactory.createDefaultModel();
        // Declared so a human reading the file back sees acl:Read, not the full IRI.
        model.setNsPrefix(Acl.PREFIX, Acl.NS);
        Resource root = model.createResource(spec.root().uri().toString());
        Resource owner = model.createResource(spec.ownerWebId().toString());
        Resource authorization = model.createResource(spec.acl().uri() + OWNER_AUTHORIZATION_FRAGMENT);
        authorization.addProperty(RDF.type, Acl.AUTHORIZATION);
        authorization.addProperty(Acl.AGENT, owner);
        authorization.addProperty(Acl.ACCESS_TO, root);
        authorization.addProperty(Acl.DEFAULT, root);
        for (AccessMode mode : OWNER_MODES) {
            authorization.addProperty(Acl.MODE, model.createResource(mode.iri()));
        }
        return model;
    }
}
