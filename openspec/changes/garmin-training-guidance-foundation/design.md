# Design

## Context

See proposal.md for motivation and the agreed cumulative roadmap. The Kotlin/Ktor, React and SQLite foundation implements imports, factual reviews, manual events, review controls and a durable scheduler/queue alongside guarded model experiments. The target Pi 5 has 8 GB RAM and runs K3s. No local model is production-qualified; the fresh Qwen3.5 prepared-focus benchmark remained 5/10 relevant. The current change's acceptance remains descriptive. Richer connected reviews with persistent context are the next increment; goal-based planning follows without requiring calendar integration first. Do not expand scheduler or queue capability until the insights increment demonstrates value; current behavior remains opt-in and unchanged.

## Goals / Non-Goals

**Goals:**
- Separate Garmin retrieval, record processing, factual comparisons, AI synthesis, and the private dashboard.
- Keep one person's data and events on the home K3s cluster.
- Verify Intervals.icu record coverage and origin before relying on its API as the initial, replaceable connector.
- Build and deploy tested ARM64 releases through Git and Flux without exposing K3s credentials to CI.

**Non-Goals:**
- Google Calendar integration, training plans, automatic changes, knowledge-base questions, photo/video review, or multi-user access in phase one.
- Change the Garmin account or provide medical or whitewater-safety judgments.

## Decisions

