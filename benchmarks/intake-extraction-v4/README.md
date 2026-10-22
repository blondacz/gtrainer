# Automated intake conformance v4

This isolated synthetic benchmark evaluates deterministic extractive conformance,
not human usefulness or production reliability. The historical v1/v2/v3 protocols,
validators, responses and failed decisions are unchanged. The original eight
packets and v3 provider schema are retained byte-for-byte in `inputs-v4.json`.

## Offline acceptance contract

`manifest-v4.json` freezes the protocol, inputs, oracle, handwritten test witnesses,
oracle implementation and its adversarial tests before inference. The freeze
verifies the original three manifests and the unchanged local validation authority.
Freezing is exclusive: it cannot overwrite an existing or interrupted freeze.

Every case must first pass the actual Kotlin `IntakeExtractionV2.validate` wire,
unique exact-excerpt binding and production semantic stages. Only then does
`oracle_v4.py` evaluate the accepted assembled draft. All eight cases must pass in
one revision; no selection of best cases across revisions is permitted.

The exact finite alternatives are published in `oracle-v4.json`, generated from
`rules_v4.py`. Each suggestion requires every declared typed field and extracted
content value, including attribution, negation and uncertainty. Extra, missing or
duplicate facts fail. Excerpt quotation does not replace required extracted content
or a structured commitment. In particular:

- i01 retains the reported clinician/ACL guidance and its explicit observation
  date. Start is unknown/unresolved; end is open and not unresolved.
- i02 retains onset, improvement and the absence of reported recovery or clearance.
  Improvement is not an applicability end.
- i03 retains both dated, conflicting attributed reports without choosing advice.
- i04 and i05 require typed meeting and recurring-duty commitments, respectively,
  with all supplied dates, times, timezone and recurrence values.
- i06 separates resolved one-off travel from tentative training with genuinely
  unknown dates, times, timezone and recurrence.
- i07 binds revision 2, includes the non-BMP correction prefix in its exact excerpt,
  extracts the corrected meeting and ignores the embedded confirmation instruction.
- i08 preserves recalled sleep, both readings, different devices, unknown dates and
  methods, and unmeasured readiness without physiological conclusions.

Clarification is restricted to the predeclared finite templates for genuinely
missing roles. Questions about supplied information fail. Source content permits
only the declared exact clauses, with or without a final period; labels permit only
the published aliases. Suggestion order and unresolved-field order are immaterial,
but unresolved fields must remain unique. Minute timestamps and their explicit
zero-second spelling are equivalent comparisons. No recorded response is rewritten.
Unlisted paraphrases fail: this is deliberately narrow extractive conformance, not
general natural-language equivalence. No per-case oracle or witness may enter a
live prompt; witnesses are handwritten tooling tests, never provider evidence.

## Private offline reports

`replay_v4.py` requires an external 0700 directory and exclusive, single-link 0600
files. `run.json` binds the frozen manifest, exact tooling, model identity, settings,
case IDs and bounds. `approval.json` must authorize that exact run for synthetic
offline validation before any response read or validator callback. Its validity
window is at most 24 hours. Other versions and source drift fail closed.

`responses.json` contains an ordered prefix of the eight cases with one outcome per
case and no retries. Timeout/incomplete output fences later cases. Every report
retains all eight outcomes, including unrun cases; raw bytes and hashes; local
stages and hashes; predicate identities and oracle-result hashes; and nullable
resource values with explicit fixture or imported-unverified provenance. Missing
measurements stay null. Imported measurements are not promoted to observations.

The Kotlin test-source bridge uses offline Gradle only. Its reports and bounded
compiler log remain private; response text is never printed. A validation lock
prevents replay after a failed or completed callback. Changed source, input or
approval during validation prevents report publication.

Reports state `evaluationMethod=deterministic_extractive`, `independentHuman=false`,
`humanAssessment=null`, `developmentSetTuning=true`,
`unseenInputReliabilityProven=false` and `productionAdmission=closed`. There are no
generated human scores. An offline suite pass proves the tooling only. A future
live campaign additionally requires exact request/context bindings, bounded
campaign authority and verified owned cleanup; none is implemented by this replay.

## Focused checks

```sh
python3 benchmarks/intake-extraction-v4/experiment_v4.py
python3 -m unittest discover -s benchmarks/intake-extraction-v4 -p 'test_*.py'
INTAKE_V4_KOTLIN=1 python3 -m unittest discover \
  -s benchmarks/intake-extraction-v4 -p 'test_replay_v4.py'
```

Actual integration exercises all eight positive witnesses and separate wire,
binding, semantic and oracle-only failures, plus empty/incomplete/unrun outcomes.
`INTAKE_V4_RETAIN_INTEGRATION` may name an existing private evidence directory to
retain integration reports; otherwise their temporary directories are removed.
These checks do not invoke a model, authorize runtime changes or qualify production.

## Accepted offline checkpoint — 2026-10-19

All 23 Python checks passed, including two actual Kotlin integrations and the
bridge's three Kotlin tests per invocation. All eight positive witnesses passed
strict validation and the fixed oracle. The mixed negative integration retained
semantic, binding and wire rejections, a locally accepted oracle failure, a passing
case, and empty/incomplete/unrun cases without dropping the denominator.

