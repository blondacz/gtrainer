# Garmin Data Ingestion Specification

## Purpose

Retrieve Garmin-derived activity and wellness records through the user's linked Intervals.icu account, while keeping the source replaceable and sync limitations visible.

## Requirements

### Requirement: Garmin source integration is replaceable
The system MUST initially read the user's available Garmin-derived records from Intervals.icu through its API, behind a replaceable source adapter. It MUST identify Intervals.icu as an intermediary, not as direct Garmin access, and MUST allow a future source without requiring the user to re-enter events. It MUST NOT claim that every Garmin score is available through this intermediary.

#### Scenario: Intervals.icu is selected
- **WHEN** the user configures the Intervals.icu adapter after checking actual record coverage
- **THEN** the system retrieves its supported Garmin-derived activity and wellness records and identifies Intervals.icu as the source

#### Scenario: Intervals.icu cannot provide a category
- **WHEN** a requested Garmin metric is not available from Intervals.icu
- **THEN** the system identifies that category as unavailable rather than presenting a substitute Garmin value

### Requirement: User can control Intervals.icu synchronization
The system MUST let the user initiate a read-only Intervals.icu sync and MUST expose its last attempt, completion state, and supported record categories. API credentials MUST be handled as secrets and MUST NOT appear in logs, the UI, or Git. The system MUST distinguish failure to read Intervals.icu from an upstream Garmin-to-Intervals.icu delay when the latter cannot be verified.

#### Scenario: User requests a sync
- **WHEN** the user starts synchronization for a configured Intervals.icu source
- **THEN** the system reports progress or completion and identifies which supported categories were retrieved or failed

#### Scenario: API key fails
- **WHEN** the Intervals.icu API key is invalid or revoked
- **THEN** the system requests a replacement without disclosing the key

#### Scenario: Upstream freshness cannot be confirmed
- **WHEN** the Intervals.icu API responds but expected recent Garmin records are absent
- **THEN** the system reports the observed data gap without claiming that Garmin synchronization definitely failed

### Requirement: Retrieval is read-only
The system MUST NOT modify, delete, or upload records to the user's Garmin account or Intervals.icu as part of synchronization.

#### Scenario: Sync retrieves records
- **WHEN** the system synchronizes Intervals.icu data
- **THEN** it only reads from Intervals.icu and changes no upstream account data
