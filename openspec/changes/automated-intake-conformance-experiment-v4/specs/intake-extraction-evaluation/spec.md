# Automated Intake Conformance Spec Delta

## Purpose

Evaluate a version-isolated synthetic intake extractor through deterministic
conformance checks and bounded autonomous iterations, without human scoring or
relaxing production validation and execution safety.

## ADDED Requirements

### Requirement: V4 preserves historical protocols and strict local authority
V4 MUST preserve v1/v2/v3 freezes, run outcomes and human assessments unchanged. It MUST retain the original eight synthetic packets, the v3 provider schema and unchanged strict local wire, exact-excerpt binding and production semantic validation. Changed prompts MUST use distinct v4 revision identities.

#### Scenario: Older results are compared
- **WHEN** a v4 report references the completed failed v3 experiment
- **THEN** v3 retains its human gate, recorded scores, two rejections and failed decision
- **AND** no new result rewrites that history or qualifies the original v1 profile

#### Scenario: Invalid output looks repairable
- **WHEN** a response has duplicate unresolved fields or contradictory open/unknown bounds
- **THEN** unchanged local validation rejects the whole response
- **AND** no deduplication, response repair, item salvage or validator relaxation is used

### Requirement: V4 automated acceptance is fixed before inference
V4 MUST freeze deterministic case predicates before its first provider request. Acceptance MUST require local validation and all predicates for all eight cases in one revision. The oracle MUST cover extracted fact completeness, typed commitments, uncertainty, attribution and safety rather than schema validity alone. Missing or unsupported checks MUST fail closed.

#### Scenario: Meeting text has no structured commitment
- **WHEN** a response retains meeting or recurring-duty text only as symptom context
- **THEN** its case fails automated conformance even if local validation accepts it

#### Scenario: Expected answer is hidden in an excerpt
- **WHEN** a response quotes the source but omits a required structured value or extracted-content fact
- **THEN** source-excerpt presence alone does not satisfy the missing predicate

#### Scenario: Equivalent permitted representation is used
- **WHEN** suggestions are reordered or use a predeclared equivalent timestamp spelling
- **THEN** the oracle evaluates the corresponding typed facts without requiring identical whole-response text
- **AND** the exact original response remains unchanged

#### Scenario: Oracle defect is discovered after acquisition
- **WHEN** an implementation or predicate defect could change acceptance
- **THEN** the campaign stops and preserves its previous decisions
- **AND** an oracle change requires a new version and separately bounded campaign rather than silently resetting the current budget

### Requirement: V4 does not require or impersonate human assessment
V4 MUST report deterministic conformance rather than human usefulness scores. No human quality review MUST be required to continue a permitted revision or pass the suite. Automated results MUST identify their method and MUST NOT claim independent human assessment, broad model qualification or production acceptance.

#### Scenario: Every automated case passes
- **WHEN** all eight cases pass the fixed checks in one revision and cleanup is verified
- **THEN** v4 reports a deterministic suite pass with human assessment absent
- **AND** production admission remains closed and generalization beyond the known suite is unproven

### Requirement: V4 revisions have exact request and context bindings
Before any provider request, V4 MUST bind its campaign, revision, oracle, inputs, prompt, schema, full request bytes, rendered context, tokenizer, model, tooling and runtime identity. Changed prompts MUST have fresh runtime-matched offline context evidence within context 4096, output reserve 1536 and margin 256. No truncation or implicit proof refresh is permitted.

#### Scenario: Prompt differs from the old context evidence
- **WHEN** a revision changes instructions or examples
- **THEN** the old 816-token maximum is not reused as evidence
- **AND** exact new rendered-input counts and request hashes must pass before generation

#### Scenario: Bound source changes during a batch
- **WHEN** a prompt, oracle, request builder or runtime binding drifts after launch
- **THEN** further generation is refused and evidence is preserved without fallback

### Requirement: V4 autonomous iteration is bounded and sequential
A V4 campaign MUST allow at most three eight-case batches and one non-scored active-stop probe per batch within one four-hour monotonic campaign budget. It MUST use one scored attempt per case per revision, sequential requests and fresh immutable revision records. Only a completed safely stopped model-quality failure can lead to another prompt revision. Cases from different revisions MUST NOT be combined to pass.

#### Scenario: One revision has a quality failure
- **WHEN** a complete batch fails fixed predicates but its runtime cleanup and safety checks pass
- **THEN** a new generic prompt revision may run within the remaining shared batch/time budget without human scoring
- **AND** every earlier failure remains recorded, with no in-place correction or retry of an individual case

