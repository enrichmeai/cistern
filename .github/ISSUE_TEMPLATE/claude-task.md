---
name: Claude task
about: A task for the Claude workflow. Creating it does not start anything. Review it, then add the `claude` label (owner's account only).
labels: []
---

<!-- enrichmeai/penstock uses the same sections, shaped to that repo. A BACKLOG.md ticket (T<phase>.<n>) is
     linked here, not copied: docs/BACKLOG.md stays the plan, this issue is the work order. -->

## Goal

<!-- One sentence: the behaviour that will be true when this is done. -->

## Spec

<!-- The Solid Protocol / WAC / Solid-OIDC / MCP sections this implements, and the CTH features
     it touches. CLAUDE.md ground rule 1: the CTH is the real API. Write "none" for pure tooling. -->

## Measure

<!-- Every task names its number, or says why it has none. For protocol work the number is
     usually the CTH row in cth/BASELINE.md. -->
- Metric:
- Baseline, measured on main (command or run ID):
- Target:
- How it is read after merge (test, CTH run, CI job):

## Evidence of done

<!-- Each line is checkable by someone who only reads the PR and its CI runs. -->
- [ ] Red proof: the test or guard that fails on main, quoted
- [ ] Green proof: the same test passing on the branch, quoted
- [ ] The metric re-measured on the branch, beside its baseline
- [ ] No CTH assertion that passed on main fails on the branch

## Risk

<!-- Exactly one. -->
- [ ] docs: only *.md, not CLAUDE.md
- [ ] code: module code and tests
- [ ] infra: terraform, k8s or deploy config (Claude prepares; the owner applies)
- [ ] release: versions, publishing, tags (Claude prepares; the owner tags)

## Surface

<!-- Modules and files expected to change. -->

## Out of scope

<!-- What must NOT change in this task. -->

## Stop and ask if

<!-- Conditions where Claude replies with a question instead of continuing. -->
- the CTH and the spec text disagree (ground rule 1: raise it, do not code around the harness)
- a test would have to be relaxed or a permission widened
- the change needs cloud access, a release or a tag
- the surface grows beyond what is listed above
