# Proposal

## Why

Approved offline inspection established that the eight frozen intake requests fit
4096 context tokens, but the pinned provider ignores v2's `uniqueItems:true` and
turns repetition maxima above 2000 into unbounded grammar rules. A separately
versioned experiment must describe those provider limits honestly without weakening
strict local validation or rewriting v2.

## What Changes

- Introduce synthetic-only experiment `context-intake-extraction-v3`, with a new
  freeze under `benchmarks/intake-extraction-v3/`. Preserve all v1/v2 artifacts,
  pinned sources and historical outcomes byte-for-byte.
- Keep v2 response shape, exact instructions/example, original eight packets,
  binding, semantic validator, local duplicate/length rejection and scoring unchanged.
  Remove exactly two unresolved-field `uniqueItems:true` declarations and three
  `maxLength:4000` declarations from the newly frozen provider schema: sourceExcerpt
  in both branches and context.content. Freeze explicit local-only ownership of
  those unchanged limits; retain all other provider constraints, including minLength.
- Audit every remaining provider constraint against the pinned converter. Bind the
  new schema and request hashes to a v3 context proof; retain proposed 4096 context
  and 1536 output reserve without treating offline counts as inference measurements.
- First deliver a usable private offline v3 replay and eight-case fake checkpoint.
  Then prepare the minimal v3 acquisition path using existing Mac safety mechanics
  and separate v3 authorization; no provider framework or production profile.
- Keep named-run inference approval, real-response offline replay, independent human
  scoring and production acceptance separate. Planning does not authorize any of them.

## Capabilities

### New Capabilities

- `intake-extraction-evaluation`: Versioned synthetic intake evaluation with explicit
  provider/local constraint ownership, strict replay and bounded acquisition gates.
  This capability is not yet in main specs; the pending v2 change introduces the
  same capability. This follow-up adds v3-specific requirements without replacing
  v2's pending requirements or claiming v2 completion.

### Modified Capabilities

None. No existing main-spec requirement or production contract changes.

## Impact

Planned additions: v3 benchmark/exporter, compatibility evidence, test-source
adapter/bridge and focused tests. Read-only reuse: frozen v2 extraction helper,
original fixture builder, v1/v2 verifiers and production semantic contract. A later
implementation slice may add a narrow explicit request/context seam to non-frozen
`benchmarks/scoped-profiles/mac_runtime.py`, preserving default v1 bytes and safety.

No application API, database, UI, model installation, dependency installation,
runtime resize, provider upgrade, personal-data flow or production admission change.
The historical parent qualification task remains incomplete. The original
implementation request paused on the additional parser limitation. This approved
amendment updates planning only; present it for review before resuming implementation.
