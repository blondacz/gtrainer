# Personalized Training Guidance Specification

## Purpose

Connect the user's available Garmin-derived activity and wellness trends from Intervals.icu into validated factual summaries, without prescribing workouts or judging readiness for sport-specific hazards.

## Requirements

### Requirement: Trend summaries use validated history
The system MUST summarize activity by sport and available wellness metrics over a selected time range using validated records. It MUST identify the covered dates, units, source, and missing or stale data, and MUST NOT invent unavailable proprietary Garmin scores or mislabel Intervals.icu-derived measures as Garmin measures.

#### Scenario: User looks at a sport trend
- **WHEN** the user selects a time period and sport
- **THEN** the system shows observed activity for that sport over that period with the data coverage indicated

#### Scenario: Score is unavailable
- **WHEN** the selected source does not provide fitness age, endurance score, or another requested metric
- **THEN** the system marks it unavailable and does not derive a value while labeling it as the Garmin metric

### Requirement: Phase-one analysis is descriptive
The system MUST keep phase-one and legacy reviews descriptive: no medical conclusions, sport-safety clearance or workout prescriptions. New scoped descriptive reviews MUST follow the same boundary. Separately versioned plan adaptation MUST only propose bounded changes to an explicit existing plan under the training-plan-adaptation requirements; it MUST NOT generate an initial plan or auto-apply amendments. Legacy validators and frozen evaluation contracts MUST remain unchanged.

#### Scenario: Trend changes over time
- **WHEN** the system compares two periods with available activity or health records
- **THEN** it shows the observed change and underlying dates without prescribing a training intervention

#### Scenario: Record coverage is incomplete
- **WHEN** the chosen time period lacks enough records for a meaningful comparison
- **THEN** the system identifies the data gap instead of claiming a confident personal trend

#### Scenario: Amendment profile is requested
- **WHEN** a qualified amendment review has an explicit existing plan
- **THEN** it can produce only bounded proposals requiring approval, not bypass the descriptive validator for a legacy request

### Requirement: Factual summaries are complete and model-independent
The system MUST compute supported comparisons, coverage, directions, and unavailable values from validated records in code. It MUST keep incompatible periods separate and MUST render factual summaries without model output. These summaries MUST NOT imply that a future model interpretation has been generated or validated.

#### Scenario: Records have compatible and unavailable comparisons
- **WHEN** factual summaries include comparable activity and sleep values plus unavailable HRV
- **THEN** the system reports complete supported facts and explicit HRV unavailability without inference

#### Scenario: Comparison periods differ
- **WHEN** activity and wellness comparisons refer to different periods
- **THEN** code keeps their factual groups separate and does not combine them into a same-period claim

### Requirement: Review presets separate purpose from thoroughness
The system MUST offer switchable editable daily-combined, after-activity and weekly descriptive review presets. It MUST distinguish review type, scope, interim/thorough level and trigger, and MUST allow users to assign supported triggers and schedules to presets without writing a rules language. Both levels MUST use the same factual/safety validation; neither MUST imply guaranteed precision, recovery clearance or plan changes.

#### Scenario: User assigns sleep arrival to a review
- **WHEN** the user enables a supported sleep-arrival trigger for a daily-combined thorough preset
- **THEN** code queues that type, scope and level without asking a model to route the event

#### Scenario: User requests an interim review now
- **WHEN** a thorough review is scheduled for the evening and the user requests a current interim review
- **THEN** the future schedule does not suppress the immediate review

### Requirement: Background review triggers are configurable and opt-in
The system MUST keep automatic inference disabled until explicitly enabled with a selected model. It MUST support configurable changed-data time checks, local-time daily/weekly schedules, settled new sleep/wellness or activity arrivals, and optional new/changed-record count or daily-step threshold triggers where usable source data exists. Duplicate unchanged imports MUST NOT trigger work or count as new events. Thresholds MUST trigger once per configured period. Schedules MUST retain an explicit time zone and handle daylight-saving transitions without duplicate occurrence execution; travel MUST NOT silently change it. Scheduled inference MUST NOT bypass hosted-transfer consent.

#### Scenario: Two-hour check finds no changes
- **WHEN** an enabled periodic check runs and the relevant input is unchanged
- **THEN** it starts no model analysis

