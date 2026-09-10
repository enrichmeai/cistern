package com.enrichmeai.cistern.cli;

import com.enrichmeai.cistern.core.ResourceIdentifier;

import java.util.Optional;

import reactor.core.publisher.Mono;

/**
 * What provisioning and syncing need from the server beyond the two ACL requests of
 * {@link AclTransport}: to create a container, to send a document's bytes, to ask a resource's
 * validator, and to delete. {@link PodClient} is the implementation over HTTP; the seam exists,
 * as {@link AclTransport}'s does, so a sequence can be exercised against a real server with a
 * concurrent writer — or a recorder — interposed, without a mocked wire.
 */
interface PodTransport extends AclTransport {

    /**
     * {@code PUT} an empty container at {@code container}, under {@code If-None-Match: *} so
     * that a container already described by someone is never replaced with an empty one.
     *
     * @return {@link ContainerCreation#CREATED} on 201; {@link ContainerCreation#ALREADY_THERE}
     *     on 412
     */
    Mono<ContainerCreation> createContainer(ResourceIdentifier container);

    /**
     * {@code PUT} {@code body} at {@code resource} as {@code mediaType}, under
     * {@code precondition}. The bytes are sent as they are; nothing is parsed (ground rule 5).
     *
     * @return on 201 or 204, the {@code ETag} the response carried — present for a non-RDF
     *     source, absent for an RDF source (RFC 9110 §9.3.4; ask {@link #validator} then);
     *     {@link CliFailure.Conflict} on 412
     */
    Mono<Optional<EntityTagHeader>> put(ResourceIdentifier resource, byte[] body, FileMediaType mediaType,
                                        WritePrecondition precondition);

    /**
     * {@code HEAD} {@code resource}: the validator it is served with.
     *
     * @return the {@code ETag}; {@link CliFailure.MissingValidator} if there is none
     */
    Mono<EntityTagHeader> validator(ResourceIdentifier resource);

    /**
     * {@code DELETE} {@code resource} under {@code precondition}.
     *
     * @return {@link Deletion#DELETED} on 204; {@link Deletion#ALREADY_ABSENT} on 404;
     *     {@link CliFailure.Conflict} on 412
     */
    Mono<Deletion> delete(ResourceIdentifier resource, WritePrecondition precondition);

    /**
     * {@code DELETE} {@code container}, unconditionally — see {@link Synchronizer} for why no
     * precondition applies to a container.
     *
     * @return {@link Deletion#DELETED} on 204; {@link Deletion#ALREADY_ABSENT} on 404;
     *     {@link CliFailure.ContainerNotEmpty} on 409
     */
    Mono<Deletion> deleteContainer(ResourceIdentifier container);
}
