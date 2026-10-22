# Guarded synthetic live-intake pilot

This opt-in development harness inspects one previously acquired synthetic u02
response. It is not part of the normal application, an upload endpoint, a new
eight-case evaluation, or production admission. Browser inspection never runs a
model. Do not enter personal data.

## Evidence modes

- `synthetic_fake`: a durable fake transport supplies the existing witness. The UI
  says **SYNTHETIC FAKE ACQUISITION; NO MODEL EXECUTION**. This proves integration,
  not extraction quality.
- `live`: a separately authorized durable worker acquires the real response before
  the app starts. The UI says **REAL MODEL CAPTURE** and displays acquisition
  identity and the independently recorded receipt digest. The unchanged Kotlin
  wire/binding/semantic validator runs again at startup.

Neither mode makes suggestions authoritative. Inspect the saved source and read-only
suggestions, edit a selected suggestion, supply missing calendar details, acknowledge
every unresolved field, stage the item, review pending JSON, then explicitly confirm.
Inspection, editing, acknowledgments and staging do not write authoritative rows.
Reload discards unconfirmed local work and does not reacquire suggestions or replay
confirmation. A corrected source makes the older capture unavailable.

## Offline checks

```sh
INTAKE_PILOT_TEST_PROMPT=/private/path/to/frozen-candidate.txt \
  python3 -m unittest discover -s benchmarks/live-intake-pilot -p 'test_*.py'
./gradlew --offline :backend:test --tests 'com.gtrainer.LiveIntakePilotTest'
npm --prefix frontend test -- --maxWorkers=2
npm --prefix frontend run build:live-intake-pilot
```

The candidate is the original private, digest-pinned prompt, not a new prompt or
gold-answer substitution. Fake acquisitions can be recreated for offline tests;
no fake grant can admit a live runtime. Historical evaluation manifests remain
unchanged.

## Explicit acquisition and startup

Only the primary operator may consume an approved source/root-bound grant. The
grant records the exact tooling closure, execution mode, one private root, one
non-scored stop probe, one u02 request, runtime pins, fixed budgets and cleanup
policy. `run_mac_pilot.py --create` consumes it exclusively. The subsequent
`--revision` command registers the existing launchd supervisor and never creates
a replacement campaign. The vocabulary-only counter and all live observations
remain inside that supervised grant. Do not invoke these commands without approval.

After verified completion, `seal_capture.py --seal` publishes a private capture
receipt. Independently record its SHA-256. Startup requires that expected digest,
the registered grant/claim/supervisor chain, current source closure, strict accepted
output and verified cleanup. Arbitrary response files, fixture provenance, failed
cleanup and fake evidence presented as live are refused. Private capture bytes,
prompts, logs, passwords and generated bundles must not be committed.

With the admitted capture and a dedicated synthetic password verifier:

```sh
npm --prefix frontend run build:live-intake-pilot
GTRAINER_LIVE_INTAKE_CAPTURE_DIR=/private/path/to/campaign \
GTRAINER_LIVE_INTAKE_RECEIPT_SHA256=<independently-recorded-digest> \
GTRAINER_LIVE_INTAKE_EVIDENCE_MODE=synthetic_fake \
GTRAINER_SYNTHETIC_PILOT_PASSWORD_HASH=<dedicated-pbkdf2-verifier> \
  ./gradlew --offline :backend:runLiveIntakePilot
```

Use `live` only for the actual admitted model capture. Open
`http://127.0.0.1:8082/`. The launcher has a fixed loopback port, its own cookie,
same-origin/CSRF enforcement, a generated private SQLite store and a durable
exclusive app-session claim. Its monotonic 1200-second budget cannot be renewed
by reload or restart. A second process cannot reuse that claim. A busy port never
authorizes killing another process. Stop the exact owned server; its shutdown hook
closes the store and removes only its generated directory. Never remove the claim
to restart a spent live session.

## Current verification checkpoint

The offline source-frozen journey has passed through a real launchd fake worker,
sealed-chain startup, actual Ktor/SQLite app, separate React bundle, authenticated
browser inspection/edit/resolution/staging/explicit confirmation and reload.
All table counts remained identical until explicit confirmation. Afterward there
was exactly one selected commitment revision and one confirmation receipt; reload
changed no counts. Production execution tables remained empty. The owned app store
was removed on shutdown.

Regression checks passed: 123 v4 tests, 33 shared runtime tests, 29 v5 tests including
actual Kotlin integration, 45 offline oracle tests, 13 pilot tests, 372 frontend
tests and the normal/live frontend builds. The first v5 command omitted its required
retained-integration directory; it failed without model calls. Correcting that
environment produced 29/29 passes. These results do not establish live acquisition
success. Actual runtime and interaction outcomes belong in the active OpenSpec task
record after the separately gated test.

The user's 2026-10-22 approval permits the bounded test after offline checks and
allows the original Ollama deployment to remain paused. Owned pod/process/cgroup/CRI
stop proofs remain mandatory. No retry, fallback, response repair, download, resize,
personal-data access, deployment or production admission follows from these checks.

## Actual bounded acquisition, 2026-10-22

The authorized one-probe/one-u02 run completed with accepted strict wire, binding
and semantic stages and a passed predeclared case comparison. Fresh input count was
2281 tokens; the unchanged request produced341 output tokens in approximately482
seconds. No retry, response repair, fallback or replacement root was used. Both
owned pods have verified UID/cgroup/CRI stop proofs. The original deployment remains
paused as authorized; restoration was not claimed.

The genuine sealed capture then passed actual startup admission into the separate
loopback app. Real-browser inspection/editing/unknown acknowledgments/staging changed
no table counts. Explicit confirmation created one selected commitment revision and
one receipt; reload made no further write or suggestion request. The reviewer supplied
the training time, timezone and recurrence; those are not extracted model facts.
The original model response and comparison remain unchanged. This known synthetic
case does not establish reliability or production qualification.

The single bounded app session was available for the requested UI demo until
2026-10-22T14:17:31.398Z, then stopped automatically. Independent checks verified
exit0, absent exact server/client/descendants and listener, and removal of only the
owned generated store. Acquisition and approval evidence remain intact. The demo
link is no longer active; no second session or additional inference was started.
All eight pilot tasks are complete. Production admission remains closed.
