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
The system MUST present historical comparisons as observations, not as medical conclusions, sport-safety clearance, or prescriptions for training intensity. It MUST NOT generate or auto-apply a weekly training plan in this change.

#### Scenario: Trend changes over time
- **WHEN** the system compares two periods with available activity or health records
- **THEN** it shows the observed change and underlying dates without prescribing a training intervention

#### Scenario: Record coverage is incomplete
- **WHEN** the chosen time period lacks enough records for a meaningful comparison
- **THEN** the system identifies the data gap instead of claiming a confident personal trend

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
