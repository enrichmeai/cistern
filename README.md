# Cistern

**An open, self-hostable Solid pod server for the AI era.** JVM-native (Spring Boot 4 /
WebFlux), conformance-first, and MCP-fronted — so any AI agent (Claude, ChatGPT, your
in-house bot) can read and write user-owned data *with the user's consent model enforced
by the server, not promised by the vendor*.

> Your agent's memory. Your pod. Your Cistern.

## Why

AI assistants are accumulating the deepest personal profiles ever built — locked inside
each vendor. [Solid](https://solidproject.org) solved the hard parts (identity, storage,
consent) years ago; what it lacked was demand. The AI era supplies the demand, and
[MCP](https://modelcontextprotocol.io) supplies the protocol agents actually speak.
Cistern joins the two: a spec-conformant Solid server whose flagship interface is an MCP
front-end with Web Access Control enforced underneath.

Commercial personal-AI vaults are arriving closed and top-down. Cistern is the open
infrastructure version: Apache 2.0, self-hostable, bring-your-own agent and identity
provider.

## Status

**v0.2.0**, built in the open — what changed is in [CHANGELOG.md](CHANGELOG.md).
Conformance against the official
[Solid test harness](https://github.com/solid-contrib/conformance-test-harness) is the
project's public health metric — numbers only move forward (see `cth/BASELINE.md`), and we
publish it whether or not it flatters us. As of the 28 August 2026 run recorded in
`cth/BASELINE.md`, it reads 0 of 41 cases executed. What has changed
is where the run stops: with an external identity provider configured, the harness now
registers its authenticated test clients against Cistern and gets one step further, halting
in PREPARE SERVER. Its client sends DPoP proofs with no `ath` claim, which RFC 9449 §4.3
requires a resource server to reject, so the request arrives anonymous. We have contributed
the fix upstream
([conformance-test-harness#789](https://github.com/solid-contrib/conformance-test-harness/pull/789)).
The number moves when an unmodified harness produces it.

## Standing on

Cistern implements the [Solid Protocol](https://solidproject.org/TR/protocol), [WAC](https://solidproject.org/TR/wac)
and [Solid-OIDC](https://solidproject.org/TR/oidc). None of this is our design. The Solid
community spent years agreeing on what a personal data store should do and writing it down
precisely enough to build against, then published a
[conformance harness](https://github.com/solid-contrib/conformance-test-harness) that will
tell us when we are wrong — and the [Community Solid Server](https://github.com/CommunitySolidServer/CommunitySolidServer)
gave us a working implementation to check our reading against. We get to build a server
because they did the hard part first.

Roadmap: [docs/BACKLOG.md](docs/BACKLOG.md) · Architecture:
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) · Integrating an application:
[docs/INTEGRATION.md](docs/INTEGRATION.md)

## Quickstart

### Run it (pull the image)

Releases are tagged; each publishes `ghcr.io/enrichmeai/cistern:<version>` for linux/amd64
and linux/arm64, plus the jar and checksums on the
[GitHub Release](https://github.com/enrichmeai/cistern/releases). What changed is in
[CHANGELOG.md](CHANGELOG.md).

```bash
TOKEN=$(openssl rand -hex 32); echo "Owner token: $TOKEN"   # keep this shell open — it's the only copy

docker run -d --rm -p 127.0.0.1:3737:3000 \
  -e CISTERN_BASE_URL=http://localhost:3737 \
  -e CISTERN_OWNER_WEBID='https://you.example/profile/card#me' \
  -e CISTERN_OWNER_TOKEN="$TOKEN" \
  -v cistern-data:/data \
  ghcr.io/enrichmeai/cistern:0.2.0   # -d: runs in the background so $TOKEN stays live below
```

Three things in that command are load-bearing. The port is published on **`127.0.0.1`**,
not `0.0.0.0`: this quickstart runs with a local owner token and no TLS in front, so keep
it on your own machine — going beyond loopback needs the posture in
[`docs/deploy.md`](docs/deploy.md) (TLS terminated in front, a Solid-OIDC issuer,
[ADR 0002](docs/adr/0002-production-posture.md), which superseded the loopback-only
[ADR 0001](docs/adr/0001-local-only-until-phase-5.md) once the authority plane it waited
for was built). `CISTERN_BASE_URL` must be the URL clients
actually call — it mints every resource identifier, and the container listens on 3000
whatever the host port is. And setting the **owner** (`CISTERN_OWNER_WEBID` + `_TOKEN`) is
what turns Web Access Control on: the root ACL is seeded granting that WebID everything,
anyone else gets `401`, and the owner authenticates with `Authorization: Bearer <token>`.
The WebID is an identifier, not a login: Cistern bundles no Solid-OIDC provider to mint one
in v1, and nothing here dereferences it — it's checked only for being an absolute URI and
written into the ACL as-is — so any absolute URI you control, including the placeholder
above, works for this quickstart. Data lives in the `cistern-data` volume and survives
restarts.

The same works from the jar — `java -jar cistern-app-0.2.0.jar` with the same environment
variables (Java 25) — and on a local Kubernetes cluster via [`k8s/`](k8s/README.md).

### Store something, and read it back

Same shell, so `$TOKEN` is still set:

```bash
curl -i -X PUT http://localhost:3737/hello \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: text/turtle' \
  --data-raw '<#it> <http://purl.org/dc/terms/title> "Hello, Cistern." .'
# HTTP/1.1 201 Created

curl http://localhost:3737/hello \
  -H "Authorization: Bearer $TOKEN"
# <http://localhost:3737/hello#it>
#         <http://purl.org/dc/terms/title>
#                 "Hello, Cistern." .
```

An authenticated `PUT` created a resource; an authenticated `GET` read it back. Drop the
`Authorization` header from either and it's a `401` — that refusal, enforced the same way
for a human, a script or an agent, is the rest of this quickstart.

### Connect an agent over MCP

MCP is the flagship interface: an agent authenticates with **its own** credential — never
a copy of the owner's token — and reaches pod data only through the same
Web Access Control this `curl` round trip just exercised.
[`docs/demo/claude-desktop.md`](docs/demo/claude-desktop.md) walks the whole arc: run a pod
with one extra credential for the agent, bind Claude Desktop to it, grant it read on one
folder, watch it get refused everywhere else, then revoke the grant from a terminal and
watch the agent's very next call fail. Running a different MCP host — Claude Code, Codex,
JetBrains Air's agents, Zed — see
[`docs/demo/mcp-clients.md`](docs/demo/mcp-clients.md) for the one config and where each
host wants it.

### Get the CLI

The same Release carries the `cistern` command — pods, grants and revocations without
hand-editing Turtle — as `cistern-cli-0.2.0.jar` (a self-contained executable jar, Java 25)
plus the `cistern` wrapper script, both listed in `SHA256SUMS`. It also carries the MCP
bridge jar (`cistern-mcp-0.2.0-bridge.jar`) an assistant like Claude Desktop launches —
see [`docs/demo/claude-desktop.md`](docs/demo/claude-desktop.md):

```bash
REL=https://github.com/enrichmeai/cistern/releases/download/v0.2.0
curl -fsSLO "$REL/cistern-cli-0.2.0.jar" && curl -fsSLO "$REL/cistern" && chmod +x cistern
export CISTERN_CLI_JAR="$PWD/cistern-cli-0.2.0.jar"   # tells the wrapper where the jar is
export CISTERN_TOKEN='<the owner token from the run above>'

./cistern pod create --root /firms/acme/ --owner 'https://acme-law.example/profile#firm'
./cistern grant 'https://valuedocs.example/apps/legal#id' --read /firms/acme/
./cistern revoke 'https://valuedocs.example/apps/legal#id' /firms/acme/
```

`--base` defaults to `http://127.0.0.1:3737` — where the quickstart above put the server.
Exit codes: `0` ok, `1` failure, `2` refused (the server enforces `acl:Control`; the CLI
only writes the files), `3` conflict (the ACL changed underneath; nothing written). The
full command reference is in [docs/INTEGRATION.md §6.6](docs/INTEGRATION.md#66-cli-90-built-91-built).

### Build it (from source)

```bash
mvn -q verify
docker compose up --build                  # pod server on http://127.0.0.1:3737
./cth/run-cth.sh                           # run the conformance harness (Docker)
```

`docker compose` builds the image as `cistern:local` and runs it on loopback **without an
owner** — no authorization layer, which is fine for hacking on the server and wrong for
holding anything. To run your own build the way the published image runs, use the
`docker run` command above with `cistern:local` in place of the tag.

The CLI you build here (`cistern-cli/target/cistern-cli-*.jar`) also has **`cistern sync`**
(T7.17, #200), which the `0.2.0` release above predates: it mirrors a folder into a pod
container, sending each file once under a conditional `PUT` and remembering what it sent in
`<local-dir>/.cistern-sync.json`, so a second run with nothing changed sends nothing.

```bash
java -jar cistern-cli/target/cistern-cli-*.jar sync ./documents /firms/acme/docs/ --dry-run   # the plan, nothing sent
java -jar cistern-cli/target/cistern-cli-*.jar sync ./documents /firms/acme/docs/             # 201s, then 204s only for what changed
```

`--delete` (off by default) also removes what the folder no longer holds; a copy that changed on
the pod since it was sent is never overwritten (exit `3`). Details in
[docs/INTEGRATION.md §6.6](docs/INTEGRATION.md#66-cli-90-built-91-built).

## Modules

`cistern-core` (LDP semantics, storage SPI) · `cistern-storage-file` · `cistern-webflux`
(HTTP) · `cistern-auth` (Solid-OIDC + DPoP validation) · `cistern-wac` (Web Access
Control) · `cistern-mcp` (the agent front-end) · `cistern-spring-boot-starter` ·
`cistern-app`

## Licence & governance

Apache License 2.0. Copyright © Good Shepherd Software Consultancy Ltd (Company
No. 09702990), trading as **EnrichMeAI**. Contributions require DCO sign-off — see
[CONTRIBUTING.md](CONTRIBUTING.md). The component is free to run, for anyone, for any
purpose; support and integration engineering are optional contracts — see
[COMMERCIAL.md](COMMERCIAL.md).
