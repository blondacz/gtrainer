# Spec Delta

## Purpose

Extend attributed athlete context with single-entry reuse, confirmed calendar projections and explicit unresolved/review-due states, without equating expired text or missing updates with recovery.

This additive delta depends on the athlete-context capability introduced by `connected-insights-context`. Synchronize that change first; its attribution, correction, deletion, restriction and feedback requirements remain intact.

Context reads and updates for this change use the canonical review preparation/control flow; historical profile artifacts do not require legacy HTTP interfaces to remain available.

## ADDED Requirements

### Requirement: One context intake supports confirmed reusable projections
The system MUST accept a note once and retain its original wording and attribution. Suggested symptoms, restrictions, commitments or status updates MUST remain proposals until the user confirms or corrects them. Confirmation MUST reuse the original intake identity and preserve revisions; unconfirmed suggestions MUST NOT alter restrictions, calendar state or the plan. Text alone MUST NOT imply medical guidance.

#### Scenario: Note describes illness and climbing
- **WHEN** a note describes improving illness and a Wednesday climbing commitment
- **THEN** the user can confirm each proposed item without retyping, and ambiguous dates or recurrence require clarification

#### Scenario: Extraction unavailable
- **WHEN** qualified text extraction is unavailable
- **THEN** the original note remains usable as attributed free text and the user can explicitly associate structured items without a hidden provider call

#### Scenario: Original source wording is corrected
- **WHEN** a user corrects an intake with already confirmed items
- **THEN** the intake gains a revision, exact previous source links show needs reconfirmation, dependent reviews are invalidated, and confirmed item text/identity is not silently rewritten

#### Scenario: Visible batch confirmation fails or is retried
- **WHEN** the user confirms a selected batch or repeats its confirmation identity after an uncertain response
- **THEN** all selected items are committed atomically under explicit item/byte bounds, an identical request returns its original revision references without duplicates, and a changed request using that confirmation identity conflicts

### Requirement: Context lifecycle separates review due from resolution
Context MUST distinguish observation date, applicability, optional review-due date and explicit unresolved/resolved state. Passing a review-due or applicability end date MUST NOT establish recovery or clearance. Unresolved safety-relevant context MUST remain visible as needs review and block affected amendment proposals when current applicability is uncertain, without presenting an expired instruction as current guidance.

Explicit user resolution deactivates the item's new-profile operational blocking
without claiming medical clearance. Retirement alone MUST NOT resolve an
unresolved safety item. Historical views MUST disclose that they interpret current
canonical revisions, not reconstruct past knowledge. Missing lifecycle metadata
MUST default to unresolved, never recovered. Lifecycle edits MUST preserve exact
source-revision associations and MUST NOT imply source reconfirmation.

#### Scenario: Illness status becomes stale
- **WHEN** an improving-illness report reaches its review-due date without an update
- **THEN** the system requests a targeted status update and does not mark the user recovered

#### Scenario: Restriction applicability ends
- **WHEN** a restriction's applicability ends while its safety-relevant status is unresolved
- **THEN** it is shown as historical guidance needing clarification and affected amendments are withheld pending resolution

### Requirement: Calendar views reuse context and commitment identities
The system MUST present confirmed dated/recurring commitments and linked context through shared identities rather than independent copies of their text. Recurrence MUST include timezone and exceptions. Corrections/deletions MUST update projections and invalidate dependent reviews/proposals. Existing event notes MUST NOT silently become model context or structured constraints.

The initial local recurrence contract supports one-off, daily and weekly items,
with explicit timezone, interval/end date and per-occurrence cancel/reschedule
exceptions. Expansion MUST be bounded to a requested 1–366-date horizon. Other
recurrence patterns MUST be labeled unsupported rather than silently converted.
Individual intervals MUST support multi-day spans up to 366 local wall-clock
days, independent of the query horizon. Occurrences overlapping the horizon,
including starts before it and exceptions rescheduled into it, MUST be included.

#### Scenario: Commitment is corrected once
- **WHEN** a confirmed recurring commitment changes
- **THEN** subsequent calendar occurrences and review evidence reflect the new revision without requiring a separate prompt update

#### Scenario: Legacy event linked explicitly
- **WHEN** the user explicitly associates an existing event with review context
- **THEN** the association retains the event and context identities and includes only the authorized fields

### Requirement: Context retrieval evaluates relevant intervals
New-profile retrieval MUST evaluate applicability over the evidence interval and future amendment horizon separately. It MUST include all relevant mandatory restrictions, retain historical attribution, surface conflicts and distinguish current from historical context. Unresolved restriction conflicts MUST block the affected model review or amendment while deterministic factual views remain usable.

#### Scenario: Restriction starts tomorrow
- **WHEN** a proposed amendment would move a session into tomorrow's restricted interval
- **THEN** tomorrow's restriction is evaluated even if it is not applicable today and the operation is refused if incompatible

### Requirement: Deletion propagates through shared projections
Deleting context MUST remove active text and dependent projections, invalidate pending/running derived work and remove dependent review/proposal text that reproduces it. Minimal content-free audit metadata MUST NOT preserve hidden health memory. Imported activities and unrelated plans MUST remain intact; encrypted-backup retention limitations MUST remain disclosed.

#### Scenario: Deleted symptom appeared in a proposal
- **WHEN** the user deletes that symptom
- **THEN** dependent proposal content is purged or redacted and cannot be approved, without deleting unrelated training records

#### Scenario: Deleted item shares an original note with unrelated items
- **WHEN** the user requests deletion of an item whose sensitive wording also occurs in a shared intake
- **THEN** the system previews dependencies, requires approved replacement source wording, purges old source revisions and dependent sensitive content, and explicitly reconfirms surviving sibling links without deleting unrelated items
