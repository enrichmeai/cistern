package com.enrichmeai.cistern.webflux;

import com.enrichmeai.cistern.core.CisternException;

import java.net.URI;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.Optional;

import org.springframework.util.MultiValueMap;

/**
 * A parsed receipts query (T5.9): {@code ?receipts[&from=][&to=][&agent=]}, as a value.
 *
 * <p>Two entry points, for the two components that look at the query string:
 * {@link #isRequested} answers "is this a receipts request?" — the only thing the
 * authorization filter needs, since it changes the required mode — and {@link #parse}
 * validates and types the parameters for the handler, which runs only after the filter has
 * let the request through. Splitting them keeps a malformed {@code from} from being reported
 * to a caller who was not entitled to ask in the first place: they get 401/403, not 400.
 *
 * <p>Absent bounds mean the whole log: {@link Instant#MIN} to {@link Instant#MAX}. The
 * interval is half-open, as {@code DecisionQuery} defines it.
 *
 * @param from  inclusive lower bound
 * @param to    exclusive upper bound
 * @param agent the WebID to restrict to, if any
 */
record ReceiptsRequest(Instant from, Instant to, Optional<URI> agent) {

    ReceiptsRequest {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(agent, "agent");
        if (!from.isBefore(to)) {
            throw new CisternException.BadInput(WebfluxMessage.RECEIPTS_INTERVAL_EMPTY.format(from, to));
        }
    }

    /** Whether the query string marks this as a receipts request: {@code receipts} is present, valued or not. */
    static boolean isRequested(MultiValueMap<String, String> query) {
        return query.containsKey(ReceiptsParameter.RECEIPTS.parameterName());
    }

    /**
     * The typed request.
     *
     * @throws CisternException.BadInput if a bound is not an ISO 8601 instant, the agent is not
     *                                   an absolute URI, or the interval is empty — the
     *                                   client's error, rendered as 400 by the one error mapper
     */
    static ReceiptsRequest parse(MultiValueMap<String, String> query) {
        Instant from = instant(query, ReceiptsParameter.FROM).orElse(Instant.MIN);
        Instant to = instant(query, ReceiptsParameter.TO).orElse(Instant.MAX);
        Optional<URI> agent = value(query, ReceiptsParameter.AGENT).map(ReceiptsRequest::webId);
        return new ReceiptsRequest(from, to, agent);
    }

    private static Optional<Instant> instant(MultiValueMap<String, String> query, ReceiptsParameter parameter) {
        return value(query, parameter).map(text -> {
            try {
                return Instant.parse(text);
            } catch (DateTimeParseException e) {
                throw new CisternException.BadInput(
                        WebfluxMessage.RECEIPTS_INSTANT_MALFORMED.format(parameter.parameterName(), text));
            }
        });
    }

    private static URI webId(String text) {
        try {
            URI uri = new URI(text);
            if (!uri.isAbsolute()) {
                throw new CisternException.BadInput(WebfluxMessage.RECEIPTS_AGENT_MALFORMED.format(text));
            }
            return uri;
        } catch (java.net.URISyntaxException e) {
            throw new CisternException.BadInput(WebfluxMessage.RECEIPTS_AGENT_MALFORMED.format(text));
        }
    }

    /** The first value of {@code parameter}, if present and non-blank. */
    private static Optional<String> value(MultiValueMap<String, String> query, ReceiptsParameter parameter) {
        String first = query.getFirst(parameter.parameterName());
        return first == null || first.isBlank() ? Optional.empty() : Optional.of(first.trim());
    }
}
