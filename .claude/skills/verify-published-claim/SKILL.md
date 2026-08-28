---
name: verify-published-claim
description: Check a factual claim before it reaches enrichmeai.com, a README, a PR body or the user — image pullable, release assets present, links resolving, numbers matching their source. Use before publishing any claim about an artifact, a score, a vulnerability count or an external fact.
---

# Verify a published claim

Everything on enrichmeai.com is meant to be checkable by a stranger. That only holds if we
check it first. On 2026-08-28 this caught a wrong vulnerability count, a wrong package
visibility claim, two stale conformance sentences and a deleted safety warning — none of
which any test would have found.

**Rule: verify claims you are about to publish, including your own, and including ones a
teammate handed you. Confident prose is a hypothesis.**

## Container image — "docker pull X works"

GHCR always requires a token, even for public images, so a bare 401 does **not** mean private.
Fetch an anonymous token and ask for the manifest:

```bash
T=$(curl -s "https://ghcr.io/token?scope=repository:OWNER/IMAGE:pull&service=ghcr.io" \
     | python3 -c "import sys,json;print(json.load(sys.stdin)['token'])")
curl -s -o /dev/null -w "%{http_code}\n" -H "Authorization: Bearer $T" \
  -H "Accept: application/vnd.oci.image.index.v1+json" \
  https://ghcr.io/v2/OWNER/IMAGE/manifests/TAG
```

`200` = anonymously pullable. `403` = private. Compare against a known-public and a
known-private package in the same org when unsure.

Multi-arch: parse `.manifests[].platform` from the same response rather than trusting the
release notes.

## Release — "the jar is on the release page"

```bash
gh release view TAG --repo OWNER/REPO --json assets,isDraft --jq '[.assets[].name]'
```

A draft release is invisible to strangers. Check `isDraft` as well as the asset names.

## Numbers from a report — scan results, test counts, scores

**Read the tool's own summary line. Do not count rows with grep.** A Trivy text table does not
repeat the severity on every row of a grouped finding, so `grep -c CRITICAL` undercounts
badly — that produced a "9" when the report's own header said `Total: 138 (HIGH: 124,
CRITICAL: 14)`.

Download the artifact and read it:

```bash
gh release download TAG --repo OWNER/REPO --pattern "trivy*" --dir /tmp --clobber
grep -iE "^Total:" /tmp/trivy-report-*.txt
grep -oE "CVE-[0-9]{4}-[0-9]+" /tmp/trivy-report-*.txt | sort -u | wc -l   # unique CVEs
```

Then check the *specific* CVE you are claiming is fixed is actually absent, rather than
reporting a smaller total.

## A fix that "will be applied" — check it exists first

A scanner naming a fixed version is not proof that version is published. `spring-security-web`
CVE-2026-22732 lists 6.5.9 and 7.0.4 as fixed; neither was on Maven Central, so there was
nothing to pin and "we will fix it in the next release" would have been a promise nobody could
keep.

Before writing that a dependency will be bumped, confirm the artifact is actually resolvable:

```bash
curl -s "https://repo1.maven.org/maven2/GROUP/PATH/ARTIFACT/maven-metadata.xml" \
  | grep -oE "<version>[^<]*</version>" | tail -5
```

If the fix version is not there, say **"no fixed release upstream yet"** rather than implying a
schedule. That is a true sentence and it does not expire.

Credit: `ai-coding-agent-11` found this one on Penstock's v0.1.1 bump.

## Links and anchors

```bash
curl -sIL -o /dev/null -w "%{http_code} %{url_effective}\n" URL
```

For internal anchors, confirm the `id` exists in the target file. A link that 200s to the
wrong page is worse than a 404.

## Upstream claims — issues, source behaviour

Read the source, not the description of it. `gh issue view N --repo OWNER/REPO --json state`
for status; `curl raw.githubusercontent.com/.../file` for what the code actually does. An
issue title is somebody's reading; the file is the fact.

## Then say the narrower thing

If verification supports a weaker claim than the one you meant to make, publish the weaker
one. "The criticals with a released fix are fixed" survives scrutiny; "clean scan" does not,
when the report still says four.