1. **Use Intervals.icu as the preferred source, not as a claim of Garmin parity.** The user already linked Garmin Connect there. Its documented API can read activities and wellness with a personal API key; the user's field-name-only sample showed `type`, durations, `calories`, `weight`, `hrv`, sleep fields, and `vo2max`. It did not show fitness age, endurance score, or Garmin training status. Before integration, check non-null coverage, dates, source/origin, and whether the scale values really sync; never store real samples or credentials in the repository. Authenticate read-only, keep the key out of Git/logs/AI input, and let the user replace it if rotated. Prefer this over a direct unofficial Garmin login because it avoids handling Garmin credentials; user exports or other approved sources remain fallback adapters. Garmin's official program is business-only and, per a 2026 Garmin forum response, new access requests are temporarily paused; do not make it a prerequisite. Intervals.icu remains an additional external holder of the user's data.
2. **Store source-aware historical records.** Keep activity and wellness records separate from raw API transport and record the Intervals.icu ID, upstream origin when exposed, units, sport, time context, and fetch status. Make repeated sync idempotent and allow local deletion. Compute reproducible aggregates and period comparisons from validated records before sending a small, structured summary to a model; keep recorded values and provenance as the authority. Calculate time spent in recorded activities but never call it Garmin intensity minutes. Name `atl`/`ctl` and similar measures as Intervals.icu calculations, not Garmin scores. Show missing proprietary metrics as unavailable.
3. **Keep manual events independent of Garmin data.** Store each event's start/end, sport, description of success, and optional current-state notes. Sort by start date for the next-event display. Do not automatically score readiness or prioritize events. This app-owned event data can later be mapped to Google Calendar, but phase one uses no Google credentials.
4. **Use an explicitly switchable model boundary.** Local Ollama and hosted providers are both in scope as options; prefer local where useful, but select no automatic default. Start with one selected provider per task and compare common synthetic cases before introducing optional small/large-model routing. Hosted personal-data transfer requires disclosure and explicit consent; no silent escalation or fallback. Supply only the necessary evidence/context and keep factual charts independent. In this descriptive foundation, do not generate training prescriptions or medical/sport-safety judgments. Richer contracts need claim-level grounding and usefulness evaluation, not merely valid IDs or JSON. Do not log sensitive prompts, health payloads or keys.
5. **Use Kotlin/Ktor and React/TypeScript.** Kotlin matches the user's JVM experience; Ktor keeps the backend small on the Pi, while React/TypeScript handles charts and the event UI. Read Intervals.icu over its documented API in a replaceable adapter; do not introduce a Python Garmin-login worker in this phase. Prefer a simple local persistent store for this single-user release and choose its backup/restore method after checking the cluster. A larger JVM framework and a separate database service are unnecessary defaults for now.
6. **Automate private K3s deployment with Flux.** Check the cluster and Git host first, then bootstrap/configure Flux to reconcile version-pinned manifests from a repository. CI builds and tests the Ktor and React app, publishes a Linux ARM64 image to a registry, and updates the pinned image reference in the deployment source after tests pass; Flux pulls and applies the change on the Pi. CI must not receive cluster credentials or open a path to the home network. Use an appropriate protected promotion path for releases and encrypt any Git-stored secrets with SOPS/age (or provision them outside Git); never store decryption keys in the repository. Keep storage persistent, dashboard access authenticated and LAN-only, and model resources bounded. Argo CD is not needed for this single app.
7. **Extend the foundation through small cumulative phases.** Follow the proposal's connected-insights/context, long-term goals, adaptive weekly planning and knowledge-backed coaching sequence. Manual commitments suffice initially; calendar integration is optional. Preserve the existing implementation and capture future planning constraints below without adding every phase to the foundation task list. Develop only the next increment's executable specs/tasks when it is proposed. Photo/video analysis and multi-model routing need demonstrated value before added infrastructure.
8. **Guarantee factual coverage before inference.** Code groups every required comparison by compatible time range, keeping unavailable comparisons explicit. These facts can be rendered without a model. Optional model work is separately bounded, initially selecting a relevant descriptive focus from code-prepared supported relationships; it is not asked to repeat every fact. Reject an invalid whole response before rendering its interpretation. A correction attempt receives only bounded validator feedback and the same immutable input; it is a new independently validated response, never patched fragments. Closed-vocabulary focus selection tests instruction following/relevance, not unrestricted sports interpretation or coaching.
9. **Use editable review presets rather than a rules-language UI.** Type means what is examined, scope means the period/activity, level means interim or thorough, and trigger means why/when it runs. Start with daily-combined, after-activity, and weekly presets, all switchable and assignable to supported triggers. Code owns routing and scheduling, not the model. Suggested configurable rules include a two-hour changed-data check, settled sleep/wellness arrival, settled new activity, daily/weekly local-time reviews, and optional record-count/daily-step thresholds. There is no fixed morning hour. Daily steps require verified source support; threshold crossings occur once per configured period and duplicate imports count as no change. Imports refer to newly received/corrected records, not proof of the physiological event's exact time.
10. **Keep a small stable queue.** Run one analysis at a time, with at most one pending job per review type, scope and compatible execution window. New matching requests replace obsolete pending snapshots and merge reasons; a higher level upgrades but never downgrades matching work. Resolve the newest snapshot when execution begins. Do not let an evening thorough schedule suppress an immediate interim request. Different scopes remain separate. Debounce import bursts, enforce a configurable cooldown and maximum deferral, and do not cancel running jobs repeatedly. New data may queue a follow-up; completed old-snapshot output remains dated historical output, not current coverage. No external broker or generalized workflow engine is needed initially.
11. **Treat latency as a background budget, not a real-time qualification gate.** The application serves factual data while analysis runs. Time budgets include model loading, checks and at most one corrective retry, with separate bounded per-call and total-job deadlines. A five-minute target and ten-minute maximum are provisional tunable starting points, not agreed fixed limits or changes to the installed prototype's 120/130-second bounds. Correctness and resource safety take priority over speed. All review levels use identical safety validation; thorough is not guaranteed precision. Keep the three-core/5 GiB model cap and stop on OOM, restart or host/live-health guard failure. Test Qwen3 4B Instruct and pinned Qwen3.5 4B using synthetic inputs; any future hardware/cap change needs separate approval and a separately labeled run.
12. **Automatic reviews require explicit enablement.** Default inference and schedules remain off; enabling schedules requires an explicit model/configuration choice and does not authorize hosted health transfer. Disable/pause or model-selection changes invalidate pending work and prevent stale in-flight publication. Future scheduled local selection persistence, if implemented, must be explicit and documented; the current memory-only prototype remains off after restart. Show next scheduled check (conditional on changed data), next due review, pending event conditions, queued reasons/start estimate, running state and last successful snapshot coverage. Do not invent a next event-trigger time or guarantee an exact start behind running work.

## Agreed incremental coaching architecture (2026-10-03)

### Preserve the implementation

Extend the current application and SQLite store. Reuse imports/provenance and
factual calculations as evidence, manual events as the starting point for
event-linked goals, dashboard/evidence paging as presentation, scheduler/queue as
background execution, and authentication/backups/delivery as operations. Existing
tests and original benchmark captures remain regression evidence. Keep the
restricted prototype and focus selector as separately named experimental baselines;
introduce a richer review contract rather than forcing coaching through their
ID-selection validators. No wholesale rewrite, external broker, agent swarm or
separate ML platform is required.

