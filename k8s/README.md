# Running Cistern on Kubernetes

Two overlays over one base:

| | `k8s/overlays/local` | `k8s/overlays/production` |
|---|---|---|
| For | Docker Desktop, minikube, kind | a real cluster with an ingress controller, cert-manager (or your own certificate) and a CNI that enforces NetworkPolicy |
| Reached by | `kubectl port-forward` only — no Ingress, no TLS | an Ingress terminating TLS; the Service stays `ClusterIP` behind it |
| The owner authenticates with | a bearer token from the `cistern-owner` Secret | a JWT from your OIDC issuer, or a hashed service credential — **no owner token** |
| Posture | [ADR 0001](../docs/adr/0001-local-only-until-phase-5.md), kept as the development shape | [ADR 0002](../docs/adr/0002-production-posture.md) |
| Held by CI | `.github/workflows/k8s.yml` refuses `NodePort`/`LoadBalancer`/Ingress here | the same workflow requires an Ingress with `tls:`, a NetworkPolicy, `IfNotPresent`, one replica, and no `CISTERN_OWNER_TOKEN` |

`k8s/base/` is not meant to be applied on its own: it sets no base URL and no credentials.
The operator's guide — topology, backups, the restore drill — is
[`docs/deploy.md`](../docs/deploy.md); this file is about these manifests.

**Check your context first.** `kubectl apply -k` deploys to whatever cluster
`kubectl config current-context` names.

## Read this first

Cistern enforces **Web Access Control** (T5.1–T5.4): every request crosses one enforcement
point, deny by default. Enforcement is switched on by naming the pod's owner
(`CISTERN_OWNER_WEBID`); the root ACL is then seeded granting that WebID full access and
everything else is denied — an anonymous `GET`, `PUT` or `DELETE` gets `401`, and
`GET`/`HEAD` report exactly what the caller holds in `WAC-Allow`. How the owner
*authenticates* is a separate matter, and it is what tells the two overlays apart: a
local bearer token is fine on a laptop and has no place behind a public address.

Credentials configured without an owner are refused at start-up (the enforcement guard,
T7.7): a pod that looked locked in its configuration but enforced nothing would be the
worst outcome, so the server will not come up in that state.

