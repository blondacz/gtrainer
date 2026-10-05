# Proposal

## Why

Connected-review contracts, snapshots and inspection already exist, but there is no real Ollama provider wired for development and the current run endpoint waits synchronously. Development needs a durable, inspectable queue and a dedicated runtime-owning worker: queue status alone cannot prove remote inference stopped, while the promising but incomplete Pi Qwen3 Q3 reference remains unqualified.

## What Changes

- Add a dedicated, opt-in, authenticated loopback development launcher using fixed synthetic cases and a separate persistent queue database. It never opens the personal database, imports records, or permits arbitrary user packets to enter inference.
- Implement a provider-neutral-compatible Ollama adapter with explicit endpoint, model tag, manifest digest, runtime version and bounded generation settings. Qwen3 Q3 is an example configuration, not a hard-coded/default model or a qualified candidate.
- Add a separately identified local development execution policy: explicit configurable budgets up to fifteen minutes per attempt and thirty minutes forty seconds total, at most one correction, fixed prompt/response limits and real resource/identity checks. Normal application and hosted policies remain unchanged.
- Persist manually submitted runs and their immutable inputs, outcomes and execution/cleanup status. Execute one run at a time; supersede only matching queued work when genuinely newer input arrives, never stop a running predecessor merely because input changed.
- Introduce an event-driven worker that exclusively owns a new dedicated Ollama runtime. Normal tasks include cleanup/reconciliation before execution. Startup reconciles execution ownership and checks pending events, without an unconditional retention sweep; there is no idle cleanup timer. Provide an explicit maintenance-only task through the same worker for ad hoc cleanup/reconciliation without inference.
- Give every run a fixed 24-hour TTL from creation. Expired queued work cannot start; active expiry caps its execution deadline. Delete retained payload/history asynchronously when subsequent queue events are processed, potentially later than expiry; retain minimal non-payload safety metadata while old execution remains unconfirmed.
- Provide queue inspection, explicit cancellation and idempotent submission in a special Developer tab behind an opt-in visibility switch. Show synthetic-only/unqualified labels, task identity, revision, expiry, queue order, budgets, health and separate outcome/execution/cleanup states. Record bounded worker action summaries with fixed reasons explaining cleanup, supersession, execution and refusal decisions; the visibility switch never grants inference permission.
- Preserve independent whole-draft validation, immutable packet/model bindings, redacted diagnostics, no fallback and no automatic timeout retry. Do not mark existing qualification tasks complete or enable production inference.
- Apply relevant Effective Java principles idiomatically: defensive copies, encapsulated state/resources and explicit concurrency contracts. Refactor touched oversized methods into cohesive operations and scattered SQL into named repository queries with parameter-bound values; verify behavior and privacy with regression tests rather than adding an ORM or broadly rewriting unrelated code.
- Keep the Pi example's roughly 5 GiB whole-appliance memory budget distinct from the measured 1 GiB minimum host-headroom guard and from the small non-inference Linux test VM. Neither the estimate nor process-fixture acceptance proves model fit or qualification.

## Capabilities

### New Capabilities

- `connected-review-development`: Isolated synthetic-only durable queue, runtime ownership, bounded execution, queued-only supersession, crash reconciliation and asynchronous retention for replaceable local Ollama models.

### Modified Capabilities

- `guidance-dashboard`: Add development-only queue, run and retention inspection without changing personal dashboard or production interpretation permissions.

## Impact

Reuse `ConnectedReviewProvider`, `ConnectedReviewExecutor`, `InterpretationContractV1`, SQLite and existing Ktor/coroutine dependencies. Add isolated queue/event persistence, runtime process supervision, ownership verification, event-driven cleanup and frontend inspection with synthetic tests. Deliver disabled worker/runtime packaging examples without taking over the provisioned Ollama deployment. Imports remain separate and unchanged; the inference worker owns only its queue/runtime cleanup. No hosted transport, model download, image publication, deployment, live runtime change, personal-data inference or live database migration is authorized by this planning revision.
