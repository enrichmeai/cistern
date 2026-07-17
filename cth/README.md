# Conformance test harness (CTH)

The [Solid conformance test harness](https://github.com/solid-contrib/conformance-test-harness)
is this project's fitness function. `./run-cth.sh` runs the dockerized harness
(`solidproject/conformance-test-harness`) against a Cistern instance on
`http://localhost:3000`.

- `subject-cistern.ttl` — the test-subject description (T0.4 fleshes this out from the
  examples in [specification-tests](https://github.com/solid-contrib/specification-tests)).
- `BASELINE.md` — the best-known pass-count. CI enforces "numbers only move forward"
  (T3.2). Update it in the same PR that raises it.
- `reports/` — generated harness output (gitignored).

The harness needs two test accounts (alice, bob) with pods — provisioned by
`cistern.pods.seed` (T5.4); until auth lands, run the unauthenticated protocol suite only.
