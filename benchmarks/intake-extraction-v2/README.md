# Schema-guided intake experiment v2

This is an isolated, synthetic-only experiment, not a production profile. It does
not qualify a model, confirm context or apply an amendment. The original frozen
`../scoped-profiles/` benchmark and historical attempts are unchanged.

## Wire and validation

`IntakeExtractionV2.kt` in Kotlin test sources defines the response, provider JSON
Schema and one disjoint synthetic example. A response contains only `suggestions`
and `questions`. Each suggestion supplies `kind`, an exact `sourceExcerpt`,
`context`, `commitment` and `unresolvedFields`. Both payload keys are required; the
inactive one is null. Every nested field is required, including nullable fields.
Questions contain only `text`. The schema rejects extra keys and constrains both
payload branches, enums, primitive types and collection/string bounds. Provider
schema compatibility is not yet established by these offline tests.

The model owns extracted values, unresolved disclosures and questions. Code binds
identity/revision from the original immutable input and derives UTF-16 offsets only
for a unique, nonempty exact excerpt. Repeated and overlapping occurrences fail.
There is no whitespace normalization, fuzzy matching, default-field repair, item
salvage, generated clarification or inferred temporal/clinical content.

Validation records three separate stages:

1. **wire**: strict JSON, required keys, primitive types, branch/enum/count bounds;
2. **binding**: unique exact excerpts and canonical internal draft assembly;
3. **semantic**: the unchanged production intake validator.

Each stage is `accepted`, `rejected` or `not_run`. Fixed reason categories contain
no model text. A semantic rejection retains the assembled draft privately; a
binding rejection produces no partial draft. Raw and assembled drafts each have
a separate 12,288-byte UTF-8 bound. The internal draft is validator adaptation,
not an original v1 model response. Empty extraction may pass every stage but is
not thereby useful.

## Offline artifacts and checks

`artifacts-v2.json` contains the schema, disjoint example and exact new instructions
with the eight original intake packet strings unchanged. `protocol-v2.json` inherits
the substantive case expectations, scoring dimensions/weights and success gates
without changing the denominator. `manifest-v2.json` pins those artifacts and new
implementation sources, and references the unchanged v1 manifest. The initial
freeze is exclusive; existing artifacts cannot be overwritten. Material changes
after freezing require a new experiment version.

```sh
python3 benchmarks/scoped-profiles/protocol.py
python3 benchmarks/intake-extraction-v2/experiment.py
python3 -m unittest discover -s benchmarks/intake-extraction-v2 -p 'test_*.py'
JAVA_HOME=$(/usr/libexec/java_home -v 25) ./gradlew --offline --no-daemon \
  --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process \
  :backend:test --tests 'com.gtrainer.IntakeExtractionV2*' --rerun
```

The Kotlin fixture helper uses synthetic handwritten vectors for all eight cases.
These exercise wire, binding and semantic checks, not extraction quality or
independent usefulness. They are never recorded as model outputs or human scores.

## Boundaries and next gate

Only implementation and offline fake checks are approved. No v2 model call,
download, hosted transfer, personal-data operation or runtime change is authorized.
Live acquisition approval records and automated v2 admission are still pending.
The source-audited offline investigation below supports retaining 4096, but found
a separate provider-schema compatibility blocker.
The prior v1 input-token bound does not account for the new example/schema. The
proposed 4096 context and provider schema subset must be proven adequate before
any future run; failure blocks acquisition rather than weakening or resizing it.

The proposed first experiment allows one scored attempt per case, no correction
or automatic fallback. The historical baseline used a different attempt policy
and smaller memory envelope; any future comparison must disclose both differences.
Success requires independent human assessment: every case complete/useful,
every dimension at least 2/4, weighted mean at least 80%, zero critical failures.
Even experimental success does not change production admission or complete v1
qualification by substitution.

## Private offline replay

`replay.py --directory <private-directory>` processes already recorded responses;
it cannot generate them. Use a pre-existing directory outside the repository,
mode `0700`, with no symlink ancestors. Inputs are exclusively created regular
files, mode `0600`, with no symlinks/hardlinks. Files are bounded to 2 MiB; each
raw response is separately bounded to 12,288 bytes.

