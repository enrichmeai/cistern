package com.enrichmeai.cistern.auth;

import com.enrichmeai.cistern.core.Agent;

import java.net.URI;
import java.util.Objects;
import java.util.Optional;

/**
 * Who a verified Solid-OIDC access token says is calling, and through what (T4.1).
 *
 * <p>Deliberately <em>not</em> an {@link Agent}. Everything here is verified — the signature
 * checked against the issuer's published keys, the claims checked — but two things stand
 * between a verified token and an authenticated request, and neither exists yet:
 *
 * <ol>
 *   <li>the DPoP proof binding this token to the caller's key has not been validated (T4.2),
 *       so a token lifted off the wire would still present as valid; and
 *   <li>the WebID has not been dereferenced to confirm it names {@code issuer} as one of its
 *       {@code solid:oidcIssuer}s (T4.3) — without that check any issuer may mint a token for
 *       any WebID, which is the whole trust model of Solid-OIDC.
 * </ol>
 *
 * <p>So this type carries the material those tickets need and stops short of the conclusion.
 * {@link #toAgent()} exists for them to call once both checks pass; nothing on the request
 * path may call it before that.
 *
 * @param webId      the {@code webid} claim (Solid-OIDC §5)
 * @param client     the {@code client_id} claim when it is an absolute URI, else empty — under
 *                   a client-credentials grant it is typically an opaque identifier rather than
 *                   a Client Identifier Document, and an opaque string names no client we can
 *                   check
 * @param issuer     the {@code iss} the token claims, for T4.3 to hold the WebID against
 * @param thumbprint the {@code cnf.jkt} the DPoP proof must match (RFC 9449 §6.1)
 */
public record SolidOidcIdentity(URI webId, Optional<URI> client, URI issuer, String thumbprint) {

    public SolidOidcIdentity {
        Objects.requireNonNull(webId, "webId");
        Objects.requireNonNull(client, "client");
        Objects.requireNonNull(issuer, "issuer");
        Objects.requireNonNull(thumbprint, "thumbprint");
        if (thumbprint.isBlank()) {
            throw new IllegalArgumentException(AuthMessage.REASON_CONFIRMATION_MISSING.format());
        }
    }

    /**
     * The agent this identity authenticates.
     *
     * <p>Only sound once the DPoP proof has been validated (T4.2) and the WebID has been
     * confirmed to name {@link #issuer()} (T4.3). Calling it earlier authenticates a request on
     * a token nobody proved possession of.
     */
    public Agent toAgent() {
        return Agent.of(webId, client);
    }
}
