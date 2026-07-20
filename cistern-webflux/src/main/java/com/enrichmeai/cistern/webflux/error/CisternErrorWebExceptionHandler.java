package com.enrichmeai.cistern.webflux.error;

import com.enrichmeai.cistern.webflux.WebfluxMessage;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.webflux.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.http.codec.HttpMessageWriter;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.reactive.result.view.ViewResolver;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Cistern's single error mapper: every unhandled error on the HTTP surface becomes an
 * RFC 9457 {@code application/problem+json} response here and nowhere else (CLAUDE.md
 * ground rule 4). Handlers stay free of {@code .onErrorResume} status mapping.
 *
 * <h2>Why this mechanism</h2>
 * Cistern routes functionally ({@code RouterFunction}), not with annotated controllers, so
 * {@code @ControllerAdvice} / {@code ResponseEntityExceptionHandler} — and hence Spring Boot's
 * {@code spring.webflux.problemdetails.enabled} switch, which only installs an advice for
 * annotated controllers — cannot see these errors. The whole-pipeline hook is
 * {@link ErrorWebExceptionHandler}, the last handler in the {@code WebHandler} chain, which
 * catches errors from routing, codecs and handlers alike.
 *
 * <p>Spring Boot 4.1 moved that interface to {@code org.springframework.boot.webflux.error}
 * (module {@code spring-boot-webflux}). Boot's own {@code DefaultErrorWebExceptionHandler} is
 * declared {@code @ConditionalOnMissingBean(search = CURRENT)} at {@code @Order(-1)}, so
 * declaring this bean makes Boot back its own off entirely and leaves exactly one error
 * mapper in the context — which is the property this ticket exists to establish. The
 * {@code @Order(-2)} is belt-and-braces for embeddings that reinstate the default one.
 *
 * @see ProblemMapper the mapping table
 * @see <a href="https://www.rfc-editor.org/rfc/rfc9457">RFC 9457 — Problem Details for HTTP APIs</a>
 */
@Component
@Order(-2)
public class CisternErrorWebExceptionHandler implements ErrorWebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(CisternErrorWebExceptionHandler.class);

    private final ProblemMapper mapper;
    private final ServerResponse.Context responseContext;

    public CisternErrorWebExceptionHandler(RequestAuthentication requestAuthentication,
                                           ServerCodecConfigurer codecConfigurer) {
        this.mapper = new ProblemMapper(requestAuthentication);
        // Reuse the application's configured codecs rather than a private ObjectMapper, so
        // the problem body is serialised by the same writers as every other response.
        List<HttpMessageWriter<?>> writers = codecConfigurer.getWriters();
        this.responseContext = new ServerResponse.Context() {
            @Override
            public List<HttpMessageWriter<?>> messageWriters() {
                return writers;
            }

            @Override
            public List<ViewResolver> viewResolvers() {
                return List.of();
            }
        };
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable error) {
        // Nothing can be said once bytes are on the wire; re-signal so the server closes the
        // connection rather than appending a problem body to a half-written response.
        if (exchange.getResponse().isCommitted()) {
            return Mono.error(error);
        }

        ProblemMapper.Problem problem = mapper.map(error, exchange);
        logIt(problem.body(), error, exchange);

        // A partially-populated Content-Type/Content-Length from the aborted handler would
        // otherwise survive onto the problem response.
        exchange.getResponse().getHeaders().clearContentHeaders();

        return ServerResponse.status(problem.body().statusCode())
                .headers(headers -> headers.putAll(problem.headers()))
                .contentType(ProblemDocument.MEDIA_TYPE)
                .bodyValue(problem.body())
                .flatMap(response -> response.writeTo(exchange, responseContext));
    }

    private void logIt(ProblemDocument problem, Throwable error, ServerWebExchange exchange) {
        String method = String.valueOf(exchange.getRequest().getMethod());
        String path = exchange.getRequest().getPath().value();
        if (problem.statusCode().is5xxServerError()) {
            // The only record of what actually broke: the client is told nothing but "500".
            log.error(WebfluxMessage.LOG_SERVER_ERROR.format(method, path, problem.status()), error);
        } else if (log.isDebugEnabled()) {
            log.debug(WebfluxMessage.LOG_CLIENT_ERROR.format(method, path, problem.status(), error));
        }
    }
}
