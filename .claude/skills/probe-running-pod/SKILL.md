---
name: probe-running-pod
description: Answer "does X actually work against Cistern?" by standing up the real server and driving it with a real client, rather than reasoning from the code. Use before claiming an integration works, an SDK is needed, or a client is incompatible.
---

# Probe a running pod

Ground rule 6 is real-first: a mock built from a guess will happily confirm the guess. On
2026-08-22 this settled a build-or-not decision in twenty minutes — `@inrupt/solid-client`
drives Cistern 0.2.0 **8/8 unmodified**, which removed the case for writing our own
TypeScript client.

## Stand the server up so the IRIs line up

Cistern mints every resource identifier from `CISTERN_BASE_URL`. If that is not the URL the
client actually calls, identifiers will not match and reads fail in confusing ways. Put both
on one Docker network and use the in-network address.

```bash
docker network create probe-net
TOKEN=$(openssl rand -hex 32)
docker run -d --name cistern --network probe-net \
  -e CISTERN_BASE_URL=http://cistern:3000 \
  -e CISTERN_OWNER_WEBID='https://you.example/profile/card#me' \
  -e CISTERN_OWNER_TOKEN="$TOKEN" \
  -v probe-data:/data ghcr.io/enrichmeai/cistern:0.2.0
```

The owner WebID is **never dereferenced** — no deref machinery exists in main source; it is
an opaque ACL subject validated only as an absolute URI. The placeholder works as written.

## Known traps

- **`localhost` vs `127.0.0.1` on macOS** — `localhost` resolves to `::1` first and Docker
  binds IPv4 only. The failure looks exactly like a 401 from a misbehaving server.
- **Service-principal env vars use relaxed binding with hyphens removed** —
  `CISTERN_AUTH_SERVICEPRINCIPALS_0_WEBID`, never `..._SERVICE_PRINCIPALS_0_WEB_ID`, which
  binds nothing, silently.
- **Node 23 crashes Inrupt's client** — `@inrupt/solid-client` → `jsonld` →
  `@digitalbazaar/http-client` → `esm@3.2.25`, a native assertion in `InternalModuleStat`.
  Use Node 20 (`docker run --rm --network probe-net -v "$PWD":/w -w /w node:20-slim`).

## Probe the whole loop, including the refusal

A probe that only tests success proves half the product. Cover: read a container, create a
container, write a document, read it back and compare the value, containment lists the
child, binary upload and download, **and an unauthenticated request is refused**.

Print one PASS/FAIL line per operation and a final count, so the result is quotable.

## Clean up what you created

```bash
docker rm -f cistern; docker volume rm probe-data; docker network rm probe-net
```

Remove only the containers, volumes and networks this probe created — other sessions run
these repos concurrently.

## Then record it

A measured result is expensive to reproduce and cheap to lose. Put it in memory and on the
issue it affects, with the version tested and the date.
