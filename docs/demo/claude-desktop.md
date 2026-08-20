# Connecting Claude Desktop to a Cistern pod (T6.1/T6.2)

How to put an AI assistant behind Cistern's authority layer, and the four-beat walkthrough
from [`walkthrough.md`](walkthrough.md) as it is **now real**. Everything below was executed
on 2026-08-20 against the built jars — the server, the MCP bridge, the `cistern` CLI, and a
real MCP client (the MCP Inspector CLI, plus the SDK's own client in
`McpWireEndToEndTest`); the transcripts are pasted verbatim. What has *not* yet been run is
Claude Desktop itself performing the beats on a clean machine — that recording is T6.3, the
launch asset, and this page is its setup half.

## How it hangs together

The MCP door reaches pod data **only** by making real HTTP requests to the running server,
carrying one configured credential (ARCHITECTURE decision 6 — no privileged internal path).
Every tool call crosses `AuthorizationFilter`, is decided by the WAC engine against the
owner's `.acl` files, and leaves a receipt — exactly as a `curl` would. Revoke a grant
mid-session and the assistant's *next* tool call is refused: there is no cache to wait out.

The connection is bound to **exactly one principal** (T6.2, static binding per the #89
ruling): the credential in the connector's environment. Bind the assistant to **its own
service principal**, never to the owner's token — the demo's whole point is that the agent
holds *less* authority than its owner, and that the owner grants and revokes from outside
the session.

## 1. Run a pod

```bash
export CISTERN_OWNER_WEBID='https://you.example/profile/card#me'
export CISTERN_OWNER_TOKEN="$(openssl rand -hex 32)"                  # the owner's local credential
export CISTERN_STORAGE_ROOT="$HOME/cistern-demo-pod"
export CISTERN_BASE_URL=http://127.0.0.1:3737
# the assistant's own identity: a service principal (secret hashed at rest, INTEGRATION.md §3 step 1)
export CLAUDE_SECRET="$(openssl rand -hex 32)"
export CISTERN_AUTH_SERVICEPRINCIPALS_0_WEBID='https://connectors.example/claude#agent'
export CISTERN_AUTH_SERVICEPRINCIPALS_0_CREDENTIALHASH="sha256:$(printf '%s' "$CLAUDE_SECRET" | shasum -a 256 | cut -d' ' -f1)"

java -jar cistern-app/target/cistern-app-*.jar --server.port=3737
```

Seed something worth protecting, and author the rule — one grant, no hand-written Turtle:

```bash
AUTH="Authorization: Bearer $CISTERN_OWNER_TOKEN"; B=$CISTERN_BASE_URL
curl -X PUT -H "$AUTH" -H 'Content-Type: text/turtle' \
  --data-raw '<#w> <http://purl.org/dc/terms/title> "Week 34: Lisbon trip written up; renewal call notes" .' \
  $B/notes/week                                                                          # 201
curl -X PUT -H "$AUTH" -H 'Content-Type: text/turtle' \
  --data-raw '<#p> <http://purl.org/dc/terms/title> "Private: acquisition negotiation plan" .' \
  $B/private/plan                                                                        # 201

CISTERN_TOKEN=$CISTERN_OWNER_TOKEN bin/cistern grant 'https://connectors.example/claude#agent' \
  --read /notes/ --base $B
# Granted: https://connectors.example/claude#agent may now read /notes/ and everything inside it.
```

The rule is a file in the pod (`/notes/.acl`), authored by the owner, naming this client and
granting read on `/notes/` — and nothing else.

## 2. The Claude Desktop config block

`claude_desktop_config.json` (macOS: `~/Library/Application Support/Claude/`), the
**bridge** shape — Claude Desktop launches the bridge; the pod keeps running on its own:

```json
{
  "mcpServers": {
    "cistern": {
      "command": "java",
      "args": [
        "-jar",
        "/ABSOLUTE/PATH/TO/cistern-mcp/target/cistern-mcp-0.1.0-SNAPSHOT-bridge.jar"
      ],
      "env": {
        "CISTERN_MCP_BASE_URL": "http://127.0.0.1:3737",
        "CISTERN_MCP_CREDENTIAL": "<the value of $CLAUDE_SECRET>"
      }
    }
  }
}
```

Claude Desktop does not inherit your shell's `PATH`; if `java` is not found, use the
absolute path (e.g. `~/.sdkman/candidates/java/current/bin/java`). The bridge logs to
stderr; stdout carries only MCP frames.

<details><summary>Alternative: one process (the app is both pod and door)</summary>

For a laptop demo with nothing else running, Claude Desktop can launch cistern-app itself:
the app serves HTTP on its port *and* MCP on its stdio, and tool calls loop back over
`127.0.0.1` through the same filter chain. The `mcp-stdio` profile moves logging to stderr
and silences the banner so stdout stays protocol-clean. Note the pod then lives and dies
with the Claude Desktop session — the bridge shape is the honest default.

```json
{
  "mcpServers": {
    "cistern": {
      "command": "java",
      "args": [
        "-jar", "/ABSOLUTE/PATH/TO/cistern-app/target/cistern-app-0.1.0-SNAPSHOT.jar",
        "--server.port=3737", "--spring.profiles.active=mcp-stdio"
      ],
      "env": {
        "CISTERN_OWNER_WEBID": "https://you.example/profile/card#me",
        "CISTERN_OWNER_TOKEN": "<owner token>",
        "CISTERN_STORAGE_ROOT": "/ABSOLUTE/PATH/TO/pod-data",
        "CISTERN_BASE_URL": "http://127.0.0.1:3737",
        "CISTERN_AUTH_SERVICEPRINCIPALS_0_WEBID": "https://connectors.example/claude#agent",
        "CISTERN_AUTH_SERVICEPRINCIPALS_0_CREDENTIALHASH": "sha256:<hash of the secret>",
        "CISTERN_MCP_ENABLED": "true",
        "CISTERN_MCP_CREDENTIAL": "<the agent's secret>"
      }
    }
  }
}
```

