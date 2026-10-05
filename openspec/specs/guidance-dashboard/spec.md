# Guidance Dashboard Specification

## Purpose

Give the user one private in-app place to review Garmin-derived factual trends from Intervals.icu, experimental interpretation status, sync status, and upcoming events entered by hand.

## Requirements

### Requirement: Dashboard access stays private
The system MUST restrict dashboard data to the intended user and MUST NOT expose the initial home-server dashboard directly to the public internet.

#### Scenario: Unauthenticated request
- **WHEN** a device without an authorized user session requests dashboard data
- **THEN** the system denies access to recommendations and health information

#### Scenario: Dashboard runs in the home cluster
- **WHEN** the dashboard is deployed to the Raspberry Pi K3s cluster
- **THEN** it is reachable on the configured home network and is not directly exposed to public internet traffic

### Requirement: Dashboard presents available trends and overall health
The system MUST show activity by sport, recorded activity time, calories burned, weight, VO2 max, HRV, and sleep when the Intervals.icu source provides populated values. It MUST distinguish recorded activity time from Garmin intensity minutes and label any Intervals.icu-derived load separately. Fitness age, endurance score, Garmin training status, or any other unavailable metric MUST appear as unavailable rather than estimated. Values MUST include source and time context.

#### Scenario: User opens trends dashboard
- **WHEN** the user opens the dashboard after records are imported
- **THEN** the system presents historical activity by sport and available health metrics with time context

#### Scenario: Requested metric cannot be fetched
- **WHEN** Intervals.icu does not supply a requested Garmin score
- **THEN** the dashboard labels it unavailable and identifies source coverage rather than showing a made-up value

### Requirement: User manages event calendar in the app
The system MUST let the user add, edit, view, and remove events with dates, sport or activity, a free-text goal or success description, and optional current-state notes. It MUST show the next upcoming event by date. Phase-one events MUST NOT require Google Calendar access.

#### Scenario: User records a trip
- **WHEN** the user saves a multi-day SUP trip with its dates, target conditions, and current ability notes
- **THEN** it appears in the event calendar and becomes the next event when it is the earliest future event

#### Scenario: User updates an event
- **WHEN** the user changes or removes an event
- **THEN** the calendar and next-event view reflect that change without altering Garmin records

### Requirement: Experimental interpretation remains distinct from factual dashboard
The system MUST keep code-generated factual summaries usable independently of experimental model interpretation. If a prototype interpretation is shown, the dashboard MUST label it as experimental, identify its evidence and date coverage, and MUST NOT imply production qualification or coaching advice.

#### Scenario: User reviews a prototype interpretation
- **WHEN** an experimental interpretation is available
- **THEN** the dashboard labels it as experimental and provides its supporting evidence and period separately from factual data

#### Scenario: Interpretation is unavailable
- **WHEN** an experimental model is unavailable or returns unusable output
- **THEN** the dashboard reports that state while keeping factual charts visible

### Requirement: Dashboard exposes data status
The system MUST make the most recent Intervals.icu synchronization result and stale or missing data visible, including the affected record categories when known. It MUST NOT conflate an Intervals.icu API failure with an unverified upstream Garmin sync failure.

#### Scenario: Intermediary data is stale or sync failed
- **WHEN** the latest Intervals.icu read failed or its available data is stale
- **THEN** the dashboard indicates the observed issue and affected categories when known without assigning an unverified upstream cause

### Requirement: Review configuration and schedule status are intuitive
The dashboard MUST expose editable, enable/disable review presets and assignable supported triggers/schedules, with advanced scope, level and time-budget settings rather than requiring a rules language. It MUST distinguish the next scheduled changed-data check from a next due analysis and an unpredictable future event trigger. It MUST show the configured time zone, paused/disabled state, queued trigger reasons and estimated eligibility/start, running state, and last successful review's generation time and input coverage. It MUST label application-generated facts separately from optional AI interpretation. Unsupported triggers MUST explain their unavailable source data.

#### Scenario: Next analysis depends on changed data
- **WHEN** the next enabled rule is a timed changed-data check
- **THEN** the dashboard shows its local date/time and that analysis runs only if relevant data changed

#### Scenario: Review waits for an activity
- **WHEN** only an activity-arrival rule is pending
- **THEN** the dashboard says it is waiting for activity data without inventing a next analysis time

#### Scenario: Imports are settling
- **WHEN** sleep and HRV arrivals merge into a pending review
- **THEN** the dashboard shows both reasons, the review type/level/scope and its settling status without promising an exact start behind running work

