# Factual historical trends and descriptive input summaries

Status: tasks 4.1–4.3 implemented with synthetic tests. Protected release/physical
Pi chart verification is pending. The Pi currently runs the verified import
release with an empty database; see [read-only-import.md](read-only-import.md).
No real records, model requests, or hosted transfers are part of development.

## Private chart workflow

Sign in through the existing Mac SSH tunnel. Under **Historical trends**, select
first/last dates and an optional activity sport, then **Show period / refresh**.
The initial selection is 28 inclusive UTC-calendar dates ending today. Activity
records are grouped by their preserved source-local start date, not reassigned to
the UTC instant's day; wellness uses its source date. The UI states this basis.
Changing period/sport only reads the private database: it never imports upstream
or calls a model. Use the separate import controls when a source read is wanted.

Each selected period is compared with its immediately preceding **equal-length**
period. Ranges are inclusive, ordered, no later than today, and 1–366 dates. Both
periods are read consistently with their category metadata under the store lock;
more than 50,000 combined records fails with a shorter-period request, never a
silently truncated comparison. A selected sport filters activities only: wellness
is not sport-specific and remains independently visible. Import completion or
local removal refreshes the charts; outdated chart values are hidden immediately
while reloading, and sign-out removes the private view.

## Arithmetic, source evidence, and missing values

- Sport views show observed imported activity count, moving seconds, elapsed
  seconds, recorded activity kcal, and separately named **Intervals.icu activity
  load**. Moving and elapsed time are distinct **recorded activity time**, never
  Garmin intensity minutes. Missing moving time is not replaced with elapsed time.
- Activity metrics sum populated values. All-missing duration/calories/load is
  unavailable, not zero. Zero imported-activity count means no observed imports,
  not verified inactivity. Numeric zero from a record remains an actual zero.
- Wellness shows recorded weight (kg), VO2 max (mL/kg/min), HRV/SDNN (ms), sleep
  duration (seconds), body fat (%), resting HR (beats/minute), source sleep score,
  steps, and separately labeled **Intervals.icu ATL/CTL** when populated. Period
  means weight each measured source record equally, **not each date**. Different
  sources remain distinct; no guessed cross-source deduplication occurs.
- Duration headlines/tooltips express hours for readability; plot axes and source
  evidence explicitly retain seconds. Other values display original units.
- Charts plot observed data only: activity bars are daily sums on dates with
  records; wellness dots are separate measurements. They neither fill absent dates
  with zero nor connect/interpolate gaps. Known zero bars remain at the baseline.
- **Show evidence** lists every supporting date, original value/unit, intermediary,
  source record ID, explicit metric origin, separate record-origin labels, and
  preserved time context. Evidence is paginated, not silently discarded. A Garmin
  record label never supplies a missing metric origin or proves a scale's origin.
- Absolute and percentage changes use the same named metric/aggregation and
  equal-length periods. A missing side yields no change; a zero previous value
  yields no percentage. Sparse samples and partial value/date coverage are visible.
  These are arithmetic observations, not confidence, causation, diagnosis, sport
  clearance, or a workout prescription.
- Fitness age, endurance score, and Garmin training status remain explicitly
  unavailable, never estimated from load, VO2 max, or another field. Any other
  unpopulated metric likewise remains unavailable.
- Category read outcomes, latest observed dates, and current observed age are
  separate from unknowable Garmin-to-Intervals.icu freshness. An observed date gap
  does not establish missed activity, illness, or a particular upstream failure.
  Retained factual charts remain readable through a source/key failure.

## Reproducible summary boundary (no model integration)

Authenticated `GET /api/trends?oldest=YYYY-MM-DD&newest=YYYY-MM-DD[&sport=...]`
returns the report and source evidence. Authenticated
`GET /api/analysis-input` with the same query returns a version-1 compact summary:

- Ordered facts for each period/sport/metric with aggregation, value or absence,
  units, exact selected/observed dates, sample/date counts, intermediary and known
  origins, and unknown-origin counts.
- Descriptive comparisons with their sample counts and missing/sparse/partial
  flags; source read metadata stays independent of observed record coverage.
- Per-metric observed gaps, mixed-source/origin flags, and unsuccessful latest
  category reads, without attributing a cause or fabricating an absent score.
- Evidence IDs identify corresponding series in the private report, whose points
  retain original source identities and time context. SHA-256 of the serialized
  matching report binds the summary to that snapshot. Reordered input records
  give the same summary/hash at the same evaluation date and metadata. If records
  or metadata change between separate HTTP requests, their snapshots can differ;
  never treat evidence from a different hash as matching.
- No raw record IDs, activity names, event notes, transport JSON, credentials, or
  individual point values are repeated in the compact summary. Only necessary
  aggregate facts/provenance are included. Report/point/fact/summary object
  stringification is redacted and private responses disable caching.

Both endpoints are local computations, **not inference or consent to inference**.
No model client exists. The failed small-model candidates are not selected as
defaults; the user must select an acceptable alternative before model integration.
Charts explicitly report that AI is unavailable and remain independent of it.
A later hosted option still requires provider-specific informed consent and no
silent fallback; a summary is sensitive health data even without raw record IDs.

Synthetic tests cover known totals/means/comparisons, source-local dates, leap
years, inclusive bounds, actual zeros, absent values, invalid units/nonfinite
values, multi-source same-day measurements, per-metric stale gaps, source failure,
privacy/summary reproducibility, authenticated API access, UI empty/error/stale
states, evidence pagination, and clearing/cancelling obsolete chart data. The
immutable ARM64 image gate additionally checks empty private trends and summary
endpoints using only synthetic credentials, with no upstream/model read.