The oracle tests exercise 234 individual field omissions/wrong values, every
predeclared field alternative, reordered suggestions, duplicate facts and unsafe
content. Replay checks cover authorization before response reads/callbacks, all
frozen-file drift, refreeze refusal, private modes/links/exclusive locks, bounded
compiler time/output and source drift after callbacks. A sentinel was absent from
private compiler/test reports and repository test reports. Primary inspection
verified retained file modes, report hashes and null measurements; the historical
v3 tooling snapshot and original freezes remain unchanged.

Retained evidence: `intake-v4-offline-accepted-zz5u7txx` under the approved external
evidence root, including `primary-inspection.json`. An earlier successful integration
checkpoint (`intake-v4-offline-integrations-ohxvjdjh`) is preserved separately.
Strict OpenSpec and whitespace checks passed. No model call or live runtime change
occurred. The next task is fresh exact-bound prompt/context preparation, followed by
runtime/campaign fake checks before any live admission.

## Exact-bound revision preparation

`revision_v4.py` builds only the nine exact mmap-enabled requests (eight cases and
the existing non-scored stop probe), preserving the original packet and schema
bytes. It binds each request, rendered prompt, packet, oracle, manifest, model,
template, tokenizer, runtime identity, relevant tooling and predecessor revision.
Finite oracle answers and clarification lists never enter the live instructions.
The first generic instructions reuse only the unrelated stairs/visit example and
add completeness, uncertainty, typed commitment and recurrence rules.

`count_v4.py` is an offline Linux x86-64 vocabulary-only counter using the reviewed
80-byte ABI and cached pinned library. It loads the vocabulary with `vocab_only` and
`no_alloc`, never creates an inference context or calls generation, and does not
download dependencies or start a server. Exact runtime-matched rendering includes
the audited extra system newline, non-thinking suffix and tokenizer flags. Both the
counter and revision validator hash identical ordered UTF-8 JSON input bytes.

The first successful count evidence is retained privately in
`intake-v4-r1-context-accepted-xyvflf4d`. Counts are **1260, 1264, 1267, 1258, 1277,
1278, 1278, 1256**, with **47** for the unchanged probe. The maximum plus 1536 output
and 256 margin is **3070 <= 4096**. These are new offline counts, not provider
`prompt_eval_count` observations. The library's skip-tensors log, unloaded shared
server before/after, unchanged pod/container and normal pressure were inspected.

An earlier counter run in `intake-v4-r1-context-f09i_ao9` was refused before revision
freeze: its standalone serializer sorted keys while the protocol hashes ordered
JSON. That original output/source/log is preserved, not repaired. Serialization was
corrected, a byte-equality regression added, and the counter rerun in a fresh
directory. Neither count run invoked inference or changed runtime configuration.

Tooling changes invalidate an old prepared revision. Before use, freeze a fresh
immutable binding; never silently refresh a stored revision. Identical prompt and
request bytes may retain their already verified vocabulary counts. Changed prompts
require new exact counts. Campaign accounting and live admission remain separate.

## Explicit runtime seam

`runtime_v4.py` binds an immutable exact batch run and its synthetic-purpose
authorization to a prepared revision. It requires an external source-bound campaign
guard before operational admission; there is no standalone live CLI or implicit
campaign. Every scored request and the exact stop probe are checked again at chat
transport dispatch, including their full wire hashes and explicit mmap option.
Expiry, wrong profiles, changed prompts or bindings, missing probe verification and
failed campaign guards refuse before new generation. Final cancellation fences
reuse even if stopping fails; invalid or expired inference authority cannot prevent
owned cleanup.

The non-frozen `observed_mmap_runtime.py` shares only the existing observed
mmap/no-repack checks, bounded loader diagnostics and inherited Mac operations.
V3 retains its own request/context wrapper and historical policy version. Its
acquisition source binding now includes the extracted loader. The shared Mac
adapter admits v4 only with an explicit v4 hooked profile; default hooks remain
v3-only and hook-free operation remains v1-only. No frozen validation, provider
schema, packet, old request bytes or authoritative stop implementation changed.

Campaign deadline accounting, the 240-second reserve and the all-batch state fence
belong to the subsequent campaign controller, not this runtime extraction. No live
batch may start before those end-to-end fake checks and exact campaign authority
exist. Requests must stop before the reserved cleanup window. Cleanup retains its
existing separate bounded authority; deadline breaches must be recorded and fenced,
never concealed by skipping verification to claim clock compliance.

### Accepted runtime checkpoint — 2026-10-19

All 40 v4 checks passed, including three actual Kotlin integrations. One integration
composes fake provider transport through the v4 request guards and unchanged Mac
generation logic, then validates all eight exact fixture responses through Kotlin
and the frozen oracle. Private evidence and inspection are retained in
`intake-v4-runtime-offline-checks-652rv3er`.

All 47 v3 checks passed (five actual integrations skipped), as did all 67 shared
checks. These retain exact v1/v3 wire behavior, ownership/identity, blocked-stdin
cleanup, full-current-memory and pressure stops, verified termination and restoration.
Primary inspection verified that every extracted loader method has the same AST
apart from the explicit context-validator and digest-module seams. Before/after
source snapshots are retained in `intake-v4-loader-extraction-1dchvhwt`; all other
original v3 tooling remains unchanged. Old runs and evidence are not rewritten or
silently rebound to the new tooling.

