# Scoped factual reviews and comparisons

Scoped reviews use the canonical `GET /api/reviews/options` and
`POST /api/reviews/prepare` endpoints. They provide inspectable factual reporting,
not automatic inference, model selection, or plan-amendment execution. Overview
automatically prepares inert facts after the saved zone is confirmed; it never
enqueues a review or requests inference.

## Two targets, independent intent

Shared review controls are available alongside the charts and evidence pages,
which page at most 25 records. Review preparation uses the server date and
CSRF-protected authenticated requests. `daily_combined` is the default factual
focus, not a temporal scope.

- **Selected activities:** review exactly one or several chosen imported
  identities; comparison needs at least two explicitly selected sessions. The
  enclosing dates do not select intervening workouts. Mixed-sport/triathlon
  selections are valid without claiming they form one verified event.
- **Period:** review or compare a source-date range, using calendar, rolling,
  selected day/night or custom semantics. Available relevant wellness is included
  by default. Switching modes shows the changed target and resets its wellness
  default, not silently reinterpreting an activity set as a date range.

Activity wellness defaults **off**. **Display only** shows associated source-date
observations but excludes them from future provider-packet input. **Assessment
context** explicitly selects contextual wellness for later assessment; it does
not automatically compare those values between sessions. Longer baselines are
separate future evidence windows, not implied by this factual report.

Session comparisons are bounded side-by-side values, not quadratic pair expansion
or an arbitrarily chosen reference workout. Moving and elapsed duration remain
separate physical-time quantities, never equivalent sport effort. Distance is
compared within a sport. Calories, average HR and source load are displayed with
their provenance, not assumed compatible intensity or training effects merely
because units match. Missing/invalid values remain unavailable.

## Date and comparison meanings

The server captures one evaluation instant and reads the explicitly persisted
timezone. Missing persisted state is not replaced by a UTC fallback. The browser
timezone is never substituted. No automatic inference of dates, context, or
activity selection occurs.

| Scope | Evidence dates / default comparison |
| --- | --- |
| Activity selection | Exact verified source identities/dates; no invented prior activity |
| Day | Selected source date; preceding day |
| Night | Only a verified source-date association; otherwise needs confirmation, not a day fallback |
| Calendar week | Monday–Sunday, through the saved-zone current date when current |
| Calendar month/year | Actual calendar boundaries, including leap dates; current period is so far |
| Last 7/30/365 days | Anchor minus N−1 through anchor; preceding N dates |
| Custom | 1–366 inclusive historical dates; preceding equal-length range |

For calendar comparisons, compare the first shared **ordinal date count** of
the selected period and the preceding calendar period. Keep full selected and
full previous-calendar totals separately labeled:

- March 1–30, 2026 compares March 1–28 against February 1–28; March 29–30 are
  unmatched, not silently discarded from selected totals.
- Completed February 2026 keeps full January 1–31 totals and unequal-duration
  disclosure; its comparable pair is February 1–28 versus January 1–28.
- Completed 2024 compares the first 365 dates of each year; December 31, 2024
  is unmatched. This is ordinal comparison, not month/day alignment.

Every period scope may use an explicit historical 1–366-date comparison range,
including an overlapping or later historical range. Its actual dates, overlap
and unequal duration are disclosed, without a claim of independent or
like-for-like periods. Arithmetic changes use the labeled comparable pair,
not full selected totals paired accidentally with a shorter previous window.

Source-local activity dates and wellness source dates are preserved. A directly
selected activity with a verified past start instant can have a source-local date
ahead of the saved-zone date; the mismatch is visible. Without a verified past
instant, that selection needs clarification and produces no observed-session
report. Period windows do not expand to include it. Legacy arrival suppression,
weekly endpoints and occurrence hashes remain unchanged.

Dates are strictly four-digit years 0001–9999. Selected windows that cross that
domain are rejected, not clipped. A valid window near year 1 can remain reportable
with its preceding comparison unavailable. Current periods disclose the cutoff;
they do not promise complete imports or end-of-day totals.

## Available versus unverified context

The current adapter supplies source-date sleep and HRV where imported, but no
verified preceding-night mapping or training-readiness field. A dated sleep value
is not automatically "the sleep before this workout." Observation dates, session
timestamps and import availability have separate meanings. Shared day observations
retain one source identity when referenced beside several activities.

Readiness remains unavailable unless a verified adapter supplies an attributed
vendor observation. Never reconstruct it from sleep/HRV, assume those are all its
inputs, or claim its formula is known. Sleep and HRV remain separate evidence
even if a later adapter supplies readiness. None alone establishes a causal
performance explanation, recovery, sport clearance or medical safety.

Period means currently reuse existing **per-record** descriptive arithmetic.
They are not the compatible daily observations/median baselines required by later
personal signal rules. New nightly semantics and detailed streams remain visibly
unverified/unsupported, not synthetic replacements for absent source fields.

## API and bounds

- `GET /api/reviews/options`: authenticated options for shared controls.
- `POST /api/reviews/prepare`: authenticated, same-origin, CSRF-protected factual
  review preparation. Reports set `factualReview` to null for selected-activity
  subjects or without an available comparable pair; otherwise facts use the actual
  comparable pair, not full selected-period totals.
- These endpoints do not sync, infer, or implicitly select activities/context.
- At most 1,000 distinct verified activity IDs spanning 366 source dates; custom
  selection/comparison ranges each 1–366 dates. The existing **32 KiB request**
  ceiling also applies, so a payload can hit its byte limit before its ID limit.
  There is no automatic window reduction, truncation or limit increase.
- Evidence reads have one combined **50,000-record** budget. Indexed activity
  lookup does not decode unrelated history. Period reads use the union of required
  ranges, without fetching every intervening date for distant comparisons.

## Storage and operational boundary

Fresh history schema version 1 uses application id `0x47544E52`, exact DDL, 27
tables, 12 indexes and 5 triggers. It has no migration/backfill path or
`schema_migrations` table. Schema creation, seeds, and markers are atomic in the
caller's transaction. Malformed or nonempty obsolete databases are refused with
actionable guidance; they are never reset. Existing application DB versions 1–8
are obsolete and are refused without automatic migration or deletion. Development
review-store TTL/isolation is unchanged.

Restore source validation is read-only: current manifest, SQLite schema/FK
integrity, and minimal state sanity only. This is not runtime acceptance. Installed
operator helpers are not updated; operators must explicitly rerun setup from the
checkout to copy the public manifest. No live backup, reset, decryption, or helper
installation was performed or authorized.

The [compatibility baseline](scoped-review-compatibility-baseline.md) retains
exact legacy packet/prompt and fixture hashes. Production inference, new-profile
qualification, real-runtime acceptance, source access and deployment remain closed.

## Historical implementation record — superseded

The following verification is historical and superseded by the consolidation;
its counts are not current verification results.

| Command/check | Actual result |
| --- | --- |
| `JAVA_HOME=$(/usr/libexec/java_home -v 25) ./gradlew --offline --no-daemon :backend:test` | 410 tests, 55 suites; zero failures/errors/skips |
| Legacy compatibility checks within that run | 5 baseline and 13 V1 contract tests; frozen packets/prompts remain pinned |
| `npm --prefix frontend test -- --run` | 270 tests, 16 files passed |
| `npm --prefix frontend run build` | TypeScript check and Vite build passed |
| `python3 -m unittest discover -s scripts -p 'test_backups.py'` | 8 synthetic backup/restore tests passed, including intentional schema-8 refusal |
| `openspec validate scoped-reviews-and-adaptive-planning --strict` | Passed using `/Users/tomasklubal/.local/bin/openspec` |
| Local Markdown links and `git diff --check` | No missing local links or whitespace errors |

Tests cover source-independent authenticated queries, explicit timezone confirmation,
synthetic v1/v7 migration and reopen, identity-preserving corrections/deletion,
selected lookup amid 50,100 unrelated malformed payloads, sparse period-union
reads, calendar arithmetic, source-date/instant travel boundaries and verified
future-instant exclusion. Frontend tests exercise map-shaped wellness, mode defaults,
real dates, stale-response fences, session changes, unmount aborts and range-bound
pagination. Labels and form controls are exercised through accessible queries;
this is not a claim of a separate human accessibility audit.

Intermediate checks exposed an invalid legacy-week test constructor, outdated
schema-7 test expectations and an existing authentication-notification effect
race. The constructors/expected schema were corrected, and the unchanged
authentication assertion now waits for its effect. Independent code review also
identified revision-counter timezone inference and pagination changing its date
query; both were fixed and covered by regressions before the passing runs.

No live/private database, source account or model runtime was contacted during this
historical work. Do not interpret historical counts as current results. Frozen benchmark seeds, calculation
semantics, scheduler reports, archived ledgers and fixtures remain unchanged. The
compatibility baseline is a frozen internal calculation contract, not an old HTTP
compatibility guarantee.

## Consolidation verification — 2026-10-07

Primary-session checks after integration:

| Check | Result |
| --- | --- |
| JDK 25 offline `:backend:test` | 403 tests, 54 suites; no failures/errors/skips |
| `npm --prefix frontend test -- --run` | 220 tests, 15 files passed |
| `npm --prefix frontend run build` | TypeScript and Vite passed |
| Synthetic `test_backups.py` | 9 tests passed; no real backup/decryption/install |
| Strict OpenSpec validation | Passed |
| Local Markdown links and `git diff --check` | 50 Markdown files checked; no missing links or whitespace errors |

The checks retain frozen fixture/packet/prompt goldens, privacy/development-isolation
tests and queue cancellation/serialization. New tests cover atomic DDL/marker/seed
rollback, legacy and partial-schema refusal, missing persisted scheduler fields,
comparable-prefix factual supports, display-only wellness exclusion, CSRF and UTF-8
request bounds, source provenance, pagination and saved-zone overview dates.

One concurrent full-suite run hit the pre-existing connected-review timing test's
5 ms scheduling margin. Full backend runs subsequently passed alone with that
test and its assertions unchanged. This remains a timing-sensitive test, not a
runtime acceptance result.

The three former reporting panels contained 636 lines / 55,254 bytes; the shared
reporting host, controls, client, parser, metric types and evidence card contain
393 lines / 53,900 bytes, including the new scope functionality. This comparison
excludes overview tiles and unrelated UI; it is not a repository-wide reduction
claim. The prototype runtime and eight superseded HTTP paths are removed. Fresh
schema DDL is defined once outside `HistoryStore`, with no migration chain.

Consolidation task 1.5 and intake/lifecycle/applicability/commitment/confirmation
tasks 2.1–2.6 are complete; authored plans are next. Optional model
suggestions and plan/evidence/adaptation workflows are not yet
implemented or qualified.
No current work was committed or pushed. No application database was reset, no
operator helper installed, and no inference, download, live-source access,
qualification, publication or deployment was performed. Those gates remain closed.

## Intake repository foundation — 2026-10-15

Task 2.1 adds attributed notes with stable intake IDs and append-only revisions,
and explicit provenance links to exact existing context revisions. It never
creates a structured constraint, copies a whole note into each context item,
infers, syncs or submits queue work. Existing context IDs/content and frozen V1
serialization are unchanged. Attribution is user-supplied, not independently
verified; `enteredBy` and `recordedUtc` are server-owned.

Authenticated API (all writes require same-origin CSRF):

- `GET /api/context-intakes` and `POST /api/context-intakes` list/save notes.
- `GET /api/context-intakes/{id}/history` retains exact wording and revisions.
- `PUT /api/context-intakes/{id}` takes `{expectedRevision, note}`; `note` has
  `content`, `sourceCategory`, `authorAttribution`, `observedOn`.
- `GET /api/context-intakes/{id}/links` inspects exact provenance references.
- `POST /api/context-intakes/{id}/links` takes `{expectedRevision, contextId,
  contextRevision}` and explicitly associates already-authored context. An
  identical association is idempotent; stale revisions and conflicting origins
  return fixed 409 errors without partial writes. No field equality is claimed.

Notes/revision histories and per-intake links are bounded to 1,000. Note content
is at most 4,000 characters and author attribution 160; bodies are bounded to
16 KiB UTF-8 (association 1 KiB), with strict real dates, fields, duplicate keys
and encoding checks. Responses are no-store; payload-bearing DTO diagnostics
are redacted. Notes may be user reports or user-entered clinician/coach guidance;
review feedback remains owned by the existing snapshot-feedback workflow.

Source corrections append a revision and flag older links `sourceChanged`.
Only dependent connected reviews become stale; confirmed item wording does not
silently change. The user chose explicit reconfirmation, not automatic extraction
or replacement. Linked context correction/deletion through existing routes now
fails closed (409), including deletion through dependent feedback. Until tasks
2.5/2.6 implement explicit confirmation/redaction, do not present these guards as
a completed editing/deletion workflow. Source deletion has no endpoint yet.

The confirmed shared-note deletion design requires dependency preview,
user-approved replacement wording, purge of old source revisions and dependent
sensitive content, and explicit surviving-sibling reconfirmation. It must not
delete unrelated siblings or silently leave the deleted text in source history.
Retained encrypted backups remain subject to their separate retention policy.

Current DDL adds two intake tables and a reverse-link index to the sole canonical
manifest (15 tables, six indexes, one trigger). It retains `user_version=1` and
GTNR identity; previous development layouts are incompatible and refused, not
migrated, repaired or reset. `HistoryStore` owns every monitor/transaction; the
cohesive intake helper owns only SQL operations on that connection.

Verification: offline JDK 25 backend suite **412 tests / 56 suites passed**;
the nine synthetic backup tests passed against the extended manifest. Tests cover
fresh/reopen, exact IDs, expected-revision races, write capacity, FK constraints,
association and correction/invalidation rollback, source-changed status,
unrelated-review preservation, indirect deletion guards, authentication/CSRF,
strict bounded input and zero source/model calls. Frozen golden tests pass.
The first compiler-daemon run timed out; final runs passed with
`--max-workers=2 -Pkotlin.compiler.execution.strategy=in-process` (no project
build configuration change). Frontend runtime was not changed in this task.

## Context lifecycle and interval applicability — 2026-10-15

Tasks 2.2/2.3 add revision-linked lifecycle metadata and a pure new-profile
retrieval helper, without changing `AthleteContext` or V1 retrieval/packet bytes.
Missing metadata defaults to **unresolved**; no migration or inferred recovery is
performed. Current DDL now has **16 tables, six indexes and one trigger**, still
with one canonical manifest, GTNR identity and version 1. Earlier development
layouts are refused, never automatically repaired or reset.

- `GET /api/context-lifecycle[?asOf=YYYY-MM-DD]` is authenticated, no-store and
  read-only. It includes latest current, upcoming, historical/expired, retired and
  resolved items, original context fields, lifecycle metadata and reason flags.
  Its default evaluation date is the server UTC date, not an inferred browser
  zone. Explicit date projection does not authorize inference or future physiology.
- `PUT /api/contexts/{id}/lifecycle` requires same-origin CSRF, at most 1 KiB of
  strict JSON and `{expectedContextRevision, resolution, reviewDueOn}`. Explicit
  `null` clears the review date. An expected-revision mismatch returns 409. The
  write appends a context revision with identical original text/attribution/dates,
  metadata and exact intake provenance in one transaction, then fences dependent
  connected reviews. Linked source changes remain needs-reconfirmation: changing
  lifecycle state never silently binds a newer intake revision.

Applicability answers **when guidance applies**. Review due requests a targeted
update, never expiry, recovery or clearance. Ended/retired unresolved safety items
remain historical and need clarification; they are not displayed as current
instructions. Explicit user resolution deactivates new-profile operational
blocking without asserting medical clearance. Retirement alone does not resolve
an item. Content correction resets resolution to unresolved; retirement preserves
the existing lifecycle. V1 continues its prior semantics independently.

Historical interpretation uses **current canonical corrected revisions** with
original observation/applicability dates, not a reconstruction of what was known
then. This basis is explicit in the report. A later revision observed after the
captured cutoff does not resurrect an older revision.

`ContextLifecycleService.retrieve` uses correlated date/sport/activity/review
targets, with separate historical-evidence and future-amendment results. Older
applicable restrictions and future-start restrictions are retained. Selected
sessions do not create a cross-product of unrelated dates/sports/identities.
Unresolved ended/retired safety items, stale status and unknown symptom
applicability require clarification for matching future targets; known open-ended
restrictions remain enforceable, not automatically unknown. Resolved items may
remain attributed historical context but do not silently re-enter future
constraints. This helper does not yet assemble a new-profile snapshot or apply a
plan; those later tasks remain unchecked.

All relevant restrictions and clarification items bypass optional ranking. Missing
mandatory budgets, overflow, malformed restrictions, conflicts or required
confirmation block model preparation while factual inspection remains available.
Mandatory identity counts do not double-count an item used in both roles. Conflict
pairs must overlap within the same target interval; conflict checking emits each
pair once rather than materializing duplicate lists for every target. Different
maximum durations remain conflicting, not silently replaced by the stricter one.

Primary verification: **433 backend tests / 59 suites passed**, including frozen
goldens, CAS races, original-field/provenance copying, legacy unstructured rows,
transaction rollback, historical visibility, stale illness, scope isolation,
conflicts/overflow and a 1,000-target high-fan-out regression. Nine synthetic backup
tests pass against the extended manifest; strict OpenSpec and whitespace checks
pass. Frontend runtime remains unchanged; no inference, live operation, database
reset, helper install, commit or deployment occurred.

The final integration rerun also passed all **220 frontend tests** and the frontend
TypeScript/Vite build. Local links in 50 Markdown files passed. Frozen tracked
contracts and the separate `connected-insights-context` change remain unchanged.

## Confirmed commitments and availability — 2026-10-15

Task 2.4 adds confirmed local commitments/availability with one-off, daily or
weekly recurrence and explicit timezone, interval/end date and cancel/reschedule
exceptions. The user approved multi-day intervals up to **366 local wall-clock
days**, separately from the **1–366-date projection horizon**. Weekly recurrence
uses the anchor weekday, intervals are 1–366 repeat steps and the optional end
date limits original occurrence start dates. Monthly/yearly/positional rules and
external calendar adapters are unsupported, never inferred.

Authenticated, no-store API:

- `GET /api/commitments` and `GET /api/commitments/{id}/history` inspect current
  definitions or immutable revisions.
- `POST /api/commitments/confirm` explicitly confirms `{intakeId,
  expectedIntakeRevision, definition, contextId?, expectedContextRevision?,
  eventId?}`. `definition` contains `label`, `kind` (`commitment`/`availability`),
  `timeZone`, minute-precision `startsLocal`/`endsLocal`, optional recurrence
  `{frequency, interval, endOn?}` and exceptions
  `{originalDate, action, startsLocal?, endsLocal?}`. Cancel has no replacement
  times; reschedule requires both. Each exception must identify a real original
  instance and may appear only once (at most 1,000 exceptions).
- `POST /api/commitments/{id}/confirm` takes `{expectedCommitmentRevision,
  confirmation}` and appends a corrected/reconfirmed revision under the same ID.
  All confirmations require same-origin CSRF and strict bodies of at most 32 KiB.
- `GET /api/commitments/calendar?oldest=YYYY-MM-DD&newest=YYYY-MM-DD&timeZone=Area/City`
  derives current occurrences without inference, sync, queue writes or storage
  of a second calendar copy. Coverage explicitly says local-only/external
  unavailable; it is not a plan or permission to amend one.

Occurrences have half-open instant intervals and stable identity
`commitmentId:originalDate`; correction changes their evidence revision, not the
identity of the original recurrence date. Projection includes overlaps starting
before the horizon and exceptions moved into it from outside, including one-off
items. Definition and horizon zones may differ. Local endpoints retain wall-clock
time/day offsets across DST; gaps shift forward and overlaps use the earlier
offset, with explicit flags. Non-positive resolved intervals require clarification
and return a fixed 409 without partial calendars. A skipped civil date has an
empty instant horizon, never a spurious overlapping occurrence.

Original intake source/author is read through exact revision references; it is
not duplicated in definition JSON. Confirmation actor/time is server-owned.
Source corrections and linked context revisions mark confirmed entries as
needs-reconfirmation: they remain inspectable/non-authoritative and block
constraint coverage, even when no occurrence is in the requested horizon. This
must not mean free time or zero availability. Explicit reconfirmation checks all
expected revisions in one HistoryStore-owned transaction. Lifecycle resolution
does not itself erase independently confirmed calendar intervals, nor silently
reconfirm source wording. A source-changed linked context must be reconciled
through the later context confirmation workflow first.

An optional event association checks only a valid existing event ID; event notes
and goals are neither read into definitions nor made constraints. Event records
remain independent. No destructive commitment endpoint exists before the atomic
redaction/dependency workflow in task 2.6. Historical as well as current commitment
references guard old context PUT/DELETE routes against bypasses.

