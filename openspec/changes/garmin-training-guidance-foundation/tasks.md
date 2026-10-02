# Tasks

## 1. Verify Intervals.icu coverage and the Pi

- [x] 1.1 Inspect Pi 5/K3s CPU, memory, storage, persistent volumes, and LAN routing; verify a written inventory against hardware and cluster commands.
- [x] 1.2 With the user's currently valid key held only in a local secret, inspect read-only Intervals.icu activity and wellness coverage, non-null values, source attribution, history, and freshness; verify a field-coverage report that contains no personal values or key and flags unverified scale origin and absent Garmin-only scores.
- [x] 1.3 Record Intervals.icu as the preferred source, its data-sharing and API-key rotation/revocation risks, and a replaceable import boundary for exports or other future sources; verify the decision does not assume direct Garmin API approval or claim Intervals.icu fields equal every Garmin metric.
- [x] 1.4 Record the local store, LAN access, retention/backup approach, Git host, CI service, and image registry suited to the Pi; verify a decision note covers privacy, resource limits, and how Flux will reach the repository and image without exposing the cluster to CI.
- [x] 1.5 Benchmark small local Ollama model choices on the Pi 5 using synthetic cross-metric trend samples; verify a decision note records memory, response time, grounding quality, and whether local inference is usable before selecting a default.

## 2. Set up the private app

- [x] 2.1 Scaffold a Kotlin/Ktor backend and React/TypeScript UI with repeatable build/test commands; verify a fresh checkout builds and both baseline test suites pass.
- [x] 2.2 Add a container build and CI pipeline that tests the app and publishes a Linux ARM64 image to the chosen registry; verify a failing test blocks publication and the published image runs on Pi architecture.
- [x] 2.3 Bootstrap Flux in the existing K3s cluster and add version-pinned, private LAN-only deployment manifests with persistent storage and health checks; verify Flux reports a healthy rollout and no public access exists.
- [x] 2.4 Configure protected automated image promotion after passing tests so Flux pulls and applies an immutable new image reference; verify a test release appears on K3s without giving CI cluster credentials.
- [x] 2.5 Add single-user access control and provision the Intervals.icu API key and any model credentials outside Git or encrypt them with SOPS/age while keeping keys off Git; verify unauthenticated requests fail and CI/logs/manifests expose no raw secrets.
- [x] 2.6 Document Flux bootstrap, image promotion, install, upgrade, backup, restore, rollback, and removal on the Pi; verify a test backup restore and a Git image-revert rollback both work.

## 3. Collect Garmin-derived history from Intervals.icu

- [x] 3.1 Implement a read-only Intervals.icu activities/wellness adapter using a rotatable API key and a replaceable source interface; verify API auth failure, revoked-key handling, no upstream writes, and category tests using synthetic fixtures.
- [x] 3.2 Add normalized activity and wellness records with Intervals.icu IDs, upstream source when known, sport, units, and timestamp context; verify synthetic examples preserve absent values and time zones without committing real records.
- [x] 3.3 Validate input, prevent duplicate imports, report per-category Intervals.icu fetch outcomes and unknown upstream freshness separately, and allow local data removal; verify invalid/repeated records, API failures, missing recent data, and deletion with tests.
- [x] 3.4 Document verified field coverage, source limitations, intermediary privacy, key rotation, and missing proprietary Garmin scores; verify docs match tests and do not disclose credentials or personal values.

## 4. Show history and connect the trends with AI

- [x] 4.1 Compute activity-by-sport, recorded-activity-time, and calorie trends over user-selected periods; verify totals and period comparisons with known test records and label recorded time distinctly from Garmin intensity minutes.
- [x] 4.2 Show verified populated weight, VO2 max, HRV, and sleep values with source, dates, and units; verify fitness age, endurance score, Garmin training status, and any other unavailable field show 'unavailable', while Intervals.icu load values are named separately.
- [x] 4.3 Create a reproducible, source-linked summary of validated metrics and period comparisons for AI input; verify known samples contain dates, units, provenance, and missing/stale-data flags without invented scores.
- [ ] 4.4 Add a switchable model interface with a user-selected local model option; verify selected local inference receives only the needed summarized data and changing models leaves stored Garmin records untouched.
- [ ] 4.5 Add an explicitly selected hosted-model option behind informed user consent, with no automatic fallback or sensitive prompt/key logging; verify no personal data leaves the device without consent and log tests find no sensitive values.
- [ ] 4.6 Generate cross-metric observations from the selected model and validate each personal claim against supplied metrics and time ranges; verify tests reject invented Garmin values, unsupported causal claims, diagnoses, and workout prescriptions.
- [x] 4.7 Keep charts usable when no model works or data is too sparse and report why AI analysis is unavailable; verify outage, insufficient-input, and unusable-output tests.
- [ ] 4.8 Document available metrics, model selection, local resource needs, hosted-data consent, and analysis limits; verify instructions and sample observations match tested behavior.

