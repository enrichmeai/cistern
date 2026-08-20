package com.enrichmeai.cistern.mcp;

import com.enrichmeai.cistern.core.Representation;
import com.enrichmeai.cistern.core.ResourceIdentifier;
import com.enrichmeai.cistern.core.ldp.Ldp;
import com.enrichmeai.cistern.core.rdf.RdfIo;
import com.enrichmeai.cistern.mcp.PodAddress.PodTarget;
import com.enrichmeai.cistern.mcp.PodHttp.PodResponse;
import com.enrichmeai.cistern.mcp.ToolResults.Field;
import com.enrichmeai.cistern.wac.AccessMode;
import com.enrichmeai.cistern.wac.AgentClass;
import com.enrichmeai.cistern.wac.Authorization;
import com.enrichmeai.cistern.wac.DecisionField;
import com.enrichmeai.cistern.wac.DecisionRecord;
import com.enrichmeai.cistern.wac.DecisionRecordJson;
import com.enrichmeai.cistern.wac.GrantOutcome;
import com.enrichmeai.cistern.wac.GrantRequest;
import com.enrichmeai.cistern.wac.GrantService;
import com.enrichmeai.cistern.wac.Grantee;
import com.enrichmeai.cistern.wac.RequiredAccess;
import com.enrichmeai.cistern.wac.RevokeRequest;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import java.util.TreeSet;

import io.modelcontextprotocol.spec.McpSchema;
import org.apache.jena.rdf.model.Model;
import reactor.core.publisher.Mono;

/**
 * The seven tools' logic: parse arguments, make the HTTP request(s), render the result. Every
 * pod access goes through {@link PodHttp} — a real request against the running server with the
 * bound credential, decided by {@code AuthorizationFilter} and receipted like any other
 * caller's (ARCHITECTURE decision 6) — and every outcome is rendered by {@link ToolResults},
 * the module's one translator.
 *
 * <p>{@link #handle} is the single guard: the one {@code onErrorResume} in the MCP layer,
 * mapping the closed {@link PodProblem} vocabulary (and the {@code CisternException} a pure
 * service like {@code GrantService} signals) into structured results. Anything outside that
 * vocabulary propagates — a bug should look like a bug, not a polite refusal.
 */
final class PodToolHandlers {

    /** The spelling of "anyone" in a grant/revoke {@code agent} argument. */
    static final String PUBLIC_TOKEN = "public";

    /** What a stored resource is when the server names no type (it always does; belt and braces). */
    private static final String UNKNOWN_MEDIA_TYPE = "application/octet-stream";

    /** Media types whose bytes are safe and useful to inline as text for the model. */
    private static final String TEXT_TYPE_PREFIX = "text/";
    private static final List<String> TEXTUAL_TYPES = List.of(
            Representation.TURTLE, Representation.JSON_LD, "application/json",
            "application/x-ndjson", "application/xml");
    private static final List<String> TEXTUAL_SUFFIXES = List.of("+json", "+xml");

    /** Media-type parameters start here; the stored type may carry a charset. */
    private static final String MEDIA_TYPE_PARAMETER_SEPARATOR = ";";

    private static final String MODE_SEPARATOR = ", ";
    private static final String LINE_SEPARATOR = "\n";

    private final PodHttp http;
    private final PodAddress address;
    private final AclEditor editor;

    PodToolHandlers(PodHttp http, PodAddress address) {
        this.http = Objects.requireNonNull(http, "http");
        this.address = Objects.requireNonNull(address, "address");
        this.editor = new AclEditor(new RemoteAclDiscovery(http, address), http, address,
                new GrantService());
    }

    /** Dispatch {@code request} to {@code tool}; the one place problems become results. */
    Mono<McpSchema.CallToolResult> handle(PodTool tool, McpSchema.CallToolRequest request) {
        return Mono.defer(() -> dispatch(tool, ToolArguments.of(request)))
                .onErrorResume(ToolResults::translatable,
                        signal -> Mono.just(ToolResults.problem(signal)));
    }

    private Mono<McpSchema.CallToolResult> dispatch(PodTool tool, ToolArguments arguments) {
        return switch (tool) {
            case READ_RESOURCE -> readResource(arguments);
            case LIST_CONTAINER -> listContainer(arguments);
            case WRITE_RESOURCE -> writeResource(arguments);
            case DELETE_RESOURCE -> deleteResource(arguments);
            case GRANT -> grant(arguments);
            case REVOKE -> revoke(arguments);
            case RECEIPTS -> receipts(arguments);
        };
    }

    // ---- read-resource ---------------------------------------------------------------------

