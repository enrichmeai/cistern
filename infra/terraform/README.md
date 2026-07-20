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

## One-time bootstrap (not automated on purpose)

Creating a keyless trust relationship between a GitHub repo and a GCP project is
security-significant, so it is done deliberately by a human, once:

1. **State bucket** — `gcloud storage buckets create gs://<bucket> --project=cistern-503016
   --location=europe-west2 --uniform-bucket-level-access`, with versioning enabled.
2. **Service account** for Terraform, granted `roles/compute.admin`,
   `roles/iam.serviceAccountAdmin`, `roles/iam.serviceAccountUser` and
   `roles/storage.admin` on the state bucket.
3. **Workload Identity Federation** pool and provider for GitHub OIDC, with the principal
   restricted to this repository — never a downloaded service-account key.
4. Enable `compute.googleapis.com` and `iap.googleapis.com`.
5. Set repository **variables**: `GCP_PROJECT_ID`, `GCP_WORKLOAD_IDENTITY_PROVIDER`,
   `GCP_SERVICE_ACCOUNT`, `GCP_TF_STATE_BUCKET`.
6. Create the `test-pod` environment and add required reviewers, so an apply needs a
   second pair of eyes.

Until step 5 is done, the `deploy` job fails fast with that instruction. The `validate`
job needs none of it and runs on every PR.

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
