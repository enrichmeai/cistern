# ADR 0001 — Cistern runs locally only until the authority plane exists

- **Status:** accepted
- **Date:** 2026-07-20
- **Deciders:** project owner

## Context

A GCP project (`cistern-503016`, billing enabled) was created to host a pod for testing.
Before anything was deployed we established, by running the current build rather than
reading the backlog, that Cistern has no authority plane at all: Phase 4 (authentication)
and Phase 5 (authorization/WAC) are unbuilt, an anonymous request carrying no credentials
can create a document (`201`) and delete an existing one (`204`), and no
`WWW-Authenticate` or `WAC-Allow` header is ever emitted.

An open `PUT` endpoint on a public address is not only a data-loss risk to the owner; it
is anonymous storage for anyone who finds it.

Separately, the storage backend constrains the plausible cloud shapes. `FileResourceStore`
writes tmp-then-`ATOMIC_MOVE`, which is what makes a crash mid-write leave either the old
resource or the new one and never a torn one. gcsfuse implements rename as copy-then-delete,
so a GCS bucket mounted into Cloud Run would void that guarantee **silently** — nothing
errors; the property simply stops holding.

## Decision

Cistern runs **locally only, via `docker compose`, bound to `127.0.0.1`**, until the
authorization engine (Phase 5) is in place. No cloud deployment before then.

No GCP resources have been created. The project was inspected read-only (`projects
describe`, `services list`); Cloud Run, Artifact Registry and Cloud Build were never
enabled, and current spend attributable to Cistern is zero.

## Consequences

- The published port is `127.0.0.1:3000:3000`, never `3000:3000`. Docker's default publish
  binds `0.0.0.0` and bypasses most host firewalls.
- Real documents go into the pod only after the authority plane exists. This is absolute,
  and it is the sequencing constraint most likely to be violated by momentum: a working
  `docker compose up` reads as "ready", and it is not.
- Being reachable from a second machine before Phase 5, if ever needed, is done with a
  private network (Tailscale/WireGuard, SSH tunnel, or a firewall allowlist) — not a shared
  secret in front of an unauthenticated store, which is one leak away from the same open pod.
- When cloud hosting does happen, the target is a GCE VM with a persistent disk, not Cloud
  Run over a bucket. The long-term answer is an object-native storage backend written
  against the existing storage SPI (ground rule 5), where per-object atomic writes remove
  the need for rename entirely.
- The GCP project stays idle. Revisit at the Phase 5 exit.
