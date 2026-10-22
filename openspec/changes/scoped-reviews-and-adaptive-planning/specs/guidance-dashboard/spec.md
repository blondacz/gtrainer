# Spec Delta

## ADDED Requirements

### Requirement: Scoped preparation uses one shared control and evidence card
The dashboard MUST reuse one frontend control/form and evidence card in Trends and Reviews. Overview factual tiles MUST use the same preparation client/parser and server-resolved dates, with navigation to detailed reporting rather than a duplicate reporting protocol. The dashboard MUST NOT add a separate prototype panel or provider-selection UI for scoped preparation. Preparation MUST remain factual and must not invoke inference.

#### Scenario: User prepares a scoped review from different views
- **WHEN** the user opens scoped controls from Trends or Reviews, or reads overview factual tiles
- **THEN** all preparation uses the same client/parser; detailed controls reuse the same form/evidence card and overview retains navigable numeric summaries

#### Scenario: User prepares facts without inference
- **WHEN** the user submits a scoped preparation request
- **THEN** the evidence is prepared without provider selection or inference, while existing guarded execution remains a separate path

### Requirement: Dashboard exposes familiar scopes and their evidence
The dashboard MUST offer selected-activity and selected-period modes with review/compare intent, named calendar and rolling scopes, selected day/night and custom dates with exact boundaries, comparison meaning and so-far labels. It MUST distinguish historical evidence, personal baselines and future plan context. Activity mode MUST offer optional wellness off/display-only/include-in-assessment with activity metrics as the default comparison subjects; period mode MUST include available wellness by default with visible inclusion/coverage. Users MUST be able to distinguish displayed context from model-used evidence and compared values, and inspect contributing records and source limitations without inference. Unavailable readiness and unverified preceding sleep MUST NOT be represented as supported measurements.

#### Scenario: Calendar month versus rolling month
- **WHEN** the user switches from Calendar month to Last 30 days
- **THEN** the dates and comparison labels change explicitly rather than retaining an ambiguous month label

#### Scenario: Activity comparison with optional context
- **WHEN** the user selects several activities and enables display-only wellness
- **THEN** the dashboard identifies the selected sessions as comparison subjects and the wellness as displayed context excluded from the model packet

#### Scenario: User switches to a period review
- **WHEN** the user switches from activity selection to a selected-period review
- **THEN** the target, review/compare intent, wellness inclusion and comparison meaning are explicitly shown rather than silently treating the prior IDs as a period selection

### Requirement: Dashboard provides shared context intake and status updates
The dashboard MUST let the user enter notes once, inspect suggested structured items and confirm/correct them without retyping. It MUST expose unresolved, needs-review, resolved and historical states and shared calendar associations. It MUST separate storing context, disclosing it to a model and approving a plan amendment.

#### Scenario: Targeted illness update
- **WHEN** saved illness context needs review
- **THEN** the user can update that entry rather than entering the complete history again

### Requirement: Automatic review status and pending consent are visible
The dashboard MUST distinguish morning/evening schedules, evidence preparation, queued/running review, awaiting hosted consent, blocked admission and factual-only results. It MUST show timezone, next eligible occurrence, pause/re-arm controls and actual input cutoff. Automatic source freshness MUST NOT be implied by a scheduled review.

#### Scenario: Gate remains closed
- **WHEN** a morning slot is configured without qualified execution
- **THEN** the dashboard shows unavailable inference while keeping prepared facts and missing-data status usable

### Requirement: Amendment approval displays the exact local plan diff
The dashboard MUST show affected sessions, before/after values, evidence, constraints, uncertainty, expiry and base-plan revision before approval. No-change, needs-confirmation, stale, rejected and applied states MUST be distinct. Approval MUST be an explicit action; calendar projections MUST NOT represent a proposed edit as applied.

#### Scenario: Pending proposal appears in calendar
- **WHEN** an amendment is pending approval
- **THEN** it is visibly a draft overlay and the active plan remains unchanged

#### Scenario: Proposal became stale
- **WHEN** its source plan or relevant evidence changes
- **THEN** approval is unavailable with an explanation instead of silently applying a revised diff
