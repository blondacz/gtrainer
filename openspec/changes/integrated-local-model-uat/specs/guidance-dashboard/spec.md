# Guidance Dashboard Spec Delta

## ADDED Requirements

### Requirement: Owner UAT runs as a persistent private application
The system MUST provide an operator-installed private Mac application with persistent owner storage and no demo lifetime or automatic data deletion. Authentication expiry and per-job safety limits MUST remain separate from app availability. Shutdown, restart and upgrade MUST preserve records and safely fence unfinished jobs. Sleep/offline limitations MUST be stated honestly.

#### Scenario: Owner returns after the old demo duration
- **WHEN** the Mac and installed app service remain available
- **THEN** the owner can sign in and continue with saved imports, notes, plans and results without recreating a demo or requesting another time allowance

#### Scenario: App restarts after interruption
- **WHEN** the service resumes
- **THEN** private records remain intact, prior claimed inference is not resubmitted, and model actions stay blocked until ownership/readiness and required re-arming are verified

### Requirement: Model journeys are discoverable and explicit
The dashboard MUST expose descriptive review, note extraction and amendment review through normal task-focused controls. It MUST distinguish preview, request, running, stale, rejected, cancelled, accepted and confirmed/applied states. Inspection and navigation MUST NOT send model requests. Unavailable capabilities MUST explain their blocker without requiring manual internal-ID discovery.

#### Scenario: Owner opens a saved model result
- **WHEN** the owner navigates from its associated evidence, note, plan or job history
- **THEN** the matching result and its evidence/state are shown without another generation

#### Scenario: Mac-local model cannot run
- **WHEN** admission or runtime health is unavailable
- **THEN** factual history, manual editing and saved-result inspection remain usable and the UI does not pretend an AI result exists

### Requirement: Owner controls feedback and operational actions
The dashboard MUST identify experimental output, allow source-linked owner feedback, and distinguish read-only status from explicit cancellation or service management. It MUST show per-job limits without implying the whole application expires. Feedback MUST NOT silently retrain a model, become verified evidence or execute another job.

#### Scenario: Owner reports an incorrect interpretation
- **WHEN** feedback is explicitly saved
- **THEN** it is retained as attributed feedback linked to the original immutable result, not a corrected historical model response or an automatic retry
