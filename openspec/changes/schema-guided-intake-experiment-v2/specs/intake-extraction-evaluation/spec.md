# Intake Extraction Evaluation Spec Delta

## Purpose

Evaluate a schema-guided synthetic intake interface and deterministic source
binding without changing production contracts or mistaking valid JSON for usefulness.

## Historical implementation status — 2026-10-22

The requirements below remain V2's unchanged acceptance criteria, not a claim that
every requirement was implemented or verified. Offline validation/replay is complete;
the live path and independent human assessment are deferred because the frozen schema
is incompatible with the pinned provider. Exact-request offline context fit was proven
separately, but does not remove that schema blocker. The separate V3 change addresses
provider compatibility under its own version and evidence; it does not establish V2
completion. No requirement is waived and no inference or production authority is added.

## ADDED Requirements

### Requirement: Experimental evaluation preserves the baseline
The evaluation MUST use a separately versioned experimental wire and frozen eight-case protocol. It MUST preserve original intake packets and substantive case expectations, reference their original hashes, and leave frozen v1 files, pinned sources and historical outcomes unchanged.

#### Scenario: New prompts improve an old failed case
- **WHEN** an experimental prompt produces a better response for an original case
- **THEN** the result is recorded only under the new version and the original rejection remains unchanged

#### Scenario: An original input changes
- **WHEN** a case packet or inherited expectation differs from its pinned original
- **THEN** integrity verification refuses the experiment rather than silently refreshing the baseline

### Requirement: Model output owns extraction rather than input identity
The experimental response MUST contain only suggestions and questions. Each suggestion MUST contain kind, exact sourceExcerpt, explicit context and commitment keys, and unresolvedFields. The active payload MUST be present and the inactive payload null. Missing, unknown or duplicate keys, primitive coercion and oversized output MUST reject the whole response.

#### Scenario: Model invents a payload wrapper
- **WHEN** a suggestion supplies payload instead of the required context or commitment keys
- **THEN** wire validation rejects the entire response without translating the invented wrapper

#### Scenario: Model echoes authoritative identifiers
- **WHEN** a response supplies profile, intake identity, revision, offsets or confirmation identifiers
- **THEN** those unknown response fields cause rejection rather than overriding the bound input

#### Scenario: Model omits a defaultable nested field
- **WHEN** a required context, commitment or recurrence field is absent even if the old internal representation has a default
- **THEN** experimental wire validation rejects the response without filling that field

### Requirement: Source binding uses unique exact excerpts
Deterministic binding MUST take identity and revision only from the immutable input and derive native UTF-16 offsets only from a unique exact, nonempty excerpt occurrence. Missing or multiple occurrences, including overlapping occurrences, MUST reject the whole response without normalization, fuzzy matching or item salvage.

#### Scenario: Emoji precedes a selected excerpt
- **WHEN** a unique excerpt follows a supplementary Unicode character
- **THEN** binding produces UTF-16 offsets that reproduce the exact excerpt from the original input

#### Scenario: Excerpt has overlapping matches
- **WHEN** an excerpt occurs more than once, including overlapping matches
- **THEN** binding rejects the whole response rather than choosing the first occurrence

#### Scenario: One suggestion cannot be bound
- **WHEN** one suggestion among otherwise valid suggestions has a missing or ambiguous excerpt
- **THEN** no partially assembled result is accepted

### Requirement: Existing semantic safety remains authoritative
An assembled result MUST pass the unchanged intake semantic validator before being structurally accepted. Payload values, unresolved-field disclosures and questions MUST remain model-owned. Binding MUST NOT infer dates, recovery, author overrides, recurrence, confirmations or missing content. Internal validator adaptation MUST NOT be represented as an original v1 model response.

#### Scenario: Well-shaped output resolves an unsupported date
- **WHEN** the wire is valid but a proposed date lacks the original contract's evidence or explicit relative-date resolution
- **THEN** semantic validation rejects the whole assembled result

#### Scenario: Unknown recurrence lacks clarification
- **WHEN** required unresolved recurrence fields or clarification questions are missing
- **THEN** the response fails unchanged semantic validation rather than receiving generated repairs

#### Scenario: Empty extraction passes structure
- **WHEN** an empty suggestions/questions response passes both structural stages
- **THEN** it still needs independent assessment and is not automatically useful or successful

