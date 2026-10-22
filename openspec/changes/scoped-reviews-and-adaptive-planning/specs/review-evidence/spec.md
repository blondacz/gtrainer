# Spec Delta

## Purpose

Assemble relevant authorized training evidence and reusable personal context into inspectable, bounded snapshots so users can ask questions without preparing screenshots or repeating their history.

Current-schema validation and read-only restore-manifest validation are implementation checks; neither implies legacy database migration, automatic reset, or actual restore.

## ADDED Requirements

### Requirement: One canonical factual preparation API
The system MUST expose factual review preparation through `GET /api/reviews/options` and `POST /api/reviews/prepare`. Preparation requests MUST identify mode, intent, scope, wellness policy and factual focus through explicit fields or documented defaults; responses MUST include prepared facts, charts and provenance. The previous `/api/trends`, `/api/analysis-input`, `/api/review-facts`, `/api/scoped-trends/v1`, prototype `/api/models` and `/api/analysis`, and orphan manual `/api/review-interpretation` routes MUST be retired without aliases or compatibility adapters. `/api/review-now` and existing qualified, guarded execution remain available. Factual preparation MUST NOT invoke inference.

#### Scenario: Scoped preparation is requested
- **WHEN** an authenticated client requests options and prepares a scoped review with explicit fields
- **THEN** it receives factual prepared evidence, charts and provenance from the canonical routes without model/provider selection or inference

#### Scenario: Retired prototype route is requested
- **WHEN** a client calls a retired factual/prototype route
- **THEN** the route is not retained as an alias or compatibility adapter

### Requirement: Reviews assemble evidence without repeated user entry
For a selected scope, intent and question, the system MUST assemble relevant available activity, daily wellness under the selected inclusion policy, applicable context, confirmed commitments and selected base-plan evidence. Availability alone MUST NOT cause every category to be included. It MUST preserve source identities, units, dates, attribution and missing-data limitations. Review purpose, question and additional free text MUST remain separate from instructions and MUST NOT silently create persistent memories.

#### Scenario: User asks about several activities
- **WHEN** the user selects activities and asks a question with an applicable saved restriction
- **THEN** the snapshot includes available session facts and that restriction without requiring screenshots or repeated context entry

#### Scenario: Morning without new sleep data
- **WHEN** a morning briefing is due but no usable recent sleep record exists
- **THEN** the snapshot identifies missing or stale sleep and includes the available plan/context without inventing poor sleep

### Requirement: Wellness usage follows the selected review mode
Activity selection MUST default to activity comparison subjects with optional wellness off; users MUST be able to display associated wellness only or explicitly include it in assessment. Display-only wellness MUST remain outside provider packets. Including wellness in assessment MUST NOT automatically make it a session-comparison subject. Activity context MUST associate available preceding sleep and day HRV/readiness only under verified source semantics, excluding intervening dates from selected evidence and retaining longer baselines separately. Period selection MUST include relevant available wellness in review/compatible period comparison by default, with inspectable inclusion and coverage. Shared observations MUST retain one evidence identity and MUST NOT acquire duplicate weight when displayed beside several sessions. Mandatory applicable restrictions MUST remain required independently of optional wellness choices.

#### Scenario: Wellness displayed beside compared activities
- **WHEN** the user compares two runs and selects display-only associated wellness
- **THEN** verified available sleep/HRV/readiness may appear beside each run, but provider packets and comparison subjects contain no display-only wellness

#### Scenario: Wellness included in activity assessment
- **WHEN** the user explicitly includes associated wellness in assessment of selected activities
- **THEN** the packet contains that identified contextual evidence without automatically comparing the wellness measurements between sessions or including intervening-day wellness

#### Scenario: Period review uses default wellness
- **WHEN** the user reviews a selected week without overriding wellness inclusion
- **THEN** available relevant daily wellness accompanies period activity facts, with actual dates, source limitations and missing coverage visible

#### Scenario: Several activities share one day observation
- **WHEN** a swim, ride and run share the same verified daily HRV observation
- **THEN** the views may reference it beside each session but aggregation and baseline input count it only once

### Requirement: Source capabilities constrain evidence promises
The system MUST expose verified supported evidence categories and fields. Initially it MUST reuse imported activity/wellness and confirmed local plan/commitment records. External plan/calendar reads MUST remain unavailable until an explicitly approved adapter and its source semantics are verified. No review request or inspection MUST itself initiate an upstream read or write.

#### Scenario: External calendar not connected
- **WHEN** the user requests a review involving calendar commitments without a verified calendar adapter
- **THEN** confirmed local commitments are used and external calendar coverage is labeled unavailable, not empty

#### Scenario: Detailed stream unsupported
- **WHEN** an activity question requires lap, power or heart-rate stream evidence not supplied by the adapter
- **THEN** the review identifies that limitation rather than fabricating interval analysis from average heart rate

#### Scenario: Prior-night association is unverified
- **WHEN** the adapter supplies a dated sleep value without verified night-assignment semantics
- **THEN** the source-date observation can be disclosed as such, but neither selected-night reporting nor activity context labels it preceding sleep or known pre-activity evidence

#### Scenario: Training readiness not supplied
- **WHEN** the verified adapter does not supply training readiness
- **THEN** its status remains unavailable and sleep/HRV are not combined into a substitute readiness score

### Requirement: Evidence snapshots are immutable and inspectable
Each review MUST bind scope/selection mode, review/compare intent, comparison relationship and metric subjects, wellness-assessment policy and association provenance, question, evidence digest, context revisions, commitment and plan revisions, deterministic reasons, profile, provider/model and evaluation instant. Users MUST be able to inspect included, excluded, display-only, missing and needs-confirmation items without inference. Changes to model-visible inclusion MUST invalidate exact-packet consent; display-only inspection MUST NOT authorize transfer. Corrections/deletions MUST invalidate dependent in-flight publication and stale dependent output without erasing unrelated evidence.

#### Scenario: Constraint changes during execution
- **WHEN** a used context revision changes before publication
- **THEN** output cannot appear current and a dependent amendment cannot be approved

#### Scenario: Read-only inspection
- **WHEN** the user refreshes an evidence or result view
- **THEN** no sync, model submission, maintenance or amendment is initiated

#### Scenario: Display-only wellness becomes assessment evidence
- **WHEN** the user changes optional wellness from display-only to include-in-assessment after consenting to a hosted packet
- **THEN** the changed packet requires new exact-packet consent before transfer

### Requirement: Evidence remains bounded without hiding constraints
The system MUST retain existing prompt/response and record budgets unless separately changed. Annual reviews MUST use code-owned aggregation with coverage and drill-down; every applicable mandatory restriction MUST remain included. Optional omissions MUST be disclosed. Mandatory overflow or unavailable required evidence MUST block the affected operation, not silently truncate or increase budgets.

#### Scenario: Annual evidence exceeds prompt budget
- **WHEN** full per-session detail cannot fit
- **THEN** bounded aggregates and disclosed optional-detail omissions are used, with original records available through authenticated drill-down

#### Scenario: Mandatory context cannot fit
- **WHEN** mandatory restrictions exceed the packet budget
- **THEN** no provider call is sent and factual reporting remains available
