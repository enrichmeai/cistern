package com.enrichmeai.cistern.webflux;

import java.net.URI;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The protected resource metadata document (RFC 9728, T6.4): every field it must carry, its
 * one media type, where it is served, and the identifier rules that decide whether it can ever
 * match a token's audience. Asserted from the typed value, then from its JSON projection.
 */
@DisplayName("OAuth 2.0 Protected Resource Metadata — RFC 9728")
class ProtectedResourceMetadataTest {

    private static final String BASE = "https://pod.example";
    private static final URI ISSUER = URI.create("https://id.example/realms/pod");

    private static ProtectedResourceMetadata metadata(String baseUrl, CisternProperties.Auth auth) {
        return new ProtectedResourceMetadata(properties(baseUrl, auth));
    }

    private static CisternProperties properties(String baseUrl, CisternProperties.Auth auth) {
        return new CisternProperties(baseUrl, null, null,
                new CisternProperties.Owner(URI.create("https://pod.example/owner#me"), null),
                auth, null, null, null);
    }

    private static CisternProperties.Auth auth(URI resourceIdentifier, URI authorizationServer) {
        var oidc = new CisternProperties.Oidc(ISSUER, java.util.Set.of("cistern"), null, null, null, null);
        return new CisternProperties.Auth(oidc, null, authorizationServer, resourceIdentifier, null);
    }

    // ---- the document's members --------------------------------------------------------------

    @Test
    @DisplayName("every RFC 9728 field is present and typed; the authorization server defaults to the issuer")
    void everyFieldPresent() {
        Map<String, Object> json = metadata(BASE, auth(null, null)).document().toJson();

        assertEquals(BASE, json.get("resource"), "resource is the identifier");
        assertEquals(List.of(ISSUER.toString()), json.get("authorization_servers"),
                "authorization_servers defaults to the OIDC issuer");
        assertEquals(List.of("webid"), json.get("scopes_supported"), "the Solid-OIDC scope");
        assertEquals(List.of("header"), json.get("bearer_methods_supported"), "header only");
        assertTrue(json.containsKey("resource_documentation"), "resource_documentation present");
        assertTrue(json.get("resource_documentation") instanceof String, "documentation is a URL string");
    }

    @Test
    @DisplayName("resource equals the configured identifier, verbatim — the aud a token must carry")
    void resourceEqualsConfiguredIdentifier() {
        URI identifier = URI.create("https://pod.example:8443");
        Map<String, Object> json = metadata(BASE, auth(identifier, null)).document().toJson();
        assertEquals(identifier.toString(), json.get("resource"));
    }

    @Test
    @DisplayName("a configured authorization server overrides the issuer")
    void authorizationServerOverridesIssuer() {
        URI as = URI.create("https://auth.example");
        Map<String, Object> json = metadata(BASE, auth(null, as)).document().toJson();
        assertEquals(List.of(as.toString()), json.get("authorization_servers"));
    }

    @Test
    @DisplayName("no issuer and no authorization server: the member is omitted, never invented")
    void noAuthorizationServerOmitsTheMember() {
        var auth = new CisternProperties.Auth(null, null, null, null, null);
        Map<String, Object> json = metadata(BASE, auth).document().toJson();
        assertFalse(json.containsKey("authorization_servers"),
                "an invented server would send a client to one that never heard of this pod");
    }

    // ---- where it lives (RFC 9728 §3) --------------------------------------------------------

    @Test
    @DisplayName("§3: an origin publishes at /.well-known/oauth-protected-resource")
    void metadataUrlForAnOrigin() {
        ProtectedResourceMetadata metadata = metadata(BASE, auth(null, null));
        assertEquals("/.well-known/oauth-protected-resource", metadata.endpoint().path());
        assertEquals(URI.create(BASE + "/.well-known/oauth-protected-resource"), metadata.url());
    }

    @Test
    @DisplayName("§3: a path component is appended after the well-known suffix, terminating slash removed")
    void metadataUrlForAPathComponent() {
        String base = "https://host.example/firms/acme";
        ProtectedResourceMetadata metadata = metadata(base, auth(URI.create(base), null));
        assertEquals("/.well-known/oauth-protected-resource/firms/acme", metadata.endpoint().path());
        assertEquals(URI.create("https://host.example/.well-known/oauth-protected-resource/firms/acme"),
                metadata.url());
    }

    // ---- the identifier rules (RFC 9728 §1.2, RFC 8707 §2) -----------------------------------

    @Test
    @DisplayName("a fragment or a query in the resource identifier is refused at bind time")
    void malformedIdentifierRefused() {
        // Validated at bind time — CisternProperties calls OAuthProtectedResource.of.
        assertThrows(IllegalArgumentException.class,
                () -> properties(BASE, auth(URI.create("https://pod.example/#x"), null)));
        assertThrows(IllegalArgumentException.class, () -> new OAuthProtectedResource(
                URI.create("https://pod.example/?q=1"), java.util.Optional.empty(),
                OAuthProtectedResource.DEFAULT_DOCUMENTATION));
        assertThrows(IllegalArgumentException.class, () -> new OAuthProtectedResource(
                URI.create("pod.example"), java.util.Optional.empty(),
                OAuthProtectedResource.DEFAULT_DOCUMENTATION));
    }
}
