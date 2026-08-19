# Cistern on GCP — one instance, one disk, TLS in front

One Container-Optimized OS instance running the Cistern image, the pod on an attached
persistent disk, behind a global HTTPS load balancer with a Google-managed certificate. This
is the production shape of [ADR 0002](../../docs/adr/0002-production-posture.md): TLS
terminated in front, 443 and nothing else, no public address on the instance, credentials
from Secret Manager, daily snapshots. It is what a firm — ValueDocs first — applies **in its
own project**. `docs/deploy.md` is the operator's guide; this file is the Terraform's.

**Nothing here has been applied by EnrichMeAI.** The GCP project `cistern-503016` stays idle;
its spend is zero. What has been verified is `terraform validate` and a `terraform plan`
against placeholder values (24 resources; see the PR for #94).

## Topology

```
client ──https──▶ global forwarding rule :443 ──▶ target HTTPS proxy (managed cert, TLS ≥1.2)
                                                       │  Cloud Armor: N req/min per IP → 429
                                                       ▼
                                                 backend service (HTTP, TCP health check)
                                                       │  only Google's LB ranges may reach :3000
                                                       ▼
                        instance (COS, no external IP, tag cistern-pod) ── docker run cistern
                              │  /mnt/disks/cistern-data ◀── pd-balanced disk ◀── daily snapshots
                              │  /run/cistern/env       ◀── Secret Manager (fetched at boot, tmpfs)
                              └─ egress via Cloud NAT (image pull, JWKS fetch)
                        IAP: ssh :22 and the pod port :3000 for smoke tests, no public port
```

Why an HTTPS load balancer and not a Caddy sidecar: the COS container declaration runs one
container, so a sidecar means a second orchestration mechanism on the VM; a managed
certificate needs no port 80, no ACME state on the data disk and no renewal job; the
instance keeps no public address; and the rate limit attaches at the load balancer and
nowhere else. The price is one forwarding rule (~USD 18/month).

Why a VM and a disk and not Cloud Run and a bucket: `FileResourceStore` writes
tmp-then-`ATOMIC_MOVE`, which is what keeps a crash mid-write from leaving a torn resource.
gcsfuse implements rename as copy-then-delete, so a bucket mounted into Cloud Run voids that
silently. A persistent disk is a real filesystem; an object-native backend is #95.

## What it creates

| Resource | Purpose |
|---|---|
| `google_compute_instance.pod` | COS instance, no external IP, shielded, OS Login; the startup script mounts the disk, fetches the env secret and runs the container |
| `google_compute_disk.data` + `resource_policy` + attachment | 10 GB pd-balanced data disk with `prevent_destroy`; daily crash-consistent snapshots, 14 days retained, kept if the disk is deleted |
| `google_service_account.pod` + secret IAM member | an identity that can read exactly one secret and write logs |
| `google_secret_manager_secret.env` | the container for the authentication environment — the value is added out of band |
| `google_compute_router` + `router_nat` | egress for a VM with no address |
| `google_compute_firewall.lb`, `.iap` | pod port from the load balancer's ranges; SSH and pod port from IAP; nothing else |
| `global_address`, `managed_ssl_certificate`, `ssl_policy`, `health_check`, `instance_group`, `backend_service`, `url_map`, `target_https_proxy`, `global_forwarding_rule` | the HTTPS load balancer, 443 only, TCP health check |
| `google_compute_security_policy.edge` (optional) | Cloud Armor per-IP throttle, `rate_limit_per_minute` |
| `google_project_service.*` | compute, iam, secretmanager — enabled, never disabled on destroy |

Three things the variables refuse: an image tag of `:latest` or none, a domain that is not a
bare hostname, a negative rate limit. And one thing the startup script refuses: an env
secret containing `CISTERN_OWNER_TOKEN` (ADR 0002 condition 3).

## Applying it in your project

Roles the applying identity needs on the project: `roles/compute.admin`,
`roles/iam.serviceAccountAdmin`, `roles/iam.serviceAccountUser`,
`roles/secretmanager.admin`, `roles/serviceusage.serviceUsageAdmin` (or simply project
Owner for the first apply). State: a versioned GCS bucket of your own, or leave the backend
local for a single operator — the `backend "gcs" {}` block is partial, so `terraform init
-backend-config="bucket=<yours>" -backend-config="prefix=cistern"` fills it in.

```bash
cd infra/terraform
cp terraform.tfvars.example terraform.tfvars      # project_id, region, zone, domain
terraform init -backend-config="bucket=<your-state-bucket>" -backend-config="prefix=cistern"
terraform plan -out=tfplan
terraform apply tfplan                            # ~24 resources; the certificate starts PROVISIONING
```

Then, in this order:

1. **DNS.** `terraform output load_balancer_ip` → an `A` record for `domain`. The managed
   certificate goes `ACTIVE` some minutes after the record resolves (up to an hour);
   `terraform output certificate_status_command` watches it. Until then 443 answers with a
   default certificate.
2. **The authentication environment.** Write `cistern.env` (below) and load it:
   `terraform output env_secret_add_command`. The instance reads the *latest* version at
   boot; the first boot before a version exists fails, deliberately, and retries on reset.
3. **Reset the instance** so the startup script runs with the secret present:
   `terraform output upgrade_command`.
4. **Smoke test** through the load balancer once the certificate is active —
   `k8s/demo.sh` runs against any URL: `CISTERN_BASE=https://<domain>` with the
   owner's credential — or through IAP before it is (`terraform output tunnel_command`,
   then `CISTERN_BASE=http://localhost:3000`, remembering that identifiers will still name
   the public origin because `cistern.base-url` does).
