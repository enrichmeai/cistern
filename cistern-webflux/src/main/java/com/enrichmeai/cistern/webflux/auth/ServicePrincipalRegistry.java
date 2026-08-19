package com.enrichmeai.cistern.webflux.auth;

import java.net.URI;
import java.util.Optional;
import java.util.Set;

/**
 * The service principals this pod recognises (T4.0, #88).
 *
 * <p>An interface, because where the principals come from will change: today they are
 * configuration ({@link ConfiguredServicePrincipalRegistry}); once T5.6 provisions pods on
 * demand, an application registered at runtime belongs here too. What does not change is the
 * question asked of it — "whose credential is this?" — and that a registry never returns a
 * secret, only the identity a presented secret proves.
 */
public interface ServicePrincipalRegistry {

    /**
     * The principal whose credential {@code presented} is, if any. Implementations compare in
     * constant time.
     */
    Optional<ServicePrincipal> byCredential(String presented);

    /** Every WebID a service principal can authenticate as. Diagnostics and tests. */
    Set<URI> webIds();

    /** Whether there is anything to resolve — an empty registry is not wired into the chain. */
    default boolean isEmpty() {
        return webIds().isEmpty();
    }
}