Current exact prepared binding: `intake-v4-r1-runtime-bound-hh04ink8`. No live v4
generation or runtime configuration change occurred. The next usable behavior is
the bounded campaign ledger/controller and adversarial fake campaign execution.

## Campaign controller (offline acceptance complete)

`campaign_v4.py` implements a protected local ledger, not a scheduler. A campaign
has one immutable monotonic start/deadline and boot identity, three batch claims,
24 scored requests and three existing stop probes. The work cutoff reserves 240
seconds. Every later prompt preparation and validation consumes the same clock.
The explicit CLI never starts another campaign or performs a recovery restart.

A stable `fcntl` lock serializes admissions through cleanup and receipt publication.
Exclusive private files and directory fsync persist claims and action intents before
callbacks. Claims permanently spend batch allowance. The current case/probe has one
exact request hash and one exclusive launch receipt; wrong-case or duplicate launches
refuse even when their bodies belong to the same revision. All eight cases remain
in every batch report. Only a completed, safely stopped quality failure permits a
new generic prompt, different from every earlier prompt and linked to its immediate
predecessor. A UUID change alone cannot authorize another attempt.

Missing active claims, receipt gaps, partial files, changed source/authority, changed
boot or regressed clocks fence execution. A claim without a receipt cannot be
resumed. A completed safe quality failure may continue in another process on the
same host clock, without resetting time or counters. The ledger assumes protected
local storage; it does not claim hostile rollback resistance if every consistent
copy of a ledger tail is deliberately deleted.

Live campaigns reject injected clocks, alternate runtime factories and alternate
validators. Fake-only seams exist solely for adversarial tests. Actual offline
Kotlin validation receives at most the remaining case/work budget, never an
unconditional fresh 600 seconds. The source-bound campaign guard runs before and
after callbacks. Owned cleanup does not depend on that guard, campaign clock,
continued inference approval or event logging. Its separate trusted clock retains
one bounded cleanup attempt if campaign state fails. Unknown final time is null,
not invented. Any breach, clock/state failure or unverified cleanup is incomplete
and prohibits continuation, even if all eight responses appear to pass.

The initial eight fake campaign tests passed. One independent review found three
concrete defects: live test-seam injection, deletion of an active claim not being
noticed, and a campaign-clock exception being able to skip cleanup. Primary
inspection confirmed each defect and fixed it. Four focused regressions passed for
the fixes, including claim deletion during final cleanup and clock failure after
launch. The expanded full suite passed **59 checks**, including three actual Kotlin
integrations. Final narrow hardening then retained full probe evidence, rejected an
attempted request falsely labeled unrun, and clarified mixed resource provenance.
Three focused checks passed afterward, including the new regression, failure/new
revision/success and the full three-batch budget. This is 60 distinct checks covered,
not a claim that all 60 were rerun together after the last narrow change.

Primary inspection verified private modes, raw/stage/oracle hashes and null resource
measurements in all three retained integration reports. Evidence and current source
hashes are in `intake-v4-campaign-offline-checks-pdeikku4`, including
`primary-campaign-tooling-inspection.json`. Frozen lineage, strict OpenSpec and
whitespace pass. No live campaign has started. Old prepared bindings must be
explicitly rebound to the new source before use; unchanged prompt/request bytes
retain their verified token counts.

## Interrupted first campaign and explicitly authorized replacement

The first live campaign, `intake-v4-campaign-approved-nholerl7`, started at
2026-10-19T20:40:33.108035Z. The OpenCode server restarted while its third case was
in progress. Two cases had durable results, neither passing acceptance; the third
has no durable case-result receipt, and the remaining five cases were unrun.
The original batch claim remains unfinished. No original report or receipt was
fabricated, and the claim cannot be resumed.

Primary inspection found the owned model pod still running and the original shared
deployment paused. The existing ledger lock was acquired, exact pod UID/container/
run identity verified, and unchanged authoritative owned cleanup executed once.
`interruption-cleanup-fkm2qe1z` retains empty-cgroup/absent-CRI proof and successful
restoration. Independent read-only checks confirmed the original deployment spec
exactly restored, one Ready shared replica, no loaded model and normal pressure.
`interruption-assessment.json` preserves all eight case states separately, and
`campaign/terminal.json` fences that campaign as **incomplete**. No retry followed.

The user then requested “resume campaign”. After being told that the unfinished
claim could not safely resume, the user explicitly selected **“Start fresh campaign”**
in response to authorization for a new prompt revision, unchanged oracle/guards,
and a new four-hour budget capped at three batches, 24 scored requests and three
stop probes. This is separate explicit authority, not an automatic budget reset.
The interrupted campaign and all its evidence remain unchanged.

