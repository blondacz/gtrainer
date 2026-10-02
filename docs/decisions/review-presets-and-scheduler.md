# Editable review presets and time-zone scheduling

Tasks 7.4–7.6 implement code-owned scheduling, durable configuration, a coalescing
queue and explicit controls, not training plans, model qualification or a production AI rollout.
No model experiment, deployment, hosted transfer or resource-cap increase is part
of this continuation. The installed prototype stays unchanged.

## Explicit opt-in

The initial presets are `daily-combined`, `after-activity` and `weekly`. All presets
and the global scheduler start disabled. Daily-combined offers two-hour
changed-data checks and settled sleep arrival; after-activity offers settled
received activity; weekly has no assigned time until edited. There is no fixed
morning hour, inferred local zone or automatic model selection.

Preset ID, review type, scope, focus, interim/thorough level, budgets and triggers
are separate editable fields. Types remain descriptive `daily_combined`,
`after_activity` or `weekly`; a type does not mandate its scope or trigger.
Scopes are a day, Monday-based week, rolling 1–90 days, or a received activity,
optionally filtered by sport. Activity scope requires activity-arrival triggers
so a clock/count rule cannot invent activity identity. Both levels retain the
same factual/safety contract; neither prescribes workouts or improves guaranteed
precision. Background budget settings cannot exceed the explicitly configured
local runtime's operator policy or the existing 240000/600000 ms ceilings.

Enabling unpaused scheduling requires explicitly enabled selection in the
**separate** `review-focus-app-v1` catalogue, not the old `/api/models` selection.
Configuration does not select a model, grant hosted consent or enable inference.
Pause, disable, removal and changed selection clear/invalidate routing state.
Configuration persists, but arming is memory-only: restart requires an explicit
configuration update after selecting a model again. The durable queue invalidates
pending/in-flight work at these boundaries and serializes selection changes with
accepted-result publication. Completed older coverage remains historical, not current.

## Supported triggers

- `changed_data`: configurable 1–10080-minute checks (default 120 minutes), only
  producing a review when actual relevant records changed since arming/checking.
  Successful-but-unchanged imports and status/freshness timestamps do not count.
  The next check is not a promised next analysis; no remote sync is initiated.
- `daily_time` / `weekly_time`: explicit `HH:mm` and optional weekly ISO weekday
  1–7, interpreted only in the configured explicit zone or offset. Past occurrences before
  enablement are skipped. Delayed checks examine the latest eligible occurrence,
  not an unbounded backlog. DST gaps move forward; overlaps choose the earlier
  offset and execute once per local date. Host/browser travel does not change the
  zone. Monotonic occurrence dates prevent duplicate execution on clock rollback.
- `activity_arrival`, `wellness_arrival`, `sleep_arrival`: actual new/corrected
  records settle for configurable 0–3600 seconds (default 120). Sleep changes use
  populated sleep duration/score fields, not disappearance or unrelated HRV.
  Received time governs settling; observed date governs coverage. Late imports
  are not proof of when an event occurred or of a missed workout. Distinct
  activity identities remain separate; compatible date arrivals can share a
  routing intent. An unknown future arrival has no fabricated next timestamp.
  Pending arrivals follow stable record identity: date/sport corrections move or
  cancel obsolete work; disappearing sleep cancels its pending arrival, while
  unrelated HRV corrections do not restart unchanged sleep settling.
  A sleep arrival is also retained when an HRV-only correction arrives before
  the first scheduling check; superseding future metadata cannot erase that
  unconsumed sleep intent.
- `record_count`: new/changed records reach a configurable threshold once per
  configured local day or Monday-based week. Repeated unchanged imports count
  zero; genuine corrections count as newly received changed records.
  Unfinished counts are retained per period across clock rollback, not overwritten
  by an earlier day's counter.
