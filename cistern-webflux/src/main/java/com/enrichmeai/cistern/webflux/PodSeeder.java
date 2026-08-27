package com.enrichmeai.cistern.webflux;

import com.enrichmeai.cistern.wac.PodProvisioned;
import com.enrichmeai.cistern.wac.PodProvisioner;
import com.enrichmeai.cistern.wac.PodSpec;

import java.net.URI;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Provisions the pods {@code cistern.pods.seed[]} lists, at boot (T5.6, #90).
 *
 * <p>Each entry is a root container and an owner; each becomes a pod through
 * {@link PodProvisioner} — container plus owner ACL, {@code acl:accessTo} and
 * {@code acl:default}, all four modes, nothing to the public. Idempotent by construction: a
 * root that already has an ACL is reported as {@link PodProvisioned.AlreadyExists} and left
 * exactly as it is, so a restart provisions nothing and resets nothing.
 *
 * <p>Pods are provisioned one after another, in configuration order, so that a parent listed
 * before its child is committed first and the log reads the way the configuration does. A
 * failure stops the sequence and fails the start: a server that could not provision the pods
 * it was told to must not come up looking as though it had.
 *
 * <p>Runs after {@link OwnerPodSeeder}, which owns the storage root; see {@link #ORDER}.
 */
public final class PodSeeder implements ApplicationRunner, Ordered {

    /** Immediately after the owner's root: the storage root before any root beneath it. */
    static final int ORDER = OwnerPodSeeder.ORDER + 1;

    private static final Logger log = LoggerFactory.getLogger(PodSeeder.class);

    private final PodProvisioner provisioner;
    private final CisternProperties properties;

    public PodSeeder(PodProvisioner provisioner, CisternProperties properties) {
        this.provisioner = Objects.requireNonNull(provisioner, "provisioner");
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<PodSpec> pods = properties.pods().specsUnder(properties.baseUrl());
        if (pods.isEmpty()) {
            return;
        }
        // Blocking is acceptable exactly here: ApplicationRunner is startup, not a request
        // path, and the server must not begin serving before the pods it was told to
        // provision exist. concatMap, not flatMap: in order, one at a time.
        // specsUnder returns the pods in configuration order, so index i of each list is the
        // same pod. Paired rather than merged into PodSpec because the issuer is a seeding
        // instruction, not part of what a pod is.
        List<CisternProperties.Seed> seeds = properties.pods().seed();
        Flux.range(0, pods.size())
                .concatMap(index -> seed(pods.get(index), seeds.get(index).oidcIssuer()))
                .then()
                .block();
    }

    private Mono<?> seed(PodSpec spec, URI oidcIssuer) {
        return provisioner.provision(spec)
                .doOnNext(outcome -> {
                    switch (outcome) {
                        case PodProvisioned.Created created -> log.info(WebfluxMessage.SEEDED_POD
                                .format(created.root().uri(), created.acl().uri(), spec.ownerWebId()));
                        case PodProvisioned.AlreadyExists existing -> log.debug(
                                WebfluxMessage.POD_ALREADY_PROVISIONED.format(existing.root().uri()));
                    }
                })
                // The profile is seeded after the pod, and only when an issuer was configured:
                // without one there is nothing a WebID document could usefully say.
                .then(oidcIssuer == null
                        ? Mono.empty()
                        : provisioner.provisionWebIdProfile(spec, oidcIssuer)
                                .doOnNext(profile -> log.info(WebfluxMessage.SEEDED_WEBID_PROFILE
                                        .format(profile.uri(), spec.ownerWebId(), oidcIssuer))));
    }
}
