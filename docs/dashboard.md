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

**Overview dates:** edit First date and Last date, then select **Show range** to
update activity totals and wellness averages. Pending edits do not change the
displayed results until submitted. The applied range and the preceding comparison
period are stated below the form in plain language. Import status counts all stored
dates, the next event is upcoming relative to today, and the review queue is current;
these three status cards are explicitly labeled as not date-filtered.

Card links use the same names as the top tabs: activity/wellness metrics open
**Trends**, import cards open **Source**, the next event opens **Events & plans**,
and the queue opens **Reviews**. Import cards select **Import status** and the
matching Activities/Wellness category, rather than the import form. The event card
focuses **Manual events**, selects that exact upcoming event and opens its full goal;
it does not default to the oldest event or the plan editor. A removed event is reported
as unavailable rather than silently replaced. The queue card selects **Queue status**,
not the action for requesting a new review. Keyboard focus follows these destinations.
When a report time zone is missing, **Set report time zone in Reviews** opens its
settings directly without saving settings or enabling a schedule.

The applied Overview dates carry into the Trends
custom-date form; select **Request factual report** there to load details. Returning
to Overview retains the applied range for the signed-in session. Dates are not saved
in browser storage and are cleared on sign-out or page reload. Navigation does not
authorize inference or source reads.

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

**Request factual report** displays calculations without saving a snapshot.
**Prepare evidence snapshot** is a separate explicit save of the selected local
evidence for later inspection; it does not authorize inference, hosted transfer or
a plan change. **Connected insights** is an existing connected-review inspector,
not a generator or a review list. It requires a connected-review ID, which is not
interchangeable with a factual evidence snapshot ID. Without that ID, its **Open
Trends** and **Open Context** buttons lead to reports and note management respectively.

## Experimental local descriptive reviews

The normal factual form now includes **Open local model reviews**. Opening it reads
only private policy status and saved results; it never prepares evidence, imports
records or starts a model. This source implementation is tested with fake owned
transport. A real local runtime and persistent Mac installation are not accepted yet.

For a new review, explicitly **Prepare evidence snapshot** with the desired source
selection, then **Prepare local review preview**. The preview names its exact date
coverage and selected model. After an app restart, **Re-arm manual local model jobs**
is separate from **Request local model review**. Request availability means permission
to submit through runtime guards, not live health, qualification or guaranteed success.
No hosted request or automatic model schedule is enabled by these actions.

**Refresh review status (read only)** shows saved state and stopping status without
resending the request or renewing its deadline. **Cancel this model job** is an explicit
mutation; uncertain stopping continues to block new jobs. Per-job deadlines never
expire the application or delete owner records. Lost browser responses do not cause a
retry; read saved results to discover the recorded outcome instead.

Saved rows show coverage, model, local/hosted provenance and stored outcome. Select a
row to reopen its source snapshot and validated result without copying internal IDs.
Interpretations remain separate from factual calculations, disclose uncertainty, and
open their exact cited evidence. Stale, rejected, removed and unverifiable output is
withheld rather than salvaged or presented as current. An empty structurally accepted
draft is not proof of usefulness. Historical hosted selections cannot execute in local UAT.

Use **Your feedback on this stored result** to save an attributed rating/correction.
Feedback never rewrites the original output, starts another generation or becomes a
verified measurement. Result deletion refuses while any historical feedback dependency
remains; review its deletion through the existing Context controls rather than silently
purging shared notes. Source deletion includes affected results and their feedback in
the same fingerprinted dependency preview and explicit redaction/confirmation boundary.

Large evidence packets can be blocked before a model preview exists. In particular,
the current 32-KiB packet limit can reject even short period comparisons with default
wellness assessment. The UI does not drop chosen evidence to make these fit. An owner
may explicitly choose a smaller permitted scope or wellness policy; the factual report
remains available. Token/context admission is separately required before real execution.

## Read-only source

**Source → Import history** starts an explicitly requested Intervals.icu read for the chosen
dates. **Import status** separates activity and wellness categories, latest attempt/success
and stored observed dates. A failed read can coexist with retained charts.
Garmin-to-Intervals.icu freshness remains unverified: missing imports do not prove
missed activity, and missing wellness values do not imply illness.

Import status covers **all stored dates**, not the Overview filter. When opened from
an Overview card, it repeats the selected Overview dates and offers **Open selected
dates in Trends**. This opens the matching report form without importing or running a
model. The most recent stored-record date is separate from import timestamps and may
be outside the selected range. Timestamps and record checks are available in an
expandable section; status and source-limit sentences are not split into text pages.

**Source → Remove imports** confirms removal of local imported records and review snapshots.
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
