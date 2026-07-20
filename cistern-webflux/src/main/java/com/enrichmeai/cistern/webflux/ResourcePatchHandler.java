package com.enrichmeai.cistern.webflux;

import com.enrichmeai.cistern.core.CisternException;
import com.enrichmeai.cistern.core.Representation;
import com.enrichmeai.cistern.core.ResourceIdentifier;
import com.enrichmeai.cistern.core.ldp.LdpService;
import com.enrichmeai.cistern.core.ldp.ResourceView;
import com.enrichmeai.cistern.core.ldp.WriteOutcome;
import com.enrichmeai.cistern.core.rdf.N3Patch;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

/**
 * Serves {@code PATCH} with an N3 Patch body (T2.7), wiring T1.5's engine to the wire.
 *
 * <p>Thin by rule, like every handler here: it maps a request path to an identifier, checks that
 * the body claims to be a patch document, and hands the bytes to {@link LdpService#patch}. Every
 * decision the Solid Protocol makes about a patch — parsing it, the 400/422 split, applying it,
 * the three 409s, whether the target may be patched at all, whether the write created or
 * modified — is made in core and arrives here already made.
 *
 * <h2>Status codes</h2>
 * Solid Protocol §5.3.1 ends its processing algorithm with "the server responds with the
 * appropriate status code" and leaves the choice to HTTP, so both come from the general
 * specifications:
 *
 * <ul>
 *   <li><b>Created → 201.</b> RFC 9110 §15.3.2: 201 "indicates that the request has been
 *       fulfilled and has resulted in one or more new resources being created". That a patch can
 *       create is the spec's own step 1 — "Start from the RDF dataset in the target document, or
 *       an empty RDF dataset if the target resource does not exist yet" — reinforced by §5.5
 *       ("When a server creates an RDF source on HTTP {@code PUT}, {@code POST}, or {@code PATCH}
 *       requests ...") and by §5.3's URI-allocation note ("Clients can use {@code PUT} and
 *       {@code PATCH} requests to assign a URI to a resource").</li>
 *   <li><b>Modified → 204, not 200 and not 205.</b> RFC 5789 §2.1 makes the choice for us on the
 *       same grounds T2.2 chose it for {@code PUT}: "The 204 response code is used because the
 *       response does not carry a message body (which a response with the 200 code would have)."
 *       The only content a 200 could honestly carry is the patched representation, which doubles
 *       the bytes of every patch to restate what the client can compute. 205 (§15.3.6) is not a
 *       candidate at all: it instructs a user agent to reset its document view, which is a
 *       statement about a form, not about a graph.</li>
 * </ul>
 *
 * <h2>No {@code Location}, on either outcome</h2>
 * RFC 9110 §15.3.2 identifies the created resource "by either a {@code Location} header field in
 * the response or, if no {@code Location} field is received, by the target URI". A patch is
 * applied to the resource the client named, so the target URI is already the answer and
 * {@code Location} would only restate the request line — the same reasoning T2.2 applies to
 * {@code PUT}, and the opposite of {@code POST} (T2.3), where the server mints the name and must
 * therefore disclose it.
 *
 * <h2>No validators</h2>
 * RFC 5789 §2.1's example carries an {@code ETag}, but it is an example rather than a
 * requirement, and Cistern cannot honestly produce one here. {@link EntityTag} is the validator
 * of <em>one representation</em> — it hashes the media type along with the state — and a patched
 * resource is an RDF source, which Solid Protocol §5.5 requires to be servable as both
 * {@code text/turtle} and {@code application/ld+json}, with a different validator for each. A
 * {@code PATCH} request negotiates nothing: its {@code Content-Type} describes the patch
 * document, not the result (RFC 5789 §2: "entity-headers contained in the request apply only to
 * the contained patch document and MUST NOT be applied to the resource being modified"). There is
 * therefore no representation for a validator to describe, so none is sent — consistent with
 * T2.2, which sends validators only for the non-RDF writes where RFC 9110 §9.3.4 permits them.
 *
 * <h2>Errors</h2>
 * Nothing here maps a status code (ground rule 4) and there is no {@code onErrorResume}. A body
 * that is not {@code text/n3} leaves as {@link CisternException.UnsupportedMediaType} (→ 415 with
 * {@code Accept-Patch}, RFC 5789 §2.2); a malformed patch document as
 * {@link CisternException.BadInput} (→ 400); one that breaches §5.3.1's constraints as
 * {@link CisternException.UnprocessableEntity} (→ 422); an inapplicable patch or an attempt on
 * containment as {@link CisternException.Conflict} (→ 409); a non-RDF target as
 * {@link CisternException.MethodNotAllowed} (→ 405 with a truthful {@code Allow}). The single
 * error mapper (T2.6) renders each. Everything sits inside {@code Mono.defer} so a synchronous
 * throw becomes an error signal rather than escaping the reactive chain.
 */
@Component
public class ResourcePatchHandler {

    /** A {@code PATCH} with no body is an empty patch document, which core parses and refuses. */
    private static final byte[] NO_CONTENT = new byte[0];

    /** The one patch document format Cistern accepts (Solid Protocol §5.3.1). */
    private static final MediaType N3 = MediaType.valueOf(N3Patch.MEDIA_TYPE);

    private final LdpService ldp;
    private final RequestPaths requestPaths;
    private final ConditionalRequests conditionalRequests;

    public ResourcePatchHandler(LdpService ldp, RequestPaths requestPaths,
                                ConditionalRequests conditionalRequests) {
        this.ldp = ldp;
        this.requestPaths = requestPaths;
        this.conditionalRequests = conditionalRequests;
    }

