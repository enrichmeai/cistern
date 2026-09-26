package com.enrichmeai.cistern.webflux;

import java.net.URI;
import java.util.Objects;
import java.util.Optional;

/**
 * The pod as an OAuth 2.0 protected resource (RFC 9728, T6.4): its resource identifier, the
 * authorization server a client obtains tokens from, and where the resource is documented —
 * the three configured facts the protected resource metadata document is made of.
 *
 * <h2>The resource identifier</h2>
 * RFC 9728 §1.2 defines it as "a URL that uses the https scheme and has no fragment component"
 * which, "as specified in Section 2 of [RFC8707], ... also SHOULD NOT include a query
 * component". It is the value an authorization server turns into the access token's
 * {@code aud} when a client sends it as the RFC 8707 {@code resource} parameter, and the value
 * {@code cistern-auth} accepts as an audience — so it is compared verbatim, and a fragment or
 * query would make two spellings of one resource. Both are refused at bind time. The scheme is
 * <em>not</em> enforced: a loopback development pod (ADR 0001) is {@code http}, and a
 * resource identifier that lied about its scheme would fail the client's own comparison
 * against the URL it connected to.
 *
 * <p>The default is {@code cistern.base-url}: the pod as a whole is the resource, and the MCP
 * endpoint is one door into it. A token minted for the pod's origin therefore serves the plain
 * HTTP surface and the MCP door alike, and the MCP authorization specification's client-side
 * check — the metadata's {@code resource} must equal the server URL or its origin — is
 * satisfied for any endpoint under it.
 *
 * <h2>Where the metadata lives</h2>
 * RFC 9728 §3: the document is "at a URL formed by inserting a well-known URI string into the
 * protected resource's resource identifier between the host component and the path and/or
 * query components, if any", and "any terminating slash (/) following the host component MUST
 * be removed before inserting {@code /.well-known/} and the well-known URI path suffix". So
 * {@code https://pod.example} publishes at {@code https://pod.example/.well-known/oauth-protected-resource}
 * and {@code https://pod.example/firms/acme} at
 * {@code https://pod.example/.well-known/oauth-protected-resource/firms/acme}. Both are
 * computed here, once, from configuration — never from a request's {@code Host}, for the
 * reason {@link CisternProperties#baseUrl()} gives.
 *
 * @param resource            the resource identifier (RFC 9728 §1.2), verbatim
 * @param authorizationServer the authorization server the metadata names; empty when this
 *                            pod trusts no issuer, in which case the document omits
 *                            {@code authorization_servers} rather than inventing one
 * @param documentation       {@code resource_documentation}: where a developer reads how to
 *                            use this resource
 */
public record OAuthProtectedResource(URI resource, Optional<URI> authorizationServer, URI documentation) {

    /** RFC 9728 §3's well-known URI path suffix, registered with IANA by that RFC. */
    public static final String WELL_KNOWN_PATH = "/.well-known/oauth-protected-resource";

    /** Where the resource is documented unless {@code cistern.auth.resource-documentation} says otherwise. */
    static final URI DEFAULT_DOCUMENTATION =
            URI.create("https://github.com/enrichmeai/cistern/blob/main/docs/INTEGRATION.md");

    private static final String PATH_SEPARATOR = "/";
    private static final String SCHEME_SEPARATOR = "://";

    public OAuthProtectedResource {
        Objects.requireNonNull(resource, "resource");
        Objects.requireNonNull(authorizationServer, "authorizationServer");
        Objects.requireNonNull(documentation, "documentation");
        if (!resource.isAbsolute() || resource.getRawAuthority() == null
                || resource.getRawFragment() != null || resource.getRawQuery() != null) {
            throw new IllegalArgumentException(WebfluxMessage.RESOURCE_IDENTIFIER_INVALID.format(resource));
        }
        authorizationServer.ifPresent(server -> {
            if (!server.isAbsolute()) {
                throw new IllegalArgumentException(WebfluxMessage.AUTHORIZATION_SERVER_INVALID.format(server));
            }
        });
        if (!documentation.isAbsolute()) {
            throw new IllegalArgumentException(WebfluxMessage.RESOURCE_DOCUMENTATION_INVALID.format(documentation));
        }
    }

    /**
     * The protected resource {@code cistern.auth.*} describes, with the defaults applied: the
     * identifier is {@code cistern.base-url}, the authorization server is the OIDC issuer, the
     * documentation is this project's integration guide.
     */
    static OAuthProtectedResource of(String baseUrl, CisternProperties.Auth auth) {
        URI resource = auth.resourceIdentifier() != null ? auth.resourceIdentifier() : URI.create(baseUrl);
        Optional<URI> server = Optional.ofNullable(auth.authorizationServer())
                .or(() -> Optional.ofNullable(auth.oidc().issuer()));
        URI documentation = auth.resourceDocumentation() != null
                ? auth.resourceDocumentation() : DEFAULT_DOCUMENTATION;
        return new OAuthProtectedResource(resource, server, documentation);
    }

    /** The metadata document's URL, formed as RFC 9728 §3 prescribes (see the class comment). */
    public URI metadataUrl() {
        return URI.create(resource.getScheme() + SCHEME_SEPARATOR + resource.getRawAuthority() + metadataPath());
    }

    /**
     * The metadata document's path, server-relative: the well-known suffix, followed by the
     * resource identifier's own path with its terminating slash removed (RFC 9728 §3).
     */
    public String metadataPath() {
        String path = resource.getRawPath() == null ? "" : resource.getRawPath();
        while (path.endsWith(PATH_SEPARATOR)) {
            path = path.substring(0, path.length() - PATH_SEPARATOR.length());
        }
        return WELL_KNOWN_PATH + path;
    }
}
