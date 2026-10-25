# Design

## Context

See [proposal](proposal.md#why). The following observed gaps, not more benchmark
infrastructure, determine this work:

- `ScopedProfileAdmission.kt` reports all three profiles unqualified/closed.
  `PersonalReviewRuntimeAdmission.kt` has ownership/cleanup seams but is not wired
  into the normal application. `ScopedExecutionRoutes.kt` only prepares, inspects
  and deletes inert previews; amendment preparation is explicitly refused.
- `OllamaReviewFocusModel.kt` intentionally supplies no accepted live-health guard.
  Its legacy review-focus adapter does not implement the three scoped contracts.
- Normal UI, factual reports, context/commitment confirmation and exact amendment
  approval exist. Pending UI fixes have 386 passing frontend checks and must be kept.
- The one-shot live intake proof used a test-classpath app and a frozen synthetic
  packet. It is not personal execution authority or a reusable service installation.
- The owner chose local-only processing and both app/model on the Mac, wants no app
  expiry, and permits operational inspection when requested. Known credential files
  exist with private metadata; validity remains untested. No secret content was read.

## Goals / Non-Goals

**Goals:** An installed, persistent private app and three genuinely executable manual
model journeys, with failure visibility, owner feedback and a bounded release proof.
Move to ordinary usage after that proof rather than scheduling another research campaign.

**Non-Goals:** Hosted providers, initial AI plan generation, autonomous plan changes,
automatic model schedules, Pi rollout, new downloads, VM enlargement, medical/safety
judgments, invented Garmin scores or claiming independent model qualification.

## Decisions

### 1. Install a persistent Mac app, not a retained demo

Use the normal backend/frontend and a non-root, operator-installed launchd user
service. Install an immutable versioned application bundle outside the checkout;
bind only `127.0.0.1:8080` and keep normal password/session/Origin/CSRF controls.
No public/LAN ingress and no development/pilot routes are enabled. The service has
no elapsed-lifetime stop and never deletes owner data on shutdown.

Use private persistent storage under `~/Library/Application Support/GTrainer/UAT/`,
with separate configuration/runtime/evidence locations and existing secret-file
references. Never open a demo database as owner storage or silently adopt another
instance's database. Refuse incompatible existing schemas. Installation must have a
tested encrypted backup/isolated restore and an explicit app-version rollback plan;
do not claim that reverting a binary reverses database changes.

The Mac must be awake and the user's service session available. launchd can restart
the app, not invent model readiness or replay interrupted inference. Normal session
expiry means sign in again, not lose data or recreate an installation.

**Rejected:** Keeping a chat-owned demo alive indefinitely, exposing Vite/LAN HTTP,
or adding the Pi-to-Mac control channel before the first usable installation.

### 2. Make owner UAT a named admission mode

Use an explicit `owner-local-uat-v1` policy bound to the installed application,
contract/prompt/schema versions, allowed profiles, named cached model/runtime,
resource limits and owner acknowledgement. Status remains `unqualified`; expose
experimental UAT acceptance separately from ordinary execution qualification.
Local selection alone does not grant permission. No hosted selection is accepted.

The scoped parent requires independent human qualification and separate runtime
approval. This plan adds an explicit owner-UAT exception, not a reinterpretation of
old results. Before activation, reconcile that parent's qualification wording and
the main specification so the exception and unchanged ordinary gate are unambiguous.
Retain frozen contract evaluation before calls and independent assessment before
claiming usefulness qualification. This prerequisite cannot be satisfied by filling
human-score cells with model or code output.

The first installation accepts manual jobs only. Factual schedules may retain their
separate enrollment rules; they must not acquire owner-UAT model permission. Owner
policy persists independently of the chat, but restarts still require ownership
reconciliation and explicit model re-arming. Source/model/policy drift blocks sends.

**Rejected:** Returning `qualified`, borrowing developer permission, or changing
`executionAllowed` without a runtime implementation and accepted owner policy.

### 3. Reuse one owned execution path across three profiles

Connect normal authenticated request/status/cancel/result routes to the existing
personal admission, claim store and executor. A normal UI action creates one durable
job and returns its identity; polling/reload only reads it. A separately installed
launchd supervisor must retain responsibility for bounded execution/cleanup if the
browser, request handler or app process disappears. Its lifecycle is independent of
the app service. Use existing ownership journals, epochs, delayed-start fencing and
cleanup seams rather than a parallel generic queue or provider framework.

Implement the missing Mac control/provider adapter against the already cached pinned
Qwen3:8b/Ollama assets. Each job owns its runtime identity and descendants; the app
cannot cancel arbitrary user processes or adopt a shared model as owned. Preserve
CPU-only three-thread execution, the existing 10-GiB VM/8-GiB hard limit/7.5-GiB
`memory.current` stop, pressure and observed-mmap checks, identity verification and
UID/cgroup/CRI stop proof. Accept the known original deployment left paused as an
explicit new-runtime baseline; do not silently mutate it or alter historical defaults.
No image pull, package download or VM resize is part of admission.

Proposed owner-UAT job limits are distinct from unchanged PERSONAL/DEVELOPMENT policy:

| Boundary | Proposed limit |
| --- | --- |
| Concurrent model jobs | 1 |
| Attempts | 1; no corrective attempt or automatic retry |
| Job total | 1200 seconds, including cleanup reserve |
| Admission/startup | At most 120 seconds and remaining work budget |
| Generation | At most 900 seconds and remaining work budget |
| Validation/publication | At most 30 seconds and remaining work budget |
| Cleanup reserve / owned stop | 60 seconds |
| Initial context / output reserve / margin | 4096 / 1536 / 256 tokens |

Use fixed sleep-inclusive monotonic job deadlines, never renewed on inspection or
phase changes. Persist the clock/deadline binding and host boot identity in the owned
journal. A supervisor restart within the same verified boot retains the original
deadline; changed boot identity or unverifiable clock continuity fences the job.
Wall-clock timestamps are diagnostic, not authority to renew elapsed-time budgets.
The supervisor cannot execute cleanup while the host sleeps or control is unavailable:
the 1200-second sleep-inclusive deadline bounds permitted execution/publication, not
an unconditional physical-stop guarantee during suspension or supervisor failure.
Sleep never pauses or extends the budget. On wake/recovery, fence expired/unverifiable
work before publication and verify authoritative stopping before releasing capacity.
Do not claim that a process stopped at its deadline when only a later recovery stop
is proven.
The 4096-token context is an initial conservative execution setting, not the model's
inherent maximum or a cost quota. The owner should work in useful activities and date
ranges, not manage token budgets. A separately tested and explicitly accepted runtime
binding may use a larger context if it stays within the accepted memory, latency and
stop boundaries. Such a revision requires new context/resource evidence and installed
policy acceptance; it is not automatic growth, a fallback or a retry of a refused job.
The October 24 vocabulary-only check does not authorize additional native checks,
resource experiments or generation. Its four counts describe the original input
representation, not the compact representation below or a larger-context runtime.

The service has no global experiment deadline or quota that expires the app. An
explicitly cancelled, timed-out or uncertain job cannot publish or release occupied
runtime capacity without authoritative stopping. Do not automatically offer a stronger
retry or larger context after a refusal. Existing five-minute personal limits remain unchanged;
the new UAT limits require acceptance before use.

### 4. Finish the actual profile integrations

- **Descriptive:** Prepare an immutable factual snapshot, show its exact scope and
  selected local model, execute only on request, strictly validate the source-linked
  result, and show it beside facts. Persist owner feedback against the original
  result without rewriting it or treating feedback as a verified measurement.
- **Intake:** Use a separately versioned, profile-bound prompt/schema and mechanical
  binding adapter into the unchanged production draft validator. The proven benchmark
  helper is test-source and frozen; do not move/edit its pins to deploy it. A small
  main-source adapter needs parity tests for exact excerpts/UTF-16 offsets/nullable
  payloads/unknowns, with no answer injection or repair. Preview output, owner edits
  and explicit source-revision-bound confirmation remain distinct stages. Revising the
  original note invalidates older source-bound work; editing staged suggestions does
  not rewrite/invalidate immutable model output and remains an attributed owner edit,
  confirmable only against the unchanged source revision.
- **Amendment:** Open the currently refused preparation path only after implementing
  deterministic candidates from the selected authored plan and enabled permissions.
  Give the model only those candidates and fixed explanation choices. Validate its
  selection and show the existing exact-diff approval flow. Revalidate source, plan,
  permissions and constraints at approval. No original plan or permission is guessed
  because the owner has not entered one.

For every profile, prove the exact effective prompt/schema input fits context with
the reserved output and margin. No silent record truncation. A smaller date/source
selection is a new explicit owner action. Rejected/empty output is not a usable
success, and a synthetic witness can never replace a failed personal result.

The owner approved a versioned lossless descriptive model-input representation after
the bounded check. Keep the immutable canonical snapshot, compiled safety instructions
and original output validator unchanged. Encode repeated object shapes and repeated
string values once in explicit tables, with typed positional nodes and zero-based
table references. Its bounded inverse must reproduce every canonical JSON byte,
including order, identities, dates, revisions, units, nulls and attributed text. No
selected evidence is omitted, summarized, normalized or silently truncated. The model
still returns complete original source references, never transport indexes.

Bind the wire version, canonical packet and complete effective model prompt separately.
Changing the codec, appended decoding instructions or effective schema requires a new
runtime/policy binding and request-specific context proof. Offline parity and fake
execution test implementation only: neither fewer bytes nor a successful fake draft
proves token fit, real-model comprehension or usefulness. The new format remains
inactive in the default production path until the provider, context/resource admission
and operational approvals are complete.

**Rejected:** Wiring the legacy review-focus adapter as if it covered all profiles,
promoting a single frozen capture into live suggestions, or making plans authoritative
at model publication time.

### 5. Put results and operational state where the owner works

Expose request/history/status/cancel controls alongside the factual review, source
note and authored plan. Link saved results through their actual source records, not
a manual UUID entry. Keep advanced old connected-review inspection available only as
historical inspection, without confusing its IDs with new scoped evidence/results.

Show experimental status, evidence dates, selected model, queue/running state, elapsed
time, per-job limits, refusals, stale evidence and verified/unverified stopping.
Inspection cannot start cleanup, consent, inference or import. Cancellation is an
explicit mutation. Retain diagnostics as fixed reasons/counts/timings/identity hashes,
not health values, prompts, credentials or rejected text. Store private model evidence
only in the private application/evidence store with an explicit retention policy.

On-demand operator/assistant monitoring uses this content-free status. It must not
claim continuous agent presence or convert a status read into background maintenance.

### 6. Close out history without creating false passes

Use one disposition table in the task record before any sync/archive:

| Existing change | Required disposition |
| --- | --- |
| provider-compatible-intake-experiment-v3 | Verify completed evidence; reconcile/archive |
| automated-intake-conformance-experiment-v4 | Verify completed evidence; reconcile/archive |
| automated-intake-unseen-evaluation-v5 | Verify completed evaluation and fixture-pilot evidence; archive without changing original7/8 or post-hoc8/8 |
| guarded-live-intake-pilot | Verify completed one-shot and cleanup evidence; archive without reopening its allowance |
| schema-guided-intake-experiment-v2 | Preserve5/11 and six unchecked tasks; formally superseded/deferred incompatible live path, never passed |
| connected-insights-context | Preserve unfinished comparison/human qualification; transfer any still-required obligation explicitly before administrative closure |
| scoped-reviews-and-adaptive-planning | Preserve unfinished9.4 qualification; owner-UAT exception is new policy, not completion of that assessment |
| foundation-release-verification | Credit only actually verified work; defer/transfer Pi rollout obligations because the first UAT host is Mac |

Reconcile the connected-context parent before scoped additive deltas, then experimental
lineage and this UAT exception. Where delta intent overlaps, retain the stronger
applicable safety boundary and document the exact resolution rather than blindly
appending/replacing sections. Main-spec sync is not a hardware or qualification test.
Incomplete archival needs explicit disposition/approval and must retain original
unchecked evidence; never manufacture checked boxes to make an archive command pass.

## Risks / Trade-offs

- **Model latency/quality remain unknown for real packets:** fixed budgets, no silent
  retries, clear unavailable states and a positive normal-app acceptance requirement.
- **Three journeys could become another large scaffold:** first complete the normal
  descriptive vertical slice with fake owned transport; then intake and amendment
  slices. Do not add unrelated infrastructure before each slice has usable behavior.
- **Local sleep/crash can strand model work:** independently supervised cleanup,
  sleep-inclusive durable deadlines, boot/restart fencing and uncertain-stop blocking,
  with factual UI intact when the app is available. Suspended-host cleanup is not a
  physical deadline guarantee.
- **Personal records are sensitive:** private files/authenticated loopback, no hosted
  client in UAT, no payload diagnostics and fresh compatible encrypted recovery points.
- **Old specs and new UAT policy can conflict:** resolve the named qualification
  prerequisite before activation; keep ordinary qualification incomplete/closed.

## Migration Plan

First preserve and verify pending UI work and reconcile the policy/closeout map.
Prepare a versioned private Mac installation and recovery procedure using isolated
synthetic stores. Complete the three offline normal-app journeys and refusal/cleanup
tests before live operations. Do not change the existing Pi deployment.

At the operational checkpoint record explicit authority for persistent installation,
one owner-selected bounded Intervals read and readiness sends: at most one synthetic
stop probe plus one positive request for each of the three named profiles, without
correction, retry or replacement acceptance run. Use an owner-entered plan/permissions
for real amendment acceptance; if absent, report that journey blocked rather than
inventing the owner's plan. Retain actual outcomes and independent stop evidence.

Successful owner-UAT handover requires positive accepted output through all three
normal journeys, not merely safe refusals. Then the installed owner policy permits
ongoing explicit manual requests under per-job limits without another demo grant;
it does not authorize automatic inference. Document any blocked capability honestly.
The app remains installed while the owner returns and reports issues.

Rollback disables model admission, revokes/fences jobs and verifies stopping before
switching an app version. Preserve the persistent database and use only a compatible
tested restore when needed; never reset it or delete the PVC/private store as rollback.