5. **Run the restore drill** (`docs/deploy.md`, `infra/restore-drill.sh`) before real
   documents go in, and again after any storage change.

### The authentication environment (`cistern.env`)

One env file, `KEY=VALUE` per line, no quotes, no `export`. Boot's relaxed binding maps
`cistern.owner.web-id` to `CISTERN_OWNER_WEBID` and `cistern.auth.service-principals[0].web-id`
to `CISTERN_AUTH_SERVICEPRINCIPALS_0_WEBID` (dashes removed, dots to underscores).

```dotenv
# The pod owner. Setting this turns Web Access Control ON and seeds the root ACL for it.
CISTERN_OWNER_WEBID=https://valuedocs.co.in/profile#admin
# NOT CISTERN_OWNER_TOKEN — the startup script refuses it. The owner authenticates below.

# Humans (and, if you prefer OAuth client credentials, the applications) through your IdP.
CISTERN_AUTH_OIDC_ISSUER=https://id.valuedocs.co.in/realms/valuedocs
CISTERN_AUTH_OIDC_AUDIENCES=cistern
CISTERN_AUTH_OIDC_WEBID_CLAIM=webid

# Each application as itself: its own WebID, its own hashed secret (sha256:<hex>).
# printf '%s' "$SECRET" | shasum -a 256 | cut -d' ' -f1 | sed 's/^/sha256:/'
CISTERN_AUTH_SERVICEPRINCIPALS_0_WEBID=https://valuedocs.co.in/apps/legal#id
CISTERN_AUTH_SERVICEPRINCIPALS_0_CREDENTIALHASH=sha256:…
CISTERN_AUTH_SERVICEPRINCIPALS_1_WEBID=https://valuedocs.co.in/apps/tax#id
CISTERN_AUTH_SERVICEPRINCIPALS_1_CREDENTIALHASH=sha256:…

# Optional: further pods with their own owners, provisioned idempotently at boot.
# CISTERN_PODS_SEED_0_ROOT=/firms/acme/
# CISTERN_PODS_SEED_0_OWNERWEBID=https://acme-law.example/profile#firm
```

Until the IdP is wired, the owner can hold a **hashed** credential instead: one more
`SERVICEPRINCIPALS` entry whose WebID is the owner's. It is still a bearer secret in
transit — TLS is what makes that acceptable — but it is `sha256:` at rest, which the local
token never was.

The server enforces the pairing: an issuer or a service principal **without**
`CISTERN_OWNER_WEBID` is a start-up refusal (`ENFORCEMENT_REQUIRES_OWNER`), because
enforcement is keyed on the owner and the credentials would otherwise never be asked for.
`docker logs cistern` on the instance shows the banner.

**Rotating** anything: add a new secret version, reset the instance. Nothing is cached
across a boot; nothing is on the disk.

### Upgrading

Change `image` in `terraform.tfvars` to the next pinned tag, `terraform apply` (an in-place
metadata change; the instance is not restarted), then `terraform output upgrade_command`. The
startup script pulls the new tag and replaces the container on boot. Downtime is the reboot
plus JVM start — this is a single-writer service and there is no rolling update to have.

### Backups and restore

