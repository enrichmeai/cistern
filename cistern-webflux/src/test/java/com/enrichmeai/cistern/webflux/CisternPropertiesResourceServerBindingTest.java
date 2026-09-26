package com.enrichmeai.cistern.webflux;

import java.net.URI;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That {@code cistern.auth.resource-identifier} and {@code cistern.auth.authorization-server}
 * actually reach {@link OAuthProtectedResource} — from the dotted property form and from the
 * hyphen-stripped environment form Spring's relaxed binding uses.
 *
 * <p>Worth its own test for the reason CLAUDE.md gives: relaxed binding removes the hyphens
 * inside a property word rather than converting them, so {@code cistern.auth.resource-identifier}
 * is {@code CISTERN_AUTH_RESOURCEIDENTIFIER} and the intuitive
 * {@code CISTERN_AUTH_RESOURCE_IDENTIFIER} binds <em>nothing</em>, silently — which for a value
 * that decides what a token's audience must be is a security-posture change that would surface
 * only as an authentication failure. Both are properties that change security posture, so both
 * get a binding test (CLAUDE.md, "Configuration binding").
 */
@DisplayName("The resource-server properties reach the metadata, dotted and env forms")
class CisternPropertiesResourceServerBindingTest {

    private static final String BASE = "https://pod.example";
    private static final String RESOURCE = "https://pod.example:8443";
    private static final String AUTH_SERVER = "https://auth.example/realms/pod";

    @EnableConfigurationProperties(CisternProperties.class)
    static class Bindings {
    }

    private final ApplicationContextRunner contexts = new ApplicationContextRunner()
            .withUserConfiguration(Bindings.class)
            .withPropertyValues("cistern.base-url=" + BASE, "cistern.storage.root=/tmp/cistern-prm-binding");

    @Test
    @DisplayName("unset: the resource identifier defaults to cistern.base-url, no authorization server")
    void defaults() {
        contexts.run(context -> {
            OAuthProtectedResource resource = context.getBean(CisternProperties.class).protectedResource();
            assertThat(resource.resource()).isEqualTo(URI.create(BASE));
            assertThat(resource.authorizationServer()).isEmpty();
        });
    }

    @Test
    @DisplayName("the dotted property form binds both values")
    void dottedForm() {
        contexts.withPropertyValues(
                        "cistern.auth.resource-identifier=" + RESOURCE,
                        "cistern.auth.authorization-server=" + AUTH_SERVER)
                .run(context -> {
                    OAuthProtectedResource resource =
                            context.getBean(CisternProperties.class).protectedResource();
                    assertThat(resource.resource()).isEqualTo(URI.create(RESOURCE));
                    assertThat(resource.authorizationServer()).isEqualTo(Optional.of(URI.create(AUTH_SERVER)));
                });
    }

    @Test
    @DisplayName("the hyphen-stripped environment form binds — the trap CLAUDE.md names")
    void environmentForm() {
        contexts.withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                        new SystemEnvironmentPropertySource("t6.4-systemEnvironment", Map.of(
                                "CISTERN_AUTH_RESOURCEIDENTIFIER", RESOURCE,
                                "CISTERN_AUTH_AUTHORIZATIONSERVER", AUTH_SERVER))))
                .run(context -> {
                    OAuthProtectedResource resource =
                            context.getBean(CisternProperties.class).protectedResource();
                    assertThat(resource.resource())
                            .describedAs("CISTERN_AUTH_RESOURCEIDENTIFIER must bind the resource identifier")
                            .isEqualTo(URI.create(RESOURCE));
                    assertThat(resource.authorizationServer())
                            .describedAs("CISTERN_AUTH_AUTHORIZATIONSERVER must bind the authorization server")
                            .isEqualTo(Optional.of(URI.create(AUTH_SERVER)));
                });
    }
}
