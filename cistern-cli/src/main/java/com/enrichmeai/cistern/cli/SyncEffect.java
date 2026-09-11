package com.enrichmeai.cistern.cli;

/**
 * What one {@link SyncAction} turned out to do once the server had answered — a closed set,
 * so an enum (ground rule 7). Two of the five are the server saying the end state was already
 * there, which for a mirror is success, not an error.
 */
enum SyncEffect {

    /** 201: a container or document that was not there now is. */
    CREATED,

    /** 412 on a create-only container {@code PUT}: the container already existed; left as it is. */
    PRESENT,

    /** 204: the document was replaced, its validator having matched. */
    REPLACED,

    /** 204: the resource is gone. */
    DELETED,

    /** 404 on a {@code DELETE}: it was already gone. */
    ABSENT
}
