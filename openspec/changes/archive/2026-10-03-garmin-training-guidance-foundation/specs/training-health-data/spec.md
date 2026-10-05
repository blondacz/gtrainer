# Spec Delta

## Purpose

Make imported activity and wellness records consistent and trustworthy enough for historical trends, without losing their intermediary source, units, or time context.

## ADDED Requirements

### Requirement: Imported records retain source and time context
The system MUST represent supported activities and wellness measurements with their Intervals.icu source ID, upstream origin when supplied, observed time or interval, and unit where relevant. The system MUST preserve the source time-zone or offset when available and MUST distinguish unavailable values from zero values. Candidate Intervals.icu fields observed in the user's account include activity type and duration, calories, weight, HRV, sleep, steps, resting heart rate, and VO2 max; actual field population and origin MUST be verified before presentation. Calculated activity duration and Intervals.icu training-load fields MUST remain distinct from Garmin intensity minutes and proprietary Garmin scores.

#### Scenario: Record includes source time-zone information
- **WHEN** an imported record includes a local time and time-zone or offset
- **THEN** the system preserves the time context and makes the record's time unambiguous for analysis

#### Scenario: Measurement value is missing
- **WHEN** an imported measurement has no value for a field
- **THEN** the system represents that field as unavailable rather than as zero

#### Scenario: Activity time is computed from records
- **WHEN** the system sums recorded activity duration
- **THEN** it labels the result as recorded activity time rather than Garmin intensity minutes

#### Scenario: Intermediary score is present
- **WHEN** Intervals.icu supplies a training-load value
- **THEN** the system labels it as an Intervals.icu measure rather than Garmin fitness age, endurance score, or training status

### Requirement: Repeated synchronization does not duplicate records
The system MUST identify repeated source records and MUST avoid creating duplicate activities or measurements when the same source record is synchronized more than once.

#### Scenario: Same record appears in a later sync
- **WHEN** a previously imported source record is retrieved again
- **THEN** the system updates or retains the existing record according to source identity without creating a duplicate

### Requirement: Imported data quality is visible
The system MUST validate imported records before making them available to trend analysis and MUST surface rejected or incomplete records and their source category in sync results.

#### Scenario: Record fails validation
- **WHEN** an imported record is missing required fields or contains an invalid value or unit
- **THEN** the system excludes it from trend analysis and reports the validation outcome without silently treating it as valid data

### Requirement: User can review and remove imported data
The system MUST let the user review available imported record categories and remove imported Garmin records from the system without changing Garmin Connect.

#### Scenario: User removes imported Garmin records
- **WHEN** the user requests removal of imported Garmin data
- **THEN** the system removes those local records from subsequent views and trend analysis while leaving the Garmin account unchanged
