# Connected Review Development Specification

## Purpose

Enable durable, inspectable synthetic connected-review tasks with replaceable local models, exclusively owned execution, queued-only supersession and event-driven retention, without authorizing personal-data inference.

## Requirements

### Requirement: Development execution is isolated and synthetic only
The system MUST expose development execution only through an explicitly enabled, authenticated loopback launcher with separate durable storage. It MUST accept only bundled synthetic case identifiers and MUST NOT open personal storage, import records, accept arbitrary packets or enable production inference. Import execution MUST remain separate from the inference worker.

#### Scenario: Disabled normal application
- **WHEN** the normal application starts, including with development configuration present
- **THEN** development execution routes are absent and the production qualification gate remains closed

#### Scenario: Arbitrary packet rejected
- **WHEN** a development caller supplies packet text, a database path, an unknown case identifier or a personal snapshot identifier
- **THEN** the request is rejected without model calls or personal storage access

### Requirement: Model selection is explicit and immutable
The system MUST require explicit local endpoint, runtime version, model tag, manifest digest and generation settings. Every preview and run MUST bind these settings, the case and exact prompt digest immutably. It MUST NOT select a default, silently switch providers or accept browser-supplied endpoints or generation overrides.

#### Scenario: Runtime or model changed
- **WHEN** the observed Ollama version or model digest differs from the configured binding before a send or publication
- **THEN** execution is refused or invalidated without fallback or published output

#### Scenario: Model replaced for future development
- **WHEN** the operator configures another supported local model
- **THEN** new previews identify that model and existing run bindings remain unchanged

### Requirement: Transport remains bounded and local only
The development transport MUST access only its exclusively owned private local runtime, disable redirects/retries and bound request, envelope and draft bytes. It MUST request non-streamed, non-thinking JSON with fixed generation settings and reject incomplete responses, thinking output and protocol mismatches. Existing independent runtimes MUST NOT be taken over or modified.

#### Scenario: Incomplete or oversized response
- **WHEN** Ollama returns unfinished generation, non-empty thinking output, an oversized envelope or an oversized draft
- **THEN** the attempt terminates with a fixed protocol or budget failure, with no automatic retry or partial publication

#### Scenario: Redirect to another endpoint
- **WHEN** a configured endpoint returns a redirect
- **THEN** no request is sent to its target and the run fails without exposing response bodies

### Requirement: Extended budgets are development local only
Development inference MUST use an explicit policy capped at 900 seconds per attempt and 1840 seconds total, total at least attempt. At most two attempts and existing byte/validator limits MUST remain. Queue waiting MUST NOT consume the inference budget, but remaining creation-based TTL MUST cap execution time. Deadlines MUST use monotonic elapsed time; normal/hosted bounds MUST remain unchanged.

#### Scenario: Policy cannot extend hosted inference
- **WHEN** a hosted provider is supplied with the extended development policy
- **THEN** execution is rejected before any provider call

#### Scenario: Deadline expires during generation
- **WHEN** the attempt or whole-run deadline expires
- **THEN** the active request is cancelled, the run ends with the corresponding timeout reason and no correction attempt is started

### Requirement: Manual submission is asynchronous and idempotent
The system MUST durably queue authenticated, CSRF-protected manual submissions and return identifiers without waiting for inference. Repeating an unexpired preview MUST return its existing run without resetting budgets or TTL. Inspection MUST be read-only; at most one run MUST execute per owned runtime. Expired preview identifiers MUST NOT recreate deleted work.

#### Scenario: Browser refresh or duplicate click
- **WHEN** the browser refreshes, polls status or repeats a submitted preview
- **THEN** it observes the existing run and does not initiate inference

#### Scenario: Another run is active
- **WHEN** a different preview is submitted while an admitted run remains active
- **THEN** the new run is durably queued within capacity without interrupting or overlapping active inference

#### Scenario: Duplicate after retention expiry
- **WHEN** an expired preview is submitted again
- **THEN** no run is recreated and a new explicit preview is required

### Requirement: Queue history survives restart without uncertain replay
The system MUST persist immutable inputs, ordered queue entries, attempts, ownership and outcomes. After restart, eligible unclaimed work MUST remain queued; previously claimed work MUST be reconciled as interrupted rather than replayed. Capacity MUST be bounded without evicting active work. Reconciliation MUST prevent old ownership epochs from publishing or starting new execution.

