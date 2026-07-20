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
 * <h2>Validators</h2>
 * RFC 9110 §9.3.4 is a prohibition, not an encouragement: "An origin server MUST NOT send a
 * validator field (Section 8.8), such as an ETag or Last-Modified field, in a successful
 * response to PUT unless the request's representation data was saved without any transformation
 * applied to the content ... and the validator field value reflects the new representation."
 *
 * <p>For a <b>document</b> both conditions hold — core stores the client's bytes verbatim, and
 * the tag is computed by T2.1's {@link EntityTag} for the media type as stored, so it is
 * literally the validator a {@code GET} for that representation would return. For a
 * <b>container</b> the first condition fails and cannot be made to hold: what a container
 * serves includes {@code ldp:contains} triples derived from its live children (Solid Protocol
 * §4.2) and the server-asserted LDP types, none of which are in the content received. The
 * representation the client holds is therefore not the resource's new representation, which is
 * the exact situation the MUST NOT exists to prevent — so a container write returns no
 * validator, and a client that wants one issues a {@code HEAD}.
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

    public ResourceWriteHandler(LdpService ldp, RequestPaths requestPaths) {
        this.ldp = ldp;
        this.requestPaths = requestPaths;
    }

    /** Creates or replaces the resource named by the request-target. */
    public Mono<ServerResponse> put(ServerRequest request) {
        return Mono.defer(() -> {
            ResourceIdentifier target = requestPaths.identifierFor(request);
            RequestMediaType mediaType = RequestMediaType.required(request);
            return request.bodyToMono(byte[].class)
                    .defaultIfEmpty(NO_CONTENT)
                    .map(mediaType::representationOf)
                    .flatMap(representation -> ldp.put(target, representation))
                    .flatMap(outcome -> respond(outcome, mediaType));
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
                    validatorFor(view, stored)
                            .ifPresent(tag -> headers.set(HttpHeaders.ETAG, tag.headerValue()));
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
     * The validator to return with a successful write, if RFC 9110 §9.3.4 permits one at all.
     * Withheld for a container, whose served representation carries derived containment that
     * was never in the request content; sent for a document, whose bytes were stored untouched.
     */
    private static Optional<EntityTag> validatorFor(ResourceView view, RequestMediaType stored) {
        if (view.container()) {
            return Optional.empty();
        }
        return Optional.of(EntityTag.forRepresentation(view, stored.mediaType()));
    }

    /**
     * Mirrors {@link ResourceReadHandler}'s handling of the kind table's absent entries. The
     * duplication is deliberate for this ticket: sharing it means reworking T2.1's response
     * writer, which is out of scope here. Extracting one interface-metadata writer used by both
     * handlers is the natural follow-up once {@code POST}/{@code DELETE}/{@code OPTIONS} land
     * and there are five call sites instead of two.
     */
    private static void setIfPresent(HttpHeaders headers, String name, String value) {
        if (value != null) {
            headers.set(name, value);
        }
    }
}