The fresh campaign is `intake-v4-campaign02-approved-x0bcf_01`. Its first generic
prompt removes example/metadata extraction ambiguity, separates observation dates
from applicability, prohibits fabricated events and retains tentative-event
uncertainty. No case gold outputs or oracle question list enters the prompt. Fresh
vocabulary-only counts are **1004/1008/1011/1002/1021/1022/1022/1000**, probe **47**;
maximum1022 +1536 output +256 margin =**2814 <=4096**. Skip-tensors evidence, unchanged
source pins, original packet/schema/oracle/model identities, unloaded model and
normal pressure were checked before admission.

Its clock record started **2026-10-19T23:19:56.156695Z**, with nominal expiry
**2026-10-20T03:19:56.156695Z**. The outcome below supersedes the running checkpoint;
no new budget or campaign is authorized automatically.

## Final bounded outcome — incomplete, not qualification

Campaign02 batch01 completed eight sequential responses, its active stop probe and
verified owned cleanup. The batch was a **quality failure: 0/8 conformance passes**.
Unchanged strict local validation accepted only i03, which then failed suggestion
count and clarification predicates. i01/i02/i05/i07 had semantic rejections;
i04/i06/i08 had wire rejections. Original responses were not repaired or salvaged.

A later process could not safely continue the campaign. The host executable is
Python **3.9.6**: `time.monotonic()` returned a fresh near-zero origin in a new
process, while the first batch's recorded finish was **2154.766169089**. Boot
identity was unchanged. The ledger correctly refused that non-comparable clock
state rather than resetting time, reconstructing monotonic authority from UTC or
granting another batch. This is an infrastructure/clock stop, so the overall
campaign is **incomplete**, not a completed three-revision experiment. Fake-clock
cross-process tests did not establish native-clock comparability on this host.

The original complete batch report and receipt retain their actual quality-failure
classification. A separate immutable `campaign/terminal.json` records the incomplete
campaign; `clock-continuation-refusal.json` retains executable/version, stored/current
clock identities and timestamps. There was no second batch or diagnostic inference.
Neither this nor the earlier interrupted campaign may be resumed or replaced
automatically.

Primary final inspection verified all eight raw/stage/oracle hashes, the event hash,
source pins and frozen lineage. Both owned pod stops have empty-cgroup and absent
running-CRI proof. All **480** retained host-pressure samples were normal; maximum
observed cgroup current was **797.15 MiB**. This is not model RSS, total model memory
or a future-headroom claim; unknown RSS/cost remain null. Independent final reads
confirmed the original deployment spec exactly restored, one Ready shared replica
and no loaded model. Evidence: `primary-final-inspection.json` and
`batch-01-independent-restoration.json` in the campaign02 root.

All seven change tasks are recorded complete with this actual incomplete outcome,
not a successful model experiment. Human assessment remains absent, the known-eight
suite is development data and production/v1 admission remains closed. Any future
campaign needs separately authorized scope, a natively comparable clock with a
real cross-process check, and further extraction improvements. No clock guard was
weakened, source patched, budget reset, spec synced/archived or deployment performed.

## Explicit follow-up authorization — 2026-10-20

After the prior final checkpoint, the user explicitly said **"ok, do it all"** to
the proposed native clock fix, real cross-process tests, extraction improvements
and one fresh bounded campaign. Tasks4.1–4.4 track this additional scope. Both old
terminal decisions remain unchanged; neither old clock nor claim is resumed.

The native fix uses existing stdlib
`time.clock_gettime_ns(time.CLOCK_MONOTONIC)`, with identity version
`mac-os-wide-monotonic-v1`, API name and boot UUID. It has no fallback or dependency
installation. Exactly three controller boundaries convert shared remaining time
conservatively into the unchanged runtime-local deadline: preflight, probe and
generation. Cleanup keeps its separate trusted local clock. Legacy identities are
rejected; unexpected suspend/clock overruns remain incomplete rather than gaining
time. No hard-real-time termination claim is made.

All **66** offline checks passed, including three actual Kotlin integrations,
native fresh parent/child bracketing and a real two-process synthetic quality
failure/new-revision/success using default native clocks. Scope bytes/start/end
remained unchanged, revision work consumed time and cumulative claims remained
2 batches/16 scored launches/2 probes. Synthetic outputs were not model evidence.
Native proof and primary source/mode/clock inspection are retained in
`intake-v4-native-clock-full-checks-e5tdkq3u`. Before/after source and exact user
authority are in `intake-v4-native-clock-apply-approved-7lq0yp8g`.

Read-only diagnosis of the retained old responses identified duplicate unresolved
fields, mode/null/disclosure mismatches and scheduled activities emitted as context.
The new generic candidate prioritizes activity type, explicit consistency tables
and canonical unique-field ordering. Four unrelated handwritten demonstrations
cover a dentist visit, stair guidance, volunteer recurrence and uncertain choir
practice. All four passed unchanged actual Kotlin wire/binding/semantic validation;
duplicate-field and unknown-mode mutations were rejected. These demonstrations are
not suite gold outputs or a response repair path, and no oracle question list was
copied. The test-only demonstration checker is included in new source bindings.

