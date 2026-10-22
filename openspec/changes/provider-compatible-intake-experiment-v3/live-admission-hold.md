# Named-run admission hold

The user explicitly approved task 4.1 for `v3-qwen-intake-01`: one non-scored
active-request stop probe, then eight synthetic Qwen3:8b cases, one attempt each,
with temporary owned pods, deployment pause/restoration and the existing approved
limits. The approval excludes retries, downloads and automatic real-response replay.
No UUID-bound run records, runtime operations, probe or inference have started.

## Confirmed blockers

An independent read-only architecture review identified two blockers. The primary
inspected the current source and confirmed both; model agreement alone is not the
acceptance evidence.

1. `benchmarks/scoped-profiles/mac_runtime.py:167–171,322–323,430–441` records pod
   identity and deployment pause only after successful mutation responses. A
   server-side create or scale that succeeds before its response is lost can leave
   an untracked pod or paused deployment. Cancellation can then return verified
   without reconciling that ambiguous mutation. Record intent before mutation and
   reconcile only the exact deployment identity or uniquely owned pod name/run
   label, binding its UID before deletion. Unresolved ownership/restoration must
   remain unverified; never delete a replacement or unrelated resource.
2. `mac_runtime.py:188–220` writes and closes subprocess stdin synchronously before
   returning its process handle to the bounded wait/cleanup code. A stalled exec
   transport can therefore block delivery outside attempt/probe/metadata deadlines.
   Delivery must be deadline-bounded, with client ownership established immediately
   after process creation and cleanup available on delivery failure.

## Required next decision

Fixing these inherited shared mechanics expands the previously narrow request/
context seam. Ask for explicit approval for focused safety fixes and regression
tests before editing implementation. Do not weaken the gates to execute the
approved run. The existing named-run approval remains recorded but cannot bypass
these blockers. Any eventual run must bind the corrected tooling hashes and pass
all admission checks; no retry or real-response replay is authorized.

Offline checkpoint remains 7/10 tasks complete. Task 4.1 is unchecked; its live
admission is not proven by fake integration results. Frozen v1/v2/v3 artifacts and
historical acquisition evidence remain unchanged.

Execution-slice budget: one child/read-only independent review used
(`ses_eaeec60abffeBfsgJztj5cgrR5`); no implementation child or additional review used.
No background work remains pending.

## Approved fixes and offline acceptance

The user selected **Fix, verify, then run**: focused shared-adapter safety fixes
and regression tests are explicitly approved, followed by the same named run only
if every admission gate passes. This supersedes the request to obtain that scope
approval above; it does not approve another run or real-response replay.

The primary implemented and independently inspected these changes:

- Record unique pod name, run label and random create-token annotation before the
  mutation. Bind only matching metadata and a valid UID. Reconcile an ambiguous
  create before the existing UID/cgroup/CRI stop path. An absent or mismatched
  ambiguous object remains fenced, because absence alone cannot exclude a late
  create. No unrelated object is deleted and no second create is attempted.
- Record pause/restoration intent before mutations. Fence scale operations with
  exact deployment UID, observed resourceVersion and expected replica count.
  Reconcile a lost restoration acknowledgement without repeating the mutation;
  require observed replicas=1, completed rollout and a ready final baseline before
  clearing intent or claiming verified cleanup. Unresolved state remains unverified.
- Start stdin delivery on a daemon worker and expose the client immediately to
  existing deadline waits and kill/reap cleanup. Writer or thread-start failure
  cannot leave the main path blocked in a pipe write. Probe event recording now
  occurs inside client cleanup protection.
- Both live entrypoints include pending mutation state in emergency-stop ownership
  checks. V3 retains final safety state in private cancellation/result evidence.
  Frozen requests, schema, local validators and v1 default request bytes are unchanged.

Offline acceptance: **65 existing/new scoped tests**, **40 v3 Python checks with
all five actual offline Kotlin integrations**, **19 v2 unit tests**, all three
frozen integrity checks, strict OpenSpec and whitespace checks pass. The scoped
suite includes 12 focused new safety regressions. Primary inspected all three
retained fake acquisition/replay bundles: current tooling hashes, clean simulated
final safety state, eight cases, raw hashes and blank independent assessments.

