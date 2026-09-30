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
- [ ] 2.3 Bootstrap Flux in the existing K3s cluster and add version-pinned, private LAN-only deployment manifests with persistent storage and health checks; verify Flux reports a healthy rollout and no public access exists.
- [ ] 2.4 Configure protected automated image promotion after passing tests so Flux pulls and applies an immutable new image reference; verify a test release appears on K3s without giving CI cluster credentials.
- [ ] 2.5 Add single-user access control and provision the Intervals.icu API key and any model credentials outside Git or encrypt them with SOPS/age while keeping keys off Git; verify unauthenticated requests fail and CI/logs/manifests expose no raw secrets.
- [ ] 2.6 Document Flux bootstrap, image promotion, install, upgrade, backup, restore, rollback, and removal on the Pi; verify a test backup restore and a Git image-revert rollback both work.

## 3. Collect Garmin-derived history from Intervals.icu

- [ ] 3.1 Implement a read-only Intervals.icu activities/wellness adapter using a rotatable API key and a replaceable source interface; verify API auth failure, revoked-key handling, no upstream writes, and category tests using synthetic fixtures.
- [ ] 3.2 Add normalized activity and wellness records with Intervals.icu IDs, upstream source when known, sport, units, and timestamp context; verify synthetic examples preserve absent values and time zones without committing real records.
- [ ] 3.3 Validate input, prevent duplicate imports, report per-category Intervals.icu fetch outcomes and unknown upstream freshness separately, and allow local data removal; verify invalid/repeated records, API failures, missing recent data, and deletion with tests.
- [ ] 3.4 Document verified field coverage, source limitations, intermediary privacy, key rotation, and missing proprietary Garmin scores; verify docs match tests and do not disclose credentials or personal values.

## 4. Show history and connect the trends with AI

- [ ] 4.1 Compute activity-by-sport, recorded-activity-time, and calorie trends over user-selected periods; verify totals and period comparisons with known test records and label recorded time distinctly from Garmin intensity minutes.
- [ ] 4.2 Show verified populated weight, VO2 max, HRV, and sleep values with source, dates, and units; verify fitness age, endurance score, Garmin training status, and any other unavailable field show 'unavailable', while Intervals.icu load values are named separately.
- [ ] 4.3 Create a reproducible, source-linked summary of validated metrics and period comparisons for AI input; verify known samples contain dates, units, provenance, and missing/stale-data flags without invented scores.
- [ ] 4.4 Add a switchable model interface with a user-selected local model option; verify selected local inference receives only the needed summarized data and changing models leaves stored Garmin records untouched.
- [ ] 4.5 Add an explicitly selected hosted-model option behind informed user consent, with no automatic fallback or sensitive prompt/key logging; verify no personal data leaves the device without consent and log tests find no sensitive values.
- [ ] 4.6 Generate cross-metric observations from the selected model and validate each personal claim against supplied metrics and time ranges; verify tests reject invented Garmin values, unsupported causal claims, diagnoses, and workout prescriptions.
- [ ] 4.7 Keep charts usable when no model works or data is too sparse and report why AI analysis is unavailable; verify outage, insufficient-input, and unusable-output tests.
- [ ] 4.8 Document available metrics, model selection, local resource needs, hosted-data consent, and analysis limits; verify instructions and sample observations match tested behavior.

## 5. Add manual events and dashboard

- [ ] 5.1 Add local events with start/end dates, sport, goal description, and optional current-state notes; verify create, edit, delete, multi-day trips, and date sorting with tests.
- [ ] 5.2 Show the next event, trends, missing-metric labels, latest Intervals.icu read and uncertain upstream freshness, model selection/status, and separately labeled AI observations with supporting metrics/dates; verify empty, stale, API-failure, model-failure, and unauthenticated states with UI tests.
- [ ] 5.3 Document manual events and how to request/review AI trend observations without Google Calendar or workout scheduling; verify the instructions against the running app.

## 6. Check the whole phase-one flow

- [ ] 6.1 Verify a tested ARM64 release reaches the Pi through CI and Flux and that a read-only Intervals.icu import feeds historical charts and grounded AI cross-metric observations while a manually entered trip appears as the next event; confirm no non-local model transfer without consent and that dashboard data stays private.
