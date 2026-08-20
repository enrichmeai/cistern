package com.enrichmeai.cistern.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.enrichmeai.cistern.wac.AccessMode;

/**
 * The JSON Schema fragments the tool definitions are built from, with the keywords as named
 * constants rather than string literals at each definition site (ground rule 7). Deliberately
 * tiny: only what the seven tools' input schemas need.
 */
final class Schemas {

    private static final String TYPE = "type";
    private static final String OBJECT = "object";
    private static final String STRING = "string";
    private static final String BOOLEAN = "boolean";
    private static final String ARRAY = "array";
    private static final String ITEMS = "items";
    private static final String ENUM = "enum";
    private static final String PROPERTIES = "properties";
    private static final String REQUIRED = "required";
    private static final String DESCRIPTION = "description";
    private static final String MIN_ITEMS = "minItems";
    private static final String ADDITIONAL_PROPERTIES = "additionalProperties";

    private Schemas() {
        // constants and builders only
    }

    /** An object schema over {@code arguments}, requiring the ones in {@code required}. */
    static Map<String, Object> inputObject(Map<ToolArgument, Map<String, Object>> arguments,
                                           List<ToolArgument> required) {
        Map<String, Object> properties = new LinkedHashMap<>();
        arguments.forEach((argument, schema) -> properties.put(argument.jsonName(), schema));
        List<String> requiredNames = new ArrayList<>(required.size());
        required.forEach(argument -> requiredNames.add(argument.jsonName()));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put(TYPE, OBJECT);
        schema.put(PROPERTIES, properties);
        schema.put(REQUIRED, requiredNames);
        schema.put(ADDITIONAL_PROPERTIES, Boolean.FALSE);
        return schema;
    }

    static Map<String, Object> string(ToolArgument argument) {
        return Map.of(TYPE, STRING, DESCRIPTION, argument.description());
    }

    static Map<String, Object> bool(ToolArgument argument) {
        return Map.of(TYPE, BOOLEAN, DESCRIPTION, argument.description());
    }

    /** A non-empty array of access-mode tokens, enumerated from {@link AccessMode} itself. */
    static Map<String, Object> modes(ToolArgument argument) {
        List<String> tokens = new ArrayList<>();
        for (AccessMode mode : AccessMode.values()) {
            tokens.add(mode.headerToken());
        }
        return Map.of(
                TYPE, ARRAY,
                ITEMS, Map.of(TYPE, STRING, ENUM, tokens),
                MIN_ITEMS, 1,
                DESCRIPTION, argument.description());
    }
}