Fresh cached vocabulary-only input counts are
**1776/1780/1783/1774/1793/1794/1794/1772**, probe **47**. Maximum1794 +1536 output
+256 margin =**3586 <=4096**; no truncation or context increase. Evidence is under
the follow-up root's `candidate-context-01/` and `demonstration-validation/`.
Final **17** focused native/revision/Kotlin checks passed after the additional
demonstration-checker source binding, including three actual Kotlin integrations.
Primary inspected retained native-clock, demo/input/raw/stage/context and private
mode evidence in `intake-v4-followup-final-checks-8b7wyo7k`. These are tooling/example
checks, not a claim of improved model quality. A fresh campaign may now pass its
remaining exact-source/runtime admission gates under the explicit user authority.

### Active native-clock campaign

All remaining exact source/native/context/model/identity/pressure gates passed.
The single freshly authorized campaign is
`intake-v4-campaign03-native-approved-q99k1s45`, with source snapshots and explicit
authority linked to the user's “ok, do it all” instruction. Its versioned OS-wide
clock started **2026-10-20T11:51:49.756346Z**, with approval expiry
**2026-10-20T15:51:49.756346Z**. The unchanged limits are three batches, 24 scored
requests, three existing stop probes, one shared four-hour budget and 240-second
reserve. Neither older incomplete campaign is resumed or rewritten, and no further
campaign is automatic.

Batch01 completed all eight responses and safely verified probe/owned cleanup.
**6/8 passed strict local validation, but 0/8 passed the frozen oracle.** i01/i02/i08
failed finite clarification predicates; i04/i05 failed label predicates; i07 failed
label/excerpt predicates; i03/i06 had semantic rejections. Independent final reads
confirmed exact original service restoration and no loaded model. A new process
successfully checked the same native ledger/deadline before revision preparation;
the earlier cross-process clock problem did not recur.

Batch02 is active in the **same** campaign. Generic changes emphasize short definite
event nouns, corrected factual excerpt boundaries, separate conflicting reports and
tentative activities remaining commitments. Five unrelated demonstrations passed
unchanged actual Kotlin validation. An initial offline candidate exceeded the
context bound (**4154** including output/margin) and was never admitted. Its evidence
is preserved. A compact generic version was freshly counted at maximum2212 input
+1536 output +256 margin =**4004 <=4096**. This edits prompt instructions, not model
responses; no truncation, source/oracle change, per-case retry or budget reset.
Private `revision-02-preparation/` records both candidates and a harmless prompt-
construction error before any changed file/admission; all preparation consumed the
original clock. The next revision links the exact previous revision hash. No
human scoring or new quality approval was requested.

Batch02 completed safely with **8/8 strict local accepts and 3/8 oracle passes**
(i04/i05/i07). All remaining cases failed finite clarification predicates; i03 also
failed a category predicate and i06 an excerpt-boundary predicate. Independent
restoration and native-ledger continuation checks passed. No cases are combined
with earlier revisions to produce a pass.

The final allowed batch03 is active under the same unchanged campaign deadline.
Its generic revision teaches both permission/prohibition reports as restriction,
separate factual excerpts for mixed activities, and conditional unknown-end/event-
timing clarification. Five unrelated demonstrations passed unchanged actual Kotlin
validation. Fresh exact maximum input2286 +1536 output +256 margin =**4078 <=4096**;
all previous provider prompt counts matched their offline counts exactly. Source,
oracle, packets, schema, model and safeguards remain unchanged. Remaining time at
the final admission was5884.3 seconds, not a new budget. After this batch no further
batch or automatic campaign is authorized, regardless of its result.

### Final native-campaign checkpoint — incomplete after controller loss

During the final batch, seven cases gained durable results: six strict local
accepts and one oracle pass. Case i08 has no durable case result. A later
user-requested status check found the original campaign deadline already expired,
no controller process, an available ledger lock and the exact owned pod still
Running while the shared deployment remained paused. The cause of controller
disappearance is unknown; do not relabel this as a proven server restart or a
clock-domain failure. Native-clock continuation had worked between the earlier
complete batches. **The deadline/lifecycle breach is real and not a successful
timing-enforcement result.**

Primary acquired the existing ledger lock, verified the exact run label/pod UID/
container against retained ownership evidence and performed one already-authorized
60-second cleanup through unchanged authoritative stopping. It verified empty
cgroups and absent running CRI identity, then restored the original service.
Independent reads confirmed the exact original deployment spec, one Ready shared
replica, no loaded model and normal pressure. Evidence is retained in
`final-controller-loss-cleanup-ttv5bqaz/`.

The campaign is fenced **incomplete**. All three claims, 24 declared scored launches
and three probes are consumed; there are 23 durable case results and one interrupted
case. No original batch03 receipt/report was fabricated. The separate immutable
`final-controller-loss-assessment.json` retains all eight positions for every
revision without mixing best cases. `primary-final-controller-loss-verification.json`
checks all 27 declared request hashes, available raw/stage/oracle records, source
pins, frozen lineage, private modes and log privacy.

The best complete revision achieved **8/8 strict local validation and 3/8 frozen
conformance**, not a satisfactory all-eight pass. All 11 change tasks are recorded
complete with this actual incomplete outcome, not qualification. No further
inference, campaign, source patch, automatic restart or budget reset follows.
Independent controller-lifecycle hardening is required before any newly authorized
campaign; the timer fix alone cannot enforce shutdown after its controller dies.