    /**
     * Applies an N3 Patch to the resource named by the request-target, creating it if it is not
     * there.
     *
     * <p>The steps are in RFC 9110 §13.2.1's order, exactly as {@code PUT} arranges them.
     * The media-type check is a "normal request check" and so precedes the precondition gate —
     * a {@code PATCH} whose body is not a patch document is a 415 whatever its {@code If-Match}
     * said — and the gate precedes {@code bodyToMono}, so nothing subscribes to
     * {@link LdpService#patch} until the preconditions have held and a failed
     * {@code If-Match} never reaches the store.
     *
     * <p>Preconditions apply to {@code PATCH} for the plain reason that it modifies a
     * representation: §13 governs "conditional requests" by method safety, and §13.2.1's
     * evaluation point is "just before it would process the request content (if any) or perform
     * the action associated with the request method". RFC 5789 §2's own example carries an
     * {@code If-Match}, and calls the combination the way "to avoid the 'lost update' problem"
     * — which is more acute for a patch than for a {@code PUT}, since a patch is defined
     * relative to a state the client believes the resource is in.
     *
     * <p>{@link ConditionalRequests.AbsentTarget#IS_CREATED} because a patch of an absent target
     * is a 201 rather than a 404: §13.2.1 has preconditions ignored only where the unconditional
     * response would be neither 2xx nor 412, which is not the case here. So
     * {@code If-None-Match: *} is a create-only guard on a {@code PATCH} exactly as it is on a
     * {@code PUT}.
     */
    public Mono<ServerResponse> patch(ServerRequest request) {
        return Mono.defer(() -> {
            ResourceIdentifier target = requestPaths.identifierFor(request);
            requirePatchDocumentMediaType(request);
            return conditionalRequests
                    .requireMayProceed(request, target, ConditionalRequests.AbsentTarget.IS_CREATED)
                    .then(request.bodyToMono(byte[].class)
                            .defaultIfEmpty(NO_CONTENT)
                            .map(body -> new Representation(N3Patch.MEDIA_TYPE, body))
                            .flatMap(document -> ldp.patch(target, document))
                            .flatMap(ResourcePatchHandler::respond));
        });
    }

    /**
     * Solid Protocol §5.3.1 identifies an N3 Patch by {@code text/n3}, and RFC 5789 §2.2 makes an
     * unsupported patch document a 415 rather than a 400 — the document may be perfectly
     * well-formed in its own format; it is simply not one this resource takes.
     *
     * <p>Checked here rather than in core, and before the target is ever looked up, because it is
     * a fact about the request alone: RFC 9110 §13.2.1 puts "failures that can be detected before
     * significant processing occurs" ahead of everything else. Core's {@link N3Patch#parse} also
     * refuses a foreign content type, but as {@link CisternException.BadInput} (400) — right for
     * a caller that has already committed to N3, wrong for an HTTP client choosing a format, and
     * the reason this check exists rather than letting the parser answer.
     *
     * <p>A parameterized {@code text/n3;charset=utf-8} is accepted:
     * {@link MediaType#isCompatibleWith} compares type and subtype only, and core tolerates the
     * parameter for the same reason.
     *
     * @throws CisternException.UnsupportedMediaType if the declared type is absent, unparseable,
     *                                               or not {@code text/n3} (→ 415)
     */
    private static void requirePatchDocumentMediaType(ServerRequest request) {
        MediaType declared;
        try {
            declared = request.headers().contentType().orElse(null);
        } catch (InvalidMediaTypeException e) {
            throw new CisternException.UnsupportedMediaType(
                    WebfluxMessage.PATCH_MEDIA_TYPE_UNSUPPORTED.format(
                            N3Patch.MEDIA_TYPE, e.getMessage()));
        }
        if (declared == null || !declared.isCompatibleWith(N3)) {
            throw new CisternException.UnsupportedMediaType(
                    WebfluxMessage.PATCH_MEDIA_TYPE_UNSUPPORTED.format(
                            N3Patch.MEDIA_TYPE, declared == null ? "nothing" : declared));
        }
    }

    // ---------------------------------------------------------------- response shaping

    /**
     * Interface metadata comes from the {@link ResourceKind} row of the view core returned, so a
     * patched resource advertises the same {@code Allow} and {@code Accept-*} fields a
     * {@code GET} of it would — Solid Protocol §5.2 requires the {@code Accept-Patch},
     * {@code Accept-Post} and {@code Accept-Put} fields to "correspond to acceptable HTTP methods
     * listed in {@code Allow} header field value", and one table for every response is what makes
     * that true without checking.
     */
    private static Mono<ServerResponse> respond(WriteOutcome outcome) {
        ResourceView view = outcome.view();
        ResourceKind kind = ResourceKind.of(view);
        return ServerResponse.status(statusOf(outcome))
                .headers(headers -> {
                    InterfaceMetadata.write(headers, kind);
                })
                .build();
    }

    /**
     * A total switch over core's effect — adding a {@code WriteEffect} constant fails compilation
     * here rather than silently defaulting. See the class javadoc for why each is what it is.
     */
    private static HttpStatus statusOf(WriteOutcome outcome) {
        return switch (outcome.effect()) {
            case CREATED -> HttpStatus.CREATED;
            case REPLACED -> HttpStatus.NO_CONTENT;
        };
    }

    /** Mirrors the read and write handlers' handling of the kind table's absent entries. */
}
