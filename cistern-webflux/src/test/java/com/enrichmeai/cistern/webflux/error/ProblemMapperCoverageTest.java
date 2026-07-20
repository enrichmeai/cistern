package com.enrichmeai.cistern.webflux.error;

import static org.assertj.core.api.Assertions.assertThat;

import com.enrichmeai.cistern.core.CisternException;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Keeps {@link ProblemMapper}'s registry honest. {@code CisternException} is abstract but not
 * sealed, so the compiler cannot prove the table covers every subtype; this does it by
 * reflection instead. Adding a subtype in {@code cistern-core} without a status mapping fails
 * here rather than silently becoming a 500 in production.
 */
class ProblemMapperCoverageTest {

    /**
     * Resolved through the 401/403 seam rather than the class-keyed registry, because its
     * status depends on the request as well as the exception type.
     */
    private static final List<Class<?>> RESOLVED_BY_SEAM = List.of(CisternException.AccessDenied.class);

    @Test
    @DisplayName("every CisternException subtype has a status mapping")
    void everySubtypeIsMapped() {
        List<Class<?>> subtypes = Arrays.stream(CisternException.class.getDeclaredClasses())
                .filter(CisternException.class::isAssignableFrom)
                .filter(type -> !Modifier.isAbstract(type.getModifiers()))
                .toList();

        assertThat(subtypes)
                .as("reflection found no CisternException subtypes — the check would pass vacuously")
                .isNotEmpty();

        List<Class<?>> unmapped = subtypes.stream()
                .filter(type -> !ProblemMapper.DOMAIN_PROBLEMS.containsKey(type))
                .filter(type -> !RESOLVED_BY_SEAM.contains(type))
                .toList();

        assertThat(unmapped)
                .as("add a row to ProblemMapper.DOMAIN_PROBLEMS (and a ProblemType) for each")
                .isEmpty();
    }

    @Test
    @DisplayName("no problem type is registered under two exception classes")
    void mappingsAreDistinct() {
        assertThat(ProblemMapper.DOMAIN_PROBLEMS.values())
                .doesNotHaveDuplicates();
    }
}
