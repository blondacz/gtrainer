# Spec Delta

## ADDED Requirements

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
