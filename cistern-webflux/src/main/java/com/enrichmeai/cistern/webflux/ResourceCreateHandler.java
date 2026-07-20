package com.enrichmeai.cistern.webflux;

import com.enrichmeai.cistern.core.CisternException;
import com.enrichmeai.cistern.core.ResourceIdentifier;
import com.enrichmeai.cistern.core.ldp.InteractionModel;
import com.enrichmeai.cistern.core.ldp.LdpService;
import com.enrichmeai.cistern.core.ldp.ResourceView;
import com.enrichmeai.cistern.core.ldp.Slug;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Optional;

/**
 * Serves {@code POST} — creating a resource inside a container (T2.3).
 *
 * <p>Thin by rule, like every handler here: it turns three request headers into the three
 * arguments {@link LdpService#createIn} takes, and shapes the created resource into a response.
 * Which name gets minted, whether it collides, whether the body may be stored, and whether the
 * target may be posted to at all are core's decisions and arrive here already made.
 *
 * <h2>The three headers this handler reads</h2>
 * <ul>
 *   <li><b>{@code Slug}</b> (RFC 5023 §9.7, adopted by LDP §5.2.3.10) — a name hint. Everything
 *       about what is and is not a usable hint lives in {@link Slug}, which is also where the
 *       sanitization rules are written down, because they are security rules and not
 *       transport detail.</li>
 *   <li><b>{@code Link ... rel="type"}</b> (LDP §5.2.3.4, the "requested interaction model") —
 *       whether to create a document or a child container. Parsed as the structured field it is
 *       by {@link LinkHeader}; the IRIs it yields are mapped to an {@link InteractionModel} by
 *       core, so the vocabulary decision stays with the vocabulary.</li>
 *   <li><b>{@code Content-Type}</b> — what the body is stored as, canonicalized by
 *       {@link RequestMediaType} exactly as {@code PUT} does. Solid Protocol §2.1 makes its
 *       absence a 400.</li>
 * </ul>
 *
 * <h2>Status and {@code Location}</h2>
 * 201, always, with {@code Location}. LDP §5.2.3.1 states both as one requirement — "If the
 * resource was created successfully, LDP servers MUST respond with status code 201 (Created) and
 * the Location header set to the new resource's URL" — and RFC 9110 §9.3.3 agrees for POST in
 * general ("the origin server SHOULD send a 201 (Created) response containing a Location header
 * field that provides an identifier for the primary resource created"). It is not optional
 * decoration here: unlike {@code PUT}, the client did not choose the name, so without
 * {@code Location} it has no way to address what it just made. The value is the absolute URI
 * core minted; §10.2.2 permits a relative reference, but an absolute one cannot be misresolved.
 *
 * <p>No response content. LDP §5.2.3.1: "Clients shall not expect any representation in the
 * response entity body on a 201 (Created) response."
 *
 * <h2>Validators: what §9.3.3 does and does not say</h2>
 * T2.2 omits {@code ETag} and {@code Last-Modified} from every RDF {@code PUT} because RFC 9110
 * <b>§9.3.4 forbids</b> them unless the stored representation is byte-identical to the content
 * received. <b>That prohibition does not extend to {@code POST}</b>: §9.3.3 contains no such
 * sentence, and §15.3.2 says so explicitly while defining 201 — "Any validator fields (Section
 * 8.8) sent in the response convey the current validators for a new representation created by
 * the request. <em>Note that the PUT method (Section 9.3.4) has additional requirements that
 * might preclude sending such validators.</em>" Singling {@code PUT} out is only meaningful if
 * {@code POST} is not bound by it.
 *
 * <p>So validators are permitted here, and §15.3.2 fixes their meaning: they must be the current
 * validators of the created resource's representation. Cistern sends them exactly where it can
 * satisfy that, which is the same line T2.2 draws for a different reason:
 * <ul>
 *   <li><b>Non-RDF resource → both sent.</b> It has one representation, served verbatim, so
 *       {@link EntityTag} computes the very tag a {@code GET} on {@code Location} returns.</li>
 *   <li><b>RDF resource (document or created container) → neither sent.</b> An RDF source has
 *       two representations, Turtle and JSON-LD (Solid Protocol §5.5), with two different
 *       validators — {@link EntityTag} hashes the media type precisely so they cannot collide. A
 *       201 carries no content and therefore no {@code Content-Type} to say which one a tag
 *       described, so any tag sent would be a validator for a representation the client cannot
 *       identify, and a conditional request built on it would be a guess. §15.3.2 permits
 *       validators; it does not require them, and an ambiguous one is worse than none.</li>
 * </ul>
 *
 * <h2>Interface metadata describes the target resource, not the created one</h2>
 * {@code Allow}, {@code Accept-Post}, {@code Accept-Put}, {@code Accept-Patch} and
 * {@code Link rel="type"} here describe the <em>container</em> that was posted to. That is what
 * the definitions say: RFC 9110 §10.2.1 defines {@code Allow} as "the set of methods supported
 * by the target resource", Solid Protocol §5.2 as "the HTTP methods supported by the target
 * resource", and LDP §4.2.1.4 requires the type link "in all responses to requests made to an
 * LDPR's HTTP Request-URI". For a {@code POST} the target resource and the request URI are both
 * the container — the created resource is a different resource, and the response points at it
 * with {@code Location} rather than describing it. Its own metadata is one {@code HEAD} away,
 * and comes from the same {@link ResourceKind} table.
 *
 * <h2>Errors</h2>
 * Nothing here maps a status code (ground rule 4) and there is no {@code onErrorResume}. An
 * unusable request-target, a missing or malformed {@code Content-Type}, a malformed {@code Slug}
 * and a malformed RDF body leave as {@link CisternException.BadInput}; a target with no
 * representation as {@link CisternException.NotFound}; a target that is not a container as
 * {@link CisternException.MethodNotAllowed}; a non-RDF body for a child container as
 * {@link CisternException.Conflict}. The single error mapper (T2.6) renders all of them.
 */