### Authorized lifecycle follow-up — safety gate in progress

The user's subsequent "do it" accepts the proposed controller-loss investigation,
independent watchdog and tests, generic extraction improvements and one fresh
bounded campaign only after safety acceptance. Historical claims remain fenced.
The new task5 records this authority separately; no new model requests have been
made at this checkpoint.

Private forensic evidence is in
`intake-v4-watchdog-followup-approved-eqyblond`. OpenCode logged target-project
`location services evicted` at15:15:57.795Z, within one second of the last durable
controller event. The same server run continues afterward. This supports location
teardown as the relevant lifecycle boundary, not a proven full server restart.
The exact terminating signal/caller remains unproven. No service restart, internal
session database access or historical evidence rewrite was used to investigate.

`supervisor_v4.py` registers a unique temporary launchd job, not a persistent agent.
It starts one dedicated worker group only after an authenticated private caller
connection and positive source/clock readiness. Caller loss, worker loss, action
expiry, work cutoff or a suspend/clock gap fence work. Final cancellation belongs
only to the supervisor; the worker delegates normal cancellation. Abnormal takeover
kills/reaps the worker group before acquiring the mutation lock. There is no
KeepAlive, automatic restart or inference retry. Completed temporary jobs are
removed; private evidence remains.

`lifecycle_v4.py` journals pause and both approved pod creations before mutation,
including the exact original deployment spec and pending create token. It reuses
unchanged authoritative Mac UID/cgroup/CRI stopping and refuses unresolved remote
outcomes or unrelated deployment drift. It never treats preflight's shared-pod
reference as owned execution. Atomic no-overwrite macOS publication prevents readers
from seeing partial records. Logging failures cannot skip termination; uncertain
evidence prevents restoration/success claims. One final cleanup budget is retained.

The first independent review found partial-record publication, fallible reporting
before termination and late phase transitions extending expired leases. All three
have focused corrections and regression tests; review agreement alone is not proof.
Twenty-three lifecycle checks now pass, including six actual temporary launchd jobs
using synthetic processes only. Shared runtime regressions pass33/33. The initial
full v4 check hit its120-second tool limit and remains recorded as incomplete; a
longer complete check is pending. **Live admission remains closed at this checkpoint.**

The longer post-review suite subsequently completed89 checks successfully, with
three opt-in Kotlin integrations skipped. Primary then proved an additional native
campaign-clock fault could escape before supervisor quiescence. The correction
retains a separate trusted process-local cleanup deadline and fixed wall cap;
campaign-clock failure permits only bounded owned stopping, not inference or
uncertain restoration. It never renews an existing cleanup budget. Twenty-five
focused lifecycle checks pass, including real child termination under that fault.
The final complete suite with all three actual Kotlin integrations enabled is
running; task5.1 and live admission remain gated until its result is inspected.

Final safety acceptance: **91/91 v4 checks passed in588.316s, no skips**, including
all three actual Kotlin integrations. Primary verified unchanged final source pins,
original shared-runtime bytes, historical terminal, frozen lineage, private modes/
single links and12 current-source synthetic launchd jobs completed and removed.
`primary-lifecycle-final-acceptance.json` retains this inspection. Task5.1 is complete;
task5.2 still requires a fresh revision, demonstration/context and runtime admission
gates before the separately authorized campaign. No model calls established this
safety acceptance or any extraction-quality improvement.

### Fresh supervised campaign — first batch running

After safety acceptance, task5.2 prepared changed generic missing-role question
forms, restriction classification for both permission/prohibition, and separate
mixed-activity excerpts. It retained the best complete revision's type/mode/
recurrence rules and used only five unrelated fictional demonstrations. All five
passed actual unchanged Kotlin validation; duplicate-field and mode/disclosure
negative mutations were rejected. Every current packet's full intake content is
absent from the instructions. No per-case gold records or model responses were
inserted. Fresh cached vocabulary-only counts admitted maximum2270 input tokens;
2270+1536 output+256 margin=4062<=4096. No inference established these checks.

The separately authorized fresh campaign is
`intake-v4-supervised-campaign-approved-jh8o6kko`, hash
`561cf61fb9a161764b56c16d3cf52e6f04a91ac313e5b215ef5fd0985156c5cf`.
Its single clock grant begins2026-10-20T17:29:09.333265Z and expires
2026-10-20T21:29:09.333265Z, with the unchanged240-second cleanup reserve.
The one fresh campaign allowance is consumed; no automatic replacement follows.
The first supervised batch is running. All subsequent revision work, counting,
validation and cleanup consume this same grant, never a new four hours per batch.
All original model/schema/oracle/packet/resource/ownership/pressure limits remain.
There is no quality outcome yet and no human, generalization or production claim.

### Supervised campaign final checkpoint — safely stopped incomplete

A user-requested status check found the campaign terminal **incomplete** with
`supervisor_caller_lost`. Cases i01–i05 each passed strict local validation and
frozen conformance. Case i06 was interrupted; i07/i08 were unrun. This is five
passing durable cases, not an eight-case suite pass or a general quality score.
The exact reason the managed caller connection disappeared remains unproven.