Add domain concepts only when needed: athlete context, goals, plans, sessions,
feedback and revisions. Keep one small phase proposal with concrete acceptance
examples; future phases remain roadmap commitments. The AI/ML learning objective
is served through structured outputs, evaluation, model comparison and retrieval,
not by adding infrastructure without a demonstrated need.

### Durable memory and relevant retrieval

The database is the durable record; prompts contain bounded relevant context.
Store goals, current restrictions, availability, preferences and accepted decisions
as structured records. Explicitly include applicable hard constraints whenever
planning or adapting; semantic search must not decide whether a restriction is
remembered. Store session reflections and coaching feedback as dated text linked
to the relevant session, sport, goal or plan revision.

Each memory retains source/author, observation date, applicability/expiry where
known and correction/supersession history. Distinguish measurements, user reports,
human-coach/clinician guidance and model interpretations. Users can inspect,
correct, retire or delete memories; a summary must not silently turn an inference
into a user fact or resurrect superseded instructions. Derived retrieval indexes
must reflect corrections/removal. Private feedback is not a development fixture
or public documentation input.

Start with SQLite and explicit date/sport/goal filters; use full-text search when
needed. Add embeddings only if retrieval evaluation demonstrates an improvement;
a new vector database is not an initial requirement. Retrieve related experiences
and applicable knowledge without keeping entire conversations in every prompt.
Long-term memory is explicit stored context, not automatic model-weight training.

### Two feedback loops

Within a request, check facts, evidence bindings and applicable plan constraints,
then allow bounded validator feedback and a new independently checked response.
Model self-approval alone is insufficient. Retain first-pass and revised outcomes
separately; never repair rejected fragments into an accepted answer. Existing
contracts keep their current correction policies; the next contract defines its
own explicit attempt/time/cost bounds before execution. Resource, transport or
health failures do not silently trigger retries or provider escalation.

Across sessions and weeks, compare plans with actual activity, user reflections,
accepted/rejected suggestions and reported outcomes. Update the attributed memory
and propose explained plan revisions. Delayed imports remain unknown until
resolved; no automatic catch-up prescription. Initial plan changes require
explicit acceptance, with any later automation policy designed separately.

### Models and meaningful evaluation

Begin with an explicit local/remote provider choice behind the existing model
boundary. Hosted models were not ruled out; learning local inference is a goal,
not a requirement to use a weaker model for every task. Compare selected models
on common synthetic cases for evidence accuracy, useful connections, appropriate
uncertainty/questions, latency, resource use and cost. Evaluate usefulness and
claim quality, not only schema validity, citations or evidence IDs. Existing
conversational hosted reference evidence is not a controlled API comparison, a
matched fresh selector benchmark, or qualification of the richer review contract.
Later planning evaluations add feasibility, goal alignment and adaptation quality.

Optional routing of basic tasks to a small local model and complex reviews/plans
to a stronger model comes only after measurements justify it. Code owns arithmetic,
dates, hard constraints and orchestration; models provide synthesis, questions,
explanations and proposals. Disclose which context is transferred to a hosted
provider and require informed consent. Apply the same disclosure to remote
embedding/reranking services; retrieval does not bypass the transfer boundary.

### Coaching scope and knowledge

Represent present capacity per sport, alongside experience, technique, confidence,
equipment and opportunities. Support competitive goals, seasonal priorities and
maintenance of other sports. Combine strength, skills and prescribed rehabilitation
with endurance work; incorporate clinician-provided restrictions without inventing
medical clearance or changing a rehabilitation prescription. Wellbeing, recovery,
nutrition/weight goals and sustainable participation span all phases. Load metrics
are evidence, not a universal fitness/readiness score across disciplines.

Curated knowledge should retain publisher, date/version, discipline, audience and
applicability, with citations to the retrieved passages. Distinguish personal
coach/physio notes from general guidance. Useful sources informing the roadmap:

