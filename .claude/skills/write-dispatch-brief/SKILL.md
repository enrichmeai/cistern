---
name: write-dispatch-brief
description: Write the brief that sends a dev agent from a ticket to a merged PR — scope, decisions already taken, verified file references, DoD, and an explicit out-of-scope list. Use whenever dispatching work to a dev agent or another session, in cistern or any sibling repo.
---

# Write a dispatch brief

Review confirms; it does not rescue. Whatever the brief leaves ambiguous comes back as a
half-measure, and the cost lands at review. A brief is cheap to make precise and expensive
to leave vague.

## The shape

1. **Where to work.** Repo, branch name, and "branch from a worktree, never the shared
   checkout" (see `land-pr`). Say explicitly if it must not merge to main.
2. **Goal in two sentences**, stated as the observable outcome, not the implementation.
3. **Decisions already taken** — with the reason. A dev agent that does not know *why*
   plain HTTP was chosen over MCP will helpfully "improve" it back.
4. **The task**, numbered.
5. **DoD**, checkable by someone who did not write it.
6. **Out of scope**, named explicitly. This is the half most often omitted and it is what
   stops scope creep.

## Every file reference must be verified before it goes in

Grep it, open it, confirm the line number. A brief citing a method signature that has since
changed sends the agent to re-derive everything and quietly erodes trust in the rest.

Same for ticket and phase numbers: check `docs/BACKLOG.md`. Writing "Solid-OIDC (Phase 5)"
when it is Phase 4 put a wrong claim into the README before it was caught.

## Carry the standing rules the owner will check at review

- The code-quality bar goes in **every** dispatch: enums for closed sets, records for
  domain concepts, per-module message catalogues, no inline literals, no stringly-typed
  code. Half measures are rejected at review, not tidied later.
- Fully reactive; `StepVerifier` for service/core, `WebTestClient` for HTTP.
- **Anything behind `AuthorizationFilter` needs a test through the chain**, not a handler
  call. Two bugs shipped with green handler-level tests because the filter changed the
  answer.
- DCO sign-off, conventional commit, backlog checkbox updated in the same PR.

## Report real output, not a summary

Require the agent to paste actual command output for the build and the checks. "Tests pass"
is not evidence; a green build proves less than it looks, and once hid a four-PR revert.

## Say what "done" excludes

If a prerequisite is deliberately not being fixed (an identity seam, a transport, a
release), name it and say the work must not depend on it. Otherwise the agent either
blocks on it or silently builds around it.
