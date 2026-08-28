---
name: ship-site-change
description: Edit, validate and publish enrichmeai.com safely — the site repo is a separate clone, main deploys on merge, and the pages are hand-written HTML with no build step. Use for any change to enrichmeai.com copy, links or structure.
---

# Ship a change to enrichmeai.com

The site lives in `enrichmeai/enrichmeai.github.io`, cloned at
`~/projects/enrichmeai-site` — **not** in the cistern repo. There is a `CNAME`, so
**merging to `main` publishes**. Pages are hand-written HTML with no build step and no
framework.

Pages: `index.html` (homepage), `cistern/index.html`, `culvert/index.html`.

## Before editing

Pull first — other sessions ship here too:

```bash
cd ~/projects/enrichmeai-site && git checkout main && git pull && git checkout -b site/<change>
```

## Editing

Use exact string replacement with an assertion that the match count is 1. **Copy the target
text out of the file first** — it usually contains literal `—`, `→`, `·` and `↗` rather than
entities, and a mismatched entity is the most common failed edit here.

## Validate before publishing — all four

1. **Tag balance.** A stray unclosed tag renders as a blank section.
2. **Render it and look.** `--headless --screenshot` in Chrome. A grid whose second column was
   removed leaves a void; a diff will not show you that.
3. **Both themes.** The palette is defined for light, dark and the toggle. Check a light render
   as well as dark.
4. **Every link.** External links get `curl -sIL -o /dev/null -w "%{http_code}"`; internal
   anchors get their `id` confirmed in the target file.

Narrow-viewport renders below ~600px are unreliable in headless Chrome — it crops a wider
layout and fakes clipping. Verify reflow at ~700px and trust the media queries.

## Copy rules specific to this site

- **Never a sentence that promises a future event.** "when X lands", "no release yet" — both
  have gone stale here and neither announced it. State the present and its cause.
- **Never name a competitor.** See `Writing about the specs` in `CLAUDE.md`. De-name rather
  than delete the reasoning.
- **Never "first" or "the only open one"** — they are false, and the reason stays false.
- **Verify every artifact claim** before it goes up: use `verify-published-claim`. Do not
  publish a `docker run` one-liner for something you have not confirmed is pullable and safe
  to run.
- Honesty about limits is a flex, not a confession — state the number *and* its cause, and
  drop hedging words like "honest" that protest too much.

## Publish

```bash
git commit -s   # explain WHY; the message is the record of the positioning decision
git push -u origin site/<change>
gh pr create --repo enrichmeai/enrichmeai.github.io --base main --head site/<change> ...
gh pr merge <n> --repo enrichmeai/enrichmeai.github.io --squash --delete-branch
```

Then **confirm it is actually live** — Pages takes ~20–30s:

```bash
sleep 25 && curl -s https://enrichmeai.com/ | grep -o "<the new text>"
```

Merging is publishing. If the change is a positioning decision rather than a correction, open
the PR and let the owner merge.