    private Mono<McpSchema.CallToolResult> readResource(ToolArguments arguments) {
        PodTarget target = address.resolve(arguments.required(ToolArgument.URL));
        ResourceIdentifier id = target.identifier();
        return http.get(id, target.requestUri(), Optional.empty())
                .flatMap(response -> PodHttp.expecting(id,
                        RequiredAccess.forRequest(PodHttp.GET, id), response, PodStatus.OK))
                .map(response -> renderRead(id, response));
    }

    private static McpSchema.CallToolResult renderRead(ResourceIdentifier id, PodResponse response) {
        String contentType = response.contentType().orElse(UNKNOWN_MEDIA_TYPE);
        Map<Field, Object> structured = new LinkedHashMap<>();
        structured.put(Field.RESOURCE, id.uri().toString());
        structured.put(Field.CONTENT_TYPE, contentType);
        response.etag().ifPresent(etag -> structured.put(Field.ETAG, etag.value()));
        structured.put(Field.BYTES, response.body().length);
        if (!isTextual(contentType)) {
            return ToolResults.ok(
                    McpMessage.READ_BINARY.format(id.uri(), contentType, response.body().length),
                    structured);
        }
        String text = McpMessage.READ_OK.format(id.uri(), contentType, response.body().length)
                + LINE_SEPARATOR + response.bodyText();
        return ToolResults.ok(text, structured);
    }

    private static boolean isTextual(String contentType) {
        String bare = contentType.split(MEDIA_TYPE_PARAMETER_SEPARATOR)[0]
                .trim().toLowerCase(Locale.ROOT);
        return bare.startsWith(TEXT_TYPE_PREFIX)
                || TEXTUAL_TYPES.contains(bare)
                || TEXTUAL_SUFFIXES.stream().anyMatch(bare::endsWith);
    }

    // ---- list-container --------------------------------------------------------------------

    private Mono<McpSchema.CallToolResult> listContainer(ToolArguments arguments) {
        PodTarget target = address.resolve(arguments.required(ToolArgument.URL));
        ResourceIdentifier id = target.identifier();
        if (!id.isContainer()) {
            return Mono.error(new PodProblem.BadArgument(
                    McpMessage.TARGET_NOT_A_CONTAINER.format(id.uri())));
        }
        return http.get(id, target.requestUri(), Optional.of(Representation.TURTLE))
                .flatMap(response -> PodHttp.expecting(id,
                        RequiredAccess.forRequest(PodHttp.GET, id), response, PodStatus.OK))
                .map(response -> renderMembers(id, response));
    }

    private static McpSchema.CallToolResult renderMembers(ResourceIdentifier id, PodResponse response) {
        Model graph = RdfIo.parse(new Representation(
                response.contentType().orElse(Representation.TURTLE), response.body()), id);
        Set<String> members = new TreeSet<>();
        graph.listStatements(null, Ldp.CONTAINS, (org.apache.jena.rdf.model.RDFNode) null)
                .forEach(statement -> members.add(statement.getObject().toString()));
        StringJoiner text = new StringJoiner(LINE_SEPARATOR);
        if (members.isEmpty()) {
            text.add(McpMessage.LISTED_EMPTY.format(id.uri()));
        } else {
            text.add(McpMessage.LISTED.format(id.uri(), members.size()));
            members.forEach(member -> text.add(McpMessage.MEMBER_LINE.format(member)));
        }
        return ToolResults.ok(text.toString(), Map.of(
                Field.RESOURCE, id.uri().toString(),
                Field.MEMBERS, List.copyOf(members)));
    }

    // ---- write-resource --------------------------------------------------------------------

    private Mono<McpSchema.CallToolResult> writeResource(ToolArguments arguments) {
        PodTarget target = address.resolve(arguments.required(ToolArgument.URL));
        ResourceIdentifier id = target.identifier();
        String contentType = arguments.required(ToolArgument.CONTENT_TYPE);
        byte[] body = arguments.required(ToolArgument.CONTENT).getBytes(StandardCharsets.UTF_8);
        WritePrecondition precondition = precondition(arguments);
        return http.put(id, target.requestUri(), contentType, body, precondition)
                .flatMap(response -> PodHttp.expecting(id,
                        RequiredAccess.forRequest(PodHttp.PUT, id), response,
                        PodStatus.CREATED, PodStatus.NO_CONTENT))
                .map(response -> renderWrite(id, response));
    }

    private static WritePrecondition precondition(ToolArguments arguments) {
        Optional<String> ifMatch = arguments.optional(ToolArgument.IF_MATCH);
        boolean createOnly = arguments.flag(ToolArgument.CREATE_ONLY);
        if (ifMatch.isPresent() && createOnly) {
            throw new PodProblem.BadArgument(McpMessage.ARGUMENT_INVALID.format(
                    ToolArgument.CREATE_ONLY.jsonName(), Boolean.TRUE,
                    ToolArgument.IF_MATCH.jsonName()));
        }
        if (createOnly) {
            return new WritePrecondition.IfNoneMatchAny();
        }
        return ifMatch.<WritePrecondition>map(etag -> new WritePrecondition.IfMatch(new EntityTagHeader(etag)))
                .orElseGet(WritePrecondition.Unconditional::new);
    }

