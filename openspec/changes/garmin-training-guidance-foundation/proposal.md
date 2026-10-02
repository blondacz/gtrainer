# Proposal

## Why

The user wants to stay active and healthy while balancing trips, races, and several sports. Before building an adaptive coach, they need a clear view of Garmin history, overall health trends, and upcoming events in one place.

## What Changes

- **Phase 1 (this change):** Prefer the user's already linked Garmin Connect → Intervals.icu account as a read-only source through the Intervals.icu API. Validate actual field values and history coverage before building the adapter; keep the source replaceable and never store an API key in the repository or planning artifacts. Normalize records, preserve provenance, and show missing or stale data honestly.
- Show historical trends and overall health stats. The observed Intervals.icu field names include activity type, duration, calories, weight, HRV, sleep, and VO2 max; verify populated values and origin before using them. Fitness age, endurance score, and Garmin training status were not observed and must remain unavailable unless a later source supplies them. Label time calculated from recorded activities separately from Garmin intensity minutes. Use a selectable AI model to connect trends across available metrics into grounded, non-prescriptive observations; keep the source data visible and do not present correlation as cause.
- Prepare complete factual evidence groups in code before inference, independently of optional model interpretation. Offer editable daily-combined, after-activity, and weekly review presets with assignable time/data triggers, separately configurable scope and interim/thorough level, and longer bounded background execution. Scheduled inference is opt-in; model selection alone does not enable it. Show next scheduled checks/reviews and queued/running status. This is analysis scheduling, not workout scheduling or Google Calendar integration.
- Let the user enter and maintain upcoming events or trips in the app, including sport, dates, goal description, and current ability/context. Show the next event on a private dashboard. No automatic event prioritization.
- Build the Kotlin/Ktor backend and React/TypeScript dashboard as ARM64 containers and automatically deploy tested versions to the existing Pi K3s cluster through Flux, without giving CI direct access to the home cluster. Include safe secret handling and rollback.
- **Phase 2 (later change):** Read events from Google Calendar and optionally write training entries to a user-chosen calendar. Calendar write access and approval behavior will be configurable. Represent one-off and recurring commitments, blocked time, conditional activities, and date-specific travel context; let the user confirm which event constraints are fixed or adjustable. Preserve calendar time zones and external event identity, and do not move or overwrite other people's or externally managed commitments.
- **Phase 3 (later change):** Build long-range, multi-sport goals and adaptive short-term plans, with configurable approval of changes. Reassess upcoming training as recorded activities and wellness data are ingested, including unplanned activities, confirmed missed sessions, and reported fatigue, illness, or injury; also reassess after relevant calendar or travel changes. Missing or delayed records are not proof that an activity was missed, and missed sessions must not automatically become catch-up workouts. Fit flexible training around fixed commitments and the user's current location, equipment, facilities, and availability. Respect conditional club sessions, such as Wednesday paddling with Brighton Explores Club at a fixed time, on a specified board and for a given duration if conditions permit; adjust only permitted aspects, and suggest an alternative or rest if cancelled rather than moving the group session. During a trip to the Czech Republic, exclude Brighton-based sessions and consider only activities feasible at the destination. Fixed commitments do not override recovery concerns or become compulsory participation. Explain each proposed revision and its activity, health, or calendar evidence before applying the configured approval policy. Use vetted sport knowledge sources, editable user notes, prompted skill check-ins, and optional photo/video attachments; AI video analysis is not needed initially. Reuse the phase-one model boundary for planning without locking the product to one model. These later-phase requirements do not add a planning engine, calendar integration, or weather service to phase one and do not authorize medical or sport-safety judgments.

## Capabilities

### New Capabilities
- `garmin-data-ingestion`: Read supported Garmin-derived activity and wellness data from Intervals.icu first, behind a replaceable source adapter.
- `training-health-data`: Normalize and retain time-aware records, data quality, and provenance for trend analysis.
- `personalized-training-guidance`: Produce code-owned complete factual summaries and optional validated descriptive interpretation, with configurable background review presets, triggers, and a coalescing queue, without prescribing training or making a plan.
- `guidance-dashboard`: Show health/training trends, factual summaries, separately labeled AI observations, model/sync/review schedule status, and a manually managed event calendar in a private view.

### Modified Capabilities

None.

## Impact

This is a greenfield single-user app for the user's Raspberry Pi 5 (8 GB RAM) and existing K3s cluster. Phase 1 needs an Intervals.icu API adapter, local persistent storage, access control, a private dashboard, a replaceable model integration, and an ARM64 build/Flux deployment pipeline. Try local Ollama if usable on this Pi, allow other selected models, and require explicit consent before sending health data to a hosted provider. It does not need Google Calendar, a planning engine, workout scheduling, or notifications. Intervals.icu is a separate service through which this user's Garmin data passes; never present its derived training-load metrics as Garmin scores. Supporting other users is a possible later decision if this personal app proves useful; do not implement multi-user support now.
