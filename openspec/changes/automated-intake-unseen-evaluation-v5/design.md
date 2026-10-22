# Design

## Context

See [proposal](proposal.md#why). The latest acquisition completed with eight local
accepts, seven original-v4 passes and eight post-hoc v4.2 passes. All recovery roots
are terminal and their temporary jobs have been removed. No live workload exists.

Observed implementation constraints:

- `experiment_v4.py` binds the original packets, predicates, fixtures and checker
  through `manifest-v4.json`; these files must not change.
- `revision_v4.py` and `runtime_v4.py` bind requests to those packets. Runtime
  launch membership alone is insufficient: the campaign spends each request once.
- `campaign_v4.py`, `lifecycle_v4.py` and `supervisor_v4.py` currently assume the
  old case IDs, worker entry point and campaign definitions.
- `IntakeConformanceV4BridgeTest.kt` uses old fixture packets, although unchanged
  `IntakeExtractionV2.validate(raw, packet)` can validate a fresh supplied packet.
- `mac_runtime.py` explicitly allowlists the v3/v4 request profiles. The shared
  observed-mmap runtime already exposes request/context seams and authoritative
  ownership, stopping and restoration checks.

The selected prompt has SHA256
`34ed33a07b3e52eb0d61a6152b7dc2a7d714984ddbc5b9b01b732b7f333fbd7e`, stored at
`intake-v4-quality-followup-approved-x9xmzjor/preparation/selected-minimal/instructions.txt`
under the approved private evidence base. Its previous maximum input was 2289 tokens;
that is not context evidence for new packets. The v4.2 parent manifest SHA256 is
`36d783d0f3c2c1ff734a3f59df9e7d2b2b341a76996d951f6200738c98f1d887`.

## Goals / Non-Goals

**Goals:** prospective, inspectable evaluation of one unchanged candidate on eight
new synthetic packets, with frozen semantics and preserved operational safety.
First deliver a usable offline fixture-to-Kotlin-to-oracle path plus a fake durable
batch. Only then enable one bounded live batch after all admission checks.

**Non-Goals:** broad independent holdout claims, prompt tuning, another policy
relaxation after outputs, a scheduler rewrite, automatic model retries/repairs,
application integration, human scoring, production qualification, personal data,
VM/service restart/resize, downloads, fallback, deployment, publication or git.

## Decisions

### 1. Freeze fresh cases and the unchanged candidate before inference

Create `benchmarks/intake-extraction-v5/` with eight distinct IDs `u01` through
`u08`, new intake identities/content and a new profile. Reuse the provider schema
without changes. Author synthetic packets, positive witnesses and typed predicates
offline. Keep expected outputs and oracle texts out of candidate requests.

The case matrix is fixed here; implementation supplies exact short packets and
finite allowed representations, verifies them offline and freezes their manifest
before the first candidate request:

1. `u01`: a fully dated appointment expressed in different scheduling language;
   all known fields, one-off recurrence and no redundant question.
2. `u02`: distinct known travel and tentative training in one intake; do not borrow
   the travel date/timezone. Group genuinely missing training details and predeclare
   context-appropriate training/practice question alternatives.
3. `u03`: a tentative activity with an imprecise weekday/time; retain uncertainty,
   null typed values and unique unresolved fields instead of inventing a date.
4. `u04`: a corrected meeting time with a Unicode prefix and stale earlier wording;
   bind only the current corrected facts while retaining exact correction evidence.
5. `u05`: a second paraphrased fully specified commitment, distinct from appointment
   and meeting; short label case equivalence must not mask a different event noun.
6. `u06`: conflicting attributed exercise guidance with missing applicability;
   preserve both reports, ask about conflict/applicability, never choose advice.
7. `u07`: an onset/ongoing symptom report with explicitly missing recovery evidence;
   distinguish unknown from open bounds without inventing clearance or an end date.
8. `u08`: a dated bounded recurring obligation with a supplied multi-week interval;
   preserve structured frequency, interval and end date, with no unnecessary question.

These are new inputs relative to local candidate acquisitions, not proof they were
absent from model training. The same primary agent authors tests and knows prior
failures: report a small prospective synthetic evaluation, not an independent blind
holdout. Freeze all case content, alternatives and witness outputs before inference.
If any response has been inspected, later changes require a new experiment rather
than amendments to this run.

Copy the exact selected prompt into private immutable preparation evidence. Its
hash and all five existing demonstrations remain unchanged. Do not add case IDs,
expected labels, answers, new examples or oracle alternatives to the prompt.
Vocabulary-only counts must satisfy input+1536 output+256 margin<=4096 for every
rendered request, including the probe. Any frozen packet overflow blocks live
admission; do not truncate, shorten the prompt or increase context to force a run.

**Rejected alternatives:** reuse the old eight cases (not fresh); tune the prompt
for new fixtures (invalidates the intended prospective check); call this a blind
holdout (unsupported independence).

### 2. Apply the frozen v4.2 comparison semantics prospectively

Use strict typed/extractive checking with explicit finite question alternatives
in the new contract and case-insensitive commitment labels. Reuse
`oracle_v4_1.evaluate(case_id, stages, new_contract)` for those semantics: its
parent checker accepts a supplied contract. Do not apply the historical v4.2
hardwired i06 exception to an unrelated ID; declare any contextual alternatives
for the appropriate new role before outputs.

Bind the v4.2 manifest lineage and the complete new predicate contract. Exact
source excerpts, identity, dates/times/timezone, recurrence, uncertainty, counts,
attribution and local acceptance remain strict. No global synonym replacement,
fuzzy matching, whole-answer gold injection, response repair or item salvage.

**Rejected alternatives:** changing old manifests; a new model judge; accepting
arbitrary paraphrases; growing alternatives after seeing candidate output.

### 3. Reuse execution through one explicit suite selection

Add a closed, source-bound choice of legacy-v4 versus fresh-v5 suite. The choice
must propagate into packet/revision verification, request building, ordered IDs,
contract/evaluator, Kotlin bridge, phase allowlist, reports and receipts. Selection
must not accept arbitrary module paths, callbacks, profiles or user packets.

The v5 directory owns its suite data, manifest, revision/context definitions and
thin entry point. Make only the non-frozen operational changes needed in the
existing runtime/campaign/lifecycle/supervisor path. Explicitly allow the v5 profile
in the Mac runtime without weakening any resource, context or ownership check.
Replace filename-prefix assumptions such as `i*.intent.json` with exact admitted
action membership. Preserve legacy-v4 defaults, limits and test behavior.

Bind the transitive source closure for the selected suite, including its tests,
registry/entry point, packet bridge, counter and shared runtime dependencies.
Retain accepted operational bytes/hashes before changes; source drift stops new
work. Old source-bound revisions are historical evidence, not reusable authority.

Use the existing native clock, launchd supervisor, owned compiler guardians,
durable cancellation/status and single cleanup owner. The supervisor must select
only the worker entry point for the bound suite. Do not duplicate scheduling or
use monkeypatches, historical-ID aliases or profile masquerading.

**Rejected alternatives:** a standalone live script (loses durable cleanup),
forking the supervisor (duplicates safety-critical code), broad plugin support
(not needed for two known suites).

### 4. Validate supplied packets through an isolated Kotlin bridge

Add `IntakeConformanceV5BridgeTest.kt`, leaving original bridge and strict wire,
binding and production semantic validators unchanged. Each private request binds
an allowlisted new case ID, canonical packet JSON/hash, raw response/hash and frozen
suite manifest. Kotlin verifies the selected packet against the frozen v5 corpus
before calling `IntakeExtractionV2.validate(raw, packet)`; a caller-supplied hash
alone does not authorize arbitrary text.

Preserve strict parsing, duplicate-key rejection, byte/count/path limits, 0700
directories, exclusive 0600 regular single-link files, no symlinks, private external
paths and sanitized failure messages. Packet mismatch fails before response
processing; result bindings must identify the same packet, response and suite.
Reuse bounded compiler ownership rather than a new process launcher.

Positive witnesses and every per-field omission/wrong-value mutation must cover
the actual Kotlin-to-oracle path appropriately. Include wrong/travel question
subjects, missing question details, cross-case leakage, stale corrections, wrong
commitment kinds, duplicate uncertainty fields and modified source excerpts.

### 5. Spend one immutable batch allowance, never retry the candidate

Record a new authority only after the separate apply instruction and all offline,
source/context/runtime gates. The user's approved direction is one evaluation,
not an optimization campaign. Limit it to one root, one batch, eight scored launches
and one non-scored cancellation probe, all within one 14,400-second native/UTC grant
with 240 seconds reserved for cleanup. Keep current 900-second generation/probe,
1800-second case, 120-second preflight, 600-second offline validation and 60-second
cleanup ceilings capped by the outer deadline.

Disable subsequent batches, revisions, correction, infrastructure retries and
automatic repair/replacement roots for v5. Do not use the v4 recovery coordinator's
three-slot restart authority. Durable request intents and launches consume their
allowance before action; uncertain claims are never refunded or replayed.

Observer loss does not cancel an authorized healthy supervisor. Read-only attach
never creates work. Worker/supervisor loss, unsafe state, source drift, resource
failure, timeout or uncertain cleanup yields a terminal failed/incomplete outcome,
not another launch. A completed per-case quality rejection is retained while the
remaining cases run, provided operational gates remain healthy. Complete quality
failure is terminal after the batch, not an infrastructure-retry reason.

Preserve Qwen3:8b/Ollama0.35.0 CPU-only, context4096/output1536, mmap/no-repack,
the 10-GiB VM, 8-GiB hard limit, 7.5-GiB full-current-memory stop, normal host pressure
and authoritative pod UID/cgroup/CRI and local process-group stop proofs. Final
cleanup must restore the exact original deployment spec, one Ready shared replica
and unloaded model. Expiry or inference approval cannot revoke cleanup authority.

### 6. Gate claims and preserve the denominator

The report always contains all eight ordered cases, retaining failed, incomplete
and unrun outcomes. All eight must pass unchanged local and frozen conformance
checks in this one batch, with valid execution/cleanup evidence, for a prospective
synthetic-suite pass. Do not combine previous passes or mix policy versions.

Retain private request/raw/packet/stage/predicate/report/receipt/event hashes,
latency/resources with honest measurement provenance, source snapshots and stop/
restoration proofs. Independently inspect final evidence before removing only
completed temporary launchd registrations. Logs and chat summaries contain no raw
prompt/response bodies. `independentHuman=false`; no human usefulness score.

A complete 8/8 result with sound safety/latency evidence permits recommending a
separately scoped integration pilot, not implementing it or opening production.
Any quality failure blocks that recommendation pending diagnosis and a future
separate scope. Incomplete or unsafe execution is not extraction evidence of a pass.
Report bottlenecks separately from conformance; no latency target is invented here.

### 7. Correct wording equivalence in a separate offline policy

The user explicitly authorizes a comparison-only correction after inspecting u03.
Its extracted facts and unknown disclosures are correct. The retained label differs
by the leading indefinite article, and its complete practice question is a contextual
paraphrase of rehearsal. Preserve the original prospective7/8 result and closed root.

Add isolated `benchmarks/intake-extraction-v5-1/`, bound to the frozen v5 manifest
and inherited checker lineage. Normalize comparison copies of commitment labels by
casefold and removing exactly one leading `a ` or `an `. Do not trim whitespace,
remove punctuation, repeatedly strip articles or alter internal words/excerpts.

Expand copied expected question alternatives, not response text. Only the complete
canonical date/start/end/timezone/recurrence carrier is eligible. Declare role sets
training_unknowns:training/practice and rehearsal_unknowns:rehearsal/practice; do
not merge the sets transitively. Derive expansion from frozen expected predicates,
never candidate labels or question positions. Require exactly one eligible role,
one unresolved commitment target, anchored source activity consistent with that
role, all four typed fields null and exactly their four unresolved disclosures.
If another expected activity could also mean practice, even when fully resolved,
do not add the new alias. Existing frozen alternatives remain unchanged. Unsupported
contexts receive no new alternatives; all original fact/count/identity/local gates
must still pass. This is bounded declared equivalence, not arbitrary semantic NLP.

Prove positive paraphrases and adversarial mutations before re-scoring the retained
complete report. Record both original and revised outcomes, raw/stage/packet/source
hashes and new policy manifest in a separate private report. No response or historical
report/receipt is edited. Any revised8/8 is explicitly post-hoc checker adjustment,
not demonstrated generation improvement, a fresh pass or independent semantic score.
No model rerun, runtime wiring change or production admission is needed or authorized.

### 8. Propose but do not implement an integration pilot

After correction verification, document a small separately approved pilot that
displays unconfirmed suggestions behind existing validation and explicit user
confirmation. Start with bundled synthetic inputs; invalid or ambiguous items remain
blocked rather than auto-corrected or committed. Ground the proposal in current app
boundaries without adding endpoints, writes or inference. The existing prospective
failure remains historical; corrected post-hoc evidence does not by itself satisfy
the original pilot-readiness gate or authorize deployment/personal-data use.

### 9. Implement the approved synthetic interaction harness

After the separately documented proposal, the user explicitly says "approved".
Deliver one runnable isolated loopback inspect/edit/resolve/stage/confirm journey,
not only component mocks. Keep backend launcher/module on the test classpath with
an opt-in JavaExec task; never call normal Application.module or construct providers,
history ingestion, schedulers or scoped execution services. Serve a separately built
pilot UI from the same loopback origin, using a fresh generated private store per
launch with no application-path override or existing-database reset.

Use the frozen handwritten v5 witnesses, not private acquired model responses.
Verify corpus manifest/hashes, bind exact synthetic source identities/revisions and
validate each witness through unchanged strict validation. Bootstrap only the known
source rows into the fresh test database; no structured items or execution receipts.
The fixture-only endpoint returns explicit handwritten-fixture provenance and draft,
never an execution UUID or forged published result. Reject unrelated APIs and
arbitrary source creation. Known-source corrections invalidate fixture availability.

Reuse existing authenticated read/confirmation dispatchers with origin/CSRF checks,
revision conflicts and atomic/idempotent confirmation. Use a dedicated pilot cookie
name so this loopback server cannot overwrite normal or development-review sessions;
only a narrow explicit cookie allowlist addition is needed, with defaults unchanged.
The launcher accepts a dedicated authentication verifier, not normal app credentials,
uses a fixed separate loopback port and refuses an occupied port without takeover.

Extract shared source-bound draft parsing without weakening the production response
envelope. Pass an optional suggestion loader through the existing intake component;
normal defaults retain their current endpoint and execution identity requirement.
Only the separate pilot entry imports the fixture adapter. Suppress new arbitrary
notes in that entry, display a synthetic/no-model/disposable-store banner and reuse
the manual editor and explicit confirmation flow. Fix only concrete staging defects
revealed by required invalid-time/recurrence checks; do not replace server validation.
Unknown acknowledgment is a UI review boundary, not a new server attestation claim.

Test backend safety against the actual disposable SQLite store and UI transitions
through focused tests, then exercise the real browser and loopback API together.
Primary owns local startup/shutdown and disposes only generated storage. This is
not app deployment, model execution or qualification; normal stores and profile
admission remain untouched. No raw candidate responses are bundled or published.

## Risks / Trade-offs

- **Partial suite wiring validates against old packets:** bind selected suite and
  packet hashes across every stage; test cross-suite substitution before inference.
- **Old three-batch authority leaks into v5:** freeze one-batch/eight-plus-one caps
  in authority and admission; test duplicate/ninth/second-batch refusal explicitly.
- **Context headroom disappears:** recount all nine new rendered requests; stop
  before inference on overflow, without silently reducing the frozen candidate.
- **Small author-designed test overstates reliability:** preserve prospective and
  non-independent labels; do not claim distributional coverage or production readiness.
- **New safety scaffolding grows without usable behavior:** first stop at the
  actual offline validation plus fake durable-batch checkpoint; no new scheduler,
  generic plugins or recovery features. Explain any further prerequisite before work.
- **Host sleep/reboot or cleanup-supervisor death:** retain finite fencing and
  honest incomplete outcomes; do not promise guaranteed physical availability.

## Migration Plan

No application rollout or data migration. Implement the offline/fake vertical slice,
run source/frozen/legacy regressions and actual packet-bridge checks, then freeze
preparation and execute only if gates pass. Record actual results and leave all
production gates closed. On a blocked admission or implementation defect, preserve
evidence and stop before model execution; do not reopen old roots or deploy a fix.

Planning uses one architect child (`ses_ea1733315ffeuTIW2SZE89Vj5B`) of the two-child
slice budget. That is the sole independent architecture review; one child remains
for bounded implementation/tests during apply. Commands and restarts do not reset
this unfinished slice budget. Primary independently inspected the identified seams.
