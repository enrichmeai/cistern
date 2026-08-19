package com.enrichmeai.cistern.webflux;

/**
 * cistern-webflux's message catalogue (ground rule 7): every piece of human-readable text this
 * module produces — exception messages, RFC 9457 problem titles and details, log lines — is a
 * constant here, never text inlined at a throw or log site. One catalogue per module, so the
 * {@code error} subpackage draws from this enum rather than keeping a second one.
 *
 * <p>Templates are {@link String#format} patterns. A literal percent sign must be doubled
 * ({@code %%}) — the request-target messages quote percent-encoded text, so this matters
 * here more than most places.
 */
public enum WebfluxMessage {

    // ---------------------------------------------------------------- request targets

    /** Request-target that is not an absolute path — nothing to resolve against the base. */
    TARGET_NOT_ABSOLUTE("Request target must be an absolute path: %s"),

    /** {@code //} or {@code /a//b}: the storage backend cannot name an empty segment. */
    TARGET_EMPTY_SEGMENT("Empty path segment in request target: %s"),

    /** {@code .} or {@code ..}: identifiers must arrive normalized (RFC 3986 §5.2.4). */
    TARGET_DOT_SEGMENT("Dot segments are not addressable; send a normalized path: %s"),

    /** {@code %%2F} inside a segment — raw and decoded slash structure would disagree. */
    TARGET_ENCODED_SLASH("Encoded slash (%%2F) in a path segment is not addressable: %s"),

    /** Percent-escape or character that makes the request-target unparseable as a URI. */
    TARGET_MALFORMED("Malformed request target %s: %s"),

    // ---------------------------------------------------------------- negotiation

    /** {@code Accept} that will not parse — the client's error, not the server's. */
    ACCEPT_MALFORMED("Malformed Accept header: %s"),

    /** Solid Protocol §5.5 fixes what an RDF source can be serialized as. */
    RDF_SOURCE_NOT_ACCEPTABLE(
            "No acceptable representation: this resource is an RDF source and can only be served"
                    + " as %s or %s (Solid Protocol §5.5); Accept requested %s"),

    /** A non-RDF source has exactly one representation and is never transcoded. */
    NON_RDF_SOURCE_NOT_ACCEPTABLE(
            "No acceptable representation: this resource is a non-RDF source, served verbatim"
                    + " as %s; Accept requested %s"),

    /** Stored media type that will not parse: server-side corruption, never a client fault. */
    STORED_CONTENT_TYPE_INVALID(
            "Stored content type for <%s> is not a valid media type: %s"),

    // ---------------------------------------------------------------- write requests

    /** Solid Protocol §2.1 mandates 400 for a content-bearing write with no Content-Type. */
    CONTENT_TYPE_REQUIRED(
            "A write request must declare the media type of its body in the Content-Type"
                    + " header field (Solid Protocol §2.1)"),

    /** {@code Content-Type} that will not parse — the client's error, not the server's. */
    CONTENT_TYPE_MALFORMED("Malformed Content-Type header: %s"),

    /** RFC 9110 §8.3: Content-Type names what the body IS; a range names what is acceptable. */
    CONTENT_TYPE_NOT_CONCRETE(
            "Content-Type must name a concrete media type, not a range: %s"),

    /**
     * Solid Protocol §5.3.1 identifies an N3 Patch by {@code text/n3} and by nothing else, so a
     * {@code PATCH} body in any other media type is RFC 5789 §2.2's "unsupported patch document"
     * — a 415, whose {@code Accept-Patch} tells the client what to retry with.
     */
    PATCH_MEDIA_TYPE_UNSUPPORTED(
            "A PATCH body must be an N3 Patch document declared as %s (Solid Protocol §5.3.1);"
                    + " this request declared %s"),

    // ---------------------------------------------------------------- conditional requests

    /**
     * A failed {@code If-Match} (RFC 9110 §13.1.1). Names the field, so a client that sent
     * several conditionals knows which one to fix, and quotes the tags back so it can see the
     * server read them as it meant them.
     */
    PRECONDITION_IF_MATCH_FAILED(
            "The %s precondition failed for <%s>: no current representation of the resource"
                    + " matches %s (RFC 9110 §13.1.1). Re-read the resource to obtain its"
                    + " current ETag before retrying."),

