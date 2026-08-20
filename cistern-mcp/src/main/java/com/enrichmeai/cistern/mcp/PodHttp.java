package com.enrichmeai.cistern.mcp;

import com.enrichmeai.cistern.core.Representation;
import com.enrichmeai.cistern.core.ResourceIdentifier;
import com.enrichmeai.cistern.core.rdf.RdfIo;
import com.enrichmeai.cistern.wac.AccessRequirement;
import com.enrichmeai.cistern.wac.RequiredAccess;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionException;

import org.apache.jena.rdf.model.Model;
import reactor.core.publisher.Mono;

/**
 * Every HTTP request the MCP front door makes, in one class — and the <strong>only</strong>
 * way it touches pod data (ARCHITECTURE decision 6). There is no other path: no store handle,
 * no service call, no import of anything below cistern-webflux's HTTP surface. A tool call
 * becomes a request against the running server carrying the bound credential
 * ({@link BearerCredential}), crosses {@code AuthorizationFilter} like every other client's
 * request, is decided by the same WAC engine, and leaves the same receipt.
 *
 * <p>Asynchronous throughout ({@link HttpClient#sendAsync} composed as {@link Mono}s, the
 * pattern set by cistern-cli's {@code PodClient}); nothing here blocks (ground rule 3 — the
 * one deliberate exception in this module is the transport boundary in
 * {@link McpStdioSession}, not here).
 *
 * <p>Status classification lives in {@link #expecting}: 401/403 always signal
 * {@link PodProblem.Refused}, 412 always {@link PodProblem.PreconditionFailed}, an expected
 * status passes, and anything else is {@link PodProblem.Unexpected} carrying the server's own
 * problem document — so every tool refuses, conflicts and fails through one vocabulary.
 */
final class PodHttp {

    /** Long enough for loopback or LAN, short enough that a wrong base URL fails fast. */
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    /** The HTTP methods this transport issues, for {@code RequiredAccess}'s table. */
    static final String GET = "GET";
    static final String PUT = "PUT";
    static final String DELETE = "DELETE";

    /** One response, as the tools consume it. */
    record PodResponse(int status, java.net.http.HttpHeaders headers, byte[] body) {

        Optional<String> contentType() {
            return headers.firstValue(HttpHeaderName.CONTENT_TYPE.fieldName());
        }

        Optional<EntityTagHeader> etag() {
            return headers.firstValue(HttpHeaderName.ETAG.fieldName()).map(EntityTagHeader::new);
        }

        String bodyText() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    private final HttpClient http;
    private final BearerCredential credential;

    PodHttp(HttpClient http, BearerCredential credential) {
        this.http = Objects.requireNonNull(http, "http");
        this.credential = Objects.requireNonNull(credential, "credential");
    }

    /** A client with the timeouts above, bound to {@code credential}. */
    static PodHttp connect(BearerCredential credential) {
        return new PodHttp(HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build(), credential);
    }

    // ---- the requests the tools make -------------------------------------------------------

    /** {@code GET}, optionally negotiating a representation. */
    Mono<PodResponse> get(ResourceIdentifier target, URI requestUri, Optional<String> accept) {
        HttpRequest.Builder builder = authenticated(requestUri).GET();
        accept.ifPresent(type -> builder.header(HttpHeaderName.ACCEPT.fieldName(), type));
        return send(target, builder.timeout(REQUEST_TIMEOUT).build());
    }

    /** {@code PUT} {@code body} as {@code contentType} under {@code precondition}. */
    Mono<PodResponse> put(ResourceIdentifier target, URI requestUri, String contentType,
                          byte[] body, WritePrecondition precondition) {
        HttpRequest request = precondition.apply(authenticated(requestUri))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(body))
                .header(HttpHeaderName.CONTENT_TYPE.fieldName(), contentType)
                .timeout(REQUEST_TIMEOUT)
                .build();
        return send(target, request);
    }

