# Conformance test harness (CTH)

The [Solid conformance test harness](https://github.com/solid-contrib/conformance-test-harness)
is this project's fitness function. `./run-cth.sh` runs the dockerized harness
(`solidproject/conformance-test-harness`) against a Cistern instance on
`http://localhost:3000` (reached from inside the container as
`http://host.docker.internal:3000`).

- `subject-cistern.ttl` — the test-subject description (format per the examples in
  [specification-tests](https://github.com/solid-contrib/specification-tests)
  `test-subjects.ttl`; subject IRI matches the `--target` flag). The only
  configuration the harness reads from it is `solid-test:skip` — we declare none.
- `BASELINE.md` — the best-known pass-count. CI enforces "numbers only move forward"
  (T3.2). Update it in the same PR that raises it.
- `reports/` — generated harness output (gitignored).

Run modes:

```bash
./run-cth.sh               # full test run against localhost:3000 (exit 1 on failures)
./run-cth.sh --coverage    # coverage report only; does not touch the server
```

The harness needs two test accounts (alice, bob) with pods — provisioned by
`cistern.pods.seed` (T5.4). There is **no unauthenticated run mode**: verified against
the CTH source (T0.4), every test run unconditionally authenticates alice and bob
(`registerClients`) and prepares a test container with alice's client
(`prepareServer`) before executing any feature, and it emits a results report only
after that setup succeeds. Until T5.4 lands, a test run therefore stops at
"REGISTER CLIENTS" with a 404 on the WebID documents — the recorded baseline —
and `--coverage` is the only mode that runs to completion.
