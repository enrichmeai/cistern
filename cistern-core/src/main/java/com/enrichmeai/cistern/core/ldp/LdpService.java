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

import java.net.URI;
import java.security.SecureRandom;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

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

    /** Solid Protocol §3.1: a URI path ending with this separator denotes a container. */
    private static final String CONTAINER_SUFFIX = "/";

    /**
     * How many names {@link #createIn} may draw before giving up. The first draw is the client's
     * slug when it sent a usable one; every later draw is a fresh generated name, so reaching the
     * end of this budget means {@link #generatedName()} collided
     * {@value #NAME_ATTEMPTS} times in a row — a ~2^-114 event per draw. The bound exists so a
     * corrupt store that reports every name as taken fails fast instead of spinning forever.
     */
    private static final int NAME_ATTEMPTS = 4;

    /**
     * Alphabet for generated names. Lower-case alphanumerics only, which is a deliberate
     * narrowing of the URL-safe Base64 set on two grounds: no name can begin with {@code -}
     * (awkward for every command-line tool a pod operator will point at the storage directory),
     * and case-insensitive file systems — macOS's default among them — cannot fold two distinct
     * generated names onto one backing file.
     */
    private static final String NAME_ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyz";

    /**
     * Length of a generated name: 22 characters over a 36-symbol alphabet is
     * log2(36) × 22 ≈ 114 bits of entropy — UUID-scale (122 bits) collision resistance, in 22
     * URL-safe characters instead of 36. "Short, URL-safe, collision-resistant", in that order
     * of what had to give.
     */
    private static final int NAME_LENGTH = 22;

    /** Names must be unguessable as well as unique: a client must not be able to predict them. */
    private static final SecureRandom NAME_RANDOM = new SecureRandom();

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
        return find(target).switchIfEmpty(Mono.error(() -> new CisternException.NotFound(
                CoreMessage.RESOURCE_NOT_FOUND.format(target.uri()))));
    }

    /**
     * The same resolution as {@link #read}, but with absence left as an <b>empty Mono</b>
     * instead of a {@link CisternException.NotFound} signal — the low-level form, matching
     * {@link #getContainer} and {@link ResourceStore#get}.
     *
     * <p>Both exist because the two callers need genuinely different things from absence, and
     * neither can reconstruct the other's:
     *
     * <ul>
     *   <li>A <b>front-end serving a response</b> wants {@link #read}. An empty Mono returned
     *       from a WebFlux handler means "no response was produced", which the framework — not
     *       Cistern's one error mapper — would turn into a bare 404 with no problem document.</li>
     *   <li>A <b>caller deciding what to do next</b> wants this. Conditional requests (T2.5) are
     *       the motivating case: RFC 9110 §13.1.1 and §13.1.2 both branch on whether "the origin
     *       server has a current representation for the target resource", so absence is an input
     *       to the decision rather than the end of it — an {@code If-None-Match: *} on a
     *       {@code PUT} <em>succeeds</em> precisely because the resource is not there. Forcing
     *       that caller to go through {@link #read} would make it catch an exception to learn a
     *       fact, which in the HTTP layer additionally trips the ground-rule-4 guard against
     *       error operators outside the error mapper.</li>
     * </ul>
     *
     * @param target the resource to resolve; container or document
     * @return the view, or an empty Mono if no such resource exists. Signals
     *         {@link IllegalStateException} if a stored RDF representation is unparseable
     *         (server-side corruption → 500). Never throws synchronously.
     */
    public Mono<ResourceView> find(ResourceIdentifier target) {
        return Mono.defer(() -> store.get(target).flatMap(stored -> viewOf(target, stored)));
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
     * The create-in-container path every front-end uses (HTTP {@code POST} in T2.3, MCP later):
     * mints a name for a new child of {@code container}, stores the body against it, and reports
     * the created resource — whose identifier the caller is obliged to disclose (LDP 1.0
     * §5.2.3.1: "If the resource was created successfully, LDP servers MUST respond with status
     * code 201 (Created) and the Location header set to the new resource's URL").
     *
     * <h2>Why this returns a {@link ResourceView} and not a {@link WriteOutcome}</h2>
     * A {@code POST} has exactly one successful effect. It never replaces: the name is chosen
     * <em>because</em> nothing holds it, so a {@link WriteEffect} field would be a constant
     * dressed up as a decision, and a front-end could switch on it as though 204 were reachable.
     * The view carries what the caller actually needs — the minted identifier, the validators,
     * and the post-write state.
     *
     * <h2>The two refusals, and their order</h2>
     * Both are decided before a name is drawn, and existence is checked first because the spec
     * makes it the more general rule:
     * <ol>
     *   <li><b>Nothing there → {@link CisternException.NotFound}.</b> Solid Protocol §5.3: "When
     *       a {@code POST} method request targets a resource without an existing representation,
     *       the server MUST respond with the {@code 404} status code." This is unconditional —
     *       it does not ask whether the target's path ends in a slash — so a {@code POST} to a
     *       container that does not exist is a 404 and not an implicit create. That is the
     *       deliberate asymmetry with {@link #put}, which does create intermediate containers:
     *       {@code PUT} names the resource it wants, so the server can build the path to it,
     *       while {@code POST} asks an existing container to mint a child.</li>
     *   <li><b>There but not a container → {@link CisternException.MethodNotAllowed}.</b> Solid
     *       Protocol §5.3 requires creation by {@code POST} only "to a URI path ending with
     *       {@code /}", and §5.2 requires the server to advertise the methods a resource
     *       supports — {@code Allow} for a document does not list {@code POST}. RFC 9110
     *       §15.5.6 is the matching status: the method "is known by the origin server but not
     *       supported by the target resource". 405 rather than 404, because the resource
     *       demonstrably exists and answering 404 for something a {@code GET} will serve would
     *       be a lie.</li>
     * </ol>
     *
     * <h2>Choosing the name</h2>
     * The client may hint with a {@link Slug} (RFC 5023 §9.7, adopted by LDP §5.2.3.10 as "a
     * client hint"); {@link Slug} has already reduced it to a safe single path segment, or to
     * nothing at all. With no usable hint the server mints its own, which LDP §5.2.3.8 expects:
     * "LDP servers SHOULD assign the URI for the resource to be created using server application
     * specific rules in the absence of a client hint."
     *
     * <p><b>A taken name is never overwritten.</b> {@code POST} creates; it has no replace
     * semantics to fall back on, and silently overwriting a resource the client did not name
     * would be data loss triggered by a header. A candidate counts as taken if <em>either</em>
     * spelling of the name exists — {@code name} or {@code name/} — because Solid Protocol §3.1
     * makes those one name in two kinds ("if a server has associated a resource with one, the
     * other URI MUST NOT correspond to another resource"), so writing the free spelling would be
     * refused by the store as a kind flip anyway. Checking both here turns that 409 into what
     * the client asked for: a different name.
     *
     * <p><b>A collision falls back to a generated name, never to a suffix.</b> Numbering a
     * collision ({@code note}, {@code note-1}, {@code note-2}) discloses that {@code note}
     * exists — a probe a client could run against a container it may write but not read, which
     * becomes a genuine information leak once WAC lands in Phase 4. A freshly drawn name
     * discloses nothing, and {@link #generatedName()} is collision-resistant enough that the
     * retry below is a safety net rather than an expected path.
     *
     * <h2>Storing the body reuses {@link #put}</h2>
     * Once the identifier is settled, a create by {@code POST} and a create by {@code PUT} are
     * the same operation, so this delegates rather than restating it: RDF bodies are parsed
     * before they are stored, a non-RDF body offered to a container URI is a
     * {@link CisternException.Conflict}, and §5.3's server-managed containment guard runs. The
     * base URI for parsing is the <em>created</em> resource, which is what LDP §4.2.1.5 requires
     * ("LDP servers MUST assign the default base-URI ... to the URI of the created resource when
     * the request results in the creation of a new resource") and what makes §5.2.3.7's rule
     * fall out for free: a triple whose subject is the null relative URI ends up describing the
     * new resource.
     *
     * <p>The containment triple LDP §5.2.3.2 requires the container to gain needs no work here
     * either — containment is derived from {@link ResourceStore#children} on every read (see the
     * class javadoc), so the new child is listed the moment the parent is read.
     *
     * <p><b>The same known race as {@link #put}.</b> "Is this name free?" and "store it" are two
     * calls, so two writers racing on one generated name could both see it free. The window is
     * vanishingly small (the names are drawn from ~114 bits of entropy) and the fix is the same
     * atomic-create the storage SPI owes {@link #put}; it is tracked there, not worked around
     * here.
     *
     * @param container      the container to create in; existence and kind are checked, not assumed
     * @param slug           the client's sanitized name hint, or empty for none
     * @param model          what to create — a document or a child container (LDP §5.2.3.4)
     * @param representation the body to store, with an already-canonical media type
     * @return the created resource. Signals {@link CisternException.NotFound} if the target has
     *         no representation, {@link CisternException.MethodNotAllowed} if it is not a
     *         container, {@link CisternException.BadInput} for an unparseable RDF body,
     *         {@link CisternException.Conflict} for a non-RDF body offered for a child container
     *         or a body asserting the new resource's containment. Never throws synchronously.
     */
    public Mono<ResourceView> createIn(ResourceIdentifier container, Optional<Slug> slug,
                                       InteractionModel model, Representation representation) {
        return Mono.defer(() -> {
            Objects.requireNonNull(container, "container");
            Objects.requireNonNull(slug, "slug");
            Objects.requireNonNull(model, "model");
            Objects.requireNonNull(representation, "representation");
            return store.exists(container)
                    .flatMap(exists -> {
                        if (!exists) {
                            return Mono.error(new CisternException.NotFound(
                                    CoreMessage.POST_TARGET_NOT_FOUND.format(container.uri())));
                        }
                        if (!container.isContainer()) {
                            return Mono.error(new CisternException.MethodNotAllowed(
                                    CoreMessage.POST_TARGET_NOT_A_CONTAINER.format(container.uri())));
                        }
                        return freeChild(container, slug.map(Slug::value), model, NAME_ATTEMPTS)
                                .flatMap(child -> put(child, representation))
                                .map(WriteOutcome::view);
                    });
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
     * The identifier of a child of {@code container} that nothing currently occupies.
     *
     * <p>Recursive rather than iterative because each attempt is an asynchronous question to the
     * store: the recursion happens on the reactive chain, one {@code flatMap} deep per attempt,
     * and {@link #NAME_ATTEMPTS} bounds it. Only the first attempt may use the client's hint —
     * every retry drops it and draws a generated name, which is why {@code preferred} is passed
     * as {@link Optional#empty()} on the way down.
     *
     * @param preferred    the name to try first, or empty to generate one
     * @param attemptsLeft remaining draws, including this one
     */
    private Mono<ResourceIdentifier> freeChild(ResourceIdentifier container,
                                               Optional<String> preferred,
                                               InteractionModel model,
                                               int attemptsLeft) {
        return Mono.defer(() -> {
            String name = preferred.orElseGet(LdpService::generatedName);
            ResourceIdentifier asDocument = childOf(container, name, false);
            ResourceIdentifier asContainer = childOf(container, name, true);
            // Both spellings, because Solid Protocol §3.1 makes them one name: a document
            // /c/note and a container /c/note/ cannot coexist, so either one occupies the name.
            return Mono.zip(store.exists(asDocument), store.exists(asContainer),
                            (documentExists, containerExists) -> documentExists || containerExists)
                    .flatMap(taken -> {
                        if (!taken) {
                            return Mono.just(model.container() ? asContainer : asDocument);
                        }
                        if (attemptsLeft <= 1) {
                            return Mono.error(new CisternException.Conflict(
                                    CoreMessage.CHILD_NAME_UNAVAILABLE.format(
                                            container.uri(), NAME_ATTEMPTS)));
                        }
                        return freeChild(container, Optional.empty(), model, attemptsLeft - 1);
                    });
        });
    }

    /**
     * The identifier one name below {@code container}, in the requested kind.
     *
     * <p>Built by RFC 3986 §5 reference resolution rather than string concatenation: the
     * container's URI ends with {@code /}, so resolving a single-segment relative reference
     * against it appends exactly that segment and drops any query the base carried, which a
     * child does not inherit. The name is unreserved characters only — enforced by
     * {@link Slug}'s invariant, and by construction for {@link #generatedName()} — so it needs
     * no percent-encoding, contains no {@code :} that could be read as a scheme, and cannot
     * introduce a path segment of its own.
     */
    private static ResourceIdentifier childOf(ResourceIdentifier container, String name,
                                              boolean asContainer) {
        URI child = container.uri().resolve(asContainer ? name + CONTAINER_SUFFIX : name);
        return new ResourceIdentifier(child);
    }

    /** A fresh name; see {@link #NAME_ALPHABET} and {@link #NAME_LENGTH} for the arithmetic. */
    private static String generatedName() {
        StringBuilder name = new StringBuilder(NAME_LENGTH);
        for (int i = 0; i < NAME_LENGTH; i++) {
            name.append(NAME_ALPHABET.charAt(NAME_RANDOM.nextInt(NAME_ALPHABET.length())));
        }
        return name.toString();
    }

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
