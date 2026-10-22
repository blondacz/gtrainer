# Proposal

## Why

Reviewing training currently requires assembling screenshots and repeatedly explaining symptoms, recovery context and calendar constraints. The application should assemble authorized evidence and reusable context itself, provide automatic morning/evening reviews, and propose justified amendments to an existing plan without silently changing it.

## What Changes

- Support two selection modes: identified activities (one or a set), and a period (selected day/night, calendar week/month/year, rolling **7/30/365 days**, or custom historical range). Review and compare are distinct intents: selected-session comparison compares the chosen activities, while period comparison uses explicitly resolved periods. Resolve dates once; distinguish reporting windows, personal baselines and future planning horizons.
- Assemble bounded, inspectable evidence from imported activity/wellness, confirmed personal context, commitments and the selected existing plan. Activity wellness is optional, with display-only context distinct from inclusion in assessment; period reviews include available wellness by default. Associated preceding sleep, day HRV and source-attributed training readiness require verified available fields/semantics; readiness is never reconstructed. Expose available session details rather than relying only on period aggregates; unsupported source fields remain unavailable.
- Reuse one attributed context store. Enter notes once; confirm proposed structured interpretations before reuse as constraints or calendar commitments. Distinguish applicability, review due and explicit resolution; stale injury/illness context never implies recovery.
- Add automatic morning briefing and evening reflection after explicit enrollment, model selection and qualification/runtime admission. Reuse the personal scheduler/queue; hosted execution still waits for exact-packet consent. Other scopes remain on demand or separately scheduled.
- Store a versioned user/coach-authored base plan locally, with allowed amendment bounds. External plan/calendar reads require verified adapter capabilities; local entry remains available without an external integration.
- Detect confirmed skipped sessions, extra activity, supported planned-versus-actual deviations and configured personal sleep/HRV signals in code. Thresholds request review, not mandatory amendment or medical conclusions.
- Produce separately versioned, bounded amendment proposals; require authenticated approval and revalidation before applying a new local plan revision. Initially support moving, reducing duration or omitting an existing future session within explicit plan bounds, not new workouts or increased intensity/volume.
- Expose scoped reviews through one canonical factual preparation API, shared controls/evidence cards in Trends and Reviews, and the same client/parser for overview numeric tiles. Keep inference and existing guarded execution separate; scoped preparation never invokes inference.
- Use the current history schema as the sole fresh-database schema. Do not promise HTTP compatibility aliases or a historical database migration chain. Preserve frozen model packets, prompts, validators, qualification fixtures/captures and ledger as historical profile evidence, not as permanent runtime interfaces.
- Include current-schema validation/refusal behavior and read-only backup-restore validation in the implementation plan; no actual restore, database deletion or live operation is authorized.
- Introduce realistic synthetic longitudinal evaluation for the new profiles. Preserve frozen qualification evidence and incomplete qualification work unchanged.

## Capabilities

### New Capabilities

- `review-time-scopes`: Shared selection resolution, selected-session versus period comparison semantics, calendar/rolling comparisons and separation of evidence from future horizons.
- `review-evidence`: Mode-aware bounded evidence assembly, optional activity wellness/default period wellness, source capability disclosure, user questions and immutable review bindings.
- `athlete-context`: Extend the pending capability with single-entry confirmation, shared projections and explicit unresolved/review-due lifecycle. This path is introduced by `connected-insights-context`; these additional requirements depend on that change and MUST NOT replace its requirements.
- `training-plan-adaptation`: Existing-plan revisions, reconciliation, configurable deterministic review signals, bounded proposals and approval-gated local amendments.

### Modified Capabilities

- `personalized-training-guidance`: Add new scoped review profiles and morning/evening purposes while keeping legacy reviews descriptive and all execution gated.
- `guidance-dashboard`: Add familiar scope selection, shared-context intake, review status and inspectable amendment approval.
- `training-health-data`: Retain compatible measurement identity and coverage needed for personal baseline rules without inventing unsupported semantics.

## Impact

Targeted changes to Kotlin evidence preparation, SQLite repositories, existing scheduling/queue adapters, provider contracts, authenticated APIs and React controls. The canonical factual routes are `GET /api/reviews/options` and `POST /api/reviews/prepare` (mode, intent, wellness policy and explicit fields; response includes prepared facts, charts and provenance). Retire `/api/trends`, `/api/analysis-input`, `/api/review-facts`, `/api/scoped-trends/v1`, old prototype `/api/models` and `/api/analysis`, and orphan manual `/api/review-interpretation`; retain `/api/review-now` and existing qualified, guarded execution. No aliases or compatibility adapters. No new service, broker, vector database or mandatory dependency. Shared pure helpers replace duplicated date and applicability logic; development execution remains isolated.

## Non-goals and dependencies

- No initial plan generation, long-term goal-based planner, unrestricted coaching, rehabilitation progression, medical diagnosis or safety clearance. Reviews may inform those later capabilities but do not implement them.
- No external write-back, automatic amendment application, screenshot/OCR pipeline, comprehensive activity-stream import, or implicit conversational memory.
- Depends on the implemented context/snapshot foundation; its pending spec synchronization and qualification remain separate. Synchronize that capability before this follow-on's deltas; do not mark its remaining tasks complete.
- Artifact creation authorizes no implementation, inference, downloads, personal-data transfer, live-source probing, runtime acceptance test, migration, publication or deployment. Existing production gates stay closed.
