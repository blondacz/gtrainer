# Design

## Context

See proposal.md for motivation. This greenfield repository has no app code. The user has a Raspberry Pi 5 (8 GB RAM) running K3s and has linked Garmin Connect to Intervals.icu. A read-only check of the last 90 days showed wellness and activity field names but did not verify each field's values or original source. The phase-one requirements in `specs/` cover imported history, descriptive trends, model-assisted cross-metric observations, and manually entered events. Google Calendar and personalized planning are separate later phases.

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
4. **Use a replaceable model boundary for descriptive synthesis.** Benchmark small Ollama models on the Pi 5; if local inference is usable, prefer it as the default but do not bind the app to Ollama or to one model. Allow another configured local model, or a hosted provider only after clearly describing the transfer and receiving the user's explicit consent. No silent fallback from local to hosted. Give models only necessary validated aggregates and date ranges, and verify output against those inputs before showing it. Keep deterministic charts working if inference fails; never treat the model as a source for proprietary scores, causation, diagnosis, or a training prescription. Do not log prompts, health payloads, or keys. If no practical local model exists, ask the user to choose a supported alternative rather than silently sending data out.
5. **Use Kotlin/Ktor and React/TypeScript.** Kotlin matches the user's JVM experience; Ktor keeps the backend small on the Pi, while React/TypeScript handles charts and the event UI. Read Intervals.icu over its documented API in a replaceable adapter; do not introduce a Python Garmin-login worker in this phase. Prefer a simple local persistent store for this single-user release and choose its backup/restore method after checking the cluster. A larger JVM framework and a separate database service are unnecessary defaults for now.
6. **Automate private K3s deployment with Flux.** Check the cluster and Git host first, then bootstrap/configure Flux to reconcile version-pinned manifests from a repository. CI builds and tests the Ktor and React app, publishes a Linux ARM64 image to a registry, and updates the pinned image reference in the deployment source after tests pass; Flux pulls and applies the change on the Pi. CI must not receive cluster credentials or open a path to the home network. Use an appropriate protected promotion path for releases and encrypt any Git-stored secrets with SOPS/age (or provision them outside Git); never store decryption keys in the repository. Keep storage persistent, dashboard access authenticated and LAN-only, and model resources bounded. Argo CD is not needed for this single app.
7. **Leave clean boundaries for later phases.** Phase 2 will add Google events and optional writes to a configurable training calendar with configurable approval, including user-confirmed commitment and travel constraints. Phase 3 will plan for overlapping sport goals, actual activity, recovery, calendar commitments, travel, and stated abilities as described below; a vetted and user-amendable sport knowledge base can ask targeted questions. Uploaded photos/videos can be stored for review without AI analysis. Future planning may reuse the model boundary without coupling it to a provider. More users might justify applying for Garmin's business API and a different deployment and privacy model, but are not planned for now. Neither later phase is an acceptance criterion of this change.

## Later-phase planning requirements (not implemented in phase one)

### Phase 2: Calendar commitments and travel context

- Keep planned training, recorded activities, external commitments, and blocked time distinct. Retain external calendar identity, recurring occurrence identity, and time-zone context so changes or cancellations can be reconciled without duplicate events or shifts to the wrong local day.
- Let the user identify fixed and adjustable aspects of an event: time, duration, location, equipment, and intended effort. Support recurring weekly commitments and one-off calendar events. Do not assume that a calendar title alone establishes these constraints or permission to move an event.
- Represent conditional activities separately from confirmed participation or cancellation. Weather-dependent sessions can remain tentative until their status is confirmed by the user or an explicitly integrated source; unknown conditions must not be treated as suitable conditions. A weather integration is not a phase-one requirement, and weather information is not AI sport-safety clearance.
- Allow date-bounded travel context with destination, time zone, availability, and user-confirmed equipment or facilities. A trip can change which activities are feasible without blocking all exercise. Do not infer destination access or equipment solely from the trip title.
- Keep externally managed or group commitments read-only from the planner's perspective. Optional approved calendar writes target app-managed training entries, not other people's events. Manual events and user-confirmed constraints remain usable without Google Calendar.

### Phase 3: Adaptive short-term planning

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

These requirements are roadmap commitments for separate phase-2/3 changes. Their executable specs and implementation tasks will be authored in those changes; the current phase-one specs remain descriptive and its task list does not include planning or Google Calendar work.

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
- [The Pi 5's 8 GB may not run a useful local model promptly] → Benchmark candidate models on actual hardware; keep charts usable and seek a user choice if local inference is impractical.
- [AI may invent values or claim one trend caused another] → Limit inputs to traceable summaries, validate output against them, mark it as AI analysis, and reject unsupported claims.
- [A hosted model may expose personal health data] → Require explicit provider choice and informed consent before transfer; never silently fall back or log prompts.
- [JVM plus a local model may exhaust the Pi's memory] → Check real container memory limits and model performance; keep charts available without inference.
- [Automation may ship an untested or wrong-architecture image] → Gate promotion on tests and an ARM64 image check; pin an immutable image reference and verify cluster rollout.
- [Flux could accidentally deploy a public ingress or leak secrets through Git] → Review LAN-only routing in CI, encrypt or externally provision secrets, keep decryption keys off Git, and test access from outside the LAN.
- [Home-network access can still expose private health data] → Use access control, private routing, safe secret storage, backup tests, and no public ingress.

## Migration Plan

No existing application or user data needs migration. Build and test with synthetic Intervals.icu-shaped samples first; keep real data and keys out of fixtures and logs. Bootstrap Flux and storage before the first release; verify the first deployment stays private. Back up the phase-one record and event store before upgrades and test restore. For a bad release, revert the Git-pinned image reference and let Flux reconcile; for a storage/schema issue, restore a verified backup with a compatible app version. Later Google Calendar integration must preserve manual event IDs and avoid duplicate imported events; it must not silently overwrite manually entered goals.
