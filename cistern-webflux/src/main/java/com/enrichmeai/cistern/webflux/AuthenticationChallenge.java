package com.enrichmeai.cistern.webflux;

import java.net.URI;

import org.springframework.stereotype.Component;

/**
 * The {@code WWW-Authenticate} value every 401 carries (RFC 9110 §11.6.1), built once from
 * configuration so that the filter and every other door emit one challenge.
 *
 * <p>Two challenges, both naming where a client obtains a credential. {@code Bearer} for an
 * application's own token and {@code DPoP} with its accepted algorithms for Solid-OIDC
 * (RFC 9449 §7.1) were T5.3's; T6.4 adds RFC 9728 §5.1's {@code resource_metadata} parameter
 * to each — "the URL of the protected resource metadata" — which the MCP authorization
 * specification makes mandatory: "MCP servers MUST use the HTTP header {@code WWW-Authenticate}
 * when returning a 401 Unauthorized to indicate the location of the resource server metadata
 * URL". It is on both challenges because both schemes name the same resource, and a client
 * following either one should not have to read the other.
 *
 * <p>The MCP client reads the first scheme token and then matches the parameter anywhere in
 * the field, so {@code Bearer} comes first and the parameter appears verbatim quoted; the
 * template in {@link HttpConstants#WWW_AUTHENTICATE_CHALLENGE_TEMPLATE} fixes both.
 */
@Component
public final class AuthenticationChallenge {

    private final String headerValue;

    public AuthenticationChallenge(CisternProperties properties) {
        URI metadata = properties.protectedResource().metadataUrl();
        this.headerValue = HttpConstants.WWW_AUTHENTICATE_CHALLENGE_TEMPLATE.formatted(metadata, metadata);
    }

    /** The complete {@code WWW-Authenticate} field value. */
    public String headerValue() {
        return headerValue;
    }
}