## 5. Add manual events and dashboard

- [x] 5.1 Add local events with start/end dates, sport, goal description, and optional current-state notes; verify create, edit, delete, multi-day trips, and date sorting with tests.
- [x] 5.2 Show the next event, trends, missing-metric labels, latest Intervals.icu read and uncertain upstream freshness, model selection/status, and separately labeled AI observations with supporting metrics/dates; verify empty, stale, API-failure, model-failure, and unauthenticated states with UI tests.
- [x] 5.3 Document manual events and how to request/review AI trend observations without Google Calendar or workout scheduling; verify the instructions against the running app.

## 6. Check the whole phase-one flow

- [ ] 6.1 Verify a tested ARM64 release reaches the Pi through CI and Flux and that a read-only Intervals.icu import feeds historical charts and grounded AI cross-metric observations while a manually entered trip appears as the next event; confirm no non-local model transfer without consent and that dashboard data stays private.

## 7. Code-prepared reviews and background scheduling

- [x] 7.1 Freeze a synthetic-only code-prepared evidence-group/focus-selection protocol and fixtures with independent validation and no output repair; verify complete factual coverage, missing/unchanged/zero values, incompatible periods, malformed/unsupported outputs, deterministic no-model rendering and bounded corrective-attempt rules with regression tests. This is an experimental benchmark protocol, not production qualification or unrestricted interpretation.
- [x] 7.2 Run the frozen synthetic protocol on Qwen3 4B Instruct and pinned Qwen3.5 4B in isolated owned Pi resources at three cores/5 GiB; retain original attempts, separate first-pass from corrected validity and relevance, replay outputs, record latency/memory/guard failures and verify cleanup/live health. Stop resource failures without retries or cap increases; report inability to finish separately from semantic failure and preserve all earlier benchmark results.
- [x] 7.3 Implement code-owned complete factual groups and a separately validated optional interpretation path in the app; verify evidence/model invalidation, independent factual rendering, whole-response rejection and bounded corrective attempts without silently replacing the installed prototype contract or enabling live inference.
- [x] 7.4 Implement explicitly enabled editable daily-combined, after-activity and weekly presets with assignable supported time/data/count/step triggers, advanced scope/level/budgets and a time-zone-aware scheduler; verify disabled defaults, unchanged imports, once-per-period thresholds, unsupported steps, daylight-saving/travel behavior and no hosted-consent bypass.
- [x] 7.5 Implement the single-running coalescing review queue with merged reasons/highest level, execution-time snapshots, debounce/cooldown/maximum deferral and cancellation/invalidation; verify separate scopes/windows, no evening suppression of immediate reviews, no starvation or repeated running cancellation, crash recovery and stale-result labeling.
- [x] 7.6 Add review preset configuration and next-check/next-review/trigger/queued/running/last-success coverage UI and operator documentation; verify authenticated UI states, unsupported rules, configurable budgets, factual/AI separation and independent chart responsiveness without Google Calendar or plan changes.

### Guarded local prototype status

The approved background-review agreements are now captured in proposal, design
and executable requirements. Task 7.1's separate synthetic protocol and regression
checks passed (92 benchmark tests, 47 utility tests and strict spec validation).
At that benchmark milestone tasks 7.5–7.6 remained outstanding; subsequent local
queue/UI completion below does not qualify the existing prototype or enable live inference.
See [code-prepared experiment](../../../docs/decisions/code-prepared-review-experiment.md).

