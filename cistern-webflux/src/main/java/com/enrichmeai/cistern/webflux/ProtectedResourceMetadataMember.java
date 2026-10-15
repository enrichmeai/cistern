package com.enrichmeai.cistern.webflux;

/**
 * The members of the protected resource metadata document this server emits, as RFC 9728 §2
 * names them — a closed set, so the JSON member names are written once here rather than at the
 * site that renders the document and again at the tests that read it.
 */
enum ProtectedResourceMetadataMember {

    /** REQUIRED: "The protected resource's resource identifier". */
    RESOURCE("resource"),

    /**
     * OPTIONAL in RFC 9728; the MCP authorization specification makes it required of an MCP
     * server ("MUST include the {@code authorization_servers} field containing at least one
     * authorization server"). Omitted, rather than invented, when this pod trusts no issuer.
     */
    AUTHORIZATION_SERVERS("authorization_servers"),

    /** RECOMMENDED: "a list of scope values ... used in authorization requests to request access to this protected resource". */
    SCOPES_SUPPORTED("scopes_supported"),

    /** OPTIONAL: "the supported methods of sending an OAuth 2.0 bearer token [RFC6750] to the protected resource". */
    BEARER_METHODS_SUPPORTED("bearer_methods_supported"),

    /** OPTIONAL: "a page containing human-readable information that developers might want or need to know when using the protected resource". */
    RESOURCE_DOCUMENTATION("resource_documentation");

    private final String jsonName;

    ProtectedResourceMetadataMember(String jsonName) {
        this.jsonName = jsonName;
    }

    /** The member name as it appears in the document. */
    String jsonName() {
        return jsonName;
    }
}
