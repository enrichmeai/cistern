package com.enrichmeai.cistern.auth;

import com.nimbusds.jwt.JWTClaimsSet;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Optional;

/**
 * The client an access token was issued to, when it names one a policy could match (T6.5,
 * the client half of {@link com.enrichmeai.cistern.core.Agent}).
 *
 * <p>Two claims can carry it, and which one does depends on the issuer, not on the
 * specification a resource server would prefer. RFC 9068 §2.2 puts {@code client_id} on a JWT
 * access token, and a Solid identity provider does exactly that — the CSS 7.2.0 capture in
 * {@code fixtures/css} carries {@code client_id} and no {@code azp}. OpenID Connect Core §2
 * defines {@code azp}, the authorized party, as "the OAuth 2.0 Client ID of this party" on the
 * ID token, and a general-purpose issuer carries the same value there on the access token as
 * well: the Keycloak 26.7.1 captures in {@code fixtures/keycloak} and
 * {@code fixtures/keycloak-delegation} show a <em>user's</em> access token with {@code azp} and
 * no {@code client_id}, and only a client-credentials token with both. A resolver that read
 * {@code client_id} alone would therefore see no client on any Keycloak-issued user token, and
 * a delegation constrained to a client could never bind on the plain-OIDC path. So the bearer
 * path reads {@link #BEARER_CLAIMS}: {@code client_id} when present, else {@code azp}. The
 * Solid-OIDC path keeps to {@link #SOLID_OIDC_CLAIMS}, as its capture showed.
 *
 * <p>Only an absolute URI counts. A client-credentials grant from a Solid IdP puts its opaque
 * credential id here, and Keycloak's default client ids are short names; neither names a
 * client a policy could be written against, so both read as no client — the reason
 * {@code Agent.client()} is an {@code Optional<URI>} rather than a {@code String}. Reading a
 * client is never a way in: the WAC engine uses it only to narrow (AD-15), so the most a
 * misread could do is refuse.
 */
final class ClientIdentifier {

    /** RFC 9068 §2.2: the client identifier on a JWT access token. */
    static final String CLIENT_ID_CLAIM = "client_id";

    /**
     * OpenID Connect Core §2: the authorized party — the client id — which a general-purpose
     * issuer carries on a user's access token where RFC 9068 would have {@code client_id}.
     */
    static final String AUTHORIZED_PARTY_CLAIM = "azp";

    /** The claims a plain OIDC bearer token's client is read from, in order of preference. */
    static final List<String> BEARER_CLAIMS = List.of(CLIENT_ID_CLAIM, AUTHORIZED_PARTY_CLAIM);

    /** The one claim a Solid-OIDC access token carries its client in (RFC 9068 via Solid-OIDC). */
    static final List<String> SOLID_OIDC_CLAIMS = List.of(CLIENT_ID_CLAIM);

    private ClientIdentifier() {
        // static helper
    }

    /**
     * The client the first of {@code claimNames} present in {@code claims} names, when it is
     * an absolute URI; empty when no claim is present, or the first present one is opaque.
     *
     * <p>The first present claim decides: a token that carries {@code client_id} is not also
     * read at {@code azp}, since an issuer that emits both emits the same value.
     */
    static Optional<URI> from(JWTClaimsSet claims, List<String> claimNames) {
        for (String name : claimNames) {
            Object value = claims.getClaim(name);
            if (value instanceof String text && !text.isBlank()) {
                return absolute(text);
            }
        }
        return Optional.empty();
    }

    private static Optional<URI> absolute(String value) {
        try {
            URI uri = new URI(value);
            return uri.isAbsolute() ? Optional.of(uri) : Optional.empty();
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
    }
}
