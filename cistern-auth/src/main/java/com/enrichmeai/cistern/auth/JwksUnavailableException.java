package com.enrichmeai.cistern.auth;

/**
 * The issuer's key set (or the discovery document that locates it) could not be obtained.
 *
 * <p>Not a {@code CisternException}: that hierarchy is sealed and maps to HTTP status codes,
 * and this never reaches the error mapper — {@link JwtVerifier} turns it into a verdict, and
 * the request goes on as anonymous. It is its own type so that the verifier can recognise the
 * one failure it expects and let anything else be the bug it would be.
 */
public final class JwksUnavailableException extends RuntimeException {

    public JwksUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public JwksUnavailableException(String message) {
        super(message);
    }
}