#### Scenario: Success or budget exhaustion
- **WHEN** one complete revision passes or the three-batch/four-hour budget is exhausted
- **THEN** the campaign stops with the actual pass, failed or incomplete outcome
- **AND** it does not create a new campaign or reset the budget automatically

### Requirement: V4 runtime safety overrides the optimization loop
V4 MUST retain the current CPU, 10-GiB VM, 8-GiB hard limit, 7.5-GiB full-current-memory stop, host-pressure, mmap/no-repack, ownership and verified stopping/restoration guards. Attempt 900-second and case 1800-second bounds MUST remain capped by campaign time. Safety, infrastructure, timeout, incomplete response or monitoring failure MUST stop the campaign without automatic recovery.

#### Scenario: Host pressure or stop evidence fails
- **WHEN** host pressure becomes non-normal or owned execution cannot be verified stopped
- **THEN** no later case or revision starts, and the campaign remains fenced
- **AND** no resize, restart, download, guard weakening or provider fallback is attempted

#### Scenario: Campaign time approaches its limit
- **WHEN** the reserved termination/restoration window is reached
- **THEN** new requests are refused and owned cleanup runs with its separate bounded stop authority
- **AND** cleanup failure or deadline breach is reported, never hidden as a successful campaign

### Requirement: V4 retains private evidence and scoped authorization
V4 MUST require explicit campaign-purpose authorization, exact bounded revision records and private immutable evidence before actions. It MUST preserve every attempt, raw hash, stage result, predicate outcome, resource provenance, failed start and unrun case. The user-authorized synthetic acquisition and automatic validation scope MUST NOT imply personal-data access, production actions, hosted transfer or publication.

#### Scenario: A batch aborts early
- **WHEN** a safety or infrastructure failure prevents all requests completing
- **THEN** all eight cases remain in the report, with incomplete/unrun outcomes and null unknown measurements
- **AND** every attempted revision consumes its batch allowance

#### Scenario: Automated validation is permitted
- **WHEN** an authorized synthetic batch produces existing response bytes
- **THEN** its approved offline validation and deterministic oracle may process those bytes without another human quality-review gate
- **AND** prompts/responses remain out of terminal, repository and CI logs

### Requirement: Follow-up campaign clocks have native cross-process evidence
Any newly authorized follow-up campaign MUST use an explicitly versioned OS-wide monotonic clock on the existing host, with boot identity and source bindings. It MUST reject historical process-relative clock identities rather than migrate or resume old claims. Native cross-process comparability MUST be exercised without injected clocks before live admission. Existing runtime-local deadline consumers MUST receive conservative remaining-duration conversions, not shared-clock epoch timestamps. Cleanup authority and all prior safety/evaluation boundaries MUST remain unchanged.

#### Scenario: A fresh process continues a completed safe quality failure
- **WHEN** real native-clock tests open a new process on the same boot after a completed safely stopped synthetic quality failure
- **THEN** scope/start/end bytes and cumulative claims remain unchanged while elapsed revision work consumes the same budget
- **AND** only a genuinely changed linked revision may continue

#### Scenario: A runtime operation receives a shared deadline
- **WHEN** the controller dispatches preflight, the stop probe or generation
- **THEN** it samples local time before the authoritative shared time and converts only the positive remaining duration once
- **AND** inherited runtime command/attempt deadlines remain capped without changing the local cleanup clock

#### Scenario: Native clock or identity cannot be verified
- **WHEN** the API is absent, invalid, backward, on a different boot or under a legacy clock identity
- **THEN** new actions are refused with no wall-clock/process-clock fallback or budget reconstruction
- **AND** any already owned execution still receives its unchanged bounded cleanup attempt

### Requirement: V4 supervision survives controller loss

Newly authorized live batches SHALL require a temporary launchd-owned supervisor
outside the managed caller's process tree, positive readiness, source/clock binding,
write-ahead ownership evidence and fixed action deadlines before runtime mutation.
The supervisor SHALL be the sole final cleanup owner and SHALL reuse authoritative
owned termination and exact deployment restoration. It SHALL NOT restart workers,
retry inference, reopen historical claims or claim success from ambiguous cleanup.

#### Scenario: Caller or worker disappears
- **WHEN** the caller disconnects or its worker exits before normal completion
- **THEN** the supervisor closes admission, terminates and reaps the worker group,
  performs at most one bounded final cleanup and records an incomplete fence
- **AND** missing original reports or receipts are not fabricated

