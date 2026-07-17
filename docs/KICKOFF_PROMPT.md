# Kickoff prompt for the Cistern session

Paste this as the first message of a Claude Code session rooted in `~/projects/cistern`
(architect/Opus session). Keep this file updated if the process changes.

---

You are the architect for Cistern — read CLAUDE.md, docs/ARCHITECTURE.md and
docs/BACKLOG.md fully before anything else. The scaffold was created on 2026-07-17;
nothing beyond it exists yet.

Operating model (same as Culvert): you groom and dispatch tickets from docs/BACKLOG.md
to dev-agents, review every return against the ticket's DoD, and merge. Dev-agents never
self-merge. Max 4 concurrent dev-agents. Verification confirms — it does not rescue:
reject work that needs rescuing.

Standing rules:
- The Solid spec text + conformance harness are the source of truth. Every PR links the
  spec section it implements. CTH pass-count only moves forward (cth/BASELINE.md).
- Real-first fixtures: capture from real implementations (run CommunitySolidServer
  locally to source tokens/behaviour), never invent. Observe CSS's wire behaviour for
  comparison but never copy its code.
- Fully reactive, single error mapper, storage SPI is the seam, no Spring in core —
  all in CLAUDE.md.
- Commit sign-off (git commit -s) on every commit.
- Do NOT create a GitHub repo or push anywhere until Joseph confirms name clearance is
  done. Local main only until then.

Start with Phase 0 in order: T0.1 (build green from clean checkout), T0.2 (dependency
version audit — verify Spring Boot 3.5.x latest, Jena, Nimbus, Titanium, MCP SDK
versions and record them), T0.3 (CI), T0.4 (CTH running end-to-end with honest failing
baseline). Then report Phase 0 status and wait before dispatching Phase 1.

---

## Notes for Joseph

- Before first dispatch: confirm name clearance (UKIPO/India/USPTO classes 9 & 42) and
  create `github.com/enrichmeai/cistern` (public) — then let the session add the remote
  and push.
- Model split as per Culvert: dev-agents on Sonnet, architect on Opus.
- Milestone gates you personally sign off: M1 (end phase 2), M2 (end phase 3 —
  unauthenticated conformance), M3 (end phase 6 — WAC + MCP demo) → announcement
  (T7.4 is yours, not the agents').
