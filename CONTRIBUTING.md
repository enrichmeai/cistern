# Contributing to Cistern

Thanks for considering it. Ground rules are short:

## Sign your commits (DCO)

Every commit must carry a `Signed-off-by` line certifying the
[Developer Certificate of Origin 1.1](https://developercertificate.org/):

```bash
git commit -s
git rebase --signoff main   # fix an existing branch
```

No CLA — your copyright stays yours, licensed to the project under Apache 2.0.

## Engineering bar

- Read `CLAUDE.md` (it is the contributor guide too, not just for AI agents).
- The Solid spec text and the conformance harness are the source of truth — link the
  spec section your change implements in the PR description.
- Fully reactive: no `.block()` in production code.
- Storage backends must pass the shared `ResourceStoreContractTest` kit.
- A PR that lowers the CTH pass-count does not merge, whatever else it adds.

## Process

One concern per PR. Branch from `main`, conventional commit messages
(`feat(wac): ...`), tests included. CI must be green.
