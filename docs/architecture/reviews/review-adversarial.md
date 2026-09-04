# Reviewer lens — adversarial: two units that obey every AD and still build incompatibly

Verdict: **three genuine holes**, one of them a direct contradiction between two ADs.

## 1. CRITICAL — AD-15 and AD-16 contradict each other

AD-15 fixes `effective = accessFor(webId) ∩ accessFor(client)`.
AD-16(1) fixes "with no delegation policy present, behaviour is identical to plain WAC".

Construct the pair:

- **Unit A** (the `cistern-acp` evaluator) reads AD-15 literally. A client that no policy
  names matches nothing, so `accessFor(client) = ∅`, so the intersection is `∅`.
- **Unit B** (the ValueDocs deployment) authenticates `valuedocs-legal` as a service
  principal. Per T4.1's verified finding, its access token carries `client_id`, so
  `Agent.client()` is present on every request.

Both obey every AD. The moment ACP is enabled, every request from every application is
denied — and AD-16(1) says behaviour should have been unchanged. The spine as written
makes its own conformance fence unsatisfiable.

The cap needs its identity element stated: **absent a delegation policy naming the client,
`accessFor(client)` is the unconstrained set, not the empty one.** The cap then binds only
where the owner authored a delegation, which is what makes AD-16(1) true and what the idea
note meant by "for the common case the cap is vacuous".

## 2. HIGH — AD-9 does not say what "a running Cistern server" means when the front door is embedded

`cistern-mcp` ships in two shapes: the standalone stdio bridge, and the app-embedded shape
wired by `CisternMcpConfiguration` inside `cistern-app` — same JVM as the pod.

- **Unit A** reads AD-9 as a rule about crossing a process boundary, and short-circuits the
  embedded shape to an in-process `LdpService` call "since it is the same server anyway".
- **Unit B** builds the streamable-HTTP transport (T6.7 / #132) expecting every tool call to
  have crossed `AuthorizationFilter`.

Unit A's optimisation removes the single enforcement point that AD-9 exists to guarantee,
and nothing in the AD's text forbids it. The code already decides this correctly —
`PodToolHandlers(PodHttp, PodAddress)` is the only constructor, in both shapes — so the AD
should ratify what is built rather than leave the loophole.

Also unratified but load-bearing, from `McpFrontDoor`'s javadoc: *"An MCP connection is one
principal; serving a second principal is running a second front door."* A builder adding
multi-user MCP sessions would violate no AD.

## 3. HIGH — the decision-record schema has no named owner

AD-13 says `.cistern/` holds the decision log. AD-14 says client "may be recorded in
decision records and receipts".

- **Unit A** is `cistern-wac`, which owns `DecisionRecord` / `DecisionRecordJson` and
  writes JSON-lines through `JsonLinesDecisionSink`.
- **Unit B** is the future `cistern-acp`, a separate evaluator that also makes decisions and
  is told by AD-14 it may record the client.

Two writers, one append-only log, two schemas — and `JsonLinesDecisionQuery` /
`ReceiptsHandler` can then only parse half their own log. No AD assigns ownership.

## 4. MEDIUM — AD-10 binds two clients to "the same contract" without naming where it is written

The Java and TypeScript clients cannot share code, so the only thing that can keep them
consistent is a written contract. `docs/INTEGRATION.md` §8 is that document; the AD does not
cite it, so two builders can each treat their own client as the reference.
