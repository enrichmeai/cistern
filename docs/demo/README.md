# The flagship demo

One machine, one command, five minutes. An agent asks a pod for a document, is given exactly
what the owner granted, is refused everything else, and leaves a receipt. Then the grant is
taken away and the same agent is refused on its very next request.

That refusal is the demo. Everything before it is setup.

## What you need

Docker, and about 400 MB of images. Optionally [Ollama](https://ollama.com) if you want the
whole thing to run with the network cable out — the demo works with any provider, but a local
model is the version that makes the point hardest to argue with.

## Run it

```bash
export CISTERN_DEMO_SECRET=$(openssl rand -hex 32)
export CISTERN_DEMO_HASH=$(printf '%s' "$CISTERN_DEMO_SECRET" | shasum -a 256 | cut -d' ' -f1)
export CISTERN_OWNER_TOKEN=$(openssl rand -hex 32)
export PENSTOCK_PASSWORD=$(openssl rand -hex 16)
export OWNER_WEBID='https://you.example/profile/card#me'

docker compose -f docs/demo/one-command.yml up
```

Two containers come up: the pod on `127.0.0.1:3737`, the agent on `127.0.0.1:8080`.

The secret is generated on your machine and only its **digest** goes into the pod's
configuration. Cistern never holds the credential it checks.

## The five beats

### 1. Put something in the pod

```bash
curl -X PUT http://127.0.0.1:3737/notes/meeting.ttl \
  -H "Authorization: Bearer $CISTERN_OWNER_TOKEN" \
  -H 'Content-Type: text/turtle' \
  --data '<#note> <http://purl.org/dc/terms/title> "Board meeting, Tuesday" .'
```

### 2. Ask the agent to read it — and watch it be refused

Open <http://127.0.0.1:8080>, log in as `demo` with your `PENSTOCK_PASSWORD`, and ask:

> Read /notes/meeting.ttl from my pod and tell me the title.

It comes back refused. **Nothing has been granted yet**, and the agent has no way around
that — it holds its own credential, not yours, and the pod does not care that a human asked
it nicely.

### 3. Grant it, narrowly

```bash
curl -X PUT http://127.0.0.1:3737/notes/.acl \
  -H "Authorization: Bearer $CISTERN_OWNER_TOKEN" \
  -H 'Content-Type: text/turtle' \
  --data '@prefix acl: <http://www.w3.org/ns/auth/acl#> .
<#agent> a acl:Authorization ;
  acl:agent <http://127.0.0.1:3737/agents/penstock#id> ;
  acl:accessTo <http://127.0.0.1:3737/notes/meeting.ttl> ;
  acl:mode acl:Read .'
```

One document. Read only. Not the container, not the other notes.

### 4. Ask again

Same question. This time it answers, because now it may.

Ask it to read something else under `/notes/` and it is refused again — the grant named one
document, and that is what it got.

### 5. Take it back, and read the receipt

```bash
curl -X DELETE http://127.0.0.1:3737/notes/.acl \
  -H "Authorization: Bearer $CISTERN_OWNER_TOKEN"
```

Ask the agent the same question a third time. **Refused on the next request** — no restart,
no cache to expire, no waiting.

Then ask the pod what happened:

```bash
curl "http://127.0.0.1:3737/notes/meeting.ttl?receipts" \
  -H "Authorization: Bearer $CISTERN_OWNER_TOKEN"
```

Every allow and every deny, with the agent's own WebID, the rule that decided it, and when.

### 6. And ask the agent what it did

Open the **Audit** panel in Penstock's sidebar. Every model call and tool invocation it made,
attributed to the user who asked.

**These are two different records and both matter.** The pod's receipt is the *owner's* — what
was asked of their data and what the rules decided, kept by the side that owns the data and
true whether or not the agent is honest about it. The agent's audit is its own account of the
work it did. An agent that lied about being refused would still appear in the pod's receipt as
refused.

That is what "accountability at both ends" means, and this is the beat where you can see both.

## What this actually demonstrates

**The agent is a principal, not a proxy for you.** It authenticates with its own credential
and appears in the log under its own name. A grant to it is a grant to it alone.

**Refusal is the normal case.** The agent was refused before the grant and after the
revocation, and it reported that rather than routing around it. A tool that could work around
a refusal would make the whole arrangement decorative.

**Revocation is immediate**, because the decision is made per request against the current
rules rather than against a token issued earlier.

**And the record is the owner's**, queryable from the pod, not from the agent's own logs —
which is the difference between an audit trail and a promise.

## Running it with the network cable out

Set `AGENT_LLM_PROVIDER=ollama` (the default here) and point `OLLAMA_BASE_URL` at a local
Ollama. Model, agent and pod are then all on your machine, and nothing about your documents
leaves it. The demo works the same way — which is the point.

## Honest limits

**Do not put this on a network.** Run it on your own machine, behind the loopback binding
the compose file already sets, and stop it when you are done.

Penstock has fixed everything it has a fix for. Across four releases its scan went **138 →
89 → 85** findings, and the criticals from fourteen to **one**: v0.1.2's report says
`CRITICAL: 2`, but both entries are the same CVE — `spring-security-web` CVE-2026-22732,
counted once in the application and once in the bundled dependency cache. It has **no fixed
release upstream**, so there is nothing to apply rather than something left undone. Every
scan ships with its release, so those numbers are yours to read rather than ours to
summarise.

**The WebIDs here are local and made up.** A real deployment uses WebIDs that resolve, with
their own identity provider — Cistern validates Solid-OIDC tokens for that case, and this
demo uses the simpler service-credential path so it runs without standing up an IdP.

**Conformance against the Solid test suite is not yet a number this demo can quote.** The
current standing is in [`cth/BASELINE.md`](../../cth/BASELINE.md), stated plainly.
