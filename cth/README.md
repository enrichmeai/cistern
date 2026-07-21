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
- `summarize.sh` — turns a harness log plus `reports/` into the Markdown that CI posts
  as the job summary. Run it against a saved log to see exactly what CI will say.
- `reports/` — generated harness output (gitignored).

Run modes:

```bash
./run-cth.sh               # full test run against localhost:3000 (exit 1 on failures)
./run-cth.sh --coverage    # coverage report only; does not touch the server
```

Start the server with the base URL the harness will actually call:

```bash
CISTERN_BASE_URL=http://host.docker.internal:3000 mvn -q -pl cistern-app spring-boot:run
```

`cistern.base-url` is what mints `Location` headers and the storage description. Left at
its `localhost` default, every URI the server hands the harness names an origin the
harness never called — so once tests can run, URI-assignment assertions fail for a reason
that has nothing to do with the code under test. `run-cth.sh` preflights that *something*
answers on `:3000` (any status — the storage root honestly 404s until T5.4) and exits 2
with this instruction if nothing does.

In CI, the `conformance` job (T3.1) boots the packaged jar with that variable set, runs
both modes, posts the summary, and uploads `reports/` plus the harness logs as the
`cth-reports` artifact. It is **report-only and never fails the build**; T3.2 turns it
into the ratchet gate.

The harness needs two test accounts (alice, bob) with pods — provisioned by
`cistern.pods.seed` (T5.4). There is **no unauthenticated run mode**: verified against
the CTH source (T0.4), every test run unconditionally authenticates alice and bob
(`registerClients`) and prepares a test container with alice's client
(`prepareServer`) before executing any feature, and it emits a results report only
after that setup succeeds. Until T5.4 lands, a test run therefore stops at
"REGISTER CLIENTS" with a 404 on the WebID documents — the recorded baseline —
and `--coverage` is the only mode that runs to completion.
