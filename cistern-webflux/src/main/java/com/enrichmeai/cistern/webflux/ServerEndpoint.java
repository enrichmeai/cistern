package com.enrichmeai.cistern.webflux;

import java.util.List;
import java.util.Objects;

import org.springframework.http.HttpMethod;

/**
 * A path this server reserves for something other than a stored resource (T6.4): the OAuth
 * protected resource metadata, the MCP door. Declaring one is what keeps a reserved path from
 * behaving like pod storage in the two ways it otherwise would.
 *
 * <p>Every other route matches {@code /**} — a pod's URI space is "every path is a resource" —
 * so without a declaration a {@code PUT} to a reserved path would create a pod resource that
 * the reserved route then shadows on read, and {@code AuthorizationFilter} would judge every
 * request to it by Web Access Control on a resource that does not exist. A declared endpoint
 * gets a method-not-allowed route ahead of the catch-alls for any method outside
 * {@link #methods()}, and the filter applies {@link #access()} instead of WAC.
 *
 * <p>Modules declare endpoints as beans: cistern-webflux for the metadata document, cistern-mcp
 * for {@code /mcp}. The filter and the routes learn of them through {@code ServerEndpoints}, so
 * a module that adds a door adds a declaration, never a special case in the filter.
 *
 * @param path    the request path, exact — endpoints are not patterns
 * @param access  who may reach it
 * @param methods the methods it serves, in the order {@code Allow} lists them
 */
public record ServerEndpoint(String path, EndpointAccess access, List<HttpMethod> methods) {

    private static final String PATH_PREFIX = "/";
    private static final String QUERY_MARKER = "?";

    public ServerEndpoint {
        Objects.requireNonNull(access, "access");
        Objects.requireNonNull(methods, "methods");
        if (path == null || !path.startsWith(PATH_PREFIX) || path.contains(QUERY_MARKER)) {
            throw new IllegalArgumentException(WebfluxMessage.ENDPOINT_PATH_INVALID.format(path));
        }
        methods = List.copyOf(methods);
        if (methods.isEmpty()) {
            throw new IllegalArgumentException(WebfluxMessage.ENDPOINT_METHODS_REQUIRED.format(path));
        }
    }

    /** Whether this endpoint serves {@code method}. */
    public boolean serves(HttpMethod method) {
        return methods.contains(method);
    }
}
