package com.enrichmeai.cistern.webflux;

import com.enrichmeai.cistern.core.CisternException;
import com.enrichmeai.cistern.core.ResourceIdentifier;
import com.enrichmeai.cistern.core.ldp.LdpService;
import com.enrichmeai.cistern.core.ldp.ResourceView;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Serves {@code GET} and {@code HEAD} (T2.1).
 *
 * <p>Thin by rule: it maps a request path to an identifier, asks {@link LdpService#read} what
 * the resource is, picks a serialization, and writes headers. Every decision about
 * <em>what</em> a resource contains — containment triples, graph vs bytes, absence — belongs
 * to core and arrives here already made; every header value comes from the {@link ResourceKind}
 * table rather than from a literal at this call site.
 *
 * <h2>HEAD cannot drift from GET</h2>
 * Both methods enter through {@link #read(ServerRequest)}; there is no second code path and
 * no {@code if (isHead)} branch. RFC 9110 §9.3.2 defines {@code HEAD} as "identical to GET
 * except that the server MUST NOT send content" and requires that the server "SHOULD send the
 * same header fields ... that it would have sent if the request method had been GET", so the
 * response is built in full, {@code Content-Length} included, and WebFlux's
 * {@code HttpHeadResponseDecorator} discards the body on the way out.
 *
 * <h2>Conditional requests (T2.5)</h2>
 * A representation is <em>selected</em> first and only then serialized, because a conditional
 * read that turns into a 304 must not pay for a body it will not send — and because the
 * validator a precondition is compared against has to be the one this response would have
 * carried, which is knowable from the selection alone. {@link ConditionalRequests} makes the
 * decision (RFC 9110 §13.2.2) and this class writes it:
 *
 * <ul>
 *   <li><b>304</b> is written here, not signalled. It is a successful outcome of a conditional
 *       {@code GET} (RFC 9110 §15.4.5), not an error, so routing it through the error mapper
 *       would be wrong twice over — it would attach an RFC 9457 problem document to a response
 *       that "cannot contain content".</li>
 *   <li><b>412</b> leaves as {@link CisternException.PreconditionFailed} like every other
 *       domain refusal. A client may make a {@code GET} conditional on {@code If-Match}
 *       (§13.1.1) and gets the same answer a write would.</li>
 * </ul>
 *
 * <h2>Errors</h2>
 * Nothing here maps a status code (ground rule 4). Absence arrives as
 * {@link CisternException.NotFound} from core, negotiation failure leaves as
 * {@link CisternException.NotAcceptable}, an unusable request-target as
 * {@link CisternException.BadInput}; the single error mapper (T2.6) renders all of them.
 * There is no {@code onErrorResume} in this class. Everything runs inside {@code Mono.defer}
 * so a synchronous throw becomes an error signal rather than escaping the reactive chain.
 */
@Component
public class ResourceReadHandler {

    private final LdpService ldp;
    private final RequestPaths requestPaths;
    private final ContentNegotiator negotiator;
    private final ConditionalRequests conditionalRequests;
    private final StorageDescription storageDescription;

    public ResourceReadHandler(LdpService ldp, RequestPaths requestPaths,
                               ContentNegotiator negotiator,
                               ConditionalRequests conditionalRequests,
                               StorageDescription storageDescription) {
        this.ldp = ldp;
        this.requestPaths = requestPaths;
        this.negotiator = negotiator;
        this.conditionalRequests = conditionalRequests;
        this.storageDescription = storageDescription;
    }

    /** The one handler behind both {@code GET} and {@code HEAD}. */
    public Mono<ServerResponse> read(ServerRequest request) {
        return Mono.defer(() -> {
            ResourceIdentifier target = requestPaths.identifierFor(request);
            List<MediaType> accept = acceptOf(request);
            return ldp.read(target).flatMap(view -> respond(request, view, accept));
        });
    }

    // ---------------------------------------------------------------- representation

    /**
     * Selects the representation, applies RFC 9110 §13.2.2, and only then produces a body.
     * Inside {@code Mono.defer} so that a negotiation refusal — thrown by
     * {@link ContentNegotiator} — becomes an error signal.
     */
    private Mono<ServerResponse> respond(ServerRequest request, ResourceView view,
                                         List<MediaType> accept) {
        return Mono.defer(() -> {
            Selected selected = select(view, accept);
            PreconditionResult precondition =
                    conditionalRequests.evaluateRead(request, view, selected.contentType());
            return switch (precondition.outcome()) {
                case PROCEED -> Mono.fromCallable(selected::body)
                        .flatMap(body -> write(selected, body));
                case NOT_MODIFIED -> notModified(selected);
                case PRECONDITION_FAILED -> Mono.error(new CisternException.PreconditionFailed(
                        conditionalRequests.detailFor(precondition,
                                ConditionalRequest.of(request), view.identifier())));
            };
        });
    }

    /**
     * Which representation this request gets — the whole of proactive negotiation, and no
     * serialization. Splitting it out is what lets a 304 be decided without building a body.
     */
    private Selected select(ResourceView view, List<MediaType> accept) {
        return switch (view) {
            // An RDF source is always serialized from the graph, never echoed from storage.
            // That is what makes Solid Protocol §5.5 hold unconditionally — either media type
            // is available for any RDF source — and it is the only option for a container,
            // whose containment triples exist in no stored byte (§4.2).
            case ResourceView.Rdf rdf -> Selected.negotiated(rdf, negotiator.negotiateRdf(accept));
            // A non-RDF source has exactly one representation and is copied out untouched.
            case ResourceView.NonRdf binary -> Selected.unnegotiated(binary,
                    negotiator.requireAcceptable(accept, ContentNegotiator.storedMediaTypeOf(binary)));
        };
    }

    /**
     * The representation this response will describe, before any bytes exist for it. Carries
     * enough to write every header — including the validator — so that the 200 and the 304
     * paths cannot describe the same representation differently.
     *
     * @param serialization the RDF form chosen, or null for a non-RDF source, which has none
     * @param negotiated    whether {@code Accept} selected among several possible
     *                      representations — true for an RDF source (Turtle or JSON-LD), false
     *                      for a non-RDF source, which has only the one. Drives {@code Vary}.
     */
    private record Selected(ResourceView view, MediaType contentType,
                            RdfSerialization serialization, boolean negotiated) {

        /** An RDF source: {@code Accept} chose this serialization out of several. */
        static Selected negotiated(ResourceView.Rdf view, RdfSerialization serialization) {
            return new Selected(view, serialization.mediaType(), serialization, true);
        }

        /** A non-RDF source: one representation, so {@code Accept} selected nothing. */
        static Selected unnegotiated(ResourceView.NonRdf view, MediaType stored) {
            return new Selected(view, stored, null, false);
        }

        ResourceKind kind() {
            return ResourceKind.of(view);
        }

        /** The validator for THIS representation, not for the stored bytes. */
        EntityTag entityTag() {
            return EntityTag.forRepresentation(view, contentType);
        }

        /** The response body. CPU-bound for an RDF source, so callers keep it off the fast path. */
        byte[] body() {
            return switch (view) {
                case ResourceView.Rdf rdf -> serialization.serialize(rdf.graph()).data();
                case ResourceView.NonRdf binary -> binary.representation().data();
            };
        }
    }

    /**
     * Writes the 200. Every field is either a property of the selected representation or a
     * lookup in {@link ResourceKind} — with one exception, the storage-description link, which is
     * a property of the storage rather than of this resource and therefore comes from
     * {@link StorageDescription}.
     */
    private Mono<ServerResponse> write(Selected selected, byte[] body) {
        ResourceKind kind = selected.kind();
        return ServerResponse.ok()
                .headers(headers -> {
                    // Strong validator for the representation being served, not for the
                    // stored bytes (RFC 9110 §8.8.1, §8.8.3) — see EntityTag for why those
                    // differ for a container and across the two RDF serializations.
                    headers.set(HttpHeaders.ETAG, selected.entityTag().headerValue());
                    // IMF-fixdate, one-second resolution (RFC 9110 §5.6.7, §8.8.2); the
                    // store already truncates to seconds, so nothing is rounded here.
                    headers.setLastModified(selected.view().lastModified());
                    if (selected.negotiated()) {
                        // RFC 9110 §12.5.5: without this a shared cache may hand a Turtle
                        // body to a client that asked for JSON-LD. A non-RDF resource has one
                        // representation, so its response does not vary by Accept.
                        headers.set(HttpHeaders.VARY, HttpHeaders.ACCEPT);
                    }
                    // Solid Protocol §4.1 (T2.9): GET/HEAD additionally carry the
                    // storage-description Link, which is a property of the storage rather
                    // than of this resource — hence the three-argument form.
                    InterfaceMetadata.write(headers, kind, storageDescription.linkValue());
                    // Set explicitly rather than left to the codec, so a HEAD reports the
                    // same length a GET would have written (RFC 9110 §9.3.2).
                    headers.setContentLength(body.length);
                })
                .contentType(selected.contentType())
                .bodyValue(body);
    }

    /**
     * The 304 response (RFC 9110 §15.4.5).
     *
     * <p>The section prescribes both what must be sent and what must not. Required: "any of the
     * following header fields that would have been sent in a 200 (OK) response to the same
     * request: Content-Location, Date, ETag, and Vary" — {@code Date} comes from the server,
     * Cistern sends no {@code Content-Location}, and the other two are written here from the
     * same {@link Selected} a 200 would have used, so the tag on a 304 is by construction the
     * tag on the 200 it replaces.
     *
     * <p>Withheld: everything else. "A sender SHOULD NOT generate representation metadata other
     * than the above listed fields unless said metadata exists for the purpose of guiding cache
     * updates (e.g., Last-Modified might be useful if the response does not have an ETag
     * field)" — this response always has an {@code ETag}, so {@code Last-Modified} would be
     * redundant weight. No body and no {@code Content-Type}/{@code Content-Length} either: "a
     * 304 response is terminated by the end of the header section; it cannot contain content".
     *
     * <p>T2.9's discovery links are therefore absent here too, and that is the reading this
     * class takes of Solid Protocol §4.1 rather than an oversight. §4.1 requires the
     * storage-description link "in the response of HTTP {@code GET}, {@code HEAD} and
     * {@code OPTIONS} requests", and a 304 is such a response; but §15.4.5 is explicit that a
     * 304 sends the listed fields and, beyond them, only metadata "for the purpose of guiding
     * cache updates". A 304 is by definition sent to a client that already holds this
     * representation and the full header set of the 200 that produced it, so the link is
     * information it has. Every response that hands a client a representation carries it. If the
     * architect prefers the literal reading, the change is one {@code headers.add} — flagged on
     * the T2.9 PR rather than decided quietly here.
     */
    private static Mono<ServerResponse> notModified(Selected selected) {
        return ServerResponse.status(HttpStatus.NOT_MODIFIED)
                .headers(headers -> {
                    headers.set(HttpHeaders.ETAG, selected.entityTag().headerValue());
                    if (selected.negotiated()) {
                        headers.set(HttpHeaders.VARY, HttpHeaders.ACCEPT);
                    }
                })
                .build();
    }


    // ---------------------------------------------------------------- request parsing

    /**
     * The parsed {@code Accept} entries, empty when the header is absent. A malformed
     * {@code Accept} is the client's error, so Spring's {@link InvalidMediaTypeException} is
     * translated into the domain's {@link CisternException.BadInput} (→ 400) instead of
     * escaping as an unmapped exception (→ 500).
     */
    private static List<MediaType> acceptOf(ServerRequest request) {
        try {
            return request.headers().accept();
        } catch (InvalidMediaTypeException e) {
            throw new CisternException.BadInput(
                    WebfluxMessage.ACCEPT_MALFORMED.format(e.getMessage()));
        }
    }

}