    /**
     * A failed {@code If-None-Match} (RFC 9110 §13.1.2) — the opposite sense to
     * {@link #PRECONDITION_IF_MATCH_FAILED}, and a separate entry precisely so it cannot be
     * described with the wrong one: this failure means the resource <em>does</em> match, which
     * for {@code *} means it already exists and the write would not have been a create.
     */
    PRECONDITION_IF_NONE_MATCH_FAILED(
            "The %s precondition failed for <%s>: the resource has a current representation"
                    + " matching %s (RFC 9110 §13.1.2), so this request would not have been"
                    + " applied to the state the client assumed."),

    /** A failed {@code If-Unmodified-Since} (RFC 9110 §13.1.4). */
    PRECONDITION_MODIFICATION_DATE_FAILED(
            "The %s precondition failed for <%s>: the resource was modified after the date"
                    + " given (RFC 9110 §13.1.4). Re-read the resource before retrying."),

    /**
     * Fires only if the evaluator and the write path disagree about which methods are reads:
     * RFC 9110 §13.2.2 step 3 reserves 304 for {@code GET} and {@code HEAD}.
     */
    NOT_MODIFIED_ON_UNSAFE_METHOD(
            "Precondition evaluation yielded 304 for %s <%s>, but RFC 9110 §13.2.2 step 3"
                    + " allows that outcome only for GET and HEAD"),

    // ---------------------------------------------------------------- configuration

    /** {@code cistern.base-url} must be usable as the base of every resource identifier. */
    BASE_URL_INVALID("cistern.base-url must be an absolute URI without a fragment: %s"),

    // ---------------------------------------------------------------- authentication (T4.0)

    /** {@code BearerToken} constructed with nothing in it — a caller bug, not a request fault. */
    BEARER_TOKEN_BLANK("A bearer token cannot be blank"),

    /** A service principal's credential hash is not {@code <label>:<hex>}. */
    CREDENTIAL_HASH_MALFORMED(
            "cistern.auth.service-principals[].credential-hash must be <algorithm>:<hex digest>,"
                    + " e.g. sha256:<64 hex characters>; got: %s"),

    /** The label before the colon names no algorithm this server implements. */
    CREDENTIAL_HASH_UNKNOWN_ALGORITHM(
            "cistern.auth.service-principals[].credential-hash names an unknown hash algorithm"
                    + " '%s'; supported: %s"),

    /** The hex digest is the wrong size for the algorithm named — a copy-paste error, usually. */
    CREDENTIAL_HASH_WRONG_LENGTH(
            "A %s credential hash must be %s bytes of digest; got %s"),

    /** A service principal's WebID must be absolute: it is what {@code acl:agent} names. */
    SERVICE_PRINCIPAL_WEBID_INVALID("A service principal's web-id must be an absolute URI: %s"),

    /** Both halves of an entry are required; half an entry authenticates nobody. */
    SERVICE_PRINCIPAL_INCOMPLETE(
            "cistern.auth.service-principals[] entries need both web-id and credential-hash"
                    + " (web-id: %s)"),

    /** Two entries with one credential: whose is it? Refused at startup rather than guessed. */
    SERVICE_CREDENTIAL_DUPLICATE(
            "Two service principals share a credential hash (second is <%s>); a credential must"
                    + " prove exactly one identity"),

    /** {@code cistern.auth.oidc.issuer} must be an absolute URI: it is compared verbatim to {@code iss}. */
    OIDC_ISSUER_INVALID("cistern.auth.oidc.issuer must be an absolute URI: %s"),

    /** A resource server that accepts tokens meant for anyone accepts tokens stolen from anyone. */
    OIDC_AUDIENCES_REQUIRED(
            "cistern.auth.oidc.audiences must name at least one audience when"
                    + " cistern.auth.oidc.issuer is set"),

    /** Two rules for finding the WebID in a token contradict each other. */
    OIDC_WEBID_MAPPING_AMBIGUOUS(
            "Set cistern.auth.oidc.webid-claim or cistern.auth.oidc.webid-template, not both"),

    /** Skew widens the validity window; a negative one is meaningless. */
    OIDC_CLOCK_SKEW_NEGATIVE("cistern.auth.oidc.clock-skew must not be negative: %s"),

    /** Startup: which resolvers a request will be tried against, in order. Logged at INFO. */
    PRINCIPAL_RESOLVERS_WIRED("Principal resolvers, in order: %s"),

    // ---------------------------------------------------------------- cistern.pods.seed[] (T5.6)

    /** Both halves of an entry are required; a root with no owner is a container, not a pod. */
    POD_SEED_INCOMPLETE(
            "cistern.pods.seed[] entries need both root and owner-web-id"
                    + " (root: %s, owner-web-id: %s)"),

