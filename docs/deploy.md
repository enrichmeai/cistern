# Running Cistern

## Read this first

Cistern has **no authentication and no access control**. Phases 4 (auth) and 5 (WAC) are
not built. Verified 2026-07-20 against the current build: an anonymous request carrying no
credentials can create a document (`201`) and delete someone else's (`204`), and no
`WWW-Authenticate` or `WAC-Allow` header is ever emitted.

So the only safe deployment today is one nobody else can reach. Everything below binds to
loopback for that reason. **Do not put this on a public address until Phase 5 lands** — an
open `PUT` endpoint on the internet is not merely a data-loss risk to you, it is free
anonymous storage for whoever finds it.

## Local (the supported way today)

```bash
docker compose up --build      # http://127.0.0.1:3000
docker compose down            # keeps the volume
docker compose down -v         # deletes the data too
```

Data lives in the `cistern-data` named volume and survives restarts and rebuilds.

The port is published as `127.0.0.1:3000:3000`, not `3000:3000`. Docker's default publish
binds `0.0.0.0` and **bypasses most host firewalls**, so the shorter form would expose the
pod to your whole network. Change it only when there is something to stop a stranger
emptying the pod.

### `CISTERN_BASE_URL` is not cosmetic

It mints `Location` headers and the storage description, so it must be the URL clients
actually call. Behind a proxy, a tunnel, or a cloud load balancer, the default
`http://localhost:3000` makes the pod hand out URIs naming an origin nobody can reach.
Set it to the externally visible URL.

## Storage: why a bucket is the wrong shape

The file backend writes tmp-then-`ATOMIC_MOVE` (`FileResourceStore`), which is what makes
a crash mid-write leave either the old resource or the new one and never a torn one.

**gcsfuse does not implement atomic rename** — it copies then deletes. Mounting a GCS
bucket into Cloud Run therefore voids that guarantee silently: nothing errors, the
crash-safety property just stops holding. It is also slow for many small objects, which is
exactly what file-per-resource produces.

So, in order of preference:

| Option | Verdict |
|---|---|
| GCE VM + persistent disk, running this compose file | **Best fit today.** Real POSIX filesystem, atomic rename, cheap, identical to local. |
| Cloud Run + GCS bucket mount | **No.** Breaks atomic rename; poor small-object performance. |
| Cloud Run + Filestore | Preserves rename semantics, but Filestore's minimum instance is far too expensive for a test pod. |
| A native `cistern-storage-gcs` backend | **The right long-term answer**, and the storage SPI exists precisely for this. GCS object writes are atomic per object, so an object-native backend needs no rename at all. A future ticket, not a workaround. |

## Hosting it somewhere (when there is something to protect)

Until Phase 5, if you want it reachable from more than one of your own machines, keep it
private rather than public — a Tailscale/WireGuard network, an SSH tunnel, or a firewall
allowlist limited to your own address. A shared secret in front of an unauthenticated
store is not access control; it is one leak away from the same open pod.
