package com.enrichmeai.cistern.webflux;

import com.enrichmeai.cistern.core.Representation;
import com.enrichmeai.cistern.core.ResourceIdentifier;
import com.enrichmeai.cistern.core.ResourceStore;
import com.enrichmeai.cistern.wac.AclResource;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ApplicationArguments;
import reactor.core.publisher.Mono;

/**
 * Gives a fresh pod a root ACL granting its owner full access.
 *
 * <p>Without this, enforcement makes the server inert rather than secure: WAC denies by
 * default, the root has no ACL, so every request — the owner's included — is refused and there
 * is no way in to write the ACL that would let anyone in. The bootstrap has to happen
 * server-side, once, from configuration.
 *
 * <p>WAC requires it independently: "The ACL resource of the root container MUST include an
 * Authorization allowing the {@code acl:Control} access privilege."
 *
 * <p>Idempotent, and deliberately <strong>never overwrites</strong>. Rewriting the root ACL on
 * every boot would silently undo any narrowing the owner had since applied — a restart is not
 * a request to reset permissions.
 */
public final class OwnerPodSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(OwnerPodSeeder.class);

    private final ResourceStore store;
    private final CisternProperties properties;

    public OwnerPodSeeder(ResourceStore store, CisternProperties properties) {
        this.store = Objects.requireNonNull(store, "store");
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    @Override
    public void run(ApplicationArguments args) {
        CisternProperties.Owner owner = properties.owner();
        if (!owner.isConfigured()) {
            log.warn(WebfluxMessage.NO_OWNER_CONFIGURED.format());
            return;
        }
        // Blocking is acceptable exactly here: ApplicationRunner is startup, not a request
        // path, and the server must not begin serving before its root ACL exists.
        seed(owner.webId()).block();
    }

    private Mono<Void> seed(URI ownerWebId) {
        ResourceIdentifier root = new ResourceIdentifier(URI.create(properties.baseUrl() + "/"));
        ResourceIdentifier rootAcl = AclResource.of(root);

        return store.exists(rootAcl)
                .flatMap(exists -> exists
                        ? Mono.<Void>empty()
                        : store.put(rootAcl, ownerAcl(root, ownerWebId))
                                .doOnSuccess(stored ->
                                        log.info(WebfluxMessage.SEEDED_ROOT_ACL.format(
                                                rootAcl.uri(), ownerWebId)))
                                .then());
    }

    /**
     * Full access for the owner, on the root and everything under it — {@code acl:accessTo}
     * for the root itself and {@code acl:default} so descendants inherit, because the two are
     * separate statements and granting only the first would leave every child unreachable.
     *
     * <p>Nothing is granted to {@code foaf:Agent}: a new pod is private until its owner says
     * otherwise. That is the whole point of the exercise, and it is what makes an anonymous
     * {@code DELETE} return 401 instead of 204.
     */
    private static Representation ownerAcl(ResourceIdentifier root, URI ownerWebId) {
        String turtle = """
                @prefix acl: <http://www.w3.org/ns/auth/acl#> .

                <#owner> a acl:Authorization ;
                    acl:agent <%s> ;
                    acl:accessTo <%s> ;
                    acl:default <%s> ;
                    acl:mode acl:Read, acl:Write, acl:Append, acl:Control .
                """.formatted(ownerWebId, root.uri(), root.uri());
        return new Representation("text/turtle", turtle.getBytes(StandardCharsets.UTF_8));
    }
}
