# Spec Delta

## Purpose

Connect the user's available Garmin-derived activity and wellness trends from Intervals.icu into model-assisted observations with traceable evidence, without prescribing workouts or judging readiness for sport-specific hazards.

## ADDED Requirements

### Requirement: Trend summaries use validated history
The system MUST summarize activity by sport and available wellness metrics over a selected time range using validated records. It MUST identify the covered dates, units, source, and missing or stale data, and MUST NOT invent unavailable proprietary Garmin scores or mislabel Intervals.icu-derived measures as Garmin measures.

#### Scenario: User looks at a sport trend
- **WHEN** the user selects a time period and sport
- **THEN** the system shows observed activity for that sport over that period with the data coverage indicated

#### Scenario: Score is unavailable
- **WHEN** the selected source does not provide fitness age, endurance score, or another requested metric
- **THEN** the system marks it unavailable and does not derive a value while labeling it as the Garmin metric

### Requirement: Phase-one analysis is descriptive
The system MUST present historical comparisons as observations, not as medical conclusions, sport-safety clearance, or prescriptions for training intensity. It MUST NOT generate or auto-apply a weekly training plan in this change.

#### Scenario: Trend changes over time
- **WHEN** the system compares two periods with available activity or health records
- **THEN** it shows the observed change and underlying dates without prescribing a training intervention

#### Scenario: Record coverage is incomplete
- **WHEN** the chosen time period lacks enough records for a meaningful comparison
- **THEN** the system identifies the data gap instead of claiming a confident personal trend

### Requirement: AI connects observed metrics with traceable evidence
The system MUST let the user request a plain-language summary connecting trends across available activity and health metrics. Each claim about the user's history MUST cite the metrics and time periods used, distinguish an observed association from a cause, and disclose missing or stale inputs. Model output MUST be checked against the supplied data before presentation; unsupported claims MUST NOT be shown as facts.

#### Scenario: Several metrics are available
- **WHEN** the user requests an AI summary for a period with activity, sleep, and HRV records
- **THEN** the system presents a cross-metric observation tied to those available records and their dates without claiming one metric caused another

#### Scenario: Model invents a missing score
- **WHEN** the model describes a Garmin fitness age that the source did not provide
- **THEN** the system rejects or removes that claim and does not label an invented value as Garmin data

#### Scenario: Model cannot make a grounded summary
- **WHEN** the model is unavailable or returns an unsupported or unusable summary
- **THEN** the system reports that AI analysis is unavailable while leaving factual charts accessible

### Requirement: User can choose a model without silent data transfer
The system MUST let the user select a supported AI model without tying the trend view to one provider. Local inference MUST be preferred where usable. Before transmitting any personal health or activity records to a non-local provider, the system MUST disclose that transfer and obtain explicit consent. The system MUST NOT log sensitive model inputs or model credentials or silently fall back to a hosted provider.

#### Scenario: User selects a supported local model
- **WHEN** the user chooses an available local model for trend analysis
- **THEN** the system processes the supplied history locally and identifies the selected model

#### Scenario: User switches supported models
- **WHEN** the user selects another configured model
- **THEN** subsequent summaries use that selection without changing stored Garmin records

#### Scenario: Hosted model has no consent
- **WHEN** the selected model would send personal data outside the local environment and consent has not been granted
- **THEN** the system sends nothing, explains the transfer, and asks the user to decide
