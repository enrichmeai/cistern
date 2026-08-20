package com.enrichmeai.cistern.mcp;

import com.enrichmeai.cistern.core.CisternException;
import com.enrichmeai.cistern.wac.AccessRequirement;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.spec.McpSchema;
import org.apache.jena.atlas.json.JSON;
import org.apache.jena.atlas.json.JsonObject;

/**
 * The <strong>one</strong> place a tool call's outcome becomes an MCP result — the MCP-layer
 * analogue of cistern-webflux's single error mapper (ground rule 4). Handlers build successes
 * with {@link #ok}; everything that signals through the chain is rendered by {@link #problem},
 * and no handler writes its own refusal or error text.
 *
 * <p>The contract for refusals is the ticket's: a 401/403 from the server becomes a structured
 * {@code refused} result that names the resource and the required mode — never an empty
 * success, never a silent retry. Refusals and failures both set {@code isError} so a client
 * model treats them as outcomes to report, and the first word of the text says which kind it
 * is; the {@code structuredContent} repeats the same facts machine-readably.
 */
final class ToolResults {

    /** What a result was, in one token — the {@code outcome} member of every structured result. */
    enum Outcome {
        OK("ok"),
        REFUSED("refused"),
        FAILED("failed");

        private final String token;

        Outcome(String token) {
            this.token = token;
        }

        String token() {
            return token;
        }
    }

    /** The members of the structured results — a closed set of names (ground rule 7). */
    enum Field {
        OUTCOME("outcome"),
        RESOURCE("resource"),
        STATUS("status"),
        REQUIRED("required"),
        MODE("mode"),
        CONTENT_TYPE("contentType"),
        ETAG("etag"),
        BYTES("bytes"),
        EFFECT("effect"),
        MEMBERS("members"),
        ACL("acl"),
        CHANGED("changed"),
        AUTHORIZATIONS("authorizations"),
        WHO("who"),
        MODES("modes"),
        COUNT("count"),
        RECORDS("records"),
        DETAIL("detail");

        private final String key;

        Field(String key) {
            this.key = key;
        }

        String key() {
            return key;
        }
    }

    /** RFC 9457 problem members, read from the server's own error documents. */
    private static final String PROBLEM_TITLE = "title";
    private static final String PROBLEM_DETAIL = "detail";
    private static final String PROBLEM_MEDIA_TYPE = "application/problem+json";
    private static final String PROBLEM_TITLE_DETAIL_SEPARATOR = ": ";

    /** An unexpected body is quoted, not dumped: enough to diagnose, capped (chars). */
    private static final int MAX_QUOTED_BODY = 2000;
    private static final String TRUNCATION_MARKER = "…";

    private ToolResults() {
        // one translator, no instances
    }

    // ---- successes -------------------------------------------------------------------------

    /** A successful result: {@code text} for the model, {@code structured} for programs. */
    static McpSchema.CallToolResult ok(String text, Map<Field, Object> structured) {
        return McpSchema.CallToolResult.builder()
                .addTextContent(text)
                .structuredContent(keyed(withOutcome(Outcome.OK, structured)))
                .isError(false)
                .build();
    }

    /** The structured members of a nested object (an authorization line, a requirement). */
    static Map<String, Object> entry(Map<Field, Object> members) {
        return keyed(members);
    }

    // ---- the translator --------------------------------------------------------------------

    /**
     * Render a signalled problem. {@link PodProblem.Refused} is the structured refusal;
     * everything else is a structured failure carrying the server's own words where it has
     * any. Signals this translator has no rule for are not swallowed — a bug should surface
     * as the failure it is.
     */
    static McpSchema.CallToolResult problem(Throwable signal) {
        return switch (signal) {
            case PodProblem.Refused refused -> refusal(refused);
            case PodProblem.Unexpected unexpected -> failed(
                    McpMessage.UNEXPECTED_STATUS.format(unexpected.status(),
                            unexpected.target().uri(), serverWords(unexpected)),
                    Map.of(Field.RESOURCE, unexpected.target().uri().toString(),
                            Field.STATUS, unexpected.status(),
                            Field.DETAIL, serverWords(unexpected)));
            case PodProblem.PreconditionFailed precondition -> failed(
                    precondition.getMessage(),
                    Map.of(Field.RESOURCE, precondition.target().uri().toString(),
                            Field.STATUS, PodStatus.PRECONDITION_FAILED.code(),
                            Field.DETAIL, precondition.getMessage()));
            case PodProblem problem -> failed(
                    problem.getMessage(), Map.of(Field.DETAIL, problem.getMessage()));
            // GrantService refuses a revoke that would drop Control with the core Conflict
            // signal; it deserves the service's own words, not a stack trace.
            case CisternException e -> failed(
                    e.getMessage(), Map.of(Field.DETAIL, e.getMessage()));
            default -> throw asUnchecked(signal);
        };
    }