### Requirement: Requests bind schema and example explicitly
Every experimental request MUST use the exact frozen instructions, disjoint synthetic example, original packet and provider JSON Schema constraint. The schema MUST require explicit nested keys and disallow additional properties. Schema refusal or unsupported format MUST fail closed without fallback to unconstrained JSON, prompt rewriting or response repair.

#### Scenario: Provider refuses a schema feature
- **WHEN** the selected runtime rejects the schema-bearing request
- **THEN** acquisition aborts and retains the refusal without retrying in JSON-only mode

#### Scenario: Example overlaps the scored cases
- **WHEN** the example reuses a scored input or its case-specific answer
- **THEN** freezing is refused until an independently validated disjoint example is supplied

### Requirement: Context admission includes the new request shape
Before inference, admission MUST verify the exact model, template, runtime, schema and effective rendered input plus reserved output fit the approved context without truncation. It MUST include evidence of how schema handling affects that input. Old v1 context proofs, approximate byte-to-token ratios and larger RAM MUST NOT substitute for a v2 fit proof.

#### Scenario: Example and schema invalidate the old bound
- **WHEN** the v1 fit proof does not account for the experimental request
- **THEN** no model request occurs until an adequate v2 proof exists

#### Scenario: Fit requires a larger context
- **WHEN** the approved context cannot be proven sufficient
- **THEN** acquisition remains blocked rather than increasing context or removing frozen evidence automatically

### Requirement: Acquisition has one bounded scored attempt per case
The initial protocol MUST allow exactly one scored attempt per case in frozen order, with no correction, automatic replay, fallback or best-of selection. Attempt, case and batch deadlines MUST be monotonic and approval-bound. Timeout, incomplete output, monitoring failure or infrastructure failure MUST stop the batch and retain later cases as unrun.

#### Scenario: Complete draft is rejected
- **WHEN** wire, binding or semantic validation rejects a complete response
- **THEN** that case remains rejected and acquisition can proceed to the next case without another request for the rejected case

#### Scenario: Resource observation fails
- **WHEN** a required resource or ownership observation is missing or crosses an approved stop threshold
- **THEN** acquisition stops owned execution, verifies termination and starts no later case

### Requirement: Evidence separates every validation stage
Private evidence MUST retain exact raw provider output, wire outcome, binding outcome, assembled result when available, semantic outcome and all eight case outcomes separately. Hashes, immutable run/settings/runtime identity and observed resources MUST accompany them. Unknown measurements and human assessments MUST remain null; sensitive content MUST NOT enter terminal, repository or CI diagnostics.

#### Scenario: Semantic validation rejects a valid wire
- **WHEN** wire decoding and source binding succeed but semantic validation fails
- **THEN** evidence retains the raw wire and assembled result with distinct stage outcomes rather than calling the response accepted

#### Scenario: Batch ends early
- **WHEN** fewer than eight cases produce complete responses
- **THEN** the report preserves the full denominator and every incomplete or unrun outcome

### Requirement: Usefulness remains independently assessed
Experimental success MUST require a complete useful final for every case, every case/dimension at least 2/4, weighted mean at least 80%, zero critical failures and independent human scoring under inherited substantive dimensions. Structural gains alone MUST remain diagnostic. Results MUST disclose intervention and hardware differences and MUST NOT establish production or historical v1 qualification.

#### Scenario: All outputs pass validators without scores
- **WHEN** all eight outputs pass structural and semantic checks but independent scores are absent
- **THEN** the report remains usefulness-incomplete and production admission remains closed

#### Scenario: Runtime differs from the historical baseline
- **WHEN** v2 uses the resized VM or changed schema/prompt/bookkeeping interface
- **THEN** comparison labels those differences and does not claim a controlled latency improvement or isolate one cause

### Requirement: Experiment approvals confer no production authority
Implementation, inference and offline replay MUST honor their separate explicit approvals. The experiment MUST remain synthetic and non-production: no personal store, confirmation, amendment application, application route or admission update. Planning, RAM allocation or schema-valid output MUST NOT authorize model calls, downloads, hosted transfer or deployment.

#### Scenario: Only this proposal is approved
- **WHEN** the operator has approved planning but not implementation or inference
- **THEN** only planning artifacts are created and no model request or runtime mutation occurs

#### Scenario: Experimental success is recorded
- **WHEN** the new evaluation meets its independent human-scored success criteria
- **THEN** production integration and qualification still require an explicit follow-up decision rather than automatic gate changes
