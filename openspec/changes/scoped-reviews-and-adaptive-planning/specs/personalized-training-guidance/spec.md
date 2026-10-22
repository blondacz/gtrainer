# Spec Delta

## MODIFIED Requirements

Frozen model packets, prompts, validators, qualification fixtures/captures and ledger remain historical profile evidence; this preservation does not require old factual/prototype HTTP routes or model-selection interfaces as runtime compatibility.

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

## ADDED Requirements

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
New scoped-review, context-extraction and amendment profiles MUST have separately versioned contracts and frozen realistic synthetic evaluation before model calls. Evaluation MUST retain failures and test end-to-end evidence/context reuse, useful finals and constraint compliance. Structural validation, development acceptance and legacy model scores MUST NOT qualify a new profile; independent human assessment and separate runtime approval MUST remain required.

#### Scenario: Legacy candidate passed schema validation
- **WHEN** that candidate is selected for a new amendment profile
- **THEN** legacy results remain historical evidence and do not open its new production gate

#### Scenario: Longitudinal cases are evaluated
- **WHEN** the new protocol tests stale illness, older restrictions, late imports and fixed commitments
- **THEN** missed context, unsafe amendments, misleading explanations and needless repeated questions are scored, not only JSON validity
