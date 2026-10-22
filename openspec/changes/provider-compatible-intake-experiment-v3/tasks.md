# Tasks

**Execution and assessment complete; 10/10 tasks, failed experiment.** After five preserved
admission failures, approved run-06 completed its active-request stop probe and all
eight sequential single-attempt cases in the 10/8/7.5-GiB envelope with mmap/no-repack.
Acquisition-time strict validation accepted six responses, rejected i01 semantically
and i06 at wire stage. Subsequent independent human assessment is now complete.
Owned termination, restoration and primary read-only final checks passed; the original shared service has
one Ready replica and no loaded model. All restart/run authority is consumed. No
extra inference/retry/restart/resource change is approved. Separately approved
run-06 private replay also completed; all eight raw/stage/resource values match.
Original blank packet/report remain unchanged; separate completed human assessments
and decision are retained. Human judgments: 8/8 useful, minimum score 3, zero critical
failures. Frozen outcome-gated weighted mean **71.71875%**; two validator rejections
remain unchanged, so the experiment fails. No experimental or production success,
parent qualification, additional replay, archive or spec sync is authorized.
See [live admission and approved safety fixes](live-admission-hold.md).
Frozen v2 stays paused
at 5/11. The user approved revising the unfrozen v3 plan after the
[audit checkpoint](compatibility-audit.md#current-stopping-point): the five-file
source fetch showed that repetition maxima above 2000 become unbounded. The
approved schema allowlist now removes two `uniqueItems:true` and three
`maxLength:4000` declarations, preserving all strict local limits. The checkpoint
records the pre-amendment blocker, not a current requirement for another decision
on those same five keywords. The amended plan was presented, and the user explicitly
resumed apply. Task 1.1 now passes the source-backed audit and offline vectors;
this is not native grammar execution, inference or acquisition readiness.

The first usable slice is sections 1–2: a separately frozen, strict offline v3
pipeline and private eight-case fake replay. Do not add acquisition scaffolding
while compatibility is unresolved. Use the task pointers below as context indices.

## 1. Provider-compatible freeze with unchanged local acceptance

- [x] 1.1 Audit every keyword/value in the proposed provider schema against already cached matching source/runtime evidence; document constraint ownership and verify exactly the two unresolvedFields uniqueItems and three maxLength:4000 declarations are omitted, with source-backed remaining-constraint vectors and duplicate/over-4000 provider vectors that strict local validation rejects. Retain minLength and all other bounds. Stop and ask if another constraint is unsupported or unproven; do not freeze readiness or install/download anything to bypass a gap.

  Acceptance: [complete subset audit](../../../benchmarks/intake-extraction-v3/README.md#compatibility-evidence),
  `experiment.ownership()` source identities and all six `IntakeExtractionV3Test`
  tests pass. The 22 focused Kotlin tests include unchanged v2/contract safeguards.

  Context: [ownership requirement](specs/intake-extraction-evaluation/spec.md#requirement-v3-declares-provider-and-local-constraint-ownership), [design decision 3](design.md#3-audit-the-entire-provider-subset-before-freezing-readiness).
  Dependencies: `IntakeExtractionV2.kt:29–57` and the private source evidence named
  in the [v2 findings](../../../benchmarks/intake-extraction-v2/README.md#provider-schema-compatibility-blocker).

- [x] 1.2 Add the small test-source v3 adapter/exporter and exclusive new protocol/artifacts/ownership/manifest freeze; verify exactly five allowlisted schema differences, unchanged packet/instruction/example/expectation bytes, prior manifest hashes, all eight case IDs and unchanged strict duplicate/key/null/coercion/bounds, excerpt/UTF-16 and semantic rejection vectors. Include 4000/4001 ASCII boundary vectors for context content and both excerpt branches, rejecting overflow at wire stage without repair. Document read-only v2 validation reuse; run v1/v2 integrity before and after, without modifying pinned sources.

  Acceptance: exclusive freeze and six Python integrity tests pass, as do post-freeze
  Kotlin equality and all 25 focused tests (including the three new bridge checks).
  V1/v2 integrity remains unchanged. Private evidence:
  `intake-v3-offline-implementation-0acnc951` under the approved temp root.

  Context: [preservation requirement](specs/intake-extraction-evaluation/spec.md#requirement-v3-preserves-prior-experiments-and-inherited-inputs), [validation requirement](specs/intake-extraction-evaluation/spec.md#requirement-v3-retains-strict-extraction-and-source-validation), [design decisions 1–2](design.md#1-version-only-the-changed-experimental-boundary).
  Dependencies: frozen v2 helper/exporter, `ScopedEvaluationFixtures.intake()`,
  `benchmarks/intake-extraction-v2/experiment.py`. New target: test-source v3
  adapter/tests and `benchmarks/intake-extraction-v3/`; no production code changes.

## 2. Usable private offline replay and bound context evidence

- [x] 2.1 Add a v3-only offline bridge and private replay/report workflow; verify exact version/profile/manifest/tooling/settings authorization before response reads or callbacks, cross-version refusal, exclusive private writes, size bounds, raw hashes, staged outcomes, complete eight-case denominator, resource provenance and null human fields. Document actual replay record formats and separate real-response permission.

  Acceptance: eight replay unit tests, six frozen-integrity tests and the three
  Kotlin bridge checks pass; two explicitly gated Kotlin integration tests remain
  for task 2.2. See [record formats](../../../benchmarks/intake-extraction-v3/README.md#private-offline-replay).

  Context: [authorization requirement](specs/intake-extraction-evaluation/spec.md#requirement-v3-authorization-is-isolated-from-earlier-versions), [evidence requirement](specs/intake-extraction-evaluation/spec.md#requirement-v3-evidence-preserves-stage-and-measurement-provenance), [design decision 5](design.md#5-finish-offline-behavior-before-acquisition-mechanics).
  Dependencies: v2 `replay.py`/bridge as read-only reference and existing private
  file primitives. Do not reinterpret a v2 approval or add a Python semantic validator.

- [x] 2.2 Bind v3 exact schema/requests/rendered hashes to the runtime-matched offline context evidence and demonstrate real Kotlin replay across all eight fake cases plus duplicate-wire, binding, semantic, empty and early-stop outcomes; verify 816 maximum input tokens + 1536 output tokens + 256 margin tokens fit 4096 only for unchanged audited rendering, invalidate drift, keep provider measurements null and run focused Kotlin/Python, v1/v2/v3 integrity, privacy/log and document checks. Record this usable offline checkpoint before proceeding.

  Acceptance: [usable offline checkpoint](../../../benchmarks/intake-extraction-v3/README.md#usable-offline-checkpoint-2026-10-18).
  All 16 v3 Python checks pass with both actual Kotlin integrations enabled;
  complete private fake reports remain independently inspectable and unscored.

  Context: [context requirement](specs/intake-extraction-evaluation/spec.md#requirement-v3-context-evidence-is-independently-bound), [design decision 4](design.md#4-rebind-context-evidence-do-not-repeat-inference-free-discovery-broadly).
  Evidence pointer: [audited counts](../../../benchmarks/intake-extraction-v2/README.md#source-audited-token-counts-and-retained-context).
  Fake responses are pipeline checks, never model quality evidence or human scores.

## 3. Bounded acquisition preparation with fake checks only

- [x] 3.1 Add only the explicit frozen request/context-proof seam to the existing non-frozen Mac adapter; verify byte-identical v1 default requests, existing UID/cgroup/CRI/ownership/monitoring/cancellation/stop refusal tests, schema-bearing v3 requests and zero runtime actions on invalid or missing v3 proofs. Document the separate authorization boundary without a provider framework or duplicate stop path.

  Acceptance: five v3 seam tests and all 53 existing scoped tests pass; all three
  experiment integrity checks remain intact. The first existing-suite run caught
  changed v1 monitor-before-payload ordering; restored original ordering and reran
  successfully. See [seam boundary](../../../benchmarks/intake-extraction-v3/README.md#acquisition-request-seam).

  Context: [authorization requirement](specs/intake-extraction-evaluation/spec.md#requirement-v3-authorization-is-isolated-from-earlier-versions), [design decision 5](design.md#5-finish-offline-behavior-before-acquisition-mechanics).
  Dependencies: `benchmarks/scoped-profiles/mac_runtime.py` and focused runtime tests.

- [x] 3.2 Implement immutable v3 run/approval records and one-attempt-per-case orchestration; verify exact manifest/tooling/model/template/schema/context/resource bindings, fresh expiring purpose-bound approval, zero actions before authorization, separate probe/startup/stop timing, monotonic attempt/case/batch bounds, and fake accepted/rejected/empty/timeout/incomplete/unrun paths. Document proposed exact settings/limits; schema refusal, monitoring loss, pressure, identity drift and unverified stop must abort without retry, fallback or replay.

  Acceptance: 13 acquisition tests and eight seam/CLI/bridge-budget tests pass.
  Primary independently inspected and corrected the child implementation's missing
  experiment binding, existing-input handling, validator return shape and budget,
  raw retention, resource validation and logging-failure cleanup. No additional
  child or independent review was used. See [records and bounds](../../../benchmarks/intake-extraction-v3/README.md#proposed-acquisition-records-and-limits).

  Context: [attempt/stop requirement](specs/intake-extraction-evaluation/spec.md#requirement-v3-acquisition-retains-one-attempt-and-owned-stopping), [design decision 6](design.md#6-preserve-run-assessment-and-production-approval-gates).
  A record cannot manufacture user approval. Test-only fake approval fixtures must
  identify fake origin and confer no runtime authority.

- [x] 3.3 Exercise the complete fake acquisition through the real Kotlin stage bridge and private replay; verify all eight outcomes, stop fencing, unchanged v1 bytes/safety, v1/v2/v3 integrity, privacy, strict OpenSpec and whitespace checks. Publish readiness with compatibility/context evidence and all remaining approval gates; do not call fake results live safety, usefulness or qualification evidence.

  Acceptance: [bounded acquisition preparation checkpoint](../../../benchmarks/intake-extraction-v3/README.md#bounded-acquisition-preparation-checkpoint-2026-10-18).
  Current sources pass 40 v3 Python checks (five actual offline Kotlin integrations),
  25 focused Kotlin tests, 53 existing scoped tests, 19 v2 unit tests, all frozen
  integrity checks and private-evidence inspection. No live inference or probe ran.

  Integration only: tasks 3.1/3.2 must land their own focused tests and documentation.
  Do not make runtime calls, probe or warm the model during implementation.

## 4. Separately approved execution and assessment

- [x] 4.1 Only after explicit named-run approval and all admission proofs, execute the bounded active-request stop probe and eight-case Qwen experiment; verify exact identities/settings/resources, observed monitoring, owned termination/restoration and preservation of every attempt/failed start/unrun case. Record actual evidence without initiating another run or modifying frozen files.

  This task is not approved by planning or implementation. Probe output is not
  scored. No model/reference download, hosted transfer or personal data is included.

  Attempted under separate explicit approval: [named live attempt](../../../benchmarks/intake-extraction-v3/README.md#named-live-attempt-v3-qwen-intake-01-2026-10-18).
  Metadata admission passed; the probe stopped at 8023.86 MiB sampled current memory
  above the 7680-MiB threshold before active-request verification. All eight scored
  cases are unrun. UID/cgroup/CRI termination and deployment restoration passed;
  primary read-only post-run verification passed. Task remains unchecked because
  neither the verified active-request probe nor the scored experiment completed.
  Preserve this failed attempt; no automatic retry, limit change or replay.

  Separately approved run-02: [attempt and recovery fence](../../../benchmarks/intake-extraction-v3/README.md#named-live-attempt-v3-qwen-intake-02-2026-10-19).
  It stopped at 9504.02 MiB sampled current memory above the 9216-MiB threshold.
  All eight scored cases remain unrun. Owned UID/cgroup/CRI termination is verified,
  but cancellation/restoration is unverified: host pressure warning blocks baseline,
  leaving the shared deployment at zero replicas. No automatic restart, retry or
  replay; request explicit bounded recovery approval.

  Subsequent user instruction authorized a restart and one fresh attempt at unchanged
  limits. Restart/restoration and offline diagnostics checks passed; see
  [run-03 preparation](../../../benchmarks/intake-extraction-v3/README.md#approved-restart-and-run-03-preparation-2026-10-19).
  This does not complete task 4.1 or authorize another automatic retry or replay.

  Run-03 also aborted during its non-scored probe at **9462.32 MiB** current memory;
  all eight scored cases remain unrun. New counters show **4757.46 MiB anonymous**
  plus **4682.15 MiB file** memory. Owned inference termination is verified; later
  read-only checks show one Ready shared replica, but cancellation remains unverified
  and host pressure warning refuses baseline. See the
  [run-03 outcome](../../../benchmarks/intake-extraction-v3/README.md#named-live-attempt-v3-qwen-intake-03-2026-10-19).
  No additional retry, runtime change or replay is approved.

  User-approved [read-only loader investigation](loader-memory-investigation.md)
  identifies an installed, statically verified `LLAMA_ARG_REPACK=0` candidate.
  Actual run-03 backend and memory benefit remain unproven; stopped-container logs
  were unavailable. No new model call, runtime mutation or guard change occurred.
  A v3-only runtime setting and any fresh attempt require separate exact approval.

  The user subsequently approved the v3-only no-repack setting, scoped verification,
  at most one unchanged-resource restart and one fresh run-04 if all gates pass.
  Implementation and restart acceptance pass: 67 scoped tests, 46 v3 checks including
  five actual offline Kotlin integrations, 19 v2 unit tests and all frozen integrity.
  See [run-04 preparation](../../../benchmarks/intake-extraction-v3/README.md#approved-no-repack-variant-and-run-04-preparation-2026-10-19).
  Task stays unchecked pending actual admitted execution and inspected outcome.

  [Run-04 outcome](../../../benchmarks/intake-extraction-v3/README.md#named-live-attempt-v3-qwen-intake-04-2026-10-19):
  no-repack environment verified, but retained argv/logs show `--load-mode none`
  and a 4977.62-MiB allocated CPU model buffer. Probe stopped at 9759.79 MiB full
  current memory; all eight cases unrun. Termination/restoration and independent
  read-only final checks pass. Task remains incomplete. A `use_mmap:true` candidate
  changes exact provider request bytes and needs separately reviewed bindings and
  approval; no silent frozen-proof refresh or automatic retry.

  Subsequent explicit user approval covers the mmap-only wire option, fresh exact
  derivative request/context bindings and one new run-05 after gates. Preparation
  passes 67 scoped tests, 47 v3 checks (five actual offline Kotlin integrations),
  19 v2 unit tests and all frozen integrity. Baseline proof stays unchanged; a new
  run-bound `requestVariant` proves the only wire delta and binds actual hashes.
  Read-only admission passed; no restart or preparation generation occurred. See
  [run-05 preparation](../../../benchmarks/intake-extraction-v3/README.md#approved-mmap-request-variant-and-run-05-preparation-2026-10-19).

  [Run-05 outcome](../../../benchmarks/intake-extraction-v3/README.md#named-live-attempt-v3-qwen-intake-05-2026-10-19):
  actual mmap and a 4977.62-MiB `CPU_Mapped` model buffer observed. The existing
  host-pressure guard aborted before active-request verification or scoring. All
  eight cases remain unrun. Retained normal-pressure samples are not full-load peak
  evidence. Termination/restoration and read-only final checks passed; no restart
  or extra attempt occurred. Host-headroom changes need separate exact approval.

  The user subsequently selected **Cold resize and one run**: approved one graceful
  cold restart at 10-GiB VM / 8-GiB hard / 7.5-GiB full-memory stop and one fresh
  run-06 after every gate. Resize, tested memory-only deployment patch, identities/
  storage/policies/model/normal-pressure/loopback acceptance passed; no preparation
  generation or download. Offline 67 scoped / 47 v3 / 19 executed v2 checks and
  frozen integrity pass. Request/context bindings and mmap/no-repack unchanged.
  See [cold-resize checkpoint](../../../benchmarks/intake-extraction-v3/README.md#approved-cold-resize-and-run-06-preparation-2026-10-19).

  [Run-06 outcome](../../../benchmarks/intake-extraction-v3/README.md#named-live-attempt-v3-qwen-intake-06-2026-10-19):
  probe passed; all eight sequential responses recorded once; six local accepts,
  semantic rejection i01 and wire rejection i06. All 453 retained host samples normal;
  max observed cgroup current5662.11 MiB/cumulative5665.85 MiB below7680-MiB stop.
  Verified cleanup, restoration and independent primary final checks passed. Exact
  requests/raw/evidence hashes and frozen integrity inspected. No replay or human
  assessment performed; tasks 4.2/4.3 remain unopened. All live authority consumed.

- [x] 4.2 Only after separate real-response replay approval, produce the private v3 review packet through the actual bridge; verify raw/stage hashes, measurement provenance, all eight outcomes and blank independent assessments. Preserve every prior report and leave production/v1 qualification closed.

  Acceptance: user explicitly approved private replay of existing run-06 responses.
  One actual offline Kotlin bridge replay passed (three bridge tests), with exact
  eight-case raw/stage/resource equality to acquisition and conservative imported
  measurement provenance. Separate immutable run/approval/source bindings, private
  modes, blank human packet, review instructions and hash-bound inspection retained.
  Eight replay unit tests pass; 24 report/log files contain no exact raw/assembled
  responses. No new inference, live mutation, repair, scores or prior evidence edits.
  See [private replay checkpoint](../../../benchmarks/intake-extraction-v3/README.md#approved-run-06-private-replay-and-review-packet-2026-10-19).

  Context: [evidence requirement](specs/intake-extraction-evaluation/spec.md#requirement-v3-evidence-preserves-stage-and-measurement-provenance).

- [x] 4.3 Obtain independent human scoring and record the complete experimental decision; verify all eight cases are complete/useful, every case/dimension is at least 2/4, weighted mean is at least 80%, critical failures are zero, the denominator is unchanged and baseline confounders are disclosed. Missing scores or failed thresholds remain inconclusive/failed; no code/model-generated scores, production admission or parent task substitution.

  Acceptance: user explicitly requested in-chat review and supplied identity,
  independence declaration, all 48 scores, eight usefulness and critical-failure
  assessments. Exact answers/comments, immutable receipts/snapshots and separate
  completed packet/decision retained. Human usefulness 8/8, minimum score 3/4 and
  zero critical failures; i01/i06 validator rejections are not overridden. Unchanged
  frozen arithmetic gives **71.71875%**, below 80%, and the all-eight validated-useful
  gate fails. Decision is **failed**, not success or production/v1 qualification.
  Eight frozen rubric tests, exact rational cross-check, evidence hashes, private
  modes and frozen integrity pass. No new inference/replay/live operation or repair.
  See [human review and decision](../../../benchmarks/intake-extraction-v3/README.md#interactive-independent-human-review-and-failed-decision-2026-10-19).

  Context: [assessment/approval requirement](specs/intake-extraction-evaluation/spec.md#requirement-v3-success-requires-independent-assessment-and-separate-approvals).
  Before any later sync/archive, reconcile the shared capability's pending v2/v3
  deltas explicitly; neither workflow is authorized here.