@Component
public class ResourceCreateHandler {

    /** A {@code POST} with no content creates the empty representation, not a failure. */
    private static final byte[] NO_CONTENT = new byte[0];

    private final LdpService ldp;
    private final RequestPaths requestPaths;

    public ResourceCreateHandler(LdpService ldp, RequestPaths requestPaths) {
        this.ldp = ldp;
        this.requestPaths = requestPaths;
    }

    /** Creates a resource inside the container named by the request-target. */
    public Mono<ServerResponse> post(ServerRequest request) {
        return Mono.defer(() -> {
            ResourceIdentifier container = requestPaths.identifierFor(request);
            RequestMediaType mediaType = RequestMediaType.required(request);
            Optional<Slug> slug = Slug.from(request.headers().firstHeader(HttpConstants.SLUG));
            InteractionModel model = requestedModel(request);
            return request.bodyToMono(byte[].class)
                    .defaultIfEmpty(NO_CONTENT)
                    .map(mediaType::representationOf)
                    .flatMap(representation -> ldp.createIn(container, slug, model, representation))
                    .flatMap(created -> respond(container, created, mediaType));
        });
    }

    // ---------------------------------------------------------------- request reading

    /**
     * The interaction model the client asked for with {@code Link ... rel="type"} (LDP
     * §5.2.3.4), or {@link InteractionModel#RESOURCE} when it asked for nothing this server
     * recognises. All of the header lines are read, not just the first: RFC 8288 §3 allows a
     * client to spread its links across several.
     */
    private static InteractionModel requestedModel(ServerRequest request) {
        List<String> links = request.headers().header(HttpHeaders.LINK);
        return InteractionModel.forTypeIris(
                LinkHeader.targetsWithRelation(links, LinkRelation.TYPE));
    }

    // ---------------------------------------------------------------- response shaping

    private Mono<ServerResponse> respond(ResourceIdentifier container, ResourceView created,
                                         RequestMediaType stored) {
        ResourceKind targetKind = ResourceKind.ofContainer(container);
        return ServerResponse.created(created.identifier().uri())
                .headers(headers -> {
                    for (String linkValue : targetKind.linkTypeValues()) {
                        headers.add(HttpHeaders.LINK, linkValue);
                    }
                    headers.set(HttpHeaders.ALLOW, targetKind.allow());
                    setIfPresent(headers, HttpConstants.ACCEPT_PUT, targetKind.acceptPut());
                    setIfPresent(headers, HttpConstants.ACCEPT_POST, targetKind.acceptPost());
                    setIfPresent(headers, HttpHeaders.ACCEPT_PATCH, targetKind.acceptPatch());
                    writeValidators(headers, created, stored);
                })
                .build();
    }

    /**
     * Emits {@code ETag} and {@code Last-Modified} only where they can carry the meaning RFC
     * 9110 §15.3.2 gives them on a 201 — see the class javadoc for why an RDF source cannot.
     *
     * <p>Both or neither, as in T2.2: they are both validator fields under §8.8, and sending
     * only one would leave a client able to make a conditional request against a representation
     * it cannot identify.
     */
    private static void writeValidators(HttpHeaders headers, ResourceView created,
                                        RequestMediaType stored) {
        if (!(created instanceof ResourceView.NonRdf)) {
            return;
        }
        headers.set(HttpHeaders.ETAG,
                EntityTag.forRepresentation(created, stored.mediaType()).headerValue());
        // IMF-fixdate, one-second resolution (RFC 9110 §5.6.7, §8.8.2); the store already
        // truncates to seconds, so nothing is rounded here.
        headers.setLastModified(created.lastModified());
    }

    /** The kind table leaves an entry absent where the corresponding method is not supported. */
    private static void setIfPresent(HttpHeaders headers, String name, String value) {
        if (value != null) {
            headers.set(name, value);
        }
    }
}
