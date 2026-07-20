package com.enrichmeai.cistern.webflux.error;

import static org.assertj.core.api.Assertions.assertThat;

import com.enrichmeai.cistern.webflux.WebfluxMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webflux.error.ErrorWebExceptionHandler;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Every row of {@link ProblemMapper}'s table, asserted over real HTTP: status, the
 * {@code application/problem+json} content type, and the RFC 9457 members. Expected values
 * come from {@link ProblemType} and {@link Probe} rather than repeated literals, so a change
 * to a title or type URI cannot leave a test asserting the old one.
 *
 * <p>Boot 4 note: {@code @SpringBootTest} no longer configures a {@code WebTestClient}
 * implicitly — {@code @AutoConfigureWebTestClient} (module {@code spring-boot-webtestclient})
 * is required.
 *
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9457">RFC 9457</a>
 */
@SpringBootTest
@AutoConfigureWebTestClient
class CisternErrorWebExceptionHandlerTest {

    @Autowired
    private WebTestClient client;

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("exactly one error mapper is in the context — Boot's default backs off")
    void singleErrorMapper() {
        assertThat(context.getBeansOfType(ErrorWebExceptionHandler.class))
                .as("CLAUDE.md ground rule 4: only one place maps domain errors to HTTP")
                .hasSize(1)
                .containsValue(context.getBean(CisternErrorWebExceptionHandler.class));
    }

    @Test
    @DisplayName("BadInput → 400")
    void badInput() {
        assertTyped(Probe.BAD_INPUT, ProblemType.BAD_INPUT);
    }

