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
            "Provisions pods and authors Web Access Control grants against a running Cistern"
                    + " server, with your own credential. The server enforces acl:Control; this"
                    + " tool only writes the files.";

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

    static final String GRANT_NAME = "grant";
    static final String GRANT_DESCRIPTION =
            "Let a WebID, or everyone, do something to a resource: writes <path>.acl (re-stating"
                    + " whoever holds Control there today, so nobody is locked out) and prints what"
                    + " the ACL now says. A container grant covers everything inside it.";

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

    static final String BASE_OPTION = "--base";
    static final String BASE_DESCRIPTION = "The server's base URL (default: ${DEFAULT-VALUE}).";
    static final String TOKEN_OPTION = "--token";
    static final String TOKEN_DESCRIPTION =
            "Your bearer credential; defaults to the " + ServerOptions.TOKEN_ENV + " environment variable.";

    static final String EXIT_CODES_HEADING = "Exit codes:%n";
    static final String EXIT_OK = ExitCode.Values.OK + ":ok";
    static final String EXIT_FAILURE =
            ExitCode.Values.FAILURE + ":failure (bad arguments, network, unexpected response)";
    static final String EXIT_REFUSED =
            ExitCode.Values.REFUSED + ":refused (401/403 from the server, or a revoke that would drop Control)";
    static final String EXIT_CONFLICT =
            ExitCode.Values.CONFLICT + ":conflict (the ACL changed while being edited; nothing written)";

    private Usage() {
        // constants only
    }
}