    /**
     * A seed root is a path under {@code cistern.base-url}, and a pod is a subtree, so it must
     * be a container path: leading slash, trailing slash, no empty segment.
     */
    POD_SEED_ROOT_NOT_A_CONTAINER_PATH(
            "cistern.pods.seed[].root must be an absolute container path under cistern.base-url"
                    + " (starting and ending with '/', no empty segment): %s"),

    /**
     * A dot segment would make one resource reachable under two spellings; refused rather than
     * quietly collapsed, as {@code RequestPaths} refuses it on the wire.
     */
    POD_SEED_ROOT_NOT_NORMALIZED(
            "cistern.pods.seed[].root must not contain '.' or '..' segments: %s"),

    /** The root does not form a URI when appended to the base URL. */
    POD_SEED_ROOT_MALFORMED(
            "cistern.pods.seed[].root %s does not form a valid URI under %s: %s"),

    /** A relative owner would resolve against the ACL document and name nobody who exists. */
    POD_SEED_OWNER_NOT_ABSOLUTE(
            "cistern.pods.seed[].owner-web-id for root %s must be an absolute URI: %s"),

    /** Two entries for one root: whose pod is it? Refused at startup rather than guessed. */
    POD_SEED_ROOT_DUPLICATED("cistern.pods.seed[] lists root %s more than once"),

    /**
     * A seed for the storage root naming a different owner than {@code cistern.owner.web-id}.
     * Enforcement is keyed on the configured owner; letting a seed re-own {@code /} would lock
     * that owner out of their own root, so the contradiction is refused, not resolved.
     */
    POD_SEED_ROOT_CONTRADICTS_OWNER(
            "cistern.pods.seed[] names <%s> as owner of the storage root, but cistern.owner.web-id"
                    + " is <%s>; the storage root belongs to the configured owner"),

    // ---------------------------------------- RFC 9457 titles, one per problem type

    // Titles are the RFC 9457 title member and so must not vary between occurrences of the
    // same problem type (§3.1.3): they carry no format arguments. Occurrence-specific text
    // belongs in detail, which comes from the domain exception. See ProblemType.

    TITLE_BAD_INPUT("Malformed request entity"),
    TITLE_UNPROCESSABLE_ENTITY("Request entity violates a protocol constraint"),
    TITLE_NOT_FOUND("Resource not found"),

    /** RFC 9110 §15.5.7 — nothing the server can produce matches the request's {@code Accept}. */
    TITLE_NOT_ACCEPTABLE("No acceptable representation"),

    /** RFC 9110 §15.5.6 — the method is not supported on this resource; see {@code Allow}. */
    TITLE_METHOD_NOT_ALLOWED("Method not allowed on this resource"),

    /** RFC 9110 §15.5.16 — the body's media type is not one this method takes for this resource. */
    TITLE_UNSUPPORTED_MEDIA_TYPE("Unsupported media type for this method"),

    TITLE_CONFLICT("Request conflicts with the state of the resource"),
    TITLE_PRECONDITION_FAILED("Precondition failed"),
    TITLE_AUTHENTICATION_REQUIRED("Authentication required"),
    TITLE_ACCESS_DENIED("Access denied"),
    /** RFC 9110 §15.6.4; raised for an unrecordable decision under {@code cistern.audit.required}. */
    TITLE_SERVICE_UNAVAILABLE("Service unavailable"),

    // ---------------------------------------------------------------- problem details

    /**
     * The only detail any 5xx response ever carries. RFC 9457 §5 warns that problem details
     * must not leak information about the system; the real cause goes to the log instead.
     * Wording follows RFC 9110 §15.6.1's definition of 500 rather than describing the fault.
     */
    DETAIL_INTERNAL_ERROR(
            "The server encountered an unexpected condition that prevented it from fulfilling the request."),

    // ---------------------------------------------------------------- log lines

    LOG_SERVER_ERROR("%s %s failed with %s"),
    LOG_CLIENT_ERROR("%s %s rejected with %s: %s"),
    // LOG_UNMAPPED_DOMAIN_ERROR was here (#60). It announced a CisternException subtype the
    // mapper had no row for — a condition sealing made unreachable: the switch in ProblemMapper
    // is exhaustive over the permits list, so an unmapped subtype cannot compile, let alone log.
    // Removed rather than kept: a catalogue entry for a message that can never be emitted is
    // exactly the drift this catalogue exists to prevent.

    // ---------------------------------------------------------------- programming errors

    PROBLEM_MEMBER_REQUIRED("RFC 9457 member '%s' must not be null"),

