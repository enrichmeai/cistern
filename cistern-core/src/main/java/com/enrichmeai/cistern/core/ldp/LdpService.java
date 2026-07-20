package com.enrichmeai.cistern.core.ldp;

import com.enrichmeai.cistern.core.CisternException;
import com.enrichmeai.cistern.core.CoreMessage;
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
 * (and the MCP front-end) call. It owns the containment layer (container reads and the
 * write guard for server-managed containment triples, T1.4), the read path {@link #read}
 * (T2.1), the {@code PUT} write path {@link #put} (T2.2) and {@link #delete} (T2.4). Patch
 * orchestration joins them later in Phase 2.
 *
 * <p>Front-ends stay thin because the LDP decisions live here, not in a handler: what a
 * resource contains, whether a body may be stored against it, and whether a write created or
 * replaced are all answered before a status code is ever chosen.
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
 * a container to update its containment triples; if the server receives such a request,
 * it MUST respond with a 409 status code". {@link #rejectServerManagedTriples} is the
 * write-path guard for that rule and signals {@link CisternException.Conflict} (→ 409
 * via T2.6, per the spec text — architect ruling on PR #52: the spec wins over the
 * original ticket wording); {@link #getContainer} additionally strips any stored
 * {@code ldp:contains}/{@code rdf:type ldp:*} statements about the container as defense
 * in depth, so nothing that slipped into storage can ever surface.
 *
 * <p>Asymmetry (architect ruling, 2026-07-19): only containment is a hard error
 * (Conflict, 409). Client-supplied {@code rdf:type ldp:*} triples are tolerated on
 * write — clients commonly echo the type triples a GET handed them — and are dropped
 * and re-derived on read instead.
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
     *         {@link IllegalStateException} if the stored representation is not
     *         parseable RDF — stored container bodies only exist through validated
     *         writes, so unparseable stored state is server-side corruption (a 500),
     *         never the reading client's fault (never a 400). Never throws
     *         synchronously.
     */
    public Mono<Model> getContainer(ResourceIdentifier container) {
        return Mono.defer(() -> {
            if (!container.isContainer()) {
                return Mono.error(new IllegalArgumentException(
                        CoreMessage.NOT_A_CONTAINER_IDENTIFIER.format(container.uri())));
            }
            return store.get(container)
                    .flatMap(stored -> store.children(container)
                            .collectList()
                            .map(children -> containerRepresentation(container, stored, children)));
        });
    }

    /**
     * The read path every front-end uses (HTTP {@code GET}/{@code HEAD} in T2.1, MCP
     * later): resolves one identifier to the content that must be served for it, together
     * with the validators the caller needs for {@code ETag}/{@code Last-Modified}.
     *
     * <p>This is where "is this a graph or a byte stream?" is decided, because that is
     * LDP/Solid semantics rather than transport detail (see {@link ResourceView}):
     * <ul>
     *   <li><b>Container</b> → {@link ResourceView.Rdf} carrying the same merged graph
     *       {@link #getContainer} produces (derived {@code ldp:contains} + server-asserted
     *       types, Solid Protocol §4.2). Containers are always RDF sources.</li>
     *   <li><b>Document with an RDF media type</b> → {@link ResourceView.Rdf} carrying the
     *       parsed graph, so the caller can satisfy {@code GET} in either
     *       {@code text/turtle} or {@code application/ld+json} as Solid Protocol §5.5
     *       requires. Parsing on read (rather than handing back stored bytes) is what makes
     *       both serializations available from one code path.</li>
     *   <li><b>Any other document</b> → {@link ResourceView.NonRdf} carrying the stored
     *       representation untouched, to be served verbatim.</li>
     * </ul>
     *
     * <p><b>Absence is an error signal here, unlike {@link #getContainer}.</b> The store
     * reports absence as an empty Mono and {@link #getContainer} preserves that (it is the
     * low-level graph accessor); {@code read} instead signals
     * {@link CisternException.NotFound}. Rationale: {@code read} is the front-end-facing
     * operation, and an empty Mono returned from a WebFlux handler means "no response was
     * produced", which the framework — not Cistern's one error mapper (T2.6) — would turn
     * into a bare 404. Making absence an explicit domain signal keeps every front-end
     * (HTTP, MCP) on the same mapping and gives T2.6 a body to render.
     *
     * @param target the resource to read; container or document
     * @return the view; signals {@link CisternException.NotFound} if no such resource
     *         exists, and {@link IllegalStateException} if a stored RDF representation is
     *         unparseable (server-side corruption → 500, never the reading client's
     *         fault). Never throws synchronously.
     */
    public Mono<ResourceView> read(ResourceIdentifier target) {
        return Mono.defer(() -> store.get(target)
                .switchIfEmpty(Mono.error(() -> new CisternException.NotFound(
                        CoreMessage.RESOURCE_NOT_FOUND.format(target.uri()))))
                .flatMap(stored -> viewOf(target, stored)));
    }

    /**
     * Removes one resource (T2.4). The front-end-facing delete operation, so — like
     * {@link #read} — every outcome is a domain signal the single error mapper can render.
     *
     * <h2>What this adds over {@link ResourceStore#delete}</h2>
     * Exactly one rule, and it is the one the storage SPI cannot express: <b>the storage's
     * root container is undeletable</b>. Solid Protocol §5.4 — "When a {@code DELETE} request
     * targets storage's root container or its associated ACL resource, the server MUST respond
     * with the {@code 405} status code" — so the root is refused with
     * {@link CisternException.MethodNotAllowed} <em>before</em> the store is touched, and a
     * backend can never be asked to remove its own root. "Storage's root container" is
     * {@link ResourceIdentifier#isStorageRoot()}: the resource with no parent container.
     *
     * <p>The rest of §5.4 is already the storage contract, and is deliberately not restated
     * here — one rule, one place:
     * <ul>
     *   <li>"When a {@code DELETE} request targets a container, the server MUST delete the
     *       container if it contains no resources. If the container contains resources, the
     *       server MUST respond with the {@code 409} status code" → the store signals
     *       {@link CisternException.Conflict} for a non-empty container.</li>
     *   <li>A resource that is not there → {@link CisternException.NotFound} (404), because
     *       {@code Mono<Void>} cannot distinguish absence from success by emptiness.</li>
     *   <li>"When a contained resource is deleted, the server MUST also remove the
     *       corresponding containment triple" → free by construction: containment is derived
     *       from {@link ResourceStore#children(ResourceIdentifier)} at read time and never
     *       stored (see the class javadoc), so the parent's next read cannot list a resource
     *       the store no longer has. {@code LdpServiceDeleteTest} pins that rather than
     *       assuming it.</li>
     * </ul>
     *
     * <p>Auxiliary resources ("the server MUST also delete the associated auxiliary
     * resources") are out of scope until ACLs exist (Phase 4) — there is no auxiliary
     * resource to cascade to yet.
     *
     * @param target the resource to remove; container or document
     * @return an empty Mono on success. Signals {@link CisternException.MethodNotAllowed} for
     *         the storage root, {@link CisternException.NotFound} if no such resource exists,
     *         and {@link CisternException.Conflict} for a non-empty container. Never throws
     *         synchronously.
     */
    public Mono<Void> delete(ResourceIdentifier target) {
        return Mono.defer(() -> {
            if (target.isStorageRoot()) {
                return Mono.error(new CisternException.MethodNotAllowed(
                        CoreMessage.STORAGE_ROOT_NOT_DELETABLE.format(target.uri())));
            }
            return store.delete(target);
        });
    }

    /**
     * The write path every front-end uses (HTTP {@code PUT} in T2.2, MCP later): creates the
     * target resource or replaces its representation, and reports which of the two happened
     * together with the resource's post-write state.
     *
     * <h2>What this method decides, and what the store decides</h2>
     * The split follows the storage-SPI seam. <b>Core</b> owns everything that requires
     * knowing what RDF <em>means</em>; the <b>store</b> owns everything that is a fact about
     * the resource hierarchy, and already enforces it (see {@link ResourceStore}):
     *
     * <ul>
     *   <li><b>Store —</b> intermediate containers. Solid Protocol §5.3: "Servers MUST create
     *       intermediate containers and include corresponding containment triples in container
     *       representations derived from the URI path component of {@code PUT} and
     *       {@code PATCH} requests." The containment triples half of that sentence needs no
     *       write-time work here: {@link #getContainer} derives containment from
     *       {@link ResourceStore#children} on every read, so a newly created intermediate
     *       lists its members the moment it is read.</li>
     *   <li><b>Store —</b> slash semantics. Solid Protocol §3.1: "Paths ending with a slash
     *       denote a container resource", and if two URIs differ only in the trailing slash
     *       and the server has associated a resource with one, "the other URI MUST NOT
     *       correspond to another resource". A write that would flip a name's kind in either
     *       direction, or that would need an intermediate container where a document already
     *       sits, signals {@link CisternException.Conflict} from the store. This method must
     *       not swallow it — it is propagated untouched, as 409 is exactly the right answer.</li>
     *   <li><b>Core —</b> a container's body must be an RDF source (Solid Protocol §4.2:
     *       Solid containers are LDP Basic Containers). A {@code PUT} to a container URI
     *       offering a non-RDF media type is refused with {@link CisternException.Conflict}:
     *       RFC 9110 §9.3.4 requires an origin server to "verify that the PUT representation
     *       is consistent with its configured constraints for the target resource" and
     *       suggests 409 or 415 when it is not, and the constraint being broken here is the
     *       same container/document distinction §3.1 makes, so it is reported the same way a
     *       kind flip is.</li>
     *   <li><b>Core —</b> RDF bodies are parsed before they are stored, so malformed input is
     *       the client's {@link CisternException.BadInput} at write time rather than a
     *       server-side 500 at read time. This is what lets {@link #read} treat unparseable
     *       stored bytes as corruption: nothing reaches storage unvalidated.</li>
     *   <li><b>Core —</b> {@link #rejectServerManagedTriples} runs on every RDF body, which is
     *       the §5.3 guard: "Servers MUST NOT allow HTTP {@code PUT} or {@code PATCH} on a
     *       container to update its containment triples; if the server receives such a
     *       request, it MUST respond with a {@code 409} status code."</li>
     * </ul>
     *
     * <h2>The client's bytes are stored verbatim</h2>
     * Parsing is <em>validation</em>, not a transformation: the representation handed to the
     * store is the one that arrived, byte for byte. Only its media type is expected to be
     * canonical (bare, lower-cased) so that {@link Representation#isRdf()} classifies it —
     * the front-end normalizes that before calling. Re-serializing the parsed graph instead
     * was rejected for three reasons: it would discard the client's prefixes, comments and
     * layout for no protocol gain; Jena's JSON-LD writer is not byte-stable across calls, so
     * an unchanged document would churn its stored etag on every write; and it would make the
     * stored data differ from the received content, which is precisely the condition under
     * which RFC 9110 §9.3.4 forbids returning a validator with the response.
     *
     * <h2>Created or replaced — a known, tracked race</h2>
     * Determined by asking {@link ResourceStore#exists} immediately before the write, because
     * {@link ResourceStore#put} creates and replaces through one signature and cannot report
     * which it did.
     *
     * <p><b>The two calls are not atomic.</b> Two writers racing on the same absent target can
     * both observe "absent" and both report {@code CREATED}, and a writer racing a deleter can
     * report {@code REPLACED} for what became a create. The consequence is confined to the
     * status code a client sees (201 versus 204): the write itself is serialized by the store,
     * both outcomes are successes, and the resulting stored state is identical either way.
     *
     * <p>Known and deliberately not fixed here (architect ruling, PR #66): the real fix is for
     * the storage SPI to report the effect atomically as part of {@code put}, which changes
     * {@link ResourceStore}, the shared contract-test kit and every backend, and so is tracked
     * as its own ticket. A client that needs create-only or replace-only semantics does not
     * depend on this in the meantime — it states the precondition with {@code If-None-Match: *}
     * or {@code If-Match}, which T2.5 enforces against the store.
     *
     * <h2>The storage root</h2>
     * {@code PUT} to the root container is allowed and behaves like any other container
     * write: it replaces the root's client-authored triples, while its containment stays
     * derived and therefore untouched. Solid Protocol §5.4 singles the root out only for
     * {@code DELETE}; nothing in §5.3 or §3.1 exempts it from being written, and refusing it
     * would leave a pod unable to describe its own root.
     *
     * @param target         the resource to create or replace; container or document
     * @param representation the body to store, with an already-canonical media type
     * @return the effect plus the post-write {@link ResourceView}. Signals
     *         {@link CisternException.BadInput} for an unparseable RDF body,
     *         {@link CisternException.Conflict} for a kind flip, a blocked intermediate
     *         container, a non-RDF body for a container, or a body asserting the target's
     *         containment. Never throws synchronously.
     */
    public Mono<WriteOutcome> put(ResourceIdentifier target, Representation representation) {
        return Mono.defer(() -> {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(representation, "representation");
            validateBody(target, representation);
            return store.exists(target)
                    .flatMap(existed -> store.put(target, representation)
                            .flatMap(stored -> viewOf(target, stored))
                            .map(view -> new WriteOutcome(WriteEffect.of(existed), view)));
        });
    }

    /**
     * Write-path guard for Solid Protocol §5.3: rejects a client body that tries to
     * update the target's containment triples ("Servers MUST NOT allow HTTP PUT or
     * PATCH on a container to update its containment triples; if the server receives
     * such a request, it MUST respond with a 409 status code"). A body containing any
     * {@code ldp:contains} triple whose SUBJECT is the target resource is rejected with
     * {@link CisternException.Conflict} — mapped to 409 by the global error handler
     * (T2.6), exactly as the spec text mandates; anything else passes.
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
     * @throws CisternException.Conflict if the body asserts containment for the target
     *                                   (409 per Solid Protocol §5.3)
     */
    public void rejectServerManagedTriples(Model body, ResourceIdentifier target) {
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(target, "target");
        Resource subject = body.createResource(target.uri().toString());
        if (body.listStatements(subject, Ldp.CONTAINS, (RDFNode) null).hasNext()) {
            throw new CisternException.Conflict(
                    CoreMessage.CONTAINMENT_SERVER_MANAGED.format(target.uri()));
        }
    }

    // ---------------------------------------------------------------- internals

    /**
     * Everything {@link #put} must establish about a body before it may reach storage. Pure
     * CPU-bound work that throws, called from inside {@code Mono.defer} so the throw becomes
     * an error signal (the class never throws synchronously to a caller).
     *
     * <p>A non-RDF body is opaque: there is nothing to validate in it, and the only question
     * is whether the target is allowed to hold it. An RDF body is parsed against the target's
     * own URI as base (RFC 3986 §5.1.3 — the retrieval URI is the base, so {@code <>} in the
     * document means the resource itself) and then checked for server-managed containment.
     */
    private void validateBody(ResourceIdentifier target, Representation representation) {
        if (!representation.isRdf()) {
            if (target.isContainer()) {
                throw new CisternException.Conflict(CoreMessage.CONTAINER_REQUIRES_RDF_BODY
                        .format(target.uri(), representation.contentType(),
                                Representation.TURTLE, Representation.JSON_LD));
            }
            return;
        }
        rejectServerManagedTriples(RdfIo.parse(representation, target), target);
    }

    /**
     * Classifies one stored resource into a {@link ResourceView}. Containers take the same
     * containment merge as {@link #getContainer} (one implementation, so a container read
     * cannot differ between the two entry points); documents split on
     * {@link Representation#isRdf()}.
     */
    private Mono<ResourceView> viewOf(ResourceIdentifier target, StoredResource stored) {
        if (target.isContainer()) {
            return store.children(target)
                    .collectList()
                    .map(children -> new ResourceView.Rdf(target, stored.etag(), stored.lastModified(),
                            true, containerRepresentation(target, stored, children)));
        }
        if (stored.representation().isRdf()) {
            // parseStored: CPU-bound, and a parse failure is server-side corruption (500),
            // because stored bytes only get there through a validated write.
            return Mono.fromCallable(() -> new ResourceView.Rdf(target, stored.etag(),
                    stored.lastModified(), false, parseStored(stored, target)));
        }
        return Mono.just(new ResourceView.NonRdf(
                target, stored.etag(), stored.lastModified(), stored.representation()));
    }

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

    /**
     * Empty stored body → empty graph; otherwise parse with the resource's own URI as base
     * (RFC 3986 §5.1.3). A parse failure here is NOT the client's {@code BadInput}: stored
     * bodies only exist through validated writes, so unparseable stored state is a server
     * fault, rethrown as {@link IllegalStateException} (→ 500, not 400).
     */
    private static Model parseStored(StoredResource stored, ResourceIdentifier resource) {
        Representation representation = stored.representation();
        if (representation.data().length == 0) {
            return ModelFactory.createDefaultModel();
        }
        try {
            return RdfIo.parse(representation, resource);
        } catch (CisternException.BadInput e) {
            throw new IllegalStateException(
                    CoreMessage.STORED_REPRESENTATION_CORRUPT.format(resource.uri(), e.getMessage()),
                    e);
        }
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
