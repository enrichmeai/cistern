package com.enrichmeai.cistern.webflux;

import static org.assertj.core.api.Assertions.assertThat;

import com.enrichmeai.cistern.wac.DelegationMode;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * That {@code cistern.wac.delegation.enabled} binds — property form and environment form —
 * and that unset means off (T6.5; AD-16, AD-18).
 *
 * <p>Its own test because the flag changes security posture in a direction that fails
 * silently: a flag that did not bind would leave every {@code cistern:client} constraint
 * unenforced while the ACLs read as constrained, and nothing on the request path would say
 * so. The environment form is exercised through a real {@link SystemEnvironmentPropertySource}
 * under a name Spring treats as the environment — it applies the environment-variable mapper
 * only to a source named {@code systemEnvironment} or ending in {@code -systemEnvironment} —
 * so the relaxed-binding path the README warns about ({@code CISTERN_AUTH_SERVICEPRINCIPALS_0_WEBID},
 * hyphens dropped) is the one under test.
 */
@DisplayName("cistern.wac.delegation.enabled binds, and defaults to off")
class DelegationFlagBindingTest {

    @EnableConfigurationProperties(CisternProperties.class)
    static class Bindings {
    }

    /** The suffix that makes Spring map a property source's names as environment variables. */
    private static final String ENVIRONMENT_SOURCE = "test-systemEnvironment";

    private final ApplicationContextRunner contexts = new ApplicationContextRunner()
            .withUserConfiguration(Bindings.class);

    private static DelegationMode mode(org.springframework.context.ApplicationContext context) {
        return context.getBean(CisternProperties.class).wac().delegation().mode();
    }

    @Test
    @DisplayName("unset: delegation is off — plain WAC, invisible to the harness")
    void unsetIsOff() {
        contexts.run(context -> assertThat(mode(context)).isEqualTo(DelegationMode.DISABLED));
    }

    @Test
    @DisplayName("cistern.wac.delegation.enabled=true binds")
    void propertyFormBinds() {
        contexts.withPropertyValues("cistern.wac.delegation.enabled=true")
                .run(context -> assertThat(mode(context)).isEqualTo(DelegationMode.ENABLED));
    }

    @Test
    @DisplayName("cistern.wac.delegation.enabled=false is off, explicitly")
    void propertyFormOff() {
        contexts.withPropertyValues("cistern.wac.delegation.enabled=false")
                .run(context -> assertThat(mode(context)).isEqualTo(DelegationMode.DISABLED));
    }

    @Test
    @DisplayName("CISTERN_WAC_DELEGATION_ENABLED=true binds through the environment")
    void environmentFormBinds() {
        contexts.withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                        new SystemEnvironmentPropertySource(ENVIRONMENT_SOURCE,
                                Map.of("CISTERN_WAC_DELEGATION_ENABLED", "true"))))
                .run(context -> assertThat(mode(context)).isEqualTo(DelegationMode.ENABLED));
    }

    @Test
    @DisplayName("a misspelt environment name binds nothing — off, not an error")
    void misspeltEnvironmentNameBindsNothing() {
        contexts.withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                        new SystemEnvironmentPropertySource(ENVIRONMENT_SOURCE,
                                Map.of("CISTERN_WAC_DELEGATION_ENABLE", "true"))))
                .run(context -> assertThat(mode(context))
                        .describedAs("the trap the README documents: a near-miss name is silently ignored")
                        .isEqualTo(DelegationMode.DISABLED));
    }
}
