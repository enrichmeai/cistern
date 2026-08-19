package com.enrichmeai.cistern.webflux.auth;

import com.enrichmeai.cistern.webflux.CisternProperties;
import com.enrichmeai.cistern.webflux.WebfluxMessage;

import java.net.URI;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Service principals from {@code cistern.auth.service-principals[n]} (T4.0, #88).
 *
 * <p>Built once at startup, and strict about it: an entry whose credential hash does not parse,
 * or two entries sharing a hash (whose credential would then be ambiguous), fails the boot
 * rather than silently authenticating nobody or the wrong one. Two entries may share a WebID —
 * that is one identity with two credentials, which is legitimate (rotation).
 */
public final class ConfiguredServicePrincipalRegistry implements ServicePrincipalRegistry {

    private static final ConfiguredServicePrincipalRegistry EMPTY =
            new ConfiguredServicePrincipalRegistry(List.of());

    private final List<ServicePrincipal> principals;
    private final Set<URI> webIds;

    private ConfiguredServicePrincipalRegistry(List<ServicePrincipal> principals) {
        this.principals = List.copyOf(principals);
        Set<URI> ids = new LinkedHashSet<>();
        principals.forEach(principal -> ids.add(principal.webId()));
        this.webIds = Collections.unmodifiableSet(ids);
    }

    /** From configuration. Never null; empty when nothing is configured. */
    public static ConfiguredServicePrincipalRegistry from(
            List<CisternProperties.ServicePrincipal> configured) {
        Objects.requireNonNull(configured, "configured");
        if (configured.isEmpty()) {
            return EMPTY;
        }
        return of(configured.stream()
                .map(entry -> new ServicePrincipal(
                        entry.webId(), HashedCredential.parse(entry.credentialHash())))
                .toList());
    }

    /** From already-typed principals. */
    public static ConfiguredServicePrincipalRegistry of(List<ServicePrincipal> principals) {
        Objects.requireNonNull(principals, "principals");
        Set<HashedCredential> seen = new HashSet<>();
        for (ServicePrincipal principal : principals) {
            if (!seen.add(principal.credential())) {
                throw new IllegalArgumentException(
                        WebfluxMessage.SERVICE_CREDENTIAL_DUPLICATE.format(principal.webId()));
            }
        }
        return new ConfiguredServicePrincipalRegistry(principals);
    }

    @Override
    public Optional<ServicePrincipal> byCredential(String presented) {
        Objects.requireNonNull(presented, "presented");
        // Every comparison is constant-time on its own; the list is a handful of entries, so
        // which entry matched is not worth hiding by comparing them all.
        return principals.stream()
                .filter(principal -> principal.credential().matches(presented))
                .findFirst();
    }

    @Override
    public Set<URI> webIds() {
        return webIds;
    }
}
