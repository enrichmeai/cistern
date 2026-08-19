package com.enrichmeai.cistern.webflux;

import com.enrichmeai.cistern.core.ResourceIdentifier;
import com.enrichmeai.cistern.wac.DecisionLog;
import com.enrichmeai.cistern.wac.DecisionQuery;
import com.enrichmeai.cistern.wac.DecisionRecord;
import com.enrichmeai.cistern.wac.DecisionRecordJson;

import java.net.URI;
import java.util.Objects;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Serves {@code GET <resource>?receipts} (T5.9): the decision log for a resource — or, at the
 * pod root with {@code &agent=}, for an agent — as newline-delimited JSON.
 *
 * <p>Thin by rule. Who may ask has already been decided: the request reached this handler
 * through {@code AuthorizationFilter}, which recognised the query and required
 * {@code acl:Control} on the resource ({@code RequiredAccess.forReceipts}). Nothing here
 * re-checks that, and nothing here could be reached without it — the route is registered
 * only when enforcement is.
 *
 * <h2>Why JSON Lines, not a JSON array</h2>
 * It is the log's own format, so a receipt on the wire is byte-for-byte a receipt on disk —
 * one shape to document, one codec ({@link DecisionRecordJson}). It streams: WebFlux flushes
 * {@code application/x-ndjson} per element, so a large window is delivered as it is read rather
 * than buffered whole, and {@code curl … | jq -c .} handles it as naturally as an array. An
 * array would have needed either a buffered body or a hand-written streaming envelope, for a
 * format the log does not use.
 *
 * <h2>What the resource being asked about is</h2>
 * The receipts of a resource are the decisions <em>about</em> it, so they exist whether or not
 * the resource does: a refused {@code PUT} to a path that was never created is a receipt, and
 * so is every access to a note that has since been deleted. The handler therefore does not
 * look the resource up and never answers 404 — an empty log is an empty body, 200.
 *
 * <p>{@code Cache-Control: no-store}: audit data is sensitive and changes on every request;
 * no intermediary should keep a copy.
 */
public final class ReceiptsHandler {

    /** The response's media type: the log's own. */
    static final MediaType NDJSON = MediaType.parseMediaType(DecisionLog.MEDIA_TYPE);

    private final DecisionQuery query;
    private final RequestPaths requestPaths;

    public ReceiptsHandler(DecisionQuery query, RequestPaths requestPaths) {
        this.query = Objects.requireNonNull(query, "query");
        this.requestPaths = Objects.requireNonNull(requestPaths, "requestPaths");
    }

    /** The receipts of the request's target, one JSON object per line. */
    public Mono<ServerResponse> receipts(ServerRequest request) {
        return Mono.defer(() -> {
            ResourceIdentifier target = requestPaths.identifierFor(request);
            ReceiptsRequest receipts = ReceiptsRequest.parse(request.queryParams());
            Flux<String> lines = select(target, receipts)
                    .map(DecisionRecordJson::toLine)
                    .map(line -> line + DecisionLog.LINE_SEPARATOR);
            return ServerResponse.ok()
                    .contentType(NDJSON)
                    .cacheControl(CacheControl.noStore())
                    .body(lines, String.class);
        });
    }

    /**
     * Which records: the resource's, or an agent's. {@code agent=} at the pod root is the
     * owner's question — everything that agent did anywhere in the pod, which is what Control
     * on the root entitles them to see, since Control there governs the whole pod's policy by
     * {@code acl:default}. On any other resource, {@code agent=} narrows that resource's own
     * receipts to one agent and reveals nothing beyond what the unfiltered query would.
     */
    private Flux<DecisionRecord> select(ResourceIdentifier target, ReceiptsRequest receipts) {
        return receipts.agent()
                .map(agent -> target.isStorageRoot()
                        ? query.forAgent(agent, receipts.from(), receipts.to())
                        : query.forResource(target, receipts.from(), receipts.to())
                                .filter(record -> isBy(record, agent)))
                .orElseGet(() -> query.forResource(target, receipts.from(), receipts.to()));
    }

    private static boolean isBy(DecisionRecord record, URI agent) {
        return record.agent().webId().filter(agent::equals).isPresent();
    }
}
