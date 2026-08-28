---
name: file-backlog-issue
description: Turn a finding into a well-formed GitHub issue in the cistern repos — duplicate check first, house labels and milestones, evidence with file references, checkable DoD. Use when filing any issue, or a batch of them after an audit or review.
---

# File a backlog issue

## Check what is already tracked — first, always

A batch of six issues from one audit nearly duplicated two that already existed (Maven
Central publishing and the empty starter were both open tickets). One `gh issue list`
avoided it.

```bash
gh issue list --state open --limit 200 --json number,title --jq '.[] | "\(.number)\t\(.title)"'
gh issue list --state open --search "<keyword>" --json number,title
```

Also grep the bodies of the *near-miss* issues. A gap can be assumed by two issues and
owned by neither — that is worth its own ticket, and say so in the body.

## Use the house conventions

Labels: `ticket`, `epic`, `bug`, `documentation`, `enhancement`, `phase-0`…`phase-7`.
Milestones exist per phase plus the proof milestones. Check before inventing:

```bash
gh label list --limit 40 --json name --jq '.[].name'
gh api repos/enrichmeai/cistern/milestones --jq '.[] | "\(.number)\t\(.title)"'
```

Ticket-shaped issues are titled `T<phase>.<n> <short name> — <what it unblocks>`.

## Body shape

**Why** (the evidence, with file references and what was actually observed) → **Scope** →
**DoD** (checkable by someone else) → **Related** (issue numbers, both directions).

Evidence beats assertion. "`cistern-spring-boot-starter` contains zero `.java` files and a
12-line pom, yet `README.md:107` lists it as a module" is actionable; "the starter is
incomplete" is not.

## Conformance numbers only move forward

Never file an issue that asks for a number to look better. If the honest answer is still
zero, the ticket is to record *why*, precisely, as of the current release.

## Write bodies to a file, not an inline string

```bash
cat > /tmp/issue.md <<'BODY'
…
BODY
gh issue create --title "…" --label "ticket,phase-6" --body-file /tmp/issue.md
```

Inline `--body` with quotes and backticks corrupts silently — a stray token once shipped
into a live issue title-line and had to be edited out after the fact.

## After filing, close the loop

Comment on the issues your finding changes — a new measurement that reduces the scope of an
open ticket is more useful on that ticket than in a new one.
