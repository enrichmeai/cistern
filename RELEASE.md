# Release procedure — Cistern

One `v*` tag push on `main` is the single publishing trigger; nothing in this repo
auto-publishes, and **only the owner pushes tags** (dev agents never tag, merge or
release). Publishing is irreversible in practice: a GHCR version tag or a Release asset
someone may already have pulled is never overwritten, retagged or deleted — a broken
release is fixed **forward** as a patch version (§6).

What one tag push produces (`.github/workflows/release.yml`, #107):

- `ghcr.io/enrichmeai/cistern:<version>` (+ `:latest` for a non-prerelease),
  linux/amd64 + linux/arm64, built natively per architecture and stitched into one index
- a GitHub Release "Cistern `<version>`" carrying `cistern-app-<version>.jar`,
  `cistern-cli-<version>.jar` (the executable CLI), the `cistern` wrapper script and
  `SHA256SUMS` over all three, with the matching `## [<version>]` section of
  [CHANGELOG.md](CHANGELOG.md) as its body

No repository secret is involved anywhere: the workflow publishes with the ephemeral
`GITHUB_TOKEN` (`contents: write`, `packages: write`). There is deliberately no Maven
Central leg — applications consume the image or the fat jars; publishing the modules as
libraries is a separate decision for later.

## 0. State of the gate (update as steps complete)

| Step | Status |
|---|---|
| Release pipeline authored and rehearsable (`release.yml`, #107, PR #109) | ✅ 2026-08-18 |
| CLI ships as a Release asset (jar + wrapper, in `SHA256SUMS`) | ✅ this PR |
| Compose/kit stale-text hygiene (#123) | ✅ this PR |
| Local rehearsal: `versions:set` → `verify` → both jars at the workflow's exact paths | ✅ 2026-08-20 (transcript on the PR) |
| `workflow_dispatch` rehearsal green on `main` | ⬜ |
| CHANGELOG `## [0.1.0]` re-scoped to what the tag actually ships | ✅ 2026-08-20, this PR — Unreleased folded in, limitations and upgrade notes rewritten to `main`'s truth |
| Tag `v0.1.0` pushed | ⬜ owner only |
| GHCR package made public (one-time) | ⬜ owner only |
| Stranger test passed from published artifacts alone (§5) | ⬜ |

## 1. The release gate (all true before the tag)

1. **`main` is green and complete for the version.** `mvn -B clean verify` locally —
   the full suite, no skips; the release build repeats it and must never be the first
   to discover red.
2. **The CHANGELOG section describes the commit being tagged.** The workflow makes
   `## [<version>]` the Release body verbatim and fails in seconds without it. The tag
   is cut from `main`, so everything on `main` belongs in the version's section: fold
   `## [Unreleased]` in before tagging, every time. **Done for 0.1.0 in the
   release-readiness PR (#125)** — the prepared section predated T4.0, T5.6, T5.7, T5.9
   and T7.7 landing, and its entries, *Known limitations* and *Upgrade notes* were
   re-scoped to `main`'s truth. If anything else lands on `main` before the tag, repeat
   the fold.
3. **No stale text a stranger would read.** README quickstart names the version being
   tagged and its commands work as pasted; compose and kit comments state the ADR 0002
   posture (#123); nothing shipped claims a built feature is unbuilt or vice versa.
4. **Both rehearsals are green** (§2): the `workflow_dispatch` dry run, and the local
   check that both jars appear at the exact paths the workflow collects — historically
   where release pipelines break.
5. **After the tag: the stranger test passes** (§5) — pull the image, run it enforcing,
   get the CLI from the Release assets, complete the five beats using only shipped docs,
   read the receipts. The release is not done until someone has performed it.

## 2. Rehearsal (publishes nothing) — anyone with repo write

**In CI:** GitHub → Actions → **release** → *Run workflow* → version e.g. `0.1.0-rc1`.
A dispatch runs the `build` job and both `image` legs against the same code paths as a
real release, and stops short of everything external: `PUBLISH=false` means no GHCR
login, `push=false` on the image build (the image never leaves the runner), and the
`publish` job is skipped outright (`if: github.event_name == 'push'`). No registry tag,
no Release, no side effects. Run it on `main` (or the release PR's branch) and read the
`build` log: the CHANGELOG check, both jar paths and the CLI `--help` smoke run are all
visible there.

**Locally** (proves the asset paths without a runner; **never commit the version
change**):

```bash
mvn -B -ntp org.codehaus.mojo:versions-maven-plugin:2.18.0:set \
    -DnewVersion=0.1.0-check -DgenerateBackupPoms=false
mvn -B -ntp clean verify

test -f cistern-app/target/cistern-app-0.1.0-check.jar        # the workflow's APP_JAR path
test -f cistern-cli/target/cistern-cli-0.1.0-check.jar        # the workflow's CLI_JAR path
java -jar cistern-cli/target/cistern-cli-0.1.0-check.jar --help

git restore -- pom.xml '*/pom.xml'                            # revert; main stays <next>-SNAPSHOT
git status --porcelain                                        # must show no pom changes
```

The shade plugin **replaces** `cistern-cli`'s main artifact — the shaded jar has no
classifier, and the pre-shade jar is left as `original-cistern-cli-<version>.jar`
(never an asset). If either `test -f` fails, the workflow's collect step would have
failed the same way: fix the pom or the workflow before tagging, not the transcript.

## 3. Tag and publish — owner only

```bash
git switch main && git pull --ff-only
git tag -s v0.1.0 -m "Cistern 0.1.0"      # or -a; the tag IS the version
git push origin v0.1.0
```

Then watch the run: `build` (CHANGELOG check first, full suite, both jars) → `image`
(amd64 + arm64, pushed by digest) → `publish` (multi-arch index tagged, Release created
with the four assets). A re-run of the same tag updates assets and notes in place
(`gh release upload --clobber`) rather than failing — for a *failed* publish of the same
commit, never for shipping different code under a published version.

## 4. One-time, after the first push — owner only

GHCR packages are **private by default** and do not inherit the repository's
visibility. Make the package public at
<https://github.com/orgs/enrichmeai/packages/container/cistern/settings>
— until this is done, every `docker pull` in the README fails with `denied` and the
stranger test cannot start. Needed once, ever; later versions inherit it.

## 5. Post-release stranger test — anyone, ideally not the owner

From a machine with no repo checkout, using only the published artifacts and what the
README/Release say. Substitute the real version for `0.1.0`.

```bash
# Deploy: the image from GHCR, running ENFORCING (the three env vars)
docker pull ghcr.io/enrichmeai/cistern:0.1.0
export TOKEN="$(openssl rand -hex 32)" WEBID='https://you.example/profile/card#me'
docker run -d --name cistern-stranger -p 127.0.0.1:3737:3000 \
  -e CISTERN_BASE_URL=http://localhost:3737 \
  -e CISTERN_OWNER_WEBID="$WEBID" \
  -e CISTERN_OWNER_TOKEN="$TOKEN" \
  -v cistern-stranger-data:/data \
  ghcr.io/enrichmeai/cistern:0.1.0

# Use: the CLI from the Release assets, checksums verified
REL=https://github.com/enrichmeai/cistern/releases/download/v0.1.0
curl -fsSLO "$REL/cistern-cli-0.1.0.jar"; curl -fsSLO "$REL/cistern"; curl -fsSLO "$REL/SHA256SUMS"
shasum -a 256 --check --ignore-missing SHA256SUMS   # both downloads OK (Linux: sha256sum --ignore-missing -c)
chmod +x cistern
export CISTERN_CLI_JAR="$PWD/cistern-cli-0.1.0.jar" CISTERN_TOKEN="$TOKEN"
./cistern --version

AUTH="Authorization: Bearer $TOKEN"; BASE=http://localhost:3737
http() { curl -s -o /dev/null -w '%{http_code}\n' "$@"; }

# Beat 1 — it works: the owner provisions a pod and stores a note
./cistern pod create --root /notes/ --owner "$WEBID"                       # exit 0
http -X PUT -H "$AUTH" -H 'Content-Type: text/turtle' \
  --data-raw '<#n> <http://purl.org/dc/terms/title> "Weekly notes" .' "$BASE/notes/week"   # 201
http -H "$AUTH" "$BASE/notes/week"                                         # 200

# Beat 2 — the refusal: a caller with no grant gets nothing
http "$BASE/notes/week"                                                    # 401

# Beat 3 — the grant: one command, effective on the very next request — and only reads
./cistern grant public --read /notes/                                      # exit 0
http "$BASE/notes/week"                                                    # 200
http -X DELETE "$BASE/notes/week"                                          # 401  (read is not write)

# Beat 4 — revocation, live: no restart, no token reissued
./cistern revoke public /notes/                                            # exit 0
http "$BASE/notes/week"                                                    # 401  (the very next request)

# Beat 5 — the receipt: every decision above, and under which rule
curl -s -H "$AUTH" "$BASE/notes/week?receipts"        # NDJSON: the allows AND denies, naming the deciding ACL
http "$BASE/notes/week?receipts"                      # 401  (receipts require Control, not Read)

# Clean up
docker rm -f cistern-stranger && docker volume rm cistern-stranger-data
```

Pass = every status matches the comment, both CLI exits are 0, and the receipts line
names `/notes/.acl` as the deciding ACL for the granted read. Record the transcript on
the release issue. Any mismatch is a release defect: file it, fix forward (§6).

## 6. If a broken release ships anyway

- **Never** delete or retag `ghcr.io/enrichmeai/cistern:<version>`, the git tag, or a
  Release asset — someone may already have pulled them and pinned the digest.
- Fix on `main`, add the CHANGELOG section, tag the next patch (`v0.1.1`), and mark the
  broken GitHub Release as such in its notes (owner only). `:latest` moves to the fixed
  version automatically on the new tag.

## 7. Version discipline

`main` stays at `<next>-SNAPSHOT` permanently; the tag is the single source of truth.
The workflow sets the version on the poms with `versions:set` for the duration of each
job and commits nothing — so there is no post-release version-bump PR, and nothing to
keep in sync by grep. The only versioned text to maintain by hand is the CHANGELOG
section (checked by the workflow before anything builds) and the README quickstart's
pinned version.

## Who runs what

| Step | Who |
|---|---|
| Rehearsals (§2), stranger test (§5) | anyone with repo write / anyone at all |
| CHANGELOG re-scope for the version (§1.2) | owner (editorial call) |
| Tag push (§3), Release edits, marking a release broken (§6) | **owner only** |
| GHCR package visibility (§4) | **owner only** (org admin) |
