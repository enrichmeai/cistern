package com.enrichmeai.cistern.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.util.ToolNameValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The tool definitions hold together: names, schemas, required arguments. */
class PodToolDefinitionTest {

    private static final String PROPERTIES = "properties";
    private static final String REQUIRED = "required";

    @Test
    @DisplayName("every tool name passes the MCP specification's validator, strictly")
    void namesAreValid() {
        for (PodTool tool : PodTool.values()) {
            ToolNameValidator.validate(tool.toolName(), true);
        }
    }

    @Test
    @DisplayName("there is deliberately no search tool")
    void noSearchTool() {
        for (PodTool tool : PodTool.values()) {
            assertTrue(!tool.toolName().contains("search"), tool.toolName());
        }
    }

    @Test
    @DisplayName("every required argument is a declared property, and every definition builds")
    void schemasAreCoherent() {
        for (PodTool tool : PodTool.values()) {
            McpSchema.Tool definition = tool.definition();
            assertEquals(tool.toolName(), definition.name());
            assertNotNull(definition.description());
            @SuppressWarnings("unchecked")
            Map<String, Object> properties =
                    (Map<String, Object>) definition.inputSchema().get(PROPERTIES);
            @SuppressWarnings("unchecked")
            List<String> required = (List<String>) definition.inputSchema().get(REQUIRED);
            assertTrue(properties.keySet().containsAll(Set.copyOf(required)),
                    tool.toolName() + ": " + required + " ⊄ " + properties.keySet());
            assertTrue(properties.containsKey(ToolArgument.URL.jsonName()),
                    tool.toolName() + " takes a url");
        }
    }
}
