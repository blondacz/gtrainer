# Spec Delta

## Purpose

Provide familiar, explicit temporal scopes shared by reports and reviews without confusing activity timestamps, reporting dates, personal baselines or future planning horizons.

Scoped factual requests are prepared through the canonical `/api/reviews/options` and `/api/reviews/prepare` interface; this scope contract does not require legacy report-route compatibility.

## ADDED Requirements

### Requirement: Reports and reviews support named temporal scopes
The system MUST support an identified activity or selected activity set, selected day/night, calendar week/month/year, fixed rolling 7/30/365-day windows and custom historical ranges of 1–366 inclusive dates. Calendar weeks MUST be Monday–Sunday. Each selection MUST expose exact resolved dates, its date basis, saved timezone and captured evaluation instant; activity timestamps MUST remain distinct from reporting boundaries. Activity-set selection MUST verify at most 1,000 unique existing identities within 366 inclusive source dates under existing request/record bounds. Unknown or duplicate identities and over-limit selections MUST be rejected, not guessed, dropped or truncated.

#### Scenario: User selects rolling month
- **WHEN** the anchor date is 2026-10-06 and the user selects Last 30 days
- **THEN** the range is 2026-09-07 through 2026-10-06 inclusive and is not labeled a calendar month

#### Scenario: Leap year and calendar variants
- **WHEN** the user selects the completed calendar year 2024
- **THEN** the range contains 366 dates, while Last 365 days always contains 365 dates

#### Scenario: Activity has only local timestamps
- **WHEN** an activity lacks an unambiguous source instant
- **THEN** its source-local timestamp and uncertainty are shown without inventing a UTC instant or using the report start as its start time

#### Scenario: Activity selection exceeds its limits
- **WHEN** a selection contains more than 1,000 identities or spans more than 366 inclusive source dates
- **THEN** it is explicitly rejected without silently reviewing a smaller set

### Requirement: Selected-session and period comparisons are distinct
The system MUST separate selection target from review/compare intent. Selected-session review MUST use exactly the verified selected activities, not their enclosing period; selected-session comparison MUST compare at least two explicitly selected activities without fabricating a previous activity or period. Single-session review and same-sport or mixed-sport selection MUST remain supported. Comparison MUST preserve session/sport identities and compatible measurement bases; equal units MUST NOT imply interchangeable sport intensity or training effect. Period review/comparison MUST instead use resolved date ranges and their disclosed comparison policy.

#### Scenario: One activity is selected
- **WHEN** the user selects one run
- **THEN** it can be reviewed, while session comparison requires another explicitly selected activity and does not automatically find a prior run

#### Scenario: Two runs are selected with intervening activities
- **WHEN** the user compares two selected runs on different dates
- **THEN** only those runs are comparison subjects and intervening activities are not included as selected evidence

#### Scenario: Mixed-sport triathlon selection
- **WHEN** the user reviews or compares selected swim, ride and run activities
- **THEN** the activities remain separate identified sessions with compatible comparisons and no invented combined-event identity or cross-sport intensity equivalence

### Requirement: Calendar and rolling comparisons retain their meaning
The system MUST distinguish preceding calendar-period comparisons from preceding equal-length rolling/custom comparisons. Current calendar periods MUST be labeled so far and default to comparison with the corresponding elapsed portion of the prior calendar period. When the prior period is shorter, only shared elapsed dates MUST be compared, with unmatched dates disclosed. Full prior-period totals MUST be labeled separately. Any period scope MUST allow an explicit historical 1–366-date comparison range, including a range that overlaps or follows the selected period while remaining historical at the cutoff. Explicit comparisons MUST disclose actual dates, overlap and unequal duration rather than being labeled independent or like-for-like defaults; they MUST NOT be confused with comparison among selected sessions.

#### Scenario: Partial week
- **WHEN** a calendar-week report covers Monday through Wednesday
- **THEN** its default comparison covers Monday through Wednesday of the prior week, not all seven dates

#### Scenario: Unequal calendar months
- **WHEN** a month-to-date report has more elapsed dates than the preceding month contains
- **THEN** the comparable subset uses the shared date count and separately discloses the current unmatched dates and full selected-period totals

#### Scenario: Custom comparison selected
- **WHEN** the user explicitly supplies another historical comparison range
- **THEN** its actual dates and unequal duration, if any, are visible and it is not presented as a like-for-like default

#### Scenario: Overlapping explicit comparison overrides calendar default
- **WHEN** a completed calendar-month review explicitly compares against a historical range overlapping that month
- **THEN** the explicit range overrides the default, its overlap is disclosed, and it is not called an independent preceding-calendar comparison

#### Scenario: Completed calendar month
- **WHEN** a completed February is compared with the preceding January
- **THEN** full calendar-period totals retain their actual dates and unequal duration, rather than labeling an equal-length January suffix as the prior calendar month

### Requirement: Evidence windows and planning horizons are separate
The system MUST retain the observation window, additional baseline windows and future planning horizon separately. A morning briefing MUST distinguish latest available measurements from today's existing plan. Future dates MUST NOT contain inferred observed physiology. Persistent applicable context MUST NOT be excluded solely because its observation date precedes the selected evidence window.

#### Scenario: Older restriction applies today
- **WHEN** a dated restriction was entered before the selected seven-day window and remains applicable
- **THEN** its original attribution/date and current applicability are included without rewriting its observation date

#### Scenario: Historical review references future commitment
- **WHEN** a weekly review includes a relevant upcoming commitment
- **THEN** the commitment is explicitly future context, not an activity counted in historical totals

### Requirement: Scheduled scopes are resolved consistently
Manual and scheduled reviews MUST use identical scope semantics. Schedule times MUST use an explicitly saved timezone, not an inferred browser or travel timezone. Daylight-saving gaps MUST move to the next valid instant; overlaps MUST execute once using the earlier offset. Historical source date semantics MUST remain disclosed and MUST NOT silently change to the schedule timezone.

#### Scenario: Duplicate schedule occurrence
- **WHEN** the clock repeats a local time during a daylight-saving overlap
- **THEN** the same morning or evening occurrence is queued at most once

#### Scenario: Source-local activity date differs
- **WHEN** an activity's source-local date differs from the user's saved schedule date
- **THEN** its reporting bucket follows the disclosed source date basis while schedule execution follows the saved timezone

#### Scenario: Selected activity has occurred across a travel boundary
- **WHEN** a verified selected activity has a source-local date ahead of the saved schedule date but a verified start instant at or before the captured cutoff
- **THEN** the new selection includes it with its original source date and a disclosed timezone mismatch, without broadening period windows or changing legacy arrival routing

#### Scenario: Future source date has no verified past instant
- **WHEN** a selected activity is source-dated ahead of the schedule date and its real start instant cannot establish that it occurred by the cutoff
- **THEN** the selection requires clarification rather than inventing an instant or treating it as observed past activity