    @Test
    @DisplayName("UnprocessableEntity → 422 (RFC 4918); the T2.7 N3 Patch engine depends on it")
    void unprocessableEntity() {
        assertTyped(Probe.UNPROCESSABLE_ENTITY, ProblemType.UNPROCESSABLE_ENTITY)
                .jsonPath("$.status").isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT.value());
    }

    @Test
    @DisplayName("NotFound → 404")
    void notFound() {
        assertTyped(Probe.NOT_FOUND, ProblemType.NOT_FOUND);
    }

    @Test
    @DisplayName("NotAcceptable → 406 (RFC 9110 §15.5.7); ContentNegotiator raises it")
    void notAcceptable() {
        assertTyped(Probe.NOT_ACCEPTABLE, ProblemType.NOT_ACCEPTABLE);
    }

    @Test
    @DisplayName("Conflict → 409 (Solid Protocol §5.3: containment-triple writes)")
    void conflict() {
        assertTyped(Probe.CONFLICT, ProblemType.CONFLICT);
    }

    @Test
    @DisplayName("PreconditionFailed → 412")
    void preconditionFailed() {
        assertTyped(Probe.PRECONDITION_FAILED, ProblemType.PRECONDITION_FAILED);
    }

    @Test
    @DisplayName("AccessDenied without credentials → 401 (Solid Protocol §2.1)")
    void accessDeniedUnauthenticated() {
        assertTyped(Probe.ACCESS_DENIED, ProblemType.AUTHENTICATION_REQUIRED);
    }

    @Test
    @DisplayName("AccessDenied for an authenticated agent → 403")
    void accessDeniedAuthenticated() {
        ProblemType expected = ProblemType.ACCESS_DENIED;
        client.get().uri(Probe.ACCESS_DENIED.path())
                .header(ErrorProbeRoutes.AUTHENTICATED_HEADER, "yes")
                .exchange()
                .expectStatus().isEqualTo(expected.status())
                .expectHeader().contentType(ProblemDocument.MEDIA_TYPE)
                .expectBody()
                .jsonPath("$.status").isEqualTo(expected.status().value())
                .jsonPath("$.type").isEqualTo(expected.uri().toString())
                .jsonPath("$.title").isEqualTo(expected.title())
                .jsonPath("$.detail").isEqualTo(Probe.ACCESS_DENIED.detail());
    }

    @Test
    @DisplayName("IllegalArgumentException → 400 (caller programming error out of core)")
    void illegalArgument() {
        assertBlank(Probe.ILLEGAL_ARGUMENT, HttpStatus.BAD_REQUEST)
                .jsonPath("$.detail").isEqualTo(Probe.ILLEGAL_ARGUMENT.detail());
    }

    @Test
    @DisplayName("IllegalStateException → 500, and the corruption detail stays server-side")
    void illegalState() {
        assertBlank(Probe.ILLEGAL_STATE, HttpStatus.INTERNAL_SERVER_ERROR)
                .jsonPath("$.detail").isEqualTo(WebfluxMessage.DETAIL_INTERNAL_ERROR.format());
    }

    @Test
    @DisplayName("an unforeseen exception → 500 by the fallback arm")
    void unexpected() {
        assertBlank(Probe.UNEXPECTED, HttpStatus.INTERNAL_SERVER_ERROR)
                .jsonPath("$.detail").isEqualTo(WebfluxMessage.DETAIL_INTERNAL_ERROR.format());
    }

    @Test
    @DisplayName("500 bodies leak neither the exception message nor its type (RFC 9457 §5)")
    void serverErrorsDoNotLeakInternals() {
        for (Probe probe : new Probe[] {Probe.ILLEGAL_STATE, Probe.UNEXPECTED}) {
            client.get().uri(probe.path()).exchange()
                    .expectStatus().isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR)
                    .expectBody(String.class)
                    .value(body -> assertThat(body)
                            .as("500 body for %s", probe.path())
                            .doesNotContain(Probe.secret())
                            .doesNotContain("sidecar")
                            .doesNotContain(IllegalStateException.class.getSimpleName())
                            .doesNotContain(RuntimeException.class.getSimpleName())
                            .doesNotContain("com.enrichmeai")
                            .doesNotContain("org.springframework")
                            .doesNotContain("at java."));
        }
    }

    @Test
    @DisplayName("unsupported media type → 415 as problem+json, keeping Spring's Accept header")
    void unsupportedMediaType() {
        client.post().uri(Probe.ECHO.path())
                .contentType(MediaType.TEXT_PLAIN)
                .bodyValue("not decodable as a record")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .expectHeader().contentType(ProblemDocument.MEDIA_TYPE)
                .expectHeader().exists(HttpHeaders.ACCEPT)
                .expectBody()
                .jsonPath("$.status").isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE.value())
                .jsonPath("$.type").isEqualTo(ProblemDocument.BLANK_TYPE.toString())
                .jsonPath("$.title").isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE.getReasonPhrase())
                .jsonPath("$.instance").isEqualTo(Probe.ECHO.path());
    }

    @Test
    @DisplayName("malformed request body → 400 as problem+json")
    void unreadableBody() {
        client.post().uri(Probe.ECHO.path())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{ this is not json")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.BAD_REQUEST)
                .expectHeader().contentType(ProblemDocument.MEDIA_TYPE)
                .expectBody()
                .jsonPath("$.status").isEqualTo(HttpStatus.BAD_REQUEST.value())
                .jsonPath("$.title").isEqualTo(HttpStatus.BAD_REQUEST.getReasonPhrase())
                .jsonPath("$.instance").isEqualTo(Probe.ECHO.path());
    }

    @Test
    @DisplayName("method not allowed → 405 as problem+json, keeping the mandated Allow header")
    void methodNotAllowed() {
        assertBlank(Probe.METHOD_NOT_ALLOWED, HttpStatus.METHOD_NOT_ALLOWED);
        client.get().uri(Probe.METHOD_NOT_ALLOWED.path()).exchange()
                .expectHeader().exists(HttpHeaders.ALLOW);
    }

    @Test
    @DisplayName("an unrouted path → 404 as problem+json, not Boot's default error body")
    void unroutedPath() {
        assertBlank(Probe.UNROUTED, HttpStatus.NOT_FOUND);
    }

    /** Asserts a response carrying one of Cistern's own problem types. */
    private WebTestClient.BodyContentSpec assertTyped(Probe probe, ProblemType expected) {
        return get(probe, expected.status())
                .jsonPath("$.type").isEqualTo(expected.uri().toString())
                .jsonPath("$.title").isEqualTo(expected.title())
                .jsonPath("$.detail").isEqualTo(probe.detail())
                .jsonPath("$.instance").isEqualTo(probe.path());
    }

    /** Asserts an {@code about:blank} response, whose title is the status reason phrase. */
    private WebTestClient.BodyContentSpec assertBlank(Probe probe, HttpStatus expected) {
        return get(probe, expected)
                .jsonPath("$.type").isEqualTo(ProblemDocument.BLANK_TYPE.toString())
                .jsonPath("$.title").isEqualTo(expected.getReasonPhrase())
                .jsonPath("$.instance").isEqualTo(probe.path());
    }

    private WebTestClient.BodyContentSpec get(Probe probe, HttpStatus expected) {
        return client.get().uri(probe.path()).exchange()
                .expectStatus().isEqualTo(expected)
                .expectHeader().contentType(ProblemDocument.MEDIA_TYPE)
                .expectBody()
                .jsonPath("$.status").isEqualTo(expected.value());
    }
}