The subsequent approved deterministic continuation implements the factual portion
of task 7.3: complete all-metric/all-sport groups, digest-bound authenticated
read-only API, independently rendered factual UI and rule-selected relevant focus.
The existing optional prototype contract and zero-correction policy are unchanged.
The subsequent separately selected `review-focus-app-v1` API/runtime completes
task 7.3 with whole-response validity/relevance checks, immutable evidence/model
invalidation, shared single-flight access, call/job deadlines covering all attempt
checks, and at most one independently validated correction only with explicit
operator/user opt-in and sufficient headroom. Synthetic tests verify auth/CSRF,
logout, faults and cancellation with no repair or fallback. The production
resource/live-health guard is deliberately unwired, so even new catalogue/selection
configuration cannot enable live inference. At that milestone tasks 7.5–7.6 and
production model qualification remained outstanding. No deployment is enabled. See
[deterministic factual reviews](../../../docs/decisions/deterministic-factual-reviews.md)
and [separate guarded interpretation](../../../docs/decisions/guarded-review-interpretation.md).

Task 7.4 now adds durable editable presets, strict authenticated versioned
configuration, semantic transactional import deltas and explicit-zone routing.
Synthetic regressions cover disabled defaults, unchanged imports, calendar DST/
travel/rollback, correction reconciliation, stable scoped intent IDs, preserved
future changes/sleep arrivals, per-period counts and unavailable/ambiguous steps.
Schema 2 preserves imported records/events; restore validation supports both
complete schemas 1 and 2. Configuration persists but restart/model changes require
explicit re-arming. No production source step capability or inference guard is
verified/wired, and no database/helper is migrated live. At that milestone the
queue receiver was absent and due intents remained previewable without consumption;
the subsequent queue/UI work below now implements tasks 7.5/7.6 locally. See
[presets and scheduler](../../../docs/decisions/review-presets-and-scheduler.md).

The complete fresh Qwen3.5 suite answered 5/10 requested focuses on first pass and
finally; all 15 responses were structurally valid, but five corrections repeated
irrelevant choices. Both sparse gates and final controls/cleanup passed. This
completes experimental evidence capture, not model qualification. Earlier partial
captures remain separate; no extra run or broader cap is authorized.

Task 7.2's initial pair left Qwen3.5 inference untested: Qwen3 completed eight calls with
4/6 relevant selections both before and after two corrections; Qwen3.5 staging
failed during model pull before any inference. Both captures were independently
replayed and owned cleanup/live-state checks passed. Qwen3.5 has no quality score
from this attempt, and its underlying staging error remains unknown because the
helper withheld HTTP details. No automatic retry, larger cap or live rollout is
authorized by recording these outcomes.

The explicitly approved Qwen3.5 diagnostic rerun then staged successfully and
completed seven calls: 5/6 relevant cases on first pass and after one ineffective
correction. Identical prepared packets and frozen task matched Qwen3's 4/6 run.
Independent original-byte replay, both sparse gates, controls/log/history/unload,
resource/live-health and owned cleanup checks passed. The model again reached
5120 MiB lifetime cgroup peak but had no observed OOM or restart. Task 7.2 is now
complete as an experimental procedure, not qualification of production AI or
meaningful coaching. Earlier staging failure remains preserved and unexplained;
no additional rerun, cap/hardware change or rollout is authorized.

The user-approved Kotlin comparison validator, switchable local interface,
authenticated explicit generation, and model-status UI are implemented with
synthetic backend/adapter/API/UI tests. Task 4.7's unavailable reasons and
independent chart/evidence behavior are verified. Tasks 4.4, 4.6, and 4.8 remain
unchecked pending verification of the exact app packet/prompt with the local
model and operator instructions, not just fake-provider tests or the different
benchmark prompt. No default or inference-service rollout is approved by this
prototype. See [guarded local analysis](../../../docs/decisions/guarded-local-analysis.md)
for scope, resource limits, and the still-required synthetic Pi integration
qualification. Hosted consent and actual-data end-to-end verification remain
outstanding; events were unfinished at that prototype milestone and are now
implemented locally. No requirements are waived.

The separately approved exact published-app synthetic Pi run completed ten app
requests: eight real Ministral responses were rejected whole (0/8 accepted), and
two sparse inputs made no model call. Independent retained-response replay agreed.
The final runner log-decoding failure prevented log-scan completion and sustained
telemetry export; those checks and accepted hardware rendering remain unverified.
Tasks 4.4, 4.6, and 4.8 remain unchecked. No requirement is narrowed to declare
the integration complete. See [actual app test](../../../docs/decisions/guarded-app-ministral-test.md).

