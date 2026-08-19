package com.enrichmeai.cistern.cli;

import com.enrichmeai.cistern.core.ResourceIdentifier;

import reactor.core.publisher.Mono;

/**
 * What provisioning needs from the server beyond the two ACL requests of {@link AclTransport}:
 * to create a container — and only to create it. {@link PodClient} is the implementation over
 * HTTP; the seam exists, as {@link AclTransport}'s does, so the create-then-write sequence can
 * be exercised against a real server with a concurrent writer interposed, without a mocked
 * wire.
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
}
