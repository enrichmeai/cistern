---
name: run-conformance-harness
description: Run the Solid conformance test harness against Cistern — official or provisional lane — interpret where it halts, and record numbers without corrupting the ratchet. Use for any request to run, reproduce, or explain CTH numbers, or to update cth/BASELINE.md.
---

# Run the conformance harness

The mechanics live in `cth/idp/README.md` (three commands: stack up, `provision.sh`,
`run-cth-authed.sh`). This skill is the judgment around them: which lane you are in, what a
halt means, and what may be written down afterwards. Written from the runs that produced
BASELINE.md's 2026-08-28 rows.

## The two lanes — never mix them

*Policy, not mechanics: set by the owner's ruling of 2026-08-28 (contribute the harness fix
upstream rather than route around it — conformance-test-harness#789). If the lanes' meaning
changes — upstream merges, or the ruling moves — this section changes with an architect
decision, dated, not silently.*

- **Official** (`run-cth-authed.sh`, default image): the ONLY lane that may move
  `cth/BASELINE.md`'s official row. Until upstream merges
  [conformance-test-harness#789](https://github.com/solid-contrib/conformance-test-harness/pull/789),
  this lane is EXPECTED to halt in PREPARE SERVER: the harness client's DPoP proofs carry no
  `ath`, RFC 9449 §4.3 obliges Cistern to reject exactly that, and the server log shows
  `ATH_MISSING` per attempt. That halt is upstream's defect, measured — not ours, and not a
  regression.
- **Provisional** (`CTH_IMAGE=cth-patched:ath`): a build of the official image with #789's
  one guarded claim. It produces real feature numbers (24/11 MustFeatures at last run).
  Everything it yields is labelled provisional, lives beside — never in — the official row,
  and ticks no ticket. If the two lanes' relationship changes (upstream merges, or the halt
  moves), that is an architect decision, not a recording detail.

Build the patched image when needed. The diff lives in PR #789; until upstream merges it,
this recipe is the only written-down way to produce the provisional image. Re-runnable — a
fresh temp dir each time, since iterating is the normal case:

```bash
src=$(mktemp -d)
git clone https://github.com/solid-contrib/conformance-test-harness "$src"
cd "$src" && git fetch origin pull/789/head:ath && git checkout ath
docker run --rm -v "$src":/build -w /build -v cth-m2:/root/.m2 \
  maven:3.9-eclipse-temurin-21 mvn -q package -DskipTests -Dcheckstyle.skip -Dpmd.skip
printf 'FROM solidproject/conformance-test-harness:latest\nCOPY --chown=185:185 target/solid-conformance-test-harness-runner.jar /app/harness/\n' \
  | docker build -q -t cth-patched:ath -f - .
```

The overlay keeps `/data` (the test suite) and config byte-identical to the official image, so
the two lanes differ by exactly the one claim.

## Reading a halt

Work down this list before forming any theory; every entry here has burned an hour for
someone who skipped it.

1. **Script refused before the harness ran** (port pin, credentials incomplete, profile
   probe): the message names the fix. The profile probe failing means the config never
   loaded — mount, seeding, or port — not authentication.
2. **REGISTER CLIENTS**: identity topology. Grep the server log for `WEBID_ADDRESS_REFUSED`
   / `WEBID_SCHEME_REFUSED` first; then suspect stale credentials (`css-idp` restarted —
   its store is in-memory; re-run `provision.sh`), then the origin strings (issuer strings
   byte-identical, trusted-origins canonicalised — `cth/idp/README.md`'s traps).
3. **PREPARE SERVER, official lane, `ATH_MISSING` in the server log**: the documented
   upstream halt. Record 0/0/41 with that cause; nothing is wrong.
4. **PREPARE SERVER otherwise** ("Cannot get ACL url"): the server is not advertising
   `Link rel="acl"` — a real Cistern regression (#159 is the fix that made this pass).
5. **Features ran**: `report.html`/`report.ttl` exist in `cth/reports/`. The log's
   `Results:` block has the counts; `>>> failed features:` lists the work queue.

Honesty mechanics: a results report exists ONLY when features ran — never infer counts
without one (`cth/summarize.sh` encodes this). The harness exits 0 even when it could not
write reports (uid 185 — `run-cth.sh`'s chmod comment).

## Recording

- The official row in `cth/BASELINE.md` moves only on an unmodified-harness run, forward
  only; raise it in the same PR as the change that earned it. A regression is blocking
  regardless of what the PR adds.
- Provisional numbers go in the fenced provisional section, dated, with the harness diff
  named. The wording must make it impossible to mistake them for the baseline.
- If the harness and your reading of a spec disagree, stop and raise it to the architect
  (ground rule 1). Patching the harness is only ever the labelled provisional lane with the
  patch offered upstream — that is the shape #789 set, on the owner's ruling.

## Afterwards

`docker compose -f docker-compose.yml -f cth/idp/compose-cth-idp.yml down -v` when done or
before any config change — seeding is never-overwrite, and the kit's `cistern-cth-data`
volume otherwise keeps the old topology no matter what the config says.