    /** {@code DELETE}. */
    Mono<PodResponse> delete(ResourceIdentifier target, URI requestUri) {
        return send(target, authenticated(requestUri).DELETE().timeout(REQUEST_TIMEOUT).build());
    }

    // ---- the ACL conversation (grant / revoke), the same one cistern-cli has ---------------

    /** {@code GET} the ACL resource as Turtle: its graph and validator, or {@link AclFetch.Absent}. */
    Mono<AclFetch> fetchAcl(ResourceIdentifier acl, URI requestUri) {
        return get(acl, requestUri, Optional.of(Representation.TURTLE))
                .flatMap(response -> expecting(acl,
                        RequiredAccess.forRequest(GET, acl), response,
                        PodStatus.OK, PodStatus.NOT_FOUND))
                .map(response -> readAcl(acl, response));
    }

    /** {@code PUT} {@code graph} as Turtle at the ACL resource, under {@code precondition}. */
    Mono<Void> putAcl(ResourceIdentifier acl, URI requestUri, Model graph, WritePrecondition precondition) {
        Representation turtle = RdfIo.serialize(graph, Representation.TURTLE);
        return put(acl, requestUri, turtle.contentType(), turtle.data(), precondition)
                .flatMap(response -> expecting(acl,
                        RequiredAccess.forRequest(PUT, acl), response,
                        PodStatus.CREATED, PodStatus.NO_CONTENT))
                .then();
    }

    // ---- classification (the one place a status becomes an outcome) ------------------------

    /**
     * Pass {@code response} through if its status is one of {@code successes}; otherwise
     * signal the {@link PodProblem} it means. 401/403 are {@link PodProblem.Refused}
     * unconditionally — a refusal is never "unexpected" — carrying {@code required} so the
     * rendered result names what the server checked; 412 is always the precondition.
     */
    static Mono<PodResponse> expecting(ResourceIdentifier target, List<AccessRequirement> required,
                                       PodResponse response, PodStatus... successes) {
        Optional<PodStatus> known = PodStatus.of(response.status());
        if (known.filter(PodStatus::isRefusal).isPresent()) {
            return Mono.error(new PodProblem.Refused(target, known.get(), required));
        }
        if (known.filter(PodStatus.PRECONDITION_FAILED::equals).isPresent()) {
            return Mono.error(new PodProblem.PreconditionFailed(target));
        }
        for (PodStatus success : successes) {
            if (known.filter(success::equals).isPresent()) {
                return Mono.just(response);
            }
        }
        return Mono.error(new PodProblem.Unexpected(
                target, response.status(), response.body(), response.contentType()));
    }

    // ---- wire ------------------------------------------------------------------------------

    private HttpRequest.Builder authenticated(URI requestUri) {
        return HttpRequest.newBuilder(requestUri)
                .header(HttpHeaderName.AUTHORIZATION.fieldName(), credential.headerValue());
    }

    /** Send lazily, so a retry re-issues the request; a wire failure is {@link PodProblem.Transport}. */
    private Mono<PodResponse> send(ResourceIdentifier target, HttpRequest request) {
        return Mono.defer(() -> Mono.fromFuture(
                        http.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())))
                .onErrorMap(t -> !(t instanceof PodProblem),
                        t -> new PodProblem.Transport(target, unwrap(t)))
                .map(response -> new PodResponse(response.statusCode(), response.headers(), response.body()));
    }

    private static AclFetch readAcl(ResourceIdentifier acl, PodResponse response) {
        if (response.status() == PodStatus.NOT_FOUND.code()) {
            return new AclFetch.Absent();
        }
        EntityTagHeader etag = response.etag()
                .orElseThrow(() -> new PodProblem.Unexpected(
                        acl, response.status(), response.body(), response.contentType()));
        Model graph = RdfIo.parse(
                new Representation(response.contentType().orElse(Representation.TURTLE), response.body()),
                acl);
        return new AclFetch.Found(graph, etag);
    }

    private static Throwable unwrap(Throwable t) {
        return t instanceof CompletionException && t.getCause() != null ? t.getCause() : t;
    }
}
