---
name: groom
description: Review and groom every open issue and PR in BOTH cistern and penstock against the next release, and rewrite the Build board issue. Use for "groom", "what's pending", "what's left for the release", "what should I do next", at the start of a working day, or before picking the next task. Fans out to the Sonnet `groomer` agent; incremental after the first run.
---

# Grooming the backlog (both repos)

Run from the `cistern` session only (one session per repo). It reads and labels issues in
`enrichmeai/cistern` and `enrichmeai/penstock`, and never changes code.

One pass answers three questions for the owner: **what blocks the next release, what Claude can
take next unattended, and what only the owner can do.** The output is one GitHub issue — the
**Build board** in `enrichmeai/cistern` (title starts `Build board`, label `board`) — rewritten on
every run, so the answer always lives in one place and survives every session.

## Rules
- **Plans stay where they are.** `docs/BACKLOG.md` (T-tickets) and the `phase-*`/`epic`/`ticket`
  labels are cistern's plan; BMad owns planning (CLAUDE.md § Governance). The board ranks and
  labels; it never rewrites the backlog.
- **Labels.** `wave:W1|W2|W3` (W1 = the repo's next release), and the owner labels
  `claude-ready` (Claude can build it unattended), `founder` (keys, money, legal, accounts,
  rulings, release tags), `mac-session` (cloud credentials, a running pod, the local CTH IdP kit).
  First run: create any that are missing in either repo (`gh label create <name> -R <repo>`).
- **Safe writes happen; closures wait.** The run may add/remove the labels above and post a
  grooming comment. It never closes, never merges, never edits an issue body. Closures are
  proposed on the board as checkboxes; the owner ticks them; the NEXT run closes ticked ones
  with `--reason "not planned"` or `completed` and a one-line comment naming the evidence.
- **Evidence or it did not happen.** "Already fixed" needs the merged PR / commit SHA.
  "Duplicate" needs the other issue number. "Obsolete" needs the ruling or the deleted code path.
- **Usage cap:** one groomer agent per ~25 items, at most 5 agents per run. First run grooms
  everything; later runs groom only items updated since the board's `last-groomed` stamp, plus
  every `wave:W1` item, plus anything with no wave label.

## 1. Snapshot (one call per repo, compact)
```bash
S=${CLAUDE_SCRATCH:-/tmp}/groom; mkdir -p $S
for r in cistern penstock; do
  gh issue list -R enrichmeai/$r --state open --limit 500 \
    --json number,title,labels,updatedAt,author,body,comments \
    --jq '[.[] | {repo:"'$r'", n:.number, title, labels:[.labels[].name], updated:.updatedAt,
           author:.author.login, body:(.body[0:1500]), ncomments:(.comments|length),
           last_comment:((.comments|last|.body // "")[0:600])}]' > $S/issues-$r.json
  gh pr list -R enrichmeai/$r --state open --limit 100 \
    --json number,title,isDraft,updatedAt,headRefName,body,mergeable,statusCheckRollup,closingIssuesReferences \
    --jq '[.[] | {repo:"'$r'", n:.number, title, draft:.isDraft, updated:.updatedAt,
           branch:.headRefName, mergeable, closes:[.closingIssuesReferences[].number],
           checks:([.statusCheckRollup[]?.conclusion] | group_by(.) | map({(.[0] // "PENDING"):length}) | add),
           body:(.body[0:800])}]' > $S/prs-$r.json
done
```
Read the current board (`gh issue list -R enrichmeai/cistern --label board --state open`) for
its `last-groomed` stamp and any ticked closure boxes. No board yet → create it on step 5.

## 2. Close what the owner ticked
For each ticked `- [x] close …` line on the board: re-check the evidence still holds, then close it
(`gh issue close N -R enrichmeai/<repo> --reason "<not planned|completed>" --comment "<evidence> — closed from the Build board"`).
Report anything whose evidence no longer holds instead of closing it.

## 3. Fan out to `groomer` (Sonnet)
Split the items to groom into batches of ≤25 (keep one repo per batch). Launch the batches as
parallel `groomer` agents, each given: the batch JSON, both repo paths, and this rubric. Each
returns one JSON row per item (schema in `.claude/agents/groomer.md`).

## 4. Apply safe writes
- Label changes the groomer proposed (`wave:*`, owner label) — apply with `gh issue edit`.
- A grooming comment ONLY when the item is `wave:W1` and not ready: the proposed acceptance
  criteria + test approach + open questions, headed `Grooming (proposed — edit or reply to
  correct)`. Never more than one grooming comment per item; update your earlier one instead.

## 5. Rewrite the board
Body, in this order (keep it scannable on a phone):
1. `last-groomed: <UTC timestamp> · main@cistern <sha> · main@penstock <sha>`
2. **Release gate** — one line per repo: N W1 items open (claude-ready X · founder Y ·
   mac-session Z), and the critical path in dependency order (`A → B → C`), across both repos.
   Cistern's line also carries the current CTH row from `cth/BASELINE.md`.
3. **Owner queue** — W1 items only the owner can do, each with the exact next action.
4. **Claude queue (next 5)** — ready W1 `claude-ready` items in build order, each with repo,
   acceptance criteria link, and size (S/M/L). These are what `/build-task` picks up.
5. **Needs grooming** — W1 items not ready, with the missing piece named.
6. **Proposed closures** — `- [ ] close <repo>#N — <duplicate of|fixed by|obsolete because> <evidence>`.
7. **Open PRs** — per PR: draft/ready, checks, mergeable, stale (>24 h), what it closes.
8. **Drift** — BACKLOG.md ticket status vs issue state; cross-repo items whose halves are in
   different waves.
9. `W2/W3/unlabelled counts` only — not lists.

Create with `gh issue create -R enrichmeai/cistern --title "Build board (do not close)" --label board --body-file …`,
update with `gh issue edit <n> --body-file …`. Then reply to the owner with sections 2–4 only.

## Optional: dispatch
`/groom dispatch N` additionally adds the `claude` label to the top N of the Claude queue, which
starts the GitHub Action builder in that item's repo (one at a time per repo — each workflow's
concurrency group). Only on explicit request: each dispatch spends usage.