- `daily_steps`: a once-per-local-day threshold only when the source has explicit
  verified daily-step support, exactly one usable current-day count, valid units
  and an integer finite value. Ambiguous providers, missing measurements and
  unsupported sport-filtered daily counts are unavailable, not summed or treated
  as zero. **No actual source is verified/enabled by this change**; normal app
  wiring reports `verified_daily_steps_unavailable` even if a candidate numeric
  field was imported. Synthetic tests use an explicit verified source allowlist.

## Private API and persistence

`GET /api/review-schedules` returns configuration/version, trigger availability,
next changed-data check, known next time/settling eligibility and current due
intents. Reads preview rules without consuming events or calling models/imports.
`PUT /api/review-schedules` accepts `{expectedVersion, configuration}` with the
existing authentication, exact Origin/CSRF checks and strict bounded 32768-byte
JSON parsing. Stale versions return 409; unsupported fields/rules/invalid zones
or budgets fail without altering configuration. Replies use `no-store`.

The app polls rules and pumps the queue every 30 seconds while running. With a
qualified runtime and explicit enablement this can start guarded background analysis;
normal wiring has no qualification guard and therefore makes no inference call.
Polling does not promise exact start time or initiate source sync.
The job is cancelled during application shutdown. Failures are withheld without
personal diagnostics; the authenticated status operation reports failures through
the existing bounded private-error path.

Schema **2** migrates schema 1 in place, preserving imported records, events and
sync status. It adds a private singleton JSON state and a transactional import
change journal. Post-enable eligibility follows the revision baseline rather than
comparing wall-clock receive timestamps with enablement: imports after a clock
rollback remain new changes. Temporarily future receive/observed metadata is
retained across cursor advancement and reconsidered when eligible; later identity
corrections supersede obsolete future metadata **before** evaluating eligibility.
Semantic normalized-record equality ignores map/set ordering;
duplicate unchanged data creates no delta. Successful corrections append minimal
category/date/received-time/sport/sleep-change/presence metadata and SHA-256 identity from
an unambiguous `[source,id]` tuple, not raw IDs or measurement values. Final records
in a duplicate-ID batch determine changes; invalid batches roll back records and
change entries together. Failed reads preserve history and produce no deltas.
Evaluation reads at most 50000 changes/records and refuses overflow, not truncates.
Import removal also removes journal/arrival/threshold state but preserves presets
and independently owned events. None of this claims forensic backup erasure.

The restore validator accepts complete schemas 1 and 2 and rejects missing review
tables/columns/migration markers and future versions. **Older deployed images
cannot open schema 2**. Any later promotion must update the operator-installed
restore helper deliberately; rollback requires a compatible pre-migration restore,
not simply changing the image. No live database/helper is migrated here.

## Durable queue and recovery

Rules emit snapshot-free intents with configuration/model versions, exact scope,
coverage dates, optional hashed activity identity, level/budget and fixed trigger
reason and the configured coverage-date zone. They never prepare personal model
packets or call providers. The durable queue accepts intents in the same SQLite
transaction as occurrence/cursor/latch state.
Missing/rejected/failed handoff does not consume due occurrences.
Intent IDs include routed scope/dates and their own relevant change revision or
calendar occurrence; unrelated imports cannot rename retries, and different dates
from one import cannot collide. Next calendar times skip already-consumed dates
even when the clock moves backward, including before the first armed occurrence.

Normal app wiring enables the durable receiver, while the independent production
resource/live-health guard remains unwired. Thus `executionAvailable` stays false
and no model is called. Receiver-free construction remains a tested preview-only
mode reporting `queue_not_configured`.

Matching pending type/scope/date-window/activity/focus/configuration/model bindings
coalesce reasons and occurrence receipts, take the highest level and combine bounded
budgets. Separate dates, activity identities and scopes never cancel one another.
Queue capacity is 256 total pending/running jobs; overflow rolls back the batch
and routing state together. A failed/full producer cannot prevent the consumer
from running. Pending records carry no health snapshot; the latest immutable scoped
report is prepared only after the durable running claim.

