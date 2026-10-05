# Design

## Context

See proposal.md for scope. Existing SQLite stores imported records, events, review snapshots, and queue state. `Trends.analysisInput` prepares numeric facts and provenance. Existing review contracts select bounded identifiers or render code-owned facts; they are not free-form coaching validators. Model adapters are local-only today, the production guard is unwired, and no model is qualified for personal runtime use.

The foundation now defines deterministic facts and dashboard behavior in `openspec/specs/`. Preserve old review contracts and benchmark captures as separate baselines.

## Goals / Non-Goals

**Goals:**
- Add inspectable attributed context and durable feedback in the existing SQLite store.
- Retrieve relevant context deterministically, bind it with facts to immutable review snapshots, and explain exactly what was included.
- Introduce a new provider-neutral connected-review contract without weakening old validators.
- Define a reproducible synthetic evaluation that measures grounding and usefulness before any qualification decision.

**Non-Goals:**
- Expand background scheduling or queue behavior; richer reviews are manually requested only.
- Enable live inference, select a default provider, run a new benchmark, send personal data to a hosted provider, or deploy/migrate a live database as part of planning.
- Add vector infrastructure, automatic memory extraction, implicit model training, planning, prescriptions, diagnoses, or sport-safety judgments.

## Decisions

### Context records and retrieval

- Extend SQLite additively with stable context identity and revisions, separate topic and source categories, stated author/source, entering user, observation date, applicability/expiry, and optional sport/activity/review linkage. A user-entered clinician or coach attribution is not proof of authorship. The single-user app records the stable `authenticated_user` principal, never a session token.
- Topic categories are restriction, symptom, goal, preference, feedback, and note. Source categories are user report, user-entered clinician guidance, user-entered coach guidance, and review feedback. Measurements remain imported evidence; model interpretations remain review output. Free-text entries are untrusted data, never instructions.
- Start with explicit date/sport/activity filters and deterministic bounded ranking. Use SQLite full-text search only as an optional relevance aid after hard filters. Do not add embeddings or a vector database now.
- Include active applicable structured restrictions regardless of relevance score. Surface conflicts for user resolution. Exclude out-of-scope, expired, retired, superseded, or deleted entries.
- Initial structured restriction kinds are blocked activity/sport, allowed activity/sport, and maximum duration with unit. Free text alone never becomes a structured restriction.
- Treat blocked and allowed assertions for the same activity/sport, scope, and overlapping dates as conflicting. Different overlapping maximum-duration values are also surfaced as conflicts; never auto-select or choose the stricter value. Show all conflicting values with provenance in a dedicated pane and block connected review until the user resolves them.
- Bound optional context separately. Include every applicable mandatory restriction; if mandatory restrictions alone exceed the hard packet limit, refuse inference and report overflow. Set and test numeric packet/resource budgets before any model call.
- Show the user the selected entries and snapshot revision. Do not automatically ingest existing event notes or infer memories from model prose.

### Review contract and lifecycle

- Add a separately named/versioned contract; do not change `factual-review-v1`, `review-focus-app-v1`, or frozen benchmark contracts.
- Execute review snapshot binding before context deletion propagation or review-feedback persistence; those operations depend on stable review snapshot identities.
- Build the packet in code from factual evidence plus retrieved attributed context. Bind review output to evidence digest, context revisions, provider/model, and contract version.
- Validate source bindings, dates/period compatibility, attribution, permitted claim kinds, and restrictions independently. Reject the whole interpretation on failure. Evidence IDs and schema validity do not prove arbitrary prose true; usefulness and semantic quality require independent evaluation.
- Keep inference manual, bounded, and behind a production qualification/health gate. Approved connected-review limits are 5 minutes per attempt, 10 minutes total, at most two attempts (one independent correction after a validation rejection), 32 KiB serialized prompt, and 12 KiB response. Claim each immutable snapshot with one atomic persisted state transition so concurrent manual submissions cannot multiply its attempt/cost budget. A failed attempt terminally closes that snapshot so another invocation cannot reset its time/attempt/cost budget; a new explicit request requires a new generation. Hosted calls additionally require a provider-enforced ceiling of at most $0.25 per attempt and $0.50 total; refuse hosted transfer when the provider cannot enforce/report the ceiling. Local calls have no monetary ceiling; time, byte, qualification, health, and resource gates still apply. Never retry transport, timeout, cancellation, consent, resource, or health failures. These limits authorize no inference: production gate remains closed until separate qualification and approval.
- When context is corrected/deleted or evidence/provider changes, invalidate in-flight publication and mark dependent output stale. Deletion removes dependent saved review content that reproduces the deleted context. Encrypted backups follow existing retention rather than immediate per-record erasure; disclose this limitation.

### Provider choice and consent

- Use a new provider-neutral adapter; keep existing local-only adapter contracts intact. Share existing inference concurrency controls where safe.
- Require a user-selected local or hosted provider/model on every connected-review request and bind the namespaced selection into its immutable snapshot. Missing, unknown, or mismatched selection has no default; no silent fallback or automatic hosted escalation. Changing provider creates a new generation for that request and does not mutate stored context or imported evidence.
- Preview the exact serialized prompt and packet digest with the selected provider/model before hosted execution. Consent is ephemeral, applies only to that immutable snapshot and selection, and is checked before every attempt; packet/provider change, revocation, or process restart requires fresh approval. No hosted call without matching consent. Local providers do not require hosted consent.
- Keep prompts, health context, credentials, and rejected personal responses out of logs, repository fixtures, CI artifacts, and public benchmark records.

### Evaluation and qualification

- Build synthetic cases with matched facts-only, context-present, and context-absent variants. Freeze packet construction, prompt/contract version, scoring rubric, case count, and pass threshold before any model calls.
- Score factual grounding, attribution, relevance to goals/context, useful connections/questions, uncertainty, and inappropriate advice. Include all first-pass, correction, rejection, and failure outcomes; do not score only accepted outputs. Use independent human review; model self-approval is insufficient.
- Compare only explicitly selected candidates on identical cases. Record model identity/settings, prompt and packet hashes, attempt-level results, latency, resource use, and cost where available. No candidate or run is selected/authorized by this design.
- Keep production qualification separate from structural unit-test success. The live inference gate stays disabled until an independently reviewed qualification and explicit operator approval.

## Risks / Trade-offs

- **Free text can contain unsafe or misleading instructions** -> Treat it as untrusted attributed data, constrain prompt boundaries, and keep applicable restrictions code-owned.
- **A valid schema can still encode false meaning** -> Validate bindings mechanically, reject whole responses, and require independent rubric-based evaluation before qualification.
- **Deletion can leave copies in old backups** -> Remove active and dependent data, invalidate derived indexes, and disclose backup-retention limits.
- **Local and hosted comparisons may differ in settings or context** -> Use identical synthetic packets and frozen contracts; report protocol limitations and all outcomes.
- **Provider integration could bypass consent** -> Enforce packet-scoped consent at the transport boundary and test zero network requests without valid consent.

## Migration Plan

Use additive schema migrations and synthetic fixtures. Test restart, correction, deletion, restore compatibility, and existing record/event identity preservation. Do not migrate the Pi database or update the installed restore helper in this change without separate approval. Keep the new production inference gate closed; code and evaluation artifacts alone do not enable runtime calls.
