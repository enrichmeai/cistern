# Running Cistern on Kubernetes (locally)

## Read this first

Cistern has **no authentication and no access control**. Phases 4 (auth) and 5 (WAC) are
not built: an anonymous request with no credentials creates a resource (`201`) and deletes
one (`204`), and no `WWW-Authenticate` or `WAC-Allow` header is emitted.

These manifests are for a **local cluster only** — Docker Desktop, minikube, kind. Do not
apply them to a shared or internet-reachable cluster until Phase 5 lands. See
`docs/adr/0001-local-only-until-phase-5.md`.

The Service is deliberately `ClusterIP`, never `NodePort` or `LoadBalancer`, so nothing is
reachable from outside the cluster. `kubectl port-forward` is the cluster equivalent of the
`127.0.0.1` binding in `docker-compose.yml`.

> If you only want to run it, `docker compose up --build` is simpler. Reach for these
> manifests when you want to exercise it the way it will eventually be deployed.

## Quickstart

```bash
docker build -t cistern:local .          # the manifests expect this exact tag
kubectl apply -k k8s/

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
  http://127.0.0.1:3737/notes/hello
curl http://127.0.0.1:3737/notes/hello
```

Tear down:

```bash
kubectl scale -n cistern deploy/cistern --replicas=0     # stop it, KEEP the data
kubectl delete -k k8s/                                   # deletes the PVC too — data gone
kubectl delete namespace cistern                         # same, plus the namespace
```

Note that `delete -k` **does** remove the PVC, because `pvc.yaml` is part of the
kustomization. Scale to zero if you want the pod's contents to survive.

### The demo

```bash
kubectl port-forward -n cistern svc/cistern 3737:3000 &
CISTERN_TOKEN=<the token you generated> ./k8s/demo.sh
```

It shows a **negative**, which is the point: the owner stores a note and reads it with
`WAC-Allow` reporting exactly what they hold, then an agent with no grant is refused a
`GET` and a `DELETE` — and the note is still there. Before T5.3 that `DELETE` returned
`204` and the note was gone. A demo whose climax is *successful* access is a file browser;
see `docs/demo/walkthrough.md`.

## Ports, and running several instances at once

The local side of the forward defaults to **3737**, matching `docker-compose.yml`. Port
3000 is avoided deliberately: it is crowded on a dev machine, and a stray Grafana or
another compose stack holding it does not fail loudly — it silently answers your requests
instead, which reads as Cistern misbehaving. (That is not hypothetical; it happened while
these manifests were being written.)

The port *inside* the pod is always 3000 and never collides, so only the forward changes:

```bash
kubectl port-forward -n cistern svc/cistern 3801:3000
```

If you do that, change `CISTERN_BASE_URL` in `deployment.yaml` to match. It mints
`Location` headers and the storage description, so a mismatch makes the pod hand out URIs
naming an origin you are not calling — a failure that surfaces far from its cause. Unlike
`docker-compose.yml`, which derives both from `${CISTERN_HOST_PORT}`, the manifest cannot
compute this for you.

### minikube

The manifests use `imagePullPolicy: Never`, since `cistern:local` exists in no registry.
Docker Desktop shares its daemon with the cluster so a local build is immediately visible;
minikube does not, so load it explicitly:

```bash
minikube image load cistern:local
```

## Why these choices

Most of this is ordinary, but four decisions are load-bearing and easy to get wrong.

**`replicas: 1` and `strategy: Recreate`.** The file backend is a single-writer design with
no distributed locking. The default `RollingUpdate` starts the new pod before terminating
the old one, which against a `ReadWriteOnce` volume either wedges the rollout on a
multi-node cluster or — worse, on a single node — briefly runs two writers over the same
data directory. `Recreate` scales to zero first.

**All probes are TCP, not `httpGet`.** The storage root legitimately returns `404` until
pods are provisioned (T5.4). An HTTP probe treats that as failure and crash-loops a
perfectly healthy server. Same reasoning as the Dockerfile's healthcheck.

**`fsGroup: 10001`.** The image runs as uid 10001 with `readOnlyRootFilesystem`. Without
`fsGroup`, a freshly provisioned PVC arrives root-owned and the container starts fine, then
fails on the first write — a failure that looks like an application bug.

**An `emptyDir` at `/tmp`.** `readOnlyRootFilesystem: true` is otherwise fatal: the JVM
needs somewhere to write `hsperfdata`.

## What was verified

Applied to Docker Desktop Kubernetes v1.25.4 on 2026-07-21:

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
  default CNI ignores NetworkPolicy. Treat `networkpolicy.yaml` as a statement of intent
  there and a real control on a cluster running Calico or Cilium. **`ClusterIP` is what
  actually keeps this unreachable locally** — do not rely on the NetworkPolicy alone.

## Not included, on purpose

No Ingress, no `LoadBalancer`, no TLS, no HPA. Every one of those makes an unauthenticated
data store easier to reach, and none of them is useful before there is something to
protect. Revisit at the Phase 5 exit, alongside `infra/terraform/`.