Prepare these files only after the applicable explicit approval:

- `run.json`: version `intake-extraction-private-run-v2`, UUID `runId`, execution
  mode `offline_replay`, origin `synthetic_fake` or `externally_recorded`, experimental
  profile, all eight ordered `caseIds`, exact `manifestSha256`, current
  `replay.tooling()` source hashes, model, exact `replay.settings(artifacts)` and
  `replay.LIMITS`. Fake origin requires `replay.FAKE_MODEL` and all resource values
  null. External recordings require the pinned Qwen identity in `replay.MODEL`;
  replay does not independently authenticate their acquisition provenance.
- `approval.json`: version `intake-extraction-replay-approval-v2`, purpose
  `synthetic_offline_replay_only`, `runSha256` over compact run JSON, `recordedBy`,
  UTC `recordedAt` and `expiresUtc` ending in `Z`, at most 24 hours apart. This
  must represent actual permission, not an approval inferred from a file's presence.
- `replay.json`: version `intake-extraction-replay-v2`, ordered `cases` from a prefix
  of the eight frozen IDs. Each case has exactly `caseId`, `outcome`, `raw`, and
  `resources`. Outcomes are `response`, `empty`, `timeout`, `incomplete`, `unrun`.
  There is only one entry per case, never an attempt array or correction. Raw is
  a string or null; a response requires nonempty text. After an infrastructure
  stop, later entries must be unrun. Omitted later cases are recorded as unrun.
  Resources have exactly `elapsedMs`, `peakRssBytes`, `inputTokens`, `outputTokens`,
  `costUsd`, each a nonnegative recorded number or null. Integer counts cannot be
  booleans. Imported measurements are labeled externally recorded/unverified;
  unknown values are never replaced with zeros or guessed from another run.

Approval, manifest, source hashes, profile and exact settings are verified before
reading any response or invoking the Kotlin bridge. Replay creates `replay.lock`
exclusively. Any failure preserves partial evidence and the lock; retry in place
is forbidden. Outputs include private `report.json` and `human-review.json`:

- The report keeps raw bytes as UTF-8 strings and hashes, all three stage statuses,
  any complete assembled validator adaptation, imported resource provenance and
  all eight outcomes. It labels baseline differences and remains usefulness-incomplete.
- The human packet binds the report hash and includes the frozen protocol and
  exact inputs. Every dimension score, useful-final and critical-failure assessment,
  reviewer and independence declaration starts null. Inspect raw and assembled
  evidence with the packet; no automatic or model-generated score is supplied.
- `validation/` keeps per-case raw inputs and stage output. `validation.log` and
  XML/HTML/binary Gradle test reports stay outside the repository under the private
  directory. Terminal messages contain only fixed status, never response excerpts.

`IntakeExtractionV2BridgeTest` uses only the original synthetic fixture builder;
it rejects arbitrary case IDs and any response-file hash mismatch. No store,
provider, runtime or network is opened. Focused offline fake checks may create
test-only run/approval fixtures labeled `synthetic_fake`; they do not confer
permission to replay real model responses or infer usefulness.

## Offline checkpoint, 2026-10-18

Tasks 1.1–2.2 are accepted. Focused offline JDK 25 checks pass **11 new Kotlin tests**:
five wire/binding tests, three artifact/schema tests and three bridge tests, plus
five unchanged production-contract tests (**16 total**).
The ordinary Python suite passes **19 tests**, with two explicit integration tests
skipped unless configured. A separate configured run passes those **two actual
Kotlin replay integrations**: all eight fake cases, and a mixed wire/binding/semantic
rejection plus early-abort/unrun batch. Those tests verify private report placement,
content-free diagnostics and blank independent assessments. No model was called.

The initial wire test exposed a no-op malformed fixture; compilation then exposed
a test JSON-object wrapper error. Primary review also corrected the example's
case-sensitive excerpt and missing clarification, added provider branch constraints
and restored important temporal instructions before the initial freeze. All fixes
preceded freezing. Current checks compare the frozen export byte-for-byte, reject
source/input/expectation changes, and preserve the v1 integrity check. Both strict
OpenSpec validation and whitespace checks pass. These results establish a usable
offline validation pipeline, not provider schema support, context fit, model
usefulness, latency acceptance or production qualification.

