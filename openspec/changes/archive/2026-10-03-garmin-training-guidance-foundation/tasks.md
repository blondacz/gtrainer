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

## 4. Show deterministic history and factual evidence

- [x] 4.1 Compute activity-by-sport, recorded-activity-time, and calorie trends over user-selected periods; verify totals and period comparisons with known test records and label recorded time distinctly from Garmin intensity minutes.
- [x] 4.2 Show verified populated weight, VO2 max, HRV, and sleep values with source, dates, and units; verify fitness age, endurance score, Garmin training status, and any other unavailable field show 'unavailable', while Intervals.icu load values are named separately.
- [x] 4.3 Create reproducible, source-linked factual summaries of validated metrics and period comparisons; verify dates, units, provenance, and missing/stale flags without invented scores.
- [x] 4.7 Keep factual charts and summaries usable when experimental interpretation is unavailable or data is too sparse; verify outage, insufficient-input, and unusable-output states.

## 5. Add manual events and dashboard

- [x] 5.1 Add local events with start/end dates, sport, goal description, and optional current-state notes; verify create, edit, delete, multi-day trips, and date sorting with tests.
- [x] 5.2 Show the next event, factual trends, missing-metric labels, latest Intervals.icu read and uncertain upstream freshness, experimental interpretation status, and separately labeled prototype output with supporting evidence; verify empty, stale, API-failure, model-failure, and unauthenticated states with UI tests.
- [x] 5.3 Document manual events, factual summaries, experimental interpretation status, and dashboard limitations without Google Calendar or workout scheduling; verify instructions against the running app.

## 7. Code-prepared reviews and background scheduling

- [x] 7.1 Freeze a synthetic-only code-prepared evidence-group/focus-selection protocol and fixtures with independent validation and no output repair; verify complete factual coverage, missing/unchanged/zero values, incompatible periods, malformed/unsupported outputs, deterministic no-model rendering and bounded corrective-attempt rules with regression tests. This is an experimental benchmark protocol, not production qualification or unrestricted interpretation.
- [x] 7.2 Run the frozen synthetic protocol on Qwen3 4B Instruct and pinned Qwen3.5 4B in isolated owned Pi resources at three cores/5 GiB; retain original attempts, separate first-pass from corrected validity and relevance, replay outputs, record latency/memory/guard failures and verify cleanup/live health. Stop resource failures without retries or cap increases; report inability to finish separately from semantic failure and preserve all earlier benchmark results.
- [x] 7.3 Implement code-owned complete factual groups and a separately validated optional interpretation path in the app; verify evidence/model invalidation, independent factual rendering, whole-response rejection and bounded corrective attempts without silently replacing the installed prototype contract or enabling live inference.
- [x] 7.4 Implement explicitly enabled editable daily-combined, after-activity and weekly presets with assignable supported time/data/count/step triggers, advanced scope/level/budgets and a time-zone-aware scheduler; verify disabled defaults, unchanged imports, once-per-period thresholds, unsupported steps, daylight-saving/travel behavior and no hosted-consent bypass.
- [x] 7.5 Implement the single-running coalescing review queue with merged reasons/highest level, execution-time snapshots, debounce/cooldown/maximum deferral and cancellation/invalidation; verify separate scopes/windows, no evening suppression of immediate reviews, no starvation or repeated running cancellation, crash recovery and stale-result labeling.
- [x] 7.6 Add review preset configuration and next-check/next-review/trigger/queued/running/last-success coverage UI and operator documentation; verify authenticated UI states, unsupported rules, configurable budgets, factual/AI separation and independent chart responsiveness without Google Calendar or plan changes.

## Evidence and remaining work

Foundation scope now has **28/28 tasks complete**. Its deterministic source,
dashboard, events, and review-control work is implemented and synthetically
verified. Deferred model qualification is not marked complete; no live inference,
migration, or deployment is authorized by this record. Historical outcomes remain
in linked evidence.

### Completed work evidence

- Guarded review contracts, frozen experiments, and local-model limitations:
  [code-prepared experiment](../../../docs/decisions/code-prepared-review-experiment.md),
  [guarded local analysis](../../../docs/decisions/guarded-local-analysis.md),
  [actual app test](../../../docs/decisions/guarded-app-ministral-test.md),
  [Granite app test](../../../docs/decisions/guarded-app-granite42-test.md).
- Deterministic facts, guarded interpretation, presets/scheduler/queue:
  [factual reviews](../../../docs/decisions/deterministic-factual-reviews.md),
  [guarded interpretation](../../../docs/decisions/guarded-review-interpretation.md),
  [scheduler and queue](../../../docs/decisions/review-presets-and-scheduler.md).
- Dashboard, events, privacy and operator behavior:
  [dashboard guide](../../../docs/dashboard.md).
- Source merge is recorded in PR [#21](https://github.com/blondacz/gtrainer/pull/21).
  Container-input test fixes are in PR
  [#22](https://github.com/blondacz/gtrainer/pull/22); its CI passed. This is not
  image publication, promotion, or Pi deployment evidence.

### Transferred acceptance criteria

Tasks **4.4, 4.5, 4.6, and 4.8** move without waiver to
`connected-insights-context`. Task **6.1** moves without waiver to
`foundation-release-verification`. The successor tasks must preserve the original
acceptance intent and distinguish implementation tests from production qualification.

### Agreed next increment

Insights and persistent context remain first: a richer evidence-grounded review,
durable attributed feedback/context, and explicit local/hosted comparison on common
synthetic cases, assessing usefulness as well as correctness. Freeze scheduler/queue
expansion until that increment demonstrates value. See the
[proposal roadmap](proposal.md#agreed-cumulative-roadmap-2026-10-03) and
[design](design.md#agreed-incremental-coaching-architecture-2026-10-03). Planning
follows; no selector-only benchmark, new inference, personal-data transfer, or
provider choice is authorized by this roadmap.