> If you only want to run it, the one-line `docker run` of the published image in the
> top-level [README](../README.md#run-it-pull-the-image) is simpler. Reach for these
> manifests when you want to exercise it the way it is deployed.

## Local: `k8s/overlays/local`

The manifests pin the published release image, `ghcr.io/enrichmeai/cistern:0.1.0`
(linux/amd64 and linux/arm64), so there is nothing to build:

```bash
kubectl config use-context docker-desktop      # or minikube, kind-*
kubectl apply -k k8s/overlays/local

# Required: the owner's identity and credential. Setting these turns Web Access Control
# ON — the root ACL is seeded granting this WebID full access and everything else is
# denied. Without the secret the pod will not start, deliberately: silently running with
# no authorization layer is the failure this prevents.
kubectl create secret generic cistern-owner -n cistern \
  --from-literal=web-id='https://you.example/profile/card#me' \
  --from-literal=token="$(openssl rand -hex 32)"

kubectl rollout status -n cistern deploy/cistern

kubectl port-forward -n cistern svc/cistern 3737:3000
curl -X PUT -H 'Content-Type: text/turtle' \
  --data '<#me> <http://xmlns.com/foaf/0.1/name> "Joseph" .' \
  http://127.0.0.1:3737/notes/hello                          # 401: anonymous
curl -H "Authorization: Bearer $TOKEN" http://127.0.0.1:3737/notes/hello
```

Tear down:

```bash
kubectl scale -n cistern deploy/cistern --replicas=0     # stop it, KEEP the data
kubectl delete -k k8s/overlays/local                     # deletes the PVC too — data gone
kubectl delete namespace cistern                         # same, plus the namespace
```

Note that `delete -k` **does** remove the PVC, because `pvc.yaml` is part of the base.
Scale to zero if you want the pod's contents to survive.

### Running your own build instead

The local-build path is the one to use while changing the server. Build the image, point
the overlay at it, apply as above:

```bash
docker build -t cistern:local .
(cd k8s/overlays/local && kustomize edit set image ghcr.io/enrichmeai/cistern=cistern:local)
kubectl apply -k k8s/overlays/local
```

`kustomize edit` writes an `images:` block into the overlay's `kustomization.yaml`
(`newName: cistern`, `newTag: local`); don't commit that. With a local tag the pull policy
should be `Never` — the tag exists in no registry, and `Never` makes a missing local image
fail immediately rather than after a pull attempt — which is a one-line patch if you need
it. On minikube also `minikube image load cistern:local`. To move to a newer release, the
same knob: `kustomize edit set image ghcr.io/enrichmeai/cistern:0.2.0`.

### The demo

```bash
kubectl port-forward -n cistern svc/cistern 3737:3000 &
CISTERN_TOKEN=<the token you generated> ./k8s/demo.sh
```

It shows a **negative**, which is the point. An agent with no grant is refused; the owner
writes a rule — a file in the pod: *read `/notes/`, nothing else*; the agent can now read
the note but not delete it and not reach `/private/`; the owner deletes the rule and the
agent's very next request is refused again — no restart, no token reissued — while the
owner is unaffected. Then the owner asks for the **receipt** (T5.9): `GET /notes/week?receipts`
lists every decision about the note — the allow naming `/notes/.acl` as the rule that granted
it, the denies naming no rule — while the agent's own attempt to read the receipts is refused,
because receipts take Control, not Read. Before T5.3 an anonymous `DELETE` returned `204` and
the note was gone. A demo whose climax is *successful* access is a file browser; see
`docs/demo/walkthrough.md`.

The script needs no cluster: point `CISTERN_BASE` at any running Cistern (the jar on
`--server.port=3737`, or a production instance over https) and pass the owner's credential
in `CISTERN_TOKEN`. "The agent" is any caller without the owner's credential; the grant is
class-based (`foaf:Agent`) because the per-agent principal is not built yet.

### Ports, and running several instances at once

The local side of the forward defaults to **3737**, matching `docker-compose.yml`. Port
3000 is avoided deliberately: it is crowded on a dev machine, and a stray Grafana or
another compose stack holding it does not fail loudly — it silently answers your requests
instead, which reads as Cistern misbehaving. (That is not hypothetical; it happened while
these manifests were being written.)

The port *inside* the pod is always 3000 and never collides, so only the forward changes:

```bash
kubectl port-forward -n cistern svc/cistern 3801:3000
```

If you do that, change `CISTERN_BASE_URL` in `k8s/overlays/local/deployment-patch.yaml` to
match. It mints `Location` headers and the storage description, so a mismatch makes the pod
hand out URIs naming an origin you are not calling — a failure that surfaces far from its
cause.

### minikube

The published image pulls from GHCR like anywhere else. A **local build** is different:
Docker Desktop shares its daemon with the cluster so `cistern:local` is immediately
visible; minikube does not, so load it explicitly before applying:

```bash
minikube image load cistern:local
```

## Production: `k8s/overlays/production`

What it adds to the base: an **Ingress** terminating TLS (cert-manager annotation, or a
certificate you provision into the `cistern-tls` Secret), a **NetworkPolicy** admitting only
the ingress controller's namespace (and Google's load-balancer ranges, for GKE Ingress),
production **resource figures**, `CISTERN_AUDIT_REQUIRED=true`, and the authentication
environment from the **`cistern-auth` Secret** — with no owner token anywhere. The image is
the pinned GHCR tag with `imagePullPolicy: IfNotPresent`. Still **one replica** and still
`strategy: Recreate`: the file backend is single-writer, so there is nothing to spread, and
no `PodDisruptionBudget` — one with `minAvailable: 1` on a single replica would only block
node drains without buying availability.

Prerequisites on the cluster: an ingress controller (the annotations are ingress-nginx's;
adapt for another), cert-manager with a `ClusterIssuer` named `letsencrypt-production` *or*
your own certificate, a default `StorageClass` — ideally one with volume snapshots, for
backups — and a CNI that enforces NetworkPolicy (Calico, Cilium, GKE Dataplane V2; Docker
Desktop does not, see *What was verified*).

```bash
# 1. The domain — ONE edit. kustomization.yaml copies the Ingress host into the TLS hosts
#    and into CISTERN_BASE_URL, so the certificate, the route and the minted origin agree.
sed -i '' 's/pod\.example\.org/pod.valuedocs.co.in/' k8s/overlays/production/ingress.yaml  # or edit spec.rules[0].host by hand

# 2. Render and read what you are about to apply.
kubectl kustomize k8s/overlays/production

# 3. Apply, then the authentication environment (see auth-secret.example.yaml for the keys).
kubectl config use-context <the firm's production cluster>
kubectl apply -k k8s/overlays/production
kubectl create secret generic cistern-auth -n cistern --from-env-file=cistern.env
kubectl rollout status -n cistern deploy/cistern
kubectl logs -n cistern deploy/cistern | grep -E 'Principal resolvers|Decision log|Seeded root'

# 4. When the certificate is issued (kubectl get certificate -n cistern), smoke it:
curl -i https://pod.valuedocs.co.in/                     # 401, WWW-Authenticate, X-Request-Id
CISTERN_BASE=https://pod.valuedocs.co.in CISTERN_OWNER_WEBID=<owner> CISTERN_TOKEN=<owner credential> ./k8s/demo.sh
```

