# Prospective Synthetic Intake Evaluation Spec Delta

## Purpose

Evaluate synthetic intake extraction with immutable case contracts, unchanged
strict application validation and bounded private execution, while distinguishing
prospective fresh-input results from post-hoc development-set policy changes.

## ADDED Requirements

### Requirement: V5 cases and acceptance are frozen before candidate inference

V5 SHALL freeze exactly eight new synthetic packets, distinct case identities,
positive witnesses and complete typed/extractive predicates before candidate
inference. It SHALL retain the unchanged selected prompt, demonstrations, schema
and model. All historical policies, manifests, responses and outcomes SHALL remain
unchanged. Expected answers SHALL NOT enter candidate requests.

#### Scenario: Prospective preparation
- **WHEN** the fresh suite is prepared for evaluation
- **THEN** all eight packets and allowed question alternatives are manifest-bound
  before any candidate response is acquired
- **AND** previous responses are not replayed or mixed into the new score

#### Scenario: Rules change after an output is inspected
- **WHEN** a proposed change could alter acceptance after acquisition begins
- **THEN** the current result is retained under its original rules
- **AND** no amended rule or prompt is used to continue this evaluation

### Requirement: V5 equivalence preserves required facts and question meaning

V5 SHALL apply case-insensitive commitment-label comparison and only predeclared
context-specific question alternatives. It SHALL retain strict identity, excerpts,
typed dates/times, recurrence, attribution, uncertainty and counts. It SHALL NOT use
global noun replacement, fuzzy matching, response repair or item salvage.

#### Scenario: Contextual paraphrase with all required details
- **WHEN** a question uses a predeclared training/practice alternative for its role
  and all facts and required missing details are correct
- **THEN** the case passes that question predicate without changing response bytes

#### Scenario: Superficially similar but wrong question
- **WHEN** a question targets travel instead of training, omits required details or
  uses an alternative belonging only to another case
- **THEN** conformance fails even if strict local validation accepts the JSON

### Requirement: V5 selection binds every execution and validation stage

V5 SHALL bind suite identity, exact packets, requests, schema, prompt, tokenizer,
predicate contract, transitive tooling sources and runtime throughout execution.
Only explicitly supported suites SHALL be selectable. Fresh packets SHALL pass
unchanged strict local validation against their own packet, never a historical one.
Legacy-v4 behavior SHALL remain unchanged.

#### Scenario: Packet substitution at the validator boundary
- **WHEN** a case ID, packet hash or supplied packet differs from the frozen suite
- **THEN** validation refuses before processing that response

#### Scenario: Cross-suite or source substitution
- **WHEN** a fresh run is paired with historical requests, another suite's phase or
  changed bound source
- **THEN** further execution is fenced without model fallback or authority renewal

### Requirement: V5 context admission is fresh and fail-closed

V5 SHALL verify fresh runtime-matched vocabulary-only evidence for every exact
rendered request and the stop probe. Input tokens plus1536 output tokens plus256
margin SHALL NOT exceed4096. Old counts SHALL NOT substitute for new evidence;
overflow SHALL block inference without prompt truncation or context expansion.

#### Scenario: Fresh packet exceeds remaining headroom
- **WHEN** any frozen rendered request exceeds2304 input tokens
- **THEN** no inference starts and the admission failure is retained
- **AND** the prompt or packet is not silently shortened to force admission

### Requirement: V5 execution has one non-renewable evaluation allowance

V5 SHALL admit at most one new root, one batch, eight scored launches and one
non-scored stop probe within one 14,400-second native/UTC grant with 240 seconds
reserved for cleanup. It SHALL NOT retry, repair, revise, replace or refund claimed
work. Per-case quality failures SHALL remain visible while healthy execution
continues through the remaining cases.

#### Scenario: Duplicate start or second batch
- **WHEN** a caller repeats a start, attempts a ninth scored launch, a second probe
  or a second batch
- **THEN** no additional model request is admitted and no new budget is created

#### Scenario: Completed quality failure
- **WHEN** all eight cases complete but one or more fails frozen conformance
- **THEN** the root ends with the actual failed result
- **AND** failure is not treated as infrastructure eligible for retry

### Requirement: V5 preserves durable ownership and runtime safety

V5 SHALL retain observer-independent supervision, native deadlines, explicit
cancellation, write-ahead ownership and one bounded cleanup owner. It SHALL retain
the pinned CPU/model/memory/pressure/mmap gates and authoritative process/pod stop
and exact shared-service restoration evidence. Safety failure SHALL stop execution.
Cleanup SHALL NOT require still-valid inference authority.

#### Scenario: Monitoring caller disappears
- **WHEN** the monitoring caller exits during an authorized healthy evaluation
- **THEN** the durable worker continues within the original allowance
- **AND** read-only reattachment does not launch or replay requests

#### Scenario: Worker or safety evidence is lost
- **WHEN** the worker dies, authority expires or runtime/ownership checks fail
- **THEN** further inference stops and bounded owned cleanup runs
- **AND** uncertain stop or incomplete execution is never reported as a suite pass

### Requirement: V5 evidence remains private and preserves all eight outcomes

V5 SHALL retain all eight ordered outcomes, raw and packet hashes, validation and
predicate results, resource provenance, report/receipt/event bindings and stop
proofs in exclusive private artifacts. It SHALL retain unrun and failed cases and
unknown measurements honestly. Prompt/response bodies SHALL NOT appear in logs or
routine chat summaries.

#### Scenario: Batch stops early
- **WHEN** an infrastructure or safety failure prevents completion
- **THEN** all eight cases remain in the report with incomplete/unrun outcomes as
  appropriate, without fabricated measurements or partial-suite qualification

