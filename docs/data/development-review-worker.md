# Durable development worker and queue APIs

This is synthetic development tooling, not qualification. No deployment, inference,
model download, existing-runtime takeover or personal-history access is authorized.
All examples remain disabled. Imports still use explicit `HistoryService.sync()`
under its existing mutex, independently of this worker.

## Execution and cancellation

One lifetime-lock-elected coroutine consumes persisted events. Notifications occur
only after API transactions commit and are wake-up hints, not queue authority.
Startup fences/reconciles old ownership, then reads pending events. Without an
event it does **not** sweep retention or schedule a housekeeping timer. A lost
notification is recovered on restart or the next notification; claimed work is
never replayed. FIFO admission is bounded by the same durable execution fence.

For each event the owner runs short bounded deletion/reconciliation phases, then
claims eligible input, starts a fresh supervised runtime, observes health, validates
up to two attempts, and stops the runtime even after successful publication. One
runtime spans only that run and its optional correction. Only active runs have
deadline/health timers. Maintenance waits for healthy active work and runs those
same phases without inference. New input does not cancel a running predecessor.

Explicit cancellation wins a short SQLite compare-and-set before any physical
stop. A late output cannot publish; duplicates remain idempotent. If publication
already won, cancellation returns `accepted=false` with the successful outcome.
Shutdown first fences active output, then cancels/joins execution before closing
storage. Accepted cancellation is **not** a confirmed process stop.

The Kotlin supervisor client uses a private owner-only Linux Unix socket, requires
the worker to be directly parented by PID 1, and never sends arbitrary executables,
PIDs, endpoints or model text. The supervisor authenticates the exact worker PID.
Stop receipts must match run/epoch/generation/container **and** keeper PID/start
ticks and the `linux_subreaper_waitpid_echild` mechanism. Receipt parsing is bounded
and allowlisted; durable confirmation releases the safety fence. Physical stop is
attempted even when storage or request-close fails. The 60-second cleanup ceiling
reserves time for durable receipt writes; the physical stop request gets at most
57 seconds. Repeating a confirmed stop returns the same bound supervisor receipt,
not a fabricated HTTP/socket-success acknowledgment.

Uncertain stop blocks execution, not bounded queue admission. A later event can
retry the same owner/bound handle; old epochs require exact old-container/changed
host-boot proof. Missing proof, keeper loss and unreachable nodes preserve the
block. No forced takeover, readiness inference or automatic inference retry exists.

## TTL, capacity and deletion

Preview eligibility and run creation each have separate immutable 24-hour clocks;
a submitted preview/run pair consumes one of 32 physically retained slots. Claim,
publication and inspection enforce expiry even if no cleanup event has arrived.
Live execution is capped by remaining run TTL as well as the claim-origin budget.

Each event deletes at most eight due pairs, eight maintenance histories and 32
due auxiliary control records. The same event does not repeatedly retry deletion
in a wake-up. Associated prompts, outputs, attempts, processed events and summaries
are removed transactionally. Failed deletion reports `DELETION_FAILED`, hides the
expired payload, and awaits a later event; it does not masquerade as process stop.
Unresolved minimal run/epoch/node/container/keeper safety identities survive payload
deletion until positive termination evidence. They contain no prompt/output text.

Maintenance has eight separate history slots plus one coalesced non-payload signal,
so the 32-pair cap cannot prevent ad hoc catch-up. Repeated pending maintenance
coalesces. The overflow wake-up slot is rearmed, not an inference replay or a TTL
extension of a retained run. A maintenance phase can complete while reporting a
remaining runtime block; deletion failure is reported independently.

Physical deletion can lag TTL indefinitely without events. This is not forensic
erasure: SQLite pages, journals/WAL from prior configurations, filesystem snapshots
or retained external backups may contain remnants. This change creates no backups.

## Authenticated HTTP contracts

All paths below are in the isolated launcher only, with its distinct session cookie.
Writes require the session CSRF token, exact origin and small strict JSON schemas.
Unknown/duplicate keys are rejected; no browser configuration or packet is accepted.

| Method/path | Input/result |
| --- | --- |
| `GET /api/development/configuration` | Cases, exact configured models/policy; synthetic/unqualified/execution flags |
| `POST /api/development/previews` | `{caseId,providerId,modelId}`; `201` exact prompt/model/policy/digest preview |
| `GET /api/development/previews/{id}` | Read-only eligible preview; unavailable is `404` |
| `POST /api/development/runs` | `{previewId}`; prompt `202` durable task, without health/inference wait |
| `GET /api/development/queue` | Retained tasks and bounded maintenance histories |
| `GET /api/development/runs/{id}` | Consistent task/inspection/response-free attempts; expired payload hidden |
| `POST /api/development/runs/{id}/cancel` | `{}`; `{accepted,task}` with independent execution status |
| `POST /api/development/maintenance` | `{}`; `202` maintenance task/coalesced signal |
| `GET /api/development/diagnostics` | Content-free worker/runtime observations and causal summaries |

Duplicate submission preserves the existing run ID, creation, expiry and policy.
Expired preview submission is `410`; deleted/unknown IDs are `404`; capacity is
`409`. All failure bodies use fixed reasons, never SQL/provider/command text.
Inspection and browser polling start neither cleanup nor inference. With no worker
configured, authenticated writes may persist a queue for later explicitly configured
ownership, but cannot call a provider; UI submission is disabled.

Summaries contain fixed reason enums, bounded counts, timestamps and validated opaque
UUIDs only. There are at most four per event and 128 globally. Associated summaries
follow run TTL; newly recorded aggregate cleanup summaries have their own creation
TTL and only opaque affected IDs. They are code-recorded decisions, not model
reasoning. Health samples are observational, not execution permission or stop proof.

## Isolated UI and future operator configuration

Build `frontend/` with `npm run build:development`, then rebuild backend resources.
This produces a separate bundle; the personal `App` never imports the Developer tab.
The isolated page signs in with development credentials. Its opt-in visibility
switch reveals read-only diagnostics and grants no execution permission. Explicit
case/model selection and exact prompt preview precede submission. Refresh restores
only a run ID from the URL and sends GETs, never a submission. No packet is stored
in browser storage. Outcome, execution and retention are presented independently;
empty output and older-input results remain visibly unqualified.

The launcher configuration's optional `worker` object is absent by default. A future
separately approved dedicated Linux appliance must explicitly supply `enabled`,
`nodeName`, `bootId`, exact `containerId`, `supervisorSocket`, whole-appliance
`applianceMemoryLimitBytes`, `protectApplication`, and fixed node SSH host/user/port/
identity/known-hosts files. Construction validates configuration without contacting
a node; enabled assembly additionally requires Linux/PID-1 parentage, the matching
supervisor-injected socket path and owner-only SSH files. Nothing inherits private
application credentials or grants permission from UI visibility.

The 5 GiB Pi whole-appliance ceiling includes JVM/supervisor overhead; 1024 MiB
measured host headroom is a separate guard. The 1 GiB non-inference test VM is neither
allocation nor reserve. Pi protected-app baselines remain immutable across a run.

For a **later separately approved** Mac-launched live run, use job-scoped
`caffeinate -dimsu <command>` rather than permanent sleep-setting changes. No live
run is authorized here. Real Ollama/container stop/recovery acceptance still needs
explicit approval; non-inference fixture receipts are not model qualification.