#### Scenario: Restart after request may have started
- **WHEN** the worker restarts with a durable claim whose completion is uncertain
- **THEN** the run is not retried, old output is fenced and execution remains blocked until the old execution boundary is verified stopped

### Requirement: New input supersedes only matching queued work
Genuinely newer trusted input for the same logical output slot MUST atomically supersede matching queued runs and record replacement links. Identical or older input MUST NOT supersede newer work. Running runs MUST continue unchanged; their old-input output MUST NOT replace the latest-input view. Unrelated tasks or model comparisons MUST NOT supersede each other.

#### Scenario: New input while predecessor is running
- **WHEN** a running task has an older queued successor and a newer input revision arrives for the same slot
- **THEN** only the queued successor is superseded, the running task continues and the newest task waits for its runtime to be safely released

#### Scenario: Different output slot
- **WHEN** new input arrives for a different case, model or contract slot
- **THEN** unrelated queued and running work is preserved

### Requirement: Cancellation cannot publish late output
Cancellation, shutdown, execution expiry or health failure MUST atomically prevent late publication and invoke owned-runtime stop. Cancellation MUST be idempotent and distinguish accepted cancellation from confirmed termination. New execution MUST remain blocked until stop is confirmed; further submissions MUST still queue within capacity. Supersession MUST NOT cancel running work.

#### Scenario: Completion races with cancellation
- **WHEN** output arrives after cancellation was accepted
- **THEN** it is discarded and the run remains cancelled without published interpretations

#### Scenario: Remote generation remains uncertain
- **WHEN** the HTTP request closes but runtime-idle confirmation is unavailable
- **THEN** status reports unconfirmed execution stop, later work can queue but cannot execute, and socket closure is not reported as successful cleanup

#### Scenario: Publication already committed
- **WHEN** cancellation is requested after validated publication won the terminal transition
- **THEN** the response reports the existing outcome rather than claiming the run was cancelled

### Requirement: Runtime termination has authoritative evidence
One dedicated worker MUST exclusively control inference and its execution process tree. Stop confirmation MUST verify all owned execution processes exited and bind the receipt to run and runtime ownership identity. Model residency, socket closure, lease expiry or replacement readiness MUST NOT substitute for stop evidence. Shared runtimes MUST NOT be terminated.

#### Scenario: Detached execution descendant
- **WHEN** the main Ollama process exits but an owned execution descendant remains
- **THEN** stop stays unconfirmed and no subsequent inference begins

#### Scenario: Owning host is unreachable
- **WHEN** old execution cannot be verified stopped after a crash
- **THEN** the queue remains inspectable but new inference is blocked without forced takeover or cross-node failover

### Requirement: Runtime and resource health fail closed
The system MUST observe the owned runtime host before execution, during inference and at publication. Observations MUST verify identity, readiness, expected restart state, memory limits and at least 1024 MiB available host memory. Missing/stale/failed evidence MUST block or stop work, not qualify it. Submission MUST NOT wait for these observations.

#### Scenario: Resource data unavailable
- **WHEN** the runtime-host resource observer fails or reports insufficient available memory
- **THEN** inference is refused or cancelled and the run reports a fixed health failure

#### Scenario: Pi example memory budget
- **WHEN** an 8 GiB Pi is configured with roughly 2 GiB OS/other-process use and a 1 GiB reserve
- **THEN** the example whole-worker/runtime ceiling is 5 GiB, including worker overhead, and measured host headroom is still required rather than treating a 1 GiB test VM or reserve as the inference allocation

#### Scenario: Unrelated loaded model
- **WHEN** the dedicated runtime is already generating or has an unrelated model loaded
- **THEN** admission is refused without unloading or modifying the unrelated model

### Requirement: Validation and correction remain independent
Only a completed whole draft accepted by the existing connected-review validator MUST be published. A validator rejection MUST permit at most one bounded correction using only a fixed rejection reason and the original prompt. Rejected text MUST NOT enter correction prompts. Transport, timeout, health and cancellation failures MUST NOT trigger correction or provider fallback.

#### Scenario: Invalid draft then valid correction
- **WHEN** a completed first draft fails validation and the remaining budgets and health checks permit correction
- **THEN** one correction is attempted under the original binding and only its wholly validated output can be published