Private evidence basename: `intake-v3-live-safety-approved-0maxvg1a` under the approved
temp root. Tests simulate lost acknowledgements, unknown/foreign ownership,
unapplied/replaced scale, failed restoration, blocked stdin, delivery/thread errors
and probe journal failure. No runtime command, live probe or inference ran during
these checks. These findings are resolved at the offline implementation boundary,
not claimed as live process/monitoring evidence. The approved named run may now
attempt its admission checks; any runtime refusal must still stop it without retry.

Execution-slice child budget is exhausted: the one read-only independent review
above plus routine regression implementation `ses_eaed928a8ffeROccrS7qcY1GKE`.
The primary corrected and completed the supplied tests before running acceptance.
No additional review or background child remains pending.

## Named attempt outcome

After the approved fixes and checks, the primary launched `v3-qwen-intake-01`
exactly once. Metadata admission passed, but the non-scored probe hit the unchanged
7680-MiB sampled-current-memory stop at 8023.86 MiB before its active-request state
was verified. All eight scored cases remain unrun. Owned UID/cgroup/CRI termination,
deployment restoration and an independent primary read-only final check passed.
See the [complete named-attempt record](../../../benchmarks/intake-extraction-v3/README.md#named-live-attempt-v3-qwen-intake-01-2026-10-18).

The memory admission failure is now the active execution blocker. No retry, context/
resource increase or real-response replay was attempted or approved. Task 4.1 stays
unchecked and progress remains 7/10. No child, command or runtime job remains pending.

## Explicitly approved run-02 envelope

The user subsequently selected **9 GiB stop; resize and run**, approving the required
12-GiB VM / 10-GiB model hard limit / 9-GiB stop and one fresh `v3-qwen-intake-02`
probe/eight-case run after all gates. This supersedes the no-retry approval state
above only for that new named attempt. Context, CPU, attempt/time bounds, frozen
artifacts and the separate real-response replay/scoring gates remain unchanged.

Memory-only resize and acceptance passed, as did 65 scoped tests, all 40 v3 checks
including five actual offline Kotlin integrations, 19 v2 unit tests and all frozen
integrity checks. Both prior and current tooling bytes/hashes are retained. See the
[approved resize checkpoint](../../../benchmarks/intake-extraction-v3/README.md#approved-run-02-memory-resize-2026-10-19).
Fresh run records and live admission are still required. No additional retry,
download, automatic replay or qualification authority exists.

## Run-02 outcome and recovery hold

The approved memory-only resize passed acceptance, then the primary invoked run-02
exactly once. Its probe exceeded the 9-GiB stop (9504.02 MiB sampled current memory),
with no verified active-request state and no scored requests. Owned inference was
terminated with UID/cgroup/CRI evidence, independently confirmed read-only afterward.
Cancellation/restoration remained unverified: host pressure warning caused baseline
refusal, leaving the original shared deployment at zero replicas. All eight cases
are unrun. See the [complete run-02 record](../../../benchmarks/intake-extraction-v3/README.md#named-live-attempt-v3-qwen-intake-02-2026-10-19).

No automatic restart or retry is permitted after this cleanup fence. Ask for an
explicit recovery choice; no additional runtime mutation, inference, replay or
download is approved. No background job remains pending. Progress remains 7/10.

## Run-03 restart, outcome and current hold

The user then explicitly requested a restart and another attempt. The primary
preserved previous tooling/evidence, added read-only anonymous/file-memory counters,
verified 67 scoped tests, 40 v3 checks (five actual offline Kotlin integrations),
19 v2 unit tests and all frozen integrity, then gracefully restarted and restored
the unchanged 12/10/9-GiB envelope. Acceptance passed with normal pressure and one
Ready shared replica. The fresh approved run-03 was invoked exactly once.

Its probe again exceeded the unchanged stop: **9462.32 MiB** current memory, with
**4757.46 MiB anonymous** and **4682.15 MiB file** memory at that sample. No active
probe verification or scored request occurred. Owned inference termination passed;
cancellation remained unverified. Later primary read-only checks found one Ready
shared replica and preserved identities/specification/storage/policies, but host
pressure warning still refused baseline. This is not the earlier zero-replica state
and not a verified successful cleanup. See the
[run-03 outcome](../../../benchmarks/intake-extraction-v3/README.md#named-live-attempt-v3-qwen-intake-03-2026-10-19).

The latest invocation is consumed. No job remains pending. No additional retry,
runtime change, download or real-response replay is approved; task 4.1 remains
incomplete. Investigate loading-memory behavior before proposing another execution.

## Run-04 approved variant and inspected outcome

Following the read-only investigation, the user explicitly approved a v3-only
`LLAMA_ARG_REPACK=0` variant, focused tests/runner-log diagnostics, at most one
same-resource restart and one fresh run-04 after gates. Implementation, 67 scoped
tests, 46 v3 checks (five actual offline Kotlin integrations), 19 v2 unit tests,
frozen integrity and restart acceptance passed. The primary invoked it exactly once.

Actual server/runner environment was verified, but retained argv and loader logs
show **`--load-mode none`**, **`load_mode = none`** and an allocated **4977.62-MiB
CPU model buffer**. No-repack alone did not solve loading allocation plus file cache.
The probe hit **9759.79 MiB** current memory above the unchanged 9216-MiB stop.
No active-request probe pass or scored case occurred. See the
[complete run-04 outcome](../../../benchmarks/intake-extraction-v3/README.md#named-live-attempt-v3-qwen-intake-04-2026-10-19).

Termination/restoration and independent primary read-only checks passed: no owned
inference remains, one Ready original shared replica, preserved identities/spec/
storage/policies/model metadata and normal host pressure. High swap use (7835.50 MiB
at final observation) is not sustained headroom proof. No job is pending.

All run-04 authority is consumed. Any mmap-enabled provider request/runtime variant
requires separate review, fresh exact request/context bindings and explicit
execution approval. Preserve old frozen proofs, not a silent rebind or rewrite.
No further restart, attempt, download, real-response replay or guard relaxation is
approved. Progress remains 7/10; task 4.1 stays incomplete.

## Explicit mmap variant and run-05 approval

The user then explicitly requested proceeding with `use_mmap:true`, fresh bindings
and one new test. Preparation adds only that provider option to the existing probe
and eight requests. New exact `requestVariant` binds derivative wire hashes and
unchanged original rendered/context-proof identities; complete original requests
must be reproduced by removing only the new true-valued flag. Frozen artifacts and
proofs are unchanged. Actual mmap mode and a `CPU_Mapped` buffer log are required
before scoring. The existing no-repack setting and all resource/safety/time bounds
remain intact.

All 67 scoped tests, 47 v3 checks (five actual offline Kotlin integrations), 19 v2
unit tests and frozen integrity pass. Read-only admission passed with one Ready
shared replica, no loaded model and normal pressure; no preparation restart or
generation occurred. See [run-05 preparation](../../../benchmarks/intake-extraction-v3/README.md#approved-mmap-request-variant-and-run-05-preparation-2026-10-19).
Only the one fresh exact run-05 is approved after all gates; no additional restart,
resource change, attempt, download or real-response replay is included.

## Run-05 outcome: mapped storage confirmed, host admission still failed

The primary invoked the approved mmap variant exactly once. Derivative request
hashes and actual runner configuration verify the new option; retained logs show
`load_mode = mmap` and a 4977.62-MiB `CPU_Mapped` model buffer. However, the batch
aborted with `host_memory_pressure` before active-request verification or scoring.
All eight cases remain unrun. Partial retained normal-pressure samples do not
establish full-load or abort-time memory headroom. See the
[complete run-05 outcome](../../../benchmarks/intake-extraction-v3/README.md#named-live-attempt-v3-qwen-intake-05-2026-10-19).

Owned termination/restoration and independent primary read-only final checks passed:
no owned inference remains, original shared service one Ready replica, identities/
spec/storage/policies/model metadata preserved, host pressure normal afterward.
There is no pending job. Run-05 approval is consumed. No new restart, resource change,
attempt, download, replay or guard relaxation is approved. Progress remains 7/10;
remaining runtime admission needs a separately approved host-headroom decision.

## Approved lower-envelope restart and run-06 preparation

The user selected **Cold resize and one run**, authorizing the exact 10-GiB VM /
8-GiB hard / 7.5-GiB full-memory stop, one graceful cold restart, matching tooling
and acceptance, then one fresh run-06 after all gates. Mmap/no-repack and all other
bounds remain unchanged. No extra retry, download, replay or pressure relaxation.

The one restart and identity/storage/policy/model/spec/loopback/normal-pressure
acceptance passed. Shared deployment memory limit alone changed to 8Gi; all other
fields are preserved. All focused offline suites and frozen integrity pass. No
preparation generation occurred. See [cold-resize preparation](../../../benchmarks/intake-extraction-v3/README.md#approved-cold-resize-and-run-06-preparation-2026-10-19).
Restart authority is consumed; only the single fresh run-06 remains approved after
its exact authorization and live admission. Task 4.1 remains incomplete.

## Run-06 completed: acquisition gate passed, assessment gates closed

The one approved lower-envelope run completed the non-scored active-request stop
probe and all eight sequential requests, with mmap/no-repack and every original
guard intact. Six responses passed acquisition-time strict validation; i01 failed
semantic validation and i06 failed wire validation. All 453 retained host samples
were normal; observed current5662.11 MiB/cumulative5665.85 MiB stayed below the
7680-MiB stop. This is one successful acquisition, not independent usefulness or
production qualification. See [run-06 evidence](../../../benchmarks/intake-extraction-v3/README.md#named-live-attempt-v3-qwen-intake-06-2026-10-19).

Both owned identities terminated; final restoration and independent primary read-only
checks passed. Original service is one Ready replica, no model loaded; accepted spec,
storage/policies/model identities preserved. No job remains pending. Task 4.1 is
complete, progress8/10. All live authority is consumed. Private real-response replay
requires separate approval; independent human scoring and production acceptance
remain separate gates. No new inference/retry/restart/resource change is authorized.

## Separately approved replay completed; human assessment pending

The user explicitly approved the proposed private replay of existing run-06
responses. One actual offline Kotlin bridge replay completed; all eight exact
raw/stage/resource records match acquisition. Six accepted/two rejected outcomes
are preserved. Private report, blank hash-bound human packet, upstream provenance,
review instructions and inspected evidence are retained. See the
[private replay checkpoint](../../../benchmarks/intake-extraction-v3/README.md#approved-run-06-private-replay-and-review-packet-2026-10-19).

Task 4.2 is complete; progress is 9/10. Task 4.3 requires actual independent human
assessments. No scores were generated; usefulness remains incomplete, experimental
success false and production/v1 qualification closed. No new inference or runtime
mutation occurred. Replay authority is consumed; no additional replay or live
operation is authorized.

## Independent human review completed: experiment failed

The user explicitly requested and completed the review in this chat. All eight
cases and 48 dimension scores, usefulness decisions, critical-failure assessments
and reviewer/independence declarations were supplied by the human and preserved in
separate private assessment artifacts. Human judgments marked all eight useful,
with minimum score 3 and zero critical failures. Validator outcomes remain six
accepted, i01 semantically rejected and i06 wire-rejected.

The unchanged frozen rubric assigns zero contribution to rejected outcomes. With
all eight cases retained, the official mean is **71.71875%**, below 80%; the
all-eight validated-useful gate also fails. The complete experimental decision is
**failed**. See [human review and decision](../../../benchmarks/intake-extraction-v3/README.md#interactive-independent-human-review-and-failed-decision-2026-10-19).

Progress is 10/10 execution/assessment tasks, not qualification success. Parent 9.4,
production/v1 qualification and v2 remain incomplete. Original evidence/blank packet
remain unchanged. No new inference/replay/live mutation, response repair or transfer
outside this requested chat occurred. No additional runtime operation, production
acceptance, archive or spec sync is authorized.
