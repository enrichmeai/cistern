package com.enrichmeai.cistern.cli;

import java.util.Objects;

/**
 * What the state file remembers about one resource this folder has sent — sealed, so the plan
 * and the codec switch over it totally. The trailing slash of the {@link RelativePath} it is
 * keyed by says which of the two it is (Solid Protocol §3.1), so the file carries no second
 * "kind" field to disagree with the key.
 */
sealed interface SyncedResource permits SyncedResource.Document, SyncedResource.Container {

    /**
     * A document that was sent: the validator the server gave it, so the next write can be
     * {@code If-Match}, and the hash of what was sent, so an unchanged file is not sent again.
     */
    record Document(EntityTagHeader etag, ContentHash sha256) implements SyncedResource {
        public Document {
            Objects.requireNonNull(etag, "etag");
            Objects.requireNonNull(sha256, "sha256");
        }
    }

    /**
     * A container that was created (or found already there) for a sub-folder. Nothing to
     * remember beyond its existence: a container's validator changes whenever a member does,
     * so one recorded here would be stale by the next line of the transcript.
     */
    record Container() implements SyncedResource {
    }
}