The unit is the data disk: `cistern.storage.root` **including `.cistern/`**, the decision log.
The daily snapshot is crash-consistent; because every write is tmp-then-`ATOMIC_MOVE`, a
snapshot contains each resource either before or after a write and never torn. To restore
to a new disk and verify it, `docs/deploy.md` has the drill; the disk-level part is:

```bash
gcloud compute snapshots list --filter="sourceDisk~cistern-data" --sort-by=~creationTimestamp --limit=3
gcloud compute disks create cistern-data-restore --source-snapshot=<snapshot> --zone=<zone> --type=pd-balanced
# attach to a scratch instance (or this one, stopped), mount, run infra/restore-drill.sh against it
```

`on_source_disk_delete = KEEP_AUTO_SNAPSHOTS`, so even a destroyed disk leaves its
snapshots; `prevent_destroy` on the disk means a routine plan cannot destroy it at all.

## Cost, roughly

Nothing is running under EnrichMeAI's project, so this is what an apply starts costing —
approximate list prices, one small firm's traffic:

| Item | Monthly (approx, USD) |
|---|---|
| `e2-small`, always on | ~13–15 |
| 20 GB boot + 10 GB data disk, pd-balanced | ~3 |
| Daily snapshots, 14 retained (incremental) | ~1 |
| Global forwarding rule (the load balancer) | ~18 + data processing |
| Cloud Armor policy with two rules | ~7 (0 with `rate_limit_per_minute = 0`) |
| Cloud NAT, one VM | ~1 + data |
| Secret Manager, one secret | pennies |
| **Total** | **~USD 45/month** (~£35) |

Run the `finops-estimate` skill (or the pricing calculator) for a current figure in your
region before the first apply.

## Local use (no credentials)

```bash
terraform init -backend=false && terraform validate
terraform fmt -recursive -check
# A plan without a cloud: local backend override + a placeholder token; nothing is contacted.
printf 'terraform {\n  backend "local" {}\n}\n' > backend_override.tf
terraform init -reconfigure
GOOGLE_OAUTH_ACCESS_TOKEN=placeholder terraform plan -var project_id=p -var domain=pod.example.org
rm backend_override.tf
```

## EnrichMeAI's own bootstrap (2026-07-20) — CI plumbing only

Kept for the record; it concerns `cistern-503016`, not a firm's project. **No pod, no VM,
no disk** exists there.

| Thing | Value |
|---|---|
| State bucket | `gs://cistern-503016-tfstate` — europe-west2, versioned, uniform bucket-level access, public access prevention *enforced* |
| Deployer SA | `cistern-deploy@cistern-503016.iam.gserviceaccount.com` |
| Project roles | `compute.admin`, `iam.serviceAccountAdmin`, `iam.serviceAccountUser` — **not yet** `secretmanager.admin` or `serviceusage.serviceUsageAdmin`, which this configuration now needs; grant them before the first plan/apply from CI |
| Bucket role | `storage.admin` **on the state bucket only**, not project-wide |
| WIF pool / provider | `github` / `github-oidc` (issuer `token.actions.githubusercontent.com`) |
| Repo variables | `GCP_PROJECT_ID`, `GCP_WORKLOAD_IDENTITY_PROVIDER`, `GCP_SERVICE_ACCOUNT`, `GCP_TF_STATE_BUCKET`, and now `CISTERN_DOMAIN` |
| Environment | `test-pod`, required reviewer `josepharuja` |

**No service-account key was ever created or downloaded.** Authentication is keyless via
GitHub's OIDC token. Two independent restrictions bound who can use it — the provider's
attribute condition `assertion.repository=='enrichmeai/cistern'`, and the impersonation
binding scoped to `…/attribute.repository/enrichmeai/cistern` rather than the whole pool.
Verify either at any time:

```bash
gcloud iam workload-identity-pools providers describe github-oidc \
  --project=cistern-503016 --location=global --workload-identity-pool=github \
  --format='value(attributeCondition)'
gcloud iam service-accounts get-iam-policy \
  cistern-deploy@cistern-503016.iam.gserviceaccount.com --project=cistern-503016
```

Rolling it back is four deletions, and nothing depends on them while no pod is deployed:

```bash
gcloud iam service-accounts delete cistern-deploy@cistern-503016.iam.gserviceaccount.com --project=cistern-503016
gcloud iam workload-identity-pools providers delete github-oidc --workload-identity-pool=github --location=global --project=cistern-503016
gcloud iam workload-identity-pools delete github --location=global --project=cistern-503016
gcloud storage rm -r gs://cistern-503016-tfstate
```
