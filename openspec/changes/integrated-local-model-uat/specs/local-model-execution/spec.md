# Local Model Execution Spec Delta

## Purpose

Run explicitly requested model jobs on the owner's devices with supervised resource
ownership, validated private results and honest experimental admission status.

## ADDED Requirements

### Requirement: Owner UAT has explicit local admission
The system MUST distinguish experimental owner-UAT permission from qualification, synthetic development permission and ordinary personal admission. UAT execution MUST require an installed owner policy naming the allowed local model, runtime, profiles and limits. Hosted providers MUST remain unavailable under that policy.

#### Scenario: Owner opts into an unqualified local model
- **WHEN** the owner explicitly enables an accepted owner-UAT configuration
- **THEN** allowed manual jobs are labeled experimental and unqualified, without changing ordinary qualification or enabling hosted execution

#### Scenario: Model or policy identity changes
- **WHEN** the model, runtime identity, effective prompt/schema contract or accepted limits differ from the installed policy
- **THEN** execution is blocked pending renewed acceptance instead of inheriting an old grant

### Requirement: Each job has bounded owned execution
The system MUST admit at most one local model job at a time, enforce the accepted per-job time/context/resource bounds, and verify exact owned stopping. Browser loss MUST NOT renew work or revoke cleanup. Uncertain termination MUST block subsequent jobs. No automatic correction, retry, provider fallback or download is allowed in initial owner UAT.

Elapsed job time MUST include host sleep. Durable deadlines MUST bind a sleep-inclusive clock and boot identity, not restart a relative timer. While the host or supervisor cannot act, physical cleanup MUST be reported as uncertain rather than guaranteed within the deadline. Recovery MUST fence expired or unverifiable work before publication and block capacity until authoritative stopping.

#### Scenario: Browser reloads during a job
- **WHEN** the owner reconnects while a request is running
- **THEN** the app restores that job's status without another send or a renewed deadline

#### Scenario: Deadline or resource stop occurs
- **WHEN** a job exceeds its deadline or an accepted resource/health limit
- **THEN** publication is fenced, owned termination is attempted and its actual proof or uncertainty is retained while factual app views remain usable

#### Scenario: Delayed work resumes after restart
- **WHEN** an old worker or result arrives after its ownership was revoked
- **THEN** it cannot start or publish under a new app owner and is never silently replayed

#### Scenario: Host sleeps past the deadline or supervisor restarts
- **WHEN** recovery observes an expired deadline, a changed boot identity or an unverifiable clock/deadline binding
- **THEN** no publication or new job is permitted until authoritative stopping; status distinguishes missed cleanup during suspension from verified termination

### Requirement: Effective requests fit the accepted model context
Before a send, the system MUST bind the exact model/runtime, profile, source revisions, prompt, schema and context proof to the job. Complete effective input, reserved output and safety margin MUST fit the accepted context. Source or permission changes MUST invalidate publication. Oversized input MUST be refused rather than silently truncated or repaired.

#### Scenario: Imported evidence changes after preview
- **WHEN** relevant source records, notes, plan revisions or permissions change
- **THEN** the old job is stale and cannot publish as a current result

#### Scenario: Personal evidence exceeds context
- **WHEN** the selected input cannot fit the accepted budget
- **THEN** the UI explains which selected scope cannot be processed under the installed limits, without requiring the owner to manage tokens, and sends no shortened substitute or automatic retry; a new selection or separately validated and explicitly accepted runtime binding is required

#### Scenario: Descriptive evidence uses lossless compact transport
- **WHEN** a descriptive request uses the versioned compact model-input representation
- **THEN** its bounded inverse reproduces every canonical packet byte, all selected evidence and provenance remain intact, the exact effective prompt and wire version are bound before sending, and the unchanged strict output validator requires complete original source references rather than transport indexes

#### Scenario: A larger context is considered
- **WHEN** useful review scopes exceed the initial conservative context setting
- **THEN** a larger setting remains closed until separate resource/context testing and explicit installed-policy acceptance, and it is never a hidden fallback or retry of a refused job

### Requirement: Published model output passes its actual contract
The system MUST validate a whole result against its declared profile and immutable source before publishing it. Wire adaptation MUST be versioned and mechanical, never inferred repair. Empty, rejected, timed-out and failed results MUST remain distinct from useful accepted output. Model interpretations MUST remain separate from application-calculated facts.

#### Scenario: A response invents a date or unsupported reference
- **WHEN** profile validation rejects any required source binding or payload
- **THEN** the whole result is rejected without salvaging items or presenting rejected prose as accepted guidance

### Requirement: Results and monitoring preserve privacy
The system MUST retain private result/status history according to its explicit retention policy while exposing only content-free operational diagnostics by default. Status inspection MUST NOT sync data, start inference, acknowledge consent, apply a change or reconcile runtime mutations. Monitoring MUST NOT imply an agent is continuously supervising the service.

#### Scenario: Owner asks for operational inspection
- **WHEN** an authorized observer checks app/model availability and job status
- **THEN** safe state, timings, limits and fixed failure reasons are available without credentials, health values, prompts or rejected output in diagnostic logs

#### Scenario: Readiness is uncertain
- **WHEN** the Mac sleeps, the model runtime is unavailable or stop proof is missing
- **THEN** status reports the actual unavailable/blocked state and does not manufacture readiness or launch recovery from a read