#### Scenario: Newer data arrived during a review
- **WHEN** a review finishes on an older snapshot
- **THEN** its output retains its actual coverage and is marked older than the latest data rather than presented as current analysis

### Requirement: Synthetic development controls are visibly separate
The isolated development launcher MUST provide a special Developer tab behind an opt-in visibility switch. Controls MUST label packets/results synthetic-only and unqualified and require explicit case/model preview before submission. The switch MUST reveal diagnostics only, not grant inference permission. Normal personal dashboards MUST NOT expose this development execution capability.

#### Scenario: Development preview
- **WHEN** the authenticated developer selects a bundled case and configured model
- **THEN** the panel presents its exact prompt, model identity, generation settings and budgets before enabling submission

#### Scenario: Personal dashboard
- **WHEN** the user opens the normal application
- **THEN** no synthetic development run control or extended execution permission is available

#### Scenario: Developer options enabled
- **WHEN** an authenticated developer enables the visibility switch in the isolated launcher
- **THEN** the Developer tab becomes available without submitting work, granting model permission or initiating cleanup

### Requirement: Long-running development status is inspectable
The Developer tab MUST display durable queue order, task/run identity, input revision, creation/expiry, supersession links, timings and model/budget settings. It MUST distinguish logical outcome, execution state and retention state, support read-only reconnection and explicit cancellation, and MUST NOT invent completion percentages or treat polling as submission.

#### Scenario: First attempt takes several minutes
- **WHEN** a submitted synthetic run remains active
- **THEN** the developer can inspect elapsed time, current attempt and budgets and cancel it without starting another model call

#### Scenario: Refresh after submission
- **WHEN** the browser reconnects to a retained run identifier after refresh or worker restart
- **THEN** it fetches status and restores inspection without resubmitting the run

#### Scenario: Newer input supersedes queued work
- **WHEN** a queued task is superseded while its running predecessor continues
- **THEN** the tab shows the replacement link and running predecessor separately, without labeling the predecessor cancelled

### Requirement: Development failures and limits are explicit
The development panel MUST distinguish timeout, cancellation, validation rejection, runtime/resource failure and unconfirmed cleanup. It MUST display only validated interpretations, preserve source/uncertainty presentation and explain empty results. It MUST NOT call an accepted draft a qualified model or present synthetic development execution as a completed benchmark.

#### Scenario: Timeout with uncertain remote cleanup
- **WHEN** a run times out and runtime cleanup cannot be confirmed
- **THEN** the panel shows both conditions and explains why queued work cannot execute, without claiming cancellation stopped the process or offering automatic inference retry

#### Scenario: Validated but empty result
- **WHEN** an accepted synthetic draft contains no interpretations or questions
- **THEN** the panel states that no connected insights were provided and keeps the unqualified label visible

### Requirement: Worker summaries explain actions without exposing content
The Developer tab MUST show bounded worker summaries with fixed reasons for execution, cleanup, supersession and refusal. It MUST expose safe runtime/health and configuration metadata, not credentials, packet content in diagnostics, rejected text or raw errors. Reasons MUST explain recorded code decisions, not model reasoning. Associated history MUST follow its creation-based TTL.

#### Scenario: Worker blocks the next task
- **WHEN** prior termination remains unconfirmed after task processing
- **THEN** the tab explains the execution block, affected task and pending reconciliation without leaking provider output or credentials

#### Scenario: Database diagnostic
- **WHEN** a worker task reports a persistence failure
- **THEN** the tab displays its fixed safe reason rather than query text, raw database exceptions or submitted field content

#### Scenario: Memory diagnostic
- **WHEN** runtime configuration and host memory health are displayed
- **THEN** the whole-appliance memory ceiling and minimum available host headroom are identified separately, without presenting the non-inference test VM budget as a model allocation

### Requirement: Retention and maintenance are visible and explicit
The Developer tab MUST show expiry and pending/failed deletion without exposing expired payload. It MUST explain that physical deletion occurs on later queue events, not an idle timer. It MUST offer explicit maintenance-only submission through the same worker for ad hoc cleanup/reconciliation, without inference or a competing cleanup process.

#### Scenario: Expired history with no later event
- **WHEN** inspection finds a run beyond its 24-hour creation TTL
- **THEN** its payload is hidden and deletion-pending is distinguished from confirmed deletion; inspection itself starts neither cleanup nor inference

#### Scenario: Developer requests maintenance
- **WHEN** an authenticated developer explicitly submits maintenance
- **THEN** the same worker records its cleanup/reconciliation summary without issuing a model request for that maintenance task