    private static McpSchema.CallToolResult renderWrite(ResourceIdentifier id, PodResponse response) {
        boolean created = response.status() == PodStatus.CREATED.code();
        Map<Field, Object> structured = new LinkedHashMap<>();
        structured.put(Field.RESOURCE, id.uri().toString());
        structured.put(Field.EFFECT, (created ? PodStatus.CREATED : PodStatus.NO_CONTENT)
                .name().toLowerCase(Locale.ROOT));
        response.etag().ifPresent(etag -> structured.put(Field.ETAG, etag.value()));
        return ToolResults.ok(created
                        ? McpMessage.WRITTEN_CREATED.format(id.uri())
                        : McpMessage.WRITTEN_REPLACED.format(id.uri()),
                structured);
    }

    // ---- delete-resource -------------------------------------------------------------------

    private Mono<McpSchema.CallToolResult> deleteResource(ToolArguments arguments) {
        PodTarget target = address.resolve(arguments.required(ToolArgument.URL));
        ResourceIdentifier id = target.identifier();
        return http.delete(id, target.requestUri())
                .flatMap(response -> PodHttp.expecting(id,
                        RequiredAccess.forRequest(PodHttp.DELETE, id), response,
                        PodStatus.NO_CONTENT))
                .map(response -> ToolResults.ok(McpMessage.DELETED.format(id.uri()),
                        Map.of(Field.RESOURCE, id.uri().toString())));
    }

    // ---- grant / revoke ----------------------------------------------------------------------

    private Mono<McpSchema.CallToolResult> grant(ToolArguments arguments) {
        PodTarget target = address.resolve(arguments.required(ToolArgument.URL));
        Grantee grantee = grantee(arguments.required(ToolArgument.AGENT));
        Set<AccessMode> modes = modes(arguments.requiredList(ToolArgument.MODES));
        return editor.grant(new GrantRequest(target.identifier(), grantee, modes))
                .map(outcome -> renderGrant(target.identifier(), grantee, modes, outcome));
    }

    private Mono<McpSchema.CallToolResult> revoke(ToolArguments arguments) {
        PodTarget target = address.resolve(arguments.required(ToolArgument.URL));
        Grantee grantee = grantee(arguments.required(ToolArgument.AGENT));
        return editor.revoke(new RevokeRequest(target.identifier(), grantee))
                .map(outcome -> renderRevoke(target.identifier(), grantee, outcome));
    }

    private static Grantee grantee(String agent) {
        if (PUBLIC_TOKEN.equalsIgnoreCase(agent)) {
            return Grantee.PUBLIC;
        }
        URI webId = URI.create(agent);
        if (!webId.isAbsolute()) {
            throw new PodProblem.BadArgument(McpMessage.ARGUMENT_INVALID.format(
                    ToolArgument.AGENT.jsonName(), agent, PUBLIC_TOKEN));
        }
        return new Grantee.WebId(webId);
    }

    private static Set<AccessMode> modes(List<String> tokens) {
        Set<AccessMode> modes = EnumSet.noneOf(AccessMode.class);
        for (String token : tokens) {
            modes.add(mode(token));
        }
        return modes;
    }

    private static AccessMode mode(String token) {
        for (AccessMode mode : AccessMode.values()) {
            if (mode.headerToken().equalsIgnoreCase(token)) {
                return mode;
            }
        }
        throw new PodProblem.BadArgument(McpMessage.MODE_UNKNOWN.format(token));
    }

    private static McpSchema.CallToolResult renderGrant(ResourceIdentifier target, Grantee grantee,
                                                        Set<AccessMode> modes, GrantOutcome outcome) {
        String headline = (outcome.changed() ? McpMessage.GRANTED : McpMessage.ALREADY_GRANTED)
                .format(granteeName(grantee), modeTokens(modes), target.uri());
        return ToolResults.ok(headline + LINE_SEPARATOR + aclReport(outcome),
                grantStructure(outcome));
    }

    private static McpSchema.CallToolResult renderRevoke(ResourceIdentifier target, Grantee grantee,
                                                         GrantOutcome outcome) {
        String headline = (outcome.changed() ? McpMessage.REVOKED : McpMessage.NOTHING_TO_REVOKE)
                .format(granteeName(grantee), target.uri());
        return ToolResults.ok(headline + LINE_SEPARATOR + aclReport(outcome),
                grantStructure(outcome));
    }

