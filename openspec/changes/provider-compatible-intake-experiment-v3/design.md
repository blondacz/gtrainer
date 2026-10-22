# Design

## Context

See [proposal.md](proposal.md#why). This is a narrow compatibility follow-up, not
a new extraction architecture. Relevant observed state:

- `IntakeExtractionV2.kt:47–53` declares `uniqueItems:true`; line 81 separately
  rejects duplicates locally. Its prompt, example, DTOs, binding and semantic
  invocation are frozen and already exercised by the offline v2 pipeline.
- `benchmarks/intake-extraction-v2/experiment.py` verifies v1 first and pins v2
  sources/artifacts. `replay.py` and the Kotlin bridge bind v2 profile/version and
  tooling separately. Their records cannot authorize v3.
- [Offline evidence](../../../benchmarks/intake-extraction-v2/README.md#source-audited-token-counts-and-retained-context)
  establishes 794–816 tokens for audited rendering, not provider measurements.
  The [compatibility finding](../../../benchmarks/intake-extraction-v2/README.md#provider-schema-compatibility-blocker)
  establishes that the actual cached GBNF conversion does not enforce uniqueness.
- Source evidence is Ollama commit
  `cc4069396f3ad2c370c53eed2e4a42ac13adab84`, pinning llama.cpp
  `161755f29e415e2c33efe906e91843c068efd664`. Cached library hashes and disassembly
  bind the relevant conversion path; an optional source branch is not assumed active.
- The separately approved parser/sampler source fetch revealed that
  `src/llama-grammar.cpp:13,655–658` converts finite repetition maxima above 2000
  into an unbounded sentinel. The converter emits `char{1,4000}` for the three
  4000-character schema maxima. The user approved the narrow amendment below;
  [the audit checkpoint](compatibility-audit.md#parser-finding-finite-maxima-above-2000-become-unbounded)
  preserves the pre-amendment blocker and source-derived countervector.
- `benchmarks/scoped-profiles/mac_runtime.py` still constructs/proves v1 requests.
  No experimental acquisition seam exists. Shared runtime mechanics must not become
  a route around profile authorization.

## Goals / Non-Goals

**Goals:** Deliver one usable v3 offline pipeline first; then prepare bounded
acquisition with fake checks. Make provider/local constraint ownership inspectable
without changing local acceptance or claiming more provider enforcement than proven.

**Non-Goals:** Refactoring frozen v2, a general schema framework, another validator,
production integration, broader prompt tuning, provider upgrade, context increase,
dependency installation, tensor loading, inference during implementation or any
personal-data/runtime operation. Parent task 9.4 stays separate and incomplete.

## Decisions

### 1. Version only the changed experimental boundary

Use `context-intake-extraction-v3` and new artifacts under
`benchmarks/intake-extraction-v3/`, including protocol, exact requests, schema,
constraint ownership and manifest. New run/approval/replay/report versions identify
v3. Preserve old manifest hashes and input/expectation equality explicitly.

Create the v3 provider schema at export time with exactly five allowlisted removals:

- `uniqueItems:true` at the context and commitment `unresolvedFields` array nodes;
- `maxLength:4000` at `sourceExcerpt` in each of those two suggestion branches;
- `maxLength:4000` at the context branch's `context.content` string node.

Assert all five removed nodes exist with those exact values, and that the complete
remaining schema equals v2. Retain `minLength:1` on those strings, every lower string
maximum and all array/integer/required/branch constraints. No other removal or
addition is authorized. These 4000-code-unit maxima and unresolved-field uniqueness
remain enforced by unchanged strict local validation, not provider grammar.
Freeze the result as the schema actually sent; do not transform it at run time.
Keep system/user strings and example bytes identical to v2. V3 version metadata
does not enter or change the model response shape.

Alternative rejected: editing v2 or projecting its schema inside the adapter.
Both would hide a frozen protocol change. Alternative rejected: relaxing duplicate
or length validation, trimming strings or generating a deduplicated result; all
weaken or repair the acceptance boundary.

### 2. Reuse local validation without broad refactoring

Add a small test-source v3 export/validation adapter and bridge. Delegate local
response validation to the unchanged frozen v2 helper, including strict wire,
exact unique-excerpt binding and unchanged production semantic authority. Keep v3
profile attribution in the outer experiment records; label assembled internal
drafts as validator adaptations, never original v1/v2 provider outputs.

Pin the reused helper and every transitive validation dependency. Verify v1/v2
integrity alongside v3. Add v3 differential vectors for duplicate disclosures,
required/null branches, duplicate/unknown keys, coercion, byte/count bounds,
overlapping excerpts, emoji offsets, unsupported dates and missing clarification.
Add exact 4000/4001 ASCII boundary vectors for context content and each excerpt
branch, plus unchanged UTF-16/byte checks. A provider-shaped response beyond these
local limits must fail the wire stage without trimming, binding or semantic calls.
New code may not mutate the reused helper or remap its failures to success.

Alternative rejected: duplicating all DTOs and semantic checks for a provider-only
schema change. A wrapper plus hash binding retains one local acceptance boundary.

### 3. Audit the entire provider subset before freezing readiness

Freeze a constraint-ownership record: provider structural guarantees, local-only
uniqueness/4000-code-unit ceilings/byte checks, deterministic binding and semantic
authority. Audit every
remaining schema keyword and its actual usage against the pinned conversion path,
including required/additional properties, branch/null alternatives, enums, integer
limits and array/string bounds. Do not infer complete compatibility from the known
uniqueness and repetition-threshold findings or from local JSON-schema vectors alone.

Use already cached matching sources and runtime identity, with source-backed
positive/negative grammar cases where available. Record the exact evidence and
limitations; unresolved provider behavior is a blocker, not a passing audit. Any
additional unsupported constraint requires a user decision before changing the
allowlisted schema delta. No downloads, builds requiring dependencies, server
startup or model call is authorized to fill an evidence gap automatically.

Alternative rejected: claiming that strict local validation makes an unsupported
provider keyword harmless. V3 instead states the intentional boundary explicitly.

### 4. Rebind context evidence; do not repeat inference-free discovery broadly

Bind v3 schema/request/artifact hashes to the source-audited rendering and cached
tokenizer evidence. The prior maximum 816 plus 1536 output is 2352; with 256 extra
safety tokens it is 2608, below 4096. The margin is not proof for unknown rendering.
Reuse is valid only after proving unchanged exact rendered hashes, same model,
template/tokenizer identity, no hidden system/history/tools and grammar-only schema.
Keep provider-measured token counts null. If any relevant input or identity changes,
invalidate the proof and stop; do not silently resize, truncate or infer fit from RAM.

Proposed settings remain temperature 0, seed 42, `num_ctx=4096`,
`num_predict=1536`, three threads, `think=false`, `stream=false`, `keep_alive="0s"`.
Candidate remains staged `qwen3:8b`, manifest
`500a1f067a9f782620b40bee6f7b0c89e17ae61f686b92c24933e4ca4b2b8b41`, pinned
Ollama 0.35.0 CPU image. No new model or runtime setting is proposed.

### 5. Finish offline behavior before acquisition mechanics

First freeze the reviewed v3 artifacts and deliver private replay through the actual
Kotlin bridge for eight fake cases and staged rejection/early-stop vectors. Bind
v3 profile/manifest/tooling before response reads or callbacks; keep exclusive
private writes, content-free diagnostics, raw hashes and blank human fields.
This is the first usable checkpoint, not model quality evidence.
Retain the existing independent 12,288-byte raw and assembled draft bounds,
65,536-byte provider request/wrapper bounds and 2-MiB private replay file bound;
the wrapper limit does not enlarge the permitted decoded draft.

Only then add the minimal explicit request/context-proof seam to the non-frozen
Mac adapter. V1 remains the default and its request bytes/safety tests stay equal.
V3 owns strict immutable run/approval binding before any runtime action; it must not
call the v1 authorizer as a substitute. Reject unsupported format without fallback.

Reuse pod UID/CRI/cgroup ownership, loopback restrictions, monitoring, cancellation,
owned termination/restoration and unverified-stop fencing. Do not copy a weaker
stop path or create a provider framework. Fake full runs must traverse the same
request, monitoring and bridge interfaces as the proposed live path.

### 6. Preserve run, assessment and production approval gates

One scored request per case, no correction/retry/fallback/automatic replay. Proposed
limits remain 900 seconds per attempt, 1800 per case and four hours per batch,
including validation in case/batch accounting. Probe startup is bounded by 900
seconds and remaining batch time; verified stop has a separate 60-second bound.
Use the previously approved 6-vCPU/10-GiB VM and three model CPUs/threads,
8-GiB hard limit, 7.5-GiB current-memory stop as proposed identity-bound limits,
  not permission for new execution. Require a named-run approval for the active-request
stop probe and experiment; probe output is not a scored case or model warm-up.

After named attempt `v3-qwen-intake-01` stopped during its probe, the user explicitly
approved a new `v3-qwen-intake-02` envelope: **12-GiB VM, 10-GiB model hard limit,
9-GiB current-memory stop**, including the necessary graceful restart and acceptance
checks before that one fresh run. This supersedes the initial proposed memory
envelope for fresh records only. CPU, context/output, time limits, frozen artifacts,
attempt policy and separate replay/scoring gates remain unchanged. Preserve the
old run and its tooling bytes/hashes; never rewrite its resource identity. The
16-GiB host still must pass pressure checks; the larger allocation is not headroom
or qualification evidence and grants no automatic additional retry.

After run-03 repeated the loading peak, a separately approved read-only investigation
identified optional CPU repacking as a candidate allocated-storage path. The user
then explicitly approved a **v3-only `LLAMA_ARG_REPACK=0` owned-runtime variant** and
one fresh `v3-qwen-intake-04` after focused checks and unchanged-resource restart if
needed. This is an explicit runtime confounder, not a change to frozen model/input/
schema/context/local-validation authority or permission to relax memory/pressure
guards. Fresh records bind `runnerPolicy`; old records are not upgraded. Declared
and actual server environment are verified before chat, and actual runner/buffer
evidence during the existing probe is required before scoring. Bounded retained logs
must not obstruct owned stopping. Shared adapter/deployment and v1/v2 defaults stay
unchanged. See the [investigation](loader-memory-investigation.md) and
[implementation checkpoint](../../../benchmarks/intake-extraction-v3/README.md#approved-no-repack-variant-and-run-04-preparation-2026-10-19).

Run-04 directly exposed `--load-mode none` despite the no-repack environment. The
user subsequently approved the proposed **`use_mmap:true` request variant** and one
fresh run-05 after verification. Its new `requestVariant` binds derivative probe and
eight-case wire hashes separately from unchanged frozen `experiment` bindings.
Removing only the added true-valued option must reproduce each original complete
request exactly. Frozen messages/schema/rendered hashes and context/output reserves
therefore remain authoritative for the identical rendered input; full wire identities
are explicitly different. No artifact/context proof is refreshed or represented as
a measurement of this new runtime. Mandatory observed mmap mode and `CPU_Mapped`
buffer evidence gate scoring. Other settings/guards and no-repack environment stay
unchanged; no restart/resource change or additional retry is included. See the
[mmap preparation checkpoint](../../../benchmarks/intake-extraction-v3/README.md#approved-mmap-request-variant-and-run-05-preparation-2026-10-19).

Complete rejection/empty response advances once; timeout, incomplete response,
missing monitoring, identity drift or infrastructure failure aborts, preserving later
unrun cases. Retain all attempts, failed starts and partial evidence privately.

Real-response replay needs separate permission. Human fields remain null until an
  independent reviewer supplies assessments. Inherited thresholds and all eight-case
denominators remain unchanged. Reports disclose the different intervention,
attempt policy and memory envelope. No result confers production acceptance.

## Risks / Trade-offs

- Another unsupported provider constraint: block before readiness and ask; do not
  widen the schema delta silently or scaffold acquisition around a failed audit.
- Reuse of a v2-named helper could confuse attribution: pin it read-only and test
  v3 outer metadata, cross-version refusal and adaptation labels explicitly.
- Source-backed compatibility is narrower than live reliability: label evidence
  accurately and retain runtime schema-refusal handling after any later approval.
- Strong structural results can conceal poor extraction: keep independent human
  scoring and semantic evidence separate, with no automatic usefulness score.

## Migration Plan

No deployment or data migration. Implementation, if requested later, is additive
to test sources and benchmark tooling except the narrow non-frozen adapter seam.
V2 stays paused at 5/11; its unperformed acquisition tasks are not marked complete
by v3. Do not archive or sync either pending change automatically. Before either
event, reconcile the shared capability deltas explicitly without losing v2 history.
Rollback means stop using v3 and preserve its evidence; no runtime rollback is needed
for offline work. This planning workflow ends before implementation.
