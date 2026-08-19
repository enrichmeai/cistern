package com.enrichmeai.cistern.webflux;

import java.net.URI;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The enforcement guard at bind time (T7.7, #94): a credential source configured without an
 * owner is refused, naming the fix; an owner named without a token is the production shape and
 * is accepted; nothing configured is still accepted (and made loud at boot, which
 * {@link EnforcementGuardBootTest} covers).
 */
class EnforcementGuardTest {

    private static final URI OWNER = URI.create("https://valuedocs.co.in/profile#admin");
    private static final URI ISSUER = URI.create("https://id.valuedocs.co.in/realms/valuedocs");
    private static final CisternProperties.Oidc OIDC =
            new CisternProperties.Oidc(ISSUER, Set.of("cistern"), null, null, null, null);
    private static final List<CisternProperties.ServicePrincipal> PRINCIPALS = List.of(
            new CisternProperties.ServicePrincipal(
                    URI.create("https://valuedocs.co.in/apps/legal#id"), "sha256:00"));

    private static CisternProperties bind(CisternProperties.Owner owner, CisternProperties.Auth auth) {
        return new CisternProperties(null, null, null, owner, auth, null, null);
    }

    @Test
    @DisplayName("an OIDC issuer without cistern.owner.web-id is refused, naming the property and the fix")
    void issuerWithoutOwnerIsRefused() {
        var refused = assertThrows(IllegalArgumentException.class,
                () -> bind(null, new CisternProperties.Auth(OIDC, null)));
        assertEquals(WebfluxMessage.ENFORCEMENT_REQUIRES_OWNER.format(
                CisternProperties.Auth.CredentialSource.OIDC_ISSUER.property()), refused.getMessage());
    }

    @Test
    @DisplayName("service principals without cistern.owner.web-id are refused")
    void servicePrincipalsWithoutOwnerAreRefused() {
        var refused = assertThrows(IllegalArgumentException.class,
                () -> bind(null, new CisternProperties.Auth(null, PRINCIPALS)));
        assertEquals(WebfluxMessage.ENFORCEMENT_REQUIRES_OWNER.format(
                CisternProperties.Auth.CredentialSource.SERVICE_PRINCIPALS.property()), refused.getMessage());
    }

    @Test
    @DisplayName("both sources without an owner: the message names both, in declaration order")
    void bothSourcesAreNamed() {
        var refused = assertThrows(IllegalArgumentException.class,
                () -> bind(null, new CisternProperties.Auth(OIDC, PRINCIPALS)));
        assertEquals(WebfluxMessage.ENFORCEMENT_REQUIRES_OWNER.format(
                CisternProperties.Auth.CredentialSource.OIDC_ISSUER.property() + " and "
                        + CisternProperties.Auth.CredentialSource.SERVICE_PRINCIPALS.property()),
                refused.getMessage());
    }

    @Test
    @DisplayName("an owner's WebID with no token and an OIDC issuer is the production shape, and binds")
    void ownerWithoutTokenIsTheProductionShape() {
        CisternProperties properties = assertDoesNotThrow(
                () -> bind(new CisternProperties.Owner(OWNER, null), new CisternProperties.Auth(OIDC, PRINCIPALS)));
        assertTrue(properties.owner().isNamed(), "enforcement is keyed on the WebID");
        assertFalse(properties.owner().hasLocalCredential(), "no local token: the owner authenticates via OIDC");
    }

    @Test
    @DisplayName("a blank token is no token, and a token without a WebID names nobody")
    void blankTokenIsNoToken() {
        assertFalse(new CisternProperties.Owner(OWNER, " ").hasLocalCredential());
        assertTrue(new CisternProperties.Owner(OWNER, "s3cret").hasLocalCredential());
        assertFalse(new CisternProperties.Owner(null, "s3cret").isNamed());
        assertFalse(new CisternProperties.Owner(null, "s3cret").hasLocalCredential());
    }

    @Test
    @DisplayName("nothing configured binds: a laptop pod with no owner is allowed (and loud at boot)")
    void nothingConfiguredBinds() {
        CisternProperties properties = assertDoesNotThrow(() -> bind(null, null));
        assertFalse(properties.owner().isNamed());
        assertTrue(properties.auth().credentialSources().isEmpty());
    }

    @Test
    @DisplayName("credentialSources() reports exactly what is configured")
    void credentialSourcesReflectConfiguration() {
        assertEquals(Set.of(), new CisternProperties.Auth(null, null).credentialSources());
        assertEquals(Set.of(CisternProperties.Auth.CredentialSource.OIDC_ISSUER),
                new CisternProperties.Auth(OIDC, null).credentialSources());
        assertEquals(Set.of(CisternProperties.Auth.CredentialSource.SERVICE_PRINCIPALS),
                new CisternProperties.Auth(null, PRINCIPALS).credentialSources());
        assertEquals(Set.of(CisternProperties.Auth.CredentialSource.OIDC_ISSUER,
                        CisternProperties.Auth.CredentialSource.SERVICE_PRINCIPALS),
                new CisternProperties.Auth(OIDC, PRINCIPALS).credentialSources());
    }
}
