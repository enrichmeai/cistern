# ADR 0002 — Conditions under which a Cistern instance may face the internet

- **Status:** accepted — supersedes [ADR 0001](0001-local-only-until-phase-5.md); accepted by
  the merge of the PR that carries it (#94, T7.7)
- **Date:** 2026-08-19
- **Deciders:** project owner (architect); dev-agent proposal on #94

## Context

ADR 0001 (2026-07-20) kept Cistern on loopback because it had no authority plane: an anonymous
request could create (`201`) and delete (`204`) any resource. That is no longer the build.
Between then and now, all on `main`:

| Capability | Ticket | What it means for deployment |
|---|---|---|
| WAC engine, effective-ACL discovery, enforcement ahead of every handler, deny by default | T5.1–T5.3 (#84–#86) | an anonymous `DELETE` is `401`, not `204`; every request crosses one decision point |
| Owner seeding: `cistern.owner.web-id` names the root owner and the root ACL is seeded for them | T5.4 (#86) | naming an owner is what turns enforcement on |
| Three credential shapes, one resolver chain, one engine: the owner's local token, hashed service-principal credentials, JWTs from a configured OIDC issuer | T4.0 (#88) | an application can authenticate **as itself**; humans through the firm's IdP; the owner need not hold a plaintext token |
| Provisioning: `cistern.pods.seed[]`, `cistern pod create` | T5.6 (#90) | many pods, many owners, on one instance |
| Grant authoring CLI | T5.7 (#91) | grants are files the owner controls; revocation is one request away |
| Decision log and receipts; `X-Request-Id` honoured or minted, echoed on every response | T5.9 (#93) | every allow and deny is accountable; the edge and the pod can share a correlation id |
| Release pipeline: `ghcr.io/enrichmeai/cistern:<version>`, first tag `0.1.0` | #107 | a pinned, reproducible image |
| **Enforcement guard** (this PR): a credential source configured without `cistern.owner.web-id` refuses to start | T7.7 (#94) | a production configuration cannot come up open by accident |

What has **not** changed is the storage constraint ADR 0001 recorded: `FileResourceStore` is a
single-writer backend whose crash safety rests on tmp-then-`ATOMIC_MOVE` on a real filesystem.
gcsfuse still voids that silently; an object-native backend is #95, not a workaround.

The first deployer is ValueDocs Pvt Ltd (legal and tax), running Cistern **as its own service
in its own cloud org** — separate project or namespace, own volume, own IAM; each application
holds only its own credential; the owner/Control credential is held by the firm's
administrator (`docs/INTEGRATION.md`, option A).

## Decision

A Cistern instance **may be reachable from the internet when every one of the following holds.**
Each condition is a thing the operator can check, and where the server can check it, it does.

1. **TLS is terminated in front of Cistern.** Cistern speaks plain HTTP on a listener nothing
   but the terminator can reach — a Google HTTPS load balancer with a managed certificate
   (`infra/terraform`), or a Kubernetes Ingress with TLS (`k8s/overlays/production`). No port
   other than 443 is open to the world; the instance or pod accepts traffic only from the
   terminator's address ranges. `cistern.base-url` is the public `https://` origin, because it
   mints every identifier the pod hands out.
2. **Enforcement is on: `cistern.owner.web-id` is set.** It names the WebID that owns the
   storage root, registers the enforcement filter, and seeds the root ACL. It is the only
   enforcement switch — there is deliberately no `cistern.enforcement=on` (see *Why no second
   switch*, below). The server enforces this condition itself: `cistern.auth.oidc.issuer` or
   `cistern.auth.service-principals[]` configured without an owner is a start-up refusal
   (`ENFORCEMENT_REQUIRES_OWNER`), because those credentials would never be asked for and the
   pod would be open while its configuration read as locked.
3. **`cistern.owner.token` is unset.** The local bearer token is plaintext at rest and was only
   ever for a private network. In production the owner authenticates like everyone else — a
   JWT from the configured OIDC issuer whose `webid` claim is the owner's WebID — or, until an
   IdP is wired, with a **hashed** credential: an entry in `cistern.auth.service-principals[]`
   carrying the owner's WebID (`sha256:` at rest; still a bearer secret in transit, so TLS is
   what makes it acceptable). Further pods and their owners are provisioned with OIDC
   identities via `cistern.pods.seed[]` or `cistern pod create`; a seeded owner who cannot
   authenticate simply owns a pod nobody can enter yet, which is safe.
4. **Per-tenant isolation.** One instance per firm — its own volume, its own namespace or
   project, its own IAM, its own owner — is the shape ValueDocs deploys (option A). Several
   firms on one instance as separate pod roots under one operator owner is permitted only when
   every firm trusts that operator: the operator's WebID holds Control over the storage root
   and therefore over every pod beneath it. That is a business fact to be true before it is a
   configuration.
5. **Backups exist and have been restored at least once.** The unit of backup is the whole of
   `cistern.storage.root`, including `.cistern/` (the decision log — the receipts are the
   audit trail, and a restore without them is a restore of the data but not of the account
   of who touched it). Snapshots on a schedule (Terraform: a daily disk-snapshot policy;
   Kubernetes: the volume snapshot class of the cluster) **and** a restore drill
   (`infra/restore-drill.sh`) run before go-live and again after any change to storage. A
   backup that has never been restored is a hypothesis.
6. **The correlation id crosses the edge.** Applications send `X-Request-Id`; Cistern honours a
   well-formed one (`RequestId`), mints one otherwise, echoes it on every response and writes
   it into every receipt. The edge either forwards it untouched (Google HTTPS LB) or mints one
   when absent (ingress-nginx does by default), so an edge access log line and a receipt can be
   joined by one value.
7. **Rate limiting is at the edge.** Cistern has no in-process limiter and will not grow one
   in v1; Cloud Armor on the load balancer's backend service, or the ingress controller's
   rate-limit annotations, is where a client is slowed down. Applications should expect `429`
   from the edge as well as `401`/`403` from the pod.
8. **Receipts are required, not best-effort.** `cistern.audit.required=true`, so that a decision
   the log cannot record is not acted on (`503`, retry). A production pod exists to be
   accountable; availability over completeness of the trail is the laptop default, not this one.

Local development keeps ADR 0001's posture unchanged and enforced by CI: `docker-compose.yml`
binds `127.0.0.1`; `k8s/overlays/local` is `ClusterIP`, no Ingress, `port-forward` only, and
`.github/workflows/k8s.yml` refuses `NodePort`/`LoadBalancer`/`Ingress` in that overlay.
Nothing in this ADR loosens the local shape; it adds a second, production shape beside it.

### Why no second switch

The brief for T7.7 left the guard's design open: require `cistern.owner.web-id`, or introduce
an explicit `cistern.enforcement=on` that registers enforcement even without an owner. The
first was chosen because enforcement without a root owner cannot be a posture. WAC denies by
default; a storage root with no ACL is reachable by nobody — including the operator — and
there is no way in to write the ACL that would fix it. Every working configuration with
enforcement on therefore names a root owner, and a switch that could be set without one adds a
state that is either a brick or a synonym for the owner spelled differently. The change
production actually needed was to separate the owner's **WebID** (who owns the root, and so
whether enforcement is on) from the owner's **token** (one way, of three, for that WebID to
authenticate): the WebID alone now seeds the root ACL and turns enforcement on
(`Owner.isNamed()`); the token only adds the local resolver (`Owner.hasLocalCredential()`).
One fact, one property, and the property the documentation has called "the enforcement
switch" since T5.3.

Two loud-but-allowed shapes remain, on purpose. Nothing configured at all still starts and
still warns `NO_OWNER_CONFIGURED` on every boot (T5.3): refusing would turn "upgrade" into
"brick" for a laptop pod that has never had an owner, and ADR 0001's loopback binding is what
protects it. An owner named with no way to authenticate — no token, no issuer, no service
principals, no contributed resolver — starts enforced and warns `ENFORCEMENT_WITHOUT_CREDENTIAL`
where the resolver chain is wired: safe (deny by default) but inert, and a public read-only pod
whose ACLs were written on disk is a legitimate use of exactly that shape.

## Consequences

- `infra/terraform` becomes a deployable production path: no external IP on the instance,
  Cloud NAT for egress (image pull, JWKS fetch), a global HTTPS load balancer with a
  Google-managed certificate on 443 and nothing on 80, a firewall admitting only the load
  balancer's and IAP's ranges, an optional Cloud Armor per-IP rate limit, the image from GHCR
  by pinned tag, the auth configuration read from Secret Manager at boot (never in tfvars or
  state), a daily snapshot schedule on the data disk, and `terraform output base_url`. The
  `0.0.0.0/0` variable validation of ADR 0001 is gone because the instance has no address to
  open; the workflow's apply confirmation phrase changes from `no-authorization-layer-yet` to
  `adr-0002-conditions-met`. **Nothing was applied by this PR**; the GCP project stays idle
  until ValueDocs' operator applies in their own project.
- `k8s/` becomes `base` + `overlays/local` + `overlays/production`. The production overlay adds
  an Ingress with TLS, a NetworkPolicy admitting only the ingress controller's namespace,
  resource limits, and the owner/OIDC environment from a Secret; a single replica because the
  file backend is single-writer (so no PodDisruptionBudget — there is nothing to spread).
- `docs/deploy.md` describes the production topology, what to back up, and the restore drill;
  `docs/INTEGRATION.md` step 8 says the same in the application's voice.
- The load-bearing thing an operator can get wrong is now the base URL and the certificate's
  domain disagreeing, or a Secret with the wrong keys — both fail loudly (minted URIs name the
  wrong origin; the guard refuses to start). What still cannot be caught by the server is
  condition 4, which is why it is written down.
- Revisit when DPoP lands (T4.1–T4.4: bearer secrets over TLS become sender-constrained
  tokens), when the object-storage backend lands (#95: the single-writer constraint and the
  disk-snapshot backup shape both change), and when the MCP front door needs the (user, client)
  principal shape (#89).
