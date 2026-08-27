package com.enrichmeai.cistern.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That {@code cistern.auth.solid.webid.trusted-origins} actually reaches the policy.
 *
 * <p>Worth its own test because it binds differently from everything around it: the rest of
 * the server's configuration goes through {@code @ConfigurationProperties} on
 * {@code CisternProperties}, and this is a {@code @Value} resolved against the Environment. A
 * property that silently fails to bind produces a policy that refuses the origin an operator
 * believes they allowed — which surfaces as an authentication failure, in a subsystem whose
 * failures already look like authentication failures.
 */
@DisplayName("The trusted-origins property reaches the fetch policy")
class TrustedOriginsBindingTest {

    /**
     * The real configuration class, with the properties it needs to stand up.
     *
     * <p>{@code CisternProperties} is bound here rather than stubbed so the test exercises the
     * same binding path the server does — the point is whether a property reaches the policy,
     * and a hand-built bean would prove nothing about that.
     */
    @org.springframework.boot.context.properties.EnableConfigurationProperties(
            com.enrichmeai.cistern.webflux.CisternProperties.class)
    static class Bindings {
    }

    private final ApplicationContextRunner contexts = new ApplicationContextRunner()
            .withUserConfiguration(Bindings.class)
            .withConfiguration(AutoConfigurations.of(CisternSolidOidcConfiguration.class))
            .withPropertyValues(
                    "cistern.auth.solid.enabled=true",
                    "cistern.base-url=http://localhost:3000",
                    "cistern.storage.root=/tmp/cistern-binding-test");

    @Test
    @DisplayName("unset means the strict policy — no origin trusted beyond public HTTPS")
    void unsetTrustsNothing() {
        contexts.run(context -> assertThat(context.getBean(WebIdFetchPolicy.class).trustedOrigins())
                .isEmpty());
    }

    @Test
    @DisplayName("a single origin binds")
    void singleOrigin() {
        contexts.withPropertyValues(
                        "cistern.auth.solid.webid.trusted-origins=http://localhost:3939")
                .run(context -> assertThat(context.getBean(WebIdFetchPolicy.class).trustedOrigins())
                        .containsExactly("http://localhost:3939"));
    }

    @Test
    @DisplayName("two origins bind — the conformance shape, Cistern's own plus the IdP's")
    void twoOrigins() {
        contexts.withPropertyValues("cistern.auth.solid.webid.trusted-origins="
                        + "http://host.docker.internal:3737,http://host.docker.internal:3939")
                .run(context -> assertThat(context.getBean(WebIdFetchPolicy.class).trustedOrigins())
                        .describedAs("a two-origin list once arrived as one string and "
                                + "canonicalised to a single unusable entry")
                        .containsExactlyInAnyOrder(
                                "http://host.docker.internal:3737",
                                "http://host.docker.internal:3939"));
    }

    @Test
    @DisplayName("whitespace around a comma does not become part of the origin")
    void whitespaceTolerated() {
        contexts.withPropertyValues("cistern.auth.solid.webid.trusted-origins="
                        + "http://localhost:3939 , https://idp.internal:8443")
                .run(context -> assertThat(context.getBean(WebIdFetchPolicy.class).trustedOrigins())
                        .containsExactlyInAnyOrder(
                                "http://localhost:3939", "https://idp.internal:8443"));
    }

    @Test
    @DisplayName("an origin that is not scheme://host fails the boot rather than binding to nothing")
    void malformedOriginFailsFast() {
        contexts.withPropertyValues("cistern.auth.solid.webid.trusted-origins=not-an-origin")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("an empty value is no origins, not one blank origin")
    void emptyValueIsEmptySet() {
        contexts.withPropertyValues("cistern.auth.solid.webid.trusted-origins=")
                .run(context -> assertThat(context.getBean(WebIdFetchPolicy.class).trustedOrigins())
                        .describedAs("a blank entry would be an origin nothing can match")
                        .isEmpty());
    }
}
