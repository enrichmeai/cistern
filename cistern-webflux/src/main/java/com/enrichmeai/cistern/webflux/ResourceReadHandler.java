package com.enrichmeai.cistern.webflux;

import com.enrichmeai.cistern.core.CisternException;
import com.enrichmeai.cistern.core.Representation;
import com.enrichmeai.cistern.core.ResourceIdentifier;
import com.enrichmeai.cistern.core.ldp.LdpService;
import com.enrichmeai.cistern.core.ldp.ResourceView;
import com.enrichmeai.cistern.core.rdf.RdfIo;
import org.springframework.http.HttpHeaders;
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
 * to core and arrives here already made.
 *
 * <h2>HEAD cannot drift from GET</h2>
 * Both methods enter through {@link #read(ServerRequest)}; there is no second code path and
 * no {@code if (isHead)} branch. RFC 9110 §9.3.2 defines {@code HEAD} as "identical to GET
 * except that the server MUST NOT send content" and requires that the server "SHOULD send the
 * same header fields ... that it would have sent if the request method had been GET", so the
 * response is built in full, {@code Content-Length} included, and WebFlux's
 * {@code HttpHeadResponseDecorator} discards the body on the way out.
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

    public ResourceReadHandler(LdpService ldp, RequestPaths requestPaths,
                               ContentNegotiator negotiator) {
        this.ldp = ldp;
        this.requestPaths = requestPaths;
        this.negotiator = negotiator;
    }

    /** The one handler behind both {@code GET} and {@code HEAD}. */
    public Mono<ServerResponse> read(ServerRequest request) {
        return Mono.defer(() -> {
            ResourceIdentifier target = requestPaths.identifierFor(request);
            List<MediaType> accept = acceptOf(request);
            return ldp.read(target).flatMap(view -> respond(view, accept));
        });
    }

    // ---------------------------------------------------------------- representation

    private Mono<ServerResponse> respond(ResourceView view, List<MediaType> accept) {
        return Mono.fromCallable(() -> switch (view) {
            // An RDF source is always serialized from the graph, never echoed from storage.
            // That is what makes Solid Protocol §5.5 hold unconditionally — either media type
            // is available for any RDF source — and it is the only option for a container,
            // whose containment triples exist in no stored byte (§4.2).
            case ResourceView.Rdf rdf -> {
                MediaType type = negotiator.negotiateRdf(accept);
                Representation body = RdfIo.serialize(rdf.graph(), RdfMediaTypes.canonical(type));
                yield new Rendered(view, type, body.data());
            }
            // A non-RDF source has exactly one representation and is copied out untouched.
            case ResourceView.NonRdf binary -> {
                MediaType stored = storedTypeOf(binary);
                negotiator.requireAcceptable(accept, stored);
                yield new Rendered(view, stored, binary.representation().data());
            }
        }).flatMap(this::write);
    }

    /** The response body and the headers that describe it, before it becomes a response. */
    private record Rendered(ResourceView view, MediaType contentType, byte[] body) {
    }

    private Mono<ServerResponse> write(Rendered rendered) {
        ResourceKind kind = ResourceKind.of(rendered.view());
        return ServerResponse.ok()
                .headers(headers -> {
                    // Strong validator, quoted (RFC 9110 §8.8.3). The store guarantees it
                    // changes whenever the representation changes.
                    headers.set(HttpHeaders.ETAG, "\"" + rendered.view().etag() + "\"");
                    // IMF-fixdate, one-second resolution (RFC 9110 §5.6.7, §8.8.2); the
                    // store already truncates to seconds, so nothing is rounded here.
                    headers.setLastModified(rendered.view().lastModified());
                    for (String type : kind.linkTypes()) {
                        headers.add(HttpHeaders.LINK, "<" + type + ">; rel=\"type\"");
                    }
                    headers.set(HttpHeaders.ALLOW, kind.allow());
                    setIfPresent(headers, HttpConstants.ACCEPT_PUT, kind.acceptPut());
                    setIfPresent(headers, HttpConstants.ACCEPT_POST, kind.acceptPost());
                    setIfPresent(headers, HttpHeaders.ACCEPT_PATCH, kind.acceptPatch());
                    // Set explicitly rather than left to the codec, so a HEAD reports the
                    // same length a GET would have written (RFC 9110 §9.3.2).
                    headers.setContentLength(rendered.body().length);
                })
                .contentType(rendered.contentType())
                .bodyValue(rendered.body());
    }

    private static void setIfPresent(HttpHeaders headers, String name, String value) {
        if (value != null) {
            headers.set(name, value);
        }
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
            throw new CisternException.BadInput("Malformed Accept header: " + e.getMessage());
        }
    }

    /**
     * The stored media type of a non-RDF resource. Stored types only get there through a
     * validated write, so an unparseable one is server-side corruption — an
     * {@link IllegalStateException} (→ 500), consistent with how core treats unparseable
     * stored RDF, and never the reading client's fault.
     */
    private static MediaType storedTypeOf(ResourceView.NonRdf binary) {
        String contentType = binary.representation().contentType();
        try {
            return MediaType.parseMediaType(contentType);
        } catch (InvalidMediaTypeException e) {
            throw new IllegalStateException("Stored content type for <" + binary.identifier().uri()
                    + "> is not a valid media type: " + contentType, e);
        }
    }
}