## Context admission blocker

This records the initial conservative-bound blocker. The subsequent approved
source/tokenizer investigation below resolves the context-size question for these
frozen requests; acquisition is now blocked on provider-schema compatibility.

The offline readiness check cannot yet admit the proposed `num_ctx=4096`.
Frozen instructions are **2420 UTF-8 bytes**; the largest packet, case `i06`, is
**476 bytes**. Even reusing the earlier **256-byte** template reserve, a byte-BPE
upper bound plus the **1536-token** output reserve totals **4688**, above 4096.
This is a conservative bound, **not an actual token count** and not proof that
the real prompt exceeds the context. The schema serializes to another **3069 bytes**;
how the pinned provider handles it must be established rather than assumed to be
free or counted as literal prompt text.

Task 3.2 explicitly requires a pause when fit is unproven. Task 3.1 has not been
implemented: no adapter seam is useful until the admission decision is resolved.
Tasks 3.1–4.2 remain unchecked. No Mac adapter, VM setting, context value, frozen
artifact or inference approval has changed. A safe next decision is either to
investigate exact offline tokenization and pinned-provider schema handling while
keeping 4096, or explicitly revise the context proposal and prove the revised
bound. Increasing RAM alone does not establish context fit. Neither option
authorizes inference, downloads or a runtime change.

### Approved offline inspection

The user approved cached-asset inspection after the initial pause. Read-only
inspection found the staged model manifest still has digest
`500a1f067a9f782620b40bee6f7b0c89e17ae61f686b92c24933e4ca4b2b8b41` and the
template digest still matches the original binding. Only approximately **5.93 MB**
of GGUF metadata was read; no tensor weights were read or loaded. Metadata reports
`qwen3.context_length=40960`, tokenizer model `gpt2`, pretokenizer `qwen2`,
151,936 vocabulary entries, 151,387 merges and no automatic BOS token. Those values
describe available assets; they do not establish runtime token counts.

The current container's read-only CRI identity matches the pinned OCI digest
`9c1dc45ea758396139ec0adfa52947714c61f8d1e4537a6e2daef8138e7a64a9`.
Its root filesystem contains Ollama and llama libraries, but no standalone tokenizer
tool or matching C headers was found. Host tokenizer packages and Go source caches
are absent. Help/version-only invocation of the cached `llama-server` executable
reports **0.4.1-dev, build 1, commit 161755f29**; it exposes schema constraints but
does not establish Ollama's request transformation or selected tokenizer path.
No model, inference context or new server was started.

Cached executable/library hashes observed during inspection:

| Asset | SHA-256 |
| --- | --- |
| `usr/bin/ollama` | `0b0650a962dda61ec0598141ea11e3b688d225e926c9c00bc2c299d0ed34c4f8` |
| `libllama.so.0.4.1` | `8dc7056ee6548c619bded63829696b439478c357df2c4f6c41594e4f86d56324` |
| `libllama-common.so.0.4.1` | `b1496a95a08a5fb09b66f6b1d900b114126dc94d8daebdb63719c3e826702434` |
| `libllama-server-impl.so` | `feb943531f1ca99ae8937d9ac00101e1b178f7b3d81aa8c17c8f03d2ea849f16` |

At that checkpoint, exact counting remained blocked on verified tokenizer entry-point/ABI and the
pinned provider's rendering/schema path. Guessing a C structure layout, using a
hand-built unverified tokenizer, or assuming schema bytes are free would not be
an admission proof. A larger context would not resolve those evidence gaps by
itself. The next narrow prerequisite is permission to fetch the matching public
source/header files, or equivalent files supplied locally. This does not require
weights, dependency installation, inference or runtime configuration changes.

### Source-audited token counts and retained context

The user explicitly approved **Fetch source only**. Public source/header files were
fetched into the private inspection directory, not installed as dependencies:

- Ollama v0.35.0 resolves to commit
  `cc4069396f3ad2c370c53eed2e4a42ac13adab84`. Its `LLAMA_CPP_VERSION` is `b11081`.