#### Scenario: A mutation response is lost
- **WHEN** a pause or either approved pod creation has durable pending intent
- **THEN** cleanup reconciles only the exact recorded identity
- **AND** an unresolved remote outcome cannot verify cleanup or permit restoration

#### Scenario: A deadline or suspend gap is detected
- **WHEN** the supervisor observes phase expiry, work cutoff, approval expiry or a
  suspend/clock gap
- **THEN** work is fenced and bounded cleanup begins at the first runnable opportunity
- **AND** no deadline resets or shutdown guarantee during host sleep are claimed

#### Scenario: Journal publication or auxiliary evidence fails
- **WHEN** a writer disappears before publishing a complete ownership update
- **THEN** the supervisor reads only complete, exclusively published records and
  retains the preceding write-ahead ownership intent
- **AND** auxiliary evidence failures cannot bypass worker termination or the
  single bounded owned-stop attempt, but prevent verified restoration claims

#### Scenario: A phase transition arrives after its preceding deadline
- **WHEN** a worker publishes a new phase after the previous lease expires
- **THEN** the supervisor rejects the transition even if both records arrive
  between polls, rather than replacing the expired deadline

### Requirement: Observer disconnection does not revoke durable execution authority

For newly authorized availability-corrected execution, the monitoring caller SHALL
be an observer, not the lifetime owner of the launchd-owned worker. Losing that
connection SHALL NOT cancel a healthy source-bound worker, renew its budget or
start another attempt. Explicit cancellation and all existing safety/deadline
conditions SHALL remain authoritative. The former caller-loss stopping scenario
remains historical evidence, not the policy for this newly authorized execution.

#### Scenario: The monitoring caller disappears
- **WHEN** an already-authorized worker remains healthy after its observer exits
- **THEN** the independent supervisor permits execution to finish within the same
  original authority and leaves durable status, results and cleanup evidence
- **AND** a later observer can inspect them without launching inference again

#### Scenario: Recovery follows an infrastructure failure or a source fix
- **WHEN** new execution uses the user's bounded restart/rerun authorization
- **THEN** every attempt retains its original failure and consumes one cumulative
  deadline and finite attempt/resource/request budget
- **AND** model recovery requires verified cleanup and a fresh immutable attempt,
  never resuming an uncertain claim or hiding a model-quality failure

#### Scenario: The user cancels or safety cannot be established
- **WHEN** explicit cancellation, exhausted authority or an unrecoverable safety
  condition occurs
- **THEN** execution stops with authoritative owned cleanup and no automatic
  further attempt or budget renewal

### Requirement: Label case equivalence is separately versioned

The user-authorized v4.1 evaluation policy SHALL ignore capitalization only for
commitment-label comparison, while retaining unchanged v4 facts, identity, source,
date/time, uncertainty and clarification requirements. It SHALL NOT modify original
responses, the frozen v4 policy, active v4 source bindings or historical outcomes.

#### Scenario: A correct label differs only in capitalization
- **WHEN** an otherwise valid accepted draft labels an event `meeting` instead of
  the allowed `Meeting`
- **THEN** v4.1 accepts the label under declared case equivalence
- **AND** whitespace, punctuation, different event nouns or wrong field types do
  not acquire that equivalence

#### Scenario: Earlier outputs are evaluated under the new policy
- **WHEN** a complete v4 report is compared with v4.1
- **THEN** the new result records both versions and the original report hash as
  an explicitly post-hoc secondary assessment with zero new inference
- **AND** the original report/receipt and any ongoing v4 acquisition remain unchanged

### Requirement: Contextual question equivalence is explicit and versioned

V4.2 SHALL inherit v4.1 label-case equivalence and accept one declared complete
practice-worded clarification as an alternative for i06's training_unknowns role.
It SHALL preserve all other predicates and historical policy/report bytes. It
SHALL NOT globally substitute activity nouns or accept arbitrary paraphrases.

#### Scenario: Practice refers to the unresolved training activity
- **WHEN** the accepted draft retains the correct travel and training facts and asks
  "What are the practice date, start and end times, timezone, and recurrence details?"
- **THEN** v4.2 accepts that question as the declared training clarification
- **AND** missing details, a travel subject, extra questions or changed underlying
  facts remain failures

#### Scenario: Retained responses pass the expanded policy
- **WHEN** the complete earlier acquisition is re-evaluated under v4.2
- **THEN** the result preserves the source report hash and all three policy scores
- **AND** it declares post-hoc re-evaluation with zero new inference, no historical
  outcome replacement and no claim of improved model performance