    /** What the ACL now holds, read back off the graph the service produced. */
    private static String aclReport(GrantOutcome outcome) {
        StringJoiner report = new StringJoiner(LINE_SEPARATOR);
        report.add(McpMessage.ACL_HOLDS.format(outcome.aclResource().uri()));
        for (Authorization authorization : outcome.authorizations()) {
            report.add(McpMessage.AUTHORIZATION_LINE.format(
                    who(authorization), modeTokens(authorization.modes())));
        }
        return report.toString();
    }

    private static Map<Field, Object> grantStructure(GrantOutcome outcome) {
        List<Map<String, Object>> authorizations = new ArrayList<>();
        for (Authorization authorization : outcome.authorizations()) {
            List<String> who = new ArrayList<>();
            authorization.agents().forEach(agent -> who.add(agent.toString()));
            authorization.agentClasses().forEach(agentClass -> who.add(agentClass.iri()));
            List<String> modes = new ArrayList<>();
            authorization.modes().forEach(mode -> modes.add(mode.headerToken()));
            authorizations.add(ToolResults.entry(Map.of(Field.WHO, who, Field.MODES, modes)));
        }
        Map<Field, Object> structured = new LinkedHashMap<>();
        structured.put(Field.ACL, outcome.aclResource().uri().toString());
        structured.put(Field.CHANGED, outcome.changed());
        structured.put(Field.AUTHORIZATIONS, authorizations);
        return structured;
    }

    private static String granteeName(Grantee grantee) {
        return switch (grantee) {
            case Grantee.WebId webId -> webId.webId().toString();
            case Grantee.Public _ -> McpMessage.ANYONE.format();
        };
    }

    private static String who(Authorization authorization) {
        StringJoiner who = new StringJoiner(MODE_SEPARATOR);
        authorization.agents().forEach(agent -> who.add(agent.toString()));
        authorization.agentClasses().forEach(agentClass -> who.add(
                agentClass == AgentClass.PUBLIC ? McpMessage.ANYONE.format() : agentClass.iri()));
        return who.toString();
    }

    private static String modeTokens(Set<AccessMode> modes) {
        StringJoiner tokens = new StringJoiner(MODE_SEPARATOR);
        for (AccessMode mode : AccessMode.values()) {
            if (modes.contains(mode)) {
                tokens.add(mode.headerToken());
            }
        }
        return tokens.toString();
    }

    // ---- receipts ----------------------------------------------------------------------------

    private Mono<McpSchema.CallToolResult> receipts(ToolArguments arguments) {
        PodTarget target = address.resolve(arguments.required(ToolArgument.URL));
        ResourceIdentifier id = target.identifier();
        URI query = ReceiptsQuery.appendTo(target.requestUri(),
                arguments.optional(ToolArgument.FROM), arguments.optional(ToolArgument.TO));
        return http.get(id, query, Optional.empty())
                .flatMap(response -> PodHttp.expecting(id,
                        RequiredAccess.forReceipts(id), response, PodStatus.OK))
                .map(response -> renderReceipts(id, response));
    }

    private static McpSchema.CallToolResult renderReceipts(ResourceIdentifier id, PodResponse response) {
        String ndjson = response.bodyText();
        List<Map<String, Object>> records = new ArrayList<>();
        ndjson.lines()
                .filter(line -> !line.isBlank())
                .forEach(line -> DecisionRecordJson.parse(line)
                        .ifPresent(record -> records.add(receiptFields(record))));
        String text = records.isEmpty()
                ? McpMessage.RECEIPTS_EMPTY.format(id.uri())
                : McpMessage.RECEIPTS.format(id.uri(), records.size()) + LINE_SEPARATOR + ndjson.strip();
        return ToolResults.ok(text, Map.of(
                Field.RESOURCE, id.uri().toString(),
                Field.COUNT, records.size(),
                Field.RECORDS, records));
    }

    /** One receipt, keyed exactly as the wire format's {@code DecisionField}s. */
    private static Map<String, Object> receiptFields(DecisionRecord record) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put(DecisionField.AT.key(), record.at().toString());
        fields.put(DecisionField.AGENT.key(), record.agent().webId().map(URI::toString).orElse(null));
        fields.put(DecisionField.TARGET.key(), record.target().uri().toString());
        fields.put(DecisionField.REQUIRED.key(), record.required().name());
        fields.put(DecisionField.OUTCOME.key(), record.outcome().name());
        fields.put(DecisionField.DECIDED_BY.key(),
                record.decidedBy().map(acl -> acl.uri().toString()).orElse(null));
        fields.put(DecisionField.REQUEST_ID.key(), record.requestId().value());
        return fields;
    }
}
