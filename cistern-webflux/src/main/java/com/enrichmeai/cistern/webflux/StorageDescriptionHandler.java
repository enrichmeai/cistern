package com.enrichmeai.cistern.webflux;

import com.enrichmeai.cistern.core.CisternException;
import com.enrichmeai.cistern.core.ldp.Ldp;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Serves the storage description resource (T2.9), Solid Protocol §4.1.
 *
 * <p>"Servers MUST include statements about the storage as part of the storage description
 * resource." What those statements are, and where the resource lives, is
 * {@link StorageDescription}; this class is the HTTP surface over it and holds no discovery
 * knowledge of its own.
 *
 * <h2>Why this resource has a handler of its own</h2>
 * Every other URI in a pod names something in the store, and {@code LdpService} answers for it.
 * This one names nothing: it is generated from configuration, exists before any resource has
 * been written, and cannot be created, replaced or deleted. Routing it through the ordinary read
 * path would mean either teaching the store about a resource it does not hold, or answering 404
 * for the one resource §4.1 requires to be there. So it is served here, from a route registered
 * ahead of the catch-all — see {@code CisternWebFluxConfiguration#cisternStorageDescriptionRoutes}.
 *
 * <h2>The three methods, and only those three</h2>
 * {@link #METHODS} is both the route predicate and the {@code Allow} value, so what this
 * resource accepts and what it says it accepts are one list — the same invariant
 * {@link ResourceKind} enforces for stored resources, applied to the one resource that has no row
 * there. Solid Protocol §5.2's mandatory trio ({@code GET}, {@code HEAD}, {@code OPTIONS}) is
 * exactly the set: the description is a projection of the server's own configuration, so there is
 * no state a write could change.
 *
 * <h2>Negotiation goes through the ordinary path</h2>
 * {@link ContentNegotiator} and {@link RdfSerialization} choose and produce the representation,
 * so §5.5's "the server MUST satisfy {@code GET} requests ... in {@code text/turtle} or
 * {@code application/ld+json}" holds here for the same reason it holds everywhere else, and a
 * client that asks for neither gets the same 406 it would get from any other RDF source. Nothing
 * is hand-serialized.
 *
 * <h2>No validator</h2>
 * The response carries no {@code ETag} and no {@code Last-Modified}. {@link EntityTag} describes
 * the representation of a {@code ResourceView} — a stored resource with a store-side validator —
 * and this resource has none: there is no stored state, so there is no modification to date and
 * nothing a lost-update guard would be protecting. Minting a second kind of validator for it
 * would add a source of truth to serve a conditional request that the specification does not ask
 * for on this resource.
 *
 * <h2>Errors</h2>
 * Nothing here maps a status code (ground rule 4) and there is no {@code onErrorResume}: a
 * negotiation refusal leaves as {@link CisternException.NotAcceptable} and the single error
 * mapper (T2.6) renders it. The body sits inside {@code Mono.defer} so a synchronous throw
 * becomes an error signal.
 */
@Component
public class StorageDescriptionHandler {

    /**
     * The methods this resource supports — Solid Protocol §5.2's mandatory set and nothing more.
     * Read by the route predicate and by {@link #ALLOW}, so the route and the advertisement
     * cannot disagree.
     */
    static final List<HttpMethod> METHODS =
            List.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.OPTIONS);

    /** {@code Allow} for this resource (RFC 9110 §10.2.1), rendered from {@link #METHODS}. */
    private static final String ALLOW = HttpConstants.allow(METHODS);

    /**
     * {@code Link: <http://www.w3.org/ns/ldp#Resource>; rel="type"} — LDP §4.2.1.4 requires a
     * server exposing an LDPR to "advertise their LDP support by exposing a HTTP Link header
     * with a target URI of http://www.w3.org/ns/ldp#Resource". The description is an RDF document
     * served over HTTP, so it advertises exactly what {@link ResourceKind#RDF_DOCUMENT} does —
     * and no container type, since it contains nothing.
     */
    private static final String LDP_RESOURCE_LINK = HttpConstants.linkType(Ldp.RESOURCE.getURI());

    private final StorageDescription description;
    private final ContentNegotiator negotiator;

    public StorageDescriptionHandler(StorageDescription description, ContentNegotiator negotiator) {
        this.description = description;
        this.negotiator = negotiator;
    }

    /**
     * {@code GET} and {@code HEAD}, one code path — RFC 9110 §9.3.2, with WebFlux's
     * {@code HttpHeadResponseDecorator} dropping the body, exactly as {@link ResourceReadHandler}
     * does it.
     */
    public Mono<ServerResponse> read(ServerRequest request) {
        return Mono.defer(() -> {
            RdfSerialization serialization = negotiator.negotiateRdf(acceptOf(request));
            return Mono.fromCallable(() -> serialization.serialize(description.graph()).data())
                    .flatMap(body -> write(serialization, body));
        });
    }

    /**
     * {@code OPTIONS} on the description resource. 204 with no representation selected, for the
     * reasons {@link ResourceOptionsHandler} sets out — and carrying no {@code Accept-Put},
     * {@code Accept-Post} or {@code Accept-Patch}, because {@link #ALLOW} lists none of the
     * methods those fields would describe (Solid Protocol §5.2: the {@code Accept-*} values must
     * "correspond to acceptable HTTP methods listed in {@code Allow} header field value").
     */
    public Mono<ServerResponse> options(ServerRequest request) {
        return ServerResponse.noContent().headers(this::describe).build();
    }

    private Mono<ServerResponse> write(RdfSerialization serialization, byte[] body) {
        return ServerResponse.ok()
                .headers(headers -> {
                    describe(headers);
                    // Two RDF representations are available here as on any other RDF source, so
                    // a shared cache must key on Accept (RFC 9110 §12.5.5).
                    headers.set(HttpHeaders.VARY, HttpHeaders.ACCEPT);
                    headers.setContentLength(body.length);
                })
                .contentType(serialization.mediaType())
                .bodyValue(body);
    }

    /**
     * The header fields every response from this resource carries, so {@code GET}, {@code HEAD}
     * and {@code OPTIONS} describe it identically.
     *
     * <p>The {@code storageDescription} link is emitted here too, on the description's own
     * responses. §4.1 scopes the requirement to "a resource in a storage" without exempting any,
     * and this resource is addressed within the storage's URI space, so the link is present and
     * points at the resource being read. A client walking links therefore always has one,
     * whichever resource it started from.
     */
    private void describe(HttpHeaders headers) {
        headers.set(HttpHeaders.ALLOW, ALLOW);
        headers.add(HttpHeaders.LINK, LDP_RESOURCE_LINK);
        headers.add(HttpHeaders.LINK, description.linkValue());
    }

    /** As {@link ResourceReadHandler}: a malformed {@code Accept} is the client's 400, not a 500. */
    private static List<MediaType> acceptOf(ServerRequest request) {
        try {
            return request.headers().accept();
        } catch (InvalidMediaTypeException e) {
            throw new CisternException.BadInput(
                    WebfluxMessage.ACCEPT_MALFORMED.format(e.getMessage()));
        }
    }
}
