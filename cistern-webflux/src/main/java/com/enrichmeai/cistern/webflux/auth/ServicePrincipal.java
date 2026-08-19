package com.enrichmeai.cistern.webflux.auth;

import com.enrichmeai.cistern.webflux.WebfluxMessage;

import java.net.URI;
import java.util.Objects;

/**
 * An application that authenticates as itself (T4.0, #88; the v1 ruling on #89).
 *
 * <p>ValueDocs' legal and tax applications are two of these. Each has its own WebID and its
 * own credential, so a grant to {@code acl:agent <…/apps/legal#id>} names the legal
 * application and no other, and revoking one credential leaves the other working. That is what
 * "the application is a principal" (docs/INTEGRATION.md §2) means concretely: the WAC engine
 * sees an {@link com.enrichmeai.cistern.core.Agent} with this WebID, exactly as it would see a
 * person, and evaluates the same rules.
 *
 * @param webId      the identity the application authenticates as; absolute
 * @param credential the digest of its secret — the secret itself is never held
 */
public record ServicePrincipal(URI webId, HashedCredential credential) {

    public ServicePrincipal {
        Objects.requireNonNull(webId, "webId");
        Objects.requireNonNull(credential, "credential");
        if (!webId.isAbsolute()) {
            throw new IllegalArgumentException(
                    WebfluxMessage.SERVICE_PRINCIPAL_WEBID_INVALID.format(webId));
        }
    }
}