#### Scenario: Structurally accepted empty draft
- **WHEN** the validator accepts a draft with no interpretations or questions
- **THEN** it is labeled as providing no connected insights, not as evidence of model usefulness or qualification

### Requirement: Development diagnostics do not disclose content
Run diagnostics MUST use fixed non-sensitive reasons and bounded timing, identity and attempt metadata. Prompts, context, credentials, raw provider responses and rejected text MUST NOT appear in logs or error bodies. Exact prompt inspection and validated output MUST be available only through authenticated development inspection.

#### Scenario: Provider error contains sensitive text
- **WHEN** a provider error body or exception includes a prompt, credential or output marker
- **THEN** logs and failure responses contain none of those markers

#### Scenario: Persistence failure
- **WHEN** a run operation encounters a database failure or adversarial field value
- **THEN** fields remain data rather than database instructions, atomic lifecycle guarantees are preserved and responses/logs expose neither SQL statements nor raw driver messages

### Requirement: Cleanup and reconciliation are event-driven task phases
The inference worker MUST process durable queue events through bounded cleanup/reconciliation before eligible task execution. Startup MUST reconcile ownership and inspect pending events, without an independent retention sweep. There MUST be no idle TTL-cleanup timer or periodic housekeeping wake-up. Active inference deadlines and health monitoring MUST remain enforced.

#### Scenario: No pending events at startup
- **WHEN** startup has reconciled old execution and finds no pending queue events or tasks
- **THEN** the worker waits without independently sweeping expired history

#### Scenario: Task arrives after history expires
- **WHEN** a committed queue event wakes the worker
- **THEN** it reconciles ownership and performs bounded due cleanup before starting the next eligible task

#### Scenario: Committed event was not notified
- **WHEN** a notification was lost before restart or a subsequent event
- **THEN** reading durable pending events recovers the work without resubmitting or replaying claimed inference

### Requirement: Explicit maintenance reuses the same worker
Authenticated explicit maintenance-only tasks MUST run the same bounded cleanup/reconciliation phases without inference. They MUST share execution ownership with normal tasks, never create a competing cleanup worker or interrupt a healthy active run. Read-only queue inspection MUST NOT initiate maintenance.

#### Scenario: Ad hoc cleanup request
- **WHEN** a maintenance-only task is explicitly submitted
- **THEN** the same worker performs due cleanup/reconciliation, records its outcome and starts no model request for that task

### Requirement: Runs expire one day after creation
Every run MUST have an immutable expiry 24 hours after creation regardless of outcome. Claim and publication MUST reject expired work; active execution MUST be capped by remaining TTL. Later queue events MUST delete eligible histories and associated payload asynchronously. Without events, physical deletion can lag expiry; inspection MUST hide expired payload.

#### Scenario: Idle queue past TTL
- **WHEN** a retained run passes expiry and no further event arrives
- **THEN** it cannot execute or publish, its payload is hidden from inspection, and deletion remains pending without a cleanup-only wake-up

#### Scenario: Cleanup covers every outcome
- **WHEN** task processing finds successful, failed, cancelled, superseded or expired history past its fixed expiry
- **THEN** its payload and associated history are deleted in bounded idempotent batches without extending TTL

### Requirement: Retention cannot erase unresolved execution safety
Payload deletion MUST preserve minimal non-payload fencing metadata while prior execution is unconfirmed. It MUST NOT release the execution block or allow stale callbacks to recreate content. Cleanup MUST be bounded, retryable on later events and transactionally coordinated with claim, publication and cancellation. Maintenance signalling MUST remain possible at capacity.

#### Scenario: TTL expires during unconfirmed stop
- **WHEN** an expired run's payload is deleted while stop evidence remains unavailable
- **THEN** its minimal ownership/uncertainty record persists and continues blocking new inference until reconciliation confirms stop

### Requirement: Worker actions have bounded causal summaries
The worker MUST report structured summaries of triggering events, actions, counts, affected opaque run IDs and fixed reasons for execution, cleanup, supersession or refusal. Summaries MUST be authenticated, content-free and creation-TTL-bound. They MUST explain recorded decisions rather than expose model reasoning, credentials, prompts or raw failures.

#### Scenario: Supersession and blocked execution
- **WHEN** newer input supersedes queued work but prior runtime termination remains unconfirmed
- **THEN** the summary explains both the supersession and execution block without claiming the running predecessor was superseded or revealing packet content
