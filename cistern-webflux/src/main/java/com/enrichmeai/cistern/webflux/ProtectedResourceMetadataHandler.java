package com.enrichmeai.cistern.webflux;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

/**
 * Serves the protected resource metadata document (RFC 9728 §3.1–3.2, T6.4): "A protected
 * resource metadata document MUST be queried using an HTTP GET request", and "A successful
 * response MUST use the 200 OK HTTP status code and return a JSON object using the
 * {@code application/json} content type".
 *
 * <p>The HTTP surface only. What the document says and where it lives are
 * {@link ProtectedResourceMetadata}'s; which methods reach here and what happens to the rest
 * is the {@link ServerEndpoint} it declares — a {@code PUT} never arrives, because the
 * endpoint's method-not-allowed route answers it first.
 *
 * <p>No validator and no negotiation, for the reasons {@link StorageDescriptionHandler} gives:
 * there is no stored state to validate, and RFC 9728 fixes the one media type.
 */
@Component
public class ProtectedResourceMetadataHandler {

    private final ProtectedResourceMetadata metadata;

    /** {@code Allow} for this resource (RFC 9110 §10.2.1), rendered from the endpoint's methods. */
    private final String allow;

    public ProtectedResourceMetadataHandler(ProtectedResourceMetadata metadata) {
        this.metadata = metadata;
        this.allow = HttpConstants.allow(metadata.endpoint().methods());
    }

    /** {@code GET} and {@code HEAD}, one code path; WebFlux drops the body for {@code HEAD}. */
    public Mono<ServerResponse> read(ServerRequest request) {
        return ServerResponse.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .headers(headers -> headers.set(HttpHeaders.ALLOW, allow))
                .bodyValue(metadata.document().toJson());
    }

    /** {@code OPTIONS}: 204 with {@code Allow}, as every server-managed resource answers it. */
    public Mono<ServerResponse> options(ServerRequest request) {
        return ServerResponse.noContent().headers(headers -> headers.set(HttpHeaders.ALLOW, allow)).build();
    }
}
