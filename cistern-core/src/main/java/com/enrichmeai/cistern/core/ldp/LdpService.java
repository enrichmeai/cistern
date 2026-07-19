package com.enrichmeai.cistern.core.ldp;

import com.enrichmeai.cistern.core.CisternException;
import com.enrichmeai.cistern.core.Representation;
import com.enrichmeai.cistern.core.ResourceIdentifier;
import com.enrichmeai.cistern.core.ResourceStore;
import com.enrichmeai.cistern.core.StoredResource;
import com.enrichmeai.cistern.core.rdf.RdfIo;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.Statement;
import org.apache.jena.vocabulary.RDF;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Objects;

/**
 * LDP/Solid semantics over a {@link ResourceStore} — the single service the HTTP layer
 * (and the MCP front-end) call. This ticket's scope (T1.4) is the containment layer:
 * container reads and the write-validation guard for server-managed containment triples.
 * Write orchestration (put/delete/patch) arrives with Phase 2.
 *
 * <h2>Containment is derived, never stored</h2>
 * Solid derives containment from the URI path hierarchy (Solid Protocol §4.2, Resource
 * Containment: a 1-1 correspondence between containment triples and the path hierarchy;
 * §3.1: paths ending with a slash denote a container). Accordingly, Cistern's
 * architecture rule is that {@code ldp:contains} triples are derived at read time from
 * {@link ResourceStore#children(ResourceIdentifier)} and are never persisted — a
 * container GET shows exactly the live children, no matter what the stored bytes say.
 *
 * <h2>Server-managed triples</h2>
 * Solid Protocol §5.3 (Writing Resources): "Servers MUST NOT allow HTTP PUT or PATCH on
 * a container to update its containment triples". {@link #rejectServerManagedTriples}
 * is the write-path guard for that rule; {@link #getContainer} additionally strips any
 * stored {@code ldp:contains}/{@code rdf:type ldp:*} statements about the container as
 * defense in depth, so nothing that slipped into storage can ever surface.
 *
 * <p>Asymmetry (architect ruling, 2026-07-19): only containment is a hard error.
 * Client-supplied {@code rdf:type ldp:*} triples are tolerated on write — clients
 * commonly echo the type triples a GET handed them — and are dropped and re-derived on
 * read instead.
 */
public final class LdpService {

    private final ResourceStore store;

    public LdpService(ResourceStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    /**
     * The container's live representation: the stored client-authored triples merged
     * with one derived {@code ldp:contains} triple per current child and the
     * server-asserted types {@code ldp:BasicContainer}, {@code ldp:Container} and
     * {@code ldp:Resource} (Solid Protocol §4.2 — Solid containers are LDP Basic
     * Containers; the LDP 1.0 class hierarchy makes every basic container a container
     * and a resource).
     *
     * <p>Any stored {@code ldp:contains} triple with the container as subject, and any
     * stored {@code rdf:type ldp:*} triple for the container, is discarded before the
     * derived triples are added: containment is derived-only, and the server re-asserts
     * the types itself.
     *
     * @param container the container to read; must be a container identifier
     * @return the merged graph; an empty Mono if the container does not exist. Signals
     *         {@link IllegalArgumentException} for a non-container identifier
     *         (consistent with {@link ResourceStore#children(ResourceIdentifier)}) and
     *         {@link CisternException.BadInput} if the stored representation is not
     *         parseable RDF. Never throws synchronously.
     */
    public Mono<Model> getContainer(ResourceIdentifier container) {
        return Mono.defer(() -> {
            if (!container.isContainer()) {
                return Mono.error(new IllegalArgumentException(
                        "getContainer() requires a container identifier (trailing slash): "
                                + container.uri()));
            }
            return store.get(container)
                    .flatMap(stored -> store.children(container)
                            .collectList()
                            .map(children -> containerRepresentation(container, stored, children)));
        });
    }

    /**
     * Write-path guard for Solid Protocol §5.3: rejects a client body that tries to
     * update the target's containment triples. A body containing any
     * {@code ldp:contains} triple whose SUBJECT is the target resource is rejected with
     * {@link CisternException.BadInput}; anything else passes.
     *
     * <p>Deliberately NOT rejected (architect ruling — encode the asymmetry):
     * <ul>
     *   <li>{@code rdf:type ldp:*} triples about the target — clients commonly echo the
     *       server-asserted types back on PUT; they are dropped and re-derived on read
     *       by {@link #getContainer} instead of failing the write.</li>
     *   <li>{@code ldp:contains} triples with a DIFFERENT subject — those do not state
     *       this server's containment for the target (a client graph may legitimately
     *       describe some other container, e.g. a cached remote listing), so they are
     *       not server-managed for this target and are stored as ordinary data.</li>
     * </ul>
     *
     * <p>Synchronous by design (like {@link RdfIo} — pure CPU-bound graph inspection):
     * Phase 2 composes it inside the reactive write pipeline (e.g. via
     * {@code Mono.fromRunnable} or a {@code map} step), where the throw becomes an
     * error signal.
     *
     * @param body   the client-supplied graph, already parsed
     * @param target the resource the PUT/PATCH addresses
     * @throws CisternException.BadInput if the body asserts containment for the target
     */
    public void rejectServerManagedTriples(Model body, ResourceIdentifier target) {
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(target, "target");
        Resource subject = body.createResource(target.uri().toString());
        if (body.listStatements(subject, Ldp.CONTAINS, (RDFNode) null).hasNext()) {
            throw new CisternException.BadInput(
                    "Containment triples are server-managed (Solid Protocol §5.3): the request body"
                            + " must not assert ldp:contains for <" + target.uri() + ">");
        }
    }

    // ---------------------------------------------------------------- internals

    /** Pure CPU-bound merge, run inside the reactive chain via {@code map}. */
    private static Model containerRepresentation(
            ResourceIdentifier container, StoredResource stored, List<ResourceIdentifier> children) {
        Model model = parseStored(stored, container);
        Resource subject = model.createResource(container.uri().toString());
        stripServerManaged(model, subject);
        for (ResourceIdentifier child : children) {
            model.add(subject, Ldp.CONTAINS, model.createResource(child.uri().toString()));
        }
        model.add(subject, RDF.type, Ldp.BASIC_CONTAINER);
        model.add(subject, RDF.type, Ldp.CONTAINER);
        model.add(subject, RDF.type, Ldp.RESOURCE);
        return model;
    }

    /** Empty stored body → empty graph; otherwise parse with the container URI as base. */
    private static Model parseStored(StoredResource stored, ResourceIdentifier container) {
        Representation representation = stored.representation();
        if (representation.data().length == 0) {
            return ModelFactory.createDefaultModel();
        }
        return RdfIo.parse(representation, container);
    }

    /**
     * Defense in depth for the derived-only rule: drop any stored {@code ldp:contains}
     * statement with the container as subject, and any stored {@code rdf:type ldp:*}
     * statement for the container (the server re-asserts the types it stands behind).
     */
    private static void stripServerManaged(Model model, Resource subject) {
        model.removeAll(subject, Ldp.CONTAINS, null);
        List<Statement> ldpTypes = model.listStatements(subject, RDF.type, (RDFNode) null)
                .filterKeep(statement -> statement.getObject().isURIResource()
                        && statement.getObject().asResource().getURI().startsWith(Ldp.NS))
                .toList();
        model.remove(ldpTypes);
    }
}
