package com.enrichmeai.cistern.webflux.error;

import static org.assertj.core.api.Assertions.assertThat;

import com.enrichmeai.cistern.core.CisternException;
import com.enrichmeai.cistern.core.ldp.LdpKind;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

/**
 * Carries over the one invariant of the deleted {@code ProblemMapperCoverageTest} that sealing
 * did <em>not</em> subsume (#60).
 *
 * <p>That class held two assertions. Its completeness check — every {@code CisternException}
 * subtype has a status — is now the compiler's job: {@code CisternException} is sealed and
 * {@code ProblemMapper}'s switch over it is exhaustive, so an unmapped subtype fails the build
 * rather than this test. Its <em>distinctness</em> check is a different guarantee, and an
 * exhaustive switch cannot express it: nothing stops two cases returning the same
 * {@link ProblemType}. So it is restated here rather than dropped.
 *
 * <p>Why it matters: RFC 9457 §3.1.1 makes the {@code type} URI the client's stable, machine-
 * readable identifier for <em>what went wrong</em>. Two distinct domain errors sharing one
 * type URI would make them indistinguishable to a client that does the right thing and
 * branches on {@code type} rather than on the status code.
 *
 * <p>Driven through the public {@code map} seam with one instance of each subtype, so it pins
 * the behaviour rather than the internal table the previous test reached into by reflection.
 */
class ProblemMapperDistinctnessTest {

    /**
     * One of each permitted subtype whose problem type is a function of its class alone.
     * {@code AccessDenied} is excluded for the same reason the old test excluded it: its type
     * is resolved through the 401/403 seam and depends on the request, not only the class.
     *
     * <p>The compiler keeps this list honest in the direction that matters — a new subtype
     * cannot be added without {@code ProblemMapper} failing to compile — so this cannot
     * silently fall behind the hierarchy.
     */
    private static final List<CisternException> ONE_OF_EACH = List.of(
            new CisternException.BadInput("bad input"),
            new CisternException.Conflict("conflict"),
            new CisternException.MethodNotAllowed("method not allowed", LdpKind.RDF_DOCUMENT),
            new CisternException.NotAcceptable("not acceptable"),
            new CisternException.NotFound("not found"),
            new CisternException.PreconditionFailed("precondition failed"),
            new CisternException.UnprocessableEntity("unprocessable entity"),
            new CisternException.UnsupportedMediaType("unsupported media type"));

    @Test
    @DisplayName("no two domain error subtypes share a problem type URI")
    void problemTypesAreDistinct() {
        ProblemMapper mapper = new ProblemMapper(exchange -> true);
        MockServerWebExchange exchange =
                MockServerWebExchange.from(MockServerHttpRequest.method(HttpMethod.GET, "/notes/"));

        List<URI> types = ONE_OF_EACH.stream()
                .map(error -> mapper.map(error, exchange).body().type())
                .toList();

        assertThat(types)
                .as("each CisternException subtype must map to its own RFC 9457 type URI")
                .doesNotHaveDuplicates()
                .hasSameSizeAs(ONE_OF_EACH);
    }
}
