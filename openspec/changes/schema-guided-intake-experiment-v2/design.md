# Design

## Context

See [proposal.md](proposal.md#why) for motivation. Relevant observed dependencies:

- `ContextIntakeSuggestionContractV1.kt:23–43,78–144` owns the existing payload
  shapes, UTF-16 binding, dates, recurrence, unresolved fields and text safeguards.
- `StrictProfileDraftJson.kt` rejects unknown keys and primitive coercion, but
  serializer defaults mean it alone does not require every defaultable nested key.
- `ScopedEvaluationFixtures.intake()` already supplies the eight synthetic inputs.
  Its source and the production contract are pinned by the v1 manifest; neither
  may be edited for this experiment.
- The completed CPU baseline has no accepted draft or human scores. Its request
  durations were 118.1–397.2 seconds; neither small models' general incapability
  nor acceptable interactive latency has been established.
- The standalone Mac adapter currently couples safe runtime mechanics to v1
  request construction and context proof. Reuse requires a narrow explicit seam,
  not importing its v1 proof or passing v2 through its v1 authorization function.

## Goals / Non-Goals

**Goals:** Produce a usable offline eight-case wire/binding/semantic pipeline,
then a fake-tested path for one separately approved synthetic experiment. Determine
whether a simpler model boundary yields complete, useful extraction.

**Non-Goals:** Production v2 API or admission, new worker/queue, database/UI work,
RAG, automatic repair, derived clinical/temporal semantics, larger-model download,
hosted transfer, another RAM increase, full causal ablation or automatic adoption.
The parent change's task 9.4 is not completed by this experiment.

## Decisions

### 1. New experimental wire; unchanged final semantic authority

Use experiment profile `context-intake-extraction-v2` in manifests/run metadata,
not a new production profile enum. The raw response root has exactly
`suggestions` and `questions`. Each suggestion has exactly:

| Field | Rule |
| --- | --- |
| `kind` | `context` or `commitment` |
| `sourceExcerpt` | Nonempty exact quote from this case's original intake content |
| `context` | Full existing context payload for context suggestions, otherwise null |
| `commitment` | Full existing commitment payload for commitment suggestions, otherwise null |
| `unresolvedFields` | Existing finite vocabulary, supplied by the model |

Questions remain `{text:string}`. All existing payload fields, including nullable
fields and mode/recurrence fields with old DTO defaults, are explicitly required
in this wire. Keep 20 suggestions / eight questions and strict primitive types.
Both raw wire and assembled draft independently have a 12,288-byte UTF-8 maximum.

Use new **test-source** wire DTOs with no omission-repair defaults, strict parsing,
and a single schema definition exported with the prompt. The schema's required
keys, enums, null branches and `additionalProperties:false` are checked against
accepted/rejected Kotlin vectors. Do not add a second Python semantic validator.

Alternative rejected: only expanding the v1 prose. It leaves identity/offset
bookkeeping in the model and cannot distinguish the new boundary clearly.
Alternative rejected: generating unresolved fields or questions in code. That
would conceal model disclosure failures and introduce new extraction semantics.

### 2. Mechanical binding is a declared part of v2, not response repair

The test-source assembler receives an immutable original packet and a strictly
decoded wire. It binds profile/identity/revision from that packet and locates each
excerpt with Kotlin string indices. Check for a second occurrence beginning one
UTF-16 code unit after the first, so overlapping occurrences are not missed.
Reject empty, missing or ambiguous excerpts. Never normalize whitespace/case,
choose a convenient match, drop a suggestion or consult a model to repair it.

Map payload values and unresolved fields without inference, serialize a canonical
internal v1-compatible draft, then call the unchanged production semantic decoder.
This preserves dates, recurrence, prohibited text, attribution boundaries and
whole-result refusal. The assembled object is explicitly labeled validator
adaptation; it is not stored as raw v1 provider output or a v1 qualification result.

Alternative rejected: model-generated UTF-16 offsets. Exact unique-quote lookup is
deterministic and removes avoidable bookkeeping; its effect on this model's
success rate remains unmeasured.

### 3. One disjoint example and provider-constrained output

Freeze compact explicit instructions plus one independently validated synthetic
input/output example illustrating both payload branches. It must not reuse scored
notes, dates, identities or case-specific answers. Treat all scored packet strings
as untrusted data. Keep the original author/date-resolution fields in the input;
removing output echoes does not remove source evidence or authorize overrides.

Send the frozen JSON Schema object as the provider's `format`, not the literal
`"json"` setting. Bind its hash and exact request serialization in the run. Record
the pinned provider's capability evidence; refusal/unsupported schema aborts, with
no unconstrained fallback. Local decoding and semantic validation remain required
even when the provider claims constrained output. No new dependency is necessary.

This bundles prompt clarity, constrained structure and reduced bookkeeping. It
tests that interface as a whole, not which individual intervention caused a gain.

### 4. Separate freeze and context proof before any model call

Create `benchmarks/intake-extraction-v2/` artifacts and a new integrity manifest.
Reference the original v1 manifest and exact eight input packet/expectation hashes.
Reuse the frozen fixture builder read-only; new export/validation tests live beside
it. Pin the new wire/assembler/exporter/schema/prompt and relevant unchanged
production sources. Any material protocol/schema/prompt/attempt-policy change
requires a new experiment version, never a silent manifest refresh.

Proposed initial candidate is only staged `qwen3:8b`, digest
`500a1f067a9f782620b40bee6f7b0c89e17ae61f686b92c24933e4ca4b2b8b41`,
Ollama 0.35.0 CPU, temperature 0, seed 42, three threads, `num_predict=1536`,
non-thinking/non-streamed, keep-alive zero. Initially propose `num_ctx=4096`;
**fit is unresolved and admission-blocking**, not assumed from v1 or RAM.

Implementation must account for the exact rendered example/instructions/packet,
template and the pinned provider's schema handling. Use a sound conservative bound
or verified offline tokenization, with the full output reserve. If this cannot
prove fit, stop before inference and request a separately reviewed context-setting
decision. Do not warm a model to guess fit, truncate packets, remove requirements
or silently raise context. Provider wrapper and wire request stay bounded to
65,536 bytes; template/schema changes invalidate the proof.

### 5. One attempt; reuse runtime safety, not v1 protocol assumptions

Initially freeze exactly one scored request per case. A complete rejected/empty
response advances to the next case; no correction or deterministic identical retry
is offered. This spends less CPU and reports first-pass usability directly.
Additional corrections or model comparisons require another approved protocol.

Propose the existing 900-second attempt / 1800-second case / four-hour batch limits,
including validation in case/batch time. Probe startup is bounded by 900 seconds
and remaining batch time; verified stopping receives its separate 60 seconds.
Use the approved six-vCPU / 10-GiB VM, three model CPUs/threads, 8-GiB hard limit
and 7.5-GiB current-memory stop. These are proposals for a new approval record,
not permission to run. Missing monitoring, pressure, restart/identity drift,
timeout, incomplete output or infrastructure failure aborts without fallback.

Reuse the current Mac adapter's pod/HTTP/monitor/UID/cgroup/CRI safety mechanics.
Add only an explicit caller-owned schema-bearing request and verified context-proof
seam in non-frozen `mac_runtime.py`; v1 construction and authorization remain the
default behavior. Both adapters validate their own immutable profile/manifest/run
before runtime action. Prove v1 request bytes and refusal behavior unchanged with
golden/fake checks; never route v2 through `acquire.authorize` for v1. No general
provider framework or duplicate weaker stop implementation is justified.

### 6. Stage outcomes and human review remain separate

Record raw provider outcome/bytes and hashes, strict-wire status, binding status,
canonical assembled draft when available, and production-semantic status separately.
Use fixed content-free diagnostic categories. Preserve full private wrappers when
bounded, all eight cases including unrun ones, exact settings/runtime/tooling identity,
timing and observed memory. Null remains null for unavailable measurements.

An offline Kotlin bridge returns those distinct outcomes; private replay creates
the independent human packet without inference. Human scores, useful-final and
critical-failure assessments start blank. Experimental success inherits substantive
v1 dimensions/weights: all eight complete/useful; every case/dimension at least
2/4; weighted mean at least 80%; zero critical failures; independent human scoring.
Anything less is diagnostic, not usability acceptance or production qualification.

Historical comparison must show different attempt policy and memory envelope.
Do not compare the new bundle's latency as a controlled RAM/prompt benchmark or
claim schema acceptance alone proves faithful attribution/completeness. Keep the
stronger reference model as an optional separately approved follow-up, not a
prerequisite for this small experiment.

## Risks / Trade-offs

- **Schema overhead may exceed the 4096 context proof:** offline admission blocks;
  explicit follow-up is required rather than weakening the protocol.
- **Pinned provider may not support the chosen schema subset:** use only verified
  supported constraints; refusal blocks acquisition without JSON-only fallback.
- **Unique-quote binding rejects some otherwise reasonable extractions:** retain
  that limitation; do not add disambiguation heuristics to this experiment.
- **Model still must handle subtle dates and recurrence:** preserve the validator
  and human assessment; do not promise the structural intervention solves semantics.
- **CPU latency may remain unacceptable:** report per-case timings; choose any
  interactive latency target in a later product decision, not after seeing scores.
- **Safety-adapter reuse can regress v1:** isolate contract-specific seams and require
  unchanged v1 request/refusal tests plus frozen integrity before integration.

## Migration Plan

No application deployment or data migration. Implement offline wire/assembly/export
first and demonstrate all eight synthetic fake cases end-to-end. Then implement
the separately gated acquisition/replay path and fake safety checks. Stop at a
reviewable readiness report until a named run is explicitly approved. A future
approved result produces only an experimental decision, never gate changes.

Rollback disables or removes the new experimental entry points. Existing manual
intake, v1 contracts, frozen evaluations and production admission stay unchanged.
