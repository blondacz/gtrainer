# Source capability matrix

This is an implementation audit, not a promise that a provider API can be used
by this application. Status describes the current local adapter and field
mapping—not whether a vendor may expose similar data. No source account or live
source was inspected. `available` means implemented through the local adapter;
`not_configured` means a possible documented source capability has no configured
local adapter; `unverified` means source semantics/provenance have not been
established; `unsupported` means the local adapter has no representation or
mapping for it. A missing record is not evidence that the upstream has no data.
Runtime credential/read status is separate: an implemented import adapter can
still be `NOT_CONFIGURED`, denied, stale or missing records. The dashboard retains
those actual source-status labels rather than treating this code audit as a live
availability or completeness check.

## Implemented local capabilities

| Capability | Local adapter implemented status | Source fields and semantics | Evidence / limits |
|---|---|---|---|
| Completed activity records | available | `GET /api/v1/athlete/0/activities`; normalizer maps `id`, `type`, `start_date_local`, `start_date`, `timezone`, `source`/`sources`; `moving_time` and `elapsed_time` (seconds), `distance` (metres), `calories` (kcal), `average_heartrate` (beats/minute), `icu_training_load` (separately named Intervals.icu load). Explicit metric-origin fields alone may attach a recognized upstream source. | [IntervalsSource.kt](../../backend/src/main/kotlin/com/gtrainer/IntervalsSource.kt), [IntervalsNormalizer.kt](../../backend/src/main/kotlin/com/gtrainer/IntervalsNormalizer.kt), [HealthRecords.kt](../../backend/src/main/kotlin/com/gtrainer/HealthRecords.kt). Read-only GET; validated normalized fields only. No raw JSON, names, descriptions, streams, power, or laps retained by this mapping. |
| Daily wellness records | available | `GET /api/v1/athlete/0/wellness`; `id` as source date and mapped fields: `weight` (kg), `bodyFat` (%), `hrv`/`hrvSDNN` (ms), `restingHR` (beats/minute), `sleepSecs` (seconds), `sleepScore` (source sleep score), `steps`, `vo2max` (mL/kg/min), `atl`/`ctl` (Intervals.icu load). Explicit origin fields used where implemented. | Same implementation citations above; [read-only import contract](read-only-import.md#records-units-and-unavailable-scores). Missing stays missing; no readiness score or Garmin proprietary fitness/training score is derived. The source date does not establish prior-night association. |
| Activity time provenance | available | Local timestamp is required; an explicit `start_date` offset/instant, explicit local offset, or a uniquely resolving named zone may supply an instant. Offset-free local values can remain local-only; ambiguous/nonexistent DST times do not get an invented instant. | [IntervalsNormalizer.kt](../../backend/src/main/kotlin/com/gtrainer/IntervalsNormalizer.kt); details in [read-only import](read-only-import.md#records-units-and-unavailable-scores). `timeContext` distinguishes upstream instant, explicit offset, named zone, ambiguous/nonexistent local time, and local-time-only. |
| User-authored plan sessions | available | Local `AuthoredPlan`/`PlannedSession` revisions, authored locally with date-only or local timed schedule, explicit timezone and optional valid offsets; supported targets are duration, distance, average HR, Intervals load, and explicitly defined reported effort. | [Plans.kt](../../backend/src/main/kotlin/com/gtrainer/Plans.kt). These are local authored plans, not imported/provider plans, completed activities, coach-verified prescriptions, or commitments. |
| User-confirmed commitments and availability | available | Locally confirmed typed commitment/availability with local interval and timezone; supported recurrence is one-off, daily, or weekly with bounded exceptions. Projection resolves occurrences into UTC and carries confirmation/source revision references. | [Commitments.kt](../../backend/src/main/kotlin/com/gtrainer/Commitments.kt). Confirmed-local-only coverage; local events are separate. An empty list cannot establish absence of external commitments. |
| Manual date-only events | available | User-created local events with dates and optional sport/goal/notes. | [HistoryStore.kt](../../backend/src/main/kotlin/com/gtrainer/HistoryStore.kt), [README](../../README.md#current-state). Not imported calendar data and not automatically interpreted as a base plan. |

## External and unsupported capabilities

| Capability | Local adapter implemented status | Source fields and semantics | Public documentation / limits |
|---|---|---|---|
| Intervals.icu calendar/event reads, including planned workouts | not_configured | No `HistorySource` calendar method or local event normalizer. A public API guide documents `GET /api/v1/athlete/{id}/events` with `oldest`/`newest` local dates and notes that only enabled calendars are returned; multiday events can expand returned range. | [Intervals.icu API access guide](https://forum.intervals.icu/t/api-access-to-intervals-icu/609). Its existence does not make the capability available in this app. Event category/type, workout-step mapping, enabled-calendar completeness, timezone interpretation and authentication scope must be proven for a specific adapter before user-facing availability. No event-as-plan inference. |
| Intervals.icu detailed activity streams, power, laps/intervals | unsupported | Current activity normalization maps aggregate duration, distance, calories, average HR, and Intervals load only. No stream/power/lap fields or endpoints are exposed in `ActivityRecord`/`HistorySource`. | [HistorySource and record contract](../../backend/src/main/kotlin/com/gtrainer/HealthRecords.kt); [scoped planning source decision](../../openspec/changes/scoped-reviews-and-adaptive-planning/design.md#3-build-one-inert-versioned-evidence-snapshot). No detail availability claim based on API capability alone. |
| Readiness and verified prior-night mapping | unverified | No readiness field exists in the normalized wellness contract. Sleep and HRV are separate source observations; their date does not prove they belong to the night preceding a selected activity or establish a vendor readiness formula. | [IntervalsNormalizer.kt](../../backend/src/main/kotlin/com/gtrainer/IntervalsNormalizer.kt); [scoped planning design](../../openspec/changes/scoped-reviews-and-adaptive-planning/design.md#3-build-one-inert-versioned-evidence-snapshot). Do not compute a substitute readiness score. |
| Garmin Connect direct API/data adapter, private or unofficial scopes | unverified | No Garmin adapter is present. Existing data is imported through Intervals.icu and does not establish direct Garmin access, official API approval, source freshness, or per-metric Garmin origin. | [README](../../README.md#current-state), [read-only import boundary](read-only-import.md#source-and-credential-boundary). Garmin API/private scope access and field semantics are not verified here and must not be promised. |
| External calendar providers (including Google Calendar) | not_configured | No external calendar adapter is implemented. Local events and confirmed commitments remain local-only. | [README](../../README.md#current-state); the scoped planning design defers verified external calendar/plan adapters to a separate change. |

Public documentation establishes that an endpoint or feature is described, not
that this application has its auth scopes, field mapping, provenance, completeness,
or safe timestamp semantics. The local Intervals adapter currently uses fixed
read-only activity and wellness GETs, not calendar/event routes. Its configured
source key and any provider authorization are not evidence for other endpoints.

Public documentation was checked on 2026-10-15, without an authenticated request.
The API access guide's 2026-10-09/14 updates warn that Garmin-origin API access
terms may change. This is not evidence of an actual account failure or permission
to change credentials/adapters: upstream availability and terms remain separate
from this matrix's implemented-field status. The same guide documents optional
activity interval detail (`GET /api/v1/activity/{id}?intervals=true`); the current
adapter does not call that endpoint or normalize its fields.

## User-facing and change boundaries

Expose capability status from what the adapter and verified field mapping
actually implement. Do not mark an external capability `available` merely because
an API exists, an account could be linked, or a local list is empty. Preserve the
distinction between source-local date/time, a verified UTC instant, and unknown
time/readiness association. Show imported source coverage separately from local
authored plans, confirmed commitments, and manual events. Do not invent a frontend
API/DTO in this audit; the primary implementation owns any required capability
contract.

Local authored plans and user-confirmed commitments are the fallback when external
sources are absent. They do not imply that upstream calendars are empty, and do
not get represented as upstream events. External read integration requires a
separate scoped change covering adapter boundary, documented auth/scope and
field semantics, normalization/provenance, limits, synthetic tests, and accurate
user-facing status. Any external write/publish behavior requires separate
explicit design and approval; this matrix authorizes no live reads, account
access, adapter work, or upstream writes.

## References audited

- Repository README, `docs/data/read-only-import.md`, `backend/src/main/kotlin/com/gtrainer/{HealthRecords,IntervalsSource,IntervalsNormalizer,Plans,Commitments}.kt`.
- `openspec/changes/scoped-reviews-and-adaptive-planning/design.md` (proposed behavior explicitly distinguishes existing source support from future external reads; not proof of implementation).
- [Intervals.icu public API access guide](https://forum.intervals.icu/t/api-access-to-intervals-icu/609) and [public API overview](https://www.intervals.icu/features/open-api). These document API availability, not local adapter support or verified authorization for this application.
