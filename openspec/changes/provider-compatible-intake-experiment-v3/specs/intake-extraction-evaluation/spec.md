# Intake Extraction Evaluation Spec Delta

## Purpose

Evaluate synthetic intake extraction through separately versioned provider and
local validation boundaries, preserving historical evidence and production safety.

## ADDED Requirements

### Requirement: V3 preserves prior experiments and inherited inputs
V3 MUST use a distinct profile, immutable freeze and run/report versions and preserve frozen v1/v2 files, sources and outcomes. V3 MUST retain all eight ordered packets, expectations, scoring and v2 instructions/example. Provider schema changes MUST be exactly two unresolved-field uniqueItems removals and three maxLength:4000 removals: sourceExcerpt in both branches and context.content. All other schema constraints MUST remain unchanged.

#### Scenario: Reviewed provider schema is exported
- **WHEN** v3 is frozen
- **THEN** verification proves that exactly the five declared keywords differ, while exact packet/instruction/example bytes and inherited expectations remain equal
- **AND** all three affected strings retain minLength:1 and every other schema constraint remains equal to v2
- **AND** new artifacts reference both prior manifest hashes without updating either manifest

#### Scenario: Another schema change is needed
- **WHEN** compatibility review identifies a further unsupported constraint or implementation changes another request field
- **THEN** freezing and acquisition stop pending an explicit plan/version decision rather than expanding the allowed difference silently

### Requirement: V3 declares provider and local constraint ownership
V3 MUST freeze an ownership record identifying provider constraints, local-only unresolved-field uniqueness and 4000-code-unit string ceilings, binding and semantic checks. Every claimed provider constraint MUST have pinned-runtime evidence. Unknown or unsupported provider constraints MUST block admission; an ownership record alone MUST NOT count as proof. Local duplicate, length and byte validation MUST remain unchanged.

#### Scenario: Provider permits repeated unresolved fields
- **WHEN** a provider-shaped response contains duplicate unresolved fields within otherwise valid array bounds
- **THEN** strict local wire validation rejects the entire response before binding or semantic validation
- **AND** the report identifies a local wire failure, not a provider guarantee or automatic deduplication

#### Scenario: Provider permits a string above a local ceiling
- **WHEN** an otherwise provider-shaped response has 4001 ASCII characters in either sourceExcerpt branch or context.content
- **THEN** unchanged local wire validation rejects the entire response before binding or semantic validation
- **AND** no string is trimmed and the report attributes rejection to the local limit rather than claiming provider enforcement

#### Scenario: Provider support is unresolved
- **WHEN** a remaining schema keyword or value cannot be shown to work on the pinned conversion path
- **THEN** no acquisition occurs and no unconstrained JSON fallback or schema projection is used

### Requirement: V3 retains strict extraction and source validation
V3 MUST retain v2 strict wire acceptance, unique exact-excerpt UTF-16 binding and unchanged production semantic validation. Missing, unknown or duplicate keys, coercion, duplicate unresolved fields and size violations MUST reject the whole response. Code MUST NOT infer values, disclosures or questions, normalize excerpts, repair output or salvage items.

#### Scenario: Wire is unchanged but local constraints still apply
- **WHEN** duplicate disclosures, missing nullable keys, extra identity keys or invalid primitive types occur
- **THEN** v3 rejects them with the same local acceptance boundary as v2 and records later stages as not run

#### Scenario: Exact binding or semantic evidence fails
- **WHEN** an excerpt has overlapping matches or a well-shaped date or recurrence lacks source support
- **THEN** the entire result is rejected at the appropriate stage without modifying the response

### Requirement: V3 context evidence is independently bound
Before inference, v3 MUST bind exact requests, schema, model, template, tokenizer and provider path to a complete input-plus-output fit proof without truncation. Proposed context MUST remain 4096 with 1536 output reserve unless separately approved. Reused offline counts MUST be re-bound to unchanged rendered inputs and MUST NOT be described as provider measurements.

