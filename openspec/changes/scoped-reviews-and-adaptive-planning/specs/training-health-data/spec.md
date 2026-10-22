# Spec Delta

## ADDED Requirements

New factual field and provenance requests are served through the canonical scoped preparation interface; preservation of old measurement and qualification evidence does not require preserving retired HTTP routes.

### Requirement: Personal signal inputs preserve measurement compatibility
New-profile baseline and deviation inputs MUST distinguish field, unit, intermediary, upstream origin when known, measurement method/basis when verified, observation date and receive revision. Data MUST NOT be called compatible solely because units match. Unknown required semantics MUST remain unavailable; proprietary scores and different HRV methods MUST NOT be blended into a universal readiness measure.

#### Scenario: HRV and HRV SDNN share units
- **WHEN** both measurements are expressed in milliseconds
- **THEN** they remain separate signal identities and are not interchangeable baseline samples

### Requirement: Associated wellness and proprietary readiness retain source meaning
Optional activity wellness MUST preserve source observation dates, verified association semantics and receive provenance separately from session timestamps. A daily source date MUST NOT alone establish a preceding-night relationship or pre-activity availability. Training readiness MUST remain an attributed vendor observation only when supplied by a verified supported adapter; it MUST NOT be reconstructed from sleep/HRV or assumed to be fully described by those inputs. Independently available sleep and HRV MUST remain separately identifiable even when readiness is supplied. Unknown required mapping or unavailable fields MUST remain explicitly unverified/unavailable.

#### Scenario: Readiness and component observations are supplied
- **WHEN** a verified adapter supplies a dated readiness score alongside sleep and HRV
- **THEN** all three retain their own provenance and dates without asserting a known score formula or replacing the separate observations

#### Scenario: Sleep record arrives after a session
- **WHEN** a sleep observation is imported after the selected activity
- **THEN** the receive provenance remains visible and the system does not claim the measurement was known before the session solely from its source date

### Requirement: Daily signal coverage is prepared without duplicate weighting
New personal signal rules MUST use at most one explicitly selected compatible observation per metric/source/day, or mark unresolved duplicates ambiguous. Their baseline MUST expose included dates, selection policy, missing dates and freshness. Existing per-record trend arithmetic MUST remain unchanged for legacy contracts; missing values MUST NOT become zero or evidence of illness.

#### Scenario: Two candidate observations exist for one date
- **WHEN** the configured source selection cannot resolve them deterministically under verified semantics
- **THEN** that day's personal signal is ambiguous and is not double-weighted

### Requirement: New evidence preparation does not imply upstream completeness
New session and daily evidence MUST retain observed import coverage and read status separately from upstream completeness. Reviews and trigger rules MUST disclose unknown upstream freshness. Corrections and repeated unchanged imports MUST preserve stable identities and MUST NOT create duplicate sessions or repeatedly trigger adaptation.

#### Scenario: Successful intermediary read has no recent activity
- **WHEN** Intervals.icu returns successfully but expected activity is absent
- **THEN** the absence is a data gap, not proof of skipped training or a verified Garmin synchronization failure