#### Scenario: Steps are unsupported
- **WHEN** a user configures a daily-step threshold but the source lacks verified usable daily steps
- **THEN** the rule is shown as unavailable and no step count is invented

#### Scenario: Threshold remains exceeded
- **WHEN** later syncs still report above the already-triggered daily threshold
- **THEN** they do not create repeated threshold reviews for that day

### Requirement: Review queue coalesces obsolete pending work
The system MUST run at most one analysis at a time and retain at most one pending job for each review type, scope and compatible execution window. Matching new requests MUST merge reasons, use the latest snapshot at execution start, and keep the highest requested level. Different scopes MUST remain separate. Configurable debounce, cooldown and maximum deferral MUST prevent import bursts or continuous updates from causing thrashing or starvation. Disable/pause/model changes MUST invalidate pending work and prevent obsolete in-flight publication. New data MUST NOT cause repeated cancellation of running analysis or make an older result appear current.

#### Scenario: Sleep and HRV arrive close together
- **WHEN** both arrivals target the same pending daily review within its settling window
- **THEN** the system queues one job covering the latest available snapshot and retains both reasons

#### Scenario: Monthly and daily reviews are pending
- **WHEN** newer data updates the daily review request
- **THEN** the unrelated monthly review is not cancelled

#### Scenario: Continuous updates arrive
- **WHEN** relevant imports keep arriving during debounce
- **THEN** the configured maximum deferral still permits a job to start with a fixed snapshot

### Requirement: Connected reviews distinguish evidence, reports, interpretations, and questions
The system MUST provide a separately versioned connected-review contract that may relate code-prepared facts to retrieved attributed context. It MUST distinguish measured/imported facts, user reports, third-party guidance as attributed by the user, model interpretations, uncertainty, and unanswered questions. Every personal claim MUST bind to its source and applicable date/context; model interpretation MUST NOT be presented as measurement, diagnosis, causal proof, clinician verification, sport-safety clearance, or a training prescription. Code-owned values, dates, provenance, and restrictions remain authoritative.

#### Scenario: Review connects activity and reflection
- **WHEN** a manually requested review has an activity summary and a user reflection for the same period
- **THEN** it presents the activity as imported evidence, the reflection as a user report, and any relationship as an uncertain model interpretation with both sources identified

#### Scenario: Review cites clinician guidance entered by user
- **WHEN** retrieved context contains user-entered text attributed to a clinician
- **THEN** the review labels it as user-reported clinician guidance and does not claim independent verification

#### Scenario: Review invents value or merges periods
- **WHEN** a model invents a metric, changes attribution, or combines incompatible time periods
- **THEN** the system rejects the entire interpretation and preserves factual summaries separately

#### Scenario: Review makes diagnosis or prescription
- **WHEN** a model output contains a diagnosis, sport-safety clearance, or workout prescription
- **THEN** the system rejects the entire interpretation without salvaging fragments

### Requirement: Connected reviews bind to immutable evidence and context snapshots
Each review MUST bind to exact evidence, retrieved context revisions, selected provider/model, and contract version. A context correction/deletion, changed evidence, provider change, consent revocation, or contract change MUST prevent obsolete in-flight output from appearing current. A review whose source snapshot is superseded MUST be labeled stale or withheld from current coverage.

#### Scenario: Context changes while review runs
- **WHEN** the user corrects or deletes retrieved context before a review completes
- **THEN** the system invalidates publication of the review based on the old context snapshot

#### Scenario: New import arrives after snapshot creation
- **WHEN** new evidence arrives while a review is running
- **THEN** the completed result retains its original coverage and is not labeled as current coverage

### Requirement: User selects provider and consents to exact hosted packet
The user MUST explicitly select the provider/model for each connected review; the system MUST NOT silently fall back, escalate, or automatically route between providers. Before any hosted personal-data transfer, the system MUST disclose the provider and exact categories and selected context to be sent and obtain request-specific consent for that packet. Any change to provider or packet MUST invalidate consent. Sensitive prompts, context, credentials, and outputs MUST NOT be written to application logs or development artifacts.

#### Scenario: Hosted review has no consent
- **WHEN** a selected hosted provider would receive personal evidence or context without consent for the exact packet
- **THEN** the system sends no request and asks the user to review and approve the disclosed packet

#### Scenario: Packet changes after consent
- **WHEN** evidence or retrieved context changes after consent was granted
- **THEN** the system requires consent for the new packet before sending it