#### Scenario: Unchanged rendering reuses offline evidence
- **WHEN** the audited v3 inputs and rendering equal the source-audited inputs and the schema remains grammar-only
- **THEN** the proof can retain the audited maximum of 816 input tokens, identify the additional 256-token safety margin and bind all eight rendered hashes to v3
- **AND** measured provider token counts remain null until actually observed

#### Scenario: Runtime identity or rendering drifts
- **WHEN** a template, tokenizer, schema path, model or request hash differs from the proof
- **THEN** admission fails before generation, without increasing context or shortening inputs

### Requirement: V3 authorization is isolated from earlier versions
V3 acquisition and replay MUST require their own exact profile, manifest, tooling, settings and purpose-bound records. Invalid or cross-version bindings MUST fail before runtime actions, response reads or validation callbacks as applicable. Shared runtime mechanics MUST preserve v1 default requests, authorization and safety checks.

#### Scenario: V2 approval is supplied for v3
- **WHEN** a v3 acquisition or replay receives a v1/v2 profile, manifest, run or approval record
- **THEN** it refuses without runtime actions or consuming responses

#### Scenario: Existing v1 caller uses the shared adapter
- **WHEN** the unchanged v1 request path executes fake checks after the v3 seam is added
- **THEN** request bytes and ownership, monitoring, refusal and stop behavior remain unchanged

### Requirement: V3 acquisition retains one attempt and owned stopping
The v3 experiment MUST allow one scored attempt per original case, with no correction, retry, fallback or automatic replay. Monotonic attempt, case and batch bounds and all existing ownership, memory and monitoring checks MUST remain approval-bound. Incomplete output, timeout, infrastructure failure or unverified stopping MUST prevent later cases from starting.

#### Scenario: Complete output is rejected locally
- **WHEN** a complete response fails a validation stage
- **THEN** that case stays rejected and the next case can proceed without a second request for the rejected case

#### Scenario: Stop verification fails
- **WHEN** owned termination cannot be verified after a stop condition
- **THEN** the batch remains fenced, later cases remain unrun and partial evidence is preserved

### Requirement: V3 evidence preserves stage and measurement provenance
Private v3 reports MUST preserve raw outputs and hashes, wire/binding/semantic statuses, available assembled adaptations, all eight outcomes and immutable run identity. Unknown measurements and human assessments MUST remain null. Fake vectors, offline counts, imported resources and live observations MUST be distinguished; sensitive data MUST stay out of terminal, repository and CI logs.

#### Scenario: Eight-case fake replay completes
- **WHEN** synthetic handwritten vectors pass the real offline validation bridge
- **THEN** the report states fake origin, leaves runtime observations and human scores null and proves pipeline behavior only

#### Scenario: A run stops early
- **WHEN** a timeout or infrastructure stop prevents completing all cases
- **THEN** all eight cases remain in the denominator, with incomplete and unrun outcomes preserved

### Requirement: V3 success requires independent assessment and separate approvals
Planning, implementation, inference, real-response replay and production acceptance MUST remain separate. V3 success MUST require independent human scoring: all eight complete/useful, each case/dimension at least 2/4, weighted mean at least 80%, zero critical failures. V3 MUST NOT qualify v1, change production admission or authorize personal data, confirmations or amendments.

#### Scenario: Planning is approved
- **WHEN** only the v3 plan is approved
- **THEN** no implementation, model call, runtime mutation, download or real-response replay occurs

#### Scenario: All validators pass without human scoring
- **WHEN** every response is structurally and semantically accepted but independent assessments are absent
- **THEN** usefulness remains incomplete and production admission stays closed

#### Scenario: Results are compared with the baseline
- **WHEN** a future approved v3 result is compared with historical v1 outcomes
- **THEN** the report discloses changed prompt/schema/binding, one-attempt policy and memory envelope without claiming a controlled causal or latency improvement
