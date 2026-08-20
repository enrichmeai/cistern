package com.enrichmeai.cistern.mcp;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.modelcontextprotocol.spec.McpSchema;

/**
 * Typed access to a tool call's arguments. Every mistake is {@link PodProblem.BadArgument},
 * so it renders through the one translator like everything else — the model gets told what
 * was wrong with its call, in words, rather than a stack trace.
 */
record ToolArguments(Map<String, Object> raw) {

    static ToolArguments of(McpSchema.CallToolRequest request) {
        return new ToolArguments(request.arguments() == null ? Map.of() : request.arguments());
    }

    /** The argument, which the schema marks required. */
    String required(ToolArgument argument) {
        return optional(argument).orElseThrow(() -> new PodProblem.BadArgument(
                McpMessage.ARGUMENT_MISSING.format(argument.jsonName())));
    }

    /** The argument if present and non-blank. */
    Optional<String> optional(ToolArgument argument) {
        Object value = raw.get(argument.jsonName());
        if (value == null) {
            return Optional.empty();
        }
        if (!(value instanceof String text)) {
            throw new PodProblem.BadArgument(McpMessage.ARGUMENT_INVALID.format(
                    argument.jsonName(), value, String.class.getSimpleName()));
        }
        return text.isBlank() ? Optional.empty() : Optional.of(text.trim());
    }

    /** A boolean argument; absent means {@code false}. */
    boolean flag(ToolArgument argument) {
        Object value = raw.get(argument.jsonName());
        if (value == null) {
            return false;
        }
        if (!(value instanceof Boolean bool)) {
            throw new PodProblem.BadArgument(McpMessage.ARGUMENT_INVALID.format(
                    argument.jsonName(), value, Boolean.class.getSimpleName()));
        }
        return bool;
    }

    /** A required, non-empty array of strings. */
    List<String> requiredList(ToolArgument argument) {
        Object value = raw.get(argument.jsonName());
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            throw new PodProblem.BadArgument(
                    McpMessage.ARGUMENT_MISSING.format(argument.jsonName()));
        }
        return list.stream().map(item -> {
            if (!(item instanceof String text) || text.isBlank()) {
                throw new PodProblem.BadArgument(McpMessage.ARGUMENT_INVALID.format(
                        argument.jsonName(), item, String.class.getSimpleName()));
            }
            return text.trim();
        }).toList();
    }
}