The `cistern-auth` Secret is where **who may use this pod** lives — `CISTERN_OWNER_WEBID`
(turns enforcement on), `CISTERN_AUTH_OIDC_*`, `CISTERN_AUTH_SERVICEPRINCIPALS_n_*`; the
annotated example is [`overlays/production/auth-secret.example.yaml`](overlays/production/auth-secret.example.yaml).
It carries no `CISTERN_OWNER_TOKEN`, and CI refuses the overlay if one ever appears in it.
Rotate anything by replacing the Secret and `kubectl rollout restart`; nothing is cached
across a boot.

Backups: the PVC is `cistern.storage.root` **including `.cistern/`**, the decision log.
Snapshot it on a schedule with your cluster's `VolumeSnapshotClass` and run the restore
drill (`docs/deploy.md`, `infra/restore-drill.sh`) before real documents go in.

Rate limiting at the edge is the `limit-rps` / `limit-burst-multiplier` annotations on the
Ingress — ingress-nginx answers `503` above the limit unless its ConfigMap sets
`limit-req-status-code: "429"`; set it, so applications can tell a slow-down from an
outage. `X-Request-Id`: ingress-nginx forwards a client's and mints one when absent, Cistern
honours a well-formed one and echoes it, so the controller's access log and the pod's
receipts share one value.

## Why these choices

Most of this is ordinary, but four decisions are load-bearing and easy to get wrong.

**`replicas: 1` and `strategy: Recreate`.** The file backend is a single-writer design with
no distributed locking. The default `RollingUpdate` starts the new pod before terminating
the old one, which against a `ReadWriteOnce` volume either wedges the rollout on a
multi-node cluster or — worse, on a single node — briefly runs two writers over the same
data directory. `Recreate` scales to zero first.

**All probes are TCP, not `httpGet`.** The storage root legitimately returns `404` until
pods are provisioned (T5.4) and `401` to an anonymous probe once an owner is set. An HTTP
probe treats either as failure and crash-loops a perfectly healthy server. Same reasoning as
the Dockerfile's healthcheck and the Terraform health check.

**`fsGroup: 10001`.** The image runs as uid 10001 with `readOnlyRootFilesystem`. Without
`fsGroup`, a freshly provisioned PVC arrives root-owned and the container starts fine, then
fails on the first write — a failure that looks like an application bug.

**An `emptyDir` at `/tmp`.** `readOnlyRootFilesystem: true` is otherwise fatal: the JVM
needs somewhere to write `hsperfdata`.

## What was verified

Applied to Docker Desktop Kubernetes v1.25.4 on 2026-07-21 (the manifests that are now
`base` + `overlays/local`; the overlay renders byte-for-byte the same objects, checked with
a normalised diff when the split was made for T7.7):

- pod reaches `Ready`, PVC binds
- `PUT` → `201`, `GET` → `200`, minted URIs match `CISTERN_BASE_URL`
- container runs as uid/gid `10001`
- **data survives the pod being deleted and rescheduled** — the point of the PVC
- container listing still shows correct `ldp:contains` afterwards

Two negative results, both verified rather than assumed:

- The namespace's `restricted` Pod Security Standard **is** enforced — a test pod without a
  compliant `securityContext` was refused outright.
- The `NetworkPolicy` is **not** enforced on Docker Desktop. A second pod in the namespace
  reached Cistern (`HTTP 200`) despite the deny-all ingress rule, because Docker Desktop's
  default CNI ignores NetworkPolicy. Treat the local policy as a statement of intent there
  and a real control on a cluster running Calico or Cilium. **`ClusterIP` is what actually
  keeps this unreachable locally** — do not rely on the NetworkPolicy alone. The same
  caveat applies to the production policy: deploy it on a CNI that enforces, and verify
  once from a pod in another namespace.

The production overlay has been rendered and schema-validated (`kubectl kustomize` +
`kubeconform -strict`, 6 resources) and its CI guards exercised in both directions; it has
**not** yet been applied to a cluster with an ingress controller — that is ValueDocs'
step 3 above, on their cluster.