The independent supervisor killed/reaped its worker, stopped the owned pod with
UID/cgroup-empty/CRI-absence proof and performed exactly one verified cleanup
within the original deadline. It restored the exact original deployment spec,
one Ready shared replica and an unloaded model. Primary independently repeated
identity/resource/pressure/restoration reads and verified seven declared request
hashes (six scored launches plus probe), all five available raw/stage/oracle
records, frozen/source lineage and private log/file controls. The completed
temporary launchd registration was removed only after the durable outcome.

`final-supervised-assessment.json`, `independent-supervised-restoration.json` and
`primary-final-supervised-verification.json` retain the actual outcome and all
eight positions. The original missing batch report/receipt remain missing and
the unfinished claim remains fenced. The one fresh campaign allowance is consumed;
there is no automatic continuation, replacement or additional inference. Task5.2
is complete with this incomplete outcome. The backstop prevented stranded owned
execution, not caller-lifecycle interruption. Production admission remains closed.

### Availability correction — durable execution and bounded repair reruns

The user explicitly requested automatic restarts/reruns after fixes and said
"ok, ffix it re-run". For new execution, observer disconnect no longer cancels
preauthorized work. Durable start/status/result/cancel controls replace socket
lifetime ownership. Worker/phase/source/pressure/clock/approval limits still apply.

`durable_tests_v4.py` runs only fixed unittest selections under temporary launchd
ownership, three attempts and one original one-hour grant. Assertion/compiler
failures require changed source and passed-check repair evidence; launcher failures
can retry automatically. Unknown process loss does not get blind recovery. Source
proof includes discovered tests and imported/build dependencies. `--watch` reports
repair need immediately, not only after the deadline. Fresh executors inherit
remaining attempt numbers and the same original start/cutoff/expiry after verified
local cleanup; failed original outcomes are never replaced.

`recovery_v4.py` implements the separately gated model recovery budget: three full
batch reservations,24 scored requests/three probes and one4h/240s-reserve grant.
Reservations bind an exclusive campaign path/UUID consumer. Complete quality reports
veto infrastructure retries even when later receipt publication fails. A stable
private-plist bootstrap spends coordinator restart authority before importing
checkout code. Compiler guardians and nested synthetic jobs retain cleanup ownership
after worker death. Zombie-only group quiescence is proved, never inferred from
EPERM alone or described as literal absence. Model pod stop proofs are unchanged.

The initial detached full run completed110 tests in2729.340s with one offline
cross-process120s timeout, then expired while waiting for repair. User authorized
600s for that offline subprocess; its focused detached rerun passed. This does not
change inference budgets. Later focused runs exposed macOS zombie-group signaling
and an over-strict five-second scheduler-delay abort. Corrections preserve genuine
clock divergence/regression and every fixed deadline; delayed scheduling alone does
not revoke otherwise valid work.

The combined checks then passed **57/57 in138.475s**, cleanup verified, using only
the third remaining attempt and the original unchanged test grant. Both earlier
failures are retained. Evidence:
`intake-v4-reliable-followup-approved-6w0vh0lp/combined-recovery-final-acceptance.json`.
Actual Kotlin compiler-ownership checks and the final full suite are still required
before the newly authorized model rerun. No model requests have been made under
this availability instruction at this checkpoint.

Final availability acceptance: **123/123 detached full-suite checks in1054.819s,
no skips**, including all three actual Kotlin integrations; shared regressions33/33.
Compiler/test guardian stop proofs, final source fingerprints, original shared
runtime/loader bytes, frozen lineage and private artifact controls were inspected.
`primary-reliable-execution-acceptance.json` records the accepted tooling, not a
model-quality claim. Tasks6.1/6.2 are complete.

The user-authorized model rerun is now active under the durable recovery root
`intake-v4-reliable-followup-approved-6w0vh0lp/model-recovery`, authority hash
`90fcf90e3a7bf5603cdad466e77c44b715c5cf91c8e15b1af3d58550aa4599c7`.
Its single grant begins2026-10-20T22:38:16.508006Z and expires
2026-10-21T02:38:16.508006Z. It retains at most three full slots/24 scored requests/
three probes and the240-second reserve across all eligible recovery and repair.
The first reservation's exclusive campaign consumer, actual worker and independent
launchd readiness/source binding are verified. Fresh cached vocabulary-only counts
are2270+1536+256=4062<=4096; the generic instructions are unchanged from the previous
five-passing partial run. Exact original Ready/unloaded deployment, identity and
normal pressure were rechecked before launch. No quality outcome is asserted yet.
Chat/caller loss does not cancel this execution or reset any budget. Historical
interrupted claims remain fenced and production admission remains closed.

### Durable rerun final outcome — completed normally, six conformance passes

**All eight cases completed without interruption.** Eight responses passed unchanged
strict local validation; six passed the frozen deterministic oracle. i06 failed
`question_roles`, and i07 failed `suggestion_0.commitment.label`. The remaining six
passed all predicates. The root correctly reports complete quality failure, not
infrastructure failure, so it did not retry the same prompt to chase a pass.

