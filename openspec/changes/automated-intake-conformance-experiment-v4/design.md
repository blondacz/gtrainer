# Design

## Context

See [proposal](proposal.md) for motivation. V3 is complete with a failed result;
its historical protocol and human judgments must not be edited to remove its gate.
The new behavior is a version-isolated automated suite, not production integration.

Inspected dependencies establish the implementation seams:

- `IntakeExtractionV2.kt:73–103` performs strict local wire validation, unique exact
  UTF-16 excerpt binding and production semantic validation. `IntakeExtractionV3`
  delegates to it and changes only the audited provider schema. Reuse both read-only.
- `ContextIntakeSuggestionContractV1.kt:94–104` explains the open/unresolved conflict;
  wire uniqueness at `IntakeExtractionV2.kt:81` explains the repeated-field rejection.
  The production contract checks safety/consistency, not complete commitment extraction.
- Shared `MacRuntime` already has explicit request-builder/context-validator seams
  and authoritative owned stopping. V3 runtime/acquisition records additionally bind
  its fixed profile, exact prompts and context hashes; they cannot authorize v4 by
  substituting a new payload under an old proof.
- Fresh prompt counts require the cached pinned vocabulary-only helper documented
  in [v2 tokenizer evidence](../../../benchmarks/intake-extraction-v2/README.md#source-audited-token-counts-and-retained-context).
  V3's fixed counts apply only to its original rendering.

## Goals / Non-Goals

**Goals:** Deliver an offline, adversarially tested deterministic oracle first,
then a minimal exact-bound v4 acquisition path and bounded prompt-improvement loop.
Define satisfactory as all eight cases passing the same fixed oracle in one batch,
with verified runtime cleanup. Human scoring is not part of v4 execution.

**Non-Goals:** No production feature, app routes/store/schema changes, personal data,
new provider framework, model downloads, runtime tuning, response repair, human-score
simulation or general reliability claim. Do not replace old protocols or claim that
one tuned eight-case pass establishes unseen-input quality.

## Decisions

### 1. Isolate the automated boundary and freeze the oracle

Create `benchmarks/intake-extraction-v4/` with its own protocol, artifact/ownership
lineage, oracle, manifest, revision/context records and reports. Preserve original
packet bytes and v3-compatible schema bytes; only instructions/examples can change
between campaign revisions. Pin v1/v2/v3 manifests and local-validator authority.
Do not copy human rubric thresholds or reinterpret generated ratings as human scores.

Report `evaluationMethod=deterministic`, `independentHuman=false`, explicit
`suiteConformance` and `productionAdmission=closed`. The oracle's source and data
are part of the pre-inference freeze. An oracle bug stops the campaign; fix it only
through a separately versioned protocol/campaign without erasing the old result.

Alternative rejected: removing the human gate from frozen v3 or using a model judge
to assign human-style scores. Both obscure the changed evaluation contract.

### 2. Check typed facts and extractive evidence, not JSON appearance

The oracle consumes only locally accepted assembled drafts. Bind each predicate to
case/source evidence and fixed reason identifiers. Verify required suggestion kinds,
field values, unresolved-field sets, extracted-content coverage, attribution, source
revision and clarification roles. Missing facts, unsupported assertions, duplicate
facts and unexpected suggestions fail. Raw source quotation alone cannot compensate
for a missing typed commitment or an omitted fact in extracted content.

For free-text content, declare accepted source clauses/combinations and finite
meaning-preserving aliases before inference. Preserve explicit negation and reported
attribution. Predeclare clarification templates by genuinely unknown field/role;
reject requests for already supplied information. This intentionally establishes
restricted extractive conformance, not general natural-language equivalence.
Allow suggestion reordering and the contract's predeclared minute/zero-second
timestamp equivalents as comparisons; never normalize or rewrite recorded responses.

Do not put per-case oracle/golden output records into the live prompt. Use generic
extraction rules and unrelated synthetic examples. Offline golden vectors exercise
the oracle and never count as provider evidence.

#### Case predicates

- **i01 — older clinician guidance:** restriction retains reported clinician/ACL
  attribution, explicit observation `2026-06-01` and no-running fact. Do not substitute
  the packet's later observation date for that explicit guidance date. Start remains
  null/unknown with unique unresolved disclosure; end is null/open and not unresolved.
  Clarify only genuinely missing applicability, without claiming clinician verification.
- **i02 — illness/improvement:** retain onset `2026-10-01`, improvement `2026-10-05`
  and no reported recovery/clearance in extracted content. Start is specified at onset;
  end remains unknown/unresolved, not improvement or an asserted open end. Packet date
  can identify observation of the report, not a new illness/recovery event. No question
  asks whether recovery has been reported when the source already states it has not.
- **i03 — conflicting reports:** retain both dated reports, different-coach attribution
  and conflict, with no chosen advice or silent precedence. Preserve both observation
  dates; unknown applicability stays unknown. Clarification must address the conflict
  and missing applicability without treating either report as authority to apply advice.
- **i04 — fixed meeting:** require a structured commitment on `2026-10-19`, local
  start `09:00`, end `10:00`, `UTC`, commitment kind and one-off recurrence. None of
  these supplied fields is unresolved or re-requested. Context-only/symptom output fails.
- **i05 — recurring duty:** require a duty commitment starting `2026-10-19`, `07:00`
  to `08:00`, `UTC`, weekly interval `2`, bounded recurrence end `2026-11-30`. No
  fabricated occurrences or questions about supplied interval/end. Context-only fails.
- **i06 — relative/ambiguous:** require the travel commitment on `2026-10-18`, `09:00`
  to `10:00 UTC`, one-off recurrence. Separately preserve tentative Friday-evening
  training as a commitment with date/time, timezone and recurrence null/unresolved.
  Do not resolve bare Friday, infer repetition, or transfer travel/reference timezone
  to explicitly undecided training timezone. Ask genuine training unknowns only.
- **i07 — corrected/injected:** bind revision `2` and an exact excerpt including the
  non-BMP prefix. Require a meeting commitment `2026-10-20`, `14:00` to `15:00 UTC`,
  one-off recurrence. Retain the correction; do not substitute Monday, extract the
  embedded instruction as a fact, assert confirmation or ask the already supplied end.
- **i08 — uncertain wellness:** retain recalled poor sleep, two readings, different
  devices, unknown reading dates/methods and unmeasured readiness in extracted content.
  Applicability stays unknown; packet date is report observation only. Ask missing
  dates/methods without invented physiology, readiness or recovery.

Freeze exact allowed alternatives and witness vectors while implementing the offline
slice, before any provider call. Each case needs a positive accepted fixture and
targeted negative mutations for every predicate, including negation/attribution swaps,
missing facts, wrong kinds, gratuitous questions and unsafe assertions. Positive
variation tests must show the oracle is not whole-JSON string equality.

### 3. Reuse strict validation and owned execution without monkeypatching v3

Use a small v4 test-source bridge around unchanged local validation and the same
private file/report controls. Reuse the v3-compatible provider schema/ownership
audit read-only. A new v4 runtime supplies its own request/context bindings through
the shared Mac seam and retains the observed mmap/no-repack gate and bounded loader
diagnostics. Share proven loader mechanics through a narrow explicit configuration
seam if needed; do not duplicate a weaker stop path or patch module globals/profile
constants to impersonate another version. Any extraction of non-frozen mechanics
must preserve v1/v3 fake behavior, exact request bytes and historical source snapshots.

Each immutable revision binds complete prompt/request bytes, original case IDs and
packet hashes, oracle/manifest/tooling hashes, model/template/tokenizer, resource
identity and its prior revision. Hash all eight effective mmap-enabled requests and
the existing probe. Re-count changed rendering with the cached pinned vocabulary-
only tokenizer; do not load model tensors or create inference context for counts.
Input plus output reserve 1536 plus margin 256 must fit 4096. Missing cache or
unsupported rendering stops preparation without a download, truncation or context bump.

### 4. Make the campaign the single bounded authorization unit

The user's autonomous-loop instruction is represented by a campaign-purpose scope,
not reused v3 named-run approvals. Once the separate apply boundary is satisfied,
implementation can prepare fresh exact revisions and run automatic validation within
the bounded scope without additional human scoring/quality approvals.

Scope fixes model/schema/packets/oracle/resources, at most three sequential batches,
one scored attempt per case/revision and one existing active-stop probe per batch.
At most 24 scored requests and three probes are allowed. No hidden warm-up,
per-case correction, fallback, independent diagnostic inference or identical-prompt
retry. A new batch must have a new bound generic instruction/example revision;
the primary may inspect failed outputs privately but must not paste rejected outputs
or per-case gold answers into subsequent live prompts.

Record a single four-hour campaign deadline before first admission/probe. Include
all later revision work, context checks, acquisition, oracle execution and cleanup;
do not give each batch four additional hours. Keep attempt 900s and case 1800s
bounds, shortened to the campaign's work cutoff. Reserve 240 seconds for owned
termination/restoration before starting requests. The existing probe stop retains
its separate 60-second authority after activity is observed; final cancellation
retains one separate 60-second bound including restoration, not a fresh timer per
phase. Normal work must end by campaign deadline minus the reserve, keeping these
cleanup bounds within the campaign. If a broken callback unexpectedly overruns,
attempt the same bounded cleanup once, report the breach as incomplete and fence
all continuation. Never restart the cleanup timer or abort verification merely to
claim clock compliance. The reserve governs admission, not hard real-time guarantees.
Pre-campaign software implementation/offline tests are not generation iterations.

Completed output rejection or deterministic case failure can advance through all
eight cases once, then permit a prompt revision only after verified cleanup. Timeout,
incomplete response, identity drift, host pressure, missing monitoring, infrastructure
error, oracle defect or unverified stop ends the campaign immediately. No restart,
resize, download, guard relaxation or automatic new campaign is included. Stop on
the first full suite pass, budget exhaustion, or lack of a defensible new revision.
Never combine best cases across batches.

Keep a small append-only campaign ledger with immutable start/deadline, host clock
identity, spent revision claims and terminal batch receipts. A process/session change
does not reset counters or time; uncertain claimed execution, lost state or changed
clock identity fences the campaign rather than replaying it. This is a bounded
command/controller, not an application queue or background maintenance service.

### 5. Keep reports private and claims narrow

Use 0700 external directories, exclusive 0600 files, bounded reads/writes, private
Gradle reports and content-free diagnostics. Preserve raw outputs/hashes, all eight
outcomes, stage and predicate reasons, unknown measurements as null, imported versus
observed provenance, every revision/failed start and verified cleanup evidence.
Campaign reports link immutable batch reports and explicitly state which revision
passed, failed or remained incomplete. Fake/offline fixtures prove tooling only.

Known-eight-case iteration is development-set tuning. Disclose it and all changed
prompt/schema/bookkeeping/runtime confounders. No holdout claim, human independence,
production/v1 qualification, personal-data access or app confirmation follows from
a suite pass. The application and its separate approval/confirmation gates are untouched.

### 7. Independent lifecycle supervision

The authorized lifecycle follow-up adds a temporary launchd-owned supervisor for
each live batch, outside OpenCode's managed process tree. It launches one worker
and observes the caller connection, worker exit, immutable phase deadlines, work
cutoff and UTC expiry. Readiness and source/clock bindings are mandatory before
any runtime mutation. There is no KeepAlive, persistent installation, restart or
inference retry. Synthetic process tests must demonstrate actual launchd survival.

Only the supervisor performs final cancellation. The worker delegates normal
cleanup; abnormal takeover first closes admission and kills/reaps the dedicated
worker process group, then acquires the mutation lock. All mutation intents and
ownership transitions are durably recorded before the operation. Preflight's
temporary shared-pod reference is never treated as owned execution. Both approved
pod creations retain independent identities. The supervisor loads cleanup code
before admission, so later checkout drift blocks work, not owned termination.

One cleanup budget covers quiescence, reconciliation, owned termination and
restoration. Pending create/pause operations must be resolved from exact names,
tokens, run labels, UIDs and the original deployment spec. Absence after a lost
create response cannot prove that no delayed create will arrive. Unresolved
mutation outcomes mean unverified cleanup, no restoration and no continuation;
never blindly replay a mutation. Exactly-once final cleanup ownership does not
claim exactly-once remote effects. Preserve absent original reports/receipts on
worker loss and record a separate incomplete terminal fence.

Journal and control JSON are fsynced into unpublished private temporary files,
then published atomically without replacement using the existing macOS
`renamex_np(RENAME_EXCL)` API. Readers ignore unpublished names. Unsupported
publication fails before admission; no overwriting or hard-link fallback is used.
Every phase transition must begin before the preceding fixed deadline, including
startup, even when multiple transitions arrive between supervisor polls.

Auxiliary evidence failures cannot skip worker termination. Cleanup captures its
single bounds before fallible reporting. A corrupt cleanup request makes original
timing unverified; the supervisor retains only one bounded emergency owned-stop
attempt, without restoration or another attempt. Failure to publish owned-stop
proof occurs after actual stopping but before shared restoration. Failed evidence
or incomplete timing never permits a successful lifecycle outcome.

The supervisor's final cleanup also retains a separate trusted process-local
deadline and fixed wall cap. A native campaign-clock API failure cannot skip
worker quiescence or already-owned stopping. Unknown native timing prohibits
restoration and a verified lifecycle outcome; it does not become an alternative
inference clock or a new cleanup grant.

Poll gaps or wall/native clock divergence fence work on the first runnable
opportunity. No shutdown guarantee applies while the host sleeps, after logout or
reboot, or if launchd/the supervisor itself dies. Sleep beyond the deadline is a
disclosed breach, never a new grant. Cleanup clocks are not restarted after sleep.
Real host-sleep behavior is not inferred from simulated suspension tests.

### 8. Durable execution authority is independent of observers

The user's subsequent availability instruction and "ok, ffix it re-run" replace
the former caller-liveness policy for new executions. A durable job-bound start
request is published before bootstrap. Once accepted, launchd owns the worker;
the observer can exit, time out or reattach without cancellation, another launch
or a renewed deadline. CLI startup returns a durable job handle. Read-only status
and results do not reopen Campaign or depend on unchanged checkout source.
Cancellation is an explicit, idempotent, job-bound durable request. Existing worker,
phase, native identity, pressure, ownership and deadline failures still stop work.

Detached offline tests use only the fixed v4/shared unittest commands. One hour
includes all three allowed attempts and repair waiting, with sixty seconds reserved
for final quiescence. A launcher failure before process creation may retry. A
completed assertion/compiler failure waits for changed source and a source-bound
passed-check repair manifest submitted by the primary; no further user approval is
required. Unknown process loss is not blindly restarted because compiler clients
may have escaped its process group. Source drift invalidates the attempt. All
failed logs/results remain immutable. This is not an arbitrary command runner or
an inference approval.

Model recovery uses a separate temporary launchd coordinator and immutable outer
authority. It reserves one full eight-case batch and one probe before every fresh
attempt, including attempts with uncertain/unused launches. At most three slots,
24 scored requests and three probes are available across restarts and fixes. Every
child campaign inherits the original four-hour start/end and240-second work reserve,
binds its root reservation and allows exactly one batch. Old unfinished claims are
never reopened or refunded. The root budget cannot be bypassed by reconnecting or
directly invoking a bound child for another batch.

Only verified-cleanup worker loss or generation timeout permits an unchanged-source
automatic full-batch retry. Complete model-quality failure is not infrastructure
and ends recovery, without best-case mixing. Eligible infrastructure fixes require
verified cleanup, new source/revision bindings and a source-bound passed-check
manifest; the primary submits it automatically after repair. Repair time consumes
the same outer grant. Pressure, clock/authority ambiguity, unknown supervisor fate
and unverified cleanup cannot trigger automatic model recovery.

The coordinator may restart on unsuccessful exit at most three times, independent
of the three batch slots. It durably spends a restart before fallible clock work;
failed expenditure publication disables KeepAlive rather than looping. Restarted
coordinators attach to a spent reservation, never replay an uncertain spawn. Each
per-attempt cleanup supervisor remains non-restarting and sole owner of Kubernetes
cleanup. A coordinator cannot bypass that cleanup or restart live services. Root
cancellation/closure fences active children. All plists remain private temporary
artifacts, never installed as permanent maintenance agents.

Owned compiler clients are also journaled. A dedicated guardian publishes its
group identity before compiler execution and retains that identity until stopping;
worker death cannot discard the compiler cleanup handle. Nested synthetic launchd
jobs register under their test owner. Verified cleanup requires their quiescence,
not merely disappearance of the original worker. macOS may retain zombie-only
groups and deny `killpg(0)`; EPERM alone is never proof. Bounded numeric process
metadata must prove no live members, and evidence distinguishes quiescence from
literal group absence. Model pod UID/cgroup/CRI requirements are unchanged.

Scheduler latency alone is not a clock failure or cancellation. Fixed native phase,
work-cutoff and UTC deadlines still apply after a delayed supervisor resumes; they
are never renewed. Actual native regression or wall/native divergence remains a
failure. This replaces the over-strict five-second poll-gap abort for new execution.

An offline executor that failed under an older implementation may restart only
after separately verified local quiescence and source-bound repair checks. Its
fresh executor inherits the original test grant and remaining attempt numbers via
an exclusive restart reservation. Original failed outcomes remain unchanged;
neither repair nor executor replacement grants a new hour or refunds spent work.
Failure observers report `awaiting_repair` immediately rather than waiting for
outer expiry. The explicitly requested offline continuation timeout is600s;
model attempt/case/campaign limits remain unchanged.

### 9. Version label capitalization as formatting equivalence

The user explicitly authorizes relaxing the label-capitalization check after the
complete v4 output `meeting` fails an allowed `Meeting` representation. The isolated
v4.1 comparison policy applies Unicode casefold only to temporary comparison copies
of `commitment.label` and its allowed alternatives. It leaves all other typed,
identity, source, uncertainty, counting and exact question predicates unchanged.
Whitespace, punctuation, different nouns and non-string labels are not normalized.

Do not change active v4 source pins or the frozen v4 contract after acquisition.
Version the policy/implementation/tests against the parent v4 manifest and retain
all original reports/receipts. Re-evaluation of previously acquired data is explicitly
post-hoc, source-hash linked and not a new acquisition, independent score or model
quality improvement. No response is repaired or rewritten; case equivalence is
comparison semantics only. The current live v4 run finishes under its original
rules, with any v4.1 result presented as a separate secondary assessment.

### 10. Version a contextual question alternative

The user authorizes accepting "practice" as a contextual paraphrase of "training"
in the retained i06 clarification. The earlier "wrong activity" diagnosis was too
strong: the extracted training facts are correct, and the question requests all
required missing details. Demonstration wording carryover is plausible, not proven.

V4.2 inherits the v4.1 label-case policy and adds one explicit complete question
alternative for i06's training_unknowns role. It does not perform global noun
replacement, fuzzy similarity or arbitrary paraphrase acceptance. All suggestions,
source excerpts, typed facts, identity, uncertainty and question counts remain
strict, so changing the underlying activity or referring to travel still fails.
The added text must retain date, start/end times, timezone and recurrence details.

Preserve the frozen v4 and v4.1 files, manifests and results. Bind the new policy,
implementation and tests to the parent manifest. Re-evaluate the latest complete
eight-response acquisition without inference or response edits, recording original
v4, v4.1 and v4.2 scores separately. A new-policy pass is post-hoc development-set
conformance, not model improvement, a fresh experiment or production qualification.

## Risks / Trade-offs

- **False positives from lexical matching:** require typed values, extractive fact
  coverage, explicit negation/attribution, finite questions and per-predicate mutations.
- **False negatives for valid paraphrases:** accept only declared extractive alternatives
  and disclose the narrow contract; do not silently loosen acceptance after outputs.
- **Overfitting the known suite:** prohibit gold-answer injection and report only suite
  conformance. Broader reliability needs a separately scoped evaluation, not extra calls.
- **Runtime reuse becomes a redesign:** constrain it to explicit request/context/loader
  seams and existing stop authority, with fake regressions before live execution.
- **Resource pressure reappears:** preserve all guards; an infrastructure stop ends
  the loop instead of consuming further probes or attempting automatic recovery.

## Migration Plan

Add an isolated benchmark; no application deployment or data migration. Deliver the
offline oracle/report slice before live-loop plumbing. Preserve previous source
snapshots and artifacts if shared non-frozen seams change. A stopped campaign is
inspected, not restarted automatically. Any later spec sync/archive must explicitly
reconcile the pending version-scoped v2/v3/v4 deltas; none is authorized here.

## Authorized follow-up — native clock domain and extraction

After both historical campaigns were fenced incomplete, the user explicitly said
**"ok, do it all"** to fixing the timer, real cross-process tests, extraction
improvements and one new bounded campaign. This does not reopen an old claim or
rewrite its result. New task4 owns the additional work and preserves the original
oracle, validators, schema, model and runtime envelope.

### 6. Bind an OS-wide clock and convert only runtime boundaries

On the existing Mac Python3.9.6, `time.monotonic()` has a process-specific reference
point. The stdlib OS API `time.clock_gettime_ns(time.CLOCK_MONOTONIC)` is available
and returns a comparable host clock. Use only this named native API, represented
as seconds, with an explicit clock-version/name and boot UUID. There is no fallback
to process time, wall time, another clock ID or a newly installed interpreter.
New tooling bindings include the clock module and its native tests. Legacy clock
identities fail closed; no old scope is migrated or interpreted under the new epoch.

Campaign start/end/cutoff, action intents, ledger timestamps, request guards and
validation budgets remain in the shared native domain. The unchanged Mac adapter
uses process-local `time.monotonic()` for its command, probe and generation timers.
Therefore convert exactly once at the controller's preflight, probe and generation
boundaries: sample local time first, sample the authoritative shared clock second,
then add only the positive remaining duration to the first local sample. Conversion
overhead shortens rather than extends authority. Never pass the OS epoch directly
to the local runtime, and never double-convert. Validators receive a shared-domain
case deadline and a bounded remaining duration, not a runtime-local timestamp.

The separate trusted final-cleanup clock already matches the runtime's local domain
and is not converted or replaced. Live test-clock/runtime/validator overrides remain
forbidden. Shared checks before and after callbacks retain source/boot/regression/
expiry fences. Suspend can affect native/local elapsed rates differently; an
unexpected overrun remains incomplete and still triggers one bounded cleanup,
not a hard-real-time guarantee or an extended deadline.

Admission requires real parent/child readings bracketed by parent readings and an
actual two-process synthetic failure/new-revision/success using default native
clocks, unchanged scope bytes and cumulative claims. Test local runtime deadline
caps through inherited command logic without invoking host/model operations.
Deterministic fake exhaustion tests remain separately labeled; they do not claim
four real hours elapsed. Only after these and existing safeguards pass may one
new3-batch/24-request/3-probe/4h campaign begin. Prior responses may guide generic
instruction/example changes privately, but not response repair, gold outputs,
oracle changes or another automatic campaign.
