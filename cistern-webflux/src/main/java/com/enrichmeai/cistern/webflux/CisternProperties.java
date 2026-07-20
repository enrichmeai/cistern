package com.enrichmeai.cistern.webflux;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

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
 * @param cors    cross-origin sharing settings (T2.8); wide open unless narrowed
 */
@ConfigurationProperties(prefix = "cistern")
public record CisternProperties(String baseUrl, Storage storage, Cors cors) {

    private static final String DEFAULT_BASE_URL = "http://localhost:3000";

    /** Insignificant on the base URL: every request path already supplies its own leading one. */
    private static final String TRAILING_SEPARATOR = "/";

    public CisternProperties {
        baseUrl = (baseUrl == null || baseUrl.isBlank()) ? DEFAULT_BASE_URL : baseUrl.trim();
        while (baseUrl.endsWith(TRAILING_SEPARATOR)) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - TRAILING_SEPARATOR.length());
        }
        URI parsed = URI.create(baseUrl);
        if (!parsed.isAbsolute() || parsed.getRawFragment() != null) {
            throw new IllegalArgumentException(WebfluxMessage.BASE_URL_INVALID.format(baseUrl));
        }
        storage = storage == null ? new Storage(null) : storage;
        cors = cors == null ? new Cors(null, null) : cors;
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

    /**
     * Cross-origin sharing (T2.8). The default is deliberately permissive: a Solid app is
     * cross-origin <em>by nature</em> — it is served from its own origin and reads the user's
     * pod on another — so a pod that only answered same-origin requests would be unusable by
     * the client ecosystem it exists for. Solid Protocol §8.1 states the requirement directly:
     * "A server MUST implement the CORS protocol such that, to the extent possible, the browser
     * allows Solid apps to send any request and combination of request headers to the server."
     *
     * <p>Permissive is nonetheless the <em>default</em>, not a hard-coding: a deployer running a
     * pod for one known application can narrow {@code cistern.cors.allowed-origins} to it.
     *
     * <p>There is no credentials switch, and that is deliberate rather than an omission.
     * Allowing credentials would tell the browser to attach cookies and HTTP-auth state to
     * cross-origin pod requests — ambient authority that any page on the web could then spend
     * on the user's behalf. Cistern has no such state to attach: Solid-OIDC authenticates with
     * an explicit {@code Authorization} header plus a {@code DPoP} proof (both listed in
     * {@link AllowedRequestHeader}), which a script must set deliberately on each request and
     * which the browser never adds by itself. So credentials mode would grant no capability the
     * protocol uses while opening a hole the protocol does not need. See
     * {@code CisternWebFluxConfiguration#cisternCorsWebFilter} for the Fetch-standard
     * consequence this choice also avoids.
     *
     * @param allowedOrigins origins permitted to read pod resources from a browser;
     *                       {@code "*"} (the default) means any. Entries are treated as
     *                       patterns and the matching origin is echoed back — never the literal
     *                       {@code *} — because §8.1 requires the response to name the request's
     *                       own origin.
     * @param maxAge         how long a browser may cache a preflight result; trades preflight
     *                       chatter against how quickly a configuration change takes effect
     */
    public record Cors(List<String> allowedOrigins, Duration maxAge) {

        /** Any origin: what a public pod serving the open Solid app ecosystem needs. */
        static final String ANY_ORIGIN = "*";

        private static final List<String> DEFAULT_ALLOWED_ORIGINS = List.of(ANY_ORIGIN);

        /**
         * Spring's own default is 30 minutes. An hour, because Cistern's CORS answer is
         * server-wide and static — it does not vary per resource — so re-asking sooner buys a
         * browser nothing but a round trip before every write.
         */
        private static final Duration DEFAULT_MAX_AGE = Duration.ofHours(1);

        public Cors {
            allowedOrigins = (allowedOrigins == null || allowedOrigins.isEmpty())
                    ? DEFAULT_ALLOWED_ORIGINS
                    : List.copyOf(allowedOrigins);
            maxAge = maxAge == null ? DEFAULT_MAX_AGE : maxAge;
        }
    }
}
