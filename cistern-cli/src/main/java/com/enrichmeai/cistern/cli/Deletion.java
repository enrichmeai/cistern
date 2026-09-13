package com.enrichmeai.cistern.cli;

/**
 * What a {@code DELETE} came back with — a closed set of two, so an enum (ground rule 7),
 * the counterpart of {@link ContainerCreation}. A resource that is already gone is the state a
 * delete wanted, so 404 is an outcome here rather than a failure.
 */
enum Deletion {

    /** 204: it was there and is not now. */
    DELETED,

    /** 404: it was not there. */
    ALREADY_ABSENT
}
