# Spec Delta

## Purpose

Let the user keep private, attributed coaching context and feedback over time, correct it, and control how it informs a requested review.

## ADDED Requirements

### Requirement: Context records retain attribution and applicability
The system MUST store user-entered feedback and context with stable identity, author/source category, entering user, observation date, and known applicability dates and sport/activity/review links. It MUST distinguish measurements, user reports, user-entered clinician or coach guidance, and model interpretations. Context topic MUST remain separate from source category. Topic categories are restriction, symptom, goal, preference, feedback, and note. Source categories are user report, user-entered clinician guidance, user-entered coach guidance, and review feedback. Measurements remain imported evidence; model interpretations remain review output, not user-entered context. User-entered attribution MUST NOT imply independent verification of a clinician, coach, or source.

#### Scenario: User records clinician-provided restriction
- **WHEN** the user saves a restriction attributed to clinician guidance
- **THEN** the system records the user as the entering party, preserves the stated source attribution, and does not claim to have verified the clinician

#### Scenario: Feedback links to an activity
- **WHEN** the user records a reflection linked to an imported activity and sport
- **THEN** the system preserves those stable references and the reflection's observation date separately from activity measurement data

### Requirement: User controls context lifecycle
The system MUST let the user inspect, correct, supersede, retire, and delete context. Corrections MUST preserve attribution and supersession history. Retired, expired, or superseded entries MUST NOT appear as current retrieved context. Deletion MUST remove an entry and dependent versions from active retrieval and derived indexes, invalidate in-flight reviews that used them, and remove stored review content that reproduces deleted context. The interface MUST disclose that encrypted backups may retain deleted data until normal retention expires.

#### Scenario: User corrects a context record
- **WHEN** the user corrects a previously saved reflection
- **THEN** the system preserves the prior version as superseded and uses only the corrected version for future retrieval

#### Scenario: User deletes context used in a review
- **WHEN** the user deletes a context record referenced by a stored or running review
- **THEN** the system excludes it from future retrieval, invalidates the running review, and removes or redacts dependent stored review content without deleting imported activity records

#### Scenario: Encrypted backup still contains deleted record
- **WHEN** a deleted record remains in a retained encrypted backup
- **THEN** the system's deletion notice states that active data is removed but backup copies expire only under documented retention

### Requirement: Retrieved context is bounded and explainable
The system MUST retrieve a bounded context set using explicit applicability filters such as date, sport, activity, or linked review, and MUST identify the entries included in a review. Applicable active structured restrictions MUST be included independently of optional relevance ranking. Initial restriction kinds are blocked activity/sport, allowed activity/sport, and maximum duration with unit. Superseded, retired, expired, deleted, and out-of-scope entries MUST be excluded. Conflicting applicable restrictions MUST be shown together in a dedicated unresolved-conflicts pane, not silently resolved by the system or model. A connected review MUST be blocked until the user resolves applicable restriction conflicts. The deterministic factual dashboard MUST remain usable.

#### Scenario: Review has applicable restriction and optional reflections
- **WHEN** an applicable structured restriction and several relevant reflections exist
- **THEN** the restriction is always included, optional context is bounded and filtered, and the review identifies the selected entries

#### Scenario: Applicable restrictions conflict
- **WHEN** two active applicable restrictions conflict
- **THEN** the system identifies the conflict and does not let a model choose which restriction to follow

#### Scenario: User reviews conflicting restrictions
- **WHEN** applicable restrictions conflict
- **THEN** the dashboard shows both restrictions, their attribution, scope, and applicability in a dedicated pane; connected review remains blocked until the user resolves the conflict

#### Scenario: Free-text reflection contains instructions
- **WHEN** retrieved user text contains instructions directed at a model
- **THEN** the system treats it only as attributed context, not as system or developer instruction

#### Scenario: Mandatory restrictions exceed packet limit
- **WHEN** all applicable mandatory restrictions cannot fit within the hard connected-review packet limit
- **THEN** the system sends no inference request, reports the overflow, and keeps deterministic factual views usable

### Requirement: User feedback is durable and attributed
The system MUST let the user record a dated usefulness rating or correction for a generated review and link it to that review and its evidence/context snapshot. Such feedback MUST remain a user report and MUST NOT silently train model weights or become a measured fact.

#### Scenario: User rates review usefulness
- **WHEN** the user rates a review or adds a correction
- **THEN** the system stores it as attributed feedback linked to that review snapshot for later user-controlled retrieval
