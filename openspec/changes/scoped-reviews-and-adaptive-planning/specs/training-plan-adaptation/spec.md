# Spec Delta

## Purpose

Review deviations from an existing user/coach-authored training plan and propose bounded, traceable amendments that can only become local plan changes after explicit approval.

## ADDED Requirements

Plan and evidence preparation use the current history schema as the sole fresh-database schema; this capability does not require a historical database migration chain or HTTP compatibility adapters.

### Requirement: Adaptation requires an explicit versioned base plan
The system MUST retain authored plan/session identities, revisions, provenance, planned dates and supported metric ranges. Users MUST explicitly select the base plan and amendment permissions. A goal, event or model review MUST NOT become a plan. Local entry MUST remain available; unsupported external plan reads MUST be labeled unavailable. Initial plan generation and external write-back MUST remain outside this capability.

#### Scenario: Only an event exists
- **WHEN** the user has an upcoming event but no selected base plan
- **THEN** descriptive reviews remain available and adaptation reports that an authored base plan is required

### Requirement: Planned sessions and recorded activity are reconciled explicitly
The system MUST distinguish matched activity, ambiguous matching, provisional unmatched sessions, confirmed skipped sessions and extra recorded activity. Matching MUST use stable identities or inspectable compatible evidence, not model guesses. Missing records alone MUST NOT prove skipped training. Late imports/corrections MUST revise provisional findings, and user confirmation MUST retain attribution.

The initial matching policy MUST be one recording per session. Automatic matching
MUST require exact sport, verified start within the authored half-open start/end
interval (the session-zone civil day for date-only sessions), and mutual uniqueness
across the whole selected plan. Unknown timing MUST NOT become an automatic match;
an explicit authenticated stable-identity link MUST retain its unverified timing.
Corrections to the linked record or plan revision MUST require reconfirmation.

#### Scenario: Recording begins later within the authored window
- **WHEN** a same-sport recording starts at 09:15 within an authored 09:00–10:00 session and neither identity has another candidate
- **THEN** it matches without inventing an exact-start tolerance, while a recording starting at 10:00 is outside that interval

#### Scenario: Split or competing recordings are present
- **WHEN** several recordings are candidates for one session or one recording is a candidate for several sessions
- **THEN** the match stays ambiguous until explicitly confirmed, without combining recordings or silently stealing an existing explicit link

#### Scenario: Sync is delayed
- **WHEN** a scheduled session has ended and its configured import grace period has passed without a match
- **THEN** it is provisionally unmatched, not confirmed skipped, and missing source coverage is disclosed

#### Scenario: Late activity arrives
- **WHEN** an activity matches a provisionally unmatched session
- **THEN** the finding is revised and dependent pending reasons/proposals become stale or are withdrawn

#### Scenario: Extra activity is recorded
- **WHEN** an activity has no match in the selected plan
- **THEN** it is identified as extra relative to that plan, not necessarily unintended

### Requirement: Planned-range deviations use compatible explicit metrics
The system MUST evaluate harder/easier deviations only against an explicit planned metric, unit, measurement basis and acceptable range. Duration, intensity, load and reported effort MUST remain distinct. Unsupported measures MUST remain unevaluable. Each finding MUST expose planned/actual values, threshold policy and supporting evidence; the model MUST NOT invent a tolerance or classify effort from unrelated metrics.

#### Scenario: Moving time and elapsed time differ
- **WHEN** a session specifies moving duration and only elapsed duration is available
- **THEN** duration deviation is unavailable unless an explicitly approved compatible basis exists

#### Scenario: Reported effort exceeds range
- **WHEN** a dated user effort report uses the same defined scale as the session's allowed range
- **THEN** the deviation is detected as reported effort, not a measured physiological fact

#### Scenario: Reported effort survives removed imports
- **WHEN** the user removes imported evidence after explicitly recording effort
- **THEN** original report history remains detached and inactive, can be inspected or withdrawn without that recording, and an identical reimport does not reactivate it without explicit reaffirmation

#### Scenario: An import correction changes the report's evidence
- **WHEN** any normalized field or receive revision changes for a recording with a saved effort report
- **THEN** its effort comparison remains unevaluable until explicitly reaffirmed, without inferring effort from HR, training load or free text

#### Scenario: Import-derived matching histories are removed
- **WHEN** the user explicitly removes local imported evidence
- **THEN** factual review copies and complete confirmation histories that ever linked recordings are purged, without exposing an earlier skip head or deleting authored plans, original testimony or unresolved runtime ownership

### Requirement: Personal recovery rules are configurable and data-gated
Sleep/HRV rules MUST bind metric/source identity, baseline window, minimum usable coverage, freshness, trigger and reset conditions, persistence and cooldown. They MUST remain disabled until explicitly configured. Insufficient, mixed-method, unknown required provenance or stale data MUST produce unevaluable status. No universal medical cutoff, vendor-score equivalence, diagnosis or recovery clearance MUST be inferred.

#### Scenario: HRV methods differ
- **WHEN** candidate data mix incompatible HRV methods or unresolved origins
- **THEN** the rule does not average them into a baseline or trigger on that comparison

