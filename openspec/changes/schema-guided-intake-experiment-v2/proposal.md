# Proposal

## Why

The frozen Qwen3 8B intake baseline produced 15 complete, structurally rejected
drafts and one memory-aborted correction; this does not establish that open-weight
models cannot extract the notes. A small synthetic experiment should test a clearer,
schema-constrained interface before adding retrieval, larger models or production
integration.

## What Changes

- Introduce an isolated `context-intake-extraction-v2` experimental model wire:
  suggestions and questions only, explicit nested `context`/`commitment` payloads,
  and one disjoint synthetic example. Require provider JSON Schema constraints,
  strict local decoding and unchanged semantic validation; JSON mode alone is not
  an equivalent fallback.
- Move only mechanical identity binding and unique-excerpt UTF-16 offset lookup
  into deterministic test-source code. Retain model responsibility for extraction,
  payload values, unresolved fields and clarification questions. Reject ambiguous
  excerpts and the whole invalid draft; never repair or salvage individual items.
- Freeze a new eight-case protocol, exact requests/schema/example and integrity
  bindings. Reuse the original intake input packets and substantive expectations
  unchanged, without altering any v1 file, source pin or historical result.
- Add private evidence separating raw v2 wire, deterministic assembly, existing
  Kotlin semantic validation and independent human assessment. Initially propose
  one scored request per case, not repeated deterministic correction attempts.
- Stage implementation as usable offline validation first, then fake acquisition
  checks. Any live experiment needs separately approved run/settings/runtime
  records and proven context fit; planning approval authorizes none of those calls.

## Capabilities

### New Capabilities

- `intake-extraction-evaluation`: Synthetic, versioned evaluation of a constrained
  intake wire and deterministic binding boundary, with immutable private evidence,
  independent usefulness assessment and no production authority.

### Modified Capabilities

None. Existing development-launcher/queue behavior and production intake semantics
are unchanged. This is a standalone benchmark, not another application worker.

## Impact

Proposed implementation touches new Kotlin test-source wire/assembler/export and
validation tests, an isolated `benchmarks/intake-extraction-v2/` workflow, and only
a narrow, regression-protected seam in the non-frozen Mac benchmark adapter for
schema-bearing requests and v2 context proof. No new dependency, service, RAG store,
UI, database, production route or profile-admission change is needed.

This plan depends on the current local implementation from
`scoped-reviews-and-adaptive-planning`; that change remains at 44/45, with its v1
qualification task incomplete. The resized 10 GiB VM / 8 GiB model hard limit /
7.5 GiB stop threshold provides a distinct future run envelope, not inherited
quality or latency evidence. A stronger reference model and production adoption
are separate follow-up decisions. Creating this proposal does not authorize code
changes, inference, downloads, hosted transfers or deployment.
