package com.enrichmeai.cistern.auth;

import com.nimbusds.jose.util.JSONObjectUtils;

import java.net.URI;
import java.text.ParseException;
import java.util.Map;
import java.util.Objects;

/**
 * The part of an OpenID Provider's discovery document (OIDC Discovery §3) this pod reads:
 * where the keys are.
 *
 * <p>Only {@code jwks_uri} for now. The document has forty-odd members; a record with the one
 * we consume is honest about what is actually depended on, and adding a member is adding a
 * component here.
 *
 * @param jwksUri OIDC Discovery §3: "URL of the OP's JSON Web Key Set document"
 */
public record OidcProviderMetadata(URI jwksUri) {

    /** The discovery document member naming the key set. */
    static final String JWKS_URI = "jwks_uri";

    public OidcProviderMetadata {
        Objects.requireNonNull(jwksUri, "jwksUri");
    }

    /**
     * Parse a discovery document.
     *
     * @param json the document body
     * @param from where it was fetched, for the message when it is unusable
     * @throws JwksUnavailableException if the body is not JSON or lacks {@code jwks_uri}
     */
    public static OidcProviderMetadata parse(String json, URI from) {
        try {
            Map<String, Object> document = JSONObjectUtils.parse(json);
            URI jwksUri = JSONObjectUtils.getURI(document, JWKS_URI);
            if (jwksUri == null) {
                throw new JwksUnavailableException(
                        AuthMessage.DISCOVERY_MISSING_JWKS_URI.format(from));
            }
            return new OidcProviderMetadata(jwksUri);
        } catch (ParseException e) {
            throw new JwksUnavailableException(
                    AuthMessage.DISCOVERY_FAILED.format(from, e.getMessage()), e);
        }
    }
}
