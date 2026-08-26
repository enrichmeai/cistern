package com.enrichmeai.cistern.auth;

import java.net.URI;

import reactor.core.publisher.Mono;

/**
 * Whether a WebID authorises an issuer to speak for it (Solid-OIDC §5).
 *
 * <p>An interface so the resolver that composes the three checks does not depend on the one
 * that reaches the network: {@link WebIdIssuerVerifier} is the implementation, and a test can
 * substitute an answer without a socket, a certificate, or a public DNS name. The same seam
 * {@link SolidOidcTokenVerifier.Issuers} draws for key sets, and for the same reason.
 */
@FunctionalInterface
public interface WebIdIssuers {

    /** Whether {@code webId} names {@code issuer}. Never empty, never an error. */
    Mono<WebIdVerdict> verify(URI webId, URI issuer);
}