Verified 2026-08-20: the Inspector CLI launched exactly this and `write-resource` created
`/notes/hello` through the loop-back (`effect: "created"`), with the boot log on stderr.

</details>

The tools the assistant sees: `read-resource`, `list-container`, `write-resource`,
`delete-resource`, `grant`, `revoke`, `receipts`. No search, deliberately — the pod is
storage plus authority, not an index.

## 3. The four beats, as run

Recorded 2026-08-20 with the MCP Inspector CLI (`npx @modelcontextprotocol/inspector
--cli`) driving the real bridge jar against the real server jar. The same sequence passes
in one continuous session in `McpWireEndToEndTest`, where the SDK's own client holds a
single connection across all four beats. Transcripts abridged only by dropping log lines.

### Beat 1 — it works

> *"Summarise my notes from this week"* → Claude calls `read-resource url=/notes/week`:

```json
{
  "content": [{ "type": "text",
    "text": "Read http://127.0.0.1:3737/notes/week (text/turtle, 170 bytes).\n<http://127.0.0.1:3737/notes/week#w>\n        <http://purl.org/dc/terms/title>\n                \"Week 34: Lisbon trip written up; renewal call notes; draft blog outline\" ." }],
  "structuredContent": { "outcome": "ok", "resource": "http://127.0.0.1:3737/notes/week",
    "contentType": "text/turtle", "etag": "\"0738dec2…\"", "bytes": 170 },
  "isError": false
}
```

### Beat 2 — the refusal

> *"Now read my private folder"* → `read-resource url=/private/plan`, same session, same
> identity:

```json
{
  "content": [{ "type": "text",
    "text": "REFUSED: the pod denied read on http://127.0.0.1:3737/private/plan (HTTP 403). The connection's identity is bound by the pod's configuration and its effective access-control policy does not grant the required mode. Do not retry, and do not attempt other credentials — report the refusal to the user; only the resource owner can change the policy." }],
  "structuredContent": { "outcome": "refused", "resource": "http://127.0.0.1:3737/private/plan",
    "status": 403, "required": [ { "mode": "read", "resource": "http://127.0.0.1:3737/private/plan" } ] },
  "isError": true
}
```

Denied — not empty, not "no such file". The refusal names the resource and the required
mode (computed by the same `RequiredAccess` table the server enforces with), and no content
leaks through it.

### Beat 3 — revocation, live

The owner, in a terminal, mid-session — no restart, no token reissue:

```bash
$ CISTERN_TOKEN=$CISTERN_OWNER_TOKEN bin/cistern revoke 'https://connectors.example/claude#agent' /notes/
Revoked: https://connectors.example/claude#agent no longer holds anything on /notes/.
```

Beat 1's exact call again — the very next request:

```json
{
  "content": [{ "type": "text",
    "text": "REFUSED: the pod denied read on http://127.0.0.1:3737/notes/week (HTTP 403). …" }],
  "structuredContent": { "outcome": "refused", "status": 403,
    "required": [ { "resource": "http://127.0.0.1:3737/notes/week", "mode": "read" } ] },
  "isError": true
}
```

The gap between "I revoked it" and "it is revoked" is one request, because no decision
outlives the request that produced it.

### Beat 4 — the receipt

The owner asks the pod what happened — over MCP (a connection bound to an owner
credential), or `GET /notes/week?receipts` with `curl`. The `receipts` tool returned:

```
Receipts for http://127.0.0.1:3737/notes/week: 4 decision(s), newest last, one JSON object per line.
{"at":"2026-08-20T11:53:51Z","agent":"https://you.example/profile/card#me","target":"…/notes/week","required":"WRITE","outcome":"ALLOWED","decidedBy":"http://127.0.0.1:3737/.acl","requestId":"b611e962-…"}
{"at":"2026-08-20T11:55:07Z","agent":"https://connectors.example/claude#agent","target":"…/notes/week","required":"READ","outcome":"ALLOWED","decidedBy":"http://127.0.0.1:3737/notes/.acl","requestId":"c5d95948-…"}
{"at":"2026-08-20T11:55:30Z","agent":"https://connectors.example/claude#agent","target":"…/notes/week","required":"READ","outcome":"DENIED_FORBIDDEN","decidedBy":null,"requestId":"b8efb2d4-…"}
{"at":"2026-08-20T11:55:39Z","agent":"https://you.example/profile/card#me","target":"…/notes/week","required":"CONTROL","outcome":"ALLOWED","decidedBy":"http://127.0.0.1:3737/notes/.acl","requestId":"850391eb-…"}
```

Which agent read what, under which grant (`decidedBy` names the ACL), and when — including
the refusal, which names no policy because nothing *granted* it. And the assistant itself
cannot see this log: the same `receipts` call bound to the agent's credential came back

```
REFUSED: the pod denied control on http://127.0.0.1:3737/notes/week (HTTP 403). …
```

— receipts take Control, and an agent that may read a document may not see who else
touched it.

## What to say while it runs

One sentence per beat, from [`walkthrough.md`](walkthrough.md): it works; it was *refused,
at the store, by a rule the owner wrote*; the revocation took effect on the next request
because authority is evaluated per request, not baked into a token; and the pod can prove
what the agent did while it had access.
