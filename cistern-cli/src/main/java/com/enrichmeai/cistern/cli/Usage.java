package com.enrichmeai.cistern.cli;

/**
 * Usage text for the {@code cistern} command and its options, spelled once. picocli takes
 * descriptions as annotation values, which must be compile-time constants, so this is a
 * constants class rather than an enum; nothing here is ever printed at a throw or log site
 * (that is {@link CliMessage}'s job).
 */
final class Usage {

    static final String COMMAND_NAME = "cistern";
    static final String COMMAND_DESCRIPTION =
            "Provisions pods, mirrors folders into them and authors Web Access Control grants"
                    + " against a running Cistern server, with your own credential. The server"
                    + " enforces acl:Write and acl:Control; this tool only writes the files.";

    static final String POD_NAME = "pod";
    static final String POD_DESCRIPTION = "Pods: containers with an owner of their own.";

    static final String POD_CREATE_NAME = "create";
    static final String POD_CREATE_DESCRIPTION =
            "Make <root> a pod owned by <webid>: creates the container if it is not there and"
                    + " writes <root>.acl granting the owner read, write, append and control on it"
                    + " and everything inside it — nothing to anyone else, you included. Never"
                    + " overwrites an ACL that already exists: run again by the pod's owner it"
                    + " reports the pod and writes nothing; anyone else no longer holds Control"
                    + " there and is refused.";

    static final String ROOT_OPTION = "--root";
    static final String ROOT_DESCRIPTION =
            "The pod's root, as a container path on the server (ending in '/'), e.g. /firms/acme/.";
    static final String OWNER_OPTION = "--owner";
    static final String OWNER_DESCRIPTION = "The owner's WebID: an absolute URI.";
    static final String WEBID_PARAM = "<webid>";

    static final String SYNC_NAME = "sync";
    static final String DRY_RUN_OPTION = "--dry-run";
    static final String DELETE_OPTION = "--delete";
    static final String SYNC_DESCRIPTION =
            "Mirror a folder into a container on the pod: sub-folders become containers, new"
                    + " files are created, changed files replaced, unchanged files not sent, and"
                    + " — only with " + DELETE_OPTION + " — resources this folder once sent and no"
                    + " longer holds are removed. Every write is conditional: a copy edited on"
                    + " the pod since it was sent is never overwritten (exit 3).%nWhat was sent is"
                    + " remembered in the folder's " + SyncStateFile.NAME + " file, so a second run"
                    + " with nothing changed sends nothing.";
    static final String LOCAL_DIR_PARAM = "<local-dir>";
    static final String LOCAL_DIR_DESCRIPTION =
            "The folder to mirror. Symbolic links and files named *.acl are skipped, with a"
                    + " message; the state file is never sent.";
    static final String SYNC_TARGET_DESCRIPTION =
            "The container on the server to mirror into, ending in '/', e.g. /firms/acme/docs/."
                    + " Each file lands at that path plus its relative path, media type from its"
                    + " extension (.md, .pdf, .csv, .ttl, …; anything else application/octet-stream).";
    static final String DRY_RUN_DESCRIPTION =
            "Print what would be sent and stop: nothing is sent, nothing is remembered.";
    static final String DELETE_DESCRIPTION =
            "Also remove from the pod what this folder once sent and no longer holds. Off by"
                    + " default. A document is removed only if it is still the one that was sent"
                    + " (If-Match); a container only once it is empty.";

    static final String GRANT_NAME = "grant";
    static final String GRANT_DESCRIPTION =
            "Let a WebID, or everyone, do something to a resource — optionally only through a named"
                    + " client: writes <path>.acl (re-stating whoever holds Control there today, so"
                    + " nobody is locked out) and prints what the ACL now says. A container grant"
                    + " covers everything inside it.";

    static final String REVOKE_NAME = "revoke";
    static final String REVOKE_DESCRIPTION =
            "Take back everything a WebID, or everyone, was granted on a resource. Refuses to"
                    + " remove an authorization that grants Control.";

    static final String GRANTEE_PARAM = "<webid|public>";
    static final String GRANTEE_DESCRIPTION =
            "The grantee: an absolute WebID URI, or the word '" + GranteeConverter.PUBLIC_KEYWORD
                    + "' for anyone (acl:agentClass foaf:Agent).";

    static final String PATH_PARAM = "<path>";
    static final String PATH_DESCRIPTION =
            "The resource, as a path on the server: a container ends in '/', a document does not.";

    static final String READ_OPTION = "--read";
    static final String READ_DESCRIPTION = "Grant acl:Read.";
    static final String WRITE_OPTION = "--write";
    static final String WRITE_DESCRIPTION = "Grant acl:Write (which carries acl:Append).";
    static final String APPEND_OPTION = "--append";
    static final String APPEND_DESCRIPTION = "Grant acl:Append.";
    static final String CONTROL_OPTION = "--control";
    static final String CONTROL_DESCRIPTION = "Grant acl:Control (read and write the ACL; implies nothing else).";
    static final String MODES_HEADING = "At least one mode:%n";

    static final String CLIENT_OPTION = "--client";
    static final String CLIENT_PARAM = "<uri>";
    static final String CLIENT_DESCRIPTION =
            "Only through this client: an OAuth client identifier as an absolute URI — the client_id"
                    + " (or azp) the access token carries. Repeatable; each names an alternative."
                    + " The grant still names <webid|public>, and the client only narrows it"
                    + " (cistern:client): a server with cistern.wac.delegation.enabled unset, or"
                    + " one that does not read the term, applies the grant to the WebID as it stands.";

    static final String BASE_OPTION = "--base";
    static final String BASE_DESCRIPTION = "The server's base URL (default: ${DEFAULT-VALUE}).";
    static final String TOKEN_OPTION = "--token";
    static final String TOKEN_DESCRIPTION =
            "Your bearer credential; defaults to the " + ServerOptions.TOKEN_ENV + " environment variable.";

    static final String EXIT_CODES_HEADING = "Exit codes:%n";
    static final String EXIT_OK = ExitCode.Values.OK + ":ok";
    static final String EXIT_FAILURE =
            ExitCode.Values.FAILURE + ":failure (bad arguments, network, unexpected response, unusable folder)";
    static final String EXIT_REFUSED =
            ExitCode.Values.REFUSED + ":refused (401/403 from the server, or a revoke that would drop Control)";
    static final String EXIT_CONFLICT =
            ExitCode.Values.CONFLICT + ":conflict (the resource changed underneath — an ACL while being"
                    + " edited, a pod copy since it was synced; nothing written)";

    private Usage() {
        // constants only
    }
}
