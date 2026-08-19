# Deploying Cistern

The operator's guide: what a Cistern instance is made of, what protects it, how a firm
(ValueDocs first) runs its own, what to back up, and how to prove the backup works.
[`k8s/README.md`](../k8s/README.md) and [`infra/terraform/README.md`](../infra/terraform/README.md)
are about the manifests and the Terraform; this is about the deployment. The posture is
[ADR 0002](adr/0002-production-posture.md); the application's view of the same steps is
[`INTEGRATION.md`](INTEGRATION.md), step 8.

## Read this first

Cistern enforces **Web Access Control** on every request — deny by default, no privileged
route, every allow and every deny written to a receipt before the response goes out
(T5.1–T5.4, T5.9). An anonymous `DELETE` is `401`, not `204`. That is why, unlike the
previous version of this document, it may face the internet — **under conditions**, and the
conditions are the point of this guide:

1. **TLS terminated in front** — a load balancer or Ingress; Cistern itself speaks plain HTTP
   on a listener only the terminator can reach; nothing but 443 is open to the world.
2. **Enforcement on: `cistern.owner.web-id` set.** It names the owner of the storage root,
   registers the enforcement filter, and seeds the root ACL. It is the only enforcement switch.
3. **`cistern.owner.token` unset.** The local bearer token is plaintext at rest and was only
   ever for a private network. In production the owner authenticates like everyone else —
   through the firm's OIDC issuer, or with a hashed service credential carrying the owner's
   WebID until the IdP is wired.
4. **Per-tenant isolation** — one instance per firm (its own volume, namespace or project,
   IAM, owner); several firms on one instance only where every firm trusts the operator.
