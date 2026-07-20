# Test pod infrastructure

One Container-Optimized OS instance running the Cistern image, with the pod on an
attached persistent disk. **Nothing here has been applied** — no GCP resources exist and
spend is zero. See `docs/adr/0001-local-only-until-phase-5.md`.

## Do not apply this yet

Cistern has no authentication and no access control until Phase 5. Verified against the
current build: an anonymous request with no credentials creates a resource (`201`) and
deletes one (`204`), and no `WWW-Authenticate` or `WAC-Allow` header is emitted.

The configuration is closed by default so that the safe shape is what you get from doing
nothing:

- no external IP (`enable_external_ip = false`)
- no ingress firewall beyond Google's IAP forwarders
- `allowed_ingress_cidrs` empty, and `0.0.0.0/0` **rejected by a variable validation** —
  the plan fails rather than the README being ignored
- the data disk carries `prevent_destroy`

Reach it with the IAP tunnel, which opens no port:

```bash
gcloud compute start-iap-tunnel cistern-test 3000 \
  --local-host-port=localhost:3000 --zone=europe-west2-a --project=cistern-503016
```

`terraform output tunnel_command` prints this with your values filled in.

## Why a VM and a disk, not Cloud Run and a bucket

`FileResourceStore` writes tmp-then-`ATOMIC_MOVE`, which is what keeps a crash mid-write
from leaving a torn resource. **gcsfuse implements rename as copy-then-delete**, so
mounting a bucket into Cloud Run voids that guarantee silently — nothing errors, the
property just stops holding. A persistent disk is a real filesystem.

The long-term answer is an object-native storage backend written against the existing
storage SPI, where per-object atomic writes remove the need for rename entirely. That is a
clean ticket, not a workaround.

## Bootstrap — done 2026-07-20

This exists now. It is CI plumbing only: **no pod, no VM, no disk**. Cistern's compute
spend remains zero, and ADR 0001 still forbids an apply until Phase 5.

| Thing | Value |
|---|---|
| State bucket | `gs://cistern-503016-tfstate` — europe-west2, versioned, uniform bucket-level access, public access prevention *enforced* |
| Deployer SA | `cistern-deploy@cistern-503016.iam.gserviceaccount.com` |
| Project roles | `compute.admin`, `iam.serviceAccountAdmin`, `iam.serviceAccountUser` |
| Bucket role | `storage.admin` **on the state bucket only**, not project-wide |
| WIF pool / provider | `github` / `github-oidc` (issuer `token.actions.githubusercontent.com`) |
| Repo variables | `GCP_PROJECT_ID`, `GCP_WORKLOAD_IDENTITY_PROVIDER`, `GCP_SERVICE_ACCOUNT`, `GCP_TF_STATE_BUCKET` |
| Environment | `test-pod`, required reviewer `josepharuja` |

**No service-account key was ever created or downloaded.** Authentication is keyless via
GitHub's OIDC token, so there is no long-lived credential to leak or rotate.

### The two independent restrictions on who can use it

Either would technically suffice; both are set, because the second is the one that is
commonly got wrong and getting it wrong means any repository the pool admits can mint
deploy credentials.

1. **Provider attribute condition** — `assertion.repository=='enrichmeai/cistern'`, so the
   pool only ever exchanges tokens issued to this repository.
2. **Impersonation binding scope** — `roles/iam.workloadIdentityUser` is granted to
   `principalSet://…/workloadIdentityPools/github/attribute.repository/enrichmeai/cistern`,
   **not** to `…/workloadIdentityPools/github/*`.

To verify either at any time:

```bash
gcloud iam workload-identity-pools providers describe github-oidc \
  --project=cistern-503016 --location=global --workload-identity-pool=github \
  --format='value(attributeCondition)'

gcloud iam service-accounts get-iam-policy \
  cistern-deploy@cistern-503016.iam.gserviceaccount.com --project=cistern-503016
```

### Rolling it back

If you ever want this gone, it is four deletions — and nothing depends on them while no
pod is deployed:

```bash
gcloud iam service-accounts delete cistern-deploy@cistern-503016.iam.gserviceaccount.com --project=cistern-503016
gcloud iam workload-identity-pools providers delete github-oidc --workload-identity-pool=github --location=global --project=cistern-503016
gcloud iam workload-identity-pools delete github --location=global --project=cistern-503016
gcloud storage rm -r gs://cistern-503016-tfstate
```

## Local use

```bash
terraform init -backend=false && terraform validate    # no credentials needed
terraform fmt -recursive
```

## Cost, roughly

Nothing is running, so this is what an apply would start costing:

| Item | Monthly (europe-west2, approx) |
|---|---|
| `e2-small`, always on | ~£11–13 |
| 20 GB balanced boot disk | ~£2 |
| 10 GB balanced data disk | ~£1 |
| Egress, IAP tunnel at test volume | pennies |
| **Total** | **~£15/month** |

Stopping the instance when idle drops the compute charge and keeps the disks (~£3/month).
Run the `finops-estimate` skill before the first real apply for a current figure.
