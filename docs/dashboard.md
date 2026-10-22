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

- **Trends** and **Reviews** share one factual review form: exact activities or
  periods, review/compare intent, wellness policy and factual focus. Periods cover
  day/night, calendar week/month/year, rolling 7/30/365 and custom 1–366 dates.
  **Request factual report** prepares facts only; it never syncs or infers.
- Results share a metric selector and **Chart / Comparison / Evidence** views.
  Evidence pages retain 25 source records each. Charts plot observed measurements,
  never invented zeros. Full selected totals and actual comparable windows remain
  distinct, including shorter calendar prefixes.
- Application-generated facts retain every supplied comparison, sparse/missing
  coverage, rule-selected focus, provenance and limitations. Unknown proprietary
  Garmin metrics remain unavailable, not estimates.
- **Overview** automatically prepares inert facts using the same authenticated,
  CSRF-protected client after the saved time zone is confirmed. Dates come from
  the server in that zone, not the browser. No model call is made on opening.

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

## Morning/evening preparation and exact previews

In **Reviews → Morning / evening slots**, configure each desired local time and the
saved timezone, then explicitly save/re-arm. There are no guessed times, automatic
source reads or missed-day replay. Restart requires re-arming. Pause affects routing;
it does not turn old snapshots into fresh evidence.

Each slot defaults to **Factual only**. An optional local/hosted **preview preference**
requires explicit provider/model identifiers; it neither proves configuration or
qualification nor permits sending anything. Queued/running here means local evidence
preparation, not inference. Hosted previews show awaiting exact consent with execution
still closed; no consent/send/run action is available. Local execution is blocked too.

**Completed coverage** distinguishes routing cutoff, actual evidence-read cutoff,
preparation finish and preview creation. Morning shows separately dated latest
wellness plus today's saved plan/context; evening covers the day so far. Neither
promises complete imports or treats absent activity as skipped. Inspect factual
evidence or an exact preview explicitly; polling/inspection does not prepare, retry,
sync, infer or change the plan. Stale/discarded previews are unavailable for consent.

Old unexecuted **automatic** preview copies expire when their outcome leaves the
24-entry queue history. Original workouts/wellness/context/plans, manual previews,
attempted reviews and unresolved runtime ownership are protected. Preparing a fresh
packet later is separate from replaying an old automatic occurrence.

## Exact local amendment review

In **Plans → Stored plan amendments**, explicitly browse stored proposal metadata,
then inspect one exact record. Browsing never generates a proposal, runs a model,
transfers data or changes the plan. Stored disposition is not a freshness claim.

Review every affected session's before/after values, authored permissions,
provenance, explanations, citations, uncertainty, expiry and base revision. Inspect
the bound source evidence and the complete captured local constraints, including
inputs absent from the immutable source packet. These are mechanical checks, not
medical suitability or verified coach approval. External calendar coverage remains
unavailable. Reading record pages revokes readiness until snapshot status is
refreshed successfully.

Only a current pending proposal for the displayed selected base can be approved.
Attest to the exact review, then select **Approve exact local amendment**. This
creates one local authored revision; it does not authorize inference, hosted
transfer or external calendar/workout writes. **Reject exact proposal** leaves the
active plan unchanged. A pending calendar overlay says **Draft proposal—not
applied** and never replaces authoritative entries. Stale, expired, no-change,
needs-confirmation, rejected and applied states cannot be approved.

Source failures, relevant refreshes, selection changes and expiry revoke readiness
and remove cached draft details/overlays. Reinspect rather than assuming an old
attestation still applies. Other plan edits and navigation remain locked during
review. Close a settled review to release that lock.

If a mutation response is lost or invalid, no automatic retry occurs. Keep the
review open; inspect authoritative state or explicitly resolve the original request
with the same payload and operation identity. A pending inspection alone does not
prove the original request stopped. A confirmed applied receipt is final even if
refreshing plans/calendar fails; close the review and reload authoritative plans,
not approve again. Reloading the browser does not preserve in-memory recovery
state; inspect authoritative records before taking any further action.

## Optional guarded review execution

The development-only experimental AI panel and prototype APIs have been removed.
Frozen evaluation packets and historical results remain independent of runtime UI.

**Reviews → Model selection** configures the separate `review-focus-app-v1`
catalogue. Choosing, enabling or allowing one correction changes drafts only;
use **Save model choices** explicitly. A correction also needs operator permission
and the preset budget. The missing qualification guard disables enabling/generation
in normal wiring. Factual preparation does not change this selection or authorize inference.

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
