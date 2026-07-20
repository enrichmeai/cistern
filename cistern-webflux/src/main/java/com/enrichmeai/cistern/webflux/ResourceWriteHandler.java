package com.enrichmeai.cistern.webflux;

import com.enrichmeai.cistern.core.CisternException;
import com.enrichmeai.cistern.core.ResourceIdentifier;
import com.enrichmeai.cistern.core.ldp.LdpService;
import com.enrichmeai.cistern.core.ldp.ResourceView;
import com.enrichmeai.cistern.core.ldp.WriteOutcome;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.util.Optional;

/**
 * Serves {@code PUT} (T2.2).
 *
 * <p>Thin by rule, exactly like {@link ResourceReadHandler}: it maps a request path to an
 * identifier, canonicalizes the declared media type, hands the body to
 * {@link LdpService#put}, and shapes whatever comes back into a response. Every LDP decision
 * — intermediate containers, slash semantics, server-managed containment triples, whether a
 * body may be stored against this target — is made in core and arrives here already made.
 * There is no LDP reasoning in this class to drift from core's.
 *
 * <h2>Status codes</h2>
 * RFC 9110 §9.3.4: "If the target resource does not have a current representation and the PUT
 * successfully creates one, then the origin server MUST inform the user agent by sending a 201
 * (Created) response. If the target resource does have a current representation and that
 * representation is successfully modified in accordance with the state of the enclosed
 * representation, then the origin server MUST send either a 200 (OK) or a 204 (No Content)
 * response to indicate successful completion of the request." Which of the two occurred is
 * core's {@code WriteEffect}, so the mapping here is a total switch over that enum rather than
 * a re-derivation.
 *
 * <ul>
 *   <li><b>Created → 201, with no {@code Location}.</b> RFC 9110 §15.3.2 defines the created
 *       resource as identified "by either a Location header field in the response or, if no
 *       Location field is received, by the target URI". A {@code PUT} always creates at the
 *       target URI — that is what distinguishes it from {@code POST}, where the server mints
 *       the name and must therefore disclose it (T2.3). Sending {@code Location} here would
 *       only restate the request line.</li>
 *   <li><b>Replaced → 204, not 200.</b> Both are permitted, and the choice is decided by what
 *       there would be to send: 200 is defined (§15.3.1) as carrying content describing the
 *       result, and the only honest content for a replace is the representation the client
 *       just supplied. Echoing it back doubles the bytes on every write to no purpose, and
 *       inventing something else would make a replace answer with a body a subsequent
 *       {@code GET} would not produce. 204 (§15.3.5) says precisely what happened: the request
 *       succeeded and there is no additional content to send.</li>
 * </ul>
 *
 * <h2>Validators: non-RDF writes only</h2>
 * RFC 9110 §9.3.4 is a prohibition, not an encouragement: "An origin server MUST NOT send a
 * validator field (Section 8.8), such as an ETag or Last-Modified field, in a successful
 * response to PUT unless the request's representation data was saved without any transformation
 * applied to the content (i.e., the resource's new representation data is identical to the
 * content received in the PUT request) and the validator field value reflects the new
 * representation."
 *
 * <p><b>This looks like an omission bug and is not</b> (architect ruling, PR #66). The decisive
 * fact is in T2.1's read path: {@link LdpService#read} hands back a parsed Jena model and
 * {@link ResourceReadHandler} re-serializes it through {@code RdfIo} on the way out. So for
 * <em>every</em> RDF source — documents as much as containers — the representation Cistern
 * would serve is a re-serialization and is never byte-identical to what the client sent. A
 * container adds a second, larger failure of the same condition: what it serves includes
 * {@code ldp:contains} triples derived from its live children (Solid Protocol §4.2) and the
 * server-asserted LDP types, which were in no part of the request content.
 *
 * <p>The rationale sentence the RFC attaches settles the intent — the validator exists so a
 * user agent knows "the representation it sent (and retains in memory) is the result of the
 * PUT, and thus it doesn't need to be retrieved again". For an RDF source that is simply false.
 * So:
 *
 * <ul>
 *   <li><b>RDF source (document or container) → no {@code ETag}, no {@code Last-Modified}.</b>
 *       Both are validator fields and §9.3.4 names them together. The client pays one
 *       round-trip and gets its validators from a {@code GET} or {@code HEAD}, which is what
 *       T2.5's conditional requests expect.</li>
 *   <li><b>Non-RDF source → both sent.</b> The bytes are stored and served verbatim, so the
 *       condition genuinely holds, and the tag is computed by T2.1's {@link EntityTag} for the
 *       media type as stored — literally the validator a {@code GET} returns for it.</li>
 * </ul>
 *
 * <p>{@link ResourceView.NonRdf} is the test rather than a media-type check, because it is the
 * read path's own classification of "served back verbatim" — the exact property §9.3.4 asks
 * about, so the two cannot drift apart.
 *
 * <h2>Interface metadata</h2>
 * Solid Protocol §5.2 requires the {@code Allow} header "in successful responses", and LDP 1.0
 * §4.2.1.4 requires the {@code Link rel="type"} advertisement "in all responses" — a 201 and a
 * 204 are both. The values come from the same {@link ResourceKind} table the read path uses, so
 * a {@code PUT} and a {@code GET} on one resource cannot advertise different interfaces.
 *
 * <h2>Errors</h2>
 * Nothing here maps a status code (ground rule 4). An unusable request-target, an absent,
 * malformed or non-concrete {@code Content-Type} and a malformed RDF body leave as
 * {@link CisternException.BadInput}; kind flips, blocked intermediate containers, a non-RDF
 * body for a container and server-managed containment leave as {@link CisternException.Conflict}
 * — all rendered by the single error mapper (T2.6). There is no {@code onErrorResume} in this
 * class. Everything runs inside {@code Mono.defer} so a synchronous throw becomes an error
 * signal rather than escaping the reactive chain.
 */
