package com.enrichmeai.cistern.mcp;

import java.net.URI;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The standalone stdio bridge: the process a desktop MCP client (Claude Desktop) launches.
 * It serves MCP on its stdio and reaches the pod <em>only</em> by real HTTP requests to a
 * running Cistern server, carrying the one credential from its environment — so every WAC
 * decision, refusal and receipt happens in the server exactly as for any other client
 * (ARCHITECTURE decision 6), and revoking the credential's grants mid-session takes effect on
 * the very next tool call.
 *
 * <p>Configuration is the launching connector's environment (T6.2's static binding):
 *
 * <dl>
 *   <dt>{@link #ENV_BASE_URL} (or {@link #ENV_BASE_URL_RELAXED})</dt>
 *   <dd>the running server's base URL — the same value as the server's
 *       {@code cistern.base-url}, e.g. {@code http://127.0.0.1:3737}</dd>
 *   <dt>{@link #ENV_CREDENTIAL}</dt>
 *   <dd>the bearer credential the connection is bound to — the owner's local token or a
 *       service principal's secret, resolved by the server's own resolver chain</dd>
 * </dl>
 *
 * Logging goes to stderr (slf4j-simple); stdout carries only MCP frames
 * ({@link McpStdioSession}).
 */
public final class McpBridge {

    /** The pod's base URL. */
    public static final String ENV_BASE_URL = "CISTERN_MCP_BASE_URL";

    /**
     * The same setting in Spring's relaxed-binding spelling ({@code cistern.mcp.base-url} →
     * {@code CISTERN_MCP_BASEURL}), accepted so one environment block works for both the
     * bridge and the app-embedded shape.
     */
    public static final String ENV_BASE_URL_RELAXED = "CISTERN_MCP_BASEURL";

    /** The bound credential. Identical in both shapes. */
    public static final String ENV_CREDENTIAL = "CISTERN_MCP_CREDENTIAL";

    /** Exit status when the environment does not configure the bridge. */
    static final int EXIT_MISCONFIGURED = 2;

    private static final String ENV_NAME_SEPARATOR = " / ";

    private static final Logger log = LoggerFactory.getLogger(McpBridge.class);

    private McpBridge() {
        // entry point only
    }

    public static void main(String[] args) {
        Binding binding;
        try {
            binding = Binding.fromEnvironment(System.getenv());
        } catch (IllegalStateException misconfigured) {
            log.error(misconfigured.getMessage());
            System.exit(EXIT_MISCONFIGURED);
            return;
        }
        try (McpStdioSession session = McpStdioSession.open(
                binding.credential(), PodAddress.of(binding.baseUrl()))) {
            log.info(McpMessage.BRIDGE_STARTED.format(binding.baseUrl()));
            session.awaitInputClosed();
            log.info(McpMessage.BRIDGE_STOPPED.format());
        }
        // Deterministic exit: the pipe is closed and the session is down; whatever worker
        // threads a JDK HttpClient still parks must not keep a dead bridge alive.
        System.exit(0);
    }

    /** The two facts the environment must supply, validated together so both gaps are named. */
    record Binding(URI baseUrl, BearerCredential credential) {

        static Binding fromEnvironment(Map<String, String> env) {
            Optional<String> base = firstNonBlank(env, ENV_BASE_URL, ENV_BASE_URL_RELAXED);
            Optional<String> credential = firstNonBlank(env, ENV_CREDENTIAL);
            StringJoiner missing = new StringJoiner(ENV_NAME_SEPARATOR);
            if (base.isEmpty()) {
                missing.add(ENV_BASE_URL);
            }
            if (credential.isEmpty()) {
                missing.add(ENV_CREDENTIAL);
            }
            if (missing.length() > 0) {
                throw new IllegalStateException(McpMessage.BRIDGE_ENV_MISSING.format(missing));
            }
            return new Binding(URI.create(base.get().trim()),
                    new BearerCredential(credential.get()));
        }

        private static Optional<String> firstNonBlank(Map<String, String> env, String... names) {
            for (String name : names) {
                String value = env.get(name);
                if (value != null && !value.isBlank()) {
                    return Optional.of(value);
                }
            }
            return Optional.empty();
        }
    }
}
