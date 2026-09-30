# Decision: Intervals.icu-first, replaceable read-only ingestion

Status: accepted for phase one. Verified against the change's ingestion and
data requirements and the read-only inspection in
[`../data/intervals-coverage.md`](../data/intervals-coverage.md).

## Source and privacy boundary

Use the user's existing Garmin Connect to Intervals.icu connection and read the
documented Intervals.icu activities and wellness endpoints. This avoids handling
Garmin login credentials in the app. Intervals.icu is an intermediary and an
additional external holder of personal activity and health data, not direct
Garmin access. Its availability and upstream synchronization are separate
dependencies.

The source is suitable for phase-one trends based on the observed populated
fields, subject to per-record validation. It does not expose verified Garmin
parity: fitness age, endurance score, and Garmin training status remain
unavailable. Wellness and scale origin remain unverified. Activity durations
must be called recorded activity time; Intervals.icu load measures must retain
their own labels.

Official Garmin API approval is not assumed or required. Do not introduce an
unofficial Garmin-login worker or infer eligibility from another application's
Garmin access. User-controlled exports or other explicitly approved sources may
be implemented later behind the same boundary.

## Replaceable application boundary

- Implement the production adapter in Kotlin. The Python coverage utility is
  only a development-time inspection tool, not a production ingestion worker.
- Expose source-neutral activity and wellness retrieval results with separate
  per-category success/failure and validation outcomes. Keep Intervals.icu JSON,
  authentication, and endpoint details inside its adapter.
- Preserve source identity, source record ID, upstream origin when supplied,
  observed time/interval, units, and known time-zone/offset information in
  normalized records. Do not infer missing metric origins or named time zones.
- Treat `(source, source record ID)` as the idempotent import identity. Source
  replacement must not duplicate imported records silently; future cross-source
  deduplication requires an explicit policy rather than guessing equivalence.
- Keep app-owned event IDs and storage independent of imported record identity.
  Changing the data source or deleting local imports must not require entering
  events again and must not modify Garmin Connect or Intervals.icu.
- Perform only upstream GET requests for phase-one synchronization. An API key
  may permit upstream writes; read-only behaviour is enforced by this app, not
  assumed to be guaranteed by the key's permissions.
- Report an Intervals.icu read failure separately from observed missing/recent
  data. Do not claim a Garmin synchronization failure when its status cannot be
  verified. Charts and local events must remain usable during source failures.

## Credential lifecycle

The current development key is in a user-managed private file outside Git.
Do not copy it into fixtures, reports, prompts, logs, process arguments, or
deployment manifests. Provision the eventual app secret outside Git or encrypt
it with SOPS/age, with decryption keys outside the repository. CI must receive
neither the Intervals.icu key nor cluster credentials.

If a key is exposed, revoke/regenerate it in Intervals.icu and replace the local
secret; do not reuse the exposed value. After rotation, reload the app's secret
through its documented mechanism and retry a user-initiated read. Revocation or
HTTP authentication failure must request replacement without revealing the key
or HTTP body. Retain existing local records, events, and a visible failed-sync
state rather than deleting history or attempting an upstream write. A 403 can
also reflect client/request filtering, so do not assert a credential is invalid
solely from that status.

## Verification and remaining work

- The source inspection made only GET calls to the two configured endpoints;
  both succeeded via `curl` with the supplied private key.
- Its tests verify credential delivery through stdin rather than command-line
  arguments, no redirect following, withholding error bodies, fixed field-name
  output, and separation of missing values from zero values.
- The written coverage report flags unavailable Garmin-only scores, uncertain
  wellness/scale origins, and unverified upstream freshness.
- This decision matches `garmin-data-ingestion`, `training-health-data`, and
  the phase-one descriptive-analysis boundary. Production adapter,
  revoked-key, normalization, idempotency, local deletion, and app log tests
  remain implementation tasks; this decision does not claim those are done.

References:
- [Intervals.icu API access guide](https://forum.intervals.icu/t/api-access-to-intervals-icu/609)
- [API integration cookbook](https://forum.intervals.icu/t/intervals-icu-api-integration-cookbook/80090)