- Upstream llama.cpp `b11081` resolves to
  `161755f29e415e2c33efe906e91843c068efd664`, matching the cached executable's build.
- The reviewed `include/llama.h` defines the 80-byte x86-64 model parameter layout;
  `vocab_only` is at offset 72. The cached, hash-verified library was invoked with
  `vocab_only=true`, `no_alloc=true`, zero GPU layers and no tensor checks. Source
  `src/llama.cpp:344–369` returns after vocabulary loading, before `load_tensors`;
  the actual private log confirms `vocab only - skipping tensors`. No inference
  context, model weights, decoding, generation or new server was used.
- Source inspection covers explicit system/user messages, no model history/system
  layer, no tools/images, the Go-template selection and non-thinking branch. The
  completion tokenizer flags are `add_special=true`, `parse_special=true`.
  Ollama passes the schema separately to sampling grammar, not into this prompt.

Runtime-matched offline tokenization of the source-audited Go rendering produced:

| Case | Input tokens |
| --- | ---: |
| i01 | 798 |
| i02 | 802 |
| i03 | 805 |
| i04 | 796 |
| i05 | 815 |
| i06 | 816 |
| i07 | 816 |
| i08 | 794 |

The first private reconstruction missed one system-header newline. That diagnostic
is preserved; the corrected reconstruction, rendered hashes and token-ID hashes
are recorded separately. The newline correction changes hashes but not these
counts. These are offline counts of audited strings, **not measured provider
`prompt_eval_count` values or an inference result**.

The maximum is **816 + 1536 output = 2352**, within 4096. Retaining a further
**256-token safety margin** gives **2608**, leaving **1488 tokens**. This margin
is additional headroom, not a substitute for auditing unknown rendering changes.
**Retain the proposed 4096 context for these exact frozen intake requests.** No
larger context or RAM increase is needed to establish their input/output fit.
Changed prompts, template, model/tokenizer or request path invalidate this evidence.

The original 4096 setting came from the older connected-review v3 Pi experiment
(three CPUs, 5 GiB container), where context increased from 2048 alongside output
768 to 1536. It was later reused on the 8-GiB Mac VM / 6656-MiB container and then
the scoped intake baseline. It was not a calculated model or host maximum. The
cached model's 40960 declaration is not a resource or latency acceptance result.

Private evidence basename: `intake-v2-source-inspection-approved-o_hqk613` under
the approved temp root. `offline-context-conclusion.json` binds source commits,
counter/result hashes, the corrected counts and the separate compatibility block.
Source fetches, initial diagnostics, corrected helper/results and logs are retained.
No frozen artifact or runtime setting changed; no new task checkbox is earned by
this investigation alone.

### Provider-schema compatibility blocker

The frozen v2 provider schema contains `uniqueItems:true` on `unresolvedFields`.
The pinned provider's GBNF conversion does not enforce that keyword:

- `common/json-schema.cpp:174–194` retains array items and minimum/maximum counts,
  but no uniqueness constraint.
- `common/json-schema-to-grammar.cpp:924–931` generates repetition of the item rule,
  permitting duplicate entries within those count limits.
- The source contains an optional llguidance bypass. Inspection of the actual
  cached `libllama-common.so.0.4.1` converter at address `0x247820` confirms direct
  calls to `common_chat_schema_from_json` and the normalized-schema GBNF converter,
  not that bypass. The hash-bound disassembly is retained privately. This is not
  a conclusion based only on symbol/keyword availability or a different version.

Strict Kotlin wire validation still rejects duplicate unresolved fields. That
local protection is unchanged, but does not make the provider enforce its schema.
The design requires a verified supported provider subset and forbids silent
fallback. Therefore **schema compatibility fails and acquisition remains closed**;
task 3.2 is not complete. No adapter seam was added merely to bypass this block.

A proposed narrow follow-up is a separately versioned provider-compatible schema,
with uniqueness explicitly retained in strict local validation and tested as a
local-only constraint. That changes a frozen schema/expectation boundary and needs
the user's decision before artifact or code changes. Preserve v1/v2 exactly; do
not remove `uniqueItems`, project the schema at runtime, upgrade Ollama or treat
the current schema as supported without that decision. No inference is approved.
