# Private dashboard and manual events

These controls are implemented in the local source and tested with synthetic data.
They are **not yet deployed** to the Pi. Opening the current live dashboard will
not show this new layout until an approved schema-compatible release is promoted.
Production AI remains unqualified and the separate review runtime refuses inference
without its resource/live-health guard. No new model experiment or hosted transfer
is authorized by these instructions.

## Navigation and factual values

Sign in using private access; never paste source/model keys into the dashboard.
The dashboard opens **Overview**: two-column rounded category tiles, dated record
totals/means, source status, next manual event and review queue metadata. Coloured
tiles use white text; surrounding text is neutral with orange accents on a white
page. Tile shades retain readable white-label contrast. Colours
identify categories, **not health, readiness or safety**. Zero is retained; missing
values remain unavailable. Imported values are not live measurements. A record
mean weights each populated record equally, not each calendar date.

Use **Previous/Next cards** for the remaining tiles. Card count adapts to viewport
height. Selecting a tile opens its corresponding section; top tabs and keyboard
Left/Right/Home/End switch sections. Only the active section mounts its private
controls. Ordinary views aim to fit the viewport; tiny displays, zoom and keyboards
may still need accessible overflow rather than clipping content.

- **Trends → period** selects 1–366 inclusive dates and an optional activity sport;
  wellness remains independent of the sport filter. Loading facts makes no source
  read or model call.
- **Trends → metrics** selects any retained sport/metric. Chart, comparison and
  evidence tabs separate plotted values, arithmetic changes and source records.
  Evidence is paged one record at a time; long provenance is paged losslessly.
  Charts plot only received values and do not fill missing dates with zero.
- **Trends → facts** presents every comparison, including missing/unchanged/zero
  values, as **Factual review — no AI**. Select comparison, previous/current period
  and values/provenance/coverage/focus/limits. Paging does not remove comparisons
  or repair a rejected factual response.
- **Trends → coverage** retains date basis, period flags, unknown source freshness
  and unavailable proprietary Garmin metrics. Recorded activity time is not
  Garmin intensity minutes; Intervals.icu load is not a proprietary Garmin score.

## Read-only source

**Source → read** starts an explicitly requested Intervals.icu read for the chosen
dates. **Status** separates activity and wellness categories, latest attempt/success
and stored observed dates. A failed read can coexist with retained charts.
Garmin-to-Intervals.icu freshness remains unverified: missing imports do not prove
missed activity, and missing wellness values do not imply illness.

**Source → remove** confirms removal of local imported records and review snapshots.
It does not delete manual events, upstream account data or retained encrypted backups.
Reads themselves make no model call; separately enabled review schedules can route
changed records to guarded analysis. See **limits** for the intermediary's privacy
and coverage boundary.

## Manual events

Open **Events → New manual event**. Use the three editor steps:

1. **Dates & sport:** start/end calendar dates and sport/activity. A one-day event
   uses the same date; multi-day trips use a range. End cannot precede start;
   maximum span is 3660 days, sport at most 80 characters.
2. **Goal:** your own goal/success description, up to 2000 characters.
3. **Notes:** optional current-state/context notes, up to 4000 characters.

**Save manual event** is the only create/edit write. Whitespace and Unicode are
preserved; unsafe controls are rejected. **Cancel edit** makes no write. Review
the paged events and losslessly paged goal/notes, use **Edit manual event**, or
**Delete manual event → Confirm delete**. **Keep event** cancels deletion.

Ordering is by start/end date and stable ID. The first event starting today or
later is **Next upcoming**. An event started before today and ending today or later
is **Ongoing**, separately labeled; past events are retained until explicitly
removed. Classification shows its UTC evaluation date, not an inferred local
event timestamp. Capacity is 1000 events. Events persist in the existing private
SQLite event table and encrypted backups; removing imports leaves them intact.
Notes/goals never enter model packets. No Google Calendar credentials, event
prioritization, training plans, automatic workouts or readiness judgments.

## Optional AI: two deliberately separate selections

**Trends → Experimental AI prototype** uses the original `/api/models` selection
and validator. It starts off; choosing a model does not generate. Only **Generate
observations** submits the exact displayed date/evidence binding. Accepted
observations are labeled AI-selected and separately paged from their supporting
metrics/dates, artifact and limitations. A failed, sparse, unavailable or invalid
whole response is withheld; switch back to metrics/facts without losing charts.
This prototype has no corrective retry or hosted fallback.

**Reviews → Model selection** configures the separate `review-focus-app-v1`
catalogue. Choosing, enabling or allowing one correction changes drafts only;
use **Save model choices** explicitly. A correction also needs operator permission
and the preset budget. The missing qualification guard disables enabling/generation
in normal wiring. Selection does not silently change the original prototype.

**Reviews → Preset editor** offers daily-combined, after-activity and weekly
presets, all initially disabled. Preset identity and coverage/focus have separate
pages. Choose preset/scope, consent/time zone, level and
budgets, one typed trigger at a time, or queue policy; then **Save schedule changes**.
The explicit saved zone survives browser travel and daylight-saving transitions.
Time/data/count triggers are supported; daily steps remain unavailable until
source support is independently verified. Invalid drafts are never clamped or
repaired; version conflicts require refreshing/reconciling instead of overwriting.

**Review overview** separates **Request now**, **Queue status**, **Schedule timing**
and **Completed coverage**. Request-now enqueues, not a synchronous model call.
Immediate interim work does not wait for an evening rule or suppress its later
thorough occurrence. Activity scope requires received activity identity, so the
manual button is disabled for that scope. Changed-data checks are conditional;
future arrivals have no invented time. Eligibility is not a promised start behind
running work. Stored results are metadata-only here; historical prose is withheld
because this view does not independently verify its support. Current facts and
validated prototype observations remain available in their separate views.

**Disable optional AI now**, **Disable schedules now** and **Pause reviews now**
are explicit writes. They invalidate pending/in-flight work; unrelated unsaved
schedule drafts are retained. Status polling makes authenticated reads only.
Restart turns selection/arming off, cancels pending work and marks running work
interrupted, without inference replay. Explicit selection/re-arming/request is
needed. See [queue/operator details](decisions/review-presets-and-scheduler.md),
[prototype limits](decisions/guarded-local-analysis.md) and
[separate runtime limits](decisions/guarded-review-interpretation.md).

## Verification and release boundary

Synthetic UI/API/storage tests cover CRUD, ordering, next/ongoing classification,
empty/stale/source/model-failure states, authentication/CSRF, strict validation,
abort/stale responses and lossless paging. Browser smoke checks use synthetic
responses only; they do not import actual records or call any model.
The new schema-2 source must not be promoted until the compatible backup/restore
helper and rollback procedure are deliberately approved; see
[backup/restore/rollback](infrastructure/backup-restore-rollback.md). Pi release and
actual-data end-to-end verification remain separate unfinished acceptance work.
