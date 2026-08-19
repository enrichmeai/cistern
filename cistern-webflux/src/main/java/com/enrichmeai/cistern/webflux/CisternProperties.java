package com.enrichmeai.cistern.webflux;

import com.enrichmeai.cistern.core.ResourceIdentifier;
import com.enrichmeai.cistern.wac.PodSpec;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Deployment configuration for the HTTP layer.
 *
 * <p>{@code cistern.base-url} is load-bearing rather than cosmetic: in Solid the resource
 * identifier IS the HTTP URI (Solid Protocol §2.1), so it is the base every request path is
 * resolved against to produce a {@code ResourceIdentifier}, and therefore the origin that
 * appears in every {@code ldp:contains} object and every subject a client reads back. It is
 * taken from configuration, not from the request's {@code Host} header, so that identifiers
 * stay stable behind a proxy and cannot be poisoned by a forged {@code Host}.
 *
 * @param baseUrl origin (and optional path prefix) the pod is published under; any trailing
 *                slash is insignificant and stripped
 * @param storage storage backend settings
 * @param cors    cross-origin sharing settings (T2.8); wide open unless narrowed
 * @param owner   the pod owner — naming one turns enforcement on (T5.3/T5.4) — and,
 *                optionally, their local credential
 * @param auth    the other ways a request proves who it is (T4.0): service principals and an
 *                OIDC issuer whose JWTs are accepted
 * @param pods    further pods, each with its own owner, provisioned at boot (T5.6)
 * @param audit   the decision log (T5.9): whether an unrecordable decision fails the request,
 *                and where the log lives
 */