The canonical fresh schema now has **17 tables, eight indexes and two triggers**,
including immutable commitment revisions and reverse reference indexes. Marker
version/identity and strict incompatible-schema refusal remain unchanged; no reset
or migration was performed. Data is bounded to 1,000 commitments and 1,000 revisions
each. Projection fails closed above 50,000 candidate instances/occurrences rather
than truncating. Writes use the existing single-writer HistoryStore ownership;
cross-process write contention is not claimed to be a new supported write API.
Corrections load only the latest definition, not the full revision history.

Verification: **461 backend tests / 62 suites passed**; **nine synthetic backup
tests**, strict OpenSpec and whitespace checks pass. Coverage includes fresh
reopen, exact references, atomic rollback/CAS races, source and lifecycle races,
revision/record limits, multi-day overlap, cancel/reschedule, timezone extremes,
DST, skipped dates, input-domain boundaries, 50,000/50,001 candidate limits,
stale availability, historical dependency guards and authenticated private API
correction/reconfirmation. Model/source call counts stay zero. Frozen contracts
and qualification evidence remain unchanged. UI controls are still task 2.5;
snapshot/proposal invalidation remains owned by later tasks 2.6/4/8.

## Single-entry confirmation controls — 2026-10-15

Task 2.5 integrates intake controls into the existing athlete-context panel,
sharing one field editor with legacy direct context corrections. Enter and save
original wording/attribution/observation date once; that operation alone creates
no active symptom, restriction or calendar constraint. Qualified extraction is
visibly unavailable and no provider is called. Users select an excerpt, or
explicitly reuse the whole note as a draft, without retyping it. Typed calendar
drafts require exact local dates, timezone and recurrence; “Wednesday” is never
silently assigned a date or repetition rule. Draft exceptions support explicit
cancel/reschedule and multi-day spans from the confirmed task 2.4 contract.

Staging/editing/removing drafts performs no HTTP mutation. The inspection area
shows full selected fields, attribution and lifecycle before per-item or selected
batch confirmation. A single-item confirmation leaves other drafts inactive.
Targeted existing-item editors retain stable IDs and explicit expected revisions,
scope/applicability and lifecycle metadata. Current source-changed status comes
from the latest context plus its exact revision link, not superseded historical
links. Original source correction remains distinct from item reconfirmation and
from privacy erasure. Original history is retained; shared-source deletion stays
unavailable until task 2.6. Legacy direct context routes remain guarded for linked
items; the new authenticated confirmation command is the intentional update path.

`POST /api/context-intakes/{id}/confirm` accepts `{confirmationId,
expectedRevision, contexts, commitments}`. Context items contain a typed
`context`, explicit `lifecycle`, and optional paired `contextId` /
`expectedContextRevision`. Context source/author must equal the exact intake
revision. An existing item must already be explicitly associated to that intake;
the command cannot silently adopt unlinked or foreign-source context. Calendar
items contain `definition`, optional paired `commitmentId` /
`expectedCommitmentRevision`, optional exact `context` reference, or a
`contextItemIndex` into the batch's resulting confirmed contexts. Existing
context/event calendar associations are retained unless a new exact context is
explicitly selected. Source-changed context cannot be silently reconciled by a
calendar edit. Mixed context/calendar writes, source links, lifecycle and review
invalidation share the existing HistoryStore transaction; no nested transaction.

Confirmation requires same-origin CSRF and a UUID, with **1–20 items and an
aggregate 32 KiB UTF-8 limit**. The UI preflights the byte size and never splits an
atomic batch. An immutable logical confirmation receipt contains only IDs,
revisions and request hash, not a copied health-text response. An identical UUID
retry returns original revision references; reuse with changed source/body
conflicts. Replays never re-invalidate reviews created after the original action.
The receipt table adds one object to the canonical manifest: **18 tables, eight
indexes, two triggers**, retaining GTNR/version 1 and strict refusal of old layouts.
No schema migration/reset or installed-helper update was performed.

The UI validates backend-shaped confirmation counts, corrected identities,
revisions, source references and consumed nested fields before reporting success
or discarding drafts. Transport failures, mutation 5xx or malformed success
responses are uncertain, not safe-to-retry failures; the UI blocks resubmission
pending reload/inspection. There is no automatic retry or provider fallback.
Structured restriction text is user-confirmed attribution, not medical clearance;
improving/stale/retired context never implies recovery. Confirmation changes no
training plan and grants no hosted transfer, inference or amendment approval.

Verification: **476 backend tests / 64 suites**, **235 frontend tests / 16 files**,
TypeScript/Vite build and **nine synthetic backup tests** pass. Strict OpenSpec,
local Markdown links, whitespace and frozen-contract checks pass. Tests include
rollback and receipts, source/identity validation, no-inference private API bounds,
single/batch drafts, relative-date clarification, targeted reconfirmation,
aggregate UTF-8 preflight, unknown-outcome blocking and strict response handling.
No inference, live source/DB operation, commit, publication or deployment occurred.

### Shared-source deletion implementation (2.6)

The approved redaction policy needs no additional product-policy decision. The
implementation must preview a bounded typed-dependency closure across historical
as well as current source/context/commitment references, snapshots/derived
feedback and content-free receipts. Bind exact edges as well as revisions:
association can change an edge without changing a context revision. Sharing a
snapshot does not make its other independent context inputs deletable.

On explicit approval, recheck the preview in the owner transaction, append
user-approved source replacements and surviving sibling confirmations under
stable IDs at pre-purge maximum revision + 1, then purge affected old histories,
dependent outputs/feedback and replay receipts in FK-safe order. Require an
explicit disposition for any commitment whose optional context is deleted;
never guess a replacement or silently detach it. No survivor may fall back to an
older current row. Clear successful-operation UI caches/drafts. Activities,
independent events and unrelated contexts remain intact; backup/forensic-erasure
limits must be disclosed. This follows declared dependencies and approved
replacement text, not semantic discovery of every independently copied phrase.

Also fence stale pre-deletion prepared bindings at snapshot creation, not only
late publication, so deleted text cannot be reinserted after purge. Verify
multi-source history, feedback recursion, cycles/overflow, association races,
transaction rollback, revision exhaustion, receipt replay and marker removal.
Future plan/proposal/snapshot domains must join these dependencies before
activation; those later domains are not implemented or verified by task 2.6.

Implemented endpoints: authenticated read-only
`GET /api/contexts/{id}/deletion-preview` and same-origin CSRF-protected
`POST /api/contexts/{id}/redact-and-delete` (32 KiB aggregate body). The preview
binds historical edges and exact revisions under a cumulative 50,000-row work
bound. The command requires every survivor and calendar disposition, preserves
stable IDs at maximum revision + 1, and purges old source/context/calendar history,
dependent snapshot/output/feedback chains and confirmation receipts. Other inputs
to a shared snapshot are not themselves deleted. Existing event associations are
preserved; deleting/detaching a calendar projection never deletes the event.

The legacy direct DELETE uses the same recursive purge and fencing primitive
only for an unshared closure; shared dependencies require the approved workflow.
Opaque context ID/revision fences stop late prepared bindings from restoring
purged text, while late publication cannot find removed snapshots. No durable
health-text or sensitive-content hash audit is added. The canonical fresh schema
at this checkpoint has **19 tables, eight indexes and two triggers**; its version
and GTNR identity remain unchanged, with no migration/reset performed.

The existing context panel now shows the dependency preview instead of a second
unrelated deletion interface. Source replacements and structured survivors are
prefilled for editing, not retyping; each survivor and the final displayed batch
need explicit approval. Edits clear approvals. Calendar items with deleted context
require an explicit new surviving association, explicit detachment or deletion.
Successful deletion and uncertain-outcome reload remount the complete panel,
clearing intake/draft/history/feedback caches. Transport/5xx/malformed-success
outcomes block automatic or blind retry. Backup, prior-transfer and forensic
limitations remain visible throughout.

Verified task-2.6 checkpoint: **493 backend tests / 66 suites**, **242 frontend
tests / 17 files**, TypeScript/Vite build and **nine synthetic backup tests** pass.
Coverage includes recursive changed-category feedback, independent snapshot
inputs, legacy deletion fences, replay rejection, every stored TEXT column marker
scan, retained event association, commitment-only provenance, stale edge binding,
late SQL rollback, revision exhaustion and bounded-work refusal. No live data,
inference, commits or deployment were used. Proposal and new-profile dependency
integration remains mandatory when those later stores are implemented.

## Authored plans and capability audit — 2026-10-15

Tasks 3.1–3.4 are implemented. Events & plans contains local authored-plan
entry/edit/selection, inert browser drafts and read-only calendar inspection.
Authorship is attributed, never independently verified. A saved event/goal cannot
stand in for an authored plan; new-plan save does not select it. Session UUIDs
survive explicit edits and removals create a new full-plan revision, not history
rewrites. Exact plan revision and selection-generation checks reject stale and
A→B→A edits; selected-plan edits advance both atomically. Effort-scale identities
have immutable definitions. No permissions are granted by omitted fields.

Authenticated GET routes are `/api/plans`, `/api/plans/{id}`,
`/api/plans/{id}/revisions/{revision}`, and `/api/plans/calendar` with exact
`oldest`, `newest`, `timeZone` query fields. CSRF-protected mutations are
`POST /api/plans`, `PUT /api/plans/{id}` and `PUT /api/plans/selection`.
Creation/correction have 32 KiB UTF-8 request and canonical-definition ceilings;
selection is bounded to 4 KiB. The fresh schema now has **22 tables, eight indexes
and three triggers**, with an exact required plan-selection seed. Backend and
offline restore checks refuse a missing seed without reseeding a nonempty schema.

Explicit policy supplies timezone, 1–366 inclusive horizon dates and import grace.
Supported target/basis pairs and move/reduction/omit permissions are documented
in the change design. Date-only and timed schedules stay distinct: timed gaps are
rejected, overlaps require explicit offsets, and saved offsets are canonicalized.
Date-only cutoffs use the earlier session/adaptation-zone midnight. Duration
reduction alone changes no calendar occupancy. Unknown permissions/bases/units
fail closed. Initial plan generation and actual amendment generation/approval are
not implemented by these tasks.

Calendar reads distinguish selected authored sessions, confirmed commitments and
imported recorded activities. Independent local GETs neither synchronize sources
nor establish activity matches, skipped sessions or constraint validation.
Draft overlays are non-authoritative. Dirty/pending/uncertain plan states guard
click and keyboard navigation, while logout clears private state. Acknowledged
save followed by failed refresh cannot become a duplicate create retry; read-only
reconciliation is required. Equivalent explicit UTC offsets are accepted without
loosening exact authored-field matching.

The [source capability matrix](source-capability-matrix.md) audits implemented
fields against public documentation. External plan/calendar/detail reads remain
not configured, unsupported or unverified as appropriate; no account was probed
and no adapter or credential scope was added. Local authored plans/confirmed
commitments remain the fallback. Significant external integration is separate.

Actual checks: TypeScript/Vite build and **255 frontend tests / 18 files** passed.
The full backend run executed **517 tests**, with one queue-budget test returning
`factual_only` instead of `available`; all plan/schema tests passed. The unchanged
20-test `ReviewQueueServiceTest` suite then passed alone. This records the failure
and rerun rather than calling the full run green. **Nine synthetic backup tests**,
strict OpenSpec and whitespace checks passed. No live backup, reset, installed
helper update, inference, commit, publication or deployment occurred.

Tasks 4.x are in progress and remain unchecked until their assembly, immutable
bindings, deletion integration, API/UI and tests are complete. New-profile gates
remain closed; synthetic passing tests do not qualify any model or runtime.

### Scoped snapshot assembly — implementation in progress

Task 4 uses a separate schema-2 envelope on the **same** preparation route:
`{schemaVersion:2, selection:<schema-1 factual request>, purpose, question,
additionalText:{content,authorAttribution}|null, baselines:[]}`. Purpose is one of `morning`,
`evening`, `activity_reflection`, `period_reflection`, `question`, `adaptation`;
question purpose requires nonblank question text. The entire request remains
32 KiB UTF-8 and authenticated/CSRF protected. Ordinary schema-1 factual reports
remain inert and do not persist a snapshot. The explicit schema-2 action does
persist a local snapshot, but cannot execute inference or change a plan.

The response contains the ordinary prepared `report` and a separate `snapshot`.
`GET /api/reviews/{snapshotId}` inspects it; `/records?offset=0&limit=25` pages
original membership, at most 50 rows per request. Both are authenticated,
no-store, read-only local inspections. A corrected source record is labeled
changed and its replacement is **not** presented as the original snapshot value.
Missing records remain unavailable. No original full-record history is invented.
Receipt-revision changes remain changed even if payload corrections return to the
original bytes (A→B→A); the captured revision is not replaced by the latest one.

The packet positively selects normalized session identity/time/sport/metric
fields and existing code-owned aggregate values, without charts, raw-record
objects or display-dependent report digests. Moving/elapsed seconds, metres,
calories, average HR and attributed Intervals load replace those available
screenshot facts; laps, interval power, HR streams, verified preceding sleep and
training readiness remain unsupported/unverified. Average HR does not establish
interval intensity and sleep/HRV never reconstruct readiness. Record-origin lists
do not establish metric origin. Source observation dates, session timestamps and
latest changed import receipts are separately retained; unchanged reimports do
not manufacture new observations or receipt revisions.

Activity review/compare uses exact subjects, with contextual wellness distinct
from compared metrics. Period review packets contain selected-period facts;
comparison packets retain the explicitly resolved relationship and comparison
aggregates. Display-only wellness values and wellness read-status metadata are
outside the packet, while their separately classified source records remain
inspectable. Optional detailed wellness may be omitted as a complete category
with a disclosed count; aggregate values and mandatory restrictions are never
silently truncated. Explicit selected subjects/required material that cannot fit
32 KiB block packet preparation. Later profile instructions must independently
fit the normal exact-prompt ceiling; this is not an instruction-budget increase.

Context uses current canonical lifecycle revisions, original observation dates
and exact source links. Future horizons retain the explicit plan-policy timezone;
they do not reuse the reporting zone or rebucket observed source dates. Commitment
projection has one cumulative 50,000-work bound across ranges. Contextual planned
sessions preserve correlated date/sport targets and half-open instant overlaps.
Nothing here matches a completed activity to a plan or establishes a skipped
session. Rule configuration and qualified execution remain unavailable.

Capture uses one HistoryStore-owned consistent read; calculation, serialization
and record hashes happen outside the publication lock. A transient connection
write counter fences intervening changes before atomic snapshot/dependency/
membership insertion. Unrelated writes may produce a safe preparation conflict;
they are not a permanent blanket staleness rule. Inspection recomputes the
relevant record-membership/revision and packet binding, then rechecks snapshot
existence and its write fence under the owner monitor before returning private
content. Corrections can stale aggregates even when their totals are unchanged.
Deletion traverses the new context/intake/commitment dependency edges and purges
request text, packet, manifest and membership atomically, without deleting other
independent inputs merely sharing a snapshot. A stale pre-deletion capture cannot
restore deleted text. No cross-process writer API is claimed.

The shared Trends/Reviews form now has an explicit optional snapshot action,
question/purpose/attributed additional text and included/display-only/excluded/
missing/needs-confirmation inspection. There is no new prototype tab, model
selector, implicit memory creation or transfer consent. Snapshot creation with an
uncertain result is not automatically retried; a confirmed 404 inspection clears
cached packet/record views. New-profile qualification and personal runtime
admission remain closed. Task 4 and the task-5.1/5.2 contract/executor seam passed
the **565 backend tests / 77 suites** checkpoint, alongside the separately passed
**262 frontend tests / 19 files**, frontend build, nine synthetic backup tests,
strict OpenSpec and whitespace checks. The subsequent task-5.3 persistence/UI work
is still in progress and is not covered by that checkpoint.

Explicit baseline requests use optional `baselines` (default empty, at most 20
distinct selections) in the schema-2 envelope. Each supplies `field` (`sleepSecs`,
`hrv`, `hrvSDNN`), `source`, nullable `upstreamOrigin`, and `configuration` with
`evaluationDate`, `trailingDays` (1–366), `minimumSamples`, finite
`minimumCoverage` (0–1), and `maximumAgeDays` (0–366). Null origin means the
measurement's origin is unknown, **not any origin**. Assessment inclusion is
required; baseline requests cannot turn display-only wellness into provider
evidence. No verification/method override is accepted from a client.

Baseline windows exclude the evaluation date and remain distinct from selected
session/period evidence. Their reads share the combined 50,000 distinct-record
ceiling and read no extra activities; baseline candidate work is separately
fail-closed at 50,000 across selections. One stored record can have selected,
display-only or baseline roles without duplicate membership. Bindings include
new candidates on previously missing dates, ambiguous observations and receipt
revisions. Explicitly requested baseline details are not removed by optional
wellness omission; their configuration/status remains inspectable even if packet
overflow blocks preparation.

Inspection manifests have a separate **2 MiB UTF-8** local storage ceiling and
fail closed before persistence if exceeded. Baseline day summaries preserve
configuration/status and candidate counts but do not copy full samples or ID
arrays; captured candidates and receive revisions are available through record
pages. Display-only ID previews are capped at 200, with the full record count
disclosed and all captured identities/roles pageable. Included IDs union actual
bounded-packet baseline samples without duplicates. A blocked snapshot has no
admitted packet: retained candidate summaries/membership are for inspection only.

Daily selection uses at most one compatible sample, identical full samples are
deduplicated and distinct duplicate candidates remain ambiguous. Median arithmetic
is separate from legacy per-record means, with configured coverage/freshness,
actual dates and receive provenance. The current adapter lacks verified required
method/basis: requested baselines therefore report **not computed: measurement
semantics are unverified**, never a fabricated median or threshold. Synthetic
verified-identity unit tests exercise arithmetic only; they do not verify an
adapter. Source-date windows use records available at preparation, not a replay
of what was known on the historical evaluation date. No baseline request and rule
policy not configured are separate visible states. Typed rule enrollment and
threshold controls remain tasks 7.3/7.5, not implicitly completed here.

Verification history retained while task 4 is open: the first full baseline-expanded
run executed **543 backend tests** with one failure in the unchanged connected
executor's total-timeout test (`calls` was zero). Its 45 ms gate left only 5 ms
before a 50 ms total timer, so suite load could expire the bound before a send.
The regression now advances the existing injected monotonic clock to account for
pre-send time and suspends the provider until cancellation; it retains exact
`total_timeout`/one-call assertions and does not alter runtime limits or retry rules.
The following focused run passed **119 tests / 15 suites**, including scoped,
deletion, schema and connected-executor regressions. That command then stopped on
a frontend TypeScript unknown-value narrowing error in the new manifest parser;
the parser fix and subsequent full integration run must pass before completion is
claimed. Task-5 contract scaffolds are also unverified until that run completes.

## New-profile contracts and execution seam (task 5, in progress)

The additive contract IDs are `scoped-descriptive-v1`,
`context-intake-suggestion-v1`, and `plan-amendment-selection-v1`. None inherits
legacy qualification. Their pure packet/prompt/draft helpers have no provider,
confirmation or plan-application access. V1 packet/prompt/validator files and
frozen evaluation evidence remain untouched.

Descriptive instructions branch on resolved mode/intent, with selected sessions
and contextual wellness kept distinct. Comparison and baseline references have
explicit collection/index bindings so a model need not calculate a citation hash.
Context observation dates are not rewritten to fit session dates. Responses use
typed exact references, strict duplicate/unknown-key and primitive-type checks,
bounded text and the 12 KiB UTF-8 response ceiling. Mechanical language guards
cover every generated description/question/explanation, but are not semantic
qualification or a medical-safety proof.

Intake suggestions retain the exact saved note identity/revision, attribution,
observation date and recorded instant. Separate suggestion DTOs contain exact
UTF-16 source spans, editable proposed fields and unresolved questions—not
confirmation requests or active-context identifiers. Applicability distinguishes
specified/open/unknown bounds. Without explicit reference-date/timezone context,
relative dates remain unresolved; supported explicit resolution is limited to
today, tomorrow and strictly next named weekday. A bare weekday stays ambiguous.
Recurrence suggestions distinguish one-off/daily/weekly, interval and bounded/open/
unknown end; missing details cannot silently become an infinite weekly commitment.
User confirmation must still resolve fields and use the existing revision-checked
atomic confirmation API. No suggestion is an authoritative restriction.

Amendment drafts can select only supplied code-owned candidate IDs, request no
change or clarification, with cited non-executable explanations. An empty set
does not create an inference prompt. This schema does **not** enumerate candidates,
validate an entire proposal against every policy, persist proposals or approve a
diff; those remain task 8. No initial plan generation or external write-back exists.