5. **Backups that have been restored at least once** — `cistern.storage.root` *including*
   `.cistern/`, the decision log; a snapshot schedule and [the restore drill](#the-restore-drill).
6. **`X-Request-Id` across the edge** — the application sends one, the edge forwards or mints
   it, Cistern honours it and writes it into the receipt.
7. **Rate limiting at the edge** — Cloud Armor or the ingress controller; Cistern has none.
8. **`cistern.audit.required=true`** — a decision the log cannot record is not acted on (`503`).

The server enforces what it can. A credential source (`cistern.auth.oidc.issuer`,
`cistern.auth.service-principals[]`) configured **without** `cistern.owner.web-id` is a
start-up refusal — the credentials would never be asked for and the pod would be open while
its configuration read as locked:

```
***************************
APPLICATION FAILED TO START
***************************

Description:

Failed to bind properties under 'cistern' to com.enrichmeai.cistern.webflux.CisternProperties:

    Reason: java.lang.IllegalArgumentException: Credentials are configured (cistern.auth.service-principals[]) but cistern.owner.web-id is not, so Web Access Control would be OFF and no credential would ever be asked for: anyone could read, write and delete without one. Set cistern.owner.web-id to the WebID that owns the storage root — that is what turns enforcement on and seeds the root ACL. cistern.owner.token is not required: leave it unset where the owner authenticates through the OIDC issuer or a service credential (docs/adr/0002-production-posture.md).
```

Two shapes remain allowed and are said out loud on every boot: nothing configured at all
(`NO_OWNER_CONFIGURED` — a laptop pod, unprotected, ADR 0001's loopback is what guards it),
and an owner named with no way to authenticate (`ENFORCEMENT_WITHOUT_CREDENTIAL` —
enforced, inert until a credential or a public grant exists). Conditions 1 and 4–7 are the
operator's to hold; the rest of this document is how.

## Local (unchanged)

```bash
docker compose up --build      # http://127.0.0.1:3737, bound to loopback
docker compose down            # keeps the volume
docker compose down -v         # deletes the data too
```

`docker compose` passes no owner, so the pod is unprotected and warns so; export
`CISTERN_OWNER_WEBID` and `CISTERN_OWNER_TOKEN` into it (or use the jar, or
`k8s/overlays/local`) to run it enforced. The port is published as `127.0.0.1:3737:3000`,
never `3737:3000`: Docker's default publish binds `0.0.0.0` and **bypasses most host
firewalls**. A local bearer token behind `0.0.0.0` is ADR 0001's open pod with one extra
step; keep it on loopback.

## Production topology — option A

ValueDocs runs Cistern **as its own service, in its own cloud org**. Every firm that
follows does the same. Nothing is shared across firms: not the instance, not the volume,
not the owner, not the IAM.

```
                         the firm's cloud project / cluster namespace
   ┌───────────────────────────────────────────────────────────────────────────────┐
   │  edge: TLS, 443 only, rate limit, access log            (LB or Ingress)       │
   │    │  X-Request-Id forwarded (or minted)                                       │
   │    ▼                                                                          │
   │  cistern  ── CISTERN_BASE_URL=https://pod.<firm>      one replica, one writer │
   │    │  owner: CISTERN_OWNER_WEBID (enforcement on, root ACL seeded)             │
   │    │  credentials: OIDC issuer + hashed service principals; NO owner token     │
   │    │  audit required                                                           │
   │    ▼                                                                          │
   │  cistern.storage.root  = the pod  +  .cistern/decisions/  (receipts)          │
   │    persistent disk / PVC, snapshotted daily, restored on a drill              │
   └───────────────────────────────────────────────────────────────────────────────┘
       ▲ legal app (its own WebID + secret)   ▲ tax app (its own)   ▲ lawyers (JWT)
       ▲ the firm's administrator: the owner WebID, via the IdP — holds Control
```

- **Applications hold only their own credential** — a hashed service principal each
  (`cistern.auth.service-principals[]`), or OAuth client credentials at the IdP. A grant to
  the legal app is not a grant to the tax app (#89, v1 ruling).
- **The owner/Control credential is the firm administrator's**, through the IdP: a JWT whose
  `webid` claim is `cistern.owner.web-id`. It is never shipped to an application.
- **Pods beneath the root** — per matter, per client — are provisioned with
  `cistern.pods.seed[]` or `cistern pod create`, each with an owner who has an OIDC identity.
- **Per-tenant isolation is physical**: one instance per firm. The alternative — several
  firms as separate pod roots on one instance under one operator owner — is permitted only
  when every firm trusts that operator, because the operator's WebID holds Control over the
  storage root and therefore over everything beneath it. That is a business fact to be
  true before it is a configuration, and it is why the default is one instance each.

Two ways to build it, same posture:

| | GCP, no Kubernetes — [`infra/terraform`](../infra/terraform/README.md) | Kubernetes — [`k8s/overlays/production`](../k8s/README.md#production-k8soverlaysproduction) |
|---|---|---|
| TLS | global HTTPS load balancer, Google-managed certificate, TLS ≥ 1.2; 443 only, no port 80 | Ingress with cert-manager (or your certificate in `cistern-tls`) |
| Reachability | instance has **no external IP**; firewall admits the LB's and IAP's ranges only; egress via Cloud NAT | Service stays `ClusterIP`; NetworkPolicy admits only the ingress controller's namespace |
| Credentials | Secret Manager secret, fetched at boot onto tmpfs; `CISTERN_OWNER_TOKEN` refused | `cistern-auth` Secret, `envFrom`; CI refuses the overlay if `CISTERN_OWNER_TOKEN` appears |
| Storage | pd-balanced disk, `prevent_destroy`, daily snapshot policy (14 days, kept on disk delete) | RWO PVC; your `VolumeSnapshotClass` on a schedule |
| Rate limit | Cloud Armor, per-IP, `429` | ingress-nginx `limit-rps` annotations (`429` once the ConfigMap sets `limit-req-status-code`) |
| Request id | forwarded untouched; Cistern mints when absent | ingress-nginx forwards or mints `X-Request-ID` |
| Image | pinned GHCR tag; `:latest` refused by validation; upgrade = change tag, apply, reset | pinned GHCR tag, `IfNotPresent`; CI refuses `:latest` |
| Verified | `terraform validate` + `plan` (24 resources) against placeholders; **not applied** | `kubectl kustomize` + `kubeconform -strict` (6 resources); guards both ways; **not applied to an ingress cluster** |

Pick Terraform for a firm without a cluster; pick the overlay if the firm already runs
Kubernetes. Do not pick Cloud Run + a bucket, for the reason below.

### Configuration checklist

| Property | Env | Production value |
|---|---|---|
| `cistern.base-url` | `CISTERN_BASE_URL` | `https://pod.<firm>` — the public origin, exactly; it mints every identifier and it is **part of the data** (see backups) |
| `cistern.owner.web-id` | `CISTERN_OWNER_WEBID` | the firm administrator's WebID; **required** — turns enforcement on |
| `cistern.owner.token` | `CISTERN_OWNER_TOKEN` | **unset** |
| `cistern.auth.oidc.issuer` / `.audiences` / `.webid-claim` | `CISTERN_AUTH_OIDC_ISSUER` / `_AUDIENCES` / `_WEBID_CLAIM` | the firm's issuer, verbatim; `cistern`; `webid` |
| `cistern.auth.service-principals[n].web-id` / `.credential-hash` | `CISTERN_AUTH_SERVICEPRINCIPALS_n_WEBID` / `_CREDENTIALHASH` | one per application; `sha256:<hex>` of a secret from `openssl rand -hex 32` |
| `cistern.pods.seed[n].root` / `.owner-web-id` | `CISTERN_PODS_SEED_n_ROOT` / `_OWNERWEBID` | optional: `/firms/<client>/` pods with OIDC-identified owners |
| `cistern.audit.required` | `CISTERN_AUDIT_REQUIRED` | `true` |
| `cistern.storage.root` | `CISTERN_STORAGE_ROOT` | `/data` — the mounted disk or PVC |
| `cistern.audit.root` | `CISTERN_AUDIT_ROOT` | leave unset: `<storage root>/.cistern`, so one snapshot carries the pod and its receipts |

Env names follow Boot's relaxed binding: dots to underscores, dashes removed, upper case.

## Storage: a disk, never a bucket

The file backend writes tmp-then-`ATOMIC_MOVE` (`FileResourceStore`), which is what makes a
crash mid-write leave either the old resource or the new one and never a torn one. It is
also what makes a disk snapshot safe (below). **gcsfuse does not implement atomic rename** —
it copies then deletes — so a bucket mounted into Cloud Run voids that guarantee silently:
nothing errors, the property just stops holding. It is also slow for many small objects,
which is exactly what file-per-resource produces.

| Option | Verdict |
|---|---|
| VM + persistent disk (`infra/terraform`) or a PVC (`k8s/overlays/production`) | **Yes.** Real filesystem, atomic rename, snapshots. |
| Cloud Run + GCS bucket mount | **No.** Breaks atomic rename; poor small-object performance. |
| Cloud Run + Filestore | Preserves rename semantics, but Filestore's minimum instance is far too expensive for one firm's pod. |
| A native object-storage backend (#95) | **The right long-term answer**; the storage SPI exists for it. GCS/S3 object writes are atomic per object, so it needs no rename. A ticket, not a workaround. |

The backend is **single-writer**: one replica, `Recreate` strategy, no rolling update. An
upgrade is a restart. That is a property of this backend, and #95 is where it changes.

## Backups

### What to back up

**The whole of `cistern.storage.root`, including `.cistern/`.** The pod's documents and
ACLs are files under the root; the decision log — every receipt — is
`.cistern/decisions/YYYY-MM-DD.jsonl` beside them, written through the same storage SPI
but outside the pod's URI space. A restore without `.cistern/` restores the data but not the
account of who touched it, and the receipts are the product. One disk, one snapshot, both.

Two things are part of the data that do not look like it:

- **`cistern.base-url`.** Every ACL names absolute IRIs under it (`acl:accessTo
  <https://pod.<firm>/matters/2026-114/>`), and so does every receipt. A restore brought up
  under a *different* base URL has ACLs that match nothing and an owner who is refused. The
  drill below boots the restored copy with the **original** base URL even though it listens
  on loopback — the base URL is what requests are resolved against, not what is dialled.
- **The authentication environment** (the Secret Manager secret / the `cistern-auth`
  Secret). It is not on the disk, on purpose, and it is in no snapshot; back it up where you
  keep secrets. Without it a restored disk boots unprotected — and says so.

Not needed: the container, the image (it is a pinned tag on GHCR), the `.meta/` sidecars
separately (they are under the root and come with it).

### Snapshot schedule

| Where | How | Default in this repo |
|---|---|---|
| GCE (`infra/terraform`) | `google_compute_resource_policy` daily snapshot, attached to the data disk | 03:00 UTC, 14 days retained, snapshots **kept** if the disk is deleted, `prevent_destroy` on the disk |
| Kubernetes | a `VolumeSnapshot` per day from your CSI driver's `VolumeSnapshotClass` (a CronJob that applies one, or the cloud's backup product) | bring your own; the PVC is the unit |
| compose / a VM without snapshots | `docker run --rm -v cistern-data:/from:ro -v $PWD:/backup <image> tar czf /backup/cistern-$(date -u +%F).tgz -C /from .` on a cron; ship the archive off the host | what the drill does |

A persistent-disk snapshot is **crash-consistent**: a point-in-time image of the blocks.
Because every write is tmp-then-`ATOMIC_MOVE`, such an image contains each resource either
before or after a write and never torn — the crash-safety property doubles as the snapshot
property, and no quiescing is needed. Retention: long enough to notice a mistake (a bulk
delete, a bad ACL) and go back before it — two weeks is the floor, not the ceiling.

### The restore drill

A backup that has never been restored is a hypothesis. `infra/restore-drill.sh` turns it
into a fact, and is what you run **before real documents go in and again after any change
to storage or to the image**:

```
snapshot the source  →  restore into a NEW volume  →  boot Cistern on it  →  smoke test
```

PASS means, on the restored copy: anonymous read of a note → `401` (enforcing); owner read →
`200` (data and root ACL came across); the restored `/.acl` is byte-identical to the source's
after boot (the seeder does not re-seed an existing ACL — a restart is not a request to reset
permissions); the note's receipts include a decision made **before** the snapshot (`.cistern/`
came across) and one made **after** the restore (the log is live on the restored volume).

```bash
# First run, against a fresh source volume it seeds itself (a note, a receipt):
CISTERN_IMAGE=ghcr.io/enrichmeai/cistern:<the tag production runs> infra/restore-drill.sh --seed

# Against the real thing — a docker volume, or a directory where you mounted a disk
# restored from a snapshot — with production's base URL and owner:
CISTERN_SOURCE=cistern-data   CISTERN_BASE_URL=https://pod.valuedocs.co.in \
CISTERN_OWNER_WEBID=https://valuedocs.co.in/profile#admin \
CISTERN_IMAGE=ghcr.io/enrichmeai/cistern:<tag> infra/restore-drill.sh
```

The drill authenticates as the owner with a throwaway token that exists only for the
loopback instance it boots (there is no owner token in production; the drill needs no IdP).
`--keep` leaves the restored container and volume for inspection.

On GCE the disk-level step comes first:

```bash
gcloud compute snapshots list --filter="sourceDisk~cistern-data" --sort-by=~creationTimestamp --limit=3
gcloud compute disks create cistern-data-restore --source-snapshot=<name> --zone=<zone> --type=pd-balanced
gcloud compute instances attach-disk <instance> --disk=cistern-data-restore --device-name=restore --zone=<zone>
# on the instance: mount /dev/disk/by-id/google-restore /mnt/disks/restore, then
CISTERN_SOURCE=/mnt/disks/restore CISTERN_BASE_URL=https://pod.<firm> CISTERN_OWNER_WEBID=… CISTERN_IMAGE=… infra/restore-drill.sh
```

On Kubernetes: a `VolumeSnapshot` → a PVC → a Job that runs the script against the mount,
or copy the volume to a machine with docker and run it there. The script is the same.

#### Transcript

Run locally on 2026-08-19 against Docker Desktop (docker 20.10.22) with `cistern:local`
built from this branch (the receipts checks need T5.9, which `0.1.0` predates — drill with
the tag production runs, which from the next release carries it):

```
$ CISTERN_IMAGE=cistern:local infra/restore-drill.sh --seed
Restore drill 20260819T105823Z
   image                                                      cistern:local
   source                                                     cistern-drill-source
   base URL (the original, by design)                         http://localhost:3737
   owner                                                      https://you.example/profile/card#me
   archive                                                    /tmp/cistern-drill/cistern-snapshot-20260819T105823Z.tgz

0. Seeding the source: a note and a receipt, so there is something to restore
   owner PUT /drill/note                                      201
   owner GET /drill/note  (X-Request-Id: drill-seed-20260819T105823Z) 200
   anon  GET /drill/note                                      401
   seed container stopped                                     ok

1. Snapshot: archive the whole storage root, .cistern/ included
   archive size                                               4.0K
   files archived                                             22
   decision-log files in the archive (.cistern/decisions/*.jsonl) 1
   source /.acl sha256                                        9b7943943b42ecb1…

2. Restore: a new volume, never the source
   restored into volume                                       cistern-restore-20260819T105823Z
   restored /.acl sha256                                      9b7943943b42ecb1…
   restored .cistern/decisions/*.jsonl                        1 file(s)
   decisions carried over (lines, before boot)                4

3. Boot: the same image, the original base URL, the restored volume
   listening (loopback, random port)                          127.0.0.1:64792

4. Smoke: the restored pod is enforcing, the data is there, the receipts came with it
   anon  GET /                                                401   (expect 401: enforcing on the restored data)
   owner GET /  (X-Request-Id: drill-restore-20260819T105823Z) 200   (expect 200: root ACL came across, owner matches)
   owner GET /drill/note                                      200   (200 when the drill note is in the source; 404 otherwise)
   /.acl after boot vs source                                 identical (not re-seeded)
   owner GET /?receipts                                       200 application/x-ndjson, 5 receipt(s) about /
   receipt from AFTER the restore (drill-restore-20260819T105823Z) present
   decisions on the restored volume (lines, after smoke)      9   (expect > 4: the log is live)
   receipt from BEFORE the snapshot (drill-seed-20260819T105823Z) present
      receipts for /drill/note:
      {"at":"2026-08-19T10:58:25.703895501Z","agent":"https://you.example/profile/card#me","target":"http://localhost:3737/drill/note","required":"WRITE","outcome":"ALLOWED","decidedBy":"http://localhost:3737/.acl","requestId":"256434e7-1202-4e82-bd68-924cad426c53"}
      {"at":"2026-08-19T10:58:25.739270751Z","agent":"https://you.example/profile/card#me","target":"http://localhost:3737/drill/note","required":"READ","outcome":"ALLOWED","decidedBy":"http://localhost:3737/.acl","requestId":"drill-seed-20260819T105823Z"}
      {"at":"2026-08-19T10:58:25.771227876Z","agent":null,"target":"http://localhost:3737/drill/note","required":"READ","outcome":"DENIED_UNAUTHENTICATED","decidedBy":null,"requestId":"d8ff4c54-6c73-425b-9753-4b8b3e84cb7b"}
      {"at":"2026-08-19T10:58:30.441510211Z","agent":"https://you.example/profile/card#me","target":"http://localhost:3737/drill/note","required":"READ","outcome":"ALLOWED","decidedBy":"http://localhost:3737/.acl","requestId":"ab35e75c-52a3-4ad0-b613-f320e4f4cb08"}
      {"at":"2026-08-19T10:58:31.138178336Z","agent":"https://you.example/profile/card#me","target":"http://localhost:3737/drill/note","required":"CONTROL","outcome":"ALLOWED","decidedBy":"http://localhost:3737/.acl","requestId":"1919ab58-f9b4-4d2d-ac3f-298196cb6744"}

Verdict
PASS restore drill 20260819T105823Z: enforcing, data present, root ACL intact, receipts carried over and live.
   archive: /tmp/cistern-drill/cistern-snapshot-20260819T105823Z.tgz
```

What the transcript shows, line by line: the source was seeded with a note and a receipt
bearing a known request id; the archive carried `.cistern/decisions/*.jsonl`; the restored
`/.acl` hash equals the source's before and after boot; anonymous `401`, owner `200`; and the
receipts for the note list **both** the pre-snapshot decision and the post-restore one.

## The edge: TLS, rate limiting, request id

- **TLS** is terminated in front; Cistern does not speak it (v1). `cistern.base-url` is the
  `https://` origin; the certificate's domain and the base URL's host are one value — the
  Terraform derives both from `domain`, the overlay copies the Ingress host into both.
- **Rate limiting** lives at the edge: Cloud Armor per-IP throttle (`rate_limit_per_minute`,
  `429`), or `nginx.ingress.kubernetes.io/limit-rps`. Applications must expect `429` from
  the edge as well as `401`/`403` from the pod, and must not treat `401` as "retry".
- **`X-Request-Id`**: send one from the application on every call. Cistern honours a
  well-formed value (1–128 characters from `A-Za-z0-9._~:/+=-`; UUIDs, ULIDs, trace ids),
  mints a UUID otherwise, echoes it on every response and writes it into the receipt. The
  Google load balancer forwards it untouched (and adds `X-Cloud-Trace-Context` of its own);
  ingress-nginx forwards it and mints one when absent. So the edge's access log, the
  application's log and the pod's receipt share one value — which is what makes a refusal
  explainable after the fact.

## Operations

- **Upgrade**: change the pinned tag (Terraform `image`; the overlay's `images:`), apply,
  restart (`terraform output upgrade_command`; `kubectl rollout restart`). Single-writer:
  the restart is the downtime. Read `CHANGELOG.md` first; a tag without a section fails the
  release, so every tag has one.
- **Rotate a credential**: add a secret version / replace the Secret, restart. Nothing is
  cached across a boot, and decisions are never cached at all — a revoked grant or a
  rotated key takes effect on the next request.
- **Health**: TCP checks only, everywhere (Dockerfile, probes, the LB health check). The
  root answers `401` to an anonymous `GET` once an owner is set and `404` before pods are
  provisioned; an HTTP check would call a healthy, enforcing server dead.
- **Logs**: the boot log names the resolver chain in order (`Principal resolvers, in
  order: …`), the decision log's location and policy (`Decision log: …`), and the seeded
  root ACL. If it names `AnonymousResolver` alone, nobody can authenticate — read the WARN
  above it.
- **Receipts** are the audit trail: `GET <resource>?receipts` (Control on the resource),
  `GET /?receipts&agent=<webid>` (the owner, per agent). Application-side, log the
  `X-Request-Id` you sent with every refusal you handle.

## What is still not there

- **DPoP / sender-constrained tokens** (T4.1–T4.4): bearer secrets and JWTs are protected by
  TLS alone today. When DPoP lands the hashed service credential and the JWT become
  proof-of-possession.
- **An object-storage backend** (#95): lifts the single-writer constraint and changes the
  backup unit from a disk to a bucket.
- **In-process rate limiting**: none, by design; the edge does it.
- **MCP front door** (Phase 6) and the (user, client) principal shape (#89).