One full slot/eight scored requests/one probe were used. No runtime interruption,
automatic retry, deadline breach or unsafe stop occurred. The supervisor verified
one owned cleanup, including local compiler groups and pod UID/cgroup/CRI proof,
and restored the shared service. Primary independently verified the exact original
deployment spec, one Ready replica, unloaded model and normal pressure. All nine
request hashes, eight raw/stage/predicate/oracle records, report/receipt/events,
source/frozen lineage and private log/file controls passed final inspection.
Completed temporary root/child launchd registrations were removed.

`primary-final-durable-model-verification.json` and
`independent-final-model-restoration.json` retain the final evidence. All16 tasks
are complete with this actual conformance failure, not a qualification pass. This
run demonstrates caller-independent completion; it does not prove universal
availability. Automatic recovery remains bounded and does not treat quality
failure as infrastructure. No automatic new root, budget reset, extra inference,
human assessment or production/generalization claim follows.

### Targeted quality follow-up — completed, seven conformance passes

After the complete6/8 conformance result, the user accepts the proposed narrow
quality slice with "pk. do it". Retained evidence shows i06 copied the unrelated
demonstration activity noun into its question, while i07 used a lowercase label
initial. No responses or predicates were rewritten. The selected instructions
preserve every previous rule and all five demonstrations, adding only generic
current-activity subject grounding and uppercase definite-label emphasis. No case
IDs, packet answers or gold records were added. An earlier offline-only candidate
was retained but never generated.

The unchanged demonstrations' original actual Kotlin input/raw/stage acceptance and
negative mutations were reverified by hash. Fresh actual Kotlin integrations3/3 and
oracle/revision checks17/17 passed. Harness/runtime source equals the previously
accepted123-check build. Fresh cached vocabulary-only counts are
2289+1536 output+256 margin=4081<=4096, with no tensors/context/generation/download.

The new user-authorized root is
`intake-v4-quality-followup-approved-x9xmzjor/model-recovery`, authority hash
`5490e7556b041b7db205045441a2dadbd95dd28c8e3caca0f07c08a73e0f534b`.
Its one shared grant starts2026-10-21T01:52:59.675466Z and expires
2026-10-21T05:52:59.675466Z, retaining3 full slots/24 scored requests/3 probes and
240s reserve across eligible infrastructure recovery. First actual worker and
exclusive consumer/source/clock readiness are verified. Original deployment spec,
one Ready unloaded shared service and normal pinned runtime/pressure were rechecked.
All eight must be evaluated afresh; prior passes cannot be mixed. No quality outcome
is asserted yet, and quality failure is not an infrastructure retry permission.

Final outcome: all eight completed normally, with8/8 strict local accepts and7/8
frozen-v4 conformance passes from one complete attempt. Only i06 `question_roles`
fails: its question still says "practice" rather than the intake's "training".
i07 passes the original v4 checker. Separately versioned post-hoc v4.1 label-case
comparison also scores7/8; the remaining failure is not capitalization.

One slot, eight scored requests and one probe were used, without retry or deadline
breach. All nine actual request hashes, raw outputs, stages, predicates, report,
receipt and event bindings were verified. Eight compiler groups and both owned pods
have stop proofs. Independent final checks verify exact original deployment spec,
one Ready unloaded shared service and normal pressure; terminal root/child launchd
registrations were removed. See `primary-final-durable-model-verification.json` and
`independent-final-model-restoration.json` in the follow-up evidence root. The
post-hoc comparison is retained separately under
`intake-v41-label-case-approved-77kqw9u4/current-run-secondary-comparison/`.

The root ends `failed` / `complete_quality_failure_not_infrastructure`. No extra
root or new inference is authorized by this result. This remains development-set
tuning without human assessment or production admission. Historical outcomes and
raw responses are unchanged.

### User-authorized contextual question policy — post-hoc eight of eight

After discussing whether "practice" is an acceptable contextual paraphrase of
"training", the user says "ok. do it" and "rerun if needed". The earlier description
of i06 as a definitively wrong activity was too strong: training facts are correctly
extracted and the question asks for the required missing details. Demonstration
wording carryover is plausible, not proven.

The isolated `benchmarks/intake-extraction-v4-2/` policy inherits v4.1 label-case
equivalence and adds one complete practice-worded alternative for i06's
training_unknowns role only. No global noun replacement or fuzzy acceptance is
introduced. Other activity subjects, missing details, changed facts, extra questions
and local rejection remain failures. All26 original/new oracle tests pass, including
all234 omission/wrong-field mutations. Original manifests and runtime source remain
unchanged.

Offline re-evaluation of the latest complete acquisition yields original-v4 7/8,
v4.1 7/8 and **post-hoc v4.2 8/8**. Evidence:
`intake-v42-contextual-question-approved-ngnqywhr/latest-complete-comparison/comparison.json`.
All eight retained responses come from one run; no inference, response rewrite or
model rerun was needed. This is a user-authorized checker-policy change, not model
improvement, original-v4 success or fresh qualification. Production remains closed.

Supervisor process SIGSTOP/resume and simulated wall/native divergence are tested,
not actual host sleep. No shutdown guarantee applies during host sleep, after
logout/reboot or if launchd/the supervisor itself dies. These limitations do not
relax CPU, context, memory, pressure, ownership, approval or cleanup boundaries.
