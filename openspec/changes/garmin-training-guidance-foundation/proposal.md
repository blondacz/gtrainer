# Proposal

## Why

The goal is a personal coaching system for an experienced multisport athlete: connect evidence, develop skills, plan toward events and seasonal priorities, adapt to feedback, and support lifelong health. This foundation supplies reliable history and reviews; subsequent increments reuse it, while providing a practical learning project in AI/ML, Ollama, model evaluation and retrieval.

## What Changes

- **Foundation (this change; historically called phase 1):** Prefer the user's already linked Garmin Connect → Intervals.icu account as a read-only source through the Intervals.icu API. Validate actual field values and history coverage before building the adapter; keep the source replaceable and never store an API key in the repository or planning artifacts. Normalize records, preserve provenance, and show missing or stale data honestly.
- Show historical trends and overall health stats. The observed Intervals.icu field names include activity type, duration, calories, weight, HRV, sleep, and VO2 max; verify populated values and origin before using them. Fitness age, endurance score, and Garmin training status were not observed and must remain unavailable unless a later source supplies them. Label time calculated from recorded activities separately from Garmin intensity minutes. Use a selectable AI model to connect trends across available metrics into grounded, non-prescriptive observations; keep the source data visible and do not present correlation as cause.
- Prepare complete factual evidence groups in code before inference, independently of optional model interpretation. Offer editable daily-combined, after-activity, and weekly review presets with assignable time/data triggers, separately configurable scope and interim/thorough level, and longer bounded background execution. Scheduled inference is opt-in; model selection alone does not enable it. Show next scheduled checks/reviews and queued/running status. This is analysis scheduling, not workout scheduling or Google Calendar integration.
- Let the user enter and maintain upcoming events or trips in the app, including sport, dates, goal description, and current ability/context. Show the next event on a private dashboard. No automatic event prioritization.
- Build the Kotlin/Ktor backend and React/TypeScript dashboard as ARM64 containers and automatically deploy tested versions to the existing Pi K3s cluster through Flux, without giving CI direct access to the home cluster. Include safe secret handling and rollback.

## Agreed cumulative roadmap (2026-10-03)

This sequencing supersedes the earlier calendar-first roadmap and selector-only
qualification next step. These are incremental product phases on the existing
foundation, not new acceptance requirements silently added to this change.

1. **Connected insights and persistent context:** richer evidence-grounded reviews
   linking activity, wellness and optional subjective feedback. Distinguish facts,
   interpretations and unanswered questions. Store feedback outside the model
   context and retrieve relevant history. Compare explicitly selected local and
   hosted models on common synthetic cases, with informed consent before any
   personal transfer. Evaluate usefulness as well as factual correctness.
2. **Goals and long-term direction:** editable event/season priorities, per-sport
   current capabilities, milestones and broad training blocks. Balance performance,
   skills, strength, clinician-prescribed rehabilitation, recovery and available
   time. Negotiate priorities rather than treating the nearest event as overriding
   every other goal. Keep distant plans broad and nearer sessions more detailed.
3. **Weekly planning and adaptation:** feasible workouts, intervals, skill practice,
   strength and recovery with purpose and alternatives. Review planned versus
   actual activity and feedback; explain proposed changes before explicit initial
   acceptance. Respect fixed/conditional commitments, travel, equipment and local
   time zones; missing imports are not missed sessions and missed sessions do not
   automatically become catch-up workouts. Provide convenient authenticated phone
   access and investigate one delivery path first: Intervals.icu planned workouts
   to Garmin. Google Calendar remains optional, not a prerequisite for coaching.
4. **Knowledge-backed coaching:** curated, cited, discipline-appropriate retrieval
   for skills, training, strength and nutrition, including separately attributed
   personal coach/physio guidance. Begin with simple retrieval; add embeddings or
   photo/video analysis only when a defined task and evaluation justify them.

Wellbeing, sustainable nutrition/weight goals, enjoyment and long-term health run
through all phases. Support experienced competitors across paddling, endurance,
climbing and seasonal snow sports; do not reduce the product to recreational SUP
or infer current ability from past achievement. Keep personal health details and
actual athlete profiles in private runtime storage, not planning artifacts.

Preserve the existing code and benchmark history. Use the current application and
database, define only the next small phase in detail, and retain future phases as
a short roadmap. Learning AI engineering is a genuine secondary goal, without
requiring a multi-agent system, new services or a vector database upfront.

## Capabilities

### New Capabilities
- `garmin-data-ingestion`: Read supported Garmin-derived activity and wellness data from Intervals.icu first, behind a replaceable source adapter.
- `training-health-data`: Normalize and retain time-aware records, data quality, and provenance for trend analysis.
- `personalized-training-guidance`: Produce code-owned complete factual summaries and optional validated descriptive interpretation, with configurable background review presets, triggers, and a coalescing queue, without prescribing training or making a plan.
- `guidance-dashboard`: Show health/training trends, factual summaries, separately labeled AI observations, model/sync/review schedule status, and a manually managed event calendar in a private view.

### Modified Capabilities

None.

## Impact

This is an existing single-user Kotlin/Ktor and React app with SQLite on the user's Raspberry Pi 5 (8 GB RAM) and K3s cluster. Reuse its imports, facts, events, dashboard, model boundaries, scheduler/queue, authentication, backups and ARM64 delivery pipeline. Local Ollama and explicitly selected hosted models are both possible; provider choice and personal-data consent remain separate from roadmap approval. Foundation acceptance remains descriptive and does not include a planning engine, workout publication or Google Calendar. Intervals.icu remains a separate data holder and its derived load metrics are not Garmin scores. Multi-user support is outside this roadmap.
