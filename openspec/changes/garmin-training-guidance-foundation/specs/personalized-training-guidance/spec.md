# Spec Delta

## Purpose

Connect the user's available Garmin-derived activity and wellness trends from Intervals.icu into model-assisted observations with traceable evidence, without prescribing workouts or judging readiness for sport-specific hazards.

## ADDED Requirements

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

### Requirement: AI connects observed metrics with traceable evidence
The system MUST let the user request a plain-language summary connecting trends across available activity and health metrics. Each claim about the user's history MUST cite the metrics and time periods used, distinguish an observed association from a cause, and disclose missing or stale inputs. Model output MUST be checked against the supplied data before presentation; unsupported claims MUST NOT be shown as facts.

#### Scenario: Several metrics are available
- **WHEN** the user requests an AI summary for a period with activity, sleep, and HRV records
- **THEN** the system presents a cross-metric observation tied to those available records and their dates without claiming one metric caused another

#### Scenario: Model invents a missing score
- **WHEN** the model describes a Garmin fitness age that the source did not provide
- **THEN** the system rejects the whole model response without salvaging fragments and does not label an invented value as Garmin data

#### Scenario: Model cannot make a grounded summary
- **WHEN** the model is unavailable or returns an unsupported or unusable summary
- **THEN** the system reports that AI analysis is unavailable while leaving factual charts accessible

### Requirement: User can choose a model without silent data transfer
The system MUST let the user select a supported AI model without tying the trend view to one provider. Local inference MUST be preferred where usable. Before transmitting any personal health or activity records to a non-local provider, the system MUST disclose that transfer and obtain explicit consent. The system MUST NOT log sensitive model inputs or model credentials or silently fall back to a hosted provider.

#### Scenario: User selects a supported local model
- **WHEN** the user chooses an available local model for trend analysis
- **THEN** the system processes the supplied history locally and identifies the selected model

#### Scenario: User switches supported models
- **WHEN** the user selects another configured model
- **THEN** subsequent summaries use that selection without changing stored Garmin records

#### Scenario: Hosted model has no consent
- **WHEN** the selected model would send personal data outside the local environment and consent has not been granted
- **THEN** the system sends nothing, explains the transfer, and asks the user to decide

### Requirement: Factual grouping is independent of model interpretation
The system MUST compute required comparisons, coverage, directions and complete evidence groups before inference. It MUST keep incompatible periods separate and unavailable comparisons explicit, and MUST be able to render the factual groups without model output. Model interpretation MUST be separately labeled and checked against the exact supplied snapshot; invalid output MUST be rejected whole, not repaired by filling omissions. Unrestricted personal prose MUST NOT be considered validated merely because schema, keywords or evidence IDs pass checks.

#### Scenario: Model interpretation fails
- **WHEN** the prepared facts include activity, sleep and unavailable HRV but the model returns an invalid interpretation
- **THEN** the system withholds the whole interpretation while its independently prepared factual groups still cover all three metrics

#### Scenario: Comparison periods differ
- **WHEN** supplied activity and wellness comparisons refer to different periods
- **THEN** code keeps their factual groups separate and validation rejects an interpretation presenting them as the same-period association

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

### Requirement: Background analysis has bounded independently validated attempts
The system MUST expose configurable bounded per-call and total-job budgets suitable for day/week background reviews, including model loading and validation. It MAY make at most one corrective attempt for a rejected semantic/structural response using bounded validator feedback and the same immutable evidence; that response MUST independently pass the same validator. Timeout, OOM, restart, cancellation, changed evidence/model or host/live-health guard failure MUST NOT cause corrective retries or silent provider fallback. Resource limits MUST remain explicit and charts MUST remain usable throughout.

#### Scenario: First response fails evidence validation
- **WHEN** a review allows a corrective attempt and its first response is rejected within the remaining job budget
- **THEN** the system may request one new response, counts both attempts and accepts only an independently valid final response without patching the first

#### Scenario: Model exceeds its memory cap
- **WHEN** a model is OOM-killed during analysis
- **THEN** the job fails without automatic retry, larger memory allocation or hosted transfer, while factual views remain available
