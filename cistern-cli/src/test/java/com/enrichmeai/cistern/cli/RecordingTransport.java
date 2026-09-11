package com.enrichmeai.cistern.cli;

import com.enrichmeai.cistern.core.ResourceIdentifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.apache.jena.rdf.model.Model;
import reactor.core.publisher.Mono;

/**
 * A {@link PodTransport} over a real one that records every request it forwards, with the
 * precondition it carried — the "counting transport" of #200's DoD. Nothing on the wire is
 * doubled: every request still goes to the server and its answer comes back unchanged. A test
 * that needs to interfere overrides the one method it interferes with.
 */
class RecordingTransport implements PodTransport {

    /** One forwarded request: what, where, and under which precondition (none for a read or a container delete). */
    record Request(PodMethod method, ResourceIdentifier resource, Optional<WritePrecondition> precondition) {
    }

    private final PodTransport delegate;
    private final List<Request> requests = new ArrayList<>();

    RecordingTransport(PodTransport delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    /** Every request forwarded so far, in order. */
    List<Request> requests() {
        return List.copyOf(requests);
    }

    /** The forwarded requests with {@code method}. */
    List<Request> requests(PodMethod method) {
        return requests.stream().filter(request -> request.method() == method).toList();
    }

    @Override
    public Mono<AclFetch> fetch(ResourceIdentifier acl) {
        return record(PodMethod.GET, acl, Optional.empty()).then(delegate.fetch(acl));
    }

    @Override
    public Mono<Void> put(ResourceIdentifier acl, Model graph, WritePrecondition precondition) {
        return record(PodMethod.PUT, acl, Optional.of(precondition)).then(delegate.put(acl, graph, precondition));
    }

    @Override
    public Mono<ContainerCreation> createContainer(ResourceIdentifier container) {
        return record(PodMethod.PUT, container, Optional.of(new WritePrecondition.IfNoneMatchAny()))
                .then(delegate.createContainer(container));
    }

    @Override
    public Mono<Optional<EntityTagHeader>> put(ResourceIdentifier resource, byte[] body, FileMediaType mediaType,
                                               WritePrecondition precondition) {
        return record(PodMethod.PUT, resource, Optional.of(precondition))
                .then(delegate.put(resource, body, mediaType, precondition));
    }

    @Override
    public Mono<EntityTagHeader> validator(ResourceIdentifier resource) {
        return record(PodMethod.HEAD, resource, Optional.empty()).then(delegate.validator(resource));
    }

    @Override
    public Mono<Deletion> delete(ResourceIdentifier resource, WritePrecondition precondition) {
        return record(PodMethod.DELETE, resource, Optional.of(precondition)).then(delegate.delete(resource, precondition));
    }

    @Override
    public Mono<Deletion> deleteContainer(ResourceIdentifier container) {
        return record(PodMethod.DELETE, container, Optional.empty()).then(delegate.deleteContainer(container));
    }

    /** Recorded at subscription time, so a request re-issued by a retry is recorded again. */
    private Mono<Void> record(PodMethod method, ResourceIdentifier resource, Optional<WritePrecondition> precondition) {
        return Mono.fromRunnable(() -> requests.add(new Request(method, resource, precondition)));
    }
}