One `ConnectedReviewInvocation` seam supplies assembly and whole-response validation
to the existing connected executor/session. Providers, cancellation, attempts,
timeouts, cost limits and lifecycle stay in that session. Legacy invocation uses
the unchanged V1 prompt and validation. New invocations advertise exact first and
fixed correction prompts; their consent digest binds both. The correction retains
the same user packet and never includes rejected content or validator diagnostics.
Infrastructure failure is terminal rather than a corrective retry. Both prompt
variants must fit 32 KiB; at most one correction follows explicit validator rejection.
Default ordinary policy remains 300 seconds/attempt, 600 total, two calls maximum,
12 KiB response, USD 0.25/attempt and 0.50 total hosted ceilings.

Send boundaries recheck exact generation/evidence/context/provider/model/profile
and admission. Hosted publication is serialized with consent revocation without
holding a consent lock during inference or expensive packet recomputation. A
prepared final publication closure leaves only its owner-fenced transaction within
the consent lock. Write-fence conflicts are terminal invalidations, not provider
failures or opportunities for a correction.

Authenticated no-store `GET /api/review-profiles` exposes all three new profiles;
optional `?profile=<id>` inspects one, including explicit unsupported status.
Inspection is pure: no writes, selection reconciliation, runtime probes, provider
construction or inference. Every new profile is unqualified/runtime-closed, has no
saved provider/model selection and reports execution disallowed. Model selection,
independent qualification, runtime acceptance, exact hosted consent, context
confirmation and amendment approval remain separate decisions.

Task-5 verification history: a full **556-test** backend run failed one new
suggestion coercion test. Kotlin JSON decoding accepted a quoted integer despite
`coerceInputValues=false`; new profiles now validate JSON primitive types against
their serializers before decoding, leaving legacy decoding unchanged. A subsequent
run stopped at test compilation on a null-list inference error in a new executor
fixture; its explicit nullable-string type is fixed. Neither failure is treated
as a passing checkpoint. Frontend build, **262 tests / 19 files**, nine synthetic
backup tests, strict OpenSpec and whitespace checks subsequently passed separately.

The next full run executed **565 backend tests** and failed one new descriptive
guardrail assertion: the readiness-number pattern matched a single digit followed
by a word boundary, so a two-digit invented score escaped it. The pattern now
consumes the complete numeric value; the next full **565-test / 77-suite** run
passed, as did nine synthetic backup tests and whitespace checks. Independent
reviews also found and corrected unsupported-date exception classification,
post-gate monotonic deadline enforcement, consent-revocation lifecycle reporting,
relative-date provenance, recurrence-detail loss and explicit citation mapping.
These synthetic checks do not qualify any model or prove live stop behavior.

### Durable preview and optional suggestion editing (5.3)

New-profile execution identities live in separate immutable
`scoped_execution_bindings` and mutable `scoped_execution_state` tables, never in
V1 snapshot storage. A binding contains one source snapshot **or** one exact intake
revision, provider/model selection, generation, original/correction prompts and
their consent digest, dependency digest and optional candidate digest. Prepared
bindings validate packet contents against the captured source; matching hashes
alone do not permit source substitution. Serialized storage has a separate
192 KiB bound; neither prompt variant may exceed 32 KiB UTF-8. Serialization/hash
work happens outside the HistoryStore owner lock, followed by an existence/write
fence in its transaction. The owner assumes one live application writer per DB;
connection-local `total_changes()` is not an external-process mutation fence.

Explicit authenticated/CSRF-protected
`POST /api/review-executions/prepare` accepts `{profile,source,providerSelection,
dateResolution:null}` and saves only an inert preview. Source is either
`{snapshotId}` or `{intakeId,intakeRevision}`; no client-supplied amendment operations
are accepted. `GET /api/review-executions/{id}` returns exact prompts, currentness
and closed admission; authenticated/CSRF-protected `DELETE` discards the derived
copy without deleting its note/factual source. There is no new run or transfer
consent endpoint. Amendment preparation returns a factual no-candidate refusal
until task 8 supplies code-owned candidates; it never calls a model.

Every source check uses its original cutoff and full current binding, including
newly applicable context and selected-plan/configuration changes. Inspection is
read-only; stale outputs are suppressed. Claim/publication independently recheck
currentness and generation under an owner write fence. A restarted RUNNING row is
INTERRUPTED and cannot replay. Dependency-deletion previews list execution IDs and
fingerprint their prompt/output/state; later creation/publication invalidates the
preview. Atomic deletion removes reached prompts/results as downstream leaves,
without traversing their other source inputs into unrelated notes or restrictions.
Original unlinked-note privacy erasure is not added by execution discard; the
existing confirmed-context dependency deletion and backup-retention limits remain.

`GET /api/context-intakes/{id}/suggestions?expectedRevision=<n>` reads only an
already validated, current published suggestion, or returns explicit unavailable
with no draft. It neither extracts on GET nor substitutes an older generation.
A changed source revision requires reload (409). The shared intake UI offers
explicit read-only inspection and **Edit suggestion**, then uses the existing
editable unconfirmed form. Every unknown field needs explicit resolution before
staging, including intentional open applicability, one-off recurrence or an
open-ended recurrence choice; missing dates/timezones remain blank. Staging still
does not persist or activate anything. Only the existing explicit per-item/batch
confirmation sends the current intake revision and a fresh idempotency ID. Source
attribution comes from the saved note, not a model-controlled field. Original notes
and manual/factual fallback remain usable when suggestions are unavailable.

Task-5.3 failure history: the first **574-test** run failed an API assertion because
an unsupported `/run` path fell through to the application's generic 501 response
instead of explicit 404. The new namespace now refuses unknown sub-operations
without assembling or invoking providers. Subsequent source-substitution and
publication-race fixes, the suggestion endpoint and editor integration require a
new full verification checkpoint; the earlier 565-test pass does not cover them.

Further verification history (5.3 still open): focused backend checks passed, then
the new suggestion UI initially failed TypeScript narrowing/mock-tuple checks.
Those fixes compiled. The next frontend run found two invalid new integration-test
fixtures/selectors; corrected fixtures preserve strict validation rather than
relaxing it. An overlapping run then exposed three enum-array coercion cases and
one existing navigation test's 5-second timeout under worker contention. Enum
checks now require actual strings. With no frontend edits during the run,
`npm --prefix frontend run build` and
`npm --prefix frontend test -- --run --maxWorkers=2` passed **276 tests / 20 files**
without changing test timeouts. A **577-test** full backend run failed the new
late-consent-revocation assertion (`consent_required` versus `consent_revoked`);
the final gate now distinguishes already-claimed RUNNING work from missing initial
consent. Both refusals already prevented publication; the lifecycle reason is now
consistent and still requires verification.

## Read-only scheduler/queue projection (6.3)

Schedule inspection now uses the same bounded rule projection through an owner
read transaction without saving `state_json` or mutating the in-process arming
flag. Queue inspection projects status without reconciling, cancelling, submitting
or updating persisted state. Explicit configuration/selection mutations retain
their invalidation hooks; `pump()` reconciles immediately before claiming and
publication independently checks current selection/configuration. Explicit queue
reconciliation cancels only observed obsolete identities, preserving unrelated
healthy pending/running work. These changes do not enroll a daily slot, open
qualification/runtime gates or constitute authoritative physical-stop acceptance.

Verification history: the next **579-test** backend run passed the new profile
contracts/storage/API checks but failed two queue assertions. One new lifecycle
test reused its pre-reconciliation immutable snapshot after reconciliation; it now
reads a fresh status before asserting cancellation. The other repeated the retained
backpressure test's `available` versus `factual_only` failure. Its success assertion
remains strict and now includes response-free reason/attempt metadata for diagnosis;
no runtime limit or assertion has been relaxed. A focused rerun is pending.

Initial 6.1 scaffolding extracts the existing daily/weekly wall-clock occurrence
calculation into one pure helper and defines bounded, explicit morning/evening
purpose-slot routing with stable occurrence IDs and high-water marks. These
models are **not yet enrollment or queue wiring**: daily preparation, separately
labelled latest-available wellness, atomic queue handoff/retention and dashboard
controls remain unfinished. No fixed times, model selection or inference is
enabled by these pure definitions.

Verified follow-up checkpoint: **587 backend tests / 80 suites**, frontend build,
**276 frontend tests / 20 files** with two workers, nine synthetic backup tests,
strict OpenSpec and whitespace checks passed. The focused queue/scheduler/
execution/storage/API rerun also passed. The strict backpressure assertion and all
runtime ceilings remain unchanged; its earlier intermittent failure remains
recorded above, not reclassified as success. Tasks 5.3 and 6.3 are complete; 6.1
slot enrollment/latest-wellness integration and 6.2 atomic scoped queue handoff
remain in progress. No model is qualified or admitted, no inference was performed,
and no database reset, helper installation, transfer or deployment occurred.

## Daily purpose enrollment and inert personal queue (6.1/6.2)

The shared schedule configuration adds at most two `purposeSlots`, each with a
stable UUID, morning/evening purpose, explicit `HH:mm`, enabled flag and new
descriptive profile. There are no default times. Reviews offers explicit
configuration, pause and **Save and explicitly re-arm daily slots**. Factual
enrollment does not require enabling a legacy model; enabled legacy presets still
require their model selection. Restart restores settings, not arming authority.
No missed-day backlog or already consumed local date replays. Stable slot/date
high-water marks survive configuration edits and pause/re-arm.

Slots use the shared DST helper (gap forward, earlier overlap once) and day scope
resolver. Routing and high-water advancement commit atomically with personal queue
admission; capacity rejection rolls both back. One pending collection, one running
claim, 256-job capacity, debounce/cooldown and maximum-deferral ordering remain
shared. Manual eligibility bypasses debounce, not the single-worker claim or an
earlier deadline. The nullable legacy/scoped union rejects both/neither arms; no
dummy legacy request, V1 packet conversion or second worker exists. Compatible
pending scoped intents coalesce exact selection/routing-cutoff identities and
reason revisions without resetting first enqueue/deadline; running work never merges.

The scoped adapter only prepares local factual snapshots, with no provider or
execution path, even with the legacy guard closed. Durable claim precedes owner
capture; assembly/serialization happens outside its monitor; insertion, content-free
outcome link and queue completion are one fenced transaction. Lost ownership,
configuration/source/domain changes or intervening writes prevent insertion. One
bounded recapture takes a fresh clock instant inside the owner boundary, including
across midnight; this is not an inference retry. The queued scope cutoff is routing
metadata; the snapshot cutoff is the actual preparation read; completion/cooldown
is separate. No scheduled-time knowledge reconstruction is claimed.

`scoped_queue_metadata` holds durable source and plan/reason revisions independent
of bookkeeping and SQLite's transient write counter. Changed records and captured
read-status changes advance source currentness. Unchanged imports create no new
import-change receipt or adaptation work; new read timestamps/status can still
stale an earlier factual packet. Context/intake lifecycle, commitments, plans and
selection mutations advance domain revisions. These conservative counters are not
personal-signal thresholds or adaptation triggers; relevant reason routing is task 7.

`scoped_queue_snapshot_ownership` identifies only automatic copies. Every persisted
queue transition prunes evicted, unreferenced owned snapshots transactionally,
including legacy completion, configuration, cancellation and restart—not GET.
Manual snapshots and execution sources survive. The fresh canonical manifest now
has **29 tables, 12 explicit indexes and five triggers**, unchanged markers and a
required metadata singleton. Incompatible schemas are refused without repair/reset.
The repository backup validator follows the manifest/new scheduler fields; no
installed helper has been updated.

Morning/evening assessment includes separate `latestAvailableWellness` context,
searched over the current and preceding 365 source dates. Latest values are selected
by field/source/exact origin/method/basis, preserving same-date ambiguity, source
dates, receive provenance and numeric age. No stale-age threshold is assumed. It
does not broaden activity subjects or period totals, claim prior-night association,
merge HRV methods or compute readiness. Missing fields/readiness remain explicit.
Manual and queued preparation share packets/membership and the inspection role
`latest_available_context_not_period_subject`. Budgets still fail closed. The
factual-prepared/blocked result leaves the plan unchanged, without claiming that no
adaptation is needed. Reconciliation remains task 7; absence never means skipped.

Verification history: initial compilation found unsafe legacy test accesses after
nullable queue payloads. A focused 21-test run reported six failures: schema seed
fixtures being updated, three wellness fixtures requesting unsupported rolling-one-day
and a capacity fixture accidentally coalescing an existing key. Fixtures were fixed
without relaxing validation. The next focused backend run passed; frontend compile
then found test-fixture types still assuming legacy jobs/no slot fields; corrected.
Independent review found the recapture cutoff, eviction pruning and status-revision
gaps above; fixes/regressions are in verification. Prior checkpoints do not verify
these changes. Tasks 6.1/6.2 remain unchecked.

Resume verification — 2026-10-16: the first focused frontend run had **16 failed,
86 passed**. `strings()` passed `text` directly to `Array.every`, accidentally
using the array index as its new maximum-length argument. The explicit unary
callback fixes that parser defect without relaxing validation. The rerun passed
**102/102 tests in two files**. The complete frontend suite subsequently passed
**281 tests / 20 files**, and the TypeScript/Vite build passed. All **nine synthetic
backup tests**, strict change validation and `git diff --check` passed. Full backend
verification and additional direct daily-slot/domain-revision regressions remain
in progress; these results do not authorize inference or runtime acceptance.

The resumed full backend baseline passed **604 tests / 84 suites** before the
additional regressions/runtime work below. The first added direct slot test failed
because it incorrectly expected `02:30` in a spring gap at `03:00`; the existing
shared occurrence contract shifts by the gap to `03:30`. The slot test now exercises
that existing rule directly (plus earlier-overlap-once and local midnight), rather
than changing scheduling semantics. Added morning packet and domain-change tests
passed in that run; the corrected slot test still needs its rerun.

## Personal runtime authority (6.4, implementation under verification)

The personal adapter remains absent from application wiring, queue execution and
HTTP run routes. Its default qualification refuses; only injected synthetic
qualification/observer/factory seams exercise the new boundary. No model/profile
is qualified and no real runtime acceptance is implied.

`ReviewRuntimeAuthority`, `PersonalReviewRuntimeStore` and
`PersonalReviewRuntimeAdmission` wrap the existing connected executor, not a second
runner. A lazy factory follows a durable domain reservation and start intent. One
claim spans validation correction, with durable attempt permits. PERSONAL policy
caps are 300 seconds per attempt, 600 total, two attempts, 32/12 KiB prompt/response
and USD 0.25/0.50 per attempt/total; ordinary and Development policy are not personal
authority. Factory/preflight time consumes the claim budget.

Successful publication consumes permission transactionally, but does not release
occupancy. Revocation commits before stop requests outside database locks. Missing,
wrong-identity or unconfirmed stop evidence leaves UNKNOWN occupancy. Stop evidence
must also fence delayed starts of the exact claim: EOF, a completed coroutine or an
empty runtime observation alone cannot prove this. A synchronous cancellation
operation is independent of the provider coroutine; its cleanup can be invoked by
another caller even when construction/generation does not unwind.

Owned publication checks dependencies, write token, execution identity, current
owner epoch, claim permission and deadline in the output transaction. Hosted lock
order is consent ledger → HistoryStore → JDBC; no owner transaction enters consent
or provider callbacks. Restart changes epoch, interrupts running executions and
retains unresolved occupancy; a current authority can reconcile an old claim.
Discard/context deletion revoke ownership without cascading away the ledger.
The single-HistoryStore-writer assumption remains explicit.

The fresh-only canonical schema now has **31 tables / 13 explicit indexes / five
triggers**, including owner singleton and non-cascading runtime claims. Markers stay
unchanged; incompatible databases are refused, never migrated/repaired/reset.
Repository backup validation includes the owner seed; the installed helper is
untouched. Runtime resource identity/provenance, physical termination and delayed
start fencing still require separately approved real acceptance. Synthetic booleans
are interface test inputs, not evidence that a real observer satisfies this contract.

The focused resumed verification passed **46 tests / six backend suites**, including
the corrected direct DST slot tests, morning missing/latest/late sleep with today's
plan/context, and plan/commitment mutation fences that preserve the running job.
Frontend factual-status/cutoff tests passed in the 281-test checkpoint above.
Tasks 6.1/6.2 are now complete; 6.4 remains unchecked pending independent review and
full integration verification. Nine backup tests (including missing/invalid runtime
owner seeds), strict OpenSpec and whitespace checks pass for this checkpoint.

Independent runtime review found three authority gaps despite that focused pass:
a suspended per-send observation could bypass the earlier consent/binding/attempt
checks; reserve/start/send used pre-monitor timestamps; and a scoped owned repository
could still invoke the executor under ordinary/Development policy before publication
was refused. Fixes repeat final send checks after observation, sample owner clocks
inside transitions (including start-observation revalidation), and refuse non-PERSONAL
scoped execution before assembly/claim/send. The shared connected service now accepts
explicitly owned PERSONAL context, allowing reuse of its exact consent lock rather
than another wrapper. Additional regressions are in progress. A follow-up review
found no further execution/publication blocker but identified missing restart claim
discovery. Read-only unresolved enumeration now includes old/expired/deleted-source
claims and supports cleanup outside locks without retained in-memory IDs; it too is
under verification. None of these mocked fixes constitutes real stop proof.

Final task-6.4 verification: the first full integration run had **624 tests, two
failures**, both older tests invoking new profiles under ordinary policy after the
new mandatory PERSONAL refusal. Their synthetic contexts were corrected without
weakening the original closed-gate/binding-conflict assertions. The next full suite
passed **625 tests / 85 suites**. The subsequently added context-purge ownership
regression passed with all **17 personal-runtime tests**. Frontend build and all
**281 tests / 20 files**, nine backup tests, strict spec and whitespace checks passed.
Task 6.4 is complete as fake-observer interfaces/tests only; production remains
unwired, unqualified and runtime-unaccepted.

For task 6.5 the user confirmed expiration of old **unexecuted automatic preview
copies** when their outcomes leave the 24-entry history. This never deletes original
source data, manual previews, executed reviews or unresolved ownership. Exact
previews will follow the existing atomic factual commit in a separately fenced
attachment, with no restart repair or GET-triggered preparation.

## Scheduled exact previews (6.5)

Morning/evening slots now save an optional, explicit provider/model **preview
preference**. Empty preference stays factual-only; nothing inherits the legacy local
model choice. Identifiers neither prove provider configuration nor qualify a model.
Both local and hosted previews remain local preparation with no provider factory,
network send, source sync, fallback or automatic plan change. Hosted status is
“awaiting exact consent — execution closed”; no consent/run/send UI or route is added.

Factual snapshot, ownership and queue completion still commit atomically. After
that commit the same adapter invocation captures persisted evidence, assembles exact
first/correction prompts outside the owner monitor, and atomically attaches a saved
execution identity to the retained outcome. Configuration, pause, source/write-token,
generation and outcome-retention fences protect that second commit. Failure leaves
facts usable and an explicit preview-not-prepared/blocked state, not a rerun or a
failed factual result. A crash between commits is never repaired by GET or restart.

The queue retains routing cutoff, actual evidence-read cutoff and preparation finish
separately. Preview creation is another time. The dashboard inspects exact prompts
only on a user-triggered no-store GET, verifies execution/profile/source/selection
and bounded prompts, and clears private inspection on session changes. The execution
store's `IN_FLIGHT` means an inert prepared binding, **not running inference**.
Stale/missing/discarded previews cannot remain usable awaiting-consent results.

The user-selected retention rule is implemented through automatic preview ownership.
Evicted unexecuted automatic copies expire before unreferenced automatic factual
snapshots are pruned. Direct manual previews and manually queued previews are not
owned automatic copies. Any recorded attempt or unresolved physical ownership
protects a copy; original context/imported records/plans are never retention targets.
Executed/manual copies retain the independent execution-store capacity of 1,000;
overflow fails visibly instead of discarding protected records. Every persisted queue
transition can prune; inspection/polling cannot.

The canonical fresh schema is now **32 tables / 13 explicit indexes / five triggers**.
Read-only backup validation additionally checks explicit daily-slot preferences and
refuses missing/invalid provider fields. No migration, reset or installed-helper update
was performed. The first preview test compile found delegated fixture references to
the stored wrapper and a nonexistent request `asOf`; corrected without production
changes. A focused backend run and frontend build/104 tests passed before additional
retention/manual-copy and negative inspection regressions; full checks remain in
progress. Independent source review found no must-fix under the single-writer
assumption; primary review additionally protected manually queued copies and fixed
the delegated frontend fixture's non-backend execution-state names. Review and earlier
passing subsets are not substitutes for the final checks.

