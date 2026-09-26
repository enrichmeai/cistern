package com.enrichmeai.cistern.webflux;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

/**
 * The OAuth 2.0 protected resource metadata document (RFC 9728, T6.4): what it says, where it
 * is served, and the endpoint declaration that reserves that path.
 *
 * <h2>Why this document exists</h2>
 * The MCP authorization specification: "MCP servers MUST implement OAuth 2.0 Protected
 * Resource Metadata (RFC9728). MCP clients MUST use OAuth 2.0 Protected Resource Metadata for
 * authorization server discovery." It is how a remote client that was just refused with a 401
 * learns which authorization server to register with and obtain a token from — and the
 * {@code resource_metadata} parameter on that 401's {@code WWW-Authenticate}
 * ({@link AuthenticationChallenge}) is how it finds this document.
 *
 * <h2>What it asserts, and nothing more</h2>
 * The five members of {@link ProtectedResourceMetadataMember}, each from typed configuration
 * or a closed set: the resource identifier, the authorization server (omitted when none is
 * configured — RFC 9728 makes the member optional and an invented issuer would send a client
 * to a server that has never heard of this pod), the one scope ({@link OAuthScope}), the one
 * bearer method ({@link BearerMethod}) and the documentation URL. Cistern implements no
 * further RFC 9728 members — signed metadata, DPoP-bound-token requirements, JWKS — and a
 * document that listed them would be advertising capabilities the server does not have.
 *
 * <h2>Public, by declaration</h2>
 * The path is reserved as a {@link ServerEndpoint} with {@link EndpointAccess#PUBLIC}: the
 * document is what a client reads <em>before</em> it can authenticate, so it cannot sit behind
 * Web Access Control, and a {@code PUT} to it must be refused rather than become a pod resource
 * shadowed on read. Both follow from the one declaration; see {@link ServerEndpoint}.
 *
 * <p>Computed once, from configuration: like {@link StorageDescription}, a discovery document
 * must not depend on how a request happened to arrive.
 */
@Component
public class ProtectedResourceMetadata {

    /**
     * The methods this resource serves — Solid Protocol §5.2's mandatory trio, exactly as the
     * storage description: a projection of configuration has no state a write could change.
     * Route predicate, {@code Allow} value and endpoint declaration are all this one list.
     */
    static final List<HttpMethod> METHODS = List.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.OPTIONS);

    private final OAuthProtectedResource resource;
    private final Document document;
    private final ServerEndpoint endpoint;

    public ProtectedResourceMetadata(CisternProperties properties) {
        this.resource = properties.protectedResource();
        this.document = new Document(
                resource.resource(),
                resource.authorizationServer().map(List::of).orElse(List.of()),
                List.of(OAuthScope.values()),
                List.of(BearerMethod.values()),
                resource.documentation());
        this.endpoint = new ServerEndpoint(resource.metadataPath(), EndpointAccess.PUBLIC, METHODS);
    }

    /** The document's absolute URL — the value the 401 challenge's {@code resource_metadata} carries. */
    public URI url() {
        return resource.metadataUrl();
    }

    /** The reservation of the document's path: public, GET/HEAD/OPTIONS. */
    public ServerEndpoint endpoint() {
        return endpoint;
    }

    /** The protected resource the document describes. */
    OAuthProtectedResource resource() {
        return resource;
    }

    /** The document, typed. */
    Document document() {
        return document;
    }

    /**
     * The document as RFC 9728 §2 defines it, member by member. The JSON projection
     * ({@link #toJson()}) is derived from this record, so what the server asserts is a typed
     * value and the wire format is one rendering of it.
     *
     * @param resource               the resource identifier
     * @param authorizationServers   the authorization servers, possibly none
     * @param scopesSupported        the scopes a client requests for this resource
     * @param bearerMethodsSupported how a token may be presented
     * @param resourceDocumentation  where the resource is documented
     */
    record Document(
            URI resource,
            List<URI> authorizationServers,
            List<OAuthScope> scopesSupported,
            List<BearerMethod> bearerMethodsSupported,
            URI resourceDocumentation) {

        Document {
            Objects.requireNonNull(resource, "resource");
            authorizationServers = List.copyOf(authorizationServers);
            scopesSupported = List.copyOf(scopesSupported);
            bearerMethodsSupported = List.copyOf(bearerMethodsSupported);
            Objects.requireNonNull(resourceDocumentation, "resourceDocumentation");
        }

        /**
         * The JSON object, members in RFC 9728 §2's order, keyed by
         * {@link ProtectedResourceMetadataMember}. {@code authorization_servers} is present only
         * when there is one to name.
         */
        Map<String, Object> toJson() {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put(ProtectedResourceMetadataMember.RESOURCE.jsonName(), resource.toString());
            if (!authorizationServers.isEmpty()) {
                json.put(ProtectedResourceMetadataMember.AUTHORIZATION_SERVERS.jsonName(),
                        authorizationServers.stream().map(URI::toString).toList());
            }
            json.put(ProtectedResourceMetadataMember.SCOPES_SUPPORTED.jsonName(),
                    scopesSupported.stream().map(OAuthScope::token).toList());
            json.put(ProtectedResourceMetadataMember.BEARER_METHODS_SUPPORTED.jsonName(),
                    bearerMethodsSupported.stream().map(BearerMethod::token).toList());
            json.put(ProtectedResourceMetadataMember.RESOURCE_DOCUMENTATION.jsonName(),
                    resourceDocumentation.toString());
            return json;
        }
    }
}
