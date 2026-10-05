# Connected-review development implementation status

## Implemented components (synthetic/mock acceptance, not qualification)

OpenSpec change `connected-review-development-ollama`: **21/21 tasks complete**,
including scoped quality review and synthetic/mock integration acceptance.

- Owner-only, isolated SQLite previews/runs/events; hash-pinned nine-case fixtures;
  immutable model/policy bindings; restart persistence and 32 retained-pair cap.
- Atomic idempotent submission, ordered admission, queued-only newer-input
  supersession/replacement links, terminal transitions and expired claim/publication
  refusal. Running older input continues without masquerading as the latest input.
- Separate opt-in loopback launcher, authentication cookie/session/CSRF namespace,
  strict preview/submit/status/cancel/maintenance APIs and a separately built
  Developer UI. It constructs no personal services.
- Bounded owned-runtime Ollama adapter, default ownership refusal, fixed errors,
  no redirects/retries, complete non-thinking envelopes and unchanged draft limits.
- Separate 900/1840-second development ceilings, TTL-clamped claim origin and
  response-free attempt observation. Ordinary/hosted defaults/bounds stay unchanged.
- Concrete method/SQL/Effective Java findings and scoped refactoring are
  recorded in `docs/decisions/connected-review-code-quality.md`.
- Dedicated PID-1/per-run Linux subreaper supervision and disabled packaging,
  verified with 16 actual Linux non-inference process tests. The Pi example uses
  a 5 GiB whole-appliance ceiling, separate from the 1 GiB host reserve/test VM.
- Persisted ownership epochs, node pinning, kernel lifetime lock (including a real
  second-JVM exclusion check), atomic bound claims/publication fences and read-only
  exact-container/changed-boot recovery. Mocked node/SSH command tests and eight
  Python verifier tests passed; no node was contacted.
- Synthetic/durable-claim health gate, single-flight observations with a 60-second
  total ceiling, oldest-measurement freshness and independent active 70-second
  watchdog. Fixed read-only runtime/node readers and immutable ten-second Pi app
  baseline passed mock/fake-clock tests, including separate memory ceilings/reserve,
  restart changes and unrelated model refusal. Five node-health Python tests passed.
- Atomic cancellation/publication/shutdown fences and exact durable Kotlin stop
  receipts, including physical stop despite storage/request-close failure and
  same-owner retry after an uncertain/lost receipt. Unknown stop blocks inference,
  not bounded submission; late output cannot overwrite accepted cancellation.
- One elected durable-event worker with FIFO admission, active health/deadline
  enforcement and serialized maintenance, no idle timer or startup retention sweep.
  Lost notifications survive restart; previously claimed inference is not replayed.
- Bounded event-triggered physical deletion across all outcomes, fixed 24-hour TTL,
  expired-payload hiding, minimal unresolved safety records and maintenance at cap.
  Publication/deletion races and injected deletion failure/retry have regressions.
- Bounded content-free causal summaries and safe observational runtime diagnostics.
  The opt-in Developer tab grants no permission, preserves independent status
  dimensions, restores a run ID with GETs only, and retains source/uncertainty/empty
  output and unqualified labels. It never imports into the personal dashboard.
- Execution admission/session, connected-review route dispatch, worker phases,
  named SQL/shared database operations and UI read/diagnostic responsibilities were
  split by concern. The quality record documents concrete fixes and exceptions.

## Remaining acceptance boundaries

Worker ownership/health, durable stop receipts, queue-event consumption, retention,
APIs and the Developer UI are implemented and covered by synthetic/mock tests.
Publication alone never releases the runtime fence. By default the launcher still
reports `development_worker_not_configured` and cannot send inference. Enabled
assembly requires a separately configured new Linux PID-1 appliance, supervisor
socket and explicit owning-node credentials. All example enable flags remain false
and deployment replicas remain zero. No live deployment/runtime inference occurred.

The Linux environment block is resolved: after explicit approval, a new isolated
Lima home/Ubuntu VM was created with no host mounts, application forwards, container
engine, Ollama or models. Only controlled runtime-helper source/tests were copied.
The latest 29 Python tests (16 process-boundary and 13 synthetic node-helper checks)
passed in private PID/mount namespaces on Linux 6.8.0 / Python 3.12.3, including
strict stop budgets and repeat confirmed receipts. The approved sandbox was reused
only for these non-inference checks and is now stopped. No existing Mac VM or Pi was
contacted or started. These results are not
live Ollama/CRI restart verification and do not substitute process-group/socket/
model-residency observations for termination evidence.

## Verification and safety boundary

Backend suites passed **374 tests across 50 suites**, including the busy asynchronous
API/stop-receipt regression, correction, cancellation/shutdown, retention and causal
summary checks. Earlier full runs hit the unchanged tiny real-time deadline tests;
focused and serial full reruns passed without weakening those assertions. Strict
OpenSpec validation and the development Gradle task's dry run passed. The latest
serial frontend run passed **245 tests/14 files**; an earlier full run hit the
unchanged `SessionPanel` callback timing assertion. No personal frontend code was
changed to hide it. Normal and separate Developer UI builds, tracked/new-file
whitespace checks and the final source-head full backend rerun passed.

This is not production qualification or real-runtime end-to-end acceptance. Existing
independent qualification tasks 6.2/6.3 remain unchanged. No live inference, personal
history inference, model download, deployment, Q3 benchmark continuation or private
capture rewrite was performed. Only the new public Linux OS image was downloaded
for the approved non-inference sandbox. Real-runtime stop/recovery smoke testing still needs
separate explicit approval; changes remain uncommitted.