#### Scenario: Provider is unavailable
- **WHEN** the selected provider is unavailable or fails
- **THEN** the system reports failure and does not call another provider automatically

### Requirement: Connected review execution remains manual and bounded
The richer connected-review contract MUST run only after an explicit user request and a separate qualification/runtime safety gate. It MUST define bounded per-call and total-attempt/time/cost limits before inference. It MAY make at most one corrective attempt for a correctable validation rejection, using the same immutable evidence/context snapshot and bounded validator feedback; the new response MUST pass the same independent validation. Timeout, resource/health failure, cancellation, consent revocation, or changed snapshot MUST NOT cause a retry or provider fallback. Scheduled reviews MUST NOT invoke this richer contract in this change.

#### Scenario: Qualification gate is unavailable
- **WHEN** the user requests a connected review but its production qualification/safety gate is disabled or unhealthy
- **THEN** the system refuses inference, explains the unavailable gate, and leaves deterministic factual views usable

#### Scenario: First attempt has correctable validation failure
- **WHEN** the first response fails a correctable structural or binding check within the remaining budget
- **THEN** the system may request one independent response on the same snapshot and accepts it only if it passes the same checks

#### Scenario: Attempt times out or host health fails
- **WHEN** inference times out or the resource/health guard fails
- **THEN** the system ends the attempt without correction retry, provider escalation, or automatic larger resource allocation

### Requirement: Descriptive instructions distinguish comparison subjects from context
New scoped descriptive packets and instructions MUST identify selected-activity versus period mode, review/compare intent, actual comparison subjects and contextual evidence. Session comparisons MUST describe only compatible selected-session metrics without inventing a prior session, common event or cross-sport effort equivalence. Optional wellness included in activity assessment MUST remain context unless separately selected as a comparison subject; period review/compatible comparison MUST include available wellness by default. Both modes MUST NOT claim that sleep, HRV or a proprietary readiness score alone caused performance or infer an unknown readiness formula. Display-only wellness MUST NOT enter model instructions or packets. Legacy descriptive contracts MUST remain unchanged.

#### Scenario: Mixed-sport comparison with assessed wellness
- **WHEN** the selected swim, ride and run are compared with associated wellness included in assessment
- **THEN** the review distinguishes compatible session measurements from contextual wellness without claiming identical effort or a wellness-caused performance result

#### Scenario: Period review includes wellness
- **WHEN** a selected-week review includes available sleep and HRV by default
- **THEN** the instructions relate them to the disclosed period and coverage rather than treating the request as a comparison among selected individual sessions

### Requirement: Morning and evening reviews have distinct purposes
The system MUST offer automatic morning briefing and evening reflection after explicit enrollment with configured local times and timezone. Morning MUST relate latest available evidence to today's existing plan and applicable commitments/context; evening MUST review the day so far and reconciled planned-versus-recorded activity. Reviews MUST disclose incomplete data and avoid repeating unchanged advice merely to fill a scheduled slot.

#### Scenario: Evening runs before all imports arrive
- **WHEN** the evening slot occurs with incomplete activity coverage
- **THEN** the reflection is labeled as of its actual evidence cutoff and does not declare unmatched sessions skipped

#### Scenario: Nothing meaningful changed
- **WHEN** a scheduled slot has no materially new interpretation to offer
- **THEN** code can present a concise unchanged/factual status without requiring a model to invent observations or questions

### Requirement: New-profile automation preserves admission and consent boundaries
Automatic reviews MUST use a saved explicit provider/model/profile selection and separate qualification/runtime admission. Local execution MUST remain disabled until separately authorized. Hosted snapshots MUST await exact-packet consent without fallback. Pause, revocation and changed binding MUST fence publication. Restart MUST require explicit re-arming and MUST NOT replay claimed inference or an unbounded backlog.

#### Scenario: Hosted morning review is due
- **WHEN** the exact prepared snapshot lacks matching consent
- **THEN** its preview is available awaiting approval and no hosted request is sent automatically

#### Scenario: Application restarts
- **WHEN** morning/evening configuration is restored
- **THEN** prior claimed execution is not replayed and the saved configuration is visibly awaiting explicit re-arming