### Requirement: V5 result does not authorize application integration

V5 SHALL report prospective synthetic conformance separately from earlier post-hoc
scores, without human assessment, independent-holdout or broad reliability claims.
An 8/8 complete safely stopped pass SHALL support only considering a separately
authorized integration pilot. Production admission SHALL remain closed regardless
of score; failures SHALL block a readiness recommendation.

#### Scenario: All eight fresh cases pass
- **WHEN** one complete batch passes unchanged local and frozen conformance checks
  and all execution/cleanup evidence is valid
- **THEN** the report states a prospective eight-case synthetic pass and its limits
- **AND** no application code, personal data, deployment or production gate is changed

#### Scenario: Apparent unseen coverage is overstated
- **WHEN** results are summarized
- **THEN** fresh means not previously acquired in this local experiment
- **AND** the summary does not claim absence from model training or independent
  human/blind evaluation

### Requirement: V5 wording corrections are separately versioned offline

The separately authorized v5.1 policy SHALL permit one optional leading indefinite
article in commitment-label comparison and bounded role-specific activity wording
equivalence. It SHALL preserve all other validation and fact predicates, actual
responses, frozen v5 policy and historical outcomes. It SHALL NOT use global synonym
replacement or arbitrary paraphrase acceptance.

#### Scenario: Equivalent rehearsal wording
- **WHEN** an otherwise valid retained draft omits the label's leading article and
  uses the complete practice clarification for the uniquely bound rehearsal role
- **THEN** v5.1 accepts those declared comparison equivalences without editing output
- **AND** incorrect facts, missing disclosures, wrong activities or omitted question
  details still fail

#### Scenario: Activity reference would be ambiguous
- **WHEN** the frozen contract contains multiple plausible practice targets or an
  unsupported role/source binding
- **THEN** the new policy adds no question alias for that unsupported context
- **AND** pre-existing frozen alternatives and strict predicates remain unchanged

### Requirement: V5 revised scores retain post-hoc provenance

An offline v5.1 assessment SHALL retain original and revised case results, source
report and policy hashes, the full eight-case denominator and explicit zero-inference
post-hoc metadata. It SHALL NOT replace original reports/receipts or imply model
improvement, independent semantic assessment, fresh qualification or app admission.

#### Scenario: Retained responses pass corrected comparison
- **WHEN** the same eight responses pass v5.1 after originally scoring7/8
- **THEN** both scores remain reported under their own policy identities
- **AND** the original prospective failure and closed execution authority remain intact

### Requirement: A guarded pilot proposal does not enable application behavior

A pilot proposal SHALL require existing strict validation and explicit user
confirmation before authoritative writes, start with synthetic inputs and keep
invalid/ambiguous items blocked. It SHALL require separate implementation approval
and SHALL NOT enable inference, personal-data use, application changes, deployment
or production admission merely because an offline checker was corrected.

#### Scenario: Proposal is delivered after offline correction
- **WHEN** the guarded pilot is documented
- **THEN** it states scope, confirmation/rejection boundaries and acceptance checks
- **AND** no application or runtime operation is performed

### Requirement: The approved interaction pilot is isolated and synthetic only

The explicitly approved pilot SHALL run only through an opt-in loopback harness
with a fresh private disposable store and bundled handwritten synthetic fixtures.
It SHALL NOT construct model/provider, personal-storage, ingestion or scheduling
services, forge execution receipts or change normal app/runtime admission. Normal
application behavior SHALL remain unchanged.

#### Scenario: A new pilot session starts
- **WHEN** the opt-in harness is launched with its dedicated authentication setup
- **THEN** it creates only new private synthetic storage and serves a separate UI
- **AND** it does not open an existing application store or acquire another service

#### Scenario: An unrelated or arbitrary source operation is requested
- **WHEN** a caller requests a non-allowlisted API or creates arbitrary intake text
- **THEN** the pilot rejects the operation without model calls or storage crossover

### Requirement: Pilot fixture suggestions have honest provenance and strict binding

Pilot suggestion inspection SHALL return only strictly validated drafts bound to
the exact frozen source identity/revision/content, with explicit handwritten-fixture
provenance rather than execution/publication identity. Invalid, stale or mismatched
pairs SHALL be unavailable. Inspection SHALL NOT stage, confirm or write any
authoritative item.

#### Scenario: A synthetic source is corrected
- **WHEN** its current revision or content no longer matches the bundled fixture
- **THEN** the old fixture draft is unavailable and unconfirmed UI state is discarded
- **AND** no regenerated or repaired draft is substituted

### Requirement: Pilot authority requires explicit reviewed confirmation

The pilot SHALL keep inspected, edited and staged suggestions non-authoritative.
Unknown fields SHALL block UI staging until deliberately resolved and acknowledged.
Only explicit confirmation of visible selected items SHALL write authoritative
synthetic items, retaining existing auth/origin/CSRF, revision, atomicity and replay
checks. Validation SHALL NOT be presented as independent factual truth.

#### Scenario: A user inspects and stages a fixture
- **WHEN** the user inspects, edits or stages a draft without confirming
- **THEN** no authoritative context, commitment or confirmation receipt is created
- **AND** invalid times, recurrence or unacknowledged unknowns remain blocked

#### Scenario: Confirmation is uncertain or repeated
- **WHEN** confirmation outcome is unknown, stale or replayed
- **THEN** uncertainty requires reload rather than automatic retry, stale input
  conflicts and identical confirmation identity/body cannot create duplicate items