The subsequent user-approved Granite 4.2 3B run finished with exported controls,
87 resource samples, unchanged history, log-marker checks, responsive concurrent
synthetic trend/input API reads, and verified cleanup. It still accepted 0/8
real responses: invalid single-sport mixes and incomplete evidence selections
failed whole-response validation. Tasks 4.4/4.6/4.8 remain unchecked; accepted
hardware rendering, broader model switching/quality, and production qualification
are not replaced with a narrower success criterion. See
[Granite app test](../../../docs/decisions/guarded-app-granite42-test.md).

### Local queue, event and dashboard continuation

Tasks **5.1–5.3/7.5/7.6** are now implemented and synthetically verified locally;
progress is **28/33**. The dashboard has a white background, more colourful rounded
category tiles with white text, neutral surrounding text/orange accents, active-section
tabs, viewport-adaptive card pages and lossless
metric/factual/prototype/event evidence paging. Colours do not encode health or
readiness. Factual metrics stay independently usable through optional-model faults.
Review controls expose versioned explicit saves, separate model consent, unsupported
steps, configured zones, conditional checks, unknown arrivals, queue eligibility,
running state and stale completed-coverage metadata. Historical stored prose is
withheld in the metadata view rather than accepted without independent support.

Manual events use the existing SQLite table, bounded strict authenticated/CSRF CRUD,
preserved text and next-upcoming/ongoing UTC date classification. Reopen, edits,
deletion, multi-day trips, ordering, capacity and unchanged history/scheduler are
verified. Events and notes never enter model packets or automatic workout decisions.

The durable queue shares the scheduler JSON singleton, atomically commits intents
with occurrence/cursor/latch state, preserves busy-slot receipts/capacity, fixes
snapshots at execution start, avoids repeated import cancellation, prevents obsolete
publication and recovers without automatic replay. Independent review findings were
fixed and **35 queue regressions** passed; the full backend build has **215 passing
tests**, frontend build **225**, benchmark suite **121** and utility suite **48**.
Palette regressions verify a white page, neutral/orange surrounding text, white
tile labels and at least 4.5:1 normal-text contrast on every category tile.
Strict specs, relative documentation links and diff checks pass. The dashboard
guide was verified against the running built frontend, using isolated browser-only
synthetic responses for sign-in, stepped multi-day event creation, next-event
classification and off/guarded review controls. API/storage tests independently
verify the real authenticated backend routes and persistence. Browser smoke uses
no actual records, model calls or production credentials; it is not actual-data/Pi
release or model qualification. See [dashboard guide](../../../docs/dashboard.md) and
[scheduler/queue/operator details](../../../docs/decisions/review-presets-and-scheduler.md).
The user subsequently approved committing/pushing this tested source for PR review
only. Nothing is promoted, deployed, migrated live or used for real inference.
Tasks 4.4/4.5/4.6/4.8/6.1 retain their original acceptance
requirements and remain unfinished.

### Pending local qualification planning

Source review was committed as `99b527a96458946255a767fe3fde22d3cbe2fe0e`
and pushed to [PR #21](https://github.com/blondacz/gtrainer/pull/21).
CI run **37073228601** passed tests/build and release provenance; image publication
and promotion were skipped. PR auto-merge is not enabled. PR #20 remains separate
and untouched. Synthetic browser checks of the committed build verify overview,
review request and paged preset identity/coverage views at 1000×700 and 390×844;
390×575 preserves accessible review-control scrolling without clipping or
horizontal overflow. Tile text is white; the page remains white with neutral text.

The user chose **local qualification planning** and approved recording the pending
plan in [design.md](design.md#pending-local-model-qualification-plan). Start with
the usefulness gate against code-owned deterministic selection; a focus-selection
pass is not proof of useful health reasoning. The proposed, not authorized,
exact-app trial freezes twenty fresh synthetic cases, the separate app contract,
pinned candidate, rubric and guards; it permits no more than twenty first-pass
calls, no corrections, unchanged three-core/5 GiB limits and the existing
120/130-second app defaults. All twenty responses must pass structural/relevance
checks for the sample gate; original attempts and failures remain independently
replayable. Production guard, sustained operation, private networking, compatible
restore/rollback and actual-data release verification remain separate gates.

This planning approval does **not** authorize runner implementation, a model run,
model/provider selection, health-data transfer, merging, image publication,
deployment or live migration. No experiment occurred. Progress remains **28/33**;
tasks 4.4/4.5/4.6/4.8/6.1 stay unchecked with their original acceptance criteria.
