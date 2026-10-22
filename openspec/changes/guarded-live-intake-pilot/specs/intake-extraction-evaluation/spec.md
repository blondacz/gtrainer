# Spec Delta

## Purpose

Evaluate source-bound intake extraction and its guarded synthetic interaction path
without treating experimental evidence as production or personal-data admission.
This extends the capability in the pending v4/v5 changes without changing their
historical execution allowances or the completed handwritten-fixture harness.

## ADDED Requirements

### Requirement: Live intake pilot approval is separate from planning and implementation

The synthetic live pilot SHALL require explicit implementation approval and a fresh
exact execution grant bound to verified sources. Conditional user approval before
implementation SHALL NOT bypass the offline checkpoint. Planning or historical authorities SHALL NOT
permit model/tokenizer execution, live API probes or runtime mutations. Production
and personal-data admission SHALL remain closed.

#### Scenario: Proposal or offline implementation is approved
- **WHEN** the user approves planning or offline implementation only
- **THEN** no model request, tokenizer execution, live API probe or runtime mutation occurs
- **AND** successful offline checks stop at the execution approval gate

#### Scenario: Required service interruption is not approved
- **WHEN** the grant omits the inherited runtime's deployment pause, chosen cleanup policy or owned pod lifecycle
- **THEN** live execution remains blocked without switching to an unowned shared runtime

### Requirement: The live intake pilot has a single immutable acquisition allowance

The pilot SHALL use only the predeclared known synthetic u02 packet and frozen
candidate prompt/schema/settings. One root SHALL admit at most one non-scored stop
probe and one intake generation, with the probe required before generation. Claims
SHALL be durable before launch. No correction, retry, replacement root, packet,
prompt revision or provider fallback SHALL be allowed.

#### Scenario: Duplicate start or uncertain launch
- **WHEN** an already claimed request is submitted again or its launch outcome is uncertain
- **THEN** no replacement request starts and the original outcome remains fenced or terminal

#### Scenario: Failed or unusable model output
- **WHEN** the response is rejected, empty, incomplete or has no usable suggestion
- **THEN** the actual result is recorded without a second generation or fixture substitution

### Requirement: Live pilot execution budgets are fixed and independently enforced

The runtime grant SHALL last at most 3600 seconds with a 240-second cleanup reserve.
Preflight SHALL be capped at 120 seconds, the probe phase at 900, generation at 900,
and the case including validation at 1800; validation SHALL be capped at 600 and an
owned-stop attempt at 60. Deadlines SHALL not reset on observer loss or restart.

#### Scenario: Remaining authority cannot cover a phase and cleanup
- **WHEN** a phase cannot fit its fixed bound and reserved cleanup time
- **THEN** it does not start and no timeout, root replacement or authority extension hides the refusal

#### Scenario: Observer disappears during execution
- **WHEN** chat, browser or an observing process disconnects
- **THEN** the durable owner retains the same deadlines and cleanup authority
- **AND** it does not replay an uncertain claimed generation

### Requirement: Live pilot resource and context admission fail closed

The pilot SHALL retain Qwen3:8b, Ollama 0.35.0, CPU-only three-thread execution,
the 10-GiB VM, 8-GiB hard limit and 7.5-GiB full-current-memory stop threshold.
Identity, pressure, mmap and host-headroom safeguards SHALL remain. Fresh exact
context evidence SHALL fit 4096 tokens including 1536 output reserve and 256 margin.
Missing or drifting evidence SHALL block or stop work without fallback or resize.

#### Scenario: Pin, context or resource evidence fails
- **WHEN** model/runtime identity drifts, context exceeds the cap, pressure crosses a limit or safety evidence is unavailable
- **THEN** no new generation is admitted and active owned work is stopped under existing ownership checks

### Requirement: Cleanup authority and proof outlive inference authority

Cancellation, deadline or health failure SHALL fence late output and invoke bounded
owned cleanup independently of observer lifetime. Successful cleanup SHALL prove
owned clients/descendants stopped, pod UID absent, cgroup empty and running CRI
identity absent, then verify the grant's cleanup policy. The grant SHALL explicitly
choose restoration or leaving the original deployment paused without other spec
changes. Uncertain required cleanup SHALL block import, not be labeled success.