    /** Whether {@link #problem} has a rule for {@code signal} — the guard's filter. */
    static boolean translatable(Throwable signal) {
        return signal instanceof PodProblem || signal instanceof CisternException;
    }

    // ---- rendering -------------------------------------------------------------------------

    private static McpSchema.CallToolResult refusal(PodProblem.Refused refused) {
        String requirement = describe(refused.requirements());
        String text = McpMessage.REFUSED.format(requirement, refused.status().code())
                + (refused.status() == PodStatus.UNAUTHORIZED
                        ? McpMessage.REFUSED_UNAUTHENTICATED.format()
                        : McpMessage.REFUSED_FORBIDDEN.format());
        List<Map<String, Object>> required = new ArrayList<>();
        refused.requirements().forEach(r -> required.add(entry(Map.of(
                Field.RESOURCE, r.target().uri().toString(),
                Field.MODE, r.mode().headerToken()))));
        Map<Field, Object> structured = new LinkedHashMap<>();
        structured.put(Field.OUTCOME, Outcome.REFUSED.token());
        structured.put(Field.RESOURCE, refused.target().uri().toString());
        structured.put(Field.STATUS, refused.status().code());
        structured.put(Field.REQUIRED, required);
        return McpSchema.CallToolResult.builder()
                .addTextContent(text)
                .structuredContent(keyed(structured))
                .isError(true)
                .build();
    }

    private static McpSchema.CallToolResult failed(String text, Map<Field, Object> members) {
        return McpSchema.CallToolResult.builder()
                .addTextContent(text)
                .structuredContent(keyed(withOutcome(Outcome.FAILED, members)))
                .isError(true)
                .build();
    }

    /** "read on <uri>", or "write on <uri> and write on <uri>" for a DELETE's pair. */
    private static String describe(List<AccessRequirement> requirements) {
        StringBuilder description = new StringBuilder();
        for (AccessRequirement requirement : requirements) {
            String one = McpMessage.REQUIREMENT.format(
                    requirement.mode().headerToken(), requirement.target().uri());
            description.append(description.isEmpty()
                    ? one : McpMessage.REQUIREMENT_SEPARATOR.format(one));
        }
        return description.toString();
    }

    /**
     * The server's own explanation: an RFC 9457 problem document's title and detail when the
     * body is one, else the body quoted (capped), else the status alone. The server's error
     * mapper remains the one authority on what went wrong; this only carries its words.
     */
    private static String serverWords(PodProblem.Unexpected unexpected) {
        String body = new String(unexpected.body(), StandardCharsets.UTF_8);
        if (unexpected.contentType().filter(t -> t.startsWith(PROBLEM_MEDIA_TYPE)).isPresent()) {
            try {
                JsonObject problem = JSON.parse(body);
                String title = problem.hasKey(PROBLEM_TITLE)
                        ? problem.get(PROBLEM_TITLE).getAsString().value() : null;
                String detail = problem.hasKey(PROBLEM_DETAIL)
                        ? problem.get(PROBLEM_DETAIL).getAsString().value() : null;
                if (title != null) {
                    return detail == null
                            ? title : title + PROBLEM_TITLE_DETAIL_SEPARATOR + detail;
                }
            } catch (RuntimeException ignored) {
                // fall through to quoting the raw body: a malformed problem document is
                // still the server's answer, and hiding it would hide the evidence
            }
        }
        return body.length() <= MAX_QUOTED_BODY
                ? body : body.substring(0, MAX_QUOTED_BODY) + TRUNCATION_MARKER;
    }

    private static Map<Field, Object> withOutcome(Outcome outcome, Map<Field, Object> members) {
        Map<Field, Object> all = new LinkedHashMap<>();
        all.put(Field.OUTCOME, outcome.token());
        all.putAll(members);
        return all;
    }

    private static Map<String, Object> keyed(Map<Field, Object> members) {
        Map<String, Object> keyed = new LinkedHashMap<>();
        members.forEach((field, value) -> keyed.put(field.key(), value));
        return keyed;
    }

    private static RuntimeException asUnchecked(Throwable signal) {
        return signal instanceof RuntimeException runtime
                ? runtime : new IllegalStateException(signal);
    }
}
