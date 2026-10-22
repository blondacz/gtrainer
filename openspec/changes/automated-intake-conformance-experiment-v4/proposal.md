# Proposal

## Why

The completed v3 experiment failed despite favorable human ratings: strict local
validation rejected two responses, while other accepted responses omitted structured
commitments and asked unnecessary questions. The user now wants an autonomous,
bounded improvement loop with automated acceptance instead of human review.

## What Changes

- Introduce synthetic-only `context-intake-extraction-v4`, preserving all v1/v2/v3
  artifacts, failed attempts, human judgments and final decisions unchanged.
- Replace the human-quality gate **for v4 only** with a frozen deterministic
  eight-case conformance oracle. Require strict wire/binding/semantic acceptance
  and every case-specific fact, structure, uncertainty and safety predicate.
- Improve extraction instructions for open versus unknown bounds, unique unresolved
  fields, structured commitments, recurring duties and clarification of genuine
  unknowns. Keep the original eight input packets and v3-compatible provider schema.
- Allow at most three full prompt-revision batches within one four-hour campaign,
  with one scored attempt per case per revision and an existing active-stop probe
  per batch. Freeze the oracle before inference; never repair responses or combine
  successful cases from different revisions.
- Retain exact revision/request/context/tooling bindings and all current runtime
  identity, CPU, memory, pressure, ownership, cancellation and restoration guards.
  Stop on safety/infrastructure failure; no automatic restart, resize or download.
- Record automated pass/fail/incomplete evidence, not human scores or general model
  qualification. No human assessment is required to pass v4 conformance.

## Capabilities

### New Capabilities

- `intake-extraction-evaluation`: add version-scoped v4 deterministic conformance
  and bounded autonomous campaign requirements. This capability is not yet in main
  specs; pending v2/v3 deltas retain their historical, version-specific requirements.
  Reconcile those deltas explicitly before any future sync/archive.

### Modified Capabilities

None. The application's development launcher, production contracts and existing
version-specific evaluation gates remain unchanged.

## Impact

- New bounded benchmark implementation under `benchmarks/intake-extraction-v4/`
  and narrowly scoped Kotlin test bridges/fixtures, with immutable lineage to v3.
- Reuse strict `IntakeExtractionV2.validate`, the v3 provider-schema subset and the
  non-frozen Mac adapter's request/context seams and owned stopping mechanics.
- Fresh offline token evidence is required for changed prompts; the old 816-token
  bound cannot be reused. Retain context 4096 and output reserve 1536.
- Keep Qwen3:8b/Ollama pins, mmap/no-repack, 10-GiB VM, 8-GiB hard limit, 7.5-GiB
  current-memory stop and CPU/time/byte bounds. No new dependency or hosted service.
- No production inference, personal data, app/database change, deployment, publication,
  commit/push or relaxation of validation is included. Planning alone starts no run.