#### Scenario: Socket closes but an owned descendant remains
- **WHEN** transport ends without complete owned-process and runtime stop proof
- **THEN** cleanup remains unconfirmed and no artifact is admitted for app inspection

#### Scenario: Restoration encounters unrelated deployment drift
- **WHEN** the current service identity/spec no longer matches the captured restoration authority
- **THEN** no unrelated state is overwritten, owned stopping still proceeds where safe and import remains blocked

### Requirement: Live intake provenance binds the actual supervised acquisition

A live-pilot receipt SHALL identify the new supervised acquisition, its grant,
source snapshot, exact request/packet, raw response, validation and verified cleanup.
Import SHALL bind this chain to an independently recorded expected receipt digest.
Hashes alone SHALL NOT establish model origin. Fake evidence, handwritten fixtures,
retained captures and production publication identities SHALL not masquerade as live provenance.

#### Scenario: Self-consistent but substituted evidence is supplied
- **WHEN** a receipt and hashes name another root, old capture, fake worker or handwritten witness
- **THEN** live-mode import rejects them without manufacturing production execution/publication rows

#### Scenario: Genuine new output matches witness wording
- **WHEN** the real supervised request happens to return identical response bytes to a witness
- **THEN** eligibility depends on actual acquisition lineage, not text inequality

### Requirement: Live-pilot inspection is read-only and source bound

The app SHALL import only a complete, private, bounded artifact snapshot after
successful acquisition and cleanup, rerun unchanged strict validation and decode
the exact draft. Availability SHALL require matching current source identity,
revision and content. Browser inspection, refresh and correction SHALL never
start inference or import arbitrary paths, uploads or provider settings.

#### Scenario: Source correction or artifact mutation
- **WHEN** the current source differs or verified artifact bytes change during import
- **THEN** live suggestions are unavailable and stale drafts are discarded without regeneration or repair

#### Scenario: Browser refresh after import
- **WHEN** the synthetic pilot page reloads
- **THEN** it reads the admitted capture and confirmed synthetic items without model or confirmation requests

### Requirement: Live pilot authority requires explicit reviewed confirmation

Inspection, editing and staging SHALL create no authoritative synthetic item.
Every disclosed unknown SHALL require deliberate resolution and UI acknowledgment
before staging. Only explicit confirmation of visible selected items SHALL write
through unchanged validation, authentication, origin, CSRF, revision, atomicity and
idempotency boundaries. Uncertain outcomes SHALL require reload, never automatic retry.

#### Scenario: Valid generated draft is inspected and staged
- **WHEN** a user edits the actual generated suggestion, resolves unknowns and stages it
- **THEN** authoritative store counts remain unchanged until a separate explicit confirmation action

#### Scenario: Confirmation is repeated or uncertain
- **WHEN** the same confirmation identity/body is replayed or its response is lost
- **THEN** identical replay cannot duplicate writes and uncertainty requires reload and inspection
- **AND** conflicting reuse or stale source/item revisions are rejected atomically

### Requirement: The live pilot has bounded private inspection and honest outcomes

The pilot SHALL allow at most one post-cleanup disposable app session capped at
1200 seconds, without renewing model authority. Evidence SHALL stay private;
diagnostics SHALL omit content and credentials. Reports SHALL distinguish strict
acceptance, the predeclared case comparison, edited confirmation and failures.
One known synthetic packet SHALL NOT establish fresh-holdout reliability or qualification.

#### Scenario: App session ends or times out
- **WHEN** the bounded loopback session stops or reaches its deadline
- **THEN** only its owned server and generated store are cleaned up, while private acquisition evidence is preserved

#### Scenario: Generated response needs a factual edit
- **WHEN** a user changes the generated draft before confirmation
- **THEN** the original raw response and comparison result remain unchanged and the edited confirmation is reported separately
- **AND** historical prospective 7/8 and post-hoc 8/8 results and production gates remain unchanged
