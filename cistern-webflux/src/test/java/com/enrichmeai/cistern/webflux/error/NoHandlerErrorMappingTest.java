package com.enrichmeai.cistern.webflux.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Guards CLAUDE.md ground rule 4 — "no {@code .onErrorResume} for error mapping in handlers"
 * — and the T2.6 DoD line that says the same. The rule is only worth stating if something
 * enforces it, and review has to catch a regression every time whereas this catches it once.
 *
 * <p>Scope is deliberately narrow: {@code cistern-webflux} main sources only. Domain modules
 * legitimately use {@code onErrorResume} for domain concerns; it is the HTTP layer that must
 * not quietly grow a second error mapper.
 */
class NoHandlerErrorMappingTest {

    private static final Path MAIN_SOURCES = Path.of("src/main/java");

    /** {@link CisternErrorWebExceptionHandler} and friends <em>are</em> the error mapper. */
    private static final Path ERROR_MAPPER_PACKAGE =
            MAIN_SOURCES.resolve("com/enrichmeai/cistern/webflux/error");

    @Test
    @DisplayName("no handler outside the error package maps errors with .onErrorResume / .onErrorMap")
    void handlersDoNotMapErrors() {
        List<String> offenders = sources()
                .filter(file -> !file.startsWith(ERROR_MAPPER_PACKAGE))
                .filter(NoHandlerErrorMappingTest::mapsErrors)
                .map(Path::toString)
                .toList();

        assertThat(offenders)
                .as("""
                        CLAUDE.md ground rule 4: only the global error handler \
                        (com.enrichmeai.cistern.webflux.error) turns errors into HTTP status codes. \
                        Signal a CisternException subtype through the reactive chain instead, and \
                        add a row to ProblemMapper if the taxonomy is genuinely missing one.""")
                .isEmpty();
    }

    private static boolean mapsErrors(Path file) {
        try {
            String source = Files.readString(file);
            return source.contains(".onErrorResume(")
                    || source.contains(".onErrorMap(")
                    || source.contains(".onErrorReturn(");
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }

    private static Stream<Path> sources() {
        if (!Files.isDirectory(MAIN_SOURCES)) {
            return Stream.of();
        }
        try (Stream<Path> tree = Files.walk(MAIN_SOURCES)) {
            return tree.filter(path -> path.toString().endsWith(".java")).toList().stream();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot walk " + MAIN_SOURCES, e);
        }
    }
}
