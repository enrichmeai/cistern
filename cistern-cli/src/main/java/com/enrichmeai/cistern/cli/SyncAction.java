package com.enrichmeai.cistern.cli;

import com.enrichmeai.cistern.core.ResourceIdentifier;

import java.util.Objects;

/**
 * One thing a sync will do to the pod — sealed, so the executor and the report switch over it
 * totally. Each carries the relative path it is about (for the transcript and the state file)
 * and the resource it addresses (for the request).
 */
sealed interface SyncAction
        permits SyncAction.CreateContainer, SyncAction.Create, SyncAction.Replace,
                SyncAction.DeleteDocument, SyncAction.DeleteContainer {

    RelativePath path();

    ResourceIdentifier resource();

    /** A sub-folder with no container yet: {@code PUT} under {@code If-None-Match: *}. */
    record CreateContainer(RelativePath path, ResourceIdentifier resource) implements SyncAction {
        public CreateContainer {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(resource, "resource");
        }
    }

    /** A file this folder has never sent: {@code PUT} under {@code If-None-Match: *}. */
    record Create(LocalTree.Document document, ResourceIdentifier resource) implements SyncAction {
        public Create {
            Objects.requireNonNull(document, "document");
            Objects.requireNonNull(resource, "resource");
        }

        @Override
        public RelativePath path() {
            return document.path();
        }
    }

    /** A file sent before and changed since: {@code PUT} under {@code If-Match: <etag>}. */
    record Replace(LocalTree.Document document, ResourceIdentifier resource, EntityTagHeader etag)
            implements SyncAction {
        public Replace {
            Objects.requireNonNull(document, "document");
            Objects.requireNonNull(resource, "resource");
            Objects.requireNonNull(etag, "etag");
        }

        @Override
        public RelativePath path() {
            return document.path();
        }
    }

    /** A document sent before and gone locally: {@code DELETE} under {@code If-Match: <etag>}. */
    record DeleteDocument(RelativePath path, ResourceIdentifier resource, EntityTagHeader etag)
            implements SyncAction {
        public DeleteDocument {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(resource, "resource");
            Objects.requireNonNull(etag, "etag");
        }
    }

    /** A container made before whose folder is gone: {@code DELETE}, after its members. */
    record DeleteContainer(RelativePath path, ResourceIdentifier resource) implements SyncAction {
        public DeleteContainer {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(resource, "resource");
        }
    }
}
