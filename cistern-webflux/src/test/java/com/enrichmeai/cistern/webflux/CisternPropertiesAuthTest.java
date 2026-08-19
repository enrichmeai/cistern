package com.enrichmeai.cistern.webflux;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The {@code cistern.auth.*} records (T4.0): defaults, and what is refused at bind time. */
class CisternPropertiesAuthTest {

    private static final URI ISSUER = URI.create("https://id.valuedocs.co.in");

    @Test
    @DisplayName("nothing configured: no issuer, no service principals, and no error")
    void unconfiguredDefaults() {
        CisternProperties properties = new CisternProperties(null, null, null, null, null, null, null);
        assertFalse(properties.auth().oidc().isConfigured());
        assertTrue(properties.auth().servicePrincipals().isEmpty());
    }

    @Test
    @DisplayName("an issuer with audiences and nothing else: webid claim, one minute of skew, discovery")
    void issuerDefaults() {
        var oidc = new CisternProperties.Oidc(ISSUER, Set.of("cistern"), null, null, null, null);
        assertTrue(oidc.isConfigured());
        assertEquals(CisternProperties.Oidc.DEFAULT_WEBID_CLAIM, oidc.webidClaim());
        assertNull(oidc.webidTemplate());
        assertEquals(CisternProperties.Oidc.DEFAULT_CLOCK_SKEW, oidc.clockSkew());
        assertNull(oidc.jwksUri());
    }

    @Test
    @DisplayName("a template switches the default claim off")
    void templateReplacesClaim() {
        var oidc = new CisternProperties.Oidc(
                ISSUER, Set.of("cistern"), null, "{iss}/users/{sub}#me", null, null);
        assertNull(oidc.webidClaim());
        assertEquals("{iss}/users/{sub}#me", oidc.webidTemplate());
    }

    @Test
    @DisplayName("an issuer without audiences is refused: tokens meant for anyone are tokens stolen from anyone")
    void audiencesRequired() {
        assertThrows(IllegalArgumentException.class,
                () -> new CisternProperties.Oidc(ISSUER, Set.of(), null, null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new CisternProperties.Oidc(ISSUER, null, null, null, null, null));
    }

    @Test
    @DisplayName("claim and template together contradict each other")
    void bothMappingsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new CisternProperties.Oidc(
                ISSUER, Set.of("cistern"), "webid", "{iss}/users/{sub}#me", null, null));
    }

    @Test
    @DisplayName("a relative issuer or a negative skew is refused")
    void malformedIssuerRefused() {
        assertThrows(IllegalArgumentException.class, () -> new CisternProperties.Oidc(
                URI.create("id.valuedocs.co.in"), Set.of("cistern"), null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new CisternProperties.Oidc(
                ISSUER, Set.of("cistern"), null, null, Duration.ofSeconds(-1), null));
    }

    @Test
    @DisplayName("a service principal entry needs both halves")
    void servicePrincipalNeedsBothHalves() {
        assertThrows(IllegalArgumentException.class,
                () -> new CisternProperties.ServicePrincipal(null, "sha256:00"));
        assertThrows(IllegalArgumentException.class,
                () -> new CisternProperties.ServicePrincipal(URI.create("https://a.example/#id"), " "));
        var auth = new CisternProperties.Auth(null, List.of(
                new CisternProperties.ServicePrincipal(URI.create("https://a.example/#id"), "sha256:00")));
        assertEquals(1, auth.servicePrincipals().size());
    }
}