#### Scenario: Persistent compatible deviation
- **WHEN** fresh compatible measurements satisfy the configured magnitude and persistence conditions
- **THEN** code emits a traceable review reason, not an automatic training reduction

#### Scenario: Verified reset and retrigger occur during cooldown
- **WHEN** a previously emitted episode is verifiably reset and a new episode qualifies before cooldown expires
- **THEN** that new episode waits and can request review only after expiry with current compatible data still satisfying trigger persistence; an unchanged continuous episode never repeats solely because cooldown expires

#### Scenario: Configuration cannot verify the source
- **WHEN** the user configures desired measurement identity but imported method, basis or verification is unknown
- **THEN** the rule remains unevaluable rather than treating selection as source verification

### Requirement: Triggered reviews are material and non-duplicating
Confirmed skipped sessions, materially extra activity, supported planned-range deviations, configured recovery signals and changed restrictions/availability MUST request review under explicit rules. A trigger MUST NOT require a changed plan. Compatible reasons MUST coalesce by base-plan/evidence revision and horizon; unchanged imports, overlapping scopes and morning/evening routing MUST NOT repeatedly generate the same amendment review.

#### Scenario: Two recovery signals arrive together
- **WHEN** related sleep and HRV conditions target the same plan revision and horizon
- **THEN** one pending adaptation review retains both reasons without cancelling a healthy running review

#### Scenario: Plan remains appropriate
- **WHEN** review finds no justified permitted amendment
- **THEN** it records no change with rationale instead of manufacturing a plan edit

### Requirement: Amendment proposals contain only bounded allowed operations
Initial proposals MUST only move an existing future session within its permitted window, reduce its planned duration within explicit bounds, or mark it omitted with audit history. Operations MUST preserve commitments and all applicable restrictions, whether cited by the model or not. New workouts, increased intensity/volume, substitutions, rehabilitation progression and unrestricted plan replacement MUST be rejected.

#### Scenario: Proposal omits a restriction citation
- **WHEN** an operation violates an applicable restriction that its explanation does not cite
- **THEN** validation still rejects the entire proposal

#### Scenario: Missed session tempts compensatory workload
- **WHEN** a response proposes adding a make-up session or increasing future volume
- **THEN** the operation is rejected rather than treated as an allowed amendment

#### Scenario: Boundary-only enumeration finds no alternative
- **WHEN** initial enumeration tests authored move endpoints, permitted duration-result boundary clamps and explicit omission without finding an admissible candidate
- **THEN** it discloses boundary-only coverage, does not claim exhaustive search or a no-change decision, and does not invoke inference; candidate/work/byte overflow exposes no partial executable set

#### Scenario: A timed move changes DST occupancy
- **WHEN** a destination cannot preserve both the authored local-time span and elapsed occupancy, or needs an unconfirmed ambiguous offset
- **THEN** the affected alternative needs clarification rather than inventing a time or changing occupancy

#### Scenario: Constraint execution meaning is unspecified
- **WHEN** an affected candidate depends on an unspecified allowed-activity/sport interpretation, activity classification, maximum-duration basis or availability exclusivity
- **THEN** it needs clarification rather than treating the unknown as permission; defined prohibitions and confirmed busy commitments remain binding

### Requirement: Proposals bind exact evidence and plan revisions
Every proposal MUST expose before/after values, affected session identities, reasons, uncertainty, base-plan/evidence/context/commitment revisions, policy and expiry. Explanations MUST be non-executable and MUST NOT claim medical suitability. Unresolved safety-relevant context, conflicts, mandatory overflow or missing required evidence MUST prevent a proposal from becoming approvable.

#### Scenario: Illness applicability is uncertain
- **WHEN** unresolved stale illness context could affect a proposed future session
- **THEN** the affected proposal requests clarification and cannot be approved as safe

#### Scenario: An amendment explanation alters a supplied fact
- **WHEN** a draft rewrites an exact code-owned explanation choice, including its numbers, units, dates, attribution, sources or uncertainty
- **THEN** the whole draft is rejected rather than accepting free prose as verified reasoning or clinical clearance

#### Scenario: Individually permitted moves collide together
- **WHEN** two selected candidates are individually permitted but their resulting session intervals overlap
- **THEN** independent whole-result validation rejects the entire draft without salvaging a subset or changing the active plan

### Requirement: Applying an amendment requires atomic explicit approval
Application MUST require authenticated, CSRF-protected approval of an unexpired exact proposal. It MUST revalidate current evidence, restrictions, commitments, permissions and base-plan revision, then atomically write a new local plan revision. Repeated approval MUST be idempotent. Stale approval MUST be rejected without silent rebasing. Model execution, hosted consent and amendment approval MUST remain separate permissions.

#### Scenario: Plan changes before approval
- **WHEN** the user approves a proposal whose base-plan revision is no longer current
- **THEN** no session is changed and the user is asked to review a new proposal

#### Scenario: Duplicate approval request
- **WHEN** the same accepted approval is submitted again
- **THEN** the existing applied revision is returned without another amendment or upstream write

#### Scenario: Inspection or generation only
- **WHEN** a review is generated or its proposal is inspected
- **THEN** neither local nor external calendar/plan state changes