    /**
     * Startup: no owner is named, so enforcement is off (T5.3) — the pod is unprotected, and it
     * says so on every boot. Logged, never thrown: refusing to start would turn "upgrade" into
     * "brick" for a laptop pod that has never had an owner, and ADR 0001/0002 keep such a pod
     * off any address a stranger can reach.
     */
    NO_OWNER_CONFIGURED(
            "cistern.owner.web-id is not set: Web Access Control is OFF and every request,"
                    + " anonymous included, is served without a decision. Set it to the WebID"
                    + " that owns the storage root; enforcement then denies by default and the"
                    + " root ACL is seeded for that owner (cistern.owner.token is optional: it"
                    + " is one way for the owner to authenticate, for a private network)."),

    /**
     * Bind-time refusal (T7.7, #94): a credential source is configured but no owner is named,
     * so enforcement would be off and the credential would never be asked for — the pod would
     * be open to everyone while looking, in its configuration, as if it were locked. Thrown,
     * never merely logged: the deployment this describes is a production one, and a production
     * pod that starts unprotected has already failed.
     */
    ENFORCEMENT_REQUIRES_OWNER(
            "Credentials are configured (%s) but cistern.owner.web-id is not, so Web Access"
                    + " Control would be OFF and no credential would ever be asked for: anyone"
                    + " could read, write and delete without one. Set cistern.owner.web-id to"
                    + " the WebID that owns the storage root — that is what turns enforcement on"
                    + " and seeds the root ACL. cistern.owner.token is not required: leave it"
                    + " unset where the owner authenticates through the OIDC issuer or a service"
                    + " credential (docs/adr/0002-production-posture.md)."),

    /**
     * Startup: enforcement is on (an owner is named) but no resolver can authenticate anyone —
     * no local token, no issuer, no service principals, nothing contributed. Every request is
     * anonymous, so only what the ACLs grant the public is reachable, the owner included. Logged
     * at WARN, not thrown: a public read-only pod whose ACLs were written on disk is a legitimate
     * shape, and an embedder may replace the resolver chain outright.
     */
    ENFORCEMENT_WITHOUT_CREDENTIAL(
            "cistern.owner.web-id is set, so Web Access Control is ON, but nothing"
                    + " authenticates anyone: no cistern.owner.token, no cistern.auth.oidc.issuer,"
                    + " no cistern.auth.service-principals[] and no contributed resolver. Every"
                    + " request is anonymous and only what the ACLs grant the public is"
                    + " reachable — the owner included."),

    /** Startup: a fresh pod was given a root ACL granting its owner full access. */
    SEEDED_ROOT_ACL("Seeded root ACL <%s> granting full access to owner <%s>"),

    /** Startup: a {@code cistern.pods.seed[]} entry was provisioned — container and owner ACL. */
    SEEDED_POD("Seeded pod <%s>: owner ACL <%s> granting full access to <%s>"),

    /**
     * Startup: a {@code cistern.pods.seed[]} entry already had an ACL and was left alone. Logged
     * at DEBUG — on every restart, for every pod, it is the expected case.
     */
    POD_ALREADY_PROVISIONED(
            "Pod <%s> already has an ACL; left as it is (a restart is not a request to reset"
                    + " permissions)"),

    // ---------------------------------------------------------------- receipts (T5.9)

    /** Startup: which sink and which log root receipts go to, and under which policy. */
    AUDIT_WIRED("Decision log: %s at <%s>; audit policy %s"),

    /** {@code ?receipts&from=}/{@code &to=} that is not an ISO 8601 instant. */
    RECEIPTS_INSTANT_MALFORMED(
            "Receipts parameter '%s' must be an ISO 8601 instant such as 2026-08-19T00:00:00Z: %s"),

    /** {@code ?receipts&from=}/{@code &to=} that do not describe an interval. */
    RECEIPTS_INTERVAL_EMPTY("Receipts interval is empty: from %s is not before to %s"),

    /** {@code ?receipts&agent=} that is not an absolute WebID. */
    RECEIPTS_AGENT_MALFORMED("Receipts parameter 'agent' must be an absolute WebID URI: %s");

    private final String template;

    WebfluxMessage(String template) {
        this.template = template;
    }

    /**
     * The message text with {@code args} substituted into this entry's template. Always goes
     * through {@link String#format}, including when called with no arguments, so a doubled
     * {@code %%} resolves to one percent sign however the entry is used.
     */
    public String format(Object... args) {
        return String.format(template, args);
    }
}