Final task-6.5 verification passed **636 backend tests / 87 suites**, **287 frontend
tests / 20 files**, frontend build, nine synthetic backup tests, strict OpenSpec and
whitespace checks. This includes atomic rollback, configuration/source/generation/
eviction fences, restart-without-repair, read-only stale/discarded status, protected
manual/attempted/unresolved copies, 27→24 automatic-copy retention, and malformed/
stale/oversized/wrong-binding/session-clear frontend inspection cases. Task 6.5 is
complete. No inference, transfer, real-runtime acceptance, live DB operation or
deployment was performed.

## Explicit plan reconciliation (7.1, under verification)

The user chose **within the authored time window**, not exact-start equality, and
**one-to-one recordings/sessions**, not grouped recordings. Automatic matching uses
exact sport and a verified recording start in `[authored start, authored end)`.
Date-only schedules use their own timezone's civil day, preserving 23/25-hour DST
days. Mutual uniqueness is evaluated against the whole selected plan, not only the
visible dates. Competing candidates remain ambiguous; no nearest-time, distance,
duration, prose or model matching is performed. Candidate edges are bounded before
allocation, and overflow refuses rather than truncating complete support.

`GET /api/plans/reconciliation?oldest=…&newest=…&timeZone=…` is authenticated,
no-store and read-only. It projects current corrected records, never past knowledge
or guaranteed upstream completeness. Sessions/verified recording instants use the
query timezone for visibility; unknown recording instants remain source-date
browseable and need confirmation. Complete local support is limited to 50,000
records; the inspection response retains the existing 2 MiB ceiling. All selected
plan sessions participate even outside the viewport. Extras are relative to this
selected plan, not necessarily unintended training.

`POST /api/plans/reconciliation/confirmations` accepts explicit match/skip/clear
assertions under authentication, origin/CSRF and an 8 KiB strict JSON bound. It binds
selected plan/revision/generation, exact read digest/cutoff, prior confirmation head
and an idempotency UUID. Identical replay returns the original attributed receipt,
even after corrections; changing that UUID's request conflicts. Final insertion
checks the owner write token and current selection and samples a fresh clock before
eligibility checks in the same transaction. The plain digest is an optimistic view
binding, not proof of an actual earlier GET or a historical database reconstruction.

Manual links may resolve stable record identities whose time is unknown, clearly
labelled `user_confirmation_timing_unverified`. They never fabricate an instant or
accept a known future recording. Existing explicit ownership must be cleared before
another session can claim that recording. Old plan/record fingerprints stay visible
as needs-confirmation and do not silently release/reassign the activity. Fingerprints
cover the entire normalized record plus latest changed-import revision, not merely
the displayed timing/sport fields. A confirmed skip is never inferred from absence;
it requires a configured grace policy, reached session-end/day-end boundary and no
compatible current candidate. Late contradictory activity changes a saved skip to
needs-confirmation, not a hidden overwrite. Clearing a match does not reject its
candidate evidence.

The shared Events & plans controls offer explicit inspection and per-session
confirmation, not a background reconciliation run. They validate the whole response,
abort/discard stale session responses and require explicit reinspection after
uncertain/conflicting writes. Reconciliation does not edit the authored plan,
select a new base, invoke inference, sync sources or enqueue adaptation work;
material reason routing and broader findings UI remain tasks 7.4/7.5.

The fresh-only canonical manifest adds one append-ordered confirmation table and
lookup index: **33 tables / 14 explicit indexes / five triggers**, unchanged schema
markers. Confirmation storage caps at 10,000 assertions and fails visibly rather
than pruning audit history. No migration, repair, reset or installed backup-helper
update is performed. The restore validator continues to use the same manifest.

Verification in progress: the first focused 12-test run had one failure because a
test incorrectly expected an old explicit link to stay current after its timing
changed; it now asserts reconfirmation and separately tests a new unverified-time
confirmation. A later 29-test run had one failure because the purported calorie
correction used the fixture's unchanged value (123); changed to 124 to exercise a
real full-record revision. Frontend typechecking identified missing `typeof` guards
at unknown-valued date boundaries; added without loosening date validation. These
failures are retained; no task completion or full-suite pass is claimed yet.

## Import-removal policy and typed planned-range checks (7.1/7.2)

Two additional user decisions are explicit. “Remove local imports” now honors the
advertised deletion promise for factual review packets, saved outputs/previews and
complete recording-match confirmation histories, including deselected plans and
older revisions. Original notes/plans and unrelated skip/clear-only lineages remain.
Deleting just match rows is prohibited because that could reveal an older skip as
the current head. Revocation precedes cascades; unresolved runtime ownership and
attempt/stop identities survive, remain discoverable and block the same resource.
The transaction also preserves consumed purpose-slot high-water marks. Neither
deletion nor coroutine completion proves physical stop, forensic erasure or backup
erasure. No real data removal was performed to implement or test this policy.

The user separately chose to preserve original **typed effort reports** as detached,
inactive history. Import removal atomically increments durable lineage detachment
generations; reimport with an identical stable ID/fingerprint and old receipt replay
cannot reactivate a report. Only an explicit new revision can reaffirm its binding.
History and withdrawal remain available without a current imported recording.
Withdrawal retains the original value, observation date, attribution and evidence
binding, rather than silently assigning old testimony to a corrected record.

`PlannedTargetChecks` evaluates only currently resolved matches. It dispatches exact
metric/unit/basis combinations to moving seconds, elapsed seconds, session-total
metres, session-average HR, attributed Intervals load or an exact-scale user report.
`authored_inclusive_range_v1` compares against authored endpoints inclusively, with
no percentage tolerance or basis substitution. Valid actual Intervals loads above
the authored-target ceiling remain comparable; actual measurement validity is not
the planned-target input bound. HR/load never become effort or interval intensity.
Unsupported objective/notes/stream prescriptions remain attributed text, not parsed
targets. A changed whole-record fingerprint or import revision makes prior effort
unevaluable until reaffirmed. Missing, withdrawn, detached, ambiguous or incompatible
reports do not fall back to earlier revisions or prose.

Reconciliation inspection now includes bounded `targetChecks` with exact plan/session
and target index, planned/actual values, fields, bases, units, source/origin, activity
revision/fingerprint, comparison policy and attributed report metadata where relevant.
Internal captured raw records are not serialized into these responses. Relevant
report heads participate in the inspection digest. Provider packets remain unchanged
pending task 7.4's coherent reason/snapshot integration; this work does not declare
new prepared inference inputs current solely from a bookkeeping revision.

`GET /api/plans/effort-reports?activityId=…&scaleId=…` is authenticated, no-store and
read-only, even with detached/missing source evidence. `POST` on the same path uses
strict 8 KiB JSON, CSRF/origin protection and an exact operation UUID. The server
resolves immutable saved scale definitions; clients cannot redefine a scale or
invent authoritative measurements. New reports require explicit dated attribution,
a finite on-grid value, current record/revision/hash, lineage/head/detachment CAS and
a fresh owner clock. Optional reconciliation origins additionally bind the exact
selected plan, generation, inspected digest and matched session/scale; elapsed time
cannot hide a newly observed competing candidate. Withdrawals need no record capture.
Exact request replay does not append, detach/reattach or advance domain revisions.

One unique `(activity,scale)` lineage, append-only reports and exact operation receipts
add three fresh-manifest tables: **36 tables / 14 explicit indexes / five triggers**.
Markers are unchanged; incompatible stores are refused without reset or migration.
The technical report ceiling is 10,000 rows, including a reserved withdrawal row per
nonwithdrawn lineage. Ordinary revisions stop at 999, leaving revision 1,000 for
withdrawal. Capacity exhaustion is visible, never truncation or silent history pruning.

The normal plan controls display metric-specific findings and explicit effort entry,
correction, reaffirmation and withdrawal. There is no initial GET/extraction for
effort history until inspection. Unknown/malformed/conflicting writes never retry
automatically. Their uncertainty survives at the parent, locking plan edits,
matching and navigation while preserving the affected history-inspection control.
Stale token/identity completions cannot restore private data, follow up or acknowledge
a mutation in a later session. Original report observation dates stay distinct from
server recording times.

Verification history retained: a routine subagent unexpectedly attempted a Gradle
check despite the no-command constraint and reported missing compiled class output;
this was not treated as a product failure or accepted verification. The primary's
concurrent check reached test compilation and exposed invented fixture method names
(`getPlan`/`getIntake`) and an incorrect lease property; corrected against actual
repository contracts. The next 78-test run failed two new pure effort fixtures:
one hashed the unmodified record while testing modified HR/load; the other tried to
serialize NaN as canonical evidence. Tests now bind the actual full record and assert
noncanonical evidence refusal. TypeScript subsequently exposed an incorrectly
inferred zero-argument mock; rewritten with explicit fetch arguments. A later backend
compile found a missing `bodyAsText` test import; fixed. Focused backend verification,
35 frontend tests in four files, build and nine backup tests then passed. Additional
full-cascade/late-publication, parent-uncertainty and capacity regressions were added;
their final full-suite results are recorded in the next checkpoint, not inferred
from the preceding focused runs.

### Final reconciliation/effort checkpoint — 2026-10-16

The first full integration run had **689 tests, one failed**: the new legacy-review
deletion fixture omitted the explicitly required hard packet budget. Providing
`hardPacketLimit=100` fixed the fixture without changing frozen V1 validation. A
second 689-test run failed the new cascade test's membership precondition: its
recording was after the morning cutoff. The fixture now uses a verified past
instant; populated membership/dependency assertions remain mandatory. All six
removal tests subsequently passed, followed by the complete backend suite.

| Final check | Actual result |
| --- | --- |
| Java 25, offline Gradle `:backend:test`, two workers/in-process Kotlin compiler | **689 tests / 94 suites passed**, no errors/failures |
| `npm --prefix frontend run build` | TypeScript and Vite production build passed |
| `npm --prefix frontend test -- --run --maxWorkers=2` | **306 tests / 22 files passed**, including final finite-grid overflow guards and subjective harder/easier labels |
| `python3 -m unittest discover -s scripts -p 'test_backups.py'` | **Nine synthetic backup tests passed** |
| Strict OpenSpec validation and `git diff --check` | Passed |
| Local Markdown links | **51 files**, no missing local links |
| Tracked frozen resources/contracts/benchmarks | No changes against HEAD |
| Sensitive/generated candidate-path scan | No private/database/key/build-output paths surfaced; not a claim of forensic inspection |

Tasks **7.1 and 7.2 are complete; 30/45 total**. Provider-packet/reason assembly
remains task 7.4, not implied by reconciliation inspection. Task 7.3 architecture
review is complete, but implementation has not started: whether a verified
reset-and-retriggered episode waits for cooldown expiry or can request review
immediately needs an explicit policy decision. No personal thresholds are needed
for implementation. Continuous unchanged episodes must not generate repeats merely
because cooldown expires. No inference, real data removal, migration, runtime
acceptance, deployment, commit or push occurred.

## Typed personal-signal rules (task 7.3)

Rules have no seeded policies or thresholds. An empty fresh store has no enabled
rules. Creating a disabled rule may omit configuration; enabling requires an exact
field/unit/intermediary/origin/method/basis, explicit reporting timezone, baseline,
direction, magnitude, persistence, reset and cooldown. Configuration selects desired
semantics but **cannot verify an adapter**: there is no writable `verification`
property. The current adapter's unknown method/basis/verification remains unknown,
so its records are unevaluable even after configuring a matching-looking policy.
Sleep, HRV and HRV SDNN remain separate fields; no readiness formula is introduced.

Authenticated, no-store routes (no inference, sync, worker or amendment):

- `GET /api/plans/signal-rules`: current immutable rule heads (initially empty).
- `POST /api/plans/signal-rules`: explicitly save one rule revision, strict 8 KiB
  JSON, CSRF/origin protected; operation UUID, rule UUID and expected revision CAS.
  Exact replay returns the historical revision without a new write; changed replay
  conflicts. Up to 20 identities and 1,000 revisions each; the final revision is
  reserved for disabling rather than leaving an enabled rule stuck at capacity.
- `GET /api/plans/signal-rules/{id}/inspection?oldest=…&newest=…`: 1–366 evaluation
  dates plus at most 366 preceding support dates (732 total), with 50,000-record
  input and the existing inspection byte bound. Overflows refuse, never truncate.
  Shared support-day rows preserve selection statuses/evidence identities without
  repeating candidates for every baseline. Baseline start/end and each support
  day's status expose included, missing and ambiguous dates without model calls.

Baselines reuse the existing `unique_compatible` daily selection and median. Exact
sample duplicates are deduplicated; distinct same-identity samples stay ambiguous.
Each evaluated date is excluded from its own trailing baseline. Coverage, minimum
samples and age of the latest usable baseline sample must pass even for absolute
limits. Units are not converted and origins/methods are not blended. Absolute
limits, absolute deviations and percentage deviations are distinct boundary types;
zero percentage baselines and nonfinite/collapsed arithmetic are unevaluable.
Threshold equality qualifies inclusively. Reset uses the same type/direction and
must be strictly less severe than trigger. Missing, unverified, stale or ambiguous
days break persistence counts, never establish recovery or close an episode.

**Synthetic example only, not health advice or a recommended threshold:** a fake
`hrv/ms/synthetic/fixture/rmssd/overnight` signal may use a three-date median with
two samples, coverage `0.5`, latest usable sample age at most two days, absolute
low trigger `17`, reset `23`, two qualifying trigger days, two reset days and
`259200` cooldown seconds. These deliberately arbitrary fixture values are not
defaults and cannot establish real-source compatibility. A distinct fake sleep
policy uses `sleepSecs/seconds`, never HRV units or an inferred prior-night mapping.

The user confirmed **wait** on verified reset/retrigger during cooldown: a new
episode remains pending and requires a currently qualifying trigger after expiry.
Hysteresis-band or unknown data do not release it. An emitted continuous episode
never repeats on expiry alone. Evaluation does not start cooldown; only successful
reason admission may acknowledge the episode. Historical inspection produces no
backlog. Unknown episode history is not assumed recovered or previously unalerted;
a verified reset provides a known continuation anchor.

`PersonalSignalTransitions` is pure. Its internal owner-fenced projection seam is
not a public endpoint and is not called by inspection. Same-day projections replace
the captured pre-day projection, rather than accumulating extra persistence on
polls. Emission facts cannot be undone by projection replacement. Source/policy
changes conservatively require reevaluation while preserving episode facts; the
connection's capture token additionally fences intervening writes, foreign owners,
restart, clock rollback and local-midnight changes. Acknowledgement is bounded to
the evaluated local date and cannot be backdated before evaluation.

**Not integrated:** durable correction-aware reason admission/acknowledgement,
checkpoint dependencies, queue coalescing and provider-packet assembly belong to
task 7.4. Inspection explicitly returns `admission: "not_integrated"`; no operational
cooldown, queued review or live runtime acceptance is claimed. The conservative
complete-source revision fence is not a substitute for that future dependency
validation. This work adds configuration revisions/receipts and bounded content-free
episode state to the sole fresh manifest: **39 tables / 14 indexes / six triggers**.
Markers remain unchanged; no existing database was migrated or reset.

Verification failures retained: the initial focused task-7.3 run had **43 tests,
two failures** in newly generated expectations: oversize private JSON correctly
returned HTTP 413 rather than expected 400, and the median of `20,40,10` correctly
equaled 20 rather than expected 30. Fixture assertions were corrected, not gates.
Primary review also corrected pre-run test assumptions about UUID validation,
distinct evidence ambiguity and required baseline availability. Final verification
is pending; this section does not mark task 7.3 complete.

Independent review then found three internal integrity gaps: caller-supplied
projection state could bypass derived transitions; a too-short continuation horizon
could omit required persistence history; and connection-local `total_changes()` did
not see writes from a second SQLite owner. The projection seam now derives its own
transition outside owner locks (no supplied state), refuses insufficient trigger/reset
lookback, and transactionally checks current rule/state/source revisions plus both
the owner write token and SQLite `data_version`. Added regressions exercise a real
second synthetic connection and persistence lookback. These fixes still need final
verification and do not authorize a public admission caller.

### Final personal-signal checkpoint — 2026-10-16

After those review fixes, the complete Java 25 offline backend command passed:
**718 tests / 100 suites, zero failures/errors/skips** (XML totals inspected).
Frontend build and **306 tests / 22 files**, nine synthetic backup tests, strict
OpenSpec, whitespace, unchanged tracked frozen/benchmark artifacts and all 51
Markdown link checks also passed. The schema count test validates **39 tables /
14 explicit indexes / six triggers**. Task **7.3 is complete; 31/45 total**. This
verifies configuration, evaluation and transition/storage seams, not operational
reason admission: task 7.4 still owns atomic acknowledgement, correction dependencies,
queue coalescing and provider-packet integration. No runtime gate was opened.

For 7.4, the user chose both explicitly configured per-record and cumulative workload
rules, and workload/planned-range re-admission only after genuine clearing/retriggering (or an
explicit user request). In the follow-up answer the user confirmed that signal
policy edits preserve cooldown and unknown-history safeguards; no edit-based bypass
is authorized. All three task-7.4 policy choices are now settled.

## Task 7.4 in progress — not operational admission

The complete reconciliation core is now independent of inspection display ranges.
Measured-target dispatch is shared with explicitly configured extra-workload rules;
per-record and cumulative modes require their own sport/source/origin, metric/unit/
basis, timezone, 1–366-date window and exact comparator/threshold. HR and perceived
effort are not workload substitutes. No rule or threshold is seeded. The authenticated
`/api/plans/workload-rules` list/save and `/{id}/inspection` routes are inert except
for explicit configuration saves; strict 8 KiB writes use immutable revisions,
expected-revision CAS and exact historical operation replay. Inspection remains
`admission: "not_integrated"` and does not submit work.

The content-free reason ledger separates stable finding identity, evidence/material
revision, open episode and successful admission receipt. Unchanged observations are
no-ops. Changed-but-still-abnormal workload/range values do not open another episode;
unknown, ambiguous, missing and out-of-display evidence are not positive clearing.
Explicit compatible clearing followed by retrigger permits another admission. The
SQL helper owns neither synchronization nor a transaction; atomic owner admission
has **not yet** been connected. Admission receipts have no queue-history foreign key.

Stable work families bind selected plan revision, rolling horizon definition,
adaptation zone and stable plan-policy owner identity. Exact resolved horizon,
policy/reason revisions and relevant evidence belong to the work version, not the
family. Selection generation remains an authorization/ABA fence, never authority
conferred by a matching hash. The pure queue primitive replaces active reason sets,
preserves a pending successor's identity and original maximum-deferral deadline,
and leaves healthy running work unchanged. It has no production admission caller;
the owner explicitly refuses this payload before ordinary descriptive preparation,
so unrelated factual output cannot masquerade as an adaptation result.

Changed-constraint fingerprinting separates operational restriction/lifecycle and
commitment changes from full dependency validity. Free-text/label-only corrections
still change exact dependencies without inventing a new operational effect. Canonical
revision is selected before cutoff filtering; expired unresolved symptoms remain
clarification, not recovery. This projection is pure and is not yet a durable
changed-constraint reason controller.

### Signal correction ancestry prerequisite

Changed normalized wellness records append a bounded mutation record containing the
source/identity hash, old and new observation dates and the union of present signal
fields. Even a provenance-only or unrelated-field correction affects that record's
receive revision, so its present signal dependencies are covered. Identical records
do not append. Date moves cover both sides; insertions cover previously empty dates.
At most 50,000 contiguous mutations are retained; incomplete coverage fails closed.
The autoincrement frontier survives ring deletion. A separate strictly seeded removal
generation increments transactionally on import removal, so identical reimport
cannot validate a pre-removal checkpoint merely through contiguous new sequences.
Removal purges source-bearing mutation rows while preserving that content-free fence.

Pure candidate binding covers every relevant field/intermediary candidate, including
incompatible identities, multiplicity, values, semantics and receive revisions;
the declared interval binds empty dates. Bounded causal watches may bridge unrelated
global source revisions only with exact binding, complete mutation ancestry and the
same removal generation. Invalid continuation exposes no old known state for a
fallback. Full persisted checkpoints/replay are **not yet integrated**. The existing
transition seam still keeps its conservative source-revision guard. Continuation
warm-up must precede the first newly processed date, not merely the current date.

Immutable signal admission high-water preserves the evaluated source-day frontier
in its original timezone and `max(admitted deadline, last admission + current
cooldown)`. New verified reset persistence must start after that frontier before it
can re-establish an unknown anchor. These pure contracts do not acknowledge an
actual admission, establish source verification or accept runtime execution.

At the signal-removal-generation checkpoint the sole fresh manifest had **45 tables / 14 explicit indexes / seven
triggers**; application/schema markers remain unchanged. Fresh seeds and read-only
backup validation require the removal-generation singleton. No migration, reset,
actual restore or installed-helper update was performed.

