# Spec Delta

## Purpose

Give the user one private in-app place to review Garmin-derived trends from Intervals.icu, source-grounded AI observations, sync/model status, and upcoming events entered by hand.

## ADDED Requirements

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

### Requirement: Dashboard shows AI observations and model status
The system MUST present requested cross-metric AI observations separately from measured data, show the supporting metrics and date range, and identify the selected model and whether analysis is available. A model failure MUST NOT hide factual charts.

#### Scenario: User reviews an AI observation
- **WHEN** a grounded AI trend summary has been generated
- **THEN** the dashboard labels it as AI analysis and lets the user see the metrics and period behind its claims

#### Scenario: Model is unavailable
- **WHEN** the selected model cannot produce a valid summary
- **THEN** the dashboard shows its unavailable or error state while keeping factual charts visible

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
