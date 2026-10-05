# Spec Delta

## ADDED Requirements

### Requirement: User manages attributed context and review feedback
The dashboard MUST let the authenticated user inspect, add, correct, retire, and delete attributed feedback/context and record usefulness ratings or corrections for reviews. It MUST distinguish source attribution and applicability, and disclose backup-retention limits for deleted content.

#### Scenario: User corrects a reflection
- **WHEN** the user edits a saved reflection
- **THEN** the dashboard shows the active corrected value and its correction history without presenting a superseded version as current

#### Scenario: User removes a context entry
- **WHEN** the user deletes an entry
- **THEN** the dashboard confirms active deletion and warns that retained encrypted backups may expire later under normal retention

### Requirement: User can inspect context and evidence used by review
For a connected review, the dashboard MUST separately present code-generated facts, attributed reports, model interpretations, uncertainty/questions, and unavailable or conflicting context. It MUST expose evidence dates/source and the exact context entries included so the user can detect missing, stale, or incorrectly attributed information.

#### Scenario: User inspects a connected review
- **WHEN** a connected review is available
- **THEN** the user can inspect the evidence and context snapshot behind each section and distinguish user reports from measured records and model interpretations

#### Scenario: Context is corrected after review
- **WHEN** an included context entry is corrected or deleted after a review
- **THEN** the dashboard marks dependent review output stale or removes/redacts it according to the context deletion behavior

### Requirement: User rates review usefulness
The dashboard MUST let the user submit a usefulness rating or correction for a connected review and show that it is stored as attributed feedback, not as a measured fact or model training.

#### Scenario: User submits review feedback
- **WHEN** the user rates a review or enters a correction
- **THEN** the dashboard confirms feedback was saved with a link to the reviewed snapshot
