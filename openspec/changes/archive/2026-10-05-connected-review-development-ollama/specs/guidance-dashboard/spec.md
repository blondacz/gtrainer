# Spec Delta

## ADDED Requirements

### Requirement: Synthetic development controls are visibly separate
The isolated development launcher MUST provide a special Developer tab behind an opt-in visibility switch. Controls MUST label packets/results synthetic-only and unqualified and require explicit case/model preview before submission. The switch MUST reveal diagnostics only, not grant inference permission. Normal personal dashboards MUST NOT expose this development execution capability.

#### Scenario: Development preview
- **WHEN** the authenticated developer selects a bundled case and configured model
- **THEN** the panel presents its exact prompt, model identity, generation settings and budgets before enabling submission

#### Scenario: Personal dashboard
- **WHEN** the user opens the normal application
- **THEN** no synthetic development run control or extended execution permission is available

#### Scenario: Developer options enabled
- **WHEN** an authenticated developer enables the visibility switch in the isolated launcher
- **THEN** the Developer tab becomes available without submitting work, granting model permission or initiating cleanup

### Requirement: Long-running development status is inspectable
The Developer tab MUST display durable queue order, task/run identity, input revision, creation/expiry, supersession links, timings and model/budget settings. It MUST distinguish logical outcome, execution state and retention state, support read-only reconnection and explicit cancellation, and MUST NOT invent completion percentages or treat polling as submission.

#### Scenario: First attempt takes several minutes
- **WHEN** a submitted synthetic run remains active
- **THEN** the developer can inspect elapsed time, current attempt and budgets and cancel it without starting another model call

#### Scenario: Refresh after submission
- **WHEN** the browser reconnects to a retained run identifier after refresh or worker restart
- **THEN** it fetches status and restores inspection without resubmitting the run

#### Scenario: Newer input supersedes queued work
- **WHEN** a queued task is superseded while its running predecessor continues
- **THEN** the tab shows the replacement link and running predecessor separately, without labeling the predecessor cancelled

### Requirement: Development failures and limits are explicit
The development panel MUST distinguish timeout, cancellation, validation rejection, runtime/resource failure and unconfirmed cleanup. It MUST display only validated interpretations, preserve source/uncertainty presentation and explain empty results. It MUST NOT call an accepted draft a qualified model or present synthetic development execution as a completed benchmark.

#### Scenario: Timeout with uncertain remote cleanup
- **WHEN** a run times out and runtime cleanup cannot be confirmed
- **THEN** the panel shows both conditions and explains why queued work cannot execute, without claiming cancellation stopped the process or offering automatic inference retry

#### Scenario: Validated but empty result
- **WHEN** an accepted synthetic draft contains no interpretations or questions
- **THEN** the panel states that no connected insights were provided and keeps the unqualified label visible

### Requirement: Worker summaries explain actions without exposing content
The Developer tab MUST show bounded worker summaries with fixed reasons for execution, cleanup, supersession and refusal. It MUST expose safe runtime/health and configuration metadata, not credentials, packet content in diagnostics, rejected text or raw errors. Reasons MUST explain recorded code decisions, not model reasoning. Associated history MUST follow its creation-based TTL.

#### Scenario: Worker blocks the next task
- **WHEN** prior termination remains unconfirmed after task processing
- **THEN** the tab explains the execution block, affected task and pending reconciliation without leaking provider output or credentials

#### Scenario: Database diagnostic
- **WHEN** a worker task reports a persistence failure
- **THEN** the tab displays its fixed safe reason rather than query text, raw database exceptions or submitted field content

#### Scenario: Memory diagnostic
- **WHEN** runtime configuration and host memory health are displayed
- **THEN** the whole-appliance memory ceiling and minimum available host headroom are identified separately, without presenting the non-inference test VM budget as a model allocation

### Requirement: Retention and maintenance are visible and explicit
The Developer tab MUST show expiry and pending/failed deletion without exposing expired payload. It MUST explain that physical deletion occurs on later queue events, not an idle timer. It MUST offer explicit maintenance-only submission through the same worker for ad hoc cleanup/reconciliation, without inference or a competing cleanup process.

#### Scenario: Expired history with no later event
- **WHEN** inspection finds a run beyond its 24-hour creation TTL
- **THEN** its payload is hidden and deletion-pending is distinguished from confirmed deletion; inspection itself starts neither cleanup nor inference

#### Scenario: Developer requests maintenance
- **WHEN** an authenticated developer explicitly submits maintenance
- **THEN** the same worker records its cleanup/reconciliation summary without issuing a model request for that maintenance task
