package com.enrichmeai.cistern.webflux;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.nio.file.Path;

/**
 * Deployment configuration for the HTTP layer.
 *
 * <p>{@code cistern.base-url} is load-bearing rather than cosmetic: in Solid the resource
 * identifier IS the HTTP URI (Solid Protocol §2.1), so it is the base every request path is
 * resolved against to produce a {@code ResourceIdentifier}, and therefore the origin that
 * appears in every {@code ldp:contains} object and every subject a client reads back. It is
 * taken from configuration, not from the request's {@code Host} header, so that identifiers
 * stay stable behind a proxy and cannot be poisoned by a forged {@code Host}.
 *
 * @param baseUrl origin (and optional path prefix) the pod is published under; any trailing
 *                slash is insignificant and stripped
 * @param storage storage backend settings
 */
@ConfigurationProperties(prefix = "cistern")
public record CisternProperties(String baseUrl, Storage storage) {

    private static final String DEFAULT_BASE_URL = "http://localhost:3000";

    public CisternProperties {
        baseUrl = (baseUrl == null || baseUrl.isBlank()) ? DEFAULT_BASE_URL : baseUrl.trim();
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        URI parsed = URI.create(baseUrl);
        if (!parsed.isAbsolute() || parsed.getRawFragment() != null) {
            throw new IllegalArgumentException(
                    "cistern.base-url must be an absolute URI without a fragment: " + baseUrl);
        }
        storage = storage == null ? new Storage(null) : storage;
    }

    /**
     * @param root directory the file backend keeps resources under
     */
    public record Storage(Path root) {

        private static final Path DEFAULT_ROOT = Path.of("./data");

        public Storage {
            root = root == null ? DEFAULT_ROOT : root;
        }
    }
}