Default debounce/cooldown/maximum deferral are **120/300/600 seconds**, independently
editable within bounded policy. Maximum deferral caps repeated settling/cooldown
extension, not a promise to preempt a running inference. Manual requests become
eligible immediately; they do not wait for evening reviews or consume their future
calendar occurrence. One queue worker shares the prototype's inference mutex. If
the slot is busy before any attempt, the job returns to pending with preserved
receipts and deferral; this is not an inference retry. Running work reserves its
restoration capacity.

New/corrected imports do not repeatedly cancel a running snapshot. They may create
a follow-up; activity identity/date corrections cancel obsolete pending activity
coverage only. Finished results carry actual dates, generation time and digest;
status compares current data/configuration/selection and labels older coverage
stale. Coverage dates use the saved zone; `evaluatedOnUtc` remains the actual UTC
date even when the configured zone is ahead of UTC. Rejected interpretations
leave independently prepared facts available. Only validated facts/typed results
and bounded reasons/attempt metadata enter private SQLite, never raw output or
provider diagnostics.

Restart cancels pending intents and marks previously running work **interrupted**.
It does not replay inference. Selection starts off, scheduler arming resets and an
explicit selection/re-arm/request is needed. Disable/pause/configuration/model
changes invalidate pending work and prevent obsolete running acceptance. Old
completed coverage is retained with stale labeling, not silently made current.

`GET /api/review-queue` exposes private policy/jobs/outcome metadata and validated
stored snapshots. `POST /api/review-now` accepts
`{expectedVersion,presetId,level}` with strict 4096-byte JSON, authentication and
exact Origin/CSRF checks. Activity-scope manual requests are rejected because they
lack an actual received activity identity. Reads never import or infer. Model,
schedule and queue replies encode defaults explicitly.

## Controls

See [dashboard guide](../dashboard.md). Reviews has overview, preset-editor and
model-selection tabs. Editors show one subsection/trigger at a time; job, trigger
and completed-coverage lists are paged. Changed-data checks, due reviews and unknown
future arrivals have distinct labels. Eligibility is not a guaranteed start time.
Unsupported steps explain the unverified source instead of treating absence as zero.
Polling performs authenticated reads only and preserves unsaved drafts. Saves,
disable/pause and manual requests are explicit CSRF-protected actions. The metadata
view withholds stored prose because it does not independently verify historical
support; current complete facts and separately labeled prototype observations have
their own evidence-bound views. No calendar credentials or workout-plan controls.

## Verification

The task-7.4 milestone passed **166 synthetic backend tests**, including schema-1
migration/record preservation, atomic import rollback, semantic unchanged imports,
durable CAS/reopen/removal, authenticated/CSRF/bounded preset API, routing-only
handoff, calendar/threshold rollback, future metadata, activity/sleep correction
reconciliation, stable distinct intent IDs and ambiguous steps. Independent review
found no remaining issues after those regression fixes.

Unchanged frontend tests/build (**73 tests**), benchmark tests (**121**), utility
tests (**48**), strict specs, documentation links and diff checks passed. All six
retained benchmark captures still replay exactly. The frozen benchmark schema-1
seed/image/contract stays unchanged; only its source-schema comparison test now
checks the legacy migration separately from the new scheduler migration.

Subsequent queue/manual-event integration passed **215 backend tests** and build;
**35 queue rules/service regressions** cover capacity/restoration, timing caps,
fixed snapshots, stale labeling, cancellation, publication locking, zoned dates,
parent cancellation and restart recovery. Frontend controls have **81 synthetic
tests**; full UI/build and documentation checks are recorded in the task ledger.

Task 7.4 was completed at **23/33**; later local task progress is tracked in
[the task ledger](../../openspec/changes/garmin-training-guidance-foundation/tasks.md).
The user approved publishing the tested source for PR review only; it is not
deployed or qualified for actual-data inference. Image publication/promotion,
live migration and inference remain outside this approval.
The stable live schema, installed restore helper, model limits and protected
delivery files are unchanged.