Verification history retained: the first mutation integration run had **75 tests,
one failed** because a generated fixture expected an 80-character source to pass the
existing 64-character import limit. Source validation was preserved and the fixture
corrected. Focused mutation/checkpoint/schema/removal/signal/workload/reason tests
then passed; nine synthetic backup tests passed. New queue/fingerprint checks and
final full integration verification remain in progress. Task **7.4 stays unchecked**:
atomic reason/queue/signal admission, durable correction-aware replay, result reuse,
and coherent packet/manifest/deletion dependencies are still required.

Independent review of these prerequisites identified three further issues, now
corrected with regressions: stale family submissions must be fenced against both
pending and running captures, including cutoff order; horizon relevance is an
eligibility/dependency fact, not a canonical material change; and cumulative clearing
may be positively proven when every retained prior contributor is explicitly matched.
That last proof binds the prior contributor set—arbitrary newly matched activities
or vanished/out-of-window records are not sufficient. The reason SQL helper also
checks the shared pure transition before accepting a changed head, preventing a
caller from rewinding episode/open-state facts through an otherwise valid CAS.

The full backend run before those last review corrections passed, as did frontend
build/**306 tests in 22 files**, nine backup tests, strict OpenSpec, whitespace,
unchanged frozen/benchmark checks and 51-file local-link/path scans. A fresh full
backend rerun including the corrections is pending; earlier green checks are not
being substituted for this rerun. No production gate, source probe or model call
was opened.

The subsequent **789-test** full rerun failed one queue fixture: its initial update
claimed an 11:00 cutoff and then submitted 10:05–10:11 cutoffs. The new stale-capture
fence correctly refused the reversal. The fixture now uses its actual simulated
10:03:20 capture instant before the later updates; the guard remains unchanged.
Final rerun is pending, and this failure remains part of the verification history.

That rerun passed: **789 backend tests / 114 suites**, zero failures/errors/skips.
Subsequent task-7.4 work adds an internal, inert owner capture of reconciliation,
raw activity/effort support, canonical context with intake links/source heads,
confirmed commitments, workload/signal rule heads, signal states and durable reason
heads/receipts in one SQLite read transaction. Projection is pure; finish checks
the owner, local write count, foreign-connection data version, source/removal fences
and selection generation. This is not an admission endpoint or a provider packet.
Its explicit missing-integration list remains nonempty. The 50,000-record work cap
counts raw wellness rows even when the selected signal field is absent, together
with activity reconciliation support; field filtering cannot replenish that budget.
Focused capture tests and final backend verification for these later edits are
pending. No current-user database, reset, restore or inference is involved.

The first focused capture run passed. The next **39-test** focused run failed the
new provenance regression: captured intake links kept their default `sourceChanged:
false` after the source head advanced, although the lifecycle view and separate source
head binding correctly marked the correction. The shared capture helper now derives
that flag from the same captured source heads. This was an implementation correction,
not a weakened assertion. A full backend rerun is in progress.

Follow-up architecture review also found date-derived lifecycle state/reason flags
in the canonical constraint-material hash. Those now belong only to dependency
validity alongside eligibility; midnight and review-due transitions cannot masquerade
as authored canonical changes. Explicit lifecycle resolution/review-due date edits
remain material, and stale unresolved symptoms remain clarification, never recovery.
The fingerprint projection is explicitly **not** complete inventory/deletion proof.

Durable changed-constraint integration must retain a complete bounded canonical
inventory and mutation sequence, compare old/new operational effects against the
same current horizon (including cancellations/removals), and preserve A→B→A changes
between polls. Pure polling cannot establish that history. One initial-enrollment
policy remains to be confirmed: whether existing relevant constraints initialize
only the baseline or request one initial review. Neither behavior has been silently
selected. All production inference/runtime/application gates remain unchanged.

The user subsequently confirmed **baseline-only initial constraint enrollment**:
existing restrictions/commitments remain binding, but initial enrollment does not
request review. Explicit review is separate; later relevant canonical changes still
request review. This settles the newly exposed policy decision without authorizing
inference, transfer or amendment application.

The capture/provenance and clock-material fixes passed the complete backend suite:
**798 tests / 116 suites**, zero failures/errors/skips. Nine backup tests, strict
OpenSpec, whitespace and unchanged benchmarks passed again. Later temporal-validity
work is still under verification: the staging projection now carries the earliest
exclusive semantic boundary from relevant local midnights, authored session
start/end/grace and verified future recording starts. This is not proposal expiry
or hosted consent and does not add an admission caller. Historical inert inspection
may still return the captured past result; a future durable commit must independently
derive and enforce its owner-clock bound.

Strict deletion remains authoritative: old constraint shapes, applicability edges,
health-content fingerprints and derived caches cannot be secretly retained to keep
automatic tracking convenient. If purging proof breaks event continuity, automatic
admission must be unavailable, without silently acknowledging events, resetting
baseline/admission history, reconstructing deleted content, or refusing deletion.
The next ledger design must preserve opaque sequence/receipt facts only and keep
existing commitment relevance (actual occurrences within the horizon).

The user confirmed the returning-plan policy: preserve the original baseline and
deduplication history, observe changes while deselected, and request a coalesced
review on return only for still-relevant material changes. Deselection/reselection
does not authorize a fresh baseline or repeated reviews of unchanged constraints.

### Opaque canonical observation ancestry (still partial task 7.4)

Owner canonical mutation transactions now capture complete opaque current heads and
provenance links before/after, including non-safety categories and post-cutoff heads.
Context, lifecycle, intake creation/correction/association/confirmation, commitments,
both deletion paths and review-feedback creation use the same owner transaction.
Source association/correction can change an observation even without changing the
confirmed context/commitment revision; commitment dependencies include its context's
separate source head. Exact association/batch replay adds no observation receipt.
Unchanged imports and read-only preparation do not enter this mutation boundary.

The journal stores only kind, opaque UUID, revision, presence/tombstone and monotonic
sequence/deletion generation. It copies **no health values, text, content hashes,
dates or applicability**. Distinct A→B→A canonical writes remain distinct receipts,
but those receipts are not proof of material change, relevance or admission. Complete
canonical operational event projection, enrollment/family cursors and the existing
queue/reason/signal atomic handoff are still missing; task 7.4 stays unchecked.

The journal retains at most 50,000 receipts, with 3,000 references/512 KiB per owner
transition and 100,000 opaque inventory identities. Capture refuses above 8 MiB or
50,000 total references, or incomplete/pruned/deletion-crossing ancestry; it never
silently truncates proof. Privacy deletion purges old receipts and advances its
generation before recording opaque tombstones in that same transaction. At terminal
counter exhaustion, a durable `available: false` state permits privacy deletion,
preserves counters/opaque deduplication facts and refuses every continuity proof.
It cannot silently become available after restart or pretend to have complete
inventory. Existing SQL/parser-failure rollback remains unchanged.

At the opaque-observation checkpoint, the sole fresh manifest had **48 tables / 14 explicit indexes / seven triggers**.
Markers remain `user_version=1` and `application_id=0x47544E52`. The new singleton is
strictly seeded only for a fresh schema; incompatible databases are refused, never
repaired or reset. Synthetic read-only backup validation uses the same manifest and
checks the availability/frontier fields. No installed helper or actual database was
updated.

Retained verification failure: the first observation integration run had **179 tests,
one failure** in an existing schema fixture that omitted the new singleton before
its valid-schema assertion. The fixture is corrected without weakening schema
validation; other missing-seed fixtures now isolate their intended missing singleton.
Independent review also found transitive context-source omission and terminal audit
exhaustion blocking deletion; both have focused regressions. Latest focused/full
checks for these changes remain pending, not replaced by the earlier 798-test pass.

The corrected focused run and final full backend rerun passed: **820 tests / 119
suites**, zero failures/errors/skips (XML totals independently counted). Frontend
build/**306 tests / 22 files**, nine synthetic backup tests, strict OpenSpec,
whitespace, unchanged frozen/benchmark checks, 51-file local links and sensitive/
generated candidate-path scans passed again. These verify the stated partial local
implementation only; no task-7.4 completion, model qualification, runtime acceptance,
transfer, database operation or deployment permission follows from them.

### Task 7.4 resume point — 2026-10-17

This is the task-local continuation index, not a completion claim. The
[live task checklist](../../openspec/changes/scoped-reviews-and-adaptive-planning/tasks.md#7-deterministic-reconciliation-and-configurable-thresholds)
owns progress; [design decision 6](../../openspec/changes/scoped-reviews-and-adaptive-planning/design.md#6-deterministic-findings-and-thresholds-not-model-routing)
owns confirmed enrollment, returning-plan, workload and signal policies. Read the
opaque-observation section immediately above for the current implementation and
latest verification, not the entire historical log.

**Current slice:** [persisted signal replay and atomic owner admission](#persisted-signal-replay-and-atomic-owner-admission-partial-task-74),
following [baseline enrollment and material events](#baseline-enrollment-and-material-constraint-events-partial-task-74).
**Next integration:** unify eligible constraint, workload, reconciliation and signal
reasons through atomic admission, then connect the existing scheduler/worker to
durable factual results without weakening proof availability or deletion redaction.
Durable result reuse and packet/provider dependencies remain required before 7.4
can complete.

**Behavior selected at the prior resume point:** an enrolled plan family can expose currently relevant material
constraint changes, including changes made while deselected, without treating
initial enrollment as a review trigger. Owner mutation, persistence/reopen and
deletion must agree on this behavior. No inference/admission is opened by this
prerequisite; task 7.4 remains incomplete until its whole acceptance contract works.

The architecture recommendation below was **not implemented or independently
accepted at this checkpoint**. The bounded implementation and remaining work are
now recorded under [baseline enrollment and material events](#baseline-enrollment-and-material-constraint-events-partial-task-74).

- Establish baseline-only enrollment/cursors at owner-controlled plan selection,
  including selected-plan correction. Preserve original baseline, chronology and
  admissions on return. Compare transient old/new canonical operational endpoints
  at one owner-clock instant and horizon for every available enrolled family,
  including inactive families. Bound shared family-by-input work and bytes.
- Persist purgeable endpoint/provenance proof and dependency edges per genuine
  material event. Preserve every A→B→A event; do not compact intermediate changes.
  Opaque event identity may bind family, subject kind/ID and mutation sequence;
  never use a health-content hash as the surviving identity. The opaque observation
  journal proves chronology only, not materiality or association absence.
- Bind association presence/absence, linked source revisions and observed
  source/context heads explicitly. Existing `ContextLifecycleStore.view()` and
  `CommitmentStore.revision()` derive provenance against current heads; they cannot
  reconstruct immutable historical endpoints. Correct the fingerprint's early
  return with no future session targets: commitment relevance is any projected
  occurrence inside the horizon, not overlap with a session.
- Scrub event proof/dependencies across the full `contextDeletionGraph()` closure,
  including surviving identities whose old revisions are purged. Preserve opaque
  baseline/admission facts; lost proof makes automation unavailable, never silently
  rebaselined or reconstructed. Preview must bind affected event/proof versions.
  The restrictive admission-to-reason-head FK needs dedicated content-free head
  redaction (`condition="unevaluable"`), including revision exhaustion; ordinary
  reason `save()` cannot be assumed sufficient. Privacy deletion must remain possible.
- Capture/project current eligible events under selection, clock, source/removal
  and mutation-frontier fences. Then integrate atomic reason/queue/signal admission,
  persisted causal replay/high-water, durable result reuse and packet/provider/
  deletion dependencies. Pure prerequisites do not establish any of those callers.

Entry points (locate symbols, then read nearby code): `HistoryStore`,
`AdaptationProjection`, `AdaptationConstraintFingerprint`, `ConstraintMutationStore`,
`ConstraintObservationHeads`, `AdaptationReasonStore`, `ContextDeletionStore` and
`ContextDeletionGraph`. Relevant existing tests include `ConstraintObservationOwnerTest`,
`ConstraintMutationStoreTest`, `AdaptationConstraintFingerprintTest`,
`AdaptationProjectionFenceTest`, `AdaptationReasonStoreTest` and `ContextDeletionTest`.

Acceptance for the next slice must exercise initial no-trigger enrollment,
deselected A→B→A/return, immutable provenance/association absence, horizon/clock
eligibility without invented canonical events, no-session commitment relevance,
full deletion closure and terminal unavailability across reopen. Preserve all
existing owner/write/foreign-version fences and fail-closed work bounds. Persisted
signal replay still needs a complete causal watch, verified reset anchors and
first-new-date warm-up; its 732-date/50,000-record/byte bounds cannot erase admission
high-water when continuity becomes unavailable.

Historical full-check commands (run focused tests during iteration, then the
appropriate integration checks; none of these authorizes live operations):

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 25) ./gradlew --offline --no-daemon --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process :backend:test
npm --prefix frontend run build
npm --prefix frontend test -- --run --maxWorkers=2
python3 -m unittest discover -s scripts -p 'test_backups.py'
/Users/tomasklubal/.local/bin/openspec validate scoped-reviews-and-adaptive-planning --strict
git diff --check
git diff --exit-code HEAD -- benchmarks
```

The context-restructuring checkpoint moved original task wording into the
[historical task record](scoped-reviews-task-checkpoint-2026-10-17.md) without
changing any checkbox state or remaining acceptance criterion. The long local
handoff is preserved in a Git-excluded checkpoint, not copied into this public log.
No application code, frozen fixtures, qualification outcomes or approval gates
were changed by that documentation work.

Restructuring verification passed: strict OpenSpec; all 45 task identities/states
(31 complete) and 14 pending acceptance lines unchanged; all original task wording
and the prior handoff preserved; previous implementation-log content unchanged;
70 local Markdown links/anchors; changed-file whitespace; four command-template
checks; local checkpoint Git exclusions; unchanged application/benchmark diff.
Application tests/builds were not rerun for documentation-only edits. No inference,
qualification, live operation, commit or publication occurred.

### Baseline enrollment and material constraint events (partial task 7.4)

The next prerequisite now has owner-integrated local behavior: selecting a plan
revision with an explicit policy establishes a baseline without creating a reason
or review. Repeated selection and return preserve that family's original opaque
baseline identity and cursor. A selected correction enrolls a distinct revision
family, consistent with `AdaptationFamily`; earlier families remain enrolled.

`ConstraintCanonicalProof` captures complete operational endpoints and provenance
before and after each canonical mutation at one owner-clock instant. Context prose,
intake text, commitment labels and attribution are not copied into endpoints.
Exact dependency hashes, operational values, dates and provenance are sensitive,
purgeable local proof, never surviving event identity or provider DTOs. Association
absence is explicit; observed heads and associations are frozen, not resolved
against later SQL state. Material comparison shares the fingerprint's canonical
fields and excludes prose, labels, revisions and derived lifecycle state.

`ConstraintEventStore` retains separate A→B and B→A endpoint proofs for every
available enrolled family, including deselected families. Dependency-only changes
advance complete coverage without creating material events. Changes outside the
current horizon remain recorded. Read-only `AdaptationProjection.constraintChanges`
projects both frozen endpoints under the same current plan, instant and horizon;
either endpoint can establish relevance. Clock advancement changes eligibility,
not event history. Commitment occurrence relevance no longer depends on having
future session targets.

Shared proof work is bounded at 50,000 operations and 8 MiB across owner captures
and generated family proofs; retained event shells are capped at 50,000, retained
proof/baseline bytes at 8 MiB, and enrollment at 1,000 families. Known proof loss or
proof-capacity exhaustion atomically purges proof and makes existing continuity
terminally unavailable while allowing the canonical correction. Unexpected SQL
failures roll back the original mutation and observer together. The family-count
limit refuses a new enrollment transaction rather than silently truncating history.
Eligibility projection has a separate fail-closed work budget and consumes no event.
An opaque authoritative event count detects missing event rows as well as null
payloads; later owner mutations cannot acknowledge the incomplete history.

Deletion preview binds all adaptation proof versions. Deletion conservatively
purges **all** baseline/event proof payloads and invalidates all existing families,
matching the global opaque journal's deletion-generation discontinuity. This
includes the complete shared-source closure and surviving identities whose old
revisions are purged. Only opaque family/baseline/event identity and chronology
survive. Reselection and reopen never rebaseline unavailable continuity. Deletion
does not persist its old sensitive endpoint as a new event, and proof exhaustion
does not block privacy deletion. This conservative policy can disable unrelated
families too; it never retains deleted applicability to preserve automation.

The sole fresh manifest now contains **50 tables / 15 explicit indexes / seven
triggers**, with unchanged version/application markers. Existing incompatible
databases remain refused; no migration, reset or installed-helper update occurred.

Remaining task 7.4 work: constraint-event reason admission and content-free reason
head redaction, atomic reason/queue/signal admission, persisted signal causal
checkpoints/high-water and replay, durable result reuse, and complete packet/provider/
deletion dependencies. This slice writes no reason heads, admissions or queue jobs.
Task 7.4 remains unchecked and overall progress remains **31/45**. Production
execution, qualification and personal runtime acceptance remain closed.

Retained failure: the first focused run failed compilation
because Kotlin could not infer the event capture's `buildList` element type. The
explicit `CapturedConstraintEvent` type fixes inference without changing behavior.
The next focused attempt hit its 120-second command timeout during compilation;
it did not establish a passing test result. The subsequent command uses a
600-second timeout. Independent review identified uncharged enrollment-manifest
bytes and missing-row continuity detection. Both are corrected, with regressions
for multiple families, persisted payload privacy, shared deletion, missing rows,
proof bytes, retained shells and the deliberately refusing family-count boundary.

Verification complete for this bounded slice:

- Focused backend: **69 tests / seven suites** passed before the final regression
  additions. The command used the historical backend invocation above with
  `--tests 'com.gtrainer.ConstraintMaterialOwnerTest'`,
  `--tests 'com.gtrainer.ConstraintObservationOwnerTest'`,
  `--tests 'com.gtrainer.AdaptationConstraintFingerprintTest'`,
  `--tests 'com.gtrainer.AdaptationProjection*Test'`,
  `--tests 'com.gtrainer.HistorySchemaTest'` and
  `--tests 'com.gtrainer.ContextDeletionTest'`.
- Final full backend: **839 tests / 120 suites**, zero failures/errors/skips,
  independently counted from XML. This includes **18 material-owner regressions**
  and the no-session commitment fingerprint regression. An unchanged-input rerun
  was fully up-to-date, confirming the checked source/resource/test inputs.
- Frontend build and **306 tests / 22 files**, nine synthetic backup tests using
  the final manifest, strict OpenSpec, 70 task/log local links/anchors, whitespace
  and unchanged frozen benchmark diff passed. Existing compiler warnings remain.

No inference, transfer, runtime acceptance, non-synthetic database operation, installed
helper update, commit, publication or deployment occurred. These checks do not
complete task 7.4 or authorize any closed operation.

### Persisted signal replay and atomic owner admission (partial task 7.4)

The internal `captureSignalAdaptation` / `PreparedSignalAdaptation.prepare` /
`commitSignalAdaptation` path now exercises a durable owner handoff: a verified
current signal candidate writes its reason head, actual coalesced queue job,
admission receipt, acknowledged checkpoint and irreversible high-water in one SQL
transaction. Queue capacity, receipt failure or a stale owner capture must roll
back the entire handoff. Preparation alone never acknowledges a signal.

The default evidence adapter remains unverified. A constructor-injected internal
adapter supplies trusted synthetic evidence in owner tests; neither configuration
selectors nor public requests can grant verification authority. No public route,
scheduler tick or provider executor calls this internal admission path yet.

Replay validates the exact previous causal watch, complete mutation ancestry,
removal generation, rule revision and candidate-set binding before continuation.
The watch includes baseline/persistence support before the first newly processed
date and never silently slides. Missing or invalid proof discards known episode
state, not the durable admission frontier or cooldown. Only a verified reset whose
entire persistence interval begins after the admitted source-day frontier can
reanchor unknown history. An ongoing emitted episode cannot use its original
pre-admission reset to manufacture an unalerted copy. Watch growth beyond 732 dates,
missing causal support and candidate byte/record limits refuse automation.

The fresh-schema manifest adds nullable replay and high-water JSON columns to the
existing signal-state table. This is not an installed-database migration. The
legacy internal projection writer clears causal proof rather than legitimizing its
unvalidated state, and preserves any irreversible high-water. Context deletion
and import removal purge replay proof and reason content hashes while retaining
opaque admission chronology and deduplication. Reason redaction also works at
revision exhaustion and preserves the restrictive admission foreign key. Deletion
previews bind both reason heads and persisted signal state.

This slice intentionally owns only personal-signal reasons. A guard refuses to
replace queue support belonging to another reason kind. The unified task-7.4
caller still needs constraint/workload/reconciliation admission, scheduler and
worker integration, durable result reuse and complete provider-packet dependencies.
Existing adaptation execution refusal and production runtime gates remain closed.

Verification passed on final inputs: backend **854 tests / 121 suites**, zero
failures/errors/skips, independently counted from XML. This includes **14 owner
admission regressions**, revision-exhaustion reason redaction and candidate byte
overflow. Owner tests cover verified versus unverified authority, reopen,
queue-capacity and receipt-failure rollback, unrelated imports, corrected-away
reset, running-job preservation, cooldown lowering, disable/re-enable, removal/
reimport, foreign/local/selection-ABA/midnight races, date moves, conflicting
candidates, A→B→A, multi-day gaps, 732/733-date watches and context deletion.
Frontend build and **306 tests / 22 files**, nine backup tests, strict OpenSpec,
71 local links/anchors, whitespace and unchanged benchmark diff also passed.

Retained failure: initial main compilation found an unsafe nullable
`running.scopedIntent` access; the safe-call correction passed the checks above.
The routine child's initial status assertion used the wrong label; primary review
replaced its overly broad nonblank assertion with exact `admission_candidate` and
expanded the two returned tests to the owner suite above. The first full backend
run passed, but final-input tests changed while it ran; the final rerun above is
the authoritative checkpoint. No child or command remains pending for this slice.

No inference, transfer, runtime acceptance, non-synthetic database operation,
installed-helper update, commit, publication or deployment occurred. Task 7.4
remains unchecked until the remaining integration works.

### Unified adaptation admission and durable factual delivery (task 7.4)

This section supersedes the integration limitations in the earlier partial-task
checkpoints. `PreparedSignalAdaptation` retains its historical class name but now
prepares reconciliation, configured extra-workload, immutable constraint-change
and personal-signal reasons from one coherent owner capture. The armed scheduler
tick invokes the same atomic admission transaction. Status and inspection remain
read-only; disabled, paused, unarmed and non-durable schedulers cannot admit work.
Restart still requires explicit re-arming. This routes factual preparation only,
not provider execution or a decision about changing the plan.

Reason support retains family, rule, activity and cumulative-contributor ancestry.
Missing, disabled, aged-out or partially available contributors cannot manufacture
clearing. Admission receipts and irreversible signal high-water remain distinct
from purgeable support. Already abnormal but changed evidence fences old results
without admitting the same episode again. A failed or blocked factual preparation
leaves admission unfulfilled, so the existing worker can retry without a new alert
or cooldown acknowledgement. Publication fulfills only the covered current heads.

Queue payloads bind the complete canonical `AdaptationWorkVersion`, not only its
hash. Identity includes plan revision, configured horizon, rule revisions, reason
dependencies and relevant factual support, but excludes display scope, unrelated
records, selection generation and admission bookkeeping. Selection generation and
owner-write/data-version tokens remain separate authorization and race fences.
Existing family coalescing retains the original maximum-deferral deadline and
preserves a healthy running job while merging at most one successor.

`AdaptationResultStore` indexes immutable scoped factual snapshots by exact work
identity. Index insertion, snapshot publication, fulfillment and worker completion
share the owner transaction. Results survive bounded queue-outcome eviction and
reopen. The existing shared 1,000-snapshot ceiling remains unchanged; new work
fails closed at capacity rather than evicting results or increasing budgets.
Canonical result packets contain typed findings, configured signal baseline facts,
relevant restrictions/commitments and historical constraint endpoints, not an
unrelated display-day report or internal admission/replay authority.

Morning/evening worker preparation and explicit assessment-enabled adaptation
preparation reuse the same current result. Overlapping manual display ranges do
not create separate adaptation identities. Daily snapshots copy the factual section
and bind the exact result reference, flattened source dependencies and record
membership. Adaptation requests with wellness off/display-only cannot transfer
signal facts; they return a blocked snapshot. Generic period reflection is not
implicitly converted into adaptation work.

Provider source recapture validates the referenced historical result against a
separate present-time coherent adaptation capture. Exact work and factual content
must still match. Exclusive deadlines include relevant local midnights, session/
grace boundaries and pending signal cooldown expiry. Preview insertion, inspection,
reservation, claim, attempt authorization and publication preserve those fences.
Owner/data-version checks also reject external-connection changes between capture
and publication. Context deletion follows result references transitively, while
flattened record membership covers imported-evidence removal. Opaque admission
history survives deletion; result content and causal support do not.

Final-input verification passed: backend **877 tests / 123 suites**, zero failures,
errors or skips, independently counted from JUnit XML. This includes seven unified
admission owner tests, fifteen result-delivery owner tests and fifteen signal owner
tests, alongside the existing queue, temporal, deletion and runtime regressions.
The final full run followed the commitment-source provenance and manifest-inclusion
corrections; no Kotlin inputs changed while it ran. Task 7.4 is complete. Task 7.5
and amendment selection/application remain separate incomplete work.

Commands and companion checks:

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 25) ./gradlew --offline --no-daemon --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process :backend:test
(cd frontend && npm run build && npm test)
python3 -m unittest discover -s scripts -p 'test_backups.py'
"$HOME/.local/bin/openspec" validate scoped-reviews-and-adaptive-planning --strict
git diff --check
git diff --quiet -- benchmarks
```

Frontend build and **306 tests / 22 files**, nine backup tests, strict OpenSpec,
whitespace and unchanged tracked benchmark diff passed. Task-local links/anchors
were checked separately; no inference or operational acceptance was performed.

Retained failures during integration: explicit event serialization was required;
the scheduler test initially exceeded the operator budget and then the policy's
maximum; an oversized plan fixture exceeded the existing authored-plan byte cap;
the no-queue status regression exposed inconsistent inspection/tick labels; and
the signal-delivery test inspected earlier than its corrected receipt timestamp.
These were corrected without raising limits or weakening production gates.

No inference, live probing, runtime acceptance, download, installed-database
migration/reset/restore, installed-helper update, commit, push or deployment is
authorized by this integration. Amendment selection/application remains separate
work, and production personal execution remains closed.

## Inspectable findings and typed threshold controls (task 7.5)

The authored-plan view now exposes explicit personal-signal and extra-workload
configuration through the existing authenticated rule APIs. Loading is explicit;
mounting, reading rules and inspecting evaluations do not request a model or
enqueue work. New rules start disabled with blank selectors, magnitudes, windows
and persistence settings. Workload aggregation, metric, unit, basis, sport, source,
window, comparator and threshold are explicit. Signal selection keeps HRV methods
separate and binds source, origin, method, basis, baseline coverage/freshness,
direction, boundary type, persistence, reset and cooldown. Desired selectors do
not grant adapter verification authority or establish medical cutoffs.

Edits retain the exact rule identity and expected revision. A successful write
requires an exact configuration/revision receipt. Conflicts, invalid receipts and
unknown outcomes block writes and rule-type switching until an explicit
authoritative list reload; reload also discards unsaved drafts. No mutation is
automatically retried. Drafts, writes and uncertainty lock parent plan edits,
matching and navigation while recovery remains available. Epoch and abort fences
suppress late responses after identity changes or unmount.

Pure saved-rule inspection and the shared evidence card reuse typed evidence
displays. They show actual values, exact thresholds, selected baseline dates and
counts, source identity, missing/ambiguous/unverified evidence and workload
support. Reconciliation and captured findings share the same planned/actual
target display, including the exact subjective effort scale. Captured findings
separate the planning horizon from the displayed evidence period, retain explicit
reason IDs/revisions and source addresses, and label stale evidence as historical.
Unknown or removed snapshots do not display cached findings after removal is
observed. An empty reason set is not a no-change decision; a trigger is not an
applied amendment, inferred readiness score, medical clearance or inference
permission. Read-only inspection neither queues reasons nor acknowledges cooldown.

`adaptation_facts_v2` adds code-owned reason provenance. Its version participates
in canonical dependency hashing so an older result cannot silently satisfy the
new factual identity. The existing packet cap, durable result index, publication
fences and separate admission/fulfillment invariants remain unchanged. The shared
snapshot parser recognizes canonical result dependencies and adaptation support
roles; assessment-only inclusion and matching result keys are checked before
display. Historical constraint endpoints remain attributed evidence, not an
instruction to remove a restriction. No proposal/application controls are added
by this task.

Implementation entry points: `AdaptationRuleControls.tsx`,
`AdaptationRulesClient.ts`, `AdaptationRuleEvidence.tsx`,
`AdaptationFindingsClient.ts`, `AdaptationFindings.tsx`,
`PlannedTargetFinding.tsx`, `ScopedEvidenceInspection.tsx`, `PlansPanel.tsx`,
`AdaptationFindingReasons.kt`, `AdaptationResult.kt` and
`AdaptationDependencies.kt`.

Integration failures retained: TypeScript narrowing in private-response parsing;
a rendered-text query also matching the display-only JSON packet; test callback
tuple inference missing its optional request argument; and an overstrict support
parser treating one compatible sample as exactly one candidate of any identity.
The latter was corrected against the shared selector: a unique compatible HRV
sample may coexist with incompatible HRV-SDNN evidence. A regression covers that
case. An intermediate frontend run overlapped test edits and was superseded by a
fresh build/full run. The backup test initially ran from the wrong import
directory; rerunning from `scripts/` passed all nine isolated synthetic tests.

Final verification: the frontend build and **325 tests across 25 files** passed.
The full offline Java 25 backend suite passed **878 tests across 123 suites**, with
zero failures, errors or skips, independently counted from JUnit XML. No Kotlin
input changed during that final run. Nine isolated backup tests, strict OpenSpec
validation, whitespace and unchanged tracked benchmark diff also passed. Vite's
511.92 kB minified chunk (147.02 kB gzip) produces a non-failing size warning;
no production limit or safety check was relaxed.

No inference, live probing, runtime acceptance, downloads, installed-database migration/reset/restore,
installed-helper update, commit, push, publication or deployment was performed.
Qualification and exact authenticated amendment approval remain separate gates.

## Boundary-only amendment candidates (task 8.1)

The user confirmed three bounded policies on 2026-10-17, recorded in design
decision 8 and the allowed-operation scenarios: boundary-only enumeration; timed
moves preserving both local-time span and elapsed occupancy; and clarification
instead of guessed constraint execution meanings. The initial policy is not an
optimizer or exhaustive search. It tries authored earliest/latest move starts,
distinct duration-result clamps at the permitted minimum and original lower
endpoint, and separately permitted omission. No percentage, clinical reduction
amount or time grid is inferred. An empty set is not a no-change decision and
does not prove that every interior alternative is forbidden.

`PlanAmendmentPolicy` supplies pure enumeration and a separate arbitrary-operation
check, rather than treating candidate-ID membership as policy validation. Exact
before sessions must belong to the selected base revision. Operation field
allowlists prohibit new sessions, sport/objective/attribution/permission changes,
increased target endpoints and combined unrelated edits. Original and resulting
sessions must be future and wholly within the configured horizon. Date-only
sessions occupy the entire civil day and retain the earlier existing cross-zone
cutoff. Duration changes leave calendar occupancy unchanged. Newly ambiguous
destination offsets, gaps or changed DST occupancy require clarification.

Constraint checks use complete destination intervals and shared lifecycle/scope
retrieval, regardless of model citations. Explicit sport prohibitions and existing
restriction conflicts remain binding. Planned sessions have no verified imported
activity/review scope: potentially relevant scoped safety context is broadened for
conservative relevance selection and then explicitly blocked as unknown, not
silently dropped. Case-only sport-scope mismatches similarly require identity
confirmation rather than silently bypass a potentially applicable prohibition.
Unresolved symptoms, expired/retired unresolved safety context,
review-due entries and changed sources cannot become recovery or training
permission. Undefined `allowed_*`, activity classification and maximum-duration
basis require clarification, including when omission is offered. Omission removes
training occupancy, not unresolved safety/source/conflict evidence.

Confirmed busy commitments use the existing half-open interval projection,
recurrence, cancellation/rescheduling, moved-in exceptions and disclosed DST
resolution. Touching endpoints do not overlap. Existing non-omitted plan sessions
also block resulting occupancy collisions; the initial policy does not solve
simultaneous swaps. Availability meanings are unknown across the projected
horizon and therefore block candidates rather than override busy time. Changed
commitment sources conservatively block the set even if the old definition does
not currently overlap: changed text does not establish unchanged applicability.

Authenticated `GET /api/plans/amendment-candidates/inspection` rejects query
parameters and is factual/read-only. `PlanService` uses a dedicated coherent
`HistoryStore` capture of selection, exact plan, context/lifecycle, shared-source
bindings and confirmed commitments; it does not replay signals, acknowledge
reasons, fetch imports or create snapshots/proposals. A short finish transaction
checks owner, local/external mutation tokens, selection generation, monotonic
clock and exclusive temporal deadline before returning. Inspection leaves plans,
calendars and queues unchanged. Results distinguish available boundary alternatives,
no candidates and needs-clarification, retaining exact before/after values,
stable candidate IDs, policy/dependency/set digests and source-addressed issues.

The set fails closed above 100 candidates, 50,000 shared policy work units, 8 MiB
serialized local input or 32 KiB serialized candidates. No first-100 truncation,
automatic split or partial executable set is returned on global overflow. The
existing 2 MiB inspection-response cap also remains. Candidate-only sizing does
not certify the later combined evidence/candidate/system-prompt budget; task 8.2
must validate exact combined bytes and whole-proposal interactions. Inference,
proposal persistence, approval and application are not provided by task 8.1.

Entry points: `PlanAmendmentPolicy.kt`, `HistoryStore.kt`, `PlanService.kt`,
`PlanRoutes.kt`; regressions: `PlanAmendmentPolicyTest.kt`,
`PlanAmendmentOwnerTest.kt`, `PlanAmendmentApiTest.kt`.
Final full offline Java 25 verification passed **899 backend tests across 126
suites**, with zero failures, errors or skips, independently counted from JUnit
XML. No Kotlin input changed during that final run. The frontend build and **325
tests across 25 files**, nine isolated backup tests, strict OpenSpec validation,
whitespace and unchanged tracked benchmark diff also passed. The documented
non-failing frontend chunk-size warning remains unchanged.
Focused policy/owner/API tests exercised 21 cases. One owner fixture initially
attempted direct correction of an intake-linked context; the existing shared-source
guard correctly refused it. The correction-race fixture now uses an unlinked
context, while a separate intake-correction case verifies linked-source fencing.
No production confirmation guard was relaxed. Primary inspection also expanded
the initial child tests to cover available (not merely missing-policy) candidates,
reopen, all owner mutation families, external writes, clock/deadline fences,
uncited constraints and a CSRF-valid unsupported mutation request. The final full
backend run includes these corrections and the sport-scope ambiguity regression.
No inference, live operations, schema migration, commit, push or deployment is
authorized or implied by this implementation.

## Whole-draft amendment checking (task 8.2)

The user confirmed amendment-only exact explanation choices on 2026-10-17.
`AmendmentSelectionContractV1` supplies deterministic statements with exact
candidate/session identity, before/after dates, timezone/offsets and duration
ranges, sources and uncertainty. Authored content remains attributed input, not
verified coach or clinician guidance. Raw author names, objectives and notes do
not become trusted generated claims. A draft selects exact choices; it cannot
rewrite text, numbers, units, dates, attribution, sources or uncertainty. Every
selected candidate requires its own choice, and choices for unselected candidates
are rejected. No-change and clarification use separate exact generic statements.
This conservative boundary leaves scoped descriptive prose and frozen legacy
contracts unchanged. It is not a clinical-safety or usefulness guarantee; choices
describe operations and uncertainty rather than inferred physiological causation.

`PlanAmendmentPreparation` is a local, inert prepare/check seam. It accepts only an
actual current stored adaptation result, not an arbitrary snapshot whose request
merely says adaptation. A single owner transaction captures both immutable source
evidence and complete current policy inputs. Preparation checks the reconstructed
source binding, exact selected plan/generation, policy and horizon. Every packet
session must match its canonical authored session, but the complete authored plan
need not equal the scope-filtered packet session list. Candidate before-values
must appear in the immutable packet; no silent expansion or candidate truncation
is allowed. Complete local plan inputs remain available for collision validation.

The inspection exposes source snapshot/packet/evidence/adaptation identities,
policy and candidate-set digests, exact first and correction prompts, prompt
binding, deadlines, and explicit plan, context, intake, commitment, adaptation-rule
and reason revision references. Exact serialized UTF-8 prompt limits include
instructions, packet wrappers, escaping and the correction suffix. Each attempt
must fit the existing 32,768-byte limit independently. Candidate-only sizing does
not establish this bound. No budgets are increased or split into extra calls.

Strict draft decoding resolves IDs only from the frozen code-owned set, with one
operation per session and exact code-owned explanations. Independent whole-result
validation rechecks every operation against original complete policy inputs under
one shared work budget, then checks combined occupied intervals against each
other and unchanged sessions. Two individually permissible moves to the same new
destination reject the whole draft. The existing conservative no-swap policy
remains: simultaneous omission cannot rescue an individually forbidden move.
Invalid drafts return no selected subset or cached explanatory prose.

Private in-process handles freeze serialized inspection content. Preparation and
validation finish with owner, local/external mutation, selection, source-existence,
monotonic-clock and exclusive-deadline fences. Even rejected output must pass the
final source fence before returning private inspection content. Handle validity is
capped by source validity, policy/session temporal validity and 24 hours; checked
result expiry also includes affected original/result cutoffs. Checked-result
digests bind the complete preparation, outcome, exact before/after operations,
explanations, issues and expiry. They grant no approval authority.

Authenticated `GET /api/plans/amendment-selection/{snapshotId}/inspection` returns
the exact local packet. CSRF-protected
`POST /api/plans/amendment-selection/validation` accepts the snapshot ID, expected
exact prompt binding and raw draft. Stateless validation reconstructs only the
current packet and rejects a mismatched expected binding rather than rebasing the
submitted draft. Neither route creates execution/proposal state, advances an
execution generation, invokes a provider, acknowledges adaptation reasons or
changes a plan/calendar. Empty candidate sets remain factual no-candidate or
clarification statuses, never inferred no-change. Persistent execution preparation
for the amendment profile remains closed; task 8.3 owns proposal persistence and
task 8.4 owns explicit approval/application.

Entry points: `PlanAmendmentPreparation.kt`, `PlanAmendmentPolicy.kt`,
`AmendmentSelectionContractV1.kt`, the narrow capture/finish methods in
`HistoryStore.kt`, `PlanService.kt` and `PlanRoutes.kt`. No inference, live operations, schema migration,
commit, push, publication or deployment is authorized by this local seam.

Integration failures retained: the child's first check overlapped primary DTO
creation and could not resolve the capture type. The first integrated compile
then found positional use of the version-first evidence request constructor and
an unmatched nested test-expression parenthesis. Fixtures now use explicit
`selection=` and a separate altered-source value. Primary inspection corrected
the child's nonexistent plan-evidence type and made explanation rendering retain
all authored duration bases instead of mislabeling multiple targets as omitted.
These are implementation/test corrections, not relaxed validation or approval
boundaries. Final checks must supersede the intermediate failures.
The subsequent focused run reached 45 tests with one API failure: validation used
an envelope limit above the shared private-HTTP maximum and was correctly refused
before parsing. The route now uses the existing 32 KiB HTTP limit, independently
of the 12 KiB decoded-draft and 32 KiB per-prompt budgets. An oversized-envelope
regression verifies rejection without weakening the shared boundary.
The parallel integration frontend build passed, but its test run had five
5-second timeouts across unrelated UI files (320 tests passed) while backend
compilation and backup verification were also active. This run is retained as a
failure; a fresh lower-concurrency run must verify the unchanged frontend without
raising timeout or assertion thresholds.
The subsequent full frontend run with `npm test -- --maxWorkers=1` passed all
**325 tests across 25 files** after backend completion, without changing timeout,
assertion or production thresholds. A final backend rerun also includes the
existing 2 MiB inspection-byte guard on checked-result serialization. Final full
offline Java 25 verification passed **917 tests across 129 suites**, with zero
failures, errors or skips, independently counted from JUnit XML. No Kotlin input
changed during this final run. Nine isolated backup tests, strict OpenSpec,
whitespace and unchanged tracked benchmark diff also passed. The existing
non-failing frontend chunk-size warning remains unchanged. Task 8.2 is complete;
no proposal persistence, approval or inference admission is implied.

## Durable local proposal lifecycle (task 8.3)

`PlanAmendmentProposals.kt` and the SQL-only `PlanAmendmentProposalStore.kt` add
explicit local generation, private inspection and rejection. Generation accepts
the same exact source/prompt binding and submitted draft as inert validation, but
uses a separate authenticated CSRF-protected write endpoint. It performs no model
call. Existing validation and inspection endpoints remain inert. No approval or
plan-application endpoint is introduced in this task.

The durable projection retains exact selected before/after operations, explanation
choices, citations/uncertainty, validation issues, base plan and selection generation,
source/adaptation/policy/candidate/prompt digests, explicit revision references and
source/removal/constraint frontiers. It does not duplicate evidence packets, prompts,
unselected candidates or raw submitted drafts. Its versioned digest is distinct
from the transient checked-result digest. Initial checked outcomes map to pending,
no-change, needs-confirmation and validation-rejected dispositions; explicit user
rejection records a distinct code-owned reason. Pending is not approval authority.

The private save capability accepts a frozen preparation and draft, performs its
own validation and retains the original capture through owner-controlled atomic
insertion. There is no serializable checked-result save API. The final save fence
checks owner, same/external connection mutation tokens, selection, current source
existence and adaptation capture, clock direction and exclusive expiry before any
write. Projection and state insertion share the owner transaction. A synthetic
state-insert failure must roll back the projection as well.

Expiry retains the checked source/policy/24-hour/affected-before-and-after cutoff
minimum; retry and restart cannot renew it. Exact submitted bytes and expected
prompt/source identities derive the private submission key. Repeating a submission
returns its existing record, including rejection or expiry, before capacity checks.
The repository admits at most 1,000 records with a 256 KiB projection limit, no
truncation, eviction or read-triggered cleanup. Its immutable projection table and
separate disposition table cascade with the source snapshot. The fresh canonical
schema now contains 53 tables, 16 indexes and eight triggers. Installed databases,
migrations and installed backup helpers are untouched.

Inspection recomputes durable freshness from a current source/policy capture, not
persisted SQLite counters or regenerated time-dependent candidate identity. It
returns effective stale/expired state independently of the original disposition,
and omits the sensitive projection when non-current. A final owner/read fence
prevents publication after concurrent writes, deletion or clock rollback. Reading
does not mutate rows, refresh expiry or advance execution/queue generations.

Privacy cleanup is intentionally conservative. Corrections/deletions of existing
context, intake, lifecycle or commitment records purge all proposal projections
and submission hashes in the same owner transaction, including proposals whose
complete policy inputs contained context absent from snapshot dependencies. New
constraint identities invalidate freshness without copying new content into old
projections. Imported evidence removal deletes snapshots and cascades projection
and state rows. No hidden content/hash audit survives proposal deletion. Authored
plans and unrelated activities remain unaffected. Conservative cleanup can remove
unrelated proposals; regenerate from current evidence instead of recovering copies.

Context deletion previews now disclose affected proposal IDs and counts. Their
digest includes proposal identity, projection/submission fingerprints and mutable
disposition, so proposal creation or rejection invalidates an earlier deletion
approval. The existing frontend deletion control validates these IDs and discloses
the count before explicit redaction approval. This is not the amendment approval
UI planned for task 8.5.

API: POST `/api/plans/amendment-proposals`, GET
`/api/plans/amendment-proposals/{id}/inspection`, and CSRF-protected POST
`/api/plans/amendment-proposals/{id}/rejection` with the exact projection digest.
All retain existing private no-store/authentication/request limits. No upstream
write, model inference, local plan/calendar change or proposal approval follows
generation, inspection or rejection.

Focused integration reached
76 tests with one failing test assertion: a substring check for forbidden stored
`exactPrompt` also matched required `exactPromptBindingSha256`. The test now checks
the exact JSON field; raw-draft and packet exclusions remain. Primary inspection
also corrected the child's rejection binding from submission hash to projection
hash, made bounded SQL listing fail above capacity instead of silently truncating,
reused the existing hash primitive and validated loaded rows. Child checks that
overlapped creation of primary-owned interfaces are intermediate compile failures,
not passing evidence. Final verification must include the owner/restart/race,
HTTP/CSRF, deletion-preview, canonical-schema and isolated backup/cascade tests.

Final full offline Java 25 backend verification passed **931 tests across 131
suites**, zero failures/errors/skips, independently counted from JUnit XML. No
Kotlin input changed during or after that run. `npm run build && npm test --
--maxWorkers=1` passed **326 tests across 25 files** and the frontend build, with
unchanged timeouts/assertions. The non-failing chunk warning remains (512.01 kB
minified / 147.05 kB gzip). `python3 -m unittest test_backups.py` passed all **10
tests**, including exact-schema refusal and synthetic proposal snapshot/cascade
checks. Strict OpenSpec and whitespace checks passed. Task 8.3 is complete;
approval/application, inference qualification and live acceptance remain separate.

## Transactional exact local approval (task 8.4)

`PlanAmendmentApproval.kt` adds a private frozen approval capability and service.
Authenticated CSRF-protected POST
`/api/plans/amendment-proposals/{id}/approval` accepts only a canonical operation
UUID, exact base-plan reference, expected selection generation and immutable
proposal digest. The body is bounded to 2 KiB and rejects unknown/duplicate fields
and primitive coercion. No client-supplied operation, replacement plan, timestamp
or model text can become execution authority. Inference, hosted-transfer consent,
local exact approval and external write-back remain separate permissions.

Preparation requires a selected, nonempty pending proposal and a current actual
adaptation source, complete policy inputs and durable bindings. It independently
checks every stored operation and the combined final result without enumerating
replacement candidates or rebasing. The final owner transaction checks for exact
receipt replay first, then verifies the original row/state, owner, same/external
connection mutation tokens, selection, source existence and temporal fences. It
revalidates operations at the current server time before its first write.

The resulting authored definition maps exact replacements over the original
session list, retaining order and every untouched field. Omission marks the
session rather than removing its history. `PlanStore.correctExact` shares the
existing normalization helper but refuses any value that normalization would
silently change; saved definition equality is checked again. Original author
provenance is preserved without implying coach/clinician approval. The local
approval receipt and new plan revision attribute the action to the authenticated
user. No explanation text is appended to plan notes.

One owner transaction appends a single local plan revision, advances selection,
performs existing constraint enrollment/review-reason invalidation, changes pending
to applied and inserts both receipt layers. A final monotonic-clock/exclusive
deadline check occurs after these writes but before return. Expiry or a backward
clock step rolls back the entire transaction, as do state/audit/binding insertion
failures. SQLite write contention fails closed; retry never silently rebases.

Receipt retention follows the existing content-free audit permission. The immutable
audit stores only operation/proposal IDs, applied local plan revision and timestamp,
with no source/context identity, explanation, request/projection hash or policy
details. A separate immutable binding holds the exact approval-request hash and
cascades with its proposal. Matching retries with a surviving binding return the
original applied revision despite subsequent edits, expiry or restart. A different
payload or operation for the applied proposal conflicts. Purging a proposal removes
all sensitive submission/projection/request hashes and explanations; its original
endpoint then returns not-found, never reconstructed authority. The retained
operation ID remains consumed, preventing reuse against a different proposal.
The initial permanent audit ceiling is 1,000, without eviction or UUID recycling.
This can refuse new approvals at capacity; it does not silently forget prior IDs.

Applied inspection is terminal and metadata-only, exposing the receipt without
preparing the obsolete base source or claiming the old projection is still a
plan-unchanged pending diff. Applied submission retries preserve this state; further
approval/rejection is unavailable. Context deletion previews disclose retained
content-free audit IDs and fingerprint the live approval associations/request
binding. The existing deletion control displays that retained-audit count and
privacy limitation before redaction approval. Deletion still removes proposal
text/hashes and preserves the independently owned applied authored plan.

The first integrated focused run reached 96 tests
with one failing fixture: it tried to generate fresh adaptation evidence after
context deletion had correctly invalidated causal proof. The fixture no longer
bypasses that gate; it verifies consumed-operation refusal before lookup of a
different proposal, while the original deleted proposal remains not-found. The
existing unavailable-proof boundary is unchanged. Synthetic tests also exercise
mixed exact moves/reductions/omissions, independent corrupted-operation/collision
revalidation, actual concurrent owners, restart replay, changed evidence/plans,
CSRF/authentication, injected transaction failures and final clock rollback. No
personal plan approval, model call, upstream operation, commit or deployment was
performed.

Final full offline Java 25 backend verification passed **943 tests across 133
suites**, zero failures/errors/skips, independently counted from JUnit XML. No
Kotlin input changed during or after this final run. `npm run build && npm test
-- --maxWorkers=1` passed **326 tests across 25 files** and the frontend build,
with unchanged timeouts and assertions. The existing non-failing chunk warning
remains (512.25 kB minified / 147.14 kB gzip). All **10 isolated backup tests**,
strict OpenSpec, whitespace and unchanged tracked benchmark diff passed. Task 8.4
is complete. UI task 8.5, inference qualification, personal runtime acceptance
and actual personal plan application remain separate.

## Exact-diff review and draft calendar controls (task 8.5)

`PlanAmendmentControls` consumes existing stored proposals only. Its explicit
bounded metadata index (`GET /api/plans/amendment-proposals`, default 25, maximum
50 per page, at most 1,000 records) is labeled uninspected. Each exact inspection
must pass strict envelope, state, revision, operation and receipt parsing before
display. Browsing, inspecting and attesting make no mutation or inference call.
No raw-draft editor, generation action, provider selector or new persistence was
added to this UI.

`AmendmentReviewConstraints` supplies an ephemeral complete local input superset
under the existing inspection owner/data-version/clock fences and 2 MiB bound.
It includes every captured context, confirmed commitment and authored occupied
interval, not only applicable inputs or those present in the immutable source
packet. Proposal ID/digest, policy digest/version, base, selection and capture time
bind this display to the exact inspected proposal. Stale, expired and applied
inspections omit these private values. The compact durable proposal remains
unchanged; no second evidence copy or schema change was introduced.

Every selected operation (up to eight) renders complete before/after values,
unchanged fields, author attribution, targets, precision/timezone/offsets,
permissions, explanation citations and uncertainty. Text remains escaped React
content. Semantic sections, named articles, native buttons/checkbox/details,
focus on inspection and status/alert regions support keyboard and screen-reader
use without color-only state. Automated accessibility checks cover these semantics;
no physical screen-reader acceptance or personal runtime acceptance is claimed.

Approval requires matching displayed selection, successfully inspected and bound
source evidence, an unexpired current pending proposal and fresh exact attestation.
Source refresh begins by revoking readiness without aborting its own read. Failed,
removed or mismatched reads hide cached private details. Record-page reads require
another successful snapshot-status refresh before approval. Relevant review epochs,
selection changes and local expiry hide old details and remove overlays. Late
source responses after unmount cannot restore readiness on a new mount.

The guarded mutation path prevents duplicate clicks. Unknown outcomes preserve the
original operation ID and exact request payload in memory, hold navigation/other
plan actions locked and never retry automatically. Explicit reinspection can
recover a bound receipt without another POST; a pending read cannot prove that
the original request will not finish. Explicit recovery reuses the identical
payload. Applied receipt handling clears the draft before refreshing authoritative
plans/calendar. Refresh failure is reported as already applied, not unknown
approval. Browser reload does not persist this ephemeral recovery state.

`PlansPanel` owns separate amendment/reconciliation/rule locks and a dedicated
applied refresh path. `PlanCalendarControls` preserves explicit dates/timezone and
refreshes previously loaded authoritative projections after application. Its
separate draft overlay never alters active authored entries. Local application,
hosted-transfer consent, inference qualification and upstream writes remain
separate permissions; see [dashboard instructions](../dashboard.md#exact-local-amendment-review).

Primary integration corrected child-client route/state assumptions, zero source
revision handling, weak revision/operation/state checks and rejected-outcome
parsing before accepting the client. Added checks cover eight selected diffs,
move/reduce/omit rendering, complete constraints absent from source packets,
malformed constraints, cached-detail removal, expiry/selection/source invalidation,
late reads/remounts, exact unknown-outcome recovery, navigation locks and preserved
calendar queries. Slice budget remained two children and no independent reviewer.

Verification checkpoint: full offline Java 25 backend run passed **945 tests / 133
suites**, zero failures/errors/skips, counted from JUnit XML. Focused TypeScript
and frontend checks passed **44 tests / 4 files**. The first full frontend build
passed, but its suite had **350 passes / 1 failure**: the integration test observed
source readiness before the parent overlay effect committed. The test now awaits
the accessible overlay instead of asserting it synchronously; its assertion and
default timeout are unchanged. A full build/single-worker rerun remains pending.
The build's non-failing chunk warning is 536.18 kB minified / 153.81 kB gzip.
All **10 isolated backup tests**, strict OpenSpec validation, task/documentation
paths, whitespace and unchanged tracked benchmark diff passed. The first backup
invocation used module discovery from the wrong directory and could not import
`mac_backup`; running `python3 scripts/test_backups.py` passed without code changes.

Final frontend rerun (`npm run build && npm test -- --maxWorkers=1`) passed the
build and **351 tests across 27 files**, with unchanged timeouts/assertions and the
same non-failing chunk warning. Backend inputs remained unchanged after their
945-test passing run. Task 8.5 is complete. No personal amendment, inference,
hosted transfer, deployment or other live operation occurred.

## Frozen new-profile longitudinal evaluation (task 9.1)

`benchmarks/scoped-profiles/` contains separately named v1 evaluations for scoped
description (12 scored cases), intake suggestions (eight) and amendment selection
(eight). `protocols-v1.json` freezes case membership, useful-content expectations,
scenario tags, six weighted dimensions per profile, 0–4 scoring anchors, outcome
accounting and the required independent-human gates: complete useful finals,
every case/dimension at least 2/4, equal-case weighted mean at least 80% and zero
critical failures. Missing/failed/empty/unrun cases remain in the denominator;
schema validity and model self-review cannot open admission.

`ScopedEvaluationFixtures.kt` uses fixed synthetic identities and receive
checkpoints across weeks of training, shared dated wellness, older ACL guidance,
illness revisions, authored sessions and commitments. It calls actual production
scope, facts, retrieval, budgeting, candidate, profile and invocation builders.
`artifacts-v1.json` freezes exact initial/correction prompt objects, packet strings
and production consent digests. The manifest pins protocol/artifact bytes and
fixture/exporter/verification/relevant production sources. Ordinary Kotlin tests
regenerate and compare exact bytes; they never update the frozen files. Explicit
materialization writes only to a new empty staging directory, and initial freezing
refuses existing artifact/manifest files. Any material revision needs a new version.

Three mandatory zero-call preparation guards supplement, not replace, the 28 scored
cases. Stale unresolved illness and unverified night mapping correctly block before
inference; absent authored permissions yield no candidates and no prompt. The
illness follow-up uses explicit synthetic user reconfirmation of applicability,
not an inferred recovery or a bypass. Unknown readiness and method semantics remain
unavailable. Pure artifacts do not prove persistent snapshot/deletion/approval
authority; those deterministic repository journeys remain task 9.2.

Integration rejected the routine child's generic placeholder cases and incorrect
profile/intake field assumptions before freezing. The primary replaced them with
concrete cases and semantic assertions. Early export checks exposed a 62,082-byte
mixed-sport period-comparison packet, stale-context preparation refusal and an
oversized amendment prompt. These were fixture defects, not relaxed guards: the
explicit period comparisons now use running-only scope (mixed-sport comparison is
still separately covered), the stale checkpoint became a required zero-call guard,
and amendment evidence uses the exact selected extra-workload activity rather than
an unnecessarily large period packet. Existing byte limits and admission checks
are unchanged. Initial and correction prompts both fit 32,768 UTF-8 bytes.

The four Kotlin artifact/semantic checks passed during explicit staging export;
the Python verifier and eight integrity/rubric tests passed on frozen artifacts.
Negative checks include packet/prompt/correction/rubric/source tampering, incomplete
case/guard membership, stale manifests, weighted arithmetic, per-dimension minima,
critical failures and unavailable human scores. Tracked legacy benchmark diffs and
whitespace checks passed. A full backend run, including ordinary read-only frozen
comparison, is pending. Slice budget: two children, no independent reviewer.

No model, provider, candidate runtime or human scorer is selected by this work.
Candidate-specific settings/resource bounds must be separately recorded and approved
before a run. No inference, transfer, qualification, personal runtime acceptance or
personal amendment occurred. See [protocol use and limitations](../../benchmarks/scoped-profiles/README.md).

Final full offline Java 25 backend run passed **949 tests / 134 suites**, zero
failures/errors/skips, counted from JUnit XML. This includes ordinary exact-byte
frozen comparison without export mode. The protocol verifier, eight Python checks,
strict OpenSpec, 91 local links, whitespace and unchanged tracked legacy benchmark
diff passed. Frontend inputs remain unchanged from the accepted 351-test/build
run. Task 9.1 is complete; qualification and personal runtime gates remain closed.

## Repository-backed deterministic journeys (task 9.2)

`ScopedEvidenceJourneyTest` and `ContextAmendmentJourneyTest` connect existing owner
APIs and temporary synthetic repositories, rather than treating draft JSON as the
user journey. No provider is constructed. The evidence source deliberately refuses
upstream reads and checks zero calls. Frozen protocol inputs and production code
remain unchanged.

The evidence journeys inspect exact session membership and record pages, actual
three-sport comparison, intervening-record exclusion, display-only versus assessed
wellness, shared observation identities, default period wellness and explicit
overlapping/later comparison dates. A verified past instant with a next-day
source-local travel date retains that date under the saved UTC schedule cutoff.
Morning preparation retains older source-dated sleep, not invented current sleep;
unverified night mapping blocks. Same-field/same-unit HRV observations from two
origins remain separate, with unknown method/basis verification and no computed
baseline or readiness. A linked evening checkpoint has no imported recording until
its explicit late receipt; fresh preparation then includes it while the prior
immutable snapshot becomes stale. Preparation/inspection leave plan state unchanged.

The context journey starts with one original note and an unconfirmed, validated
synthetic suggestion. Explicit batch confirmation reuses its source identity for
an older ACL restriction, illness and a weekly commitment; replay creates no
duplicates. Stale unresolved illness blocks adaptation without changing the plan.
Only explicit user resolution removes that blocking, without medical recovery.
The older restriction remains mandatory, recurrent occurrences retain their
identity, prohibited load increases/substitutions fail and the available proposal
contains only permitted omission. Generation/inspection still leave the plan
unchanged. A later explicit authored edit makes exact approval stale; shared-source
redaction then removes the proposal and private source text while preserving the
surviving illness lifecycle, commitment, imported activity and authored plan. A
separate recording journey requires explicit confirmation of competing recordings
without changing the authored revision.

Existing granular suites remain complementary acceptance evidence:

- `ScopedReviewEvidenceTest`, `ScopedEvidenceStoreTest`, `ScopedReviewScopesTest`
  and `ScopedWellnessEvidenceTest`: membership, binding, coverage and date semantics.
- `PersonalSignalRulesTest.incompatibleAmbiguousUnverifiedAndMissingAllBreakCounts`
  tests actual distinct method identities; real adapter journeys intentionally do
  not pretend it can supply verified methods. `actualRawAdapterEvidenceNeverGainsConfiguredVerification`
  preserves that boundary.
- `ContextConfirmationTest`, `ContextDeletionTest` and `PlanReconciliationStoreTest`:
  atomic confirmation/replay, shared-source redaction and ambiguity ownership.
- `PlanAmendmentPolicyTest`, `PlanAmendmentPreparationTest` and
  `PlanAmendmentApprovalOwnerTest`: uncited constraints, whole-result rejection,
  current source/expiry fences, exact application/replay and transactional rollback.

The implementation child supplied three partial evidence journeys; the primary
added missing assessment/shared-observation, full mixed-sport, later-comparison and
morning/evening chronology assertions plus the context/approval journeys. One
independent review requested stronger same-field HRV evidence. The primary added
separate-origin observations and unavailable-baseline checks, while retaining
existing explicit different-method unit tests rather than inventing adapter
verification. Slice budget: two children including that review, no slots remaining.

Initial focused verification ran 28 tests with one failure: the test helper tried
to construct a restriction before supplying its mandatory structured fields.
The helper now supplies those fields in its constructor; no production check was
relaxed. Focused rerun and final integration verification remain pending.

The focused rerun passed **28 tests across four suites**, including the new
journeys, signal compatibility and exact approval owner tests. The primary then
added explicit multiple-session review and cross-sport duration-only comparison
assertions. A full backend run is checking those final inputs. Protocol integrity,
strict OpenSpec, whitespace and unchanged legacy benchmark checks still pass.

Final full offline Java 25 backend verification passed **955 tests / 136 suites**,
zero failures/errors/skips, counted from JUnit XML. The final multi-session and
compatible-duration assertions are included. Protocol integrity, strict OpenSpec,
94 local links, whitespace and unchanged tracked legacy benchmarks passed.
Task 9.2 is complete; no model or live source was called and no personal state was
changed.

## Private offline capture and human review (task 9.3)

The additive `benchmarks/scoped-profiles/capture.py` accepts one explicit new
profile and its complete frozen case order, verifies the frozen protocol, and
requires separate private replay approval before reading responses or invoking a
validation callback. Approval binds the exact run digest, candidate/model version,
settings, resource bounds, manifest and case membership, with explicit correction
permission and a bounded validity window. Only offline local replay is accepted.
No live provider adapter, network request, model discovery or new production
admission path exists. The separate task 9.4 acquisition remains unauthorized.

External `0700` directories and regular single-link `0600` files hold inputs, raw
responses, exact hashes, capture/review packets and validator diagnostics. The tool
rejects repository paths, symlinked parents/files, permissive inputs and overwrites.
Public diagnostics remain fixed content-free messages. Captures retain every
first/correction attempt, raw/partial outputs, settings, imported resource metadata,
prompt/tooling/approval/raw hashes and a final row for every case, including unrun
cases. Orphan, redundant and post-timeout corrections remain visible but cannot
replace a final. Recorded resource overruns cannot count as accepted finals;
missing resources remain unknown, not measured or inferred. No automatic retry or
resume occurs.

`ScopedCaptureValidationTest` is a test-source-only Kotlin bridge. It regenerates
and checks exact frozen artifacts, uses the actual three profile contracts and
checks combined amendment results against the production policy. It handles both
attempts in one bounded batch without enlarging the production draft envelope.
Private outputs contain only case/attempt/status, never raw validation exceptions.
The human-review packet includes exact inputs, expectations, rubric and blank
scores. Accepted syntax does not pre-fill usefulness, independent human scores or
critical-failure findings. Every replay leaves qualification incomplete and
production admission closed. Local approval records are not authenticated evidence
of human authority or permission to acquire new responses.

Primary inspection corrected the bounded child bridge before integration: duplicate
identity was changed from case-only to case/attempt, attempts were limited to 1/2,
the private root check no longer assumes a nested backend directory, output modes
are set atomically at creation and the batch parser has its own bounded envelope
without weakening the model parser. Additional tests cover both attempts, duplicate
keys, primitive coercion and a batch larger than 32 KiB. The implementation remains
outside production; no schema, frozen evaluation file or legacy benchmark changed.

The 17 Python protocol/capture tests pass, including all profiles, failed/empty/
timeout/unrun/correction retention, zero callbacks without matching separate replay
approval, tampering, private modes/links and runtime-generated sensitive-marker
isolation. These fake callbacks test control flow, not validation or live stopping.
Actual private Kotlin bridge replay and full backend verification remain pending.
Slice budget: one architect and one implementation child, no independent review.

The first actual bridge acceptance command exceeded its outer 120-second shell
limit while `--rerun-tasks` rebuilt production Kotlin; it did not reach validation
or make a model call. Private staging evidence was retained. The caller now reruns
only the selected test task (`--rerun`), not all compilation dependencies, and the
acceptance shell allows 600 seconds. A fresh private-directory retry is pending;
no timeout assertion or production execution deadline was weakened.

The descriptive-profile actual bridge replay passed in a new approved temporary
directory, retaining all 12 cases, rejected first/accepted correction, timeout,
empty and unrun outcomes with zero inference. Empty valid arrays here are only
structural fake responses; they are not scored useful finals. Equivalent intake
and amendment replays plus runtime-generated marker scans are pending.

Actual Kotlin bridge replays now passed for all three explicit profiles (12/8/8
case rows retained). Intake and amendment captures each retained rejected first and
accepted correction plus all unrun rows. Runtime-generated sensitive markers were
present only in private raw captures, absent from repository benchmark files,
validator logs and JUnit outputs. The amendment bridge's ordinary tests also reject
altered explanations and the combined-collision case. A full backend run is pending;
the final 17-test Python run and frozen/legacy integrity checks passed. Private fake
evidence remains under the approved temporary root; it is not qualification data.

Final full offline Java 25 backend checks passed **958 tests / 137 suites**, zero
failures/errors/skips, counted from JUnit XML. Frozen protocol verification, strict
OpenSpec and whitespace checks passed. Task 9.3 is complete. Task 9.4 remains
unchecked and qualification closed: no candidate inference or independent human
scores were authorized or collected.

## Integration responsibility and duplication audit (task 10.1)

The primary audit follows the responsibility rows in the design's refactoring plan,
not a rewrite of the uncommitted change. Canonical manual and scheduled preparation
share `ScopedReviewScopes`, `ScopedEvidencePreparation` and interval-based
`ScopedContextRetrieval`; purpose, selection, intent and wellness policy remain
separate inputs. Amendment preparation projects the final plan horizon explicitly
instead of inheriting a descriptive display window. Snapshot inspection, execution
binding and approval retain independent current-state checks; consolidating those
checks into the initial assembler would weaken their authority boundaries.

`HistoryStore` retains transaction ownership while the cohesive context, plan,
evidence, signal, reason, proposal and approval stores own their named SQL. Reason
admission/high-water and deletion/approval transitions remain atomic. Signal
compatibility, baselines and candidate policy use their existing pure helpers;
planned targets, self-reported effort and imported measurements remain distinct
typed inputs rather than one permissive rule parser. The existing invocation and
execution-session seams are reused, not a second personal worker or a merger with
the isolated development worker. Frozen legacy adapters retain their own validators,
encoders and qualification history.

Scope/factual rendering already shares
`ScopedReviewControls`, `ScopedReviewTypes`, `ReviewApi`, `ReviewMetrics` and
`MetricCard`; the Charts and Reviews entry points do not calculate their own
scopes. `ContextFields`/`CommitmentFields` serve entry and redaction, `PlanFields`
serves authored revisions, and `ScopedEvidenceInspection` serves review and exact
approval inspection. `PlansPanel` owns cross-panel/navigation locks, separate draft
overlay ownership and the acknowledged-application refresh path. Exact amendment
diffs do not reuse authoritative calendar entries as mutable drafts.

Small parser predicates remain local when their contracts differ: nullable context
dates are not the strict scope-date predicate; plan authored timestamps have exact
minute precision while imported activity may have seconds/fractions; profile state,
revision and explanation checks remain at their own response boundaries. Sharing a
generic permissive schema/state parser would erase these distinctions. Existing
request/error/object/equality primitives are reused through `ContextIntakeClient`.
No global client framework or generic mutation state machine was added.

The only mechanical extraction selected here reuses the existing exact UTF-8
`AnalysisClaims.hash` primitive for `HistoryStore` string/tuple digests, context
confirmation receipts and deletion graph digests. Their existing JSON settings,
length-prefixed fields, row ordering and source tuple encoding remain untouched.
`ExactHashReuseTest` pins known empty/ASCII hashes, both former hex encodings,
non-BMP/combining/control text and opaque source delimiters. Streaming bounded SQL
deletion digests remain streaming rather than allocating a second graph string;
legacy prompt/report/development encoders remain unchanged. Naming cleanup is not
worth invalidating frozen artifacts or enlarging the patch.

The new offline capture tool remains separate from runtime admission and legacy
model runners. Frozen input materialization and structural response validation
share production Kotlin contracts; Python handles only private-file control flow,
hash binding and rubric arithmetic. Its larger batch envelope does not enlarge the
production model parser or bypass whole-result amendment policy.

No Gradle/npm dependency files changed. No new deployed service, worker process,
ORM, vector store or package dependency was introduced. A read-only architecture
child inspected the backend owner/scheduler boundaries; the primary independently
checked its concrete finding and the resulting code. No acceptance claim rests on
the reviewer model identity.

The backend audit identified one concrete integration defect, independently checked
by the primary: adaptation enriches final context from the canonical plan-horizon
selection (up to 1,000 optional items), while source links/bindings had been filtered
from the earlier ordinary projection (20 optional items and reporting-zone cutoff).
An older still-applicable symptom could therefore be restored without its exact
source provenance and intake dependency. The smallest fix derives source links and
bindings from the final selected context/calendar references and canonical source
heads. Independent approval/deletion fences still exist; this finding is not proof
of an approval bypass. The bounded fix is implemented in `AdaptationResult.kt`;
the ordinary assembler and its frozen manifest remain unchanged. The new owner
regression first failed on the missing link, then all **16 adaptation-result owner
tests** passed. It covers an intake-confirmed older symptom, 21 newer notes, exact
context revision/source binding, intake dependency and rejected publication after
source correction. Primary inspection also added a published-snapshot digest and
correction-invalidation assertion; final verification includes this extension.

The first primary combined regression command stopped at test compilation because
that extension omitted `kotlin.test.assertFails` from the explicit imports. The
missing import was added; the combined regression command then passed. This was
a test-source failure, not an accepted backend build or a production behavior change.

A fresh frontend rerun then passed 350/351 tests but exposed an asynchronous test
assertion in `AdaptationRuleControls.test.tsx`: the authoritative reload text can
render before React delivers the lock-state effect callback. The component already
clears uncertainty only after a parsed authoritative response; no production lock,
retry or permission behavior was changed. The test now waits for the same required
`false` callback instead of asserting it synchronously after finding text. The
one-write/no-retry assertions remain. The first focused repetition exposed the same
race after a valid save receipt in that file; its required unlocked-callback check
now uses the same asynchronous wait. All three focused repetitions then passed
**9/9 tests**, followed by the full **351/351 tests in 27 files** and frontend build.
Neither failed test chain reached its build. The final successful build retained
only the non-failing **536.18 kB minified / 153.81 kB gzip** chunk warning.

The first full backend build passed, including the exact-hash cleanup; frontend
build and **351 tests / 27 files** passed with the existing non-failing chunk warning.
Broad Python checks passed **53 script tests**, then found one historical benchmark
test still parsing removed schema-migration source text. That checker now pins the
exact unchanged published schema-1 seed bytes and verifies its unmarked application
identity, rather than assuming today's fresh-only schema retains a migration chain.
The old seed, image, model fixtures and captures are unchanged. This is a test
maintenance change, not historical database migration or relaxed schema validation.
Output mentioning an example promotion PR is from mocked script tests; no PR,
merge, publication or deployment occurred.

The corrected broad Python run passed **53 script tests, 122 legacy Ollama tests,
47 legacy connected-review tests and 17 scoped-profile tests** (239 total). Legacy
capture tests use fake responses; their printed candidate/attempt lines do not
represent model execution. Frozen fixture/image/capture files remain unchanged;
only the obsolete source-text checker was updated. The final backend build passed
**962 tests / 139 suites**, with zero failures, errors or skipped tests, including
the extended provenance regression and linked daily-slot acceptance test. The
focused precursor passed **19 tests / 3 suites**. Task 10.1 is accepted; its two
child sessions and primary inspection did not replace these checks.

## Final diagram and scenario-contract audit (task 10.3)

The primary reread all four Mermaid diagrams and all seven delta specifications.
The diagrams describe permitted interactions once prerequisites exist, not a claim
that inference, deployment or personal runtime acceptance has been authorized.

| Diagram | Implemented path and deterministic evidence | Boundary retained |
| --- | --- | --- |
| Scheduled sequence | Shared intake confirmation, actual scheduler/queue preparation, immutable facts, candidate policy and exact transactional approval are exercised by `ContextAmendmentJourneyTest`, `DailyPurposeJourneyTest`, `PersonalScopedQueueTest` and `PlanAmendmentApprovalOwnerTest`. | Source sync is separate; no qualified provider is manufactured. The new linked two-slot test passed. |
| User interaction | Canonical options/preparation, included/display-only/missing inspection, explicit context confirmation, no-change/clarification/rejection, exact diff and applied receipt are covered by the evidence/context journeys and frontend control suites. | No hidden history re-entry, provider fallback or implicit application; stale/expired/unknown outcomes remain distinct. |
| Components | Scope resolution, evidence assembly, interval retrieval, reason/policy checks and SQL helpers share the existing owner/executor boundaries; final adaptation provenance is corrected within that boundary. | Independent validation remains independent; isolated development storage and legacy validators do not inherit personal authority. |
| Deployment | Tests cover authenticated APIs, separate synthetic stores, closed runtime admission and exact hosted previews. | No topology change, source probing, runtime ownership acceptance, resource allocation, publication or deployment was performed. |

Scenario contracts are traced by responsibility, including negative and conditional
scenarios rather than counting only successful drafts:

| Delta specification | Scenario coverage and evidence |
| --- | --- |
| `review-time-scopes` | `ScopedReviewScopesTest`, `ScopedReviewEvidenceTest`, `ScopedEvidenceJourneyTest`, `ReviewPurposeSlotsTest`: rolling/calendar/leap/partial/unequal/explicit comparisons; exact activity membership and limits; original local timestamps; mixed sports; older applicability; future commitments; DST/saved timezone and verified travel cutoff. Unknown future activity timing is blocked, not invented. |
| `review-evidence` | `FactualReviewsTest`, `ScopedEvidenceApiTest`, `ScopedEvidencePreparationTest`, `ScopedEvidenceStoreTest`, the linked journeys and privacy tests: canonical routes and retired-route refusal; no upstream reads; single-entry context reuse; optional wellness roles, shared observations, current limitations, immutable dependencies/consent and bounded annual/mandatory-overflow behavior. |
| `training-health-data` | `ScopedWellnessEvidenceTest`, `PersonalSignalRulesTest` and the evidence journey: exact signal identities, receive chronology, separate HRV/SDNN, unknown methods/readiness, daily duplicate ambiguity and stable imports. Conditional vendor-readiness support is not claimed for the current adapter; the unavailable branch remains explicit. |
| `athlete-context` | `ContextConfirmationTest`, `ContextLifecycleStoreTest`, `CommitmentProjectionTest`, `ContextDeletionTest`, the context journey and frontend intake/deletion suites: original attribution, manual extraction fallback, confirmation/replay bounds, correction/reconfirmation, unresolved illness versus resolution, recurrence/exceptions, explicit event links, interval conflicts and approved shared-source redaction. |
| `personalized-training-guidance` | Frozen legacy contract tests, three new profile contract suites, `ConnectedProfileExecutionTest`, `ScopedQueuePreviewTest`, daily/personal queue tests and frozen protocol checks: descriptive boundaries, comparison/context instructions, missing-data cutoffs, factual unchanged status, exact consent, closed new-profile admission and explicit restart rearming. Usefulness and candidate qualification remain task 9.4, not a deterministic-test claim. |
| `training-plan-adaptation` | Plan/reconciliation/effort/signal/constraint/reason/result/policy/proposal/approval owner suites and the linked context journey: explicit authored base, no event-as-plan, one-record matching/ambiguity, late imports, typed basis and detached testimony, compatible configured rules, reason coalescing/high-water, conservative bounded candidates, uncited constraints, DST/undefined semantics, exact explanations, combined collisions and idempotent atomic approval. Inspection/generation never applies a revision. |
| `guidance-dashboard` | `OverviewTiles.test.tsx`, `ScopedReviewControls.test.tsx`, `ScopedEvidence.test.tsx`, context/suggestion/deletion suites, `ReviewControls.test.tsx`, `PlansPanel.test.tsx` and `PlanAmendmentControls.test.tsx`: shared client/forms/cards, dates and wellness roles, source limitations, single-entry updates, schedule/consent status, exact before/after inspection, unavailable stale approval and visibly separate draft overlays. |

Supported normalized evidence and contributing records are inspectable without
screenshots. Already saved context is reused by identity; unresolved or unsupported
facts still require a targeted clarification, not invented data. Lap/power/HR-stream
questions, verified prior-night mapping, readiness and external calendar coverage
remain explicitly unsupported where the adapter cannot supply them. Manual
structured context confirmation and authored plan entry remain usable without any
model. Local user plan edits are explicit authored revisions; an amendment changes
the selected plan only through exact approval. Synthetic two-slot execution is not
live unattended-runtime acceptance. The verified local status and remaining gates
are recorded below.

## Final offline verification and local handoff (tasks 10.2/10.3)

**2026-10-18:** implementation and deterministic integration are complete for tasks
10.1–10.3. Task 9.4 remains incomplete and approval-gated. All source changes remain
uncommitted and undeployed. This is a local implementation handoff, not release,
runtime or qualification acceptance.

The following commands ran against the final source without installing dependencies
or enabling providers:

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 25) ./gradlew --offline --no-daemon --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process :backend:test --tests com.gtrainer.AdaptationResultOwnerTest --tests com.gtrainer.ExactHashReuseTest --tests com.gtrainer.DailyPurposeJourneyTest
JAVA_HOME=$(/usr/libexec/java_home -v 25) ./gradlew --offline --no-daemon --max-workers=2 -Pkotlin.compiler.execution.strategy=in-process :backend:build
npm --prefix frontend test -- --maxWorkers=1
npm --prefix frontend run build
python3 -m unittest discover -s scripts -p 'test_*.py'
python3 -m unittest discover -s benchmarks/ollama -p 'test_*.py'
python3 -m unittest discover -s benchmarks/connected-review -p 'test_*.py'
python3 -m unittest discover -s benchmarks/scoped-profiles -p 'test_*.py'
python3 benchmarks/scoped-profiles/protocol.py
/Users/tomasklubal/.local/bin/openspec validate scoped-reviews-and-adaptive-planning --strict
git diff --check
```

- Backend build and **962 tests / 139 JUnit suites** passed, with **0 failures,
  0 errors, 0 skipped**. The focused command passed **19 tests / 3 suites**.
- Frontend **351 tests / 27 files**, type checking and production build passed.
  The rule-controls file additionally passed **three consecutive 9-test runs**
  after the two asynchronous test assertions were corrected. Existing Kotlin
  warnings and the frontend chunk warning are non-failing; no size/timeout limit
  was raised to obtain a pass.
- Python **53 script + 122 legacy Ollama + 47 connected-review + 17 scoped-profile
  tests = 239** passed. The script suite includes all **10 backup tests**, using
  temporary synthetic databases and isolated decryption only. No installed database
  or backup helper was read, reset, migrated, restored or replaced.
- `HistorySchemaTest` passed **11 tests** covering exact fresh schema, immutable
  markers, atomic DDL/seeds, refusal without repair and reopen without reseeding.
  The canonical manifest remains **55 tables / 16 indexes / 10 triggers**,
  `user_version=1`, `application_id=0x47544E52`. Read-only restore validation rejects
  legacy, malformed and wrong-identity schemas; no historical migration is implied.
- Privacy-marker, authenticated API, development store isolation, launcher refusal,
  worker cancellation and event-driven 24-hour retention regressions all passed in
  the full backend run. In particular, the development store/launcher/worker/
  retention suites passed **9/9/5/6 tests**. Their fake runtime receipts are not
  proof of a live runtime stop. The private replay bridge is inert without its
  explicit directory setting; the separately recorded task-9.3 offline replays,
  not this unset hook, supply its three-profile replay evidence.
- Frozen protocol integrity passed for **3 closed profiles / 28 cases**. The
  backend legacy-baseline and new-artifact suites passed **5 and 4 tests**.
  Legacy fixtures, image inputs and captures are unchanged; dependency-file diffs
  are empty. Relative file/anchor checks passed for **165 local links across 25
  changed/new Markdown files**, including the final evidence links.
  Tracked whitespace checks and checks of **262 new source/document/artifact files**
  passed. Strict OpenSpec validation passed, including the final task bookkeeping;
  the checklist is an index to this evidence, not proof by itself.

Usable behavior does not depend on inference: enter and confirm attributed context
once; enter/select an authored plan; prepare and inspect selected activities or
periods, comparisons, wellness roles, source coverage, constraints and deterministic
findings; explicitly enroll morning/evening factual preparation. The linked daily
journey verifies both real scheduler/queue outcomes, distinct cutoffs, no duplicate
routing, unchanged selected plan and explicit re-arm after restart. Stored amendment
controls inspect exact before/after state; only an exact current approval creates
the new local plan revision. No screenshots or repeated entry of already confirmed
supported context are needed. Missing streams, ambiguous source timing, unverified
readiness and unsupported external adapters remain visible limitations, not inferred
facts or hidden fallbacks.

Remaining gates are independent:

1. **Task 9.4, per-profile synthetic qualification:** separate explicit approval for
   named candidates, inference settings/resources and the frozen protocol, followed
   by complete useful finals and independent human scoring. All three profiles stay
   unqualified/closed until their own decision is recorded.
2. **Personal runtime acceptance:** separate operational approval and actual proof
   of exclusive ownership, stop/resource/health controls and the selected runtime.
   Synthetic tests or qualification cannot substitute for this acceptance.
3. **Exact personal amendment approval:** inspect the current proposal, evidence,
   before/after digest and selected plan revision. Inference or hosted consent does
   not authorize application; the synthetic plan revisions in tests are not a
   personal approval.
4. **Release and operations:** commits, pushes, PRs, merges, image publication,
   transfers, installed-database actions, helper updates and deployment remain
   separately unauthorized. No live source probing, model download or inference
   occurred. External calendar/plan adapters and autonomous restart recovery remain
   separate changes, not unresolved implementation requirements here.

Next action is the qualification approval/scoring decision for task 9.4, not more
implementation scaffolding. Preserve the frozen evaluation version; a material
protocol/prompt change requires a new version, not silent manifest refresh. The
short `.opencode/SESSION-HANDOFF.md` is local-only and must not be committed.
