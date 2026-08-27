package com.enrichmeai.cistern.mcp;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import io.modelcontextprotocol.server.McpAsyncServer;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpServerTransportProvider;
import io.modelcontextprotocol.spec.McpStreamableServerTransportProvider;

/**
 * Assembles the MCP server: the seven {@link PodTool}s wired to {@link PodToolHandlers}, over
 * whatever transport the caller supplies — the standalone bridge's stdio ({@link McpBridge})
 * or the app-embedded stdio ({@code CisternMcpConfiguration}). Both shapes serve exactly this;
 * there is no second tool list.
 *
 * <p>Identity binding (T6.2) is visible in the signature: one {@link BearerCredential}, one
 * {@link PodAddress}, fixed for the life of the server. An MCP connection <em>is</em> one
 * principal; serving a second principal is running a second front door.
 */
public final class McpFrontDoor {

    /** The server's MCP name, as clients display it. */
    public static final String SERVER_NAME = "cistern";

    /** Fallback when the jar manifest carries no Implementation-Version (IDE, tests). */
    private static final String DEVELOPMENT_VERSION = "dev";

    /**
     * What a connected model is told about this server. Definition text, so it lives with the
     * definitions rather than in the message catalogue, as {@link PodTool}'s descriptions do.
     */
    private static final String INSTRUCTIONS = """
            This connection operates one Solid pod through Cistern's MCP front door. Every \
            tool call becomes a real HTTP request to the pod, made as the one identity this \
            connection is bound to by its configuration; the pod's Web Access Control engine \
            decides each request afresh and records a receipt for it. A result whose text \
            begins with REFUSED is an authorization decision by the pod owner's policy: \
            report it to the user as a refusal — the resource and required mode are named — \
            and do not retry or attempt other credentials. Grants can change mid-session: \
            a resource that was readable a moment ago may be refused now, and that is the \
            system working, not an error. There is no search tool, deliberately: the pod is \
            storage plus authority, not an index.""";

    private McpFrontDoor() {
        // assembly only
    }

    /** The front door over a stdio {@code transport}, bound to {@code credential}. */
    public static McpAsyncServer serve(McpServerTransportProvider transport,
                                       BearerCredential credential, PodAddress address) {
        Objects.requireNonNull(transport, "transport");
        return describe(McpServer.async(transport), credential, address);
    }

    /**
     * The front door over a Streamable HTTP {@code transport} (T6.7).
     *
     * <p>A second overload rather than a second assembly: the SDK's two provider interfaces do
     * not share a supertype the builder accepts, but the pod is the same pod either way. The
     * tools, their descriptions and the refusal instruction below are defined once, so a client
     * arriving over HTTP cannot be offered a different surface from one arriving over stdio —
     * which is the property that lets the same WAC decision mean the same thing on both.
     */
    public static McpAsyncServer serve(McpStreamableServerTransportProvider transport,
                                       BearerCredential credential, PodAddress address) {
        Objects.requireNonNull(transport, "transport");
        return describe(McpServer.async(transport), credential, address);
    }

    /** Everything the two transports share: the tools, the capabilities, the instructions. */
    private static McpAsyncServer describe(McpServer.AsyncSpecification<?> specification,
                                           BearerCredential credential, PodAddress address) {
        PodToolHandlers handlers = new PodToolHandlers(PodHttp.connect(credential), address);
        List<McpServerFeatures.AsyncToolSpecification> tools = new ArrayList<>();
        for (PodTool tool : PodTool.values()) {
            tools.add(McpServerFeatures.AsyncToolSpecification.builder()
                    .tool(tool.definition())
                    .callHandler((exchange, request) -> handlers.handle(tool, request))
                    .build());
        }
        return specification
                .serverInfo(SERVER_NAME, version())
                .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
                .instructions(INSTRUCTIONS)
                .tools(tools)
                .build();
    }

    static String version() {
        String implementation = McpFrontDoor.class.getPackage().getImplementationVersion();
        return implementation != null ? implementation : DEVELOPMENT_VERSION;
    }
}