@ConfigurationProperties(prefix = "cistern")
public record CisternProperties(
        String baseUrl, Storage storage, Cors cors, Owner owner, Auth auth, Pods pods, Audit audit) {

    private static final String DEFAULT_BASE_URL = "http://localhost:3000";

    /** Insignificant on the base URL: every request path already supplies its own leading one. */
    private static final String TRAILING_SEPARATOR = "/";

    /** Joins the configured credential sources when the enforcement guard names them. */
    private static final String CREDENTIAL_SOURCE_SEPARATOR = " and ";

    public CisternProperties {
        baseUrl = (baseUrl == null || baseUrl.isBlank()) ? DEFAULT_BASE_URL : baseUrl.trim();
        while (baseUrl.endsWith(TRAILING_SEPARATOR)) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - TRAILING_SEPARATOR.length());
        }
        URI parsed = URI.create(baseUrl);
        if (!parsed.isAbsolute() || parsed.getRawFragment() != null) {
            throw new IllegalArgumentException(WebfluxMessage.BASE_URL_INVALID.format(baseUrl));
        }
        storage = storage == null ? new Storage(null) : storage;
        cors = cors == null ? new Cors(null, null) : cors;
        owner = owner == null ? new Owner(null, null) : owner;
        auth = auth == null ? new Auth(null, null) : auth;
        pods = pods == null ? new Pods(null) : pods;
        audit = audit == null ? new Audit(false, null) : audit;
        // The enforcement guard (T7.7, #94). Enforcement is keyed on the owner's WebID (see
        // CisternWebFluxConfiguration#cisternAuthorizationFilter); a credential source without
        // one is a pod that is open to everyone while its configuration reads as locked. Refused
        // at bind time, so the server never comes up in that state. Nothing configured at all is
        // still allowed and still loud (OwnerPodSeeder warns NO_OWNER_CONFIGURED).
        Set<Auth.CredentialSource> sources = auth.credentialSources();
        if (!owner.isNamed() && !sources.isEmpty()) {
            throw new IllegalArgumentException(WebfluxMessage.ENFORCEMENT_REQUIRES_OWNER.format(
                    sources.stream()
                            .map(Auth.CredentialSource::property)
                            .collect(Collectors.joining(CREDENTIAL_SOURCE_SEPARATOR))));
        }
        // Bind-time, not boot-time: a seed that cannot be provisioned is a configuration error
        // and should fail the start, not surface as a stack trace from a runner.
        for (PodSpec seeded : pods.specsUnder(baseUrl)) {
            if (owner.isNamed() && seeded.root().isStorageRoot()
                    && !seeded.ownerWebId().equals(owner.webId())) {
                throw new IllegalArgumentException(WebfluxMessage.POD_SEED_ROOT_CONTRADICTS_OWNER
                        .format(seeded.ownerWebId(), owner.webId()));
            }
        }
    }

    /**
     * @param root directory the file backend keeps resources under
     */
    public record Storage(Path root) {

        private static final Path DEFAULT_ROOT = Path.of("./data");

        public Storage {
            root = root == null ? DEFAULT_ROOT : root;
        }
    }

    /**
     * The decision log (T5.9): {@code cistern.audit.*}.
     *
     * <p>{@code required} is the one switch that changes an authorization outcome for a reason
     * other than policy. Off (the default), a receipt that cannot be written is logged and the
     * request proceeds as decided — availability over completeness of the trail. On, the request
     * is refused (503, retry later) — no decision is acted on that cannot be accounted for. A
     * deployment whose whole point is the receipt turns it on; a deployment on a laptop leaves
     * it off.
     *
     * <p>{@code root} is where the JSON Lines files go on the file backend. Unset means
     * {@code {cistern.storage.root}/.cistern}, beside the pod's data on the same volume so one
     * backup carries both; set it to put the trail on its own disk. Wherever it is, it is not
     * pod content: the file backend never lists a dot-prefixed directory, and no HTTP path can
     * address it (see {@code DecisionLog}).
     *
     * @param required whether a failed append fails the request closed
     * @param root     directory of the decision log; unset derives it from the storage root
     */
    public record Audit(boolean required, Path root) {

        /** The subdirectory of the storage root that holds the decision log by default. */
        public static final String DEFAULT_DIRECTORY = ".cistern";

        /** The log's directory: {@code root} if set, else {@code {storage root}/.cistern}. */
        public Path rootOrDefault(Storage storage) {
            return root != null ? root : storage.root().resolve(DEFAULT_DIRECTORY);
        }
    }

    /**
     * Cross-origin sharing (T2.8). The default is deliberately permissive: a Solid app is
     * cross-origin <em>by nature</em> — it is served from its own origin and reads the user's
     * pod on another — so a pod that only answered same-origin requests would be unusable by
     * the client ecosystem it exists for. Solid Protocol §8.1 states the requirement directly:
     * "A server MUST implement the CORS protocol such that, to the extent possible, the browser
     * allows Solid apps to send any request and combination of request headers to the server."
     *
     * <p>Permissive is nonetheless the <em>default</em>, not a hard-coding: a deployer running a
     * pod for one known application can narrow {@code cistern.cors.allowed-origins} to it.
     *
     * <p>There is no credentials switch, and that is deliberate rather than an omission.
     * Allowing credentials would tell the browser to attach cookies and HTTP-auth state to
     * cross-origin pod requests — ambient authority that any page on the web could then spend
     * on the user's behalf. Cistern has no such state to attach: Solid-OIDC authenticates with
     * an explicit {@code Authorization} header plus a {@code DPoP} proof (both listed in
     * {@link AllowedRequestHeader}), which a script must set deliberately on each request and
     * which the browser never adds by itself. So credentials mode would grant no capability the
     * protocol uses while opening a hole the protocol does not need. See
     * {@code CisternWebFluxConfiguration#cisternCorsWebFilter} for the Fetch-standard
     * consequence this choice also avoids.
     *
     * @param allowedOrigins origins permitted to read pod resources from a browser;
     *                       {@code "*"} (the default) means any. Entries are treated as
     *                       patterns and the matching origin is echoed back — never the literal
     *                       {@code *} — because §8.1 requires the response to name the request's
     *                       own origin.
     * @param maxAge         how long a browser may cache a preflight result; trades preflight
     *                       chatter against how quickly a configuration change takes effect
     */
    public record Cors(List<String> allowedOrigins, Duration maxAge) {

        /** Any origin: what a public pod serving the open Solid app ecosystem needs. */
        static final String ANY_ORIGIN = "*";

        private static final List<String> DEFAULT_ALLOWED_ORIGINS = List.of(ANY_ORIGIN);

        /**
         * Spring's own default is 30 minutes. An hour, because Cistern's CORS answer is
         * server-wide and static — it does not vary per resource — so re-asking sooner buys a
         * browser nothing but a round trip before every write.
         */
        private static final Duration DEFAULT_MAX_AGE = Duration.ofHours(1);

        public Cors {
            allowedOrigins = (allowedOrigins == null || allowedOrigins.isEmpty())
                    ? DEFAULT_ALLOWED_ORIGINS
                    : List.copyOf(allowedOrigins);
            maxAge = maxAge == null ? DEFAULT_MAX_AGE : maxAge;
        }
    }

    /**
     * Who owns this pod, and — optionally — a local secret that proves it.
     *
     * <p>The two halves do different jobs. {@code web-id} names the owner of the storage root:
     * setting it is what turns Web Access Control <strong>on</strong> (the enforcement filter is
     * registered on it) and what the root ACL is seeded for. {@code token} is one way for that
     * WebID to authenticate — a shared bearer secret, plaintext at rest, for a private network.
     * A production deployment leaves the token unset (ADR 0002): the owner authenticates through
     * {@code cistern.auth.oidc} or a hashed service credential carrying the same WebID, and the
     * root ACL is seeded for them all the same.
     *
     * <p>Neither half is a default. An unconfigured server names no owner and ships no
     * credential — the safe default, given that the alternative is a well-known token in the jar
     * — and says so on every boot ({@code NO_OWNER_CONFIGURED}). A credential source configured
     * without an owner is refused at bind time ({@code ENFORCEMENT_REQUIRES_OWNER}, T7.7).
     *
     * <p>The token is not Solid-OIDC and is not a substitute for it (Phase 4). It is the minimum
     * that lets the owner of a pod on their own machine be somebody, so that WAC has a principal
     * to evaluate. See {@code docs/ideas/first-user-path.md}.
     *
     * @param webId the owner's WebID; the enforcement switch, and the agent granted full access
     *              by the seeded root ACL
     * @param token shared secret presented as {@code Authorization: Bearer <token>}; optional
     */
    public record Owner(URI webId, String token) {

        /** Whether an owner is named — the fact enforcement and the root ACL are keyed on. */
        public boolean isNamed() {
            return webId != null;
        }

        /** Whether the owner can authenticate with a local bearer token. Both halves required. */
        public boolean hasLocalCredential() {
            return isNamed() && token != null && !token.isBlank();
        }
    }

    /**
     * The other ways a request proves who it is (T4.0, #88). Every one of them produces the
     * same {@code Agent} and is judged by the same engine; none is a bypass. See
     * {@code ChainedPrincipalResolver} for how they combine and in what order.
     *
     * @param oidc              an OIDC issuer whose signed JWTs authenticate a request
     * @param servicePrincipals applications that authenticate as themselves, each with its
     *                          own WebID and hashed credential
     */
    public record Auth(Oidc oidc, List<ServicePrincipal> servicePrincipals) {

        public Auth {
            oidc = oidc == null ? new Oidc(null, null, null, null, null, null) : oidc;
            servicePrincipals = servicePrincipals == null ? List.of() : List.copyOf(servicePrincipals);
        }

        /**
         * The ways, other than the owner's local token, that this configuration lets a request
         * prove who it is. Each names the property that switches it on, which is what the
         * enforcement guard quotes back when one is set without an owner.
         */
        public enum CredentialSource {
            OIDC_ISSUER("cistern.auth.oidc.issuer"),
            SERVICE_PRINCIPALS("cistern.auth.service-principals[]");

            private final String property;

            CredentialSource(String property) {
                this.property = property;
            }

            /** The configuration property that enables this source. */
            public String property() {
                return property;
            }
        }

        /** The credential sources this configuration enables, in declaration order; empty when none. */
        public Set<CredentialSource> credentialSources() {
            Set<CredentialSource> sources = EnumSet.noneOf(CredentialSource.class);
            if (oidc.isConfigured()) {
                sources.add(CredentialSource.OIDC_ISSUER);
            }
            if (!servicePrincipals.isEmpty()) {
                sources.add(CredentialSource.SERVICE_PRINCIPALS);
            }
            return sources;
        }
    }

    /**
     * An OIDC issuer whose JWTs this pod accepts as proof of identity (T4.0, #88).
     *
     * <p>Configured by naming the issuer; the signing keys are discovered from it
     * ({@code {issuer}/.well-known/openid-configuration} → {@code jwks_uri}) unless
     * {@code jwks-uri} says otherwise. A token is accepted only if its signature verifies
     * against one of those keys, its {@code iss} is exactly this issuer, its {@code aud} names
     * one of {@code audiences}, and it is within its {@code exp}/{@code nbf} window allowing
     * {@code clock-skew}. Anything else authenticates nobody: the request proceeds as anonymous
     * and WAC decides, so a bad token never causes an error, only a 401 where a grant was needed.
     *
     * <p>Which claim is the WebID is the one thing an issuer cannot tell us. Either
     * {@code webid-claim} names a claim carrying an absolute URI (Solid-OIDC's {@code webid},
     * the default), or {@code webid-template} builds one from claims — e.g.
     * {@code {iss}/users/{sub}#me} for an issuer that mints no WebIDs of its own. Setting both
     * is a contradiction and refused.
     *
     * @param issuer        the issuer identifier, compared verbatim against {@code iss}; unset
     *                      means no OIDC resolver
     * @param audiences     the values of which {@code aud} must contain at least one; required
     *                      when an issuer is set — a resource server that accepts tokens meant
     *                      for anyone accepts tokens stolen from anyone
     * @param webidClaim    the claim carrying the WebID
     * @param webidTemplate a template over claims, {@code {claim}} substituted, yielding the
     *                      WebID
     * @param clockSkew     tolerance applied to {@code exp} and {@code nbf}
     * @param jwksUri       where the issuer publishes its keys, when discovery is not wanted
     */
    public record Oidc(
            URI issuer,
            Set<String> audiences,
            String webidClaim,
            String webidTemplate,
            Duration clockSkew,
            URI jwksUri) {

        /**
         * Solid-OIDC §5: the access token's {@code webid} claim is the WebID. The natural
         * default for a pod server, and what a Solid-aware issuer supplies.
         */
        public static final String DEFAULT_WEBID_CLAIM = "webid";

        /**
         * A minute, which is what Nimbus and most validators default to: enough for the clock
         * drift of two machines that both run NTP, not enough to make "expired" meaningless.
         */
        public static final Duration DEFAULT_CLOCK_SKEW = Duration.ofSeconds(60);

        public Oidc {
            audiences = audiences == null ? Set.of() : Set.copyOf(audiences);
            clockSkew = clockSkew == null ? DEFAULT_CLOCK_SKEW : clockSkew;
            webidClaim = blankToNull(webidClaim);
            webidTemplate = blankToNull(webidTemplate);
            if (issuer != null) {
                if (!issuer.isAbsolute()) {
                    throw new IllegalArgumentException(
                            WebfluxMessage.OIDC_ISSUER_INVALID.format(issuer));
                }
                if (audiences.isEmpty()) {
                    throw new IllegalArgumentException(
                            WebfluxMessage.OIDC_AUDIENCES_REQUIRED.format());
                }
                if (webidClaim != null && webidTemplate != null) {
                    throw new IllegalArgumentException(
                            WebfluxMessage.OIDC_WEBID_MAPPING_AMBIGUOUS.format());
                }
                if (clockSkew.isNegative()) {
                    throw new IllegalArgumentException(
                            WebfluxMessage.OIDC_CLOCK_SKEW_NEGATIVE.format(clockSkew));
                }
            }
            if (webidClaim == null && webidTemplate == null) {
                webidClaim = DEFAULT_WEBID_CLAIM;
            }
        }

        /** Whether an OIDC resolver is wanted at all. */
        public boolean isConfigured() {
            return issuer != null;
        }

        private static String blankToNull(String value) {
            return value == null || value.isBlank() ? null : value.trim();
        }
    }

    /**
     * One application that authenticates as itself (T4.0, #88; the v1 ruling on #89):
     * {@code cistern.auth.service-principals[n]}.
     *
     * <p>The credential is configured <em>hashed</em> — {@code sha256:<hex>}; see
     * {@code HashedCredential} for how to produce one — so that neither this file, nor the
     * environment it is read from, nor a Kubernetes Secret rendered into it, holds anything an
     * attacker can present.
     *
     * @param webId          the identity the application authenticates as
     * @param credentialHash the digest of its secret, {@code <algorithm>:<hex>}
     */
    public record ServicePrincipal(URI webId, String credentialHash) {

        public ServicePrincipal {
            if (webId == null || credentialHash == null || credentialHash.isBlank()) {
                throw new IllegalArgumentException(
                        WebfluxMessage.SERVICE_PRINCIPAL_INCOMPLETE.format(webId));
            }
        }
    }

    /**
     * Pods to provision at boot (T5.6, #90): {@code cistern.pods.seed[n].root} and
     * {@code .owner-web-id}, one entry per pod, each with its own owner. This is how one server
     * hosts several pods — {@code /alice/} and {@code /bob/} for the conformance harness,
     * {@code /firms/acme/} and {@code /firms/globex/} for a hosted deployment — where
     * {@code cistern.owner} names the one owner of the storage root.
     *
     * <p>Seeding is idempotent and never overwrites: a root that already has an ACL is left
     * exactly as it is on every restart, because a restart is not a request to reset
     * permissions. See {@code PodProvisioner}.
     *
     * <p>Two things this does <em>not</em> do, deliberately. It does not make the seeded owners
     * able to authenticate — that is {@code cistern.auth.service-principals[]} or
     * {@code cistern.auth.oidc}, and a seeded owner who cannot authenticate simply owns a pod
     * nobody can enter yet. And it does not turn enforcement on: that is still keyed on
     * {@code cistern.owner.web-id}, so a server with seeds and no owner has ACLs nothing
     * consults — allowed, because seeding without an owner is a provisioning step, not a
     * credential; the moment a credential source is configured the enforcement guard requires
     * the owner (T7.7).
     *
     * @param seed the pods, in configuration order; empty when none are configured
     */
    public record Pods(List<Seed> seed) {

        public Pods {
            seed = seed == null ? List.of() : List.copyOf(seed);
        }

        /**
         * The configured pods as {@link PodSpec}s under {@code baseUrl}, in configuration order.
         *
         * @throws IllegalArgumentException if a root does not resolve to a normalized
         *         container URI under the base, or the same root is listed twice
         */
        public List<PodSpec> specsUnder(String baseUrl) {
            List<PodSpec> specs = new ArrayList<>(seed.size());
            Set<ResourceIdentifier> roots = new HashSet<>();
            for (Seed entry : seed) {
                PodSpec spec = entry.specUnder(baseUrl);
                if (!roots.add(spec.root())) {
                    throw new IllegalArgumentException(
                            WebfluxMessage.POD_SEED_ROOT_DUPLICATED.format(entry.root()));
                }
                specs.add(spec);
            }
            return List.copyOf(specs);
        }
    }

    /**
     * One pod to seed: {@code cistern.pods.seed[n]}.
     *
     * <p>The root is a <em>path</em> under {@code cistern.base-url} — {@code /firms/acme/}, or
     * {@code /} for the storage root — never an absolute URL, so a pod cannot be configured
     * outside the server that hosts it, and so the value reads the same way a request path
     * does. It must name a container: a pod is a subtree, and only a container's ACL can
     * carry the {@code acl:default} that makes the subtree inherit.
     *
     * @param root       container path under the base URL, starting and ending with {@code /}
     * @param ownerWebId the agent granted full access to the root and everything under it
     */
    public record Seed(String root, URI ownerWebId) {

        public Seed {
            if (root == null || root.isBlank() || ownerWebId == null) {
                throw new IllegalArgumentException(
                        WebfluxMessage.POD_SEED_INCOMPLETE.format(root, ownerWebId));
            }
            root = root.trim();
            // The same shape rules RequestPaths applies to a request-target, spelled once there:
            // a container path, no empty segment, no dot segment.
            if (!root.startsWith(RequestPaths.SEPARATOR) || !root.endsWith(RequestPaths.SEPARATOR)
                    || root.contains(RequestPaths.EMPTY_SEGMENT)) {
                throw new IllegalArgumentException(
                        WebfluxMessage.POD_SEED_ROOT_NOT_A_CONTAINER_PATH.format(root));
            }
            for (String segment : root.split(RequestPaths.SEPARATOR)) {
                if (segment.equals(RequestPaths.CURRENT_SEGMENT)
                        || segment.equals(RequestPaths.PARENT_SEGMENT)) {
                    throw new IllegalArgumentException(
                            WebfluxMessage.POD_SEED_ROOT_NOT_NORMALIZED.format(root));
                }
            }
            if (!ownerWebId.isAbsolute()) {
                throw new IllegalArgumentException(
                        WebfluxMessage.POD_SEED_OWNER_NOT_ABSOLUTE.format(root, ownerWebId));
            }
        }

        /**
         * This entry as a {@link PodSpec} under {@code baseUrl}, which is where the root becomes
         * an identifier: {@code baseUrl + root}, exactly as {@code RequestPaths} forms one from a
         * request path.
         *
         * @throws IllegalArgumentException if the root does not form a valid URI under the base
         */
        public PodSpec specUnder(String baseUrl) {
            try {
                return new PodSpec(new ResourceIdentifier(new URI(baseUrl + root)), ownerWebId);
            } catch (URISyntaxException e) {
                throw new IllegalArgumentException(
                        WebfluxMessage.POD_SEED_ROOT_MALFORMED.format(root, baseUrl, e.getReason()), e);
            }
        }
    }
}
