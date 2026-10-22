# Tasks

**Implementation approved; 5/11 tasks complete.** The eight-case offline pipeline,
private replay and independent-review packet pass focused checks. No inference, download, hosted transfer or runtime
operation is authorized. The existing v1 qualification task remains incomplete
and separate.

**Paused before acquisition preparation:** approved cached-asset inspection and
matching public source/header fetch resolved the task 3.2 context-size question.
Offline tokenizer counts for audited inputs peak at **816**; with 1536 output and
256 additional margin, **2608 <= 4096**. Retain 4096; no runtime setting changed.
See [token evidence](../../../benchmarks/intake-extraction-v2/README.md#source-audited-token-counts-and-retained-context).
The separate [provider-schema blocker](../../../benchmarks/intake-extraction-v2/README.md#provider-schema-compatibility-blocker)
is confirmed: cached GBNF conversion does not enforce frozen `uniqueItems:true`.
Local duplicate rejection remains strict, but does not authorize silently changing
the provider contract. Wait for a versioned compatibility decision. Tasks 3.1–4.2
remain unchecked; no adapter scaffolding, schema projection, dependency install,
model download, runtime mutation or inference occurred.

## 1. Usable offline wire and semantic validation

- [x] 1.1 Add test-source experimental wire, strict decoder, provider JSON Schema and one disjoint example; verify required nested keys, null branches, enums, duplicate/unknown-key and coercion refusal, count/byte bounds, and a valid example for both payload kinds. Document the wire beside the new benchmark without editing v1.

  Context: [wire/schema requirements](specs/intake-extraction-evaluation/spec.md#requirement-model-output-owns-extraction-rather-than-input-identity), [design decisions 1 and 3](design.md#1-new-experimental-wire-unchanged-final-semantic-authority).
  Dependencies: `ContextIntakeSuggestionContractV1.kt:23–43`,
  `StrictProfileDraftJson.kt`, `ContextIntakeSuggestionContractV1Test.kt`.
  New helper/tests belong under `backend/src/test/kotlin/com/gtrainer/`, not production routes.

- [x] 1.2 Add deterministic identity/unique-excerpt binding and staged invocation of the unchanged production semantic validator; verify missing/repeated/overlapping excerpts, emoji UTF-16 offsets, wrong identity attempts, whole-result rejection, assembled-size bounds, unsupported dates/recurrence and missing disclosures/questions. Distinguish binding from semantic failures in test outputs.

  Context: [binding requirement](specs/intake-extraction-evaluation/spec.md#requirement-source-binding-uses-unique-exact-excerpts), [semantic requirement](specs/intake-extraction-evaluation/spec.md#requirement-existing-semantic-safety-remains-authoritative), [design decision 2](design.md#2-mechanical-binding-is-a-declared-part-of-v2-not-response-repair).
  No normalization, item removal, missing-field filling or inferred values.

- [x] 1.3 Export and freeze a new eight-case protocol, schema/example and exact requests from the original read-only intake fixture builder; verify original packet/expectation equality and hashes, all eight fake end-to-end vectors, disjoint example validation, new-source integrity failures and unchanged v1 manifest verification. Document that these are fake pipeline checks, not model quality evidence.

  Context: [baseline preservation](specs/intake-extraction-evaluation/spec.md#requirement-experimental-evaluation-preserves-the-baseline), [design decision 4](design.md#4-separate-freeze-and-context-proof-before-any-model-call).
  Read only `ScopedEvaluationFixtures.intake()` and the intake rows of the old protocol/artifacts.
  Target new data/tooling: `benchmarks/intake-extraction-v2/`. Do not modify the
  pinned fixture/exporter, production source, old verifier, README or frozen files.

## 2. Private replay and independently inspectable results

- [x] 2.1 Add an offline Kotlin stage-validation bridge and separate private v2 replay/report workflow; verify strict approval/profile/manifest/settings binding before reading responses or invoking callbacks, exclusive private writes, all stage outcomes/raw hashes, explicit eight-case denominator, imported-resource provenance and blank independent human assessments. Document the separate replay approval and output format.

  Context: [evidence requirement](specs/intake-extraction-evaluation/spec.md#requirement-evidence-separates-every-validation-stage), [design decision 6](design.md#6-stage-outcomes-and-human-review-remain-separate).
  Reuse `capture.py` private-file primitives read-only where suitable, not its v1
  capture schema or semantic interpretation. New reports identify v2 raw wire and
  internal validator adaptation separately.

- [x] 2.2 Demonstrate actual offline Kotlin replay across all eight fake cases and negative/early-abort cases; verify no inference, no sensitive markers in terminal/repository/CI diagnostics, no overwritten evidence, no fabricated measurements/scores, empty-output non-usefulness, inherited rubric arithmetic and production admission closed. Record a usable offline checkpoint before adding the live acquisition path.

  Acceptance: [offline checkpoint](../../../benchmarks/intake-extraction-v2/README.md#offline-checkpoint-2026-10-18).

  Context: [usefulness requirement](specs/intake-extraction-evaluation/spec.md#requirement-usefulness-remains-independently-assessed), [approval requirement](specs/intake-extraction-evaluation/spec.md#requirement-experiment-approvals-confer-no-production-authority).

## 3. Bounded acquisition preparation with fake checks only

- [ ] 3.1 Add only the explicit schema-bearing request/context-proof seam needed in the existing non-frozen Mac adapter; verify byte-identical v1 requests and existing ownership/monitoring/UID/cgroup/CRI refusal/stop tests, with zero runtime actions for invalid v2 bindings. Keep profile authorization separate and document the compatibility boundary; add no provider framework or second weaker stop implementation.

  Context: [schema-bound requests](specs/intake-extraction-evaluation/spec.md#requirement-requests-bind-schema-and-example-explicitly), [design decision 5](design.md#5-one-attempt-reuse-runtime-safety-not-v1-protocol-assumptions).
  Dependencies: `benchmarks/scoped-profiles/mac_runtime.py`, its focused tests and
  the unchanged v1 frozen verifier. No production runtime-admission changes.

- [ ] 3.2 Implement strict experimental run/approval records and exact v2 context admission; verify schema/template/model/runtime/tooling hashes, explicit VM/resource identity, schema-support refusal, complete input plus output-reserve proof and zero calls when proof is absent. Record whether proposed 4096 context fits; if unproven, stop and request a context-setting decision rather than enlarging it or weakening the frozen experiment.

  Context: [context requirement](specs/intake-extraction-evaluation/spec.md#requirement-context-admission-includes-the-new-request-shape), [design decision 4](design.md#4-separate-freeze-and-context-proof-before-any-model-call).
  Do not reuse the v1 2088-token upper bound or run a model to guess input fit.

- [ ] 3.3 Add one-attempt-per-case acquisition and private stage-evidence orchestration; verify fake full/empty/rejected/timeout/incomplete/unrun paths, all eight cases, no correction/fallback/automatic replay, monotonic attempt/case/batch limits, separate startup/stop timers, memory/monitor failures and unverified-stop fencing. Document exact proposed request, limits and approval procedure without making model calls.

  Context: [attempt requirement](specs/intake-extraction-evaluation/spec.md#requirement-acquisition-has-one-bounded-scored-attempt-per-case), [design decisions 5 and 6](design.md#5-one-attempt-reuse-runtime-safety-not-v1-protocol-assumptions).

- [ ] 3.4 Run focused Kotlin/Python integration checks, v1 frozen integrity, strict OpenSpec and changed-document/privacy/whitespace checks; verify an eight-case fake acquisition can be replayed through the actual Kotlin bridge. Publish a readiness checkpoint listing proven checks and any context/schema/approval blockers; do not describe fake checks as live safety, usefulness or qualification evidence.

  This task integrates completed slice checks; it does not defer their own tests.
  A blocker keeps acquisition closed even when implementation artifacts exist.

## 4. Separately approved experiment and decision

- [ ] 4.1 Only after explicit named-run approval and all admission proofs, run the single bounded Qwen3 8B active-request stop probe and eight-case experiment under a fresh immutable record; verify exact identity/settings/resources, observed monitoring, owned termination/restoration and retention of every attempt/unrun outcome. Document actual results and baseline differences without editing frozen inputs or starting another run automatically.

  **Not authorized by planning or implementation approval.** Proposed bounds and
  candidate are in [design decisions 4 and 5](design.md#4-separate-freeze-and-context-proof-before-any-model-call).
  Stronger reference-model calls, downloads, hosted transfer and personal data are
  outside this experiment and require separate decisions.

- [ ] 4.2 After separate offline replay approval, create the private review packet, obtain independent human assessments and record the complete eight-case experimental decision against inherited thresholds; verify scores are not model- or code-generated, missing outcomes stay in the denominator, comparisons disclose confounders and all production/v1 admission remains closed. Record an inconclusive/failed result honestly; never mark usefulness proven without the required evidence.

  [Success and approval boundaries](specs/intake-extraction-evaluation/spec.md#requirement-usefulness-remains-independently-assessed).
  Experimental success is not production integration and does not complete the
  parent change's v1 qualification task by substitution.