- [BCAB educational philosophy](https://britishcanoeingawarding.org.uk/our-educational-philosophy/): participant-led, individualised, enjoyable development.
- [Athlete Development Framework](https://britishcanoeingawarding.org.uk/athlete-development-framework/): technical, physical and psychological development; principles rather than ready-made programmes.
- [Personal Performance Award resources](https://britishcanoeingawarding.org.uk/personal-performance-award-resources/): discipline-specific learning, including SUP and touring.
- [Coaching and leadership logbook](https://britishcanoeingawarding.org.uk/resource/coaching-and-leadership-logbook/): recording experience and development.

These inform our design, not an official endorsement of the app. Do not transfer
Olympic training prescriptions indiscriminately between populations or disciplines.
Selected reference material can support early reviews; systematic RAG follows
incrementally. Photo/video assistance is later work requiring a defined assessment
task, privacy controls and accuracy evaluation, not an assumed competence check.

### Superseded selector-only qualification proposal

The earlier proposed twenty-case, twenty-call Qwen3.5 `review-focus-app-v1` trial
and 20/20 relevance gate were planning only and were never authorized to run.
They are superseded as the next step by connected insights with persistent context
and switchable-model evaluation. The earlier fresh `prepared-focus-v1` result
remains 5/10 relevant with no corrective rescue; retain original captures and
contract boundaries. No selector score alone qualifies richer coaching.

Roadmap approval selects no provider and authorizes no new inference, personal-data
transfer, cap increase or deployment. Existing runtime limits and missing production
qualification guard remain in effect. Runtime health, responsive charts, private
access and compatible backup/restore remain release requirements. The foundation's
unfinished tasks retain their original acceptance criteria.

## Later planning and delivery constraints

These carry into the roadmap's goals/planning phases, not the foundation's current
executable requirements. Calendar integration is optional rather than a phase gate.

### Commitments, delivery and travel context

- Keep history ingestion read-only. Add a separate, explicitly authorized publication path for app-owned planned workouts when delivery is implemented. Investigate [Intervals.icu planned-workout API support](https://www.intervals.icu/features/open-api/) and [Garmin delivery](https://forum.intervals.icu/t/upload-planned-workouts-to-garmin-connect/1521) first; verify supported sports/device behaviour before promising delivery. Preserve external identity and make repeat publication idempotent. Convenient authenticated phone access belongs with daily planning; Google Calendar can follow if useful.

- Keep planned training, recorded activities, external commitments, and blocked time distinct. Retain external calendar identity, recurring occurrence identity, and time-zone context so changes or cancellations can be reconciled without duplicate events or shifts to the wrong local day.
- Let the user identify fixed and adjustable aspects of an event: time, duration, location, equipment, and intended effort. Support recurring weekly commitments and one-off calendar events. Do not assume that a calendar title alone establishes these constraints or permission to move an event.
- Represent conditional activities separately from confirmed participation or cancellation. Weather-dependent sessions can remain tentative until their status is confirmed by the user or an explicitly integrated source; unknown conditions must not be treated as suitable conditions. A weather integration is not a phase-one requirement, and weather information is not AI sport-safety clearance.
- Allow date-bounded travel context with destination, time zone, availability, and user-confirmed equipment or facilities. A trip can change which activities are feasible without blocking all exercise. Do not infer destination access or equipment solely from the trip title.
- Keep externally managed or group commitments read-only from the planner's perspective. Optional approved calendar writes target app-managed training entries, not other people's events. Manual events and user-confirmed constraints remain usable without Google Calendar.

### Adaptive short-term planning

- Reassess upcoming flexible training when new or corrected activity and wellness records are ingested, a planned session is confirmed missed or cancelled, the user reports recovery concerns, or relevant calendar/travel constraints change. Use actual sport, duration, and available effort/load information, including unplanned sessions, rather than assuming the planned workout was completed. Repeated ingestion of unchanged records must not cause duplicate or unnecessary plan revisions.
- Distinguish completed, confirmed missed, cancelled, and not-yet-confirmed sessions. Account for source freshness and incomplete imports before asking the user to confirm an apparently missed session. Missing health measurements are unknown inputs, not evidence of illness or poor recovery.
- Consider available health trends and reported fatigue, illness, or injury when proposing lower load, rest, or other short-term changes, without diagnosis or sport-safety clearance. Do not automatically stack missed workouts onto later days; consider remaining availability, recovery, and multi-sport goals together.
- Treat calendar commitments and availability as scheduling constraints, not flexible workouts to move freely. Adjust only aspects the user has marked adjustable. If a fixed session conflicts with recovery concerns or travel, surface the conflict and suggest reducing permitted effort, declining participation, or another feasible option; do not force participation or silently move the group event.
- Apply location, facilities, equipment, and availability constraints for each affected date, preserving local-day and time-zone meaning during travel. Suggest destination alternatives only when feasibility is known or confirmed; otherwise ask rather than invent access.
- Show each proposed revision as a before/after change with the reason, supporting activity/health/calendar inputs and their dates, and any uncertainty. Apply the configured approval policy before modifying the active plan or writing app-managed calendar entries; never silently overwrite a fixed external commitment.

### Examples to carry into later-phase acceptance scenarios

- **Wednesday club paddling:** Brighton Explores Club paddling recurs at a fixed time, with a specified board and duration, subject to conditions being acceptable as confirmed by the user or club. Schedule flexible sessions around it and adjust effort only if allowed. If cancelled, reconsider the week and suggest feasible alternative training or rest without moving the group session. If health or recovery makes participation questionable, flag the conflict rather than treating the commitment as mandatory.
- **Actual or missed workout:** An unplanned recorded session or a confirmed missed workout triggers a review of the remaining short-term plan. A late Garmin-to-Intervals.icu import must not be interpreted as a confirmed missed workout; a subsequently imported activity can resolve the uncertainty and inform a new proposal.
- **Czech Republic trip:** During the trip's dates, do not schedule Brighton sea paddling or Brighton climbing sessions. Preserve any other feasible commitments and consider destination training only with confirmed access, equipment, time, and recovery context. Restore home-location availability after the trip without automatically adding missed home sessions.

These constraints carry forward into separate incremental changes. Author executable specs and implementation tasks only for the next selected increment; the current foundation specs remain descriptive and its task list does not include planning or Google Calendar work.

## Risks / Trade-offs

**Registry decision update (2026-09-30):** The user approved keeping
`ghcr.io/blondacz/gtrainer` public for now. The image contains only software and
synthetic build/test inputs, never runtime credentials, imported health records,
manual personal events, prompts, or backups. This supersedes the private-image
proposal in the delivery decision note, not the authenticated LAN-only dashboard
requirement. No registry-read credential is needed for public pulls. Any later
private-image requirement needs a new package because public GHCR packages cannot
be made private again.

- [Intervals.icu may not receive or expose every Garmin metric] → Check populated values, dates, and origin per metric; show absent fitness age, endurance score, or training status as unavailable and retain a fallback adapter boundary.
- [Garmin → Intervals.icu → app adds another dependency and data holder] → Disclose the path, use a read-only API key, handle rotation/revocation, and separate API failures from unknown upstream delays.
- [Official Garmin API is unavailable to this personal app for now] → Do not block on approval; keep a user-controlled export path or future replacement source possible.
- [Dates, units, or sport labels may vary] → Preserve original provenance and time context; normalize and test examples before charting.
- [The Pi 5's 8 GB may not run a useful local model within a background budget] → Benchmark candidate models on actual hardware; distinguish semantic failures from timeouts and memory failures, keep factual views usable, and seek an explicit hardware/provider choice rather than silently raising caps or transferring data.
- [AI may invent values or claim one trend caused another] → Limit inputs to traceable summaries, validate output against them, mark it as AI analysis, and reject unsupported claims.
- [A hosted model may expose personal health data] → Require explicit provider choice and informed consent before transfer; never silently fall back or log prompts.
- [JVM plus a local model may exhaust the Pi's memory] → Check real container memory limits and model performance; keep charts available without inference.
- [Automation may ship an untested or wrong-architecture image] → Gate promotion on tests and an ARM64 image check; pin an immutable image reference and verify cluster rollout.
- [Flux could accidentally deploy a public ingress or leak secrets through Git] → Review LAN-only routing in CI, encrypt or externally provision secrets, keep decryption keys off Git, and test access from outside the LAN.
- [Home-network access can still expose private health data] → Use access control, private routing, safe secret storage, backup tests, and no public ingress.

## Migration Plan

Existing imported records and the live release remain unchanged during synthetic experimentation. The separate factual/review/queue implementation is local source, not production AI qualification; future richer contracts must not silently replace the installed validator or prompt. The local schema-2 queue/configuration work preserves records and defines recovery without automatic inference replay. Subsequent memory/goal/plan schema changes must be additive where practical, preserve stable event/history identities and include compatible restore/rollback. Build and test with synthetic samples first; keep real data and keys out of fixtures and logs. Back up the private store before upgrades and test restore. For a bad release, revert the Git-pinned image reference; for a schema issue, restore a verified backup with a compatible version. Future delivery/calendar adapters must preserve identities and never overwrite externally managed commitments or manually entered goals.
