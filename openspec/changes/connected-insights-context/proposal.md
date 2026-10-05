# Proposal

## Why

The foundation now provides private, source-aware facts and experimental review controls, but it cannot retain attributed subjective context or produce useful connected insights. This increment adds a user-controlled context loop and evaluates a richer review contract before any production qualification.

## What Changes

- Store dated, attributed user feedback and context in the existing private database, linked where useful to sport, activity, or review. Support inspection, correction, supersession, retirement, and deletion.
- Retrieve a bounded, explainable set of relevant context for a manually requested review. Always include applicable structured restrictions; never treat free text as instructions or silently convert model interpretation into user fact.
- Add a separately versioned review contract that distinguishes measured facts, attributed reports, model interpretations, uncertainty, and useful unanswered questions. Keep code-owned arithmetic, dates, provenance, and constraints authoritative.
- Support explicit local or hosted provider selection. Disclose the exact context packet before any hosted personal-data transfer and require request-specific consent. No silent fallback, automatic routing, or scheduled review expansion.
- Evaluate selected providers on common synthetic cases against a pre-registered usefulness and grounding rubric, including facts-only and context-present/context-absent comparisons. Preserve old contracts and benchmark results as distinct baselines.
- Keep local runtime inference disabled until the richer contract passes its approved qualification gate. Planning this change authorizes no model run, hosted transfer, production enablement, image publication, or deployment.

## Capabilities

### New Capabilities

- `athlete-context`: Private, attributed feedback and context lifecycle, correction history, and bounded explainable retrieval.

### Modified Capabilities

- `personalized-training-guidance`: Add a separately versioned connected-review contract, claim/evidence boundaries, provider choice, and gated execution.
- `guidance-dashboard`: Let the user manage context and inspect what context and evidence informed a review, while separating facts, reports, interpretations, and questions.

## Impact

Extend existing SQLite persistence, factual evidence preparation, model adapter/runtime boundaries, authenticated API, dashboard, and synthetic evaluation tooling. No new service, vector database, external broker, training planner, workout publisher, or scheduler/queue capability is introduced. Personal context remains private runtime data and must never enter repository artifacts or development fixtures.
