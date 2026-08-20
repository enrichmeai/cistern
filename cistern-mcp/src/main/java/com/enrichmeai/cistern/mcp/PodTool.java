package com.enrichmeai.cistern.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.spec.McpSchema;

/**
 * The tools the front door exposes — the closed set, kebab-case on the wire (ground rule 7).
 * Each constant carries its complete MCP definition: name, title, description, input schema.
 *
 * <p><strong>Deliberately no search tool.</strong> Cistern has no search or indexing by design
 * ({@code docs/INTEGRATION.md} §4): the pod is storage plus authority, and an index built by
 * the front door would be an authority bypass the moment a grant is revoked. Deliberately no
 * MCP <em>resources</em> either in v1: pod browsing is {@code list-container}, which is
 * decided per request by the server; a resource listing cached by the client would outlive
 * revocation.
 */
enum PodTool {

    READ_RESOURCE("read-resource", "Read a pod resource",
            "Read one resource from the pod. Returns its content (text formats inline;"
                    + " binary reported by type and size) and its content type and ETag."
                    + " A 401/403 comes back as a REFUSED result: report it, do not retry.") {
        @Override
        Map<ToolArgument, Map<String, Object>> arguments() {
            return ordered(Map.entry(ToolArgument.URL, Schemas.string(ToolArgument.URL)));
        }

        @Override
        List<ToolArgument> required() {
            return List.of(ToolArgument.URL);
        }
    },

    LIST_CONTAINER("list-container", "List a container",
            "List the members of a container (a URL ending in '/'). Returns the members'"
                    + " URLs. Only what the bound identity may see is reachable; a refusal"
                    + " is a REFUSED result, not an empty container.") {
        @Override
        Map<ToolArgument, Map<String, Object>> arguments() {
            return ordered(Map.entry(ToolArgument.URL, Schemas.string(ToolArgument.URL)));
        }

        @Override
        List<ToolArgument> required() {
            return List.of(ToolArgument.URL);
        }
    },

    WRITE_RESOURCE("write-resource", "Write a pod resource",
            "Create or replace one resource with text content. Honours ETag preconditions:"
                    + " pass if-match to replace only an unchanged resource, or create-only"
                    + " to refuse to overwrite. Intermediate containers are created for you.") {
        @Override
        Map<ToolArgument, Map<String, Object>> arguments() {
            return ordered(
                    Map.entry(ToolArgument.URL, Schemas.string(ToolArgument.URL)),
                    Map.entry(ToolArgument.CONTENT, Schemas.string(ToolArgument.CONTENT)),
                    Map.entry(ToolArgument.CONTENT_TYPE, Schemas.string(ToolArgument.CONTENT_TYPE)),
                    Map.entry(ToolArgument.IF_MATCH, Schemas.string(ToolArgument.IF_MATCH)),
                    Map.entry(ToolArgument.CREATE_ONLY, Schemas.bool(ToolArgument.CREATE_ONLY)));
        }

        @Override
        List<ToolArgument> required() {
            return List.of(ToolArgument.URL, ToolArgument.CONTENT, ToolArgument.CONTENT_TYPE);
        }
    },

    DELETE_RESOURCE("delete-resource", "Delete a pod resource",
            "Delete one resource. A non-empty container cannot be deleted; deleting requires"
                    + " write on the resource and on its parent container.") {
        @Override
        Map<ToolArgument, Map<String, Object>> arguments() {
            return ordered(Map.entry(ToolArgument.URL, Schemas.string(ToolArgument.URL)));
        }

        @Override
        List<ToolArgument> required() {
            return List.of(ToolArgument.URL);
        }
    },

    GRANT("grant", "Grant access",
            "Grant an agent (a WebID, or 'public' for anyone) one or more access modes on a"
                    + " resource or container, by rewriting its .acl over HTTP exactly as the"
                    + " cistern CLI does — the server enforces Control, so this succeeds only"
                    + " for a caller whose bound identity controls the target. The owner's own"
                    + " authorization is always preserved.") {
        @Override
        Map<ToolArgument, Map<String, Object>> arguments() {
            return ordered(
                    Map.entry(ToolArgument.URL, Schemas.string(ToolArgument.URL)),
                    Map.entry(ToolArgument.AGENT, Schemas.string(ToolArgument.AGENT)),
                    Map.entry(ToolArgument.MODES, Schemas.modes(ToolArgument.MODES)));
        }

        @Override
        List<ToolArgument> required() {
            return List.of(ToolArgument.URL, ToolArgument.AGENT, ToolArgument.MODES);
        }
    },

    REVOKE("revoke", "Revoke access",
            "Remove everything an agent (a WebID, or 'public') holds on a resource or"
                    + " container. Takes effect on the agent's next request — decisions are"
                    + " never cached. A revoke that would drop the owner's Control is refused"
                    + " with nothing written.") {
        @Override
        Map<ToolArgument, Map<String, Object>> arguments() {
            return ordered(
                    Map.entry(ToolArgument.URL, Schemas.string(ToolArgument.URL)),
                    Map.entry(ToolArgument.AGENT, Schemas.string(ToolArgument.AGENT)));
        }

        @Override
        List<ToolArgument> required() {
            return List.of(ToolArgument.URL, ToolArgument.AGENT);
        }
    },

    RECEIPTS("receipts", "Read the decision log",
            "The receipts for one resource: every access decision the pod took about it —"
                    + " allow and deny, with the agent, the required mode and the ACL that"
                    + " decided — as JSON Lines. Requires Control on the resource: an agent"
                    + " that may merely read a document may not see who else touched it.") {
        @Override
        Map<ToolArgument, Map<String, Object>> arguments() {
            return ordered(
                    Map.entry(ToolArgument.URL, Schemas.string(ToolArgument.URL)),
                    Map.entry(ToolArgument.FROM, Schemas.string(ToolArgument.FROM)),
                    Map.entry(ToolArgument.TO, Schemas.string(ToolArgument.TO)));
        }

        @Override
        List<ToolArgument> required() {
            return List.of(ToolArgument.URL);
        }
    };

    private final String toolName;
    private final String title;
    private final String description;

    PodTool(String toolName, String title, String description) {
        this.toolName = toolName;
        this.title = title;
        this.description = description;
    }

    /** The name on the wire, kebab-case. */
    String toolName() {
        return toolName;
    }

    /** This tool's arguments, in declaration order. */
    abstract Map<ToolArgument, Map<String, Object>> arguments();

    /** The arguments a call must supply. */
    abstract List<ToolArgument> required();

    /** The complete MCP tool definition. */
    McpSchema.Tool definition() {
        return McpSchema.Tool.builder(toolName, Schemas.inputObject(arguments(), required()))
                .title(title)
                .description(description)
                .build();
    }

    /** The tool named {@code name} on the wire. */
    static PodTool fromToolName(String name) {
        for (PodTool tool : values()) {
            if (tool.toolName.equals(name)) {
                return tool;
            }
        }
        throw new IllegalArgumentException(name);
    }

    @SafeVarargs
    private static Map<ToolArgument, Map<String, Object>> ordered(
            Map.Entry<ToolArgument, Map<String, Object>>... entries) {
        Map<ToolArgument, Map<String, Object>> arguments = new LinkedHashMap<>();
        for (Map.Entry<ToolArgument, Map<String, Object>> entry : entries) {
            arguments.put(entry.getKey(), entry.getValue());
        }
        return arguments;
    }
}
