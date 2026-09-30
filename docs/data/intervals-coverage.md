# Intervals.icu source coverage

Read-only inspection on 2026-09-30, requesting the previous 90 days through
`GET /api/v1/athlete/0/activities` and `GET /api/v1/athlete/0/wellness`.
The inclusive date range yielded 91 wellness days. Credentials and raw records
were kept in memory only; this report contains coverage metadata, not personal
metric values, activity names, IDs, exact activity dates, or health payloads.

## Observed populated fields

These are non-null coverage counts, not proof of valid units, sensor accuracy,
or independent measurements on every date. Validate and normalize records in the
adapter before charts or AI use; do not use this report as an AI health input.

| Activity field | Non-null records / 76 |
| --- | --- |
| Source ID, sport, local and UTC start date | 76 each |
| Moving time, elapsed time | 76 each |
| Calories, average heart rate | 76 each |
| Distance | 65 |
| Intervals.icu activity training load (`icu_training_load`) | 76 |
| Named time zone (`timezone`) | 0 |
| Source (`source`) | 76 |

Activities span 53 distinct observed days. The latest observed activity date
was within one day of inspection. The recognized source label is
`GARMIN_CONNECT`; preserve each record's source rather than assuming all future
activities share that origin. Both local and UTC dates are present, but a named
time zone was not populated; do not invent one or confuse local time with UTC.

| Wellness field | Non-null records / 91 |
| --- | --- |
| Weight | 70 |
| Body fat | 29 |
| HRV (`hrv`) | 89 |
| HRV SDNN (`hrvSDNN`) | 0 |
| Resting heart rate | 91 |
| Sleep duration (`sleepSecs`) | 90 |
| Sleep score | 89 |
| Steps | 91 |
| VO2 max (`vo2max`) | 14 |
| Intervals.icu load (`atl`, `ctl`) | 91 each |
| Update metadata (`updated`) | 91 |

Wellness records cover 91 distinct dates, with the latest observed date within
one day of inspection. This does not establish freshness for each measurement:
fields can be missing, carried forward, manually entered, or updated separately.

## Provenance and unavailable data

- Activities expose a recognized Garmin Connect origin. The checked wellness
  source fields (`source`, `sources`, `weightSource`, `bodyFatSource`,
  `hrvSource`) were absent. Wellness metric origin is therefore **unverified**.
- Weight and body-fat values exist, but their scale/device origin and automatic
  sync from the user's scale are **unverified**. Do not present them as verified
  Garmin-scale measurements.
- The candidate fields `fitnessAge`, `enduranceScore`, and `trainingStatus`
  were absent from both responses. This inspection does not prove Garmin itself
  lacks these metrics, or that no alternate source/field could supply them.
  This adapter must show these Garmin-only scores as unavailable unless a later
  verified source supplies them.
- Moving/elapsed activity time is recorded activity time, not Garmin intensity
  minutes. `icu_training_load`, `atl`, and `ctl` are Intervals.icu measures, not
  Garmin proprietary scores.
- HTTP 200 proves this key can read the requested Intervals.icu endpoints. It
  does not prove upstream Garmin synchronization succeeded or the user's entire
  history is available. Coverage before the requested window was not checked.
- The initial Python HTTP client received HTTP 403; the same endpoints and key
  succeeded through `curl`. The exact cause was not established. The inspection
  utility now uses `curl` without putting authorization into process arguments
  and does not follow redirects.

## Repeat safely

The utility is an inspection tool, not a Python production ingestion worker.
The production source adapter remains Kotlin/Ktor as planned.

```sh
python3 -m unittest discover -s scripts -p 'test_*.py'
python3 scripts/inspect_intervals_coverage.py --key-file "$HOME/.intervals/api_key"
```

The key file must deny group/other access (`chmod 600`) and remain outside the
repository. Only predefined field names, coverage counts, recognized origin
labels, and coarse freshness metadata are printed. Unknown free text, raw
records, key contents, and HTTP error bodies are never printed. Do not enable
shell tracing or curl verbose output. Failures leave this report as a historical
snapshot rather than silently claiming that today's source is healthy.
