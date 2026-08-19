package com.enrichmeai.cistern.cli;

/**
 * What a create-only {@code PUT} of a container came back with — a closed set of two, so an
 * enum (ground rule 7). Written under {@code If-None-Match: *} precisely so that a container
 * someone has already described is never emptied by a provisioning run: the second outcome is
 * the server saying "there is one".
 */
enum ContainerCreation {

    /** 201: the container did not exist and now does. */
    CREATED,

    /** 412: the container is already there; nothing was written. */
    ALREADY_THERE
}
