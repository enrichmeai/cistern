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
 * The HTTP requests the CLI makes — read an ACL, write an ACL, create a container, send a
 * document's bytes, ask a validator, delete — over the JDK's {@link HttpClient}, composed as
 * {@link Mono}s so the editor, the provisioner and the synchronizer can chain and retry them.
 *
 * <p>Deliberately no other requests. The CLI never fetches a document's body, does not list
 * containers, does not probe permissions: it does exactly what an owner working by hand would
 * do, with the caller's own credential, so that whatever the server would refuse the owner it
 * refuses the CLI. The one read of any kind is {@link #validator} — a {@code HEAD}, for the
 * {@code ETag} a later {@code If-Match} needs, which returns no body and which the server
 * gates on {@code acl:Read}. Every non-2xx answer this class has a rule for becomes a
 * {@link CliFailure}; every one it does not is {@link CliFailure.UnexpectedStatus} rather than
 * a guess.
 *
 * <p>Asynchronous throughout ({@link HttpClient#sendAsync}); nothing here blocks. The single
 * reactive-to-synchronous boundary in the whole tool is the command's {@code call()}.
 */
final class PodClient implements PodTransport {

    /**
     * A container with no client-authored triples — what a fresh pod root is until its owner
     * describes it, and what the server itself creates for a missing intermediate. Containment
     * is derived on read and never stored, so empty is complete.
     */
    private static final Representation EMPTY_CONTAINER = new Representation(Representation.TURTLE, new byte[0]);

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

    /**
     * {@code PUT} an empty container at {@code container}, create-only.
     *
     * @return {@link ContainerCreation#CREATED} on 201; {@link ContainerCreation#ALREADY_THERE}
     *     on 412, the server having refused to replace what is there
     */
    @Override
    public Mono<ContainerCreation> createContainer(ResourceIdentifier container) {
        HttpRequest request = new WritePrecondition.IfNoneMatchAny()
                .apply(authenticated(HttpRequest.newBuilder(container.uri())))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(EMPTY_CONTAINER.data()))
                .header(HttpHeaderName.CONTENT_TYPE.fieldName(), EMPTY_CONTAINER.contentType())
                .timeout(REQUEST_TIMEOUT)
                .build();
        return send(PodMethod.PUT, container, request)
                .flatMap(response -> created(container, response));
    }

    /**
     * {@code PUT} {@code body} at {@code resource} as {@code mediaType}, under
     * {@code precondition}; the bytes go as they are.
     *
     * @return on 201 or 204, the {@code ETag} the response carried if it carried one;
     *     {@link CliFailure.Conflict} on 412
     */
    @Override
    public Mono<Optional<EntityTagHeader>> put(ResourceIdentifier resource, byte[] body, FileMediaType mediaType,
                                               WritePrecondition precondition) {
        HttpRequest request = precondition.apply(authenticated(HttpRequest.newBuilder(resource.uri())))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(body))
                .header(HttpHeaderName.CONTENT_TYPE.fieldName(), mediaType.contentType())
                .timeout(REQUEST_TIMEOUT)
                .build();
        return send(PodMethod.PUT, resource, request)
                .flatMap(response -> stored(resource, precondition, response));
    }

    /**
     * {@code HEAD} {@code resource} for its validator. Asked for as Turtle, so that for an RDF
     * source the tag recorded is the one the default representation carries; the server
     * compares {@code If-Match} across all of a resource's representations, so either would do.
     *
     * @return the {@code ETag}; {@link CliFailure.MissingValidator} if the 200 carried none
     */
    @Override
    public Mono<EntityTagHeader> validator(ResourceIdentifier resource) {
        HttpRequest request = authenticated(HttpRequest.newBuilder(resource.uri()))
                .method(PodMethod.HEAD.name(), HttpRequest.BodyPublishers.noBody())
                .header(HttpHeaderName.ACCEPT.fieldName(), Representation.TURTLE)
                .timeout(REQUEST_TIMEOUT)
                .build();
        return send(PodMethod.HEAD, resource, request)
                .map(response -> validatorOf(resource, response));
    }

    /**
     * {@code DELETE} {@code resource} under {@code precondition}.
     *
     * @return {@link Deletion#DELETED} on 204; {@link Deletion#ALREADY_ABSENT} on 404;
     *     {@link CliFailure.Conflict} on 412
     */
    @Override
    public Mono<Deletion> delete(ResourceIdentifier resource, WritePrecondition precondition) {
        HttpRequest request = precondition.apply(authenticated(HttpRequest.newBuilder(resource.uri())))
                .DELETE()
                .timeout(REQUEST_TIMEOUT)
                .build();
        return send(PodMethod.DELETE, resource, request)
                .flatMap(response -> deleted(resource, Optional.of(precondition), response));
    }

    /**
     * {@code DELETE} {@code container}, unconditionally.
     *
     * @return {@link Deletion#DELETED} on 204; {@link Deletion#ALREADY_ABSENT} on 404;
     *     {@link CliFailure.ContainerNotEmpty} on 409
     */
    @Override
    public Mono<Deletion> deleteContainer(ResourceIdentifier container) {
        HttpRequest request = authenticated(HttpRequest.newBuilder(container.uri()))
                .DELETE()
                .timeout(REQUEST_TIMEOUT)
                .build();
        return send(PodMethod.DELETE, container, request)
                .flatMap(response -> deleted(container, Optional.empty(), response));
    }

    // ---- wire ------------------------------------------------------------------------------

    private HttpRequest.Builder authenticated(HttpRequest.Builder builder) {
        token.ifPresent(t -> builder.header(HttpHeaderName.AUTHORIZATION.fieldName(), t.headerValue()));
        return builder;
    }

    /**
     * Send lazily, so a retry re-issues the request rather than replaying a finished future, and
     * turn any transport failure into {@link CliFailure.Transport}. Refusals are decided here for
     * every method, since 401/403 mean the same thing whichever request drew them; how they are
     * explained depends on whether {@code uri} is an ACL (Control on what it governs) or a
     * resource (Write on it).
     */
    private Mono<HttpResponse<byte[]>> send(PodMethod method, ResourceIdentifier uri, HttpRequest request) {
        return Mono.defer(() -> Mono.fromFuture(http.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())))
                .onErrorMap(t -> !(t instanceof CliFailure), t -> new CliFailure.Transport(uri, unwrap(t)))
                .flatMap(response -> PodStatus.of(response.statusCode())
                        .filter(PodStatus::isRefusal)
                        .<Mono<HttpResponse<byte[]>>>map(status -> Mono.error(refused(method, uri, status)))
                        .orElseGet(() -> Mono.just(response)));
    }

    private static CliFailure refused(PodMethod method, ResourceIdentifier uri, PodStatus status) {
        return AclResource.isAcl(uri)
                ? new CliFailure.Refused(method, uri, status, AclResource.governedBy(uri))
                : new CliFailure.Refused(method, uri, status);
    }

    private static Mono<ContainerCreation> created(ResourceIdentifier container, HttpResponse<byte[]> response) {
        Optional<PodStatus> status = PodStatus.of(response.statusCode());
        if (status.filter(PodStatus.CREATED::equals).isPresent()) {
            return Mono.just(ContainerCreation.CREATED);
        }
        if (status.filter(PodStatus.PRECONDITION_FAILED::equals).isPresent()) {
            return Mono.just(ContainerCreation.ALREADY_THERE);
        }
        return Mono.error(unexpected(PodMethod.PUT, container, response));
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

    private static Mono<Optional<EntityTagHeader>> stored(ResourceIdentifier resource, WritePrecondition precondition,
                                                          HttpResponse<byte[]> response) {
        Optional<PodStatus> status = PodStatus.of(response.statusCode());
        if (status.filter(PodStatus::isWritten).isPresent()) {
            return Mono.just(response.headers().firstValue(HttpHeaderName.ETAG.fieldName()).map(EntityTagHeader::new));
        }
        if (status.filter(PodStatus.PRECONDITION_FAILED::equals).isPresent()) {
            return Mono.error(new CliFailure.Conflict(resource, precondition));
        }
        return Mono.error(unexpected(PodMethod.PUT, resource, response));
    }

    private static EntityTagHeader validatorOf(ResourceIdentifier resource, HttpResponse<byte[]> response) {
        if (PodStatus.of(response.statusCode()).filter(PodStatus.OK::equals).isEmpty()) {
            throw unexpected(PodMethod.HEAD, resource, response);
        }
        return response.headers().firstValue(HttpHeaderName.ETAG.fieldName())
                .map(EntityTagHeader::new)
                .orElseThrow(() -> new CliFailure.MissingValidator(resource));
    }

    /**
     * A 412 is a conflict only where a precondition was sent; a 409 names a container that is
     * not empty (Solid Protocol §5.4) and can only come back for one. Anything else is
     * unexpected.
     */
    private static Mono<Deletion> deleted(ResourceIdentifier resource, Optional<WritePrecondition> precondition,
                                          HttpResponse<byte[]> response) {
        Optional<PodStatus> status = PodStatus.of(response.statusCode());
        if (status.filter(PodStatus.NO_CONTENT::equals).isPresent()) {
            return Mono.just(Deletion.DELETED);
        }
        if (status.filter(PodStatus.NOT_FOUND::equals).isPresent()) {
            return Mono.just(Deletion.ALREADY_ABSENT);
        }
        if (status.filter(PodStatus.PRECONDITION_FAILED::equals).isPresent() && precondition.isPresent()) {
            return Mono.error(new CliFailure.Conflict(resource, precondition.get()));
        }
        if (status.filter(PodStatus.CONFLICT::equals).isPresent() && resource.isContainer()) {
            return Mono.error(new CliFailure.ContainerNotEmpty(resource));
        }
        return Mono.error(unexpected(PodMethod.DELETE, resource, response));
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
