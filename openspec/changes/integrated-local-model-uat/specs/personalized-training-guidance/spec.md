# Personalized Training Guidance Spec Delta

## ADDED Requirements

### Requirement: Experimental owner UAT is not usefulness qualification
The system MUST require explicit owner acceptance of unqualified local-UAT use. This is a narrow exception to the personal qualification prerequisite, not a qualified status or synthetic-development grant. Frozen contract evaluation MUST precede calls; independent human qualification remains required for qualified or ordinary admission. Automatic model execution and hosted transfer remain outside initial UAT permission.

#### Scenario: Owner evaluates the model with familiar personal records
- **WHEN** the installed local-UAT policy and runtime are accepted and the authenticated owner explicitly requests a permitted job
- **THEN** the job can use selected personal evidence as experimental UAT while qualification remains unqualified and no broader production permission is granted

#### Scenario: A prior experiment passed structural checks
- **WHEN** synthetic, legacy or owner-UAT results exist without independent human qualification
- **THEN** none of those results is relabeled as a qualified model or used to open ordinary or automatic model admission

### Requirement: Descriptive UAT links interpretation to imported evidence
The system MUST let the owner explicitly request a local descriptive review of an exact factual snapshot, inspect validated source-linked output, and record attributed feedback. The workflow MUST retain source dates, uncertainty, missing coverage and applicable context. It MUST NOT prescribe training, diagnose illness, claim readiness or invent unavailable Garmin scores.

#### Scenario: Owner compares a review with Garmin records
- **WHEN** a validated model result is available
- **THEN** its evidence and date coverage can be inspected alongside factual calculations without copying internal IDs or treating model interpretation as a measurement

### Requirement: Intake UAT preserves explicit confirmation
The system MUST let the owner explicitly request suggestions for a saved note, inspect accepted source-bound suggestions, resolve unknowns, stage selected edits and confirm them. Model execution, inspection, editing and staging MUST NOT create authoritative context or calendar items. A source-note revision MUST invalidate older source-bound work. Staged suggestion edits MUST preserve immutable model output and remain attributed owner edits, confirmable only against the unchanged source revision.

#### Scenario: Model leaves training times unknown
- **WHEN** the owner supplies dates, times, timezone or recurrence during review
- **THEN** those details are attributed to the owner's edit, not to the immutable original model output, and writes occur only on explicit confirmation

#### Scenario: Owner corrects the original source note
- **WHEN** a source-note revision changes after suggestion preparation or generation
- **THEN** the older source-bound work cannot publish or confirm against the revised note, unlike staged suggestion edits against an unchanged note

### Requirement: Amendment UAT selects only permitted authored-plan changes
The system MUST generate amendment candidates in code from an existing authored plan, current evidence and explicitly enabled permissions. The model can select only allowed candidates and fixed explanation choices. The owner MUST inspect the exact current diff and explicitly approve it before local plan changes. No initial plan generation, autonomous application or upstream write-back is allowed.

#### Scenario: Model proposes a change outside the permitted set
- **WHEN** a response invents a session or candidate, exceeds a permission, or refers to stale constraints
- **THEN** the proposal is rejected and the selected plan is unchanged

#### Scenario: Owner approves a current exact diff
- **WHEN** the authenticated owner approves the validated current proposal
- **THEN** only that exact allowed local change is applied atomically with replay safety