@Component
public class ResourceWriteHandler {

    /** A {@code PUT} with no content is a write of the empty representation, not a failure. */
    private static final byte[] NO_CONTENT = new byte[0];

    private final LdpService ldp;
    private final RequestPaths requestPaths;
    private final ConditionalRequests conditionalRequests;

    public ResourceWriteHandler(LdpService ldp, RequestPaths requestPaths,
                                ConditionalRequests conditionalRequests) {
        this.ldp = ldp;
        this.requestPaths = requestPaths;
        this.conditionalRequests = conditionalRequests;
    }

    /**
     * Creates or replaces the resource named by the request-target.
     *
     * <p>The three steps are in RFC 9110 §13.2.1's order and that order is the ticket's whole
     * point. Preconditions are evaluated "after it has successfully performed its normal request
     * checks" — hence after {@link RequestMediaType#required}, so a {@code PUT} with no
     * {@code Content-Type} is the 400 Solid Protocol §2.1 mandates whatever its
     * {@code If-Match} said — and "just before it would process the request content (if any)",
     * hence before {@code bodyToMono}. Nothing subscribes to {@link LdpService#put} until the
     * gate has completed, so a failed precondition never reaches the store.
     *
     * <p>{@link ConditionalRequests.AbsentTarget#IS_CREATED} because a {@code PUT} to a target
     * that is not there is a 201: §13.2.1 has preconditions ignored only where the
     * unconditional response would be neither 2xx nor 412, which is not the case here. That is
     * what makes {@code If-None-Match: *} a create-only guard rather than a no-op.
     */
    public Mono<ServerResponse> put(ServerRequest request) {
        return Mono.defer(() -> {
            ResourceIdentifier target = requestPaths.identifierFor(request);
            RequestMediaType mediaType = RequestMediaType.required(request);
            return conditionalRequests
                    .requireMayProceed(request, target, ConditionalRequests.AbsentTarget.IS_CREATED)
                    .then(request.bodyToMono(byte[].class)
                            .defaultIfEmpty(NO_CONTENT)
                            .map(mediaType::representationOf)
                            .flatMap(representation -> ldp.put(target, representation))
                            .flatMap(outcome -> respond(outcome, mediaType)));
        });
    }

    // ---------------------------------------------------------------- response shaping

    private Mono<ServerResponse> respond(WriteOutcome outcome, RequestMediaType stored) {
        ResourceView view = outcome.view();
        ResourceKind kind = ResourceKind.of(view);
        return ServerResponse.status(statusOf(outcome))
                .headers(headers -> {
                    for (String linkValue : kind.linkTypeValues()) {
                        headers.add(HttpHeaders.LINK, linkValue);
                    }
                    headers.set(HttpHeaders.ALLOW, kind.allow());
                    setIfPresent(headers, HttpConstants.ACCEPT_PUT, kind.acceptPut());
                    setIfPresent(headers, HttpConstants.ACCEPT_POST, kind.acceptPost());
                    setIfPresent(headers, HttpHeaders.ACCEPT_PATCH, kind.acceptPatch());
                    writeValidators(headers, view, stored);
                })
                .build();
    }

    /**
     * The RFC 9110 §9.3.4 mapping, as a total switch over core's effect — adding a
     * {@code WriteEffect} constant fails compilation here rather than silently defaulting.
     */
    private static HttpStatus statusOf(WriteOutcome outcome) {
        return switch (outcome.effect()) {
            case CREATED -> HttpStatus.CREATED;
            case REPLACED -> HttpStatus.NO_CONTENT;
        };
    }

    /**
     * Emits {@code ETag} and {@code Last-Modified} only where RFC 9110 §9.3.4 permits a
     * validator at all — see the class javadoc for why every RDF source is excluded, and why
     * their absence here is deliberate rather than an oversight.
     *
     * <p>Both fields or neither: they are both validator fields under §8.8, the prohibition
     * names them together, and splitting them would leave a client able to make a conditional
     * request on the weaker one against a representation it does not actually hold.
     */
    private static void writeValidators(HttpHeaders headers, ResourceView view,
                                        RequestMediaType stored) {
        if (!(view instanceof ResourceView.NonRdf)) {
            return;
        }
        headers.set(HttpHeaders.ETAG,
                EntityTag.forRepresentation(view, stored.mediaType()).headerValue());
        // IMF-fixdate, one-second resolution (RFC 9110 §5.6.7, §8.8.2); the store already
        // truncates to seconds, so nothing is rounded here.
        headers.setLastModified(view.lastModified());
    }

    /**
     * Mirrors {@link ResourceReadHandler}'s handling of the kind table's absent entries. The
     * duplication is deliberate for this ticket: sharing it means reworking T2.1's response
     * writer, which is out of scope here, and the values themselves come from the one
     * {@link ResourceKind} table either way, so the two cannot advertise different interfaces.
     * Extracting a single interface-metadata writer is the natural follow-up once {@code POST}
     * and {@code OPTIONS} land — T2.4's {@code DELETE} does not emit these headers, so there
     * are still only the two call sites today.
     */
    private static void setIfPresent(HttpHeaders headers, String name, String value) {
        if (value != null) {
            headers.set(name, value);
        }
    }
}
