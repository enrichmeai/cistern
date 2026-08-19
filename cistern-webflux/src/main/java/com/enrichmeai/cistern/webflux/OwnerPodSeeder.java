package com.enrichmeai.cistern.webflux;

import com.enrichmeai.cistern.core.ResourceIdentifier;
import com.enrichmeai.cistern.wac.PodProvisioned;
import com.enrichmeai.cistern.wac.PodProvisioner;
import com.enrichmeai.cistern.wac.PodSpec;

import java.net.URI;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;

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
 *
 * <p>Since T5.6 this is the single-owner face of {@link PodProvisioner}: the storage root is a
 * pod like any other, owned by {@code cistern.owner.web-id}. What is written, and the rule that
 * an existing ACL is left alone, live there; this class only says <em>which</em> pod and
 * <em>who</em>. Further pods with further owners are {@link PodSeeder}'s job.
 */
public final class OwnerPodSeeder implements ApplicationRunner, Ordered {

    /**
     * Before {@link PodSeeder}: the storage root is settled first, so the log reads top-down and
     * the root container is committed by its own seeder rather than as a side effect of a
     * child's. Nothing else at startup depends on this ordering.
     */
    static final int ORDER = Ordered.HIGHEST_PRECEDENCE;

    /** The storage root as a path under the base URL: the one pod {@code cistern.owner} names. */
    private static final String STORAGE_ROOT_PATH = "/";

    private static final Logger log = LoggerFactory.getLogger(OwnerPodSeeder.class);

    private final PodProvisioner provisioner;
    private final CisternProperties properties;

    public OwnerPodSeeder(PodProvisioner provisioner, CisternProperties properties) {
        this.provisioner = Objects.requireNonNull(provisioner, "provisioner");
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    @Override
    public int getOrder() {
        return ORDER;
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
        PodProvisioned outcome = provisioner.provision(rootPod(owner.webId())).block();
        if (outcome instanceof PodProvisioned.Created created) {
            log.info(WebfluxMessage.SEEDED_ROOT_ACL.format(created.acl().uri(), owner.webId()));
        }
    }

    private PodSpec rootPod(URI ownerWebId) {
        ResourceIdentifier root = new ResourceIdentifier(URI.create(properties.baseUrl() + STORAGE_ROOT_PATH));
        return new PodSpec(root, ownerWebId);
    }
}
