# Connecting any MCP host to a Cistern pod

One config, many hosts. [`claude-desktop.md`](claude-desktop.md) walks the full four-beat
demo end to end against one client; this page is the same JSON for every other host that
speaks MCP — Claude Code, Codex, JetBrains Air's agents, Zed — so each gets one short
subsection naming where the JSON goes, not a repeat of the transcript.

## The principle, in three sentences

The connection is bound to **the agent's own credential**, never a copy of the pod owner's
token — one service principal, granted a folder, named in every receipt it produces. Every
tool call is a real HTTP request through the same Web Access Control engine as a `curl`
round trip, so the agent reads only what the owner's `.acl` files grant it, and nothing
else. What happened — every allow and every deny — is queryable afterwards from the pod
itself, not from the agent's own logs. The mechanics (identity binding, grant, revoke,
receipts) are in [INTEGRATION.md §4a](../INTEGRATION.md#step-4a--connect-an-assistant-mcp-t61t62-3738).

## The stdio entry

Every host below accepts this shape; it is the same bridge jar INTEGRATION §4a and
`claude-desktop.md` use:

```json
{
  "mcpServers": {
    "cistern": {
      "command": "java",
      "args": [
        "-jar",
        "/ABSOLUTE/PATH/TO/cistern-mcp-0.2.0-bridge.jar"
      ],
      "env": {
        "CISTERN_MCP_BASE_URL": "http://127.0.0.1:3737",
        "CISTERN_MCP_CREDENTIAL": "<the agent's service-principal secret>"
      }
    }
  }
}
```

Built from source instead, the jar is `cistern-mcp/target/cistern-mcp-<snapshot-version>-bridge.jar`
(wildcard: `cistern-mcp-*-bridge.jar`) — `main` stays at `<next>-SNAPSHOT` permanently, so
that path never carries a dotted release version.

## The remote entry

> **From v0.3.0.** The Streamable HTTP transport (`WebFluxStreamableTransport`, T6.7, #151)
> merged after the released v0.2.0 and is not in that jar. Run a server built from source
> (or a later tag, once one ships) with `cistern.mcp.http.enabled=true` to try this shape
> today.

```json
{
  "mcpServers": {
    "cistern": {
      "type": "http",
      "url": "https://<pod>/mcp",
      "headers": {
        "Authorization": "Bearer <the agent's service-principal secret>"
      }
    }
  }
}
```

The endpoint is always `/mcp` (`CisternMcpConfiguration.MCP_HTTP_ENDPOINT`), whatever host
the pod runs on. This is the shape a client that is not a local subprocess needs — a
browser-hosted agent, a team's shared connection, anything that cannot launch a jar next to
itself.

Not every host takes both shapes:

| Host | stdio | HTTP (remote, v0.3.0+) |
|---|---|---|
| Claude Desktop | yes | not with a static bearer header — Desktop's remote connectors use OAuth, which is T6.4, not yet built |
| Claude Code | yes | yes |
| Codex | yes | yes |
| JetBrains Air (per agent, over ACP) | yes — ACP requires every agent to support it | yes, when the chosen agent advertises `mcpCapabilities.http` |
| Zed | yes | yes |

## Where the JSON goes, per host

### Claude Desktop

Already written up — see [`claude-desktop.md`](claude-desktop.md) for the config file
location (`claude_desktop_config.json`) and the full four-beat transcript.

### Claude Code

Project scope: `.mcp.json` at the repository root, checked into version control and shared
with the team; the same object nests under `mcpServers` as above, with an explicit
`"type": "stdio"` or `"type": "http"`. User and local scopes live in `~/.claude.json`
instead. Source: [code.claude.com/docs/en/mcp](https://code.claude.com/docs/en/mcp).

### Codex

`~/.codex/config.toml` (or `.codex/config.toml` for a trusted project), as TOML rather than
JSON:

```toml
[mcp_servers.cistern]
command = "java"
args = ["-jar", "/ABSOLUTE/PATH/TO/cistern-mcp-0.2.0-bridge.jar"]

[mcp_servers.cistern.env]
CISTERN_MCP_BASE_URL = "http://127.0.0.1:3737"
CISTERN_MCP_CREDENTIAL = "<the agent's service-principal secret>"
```

The remote shape carries `url` plus `bearer_token_env_var` (an environment variable holding
the bearer token) rather than an inline header. Source:
[learn.chatgpt.com/docs/extend/mcp](https://learn.chatgpt.com/docs/extend/mcp?surface=cli).

### JetBrains Air

Settings → **AI | MCP Servers**, backed by a `mcp.json` at global, `.air/mcp.json` (local)
or the shared `.mcp.json` (workspace) scope — same `mcpServers` object as above. Source:
[jetbrains.com/help/air/mcp-servers.html](https://www.jetbrains.com/help/air/mcp-servers.html).

Air runs Claude Agent, Codex, Junie, Copilot and OpenCode side by side, plus any other ACP
agent, and hands each the same server list through ACP's `session/new.mcpServers`: a stdio
entry (`name`, `command`, `args`, `env`) that every ACP agent must support, or an HTTP entry
(`type: "http"`, `name`, `url`, `headers`) that an agent only accepts when it advertises
`mcpCapabilities.http`. Source:
[agentclientprotocol.com/protocol/session-setup](https://agentclientprotocol.com/protocol/session-setup).

### Zed

The settings file (command palette: `zed: open settings file`), under the top-level
`context_servers` key — `command`/`args`/`env` for a local server, `url`/`headers` for a
remote one. Source: [zed.dev/docs/ai/mcp](https://zed.dev/docs/ai/mcp).

## What you will see

A refusal never comes back as an empty result or a stack trace: it is a structured
`REFUSED` result naming the resource and the access mode the pod required, computed by the
same `RequiredAccess` table the server enforces with — see Beat 2 of
[`claude-desktop.md`](claude-desktop.md#beat-2--the-refusal) for the transcript. Afterwards,
`cistern receipts` (the CLI) or the `receipts` tool (over MCP, with the *owner's* credential
— the agent cannot read its own receipt) shows every decision: which agent, which mode,
which rule, when. One verified transport, many hosts; the receipt is what makes any of them
trustworthy.
