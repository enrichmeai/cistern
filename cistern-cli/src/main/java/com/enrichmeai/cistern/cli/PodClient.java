package com.enrichmeai.cistern.cli;

import com.enrichmeai.cistern.core.Representation;
import com.enrichmeai.cistern.core.ResourceIdentifier;
import com.enrichmeai.cistern.core.rdf.RdfIo;
import com.enrichmeai.cistern.wac.AclResource;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionException;

import org.apache.jena.rdf.model.Model;
import reactor.core.publisher.Mono;

/**
 * The two HTTP requests the CLI makes — read an ACL, write an ACL — over the JDK's
 * {@link HttpClient}, composed as {@link Mono}s so the editor can chain and retry them.
 *
 * <p>Deliberately no other requests. The CLI does not read the resource, does not list
 * containers, does not probe permissions: it does exactly what an owner editing the file by hand
 * would do, with the caller's own credential, so that whatever the server would refuse the owner
 * it refuses the CLI. Every non-2xx answer this class has a rule for becomes a
 * {@link CliFailure}; every one it does not is {@link CliFailure.UnexpectedStatus} rather than
 * a guess.
 *
 * <p>Asynchronous throughout ({@link HttpClient#sendAsync}); nothing here blocks. The single
 * reactive-to-synchronous boundary in the whole tool is the command's {@code call()}.
 */
final class PodClient implements AclTransport {

    /** Long enough for a laptop-to-loopback or LAN round trip, short enough that a typo in --base fails fast. */
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient http;
    private final Optional<BearerToken> token;

    PodClient(HttpClient http, Optional<BearerToken> token) {
        this.http = Objects.requireNonNull(http, "http");
        this.token = Objects.requireNonNull(token, "token");
    }

    /** A client with the timeouts above, following no redirects: an ACL lives where it lives. */
    static PodClient connect(Optional<BearerToken> token) {
        return new PodClient(HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build(), token);
    }

    /**
     * {@code GET} the ACL resource {@code acl} as Turtle.
     *
     * @return the parsed graph with its validator, or {@link AclFetch.Absent} on 404
     */
    @Override
    public Mono<AclFetch> fetch(ResourceIdentifier acl) {
        HttpRequest request = authenticated(HttpRequest.newBuilder(acl.uri()))
                .GET()
                .header(HttpHeaderName.ACCEPT.fieldName(), Representation.TURTLE)
                .timeout(REQUEST_TIMEOUT)
                .build();
        return send(PodMethod.GET, acl, request)
                .map(response -> readAcl(acl, response));
    }

    /**
     * {@code PUT} {@code graph} as Turtle at {@code acl}, under {@code precondition}.
     *
     * @return completes on 201 or 204; {@link CliFailure.Conflict} on 412
     */
    @Override
    public Mono<Void> put(ResourceIdentifier acl, Model graph, WritePrecondition precondition) {
        Representation turtle = RdfIo.serialize(graph, Representation.TURTLE);
        HttpRequest request = precondition.apply(authenticated(HttpRequest.newBuilder(acl.uri())))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(turtle.data()))
                .header(HttpHeaderName.CONTENT_TYPE.fieldName(), turtle.contentType())
                .timeout(REQUEST_TIMEOUT)
                .build();
        return send(PodMethod.PUT, acl, request)
                .flatMap(response -> written(acl, response));
    }

    // ---- wire ------------------------------------------------------------------------------

    private HttpRequest.Builder authenticated(HttpRequest.Builder builder) {
        token.ifPresent(t -> builder.header(HttpHeaderName.AUTHORIZATION.fieldName(), t.headerValue()));
        return builder;
    }

    /**
     * Send lazily, so a retry re-issues the request rather than replaying a finished future, and
     * turn any transport failure into {@link CliFailure.Transport}. Refusals are decided here for
     * both methods, since 401/403 mean the same thing whichever request drew them.
     */
    private Mono<HttpResponse<byte[]>> send(PodMethod method, ResourceIdentifier acl, HttpRequest request) {
        return Mono.defer(() -> Mono.fromFuture(http.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())))
                .onErrorMap(t -> !(t instanceof CliFailure), t -> new CliFailure.Transport(acl, unwrap(t)))
                .flatMap(response -> PodStatus.of(response.statusCode())
                        .filter(PodStatus::isRefusal)
                        .<Mono<HttpResponse<byte[]>>>map(status -> Mono.error(
                                new CliFailure.Refused(method, acl, status, AclResource.governedBy(acl))))
                        .orElseGet(() -> Mono.just(response)));
    }

    private static AclFetch readAcl(ResourceIdentifier acl, HttpResponse<byte[]> response) {
        Optional<PodStatus> status = PodStatus.of(response.statusCode());
        if (status.filter(PodStatus.NOT_FOUND::equals).isPresent()) {
            return new AclFetch.Absent();
        }
        if (status.filter(PodStatus.OK::equals).isEmpty()) {
            throw unexpected(PodMethod.GET, acl, response);
        }
        EntityTagHeader etag = response.headers().firstValue(HttpHeaderName.ETAG.fieldName())
                .map(EntityTagHeader::new)
                .orElseThrow(() -> new CliFailure.MissingValidator(acl));
        String contentType = response.headers().firstValue(HttpHeaderName.CONTENT_TYPE.fieldName())
                .orElse(Representation.TURTLE);
        Model graph = RdfIo.parse(new Representation(contentType, response.body()), acl);
        return new AclFetch.Found(graph, etag);
    }

    private static Mono<Void> written(ResourceIdentifier acl, HttpResponse<byte[]> response) {
        Optional<PodStatus> status = PodStatus.of(response.statusCode());
        if (status.filter(PodStatus::isWritten).isPresent()) {
            return Mono.empty();
        }
        if (status.filter(PodStatus.PRECONDITION_FAILED::equals).isPresent()) {
            return Mono.error(new CliFailure.Conflict(acl));
        }
        return Mono.error(unexpected(PodMethod.PUT, acl, response));
    }

    private static CliFailure unexpected(PodMethod method, ResourceIdentifier acl, HttpResponse<byte[]> response) {
        return new CliFailure.UnexpectedStatus(method, acl, response.statusCode(),
                new String(response.body(), StandardCharsets.UTF_8));
    }

    private static Throwable unwrap(Throwable t) {
        return t instanceof CompletionException && t.getCause() != null ? t.getCause() : t;
    }
}
