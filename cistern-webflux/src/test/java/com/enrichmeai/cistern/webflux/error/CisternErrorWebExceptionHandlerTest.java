package com.enrichmeai.cistern.webflux.error;

import static org.assertj.core.api.Assertions.assertThat;

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
 * {@code application/problem+json} content type, and the RFC 9457 members.
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

    private static final String TYPE_BASE = ProblemMapper.TYPE_BASE;

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
        problem("/probe/bad-input", HttpStatus.BAD_REQUEST)
                .jsonPath("$.type").isEqualTo(TYPE_BASE + "bad-input")
                .jsonPath("$.title").isEqualTo("Malformed request entity")
                .jsonPath("$.detail").isEqualTo("not valid Turtle")
                .jsonPath("$.instance").isEqualTo("/probe/bad-input");
    }

    @Test
    @DisplayName("UnprocessableEntity → 422 (RFC 4918); the T2.7 N3 Patch engine depends on it")
    void unprocessableEntity() {
        problem("/probe/unprocessable-entity", HttpStatus.UNPROCESSABLE_CONTENT)
                .jsonPath("$.type").isEqualTo(TYPE_BASE + "unprocessable-entity")
                .jsonPath("$.title").isEqualTo("Request entity violates a protocol constraint")
                .jsonPath("$.detail").isEqualTo("patch deletes a triple it does not bind");
    }

    @Test
    @DisplayName("NotFound → 404")
    void notFound() {
        problem("/probe/not-found", HttpStatus.NOT_FOUND)
                .jsonPath("$.type").isEqualTo(TYPE_BASE + "not-found")
                .jsonPath("$.title").isEqualTo("Resource not found")
                .jsonPath("$.detail").isEqualTo("no such resource");
    }

    @Test
    @DisplayName("Conflict → 409 (Solid Protocol §5.3: containment-triple writes)")
    void conflict() {
        problem("/probe/conflict", HttpStatus.CONFLICT)
                .jsonPath("$.type").isEqualTo(TYPE_BASE + "conflict")
                .jsonPath("$.title").isEqualTo("Request conflicts with the state of the resource")
                .jsonPath("$.detail").isEqualTo("cannot write containment triples");
    }

    @Test
    @DisplayName("PreconditionFailed → 412")
    void preconditionFailed() {
        problem("/probe/precondition-failed", HttpStatus.PRECONDITION_FAILED)
                .jsonPath("$.type").isEqualTo(TYPE_BASE + "precondition-failed")
                .jsonPath("$.title").isEqualTo("Precondition failed")
                .jsonPath("$.detail").isEqualTo("If-Match did not match the current ETag");
    }

    @Test
    @DisplayName("AccessDenied without credentials → 401 (Solid Protocol §2.1)")
    void accessDeniedUnauthenticated() {
        problem("/probe/access-denied", HttpStatus.UNAUTHORIZED)
                .jsonPath("$.type").isEqualTo(TYPE_BASE + "authentication-required")
                .jsonPath("$.title").isEqualTo("Authentication required")
                .jsonPath("$.detail").isEqualTo("Write mode required");
    }

    @Test
    @DisplayName("AccessDenied for an authenticated agent → 403")
    void accessDeniedAuthenticated() {
        client.get().uri("/probe/access-denied")
                .header(ErrorProbeRoutes.AUTHENTICATED_HEADER, "yes")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.FORBIDDEN)
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(403)
                .jsonPath("$.type").isEqualTo(TYPE_BASE + "access-denied")
                .jsonPath("$.title").isEqualTo("Access denied")
                .jsonPath("$.detail").isEqualTo("Write mode required");
    }

    @Test
    @DisplayName("IllegalArgumentException → 400 (caller programming error out of core)")
    void illegalArgument() {
        problem("/probe/illegal-argument", HttpStatus.BAD_REQUEST)
                .jsonPath("$.type").isEqualTo("about:blank")
                .jsonPath("$.title").isEqualTo("Bad Request")
                .jsonPath("$.detail").isEqualTo("/doc is not a container");
    }

    @Test
    @DisplayName("IllegalStateException → 500, and the corruption detail stays server-side")
    void illegalState() {
        problem("/probe/illegal-state", HttpStatus.INTERNAL_SERVER_ERROR)
                .jsonPath("$.type").isEqualTo("about:blank")
                .jsonPath("$.title").isEqualTo("Internal Server Error")
                .jsonPath("$.detail").isEqualTo(ProblemMapper.INTERNAL_ERROR_DETAIL);
    }

    @Test
    @DisplayName("an unforeseen exception → 500 by the fallback arm")
    void unexpected() {
        problem("/probe/boom", HttpStatus.INTERNAL_SERVER_ERROR)
                .jsonPath("$.type").isEqualTo("about:blank")
                .jsonPath("$.detail").isEqualTo(ProblemMapper.INTERNAL_ERROR_DETAIL);
    }

    @Test
    @DisplayName("500 bodies leak neither the exception message nor its type (RFC 9457 §5)")
    void serverErrorsDoNotLeakInternals() {
        for (String path : new String[] {"/probe/illegal-state", "/probe/boom"}) {
            client.get().uri(path).exchange()
                    .expectStatus().isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR)
                    .expectBody(String.class)
                    .value(body -> assertThat(body)
                            .as("500 body for %s", path)
                            .doesNotContain(ErrorProbeRoutes.SECRET)
                            .doesNotContain("sidecar")
                            .doesNotContain("IllegalStateException")
                            .doesNotContain("RuntimeException")
                            .doesNotContain("com.enrichmeai")
                            .doesNotContain("org.springframework")
                            .doesNotContain("at java."));
        }
    }

    @Test
    @DisplayName("unsupported media type → 415 as problem+json, keeping Spring's Accept header")
    void unsupportedMediaType() {
        client.post().uri("/probe/echo")
                .contentType(MediaType.TEXT_PLAIN)
                .bodyValue("not decodable as a record")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectHeader().exists(HttpHeaders.ACCEPT)
                .expectBody()
                .jsonPath("$.status").isEqualTo(415)
                .jsonPath("$.type").isEqualTo("about:blank")
                .jsonPath("$.title").isEqualTo("Unsupported Media Type")
                .jsonPath("$.instance").isEqualTo("/probe/echo");
    }

    @Test
    @DisplayName("malformed request body → 400 as problem+json")
    void unreadableBody() {
        client.post().uri("/probe/echo")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{ this is not json")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.BAD_REQUEST)
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(400)
                .jsonPath("$.title").isEqualTo("Bad Request")
                .jsonPath("$.instance").isEqualTo("/probe/echo");
    }

    @Test
    @DisplayName("method not allowed → 405 as problem+json, keeping the mandated Allow header")
    void methodNotAllowed() {
        problem("/probe/method-not-allowed", HttpStatus.METHOD_NOT_ALLOWED)
                .jsonPath("$.type").isEqualTo("about:blank")
                .jsonPath("$.title").isEqualTo("Method Not Allowed");
        client.get().uri("/probe/method-not-allowed").exchange()
                .expectHeader().exists(HttpHeaders.ALLOW);
    }

    @Test
    @DisplayName("an unrouted path → 404 as problem+json, not Boot's default error body")
    void unroutedPath() {
        problem("/probe/no-such-route", HttpStatus.NOT_FOUND)
                .jsonPath("$.type").isEqualTo("about:blank")
                .jsonPath("$.title").isEqualTo("Not Found")
                .jsonPath("$.instance").isEqualTo("/probe/no-such-route");
    }

    /** GETs {@code path}, asserting the status, the problem media type and the status member. */
    private WebTestClient.BodyContentSpec problem(String path, HttpStatus expected) {
        return client.get().uri(path).exchange()
                .expectStatus().isEqualTo(expected)
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.status").isEqualTo(expected.value());
    }
}