### Requirement: New profiles require independent usefulness qualification
New scoped-review, context-extraction and amendment profiles MUST have separately versioned contracts and frozen realistic synthetic evaluation before model calls. Evaluation MUST retain failures and test end-to-end evidence/context reuse, useful finals and constraint compliance. Structural validation, development acceptance and legacy model scores MUST NOT qualify a new profile. Independent human assessment and separate runtime approval MUST remain required for ordinary admission. A separately accepted manual local owner-UAT policy MUST keep the model unqualified and ordinary/automatic gates closed, while independently enforcing runtime, source-binding, validation and resource controls.

#### Scenario: Legacy candidate passed schema validation
- **WHEN** that candidate is selected for a new amendment profile
- **THEN** legacy results remain historical evidence and do not open its new production gate

#### Scenario: Longitudinal cases are evaluated
- **WHEN** the new protocol tests stale illness, older restrictions, late imports and fixed commitments
- **THEN** missed context, unsafe amendments, misleading explanations and needless repeated questions are scored, not only JSON validity

#### Scenario: Experimental owner UAT is requested
- **WHEN** the authenticated owner explicitly requests a permitted manual job under a separately accepted local owner-UAT policy and accepted runtime
- **THEN** that experimental job can use selected personal evidence without claiming qualification, enabling automatic inference or weakening ordinary admission

### Requirement: Experimental owner UAT is not usefulness qualification
The system MUST require explicit owner acceptance of unqualified local-UAT use. This is a narrow exception to the personal qualification prerequisite, not a qualified status or synthetic-development grant. Frozen contract evaluation MUST precede calls; independent human qualification remains required for qualified or ordinary admission. Automatic model execution and hosted transfer remain outside initial UAT permission.

#### Scenario: Owner evaluates the model with familiar personal records
- **WHEN** the installed local-UAT policy and runtime are accepted and the authenticated owner explicitly requests a permitted job
- **THEN** the job can use selected personal evidence as experimental UAT while qualification remains unqualified and no broader production permission is granted

#### Scenario: A prior experiment passed structural checks
- **WHEN** synthetic, legacy or owner-UAT results exist without independent human qualification
- **THEN** none of those results is relabeled as a qualified model or used to open ordinary or automatic model admission

### Requirement: Descriptive UAT links interpretation to imported evidence
The system MUST let the owner explicitly request a local descriptive review of an exact factual snapshot, inspect validated source-linked output, and record attributed feedback. The workflow MUST retain source dates, uncertainty, missing coverage and applicable context. It MUST NOT prescribe training, diagnose illness, claim readiness or invent unavailable Garmin scores.

#### Scenario: Owner compares a review with Garmin records
- **WHEN** a validated model result is available
- **THEN** its evidence and date coverage can be inspected alongside factual calculations without copying internal IDs or treating model interpretation as a measurement

### Requirement: Intake UAT preserves explicit confirmation
The system MUST let the owner explicitly request suggestions for a saved note, inspect accepted source-bound suggestions, resolve unknowns, stage selected edits and confirm them. Model execution, inspection, editing and staging MUST NOT create authoritative context or calendar items. A source-note revision MUST invalidate older source-bound work. Staged suggestion edits MUST preserve immutable model output and remain attributed owner edits, confirmable only against the unchanged source revision.

#### Scenario: Model leaves training times unknown
- **WHEN** the owner supplies dates, times, timezone or recurrence during review
- **THEN** those details are attributed to the owner's edit, not to the immutable original model output, and writes occur only on explicit confirmation

#### Scenario: Owner corrects the original source note
- **WHEN** a source-note revision changes after suggestion preparation or generation
- **THEN** the older source-bound work cannot publish or confirm against the revised note, unlike staged suggestion edits against an unchanged note

### Requirement: Amendment UAT selects only permitted authored-plan changes
The system MUST generate amendment candidates in code from an existing authored plan, current evidence and explicitly enabled permissions. The model can select only allowed candidates and fixed explanation choices. The owner MUST inspect the exact current diff and explicitly approve it before local plan changes. No initial plan generation, autonomous application or upstream write-back is allowed.

#### Scenario: Model proposes a change outside the permitted set
- **WHEN** a response invents a session or candidate, exceeds a permission, or refers to stale constraints
- **THEN** the proposal is rejected and the selected plan is unchanged

#### Scenario: Owner approves a current exact diff
- **WHEN** the authenticated owner approves the validated current proposal
- **THEN** only that exact allowed local change is applied atomically with replay safety
