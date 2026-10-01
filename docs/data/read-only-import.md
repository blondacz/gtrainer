# Read-only import and private historical storage

Status: tasks 3.1–3.4 implemented and tested with synthetic fixtures. Deployment
and the first real app import are pending the new immutable-image rollout. The
prior private coverage check is documented in [intervals-coverage.md](intervals-coverage.md);
it is not evidence that this app has imported those records.

## User workflow

1. Start `bash scripts/pi-tunnel.sh` on the Mac, open `http://127.0.0.1:8080`,
   and sign in. The password is in your private
   `~/.config/gtrainer/dashboard-password` file; never paste it into chat/CI/Git.
2. Choose first/last dates under **Garmin-derived history through Intervals.icu**,
   then choose **Read Intervals.icu**. Nothing imports automatically at startup.
   The initial suggested range is 90 days; each read accepts at most 366 days.
   Earlier years/ranges can be imported separately and remain retained.
3. Review activities and wellness independently: completion/error, stored count,
   latest attempt, latest successful read, rejected/incomplete record counts,
   and latest observed record date. An old latest record is an observed coverage
   gap, not proof of a missed activity, illness, or upstream sync failure.
4. **Remove local imports** requires a valid session, Origin, CSRF header, and
   explicit confirmation. It clears local imported records and read-status metadata,
   never app events or the Garmin/Intervals.icu accounts. Retained encrypted backups
   are not erased. This is not forensic SD-card or backup erasure.

Trend charts, AI observations, and event editing are later tasks. The private
`GET /api/history?oldest=YYYY-MM-DD&newest=YYYY-MM-DD` endpoint currently provides
normalized records for their upcoming UI; public HTML contains no history.
All private data responses disable caching. Neither source records nor model
prompts are bundled in the public software image.

## Source and credential boundary

The app uses only GET on fixed HTTPS endpoints
`intervals.icu/api/v1/athlete/0/activities` and `/wellness`, authenticated with
the externally mounted source key. The key can permit writes upstream, but this
adapter contains no upstream write method. It never follows redirects and sends
no data to a model. Intervals.icu already holds the linked Garmin-derived data;
this is an additional intermediary, not direct Garmin access or official Garmin
API approval.

The replaceable `HistorySource` interface returns typed normalized records and
category results, not Intervals.icu JSON/HTTP/authentication details. Source JSON
is transient and only selected validated fields are retained; activity names,
descriptions, raw responses, and credentials are not stored. Record/measurement,
source-credential, response, and request object stringification is redacted.
No request-body, HTTP-client, sensitive prompt, or credential logging is enabled.

Each request reloads the projected source-key file. Rotate/revoke at Intervals.icu,
privately update the Mac key file, and use the explicit provisioning `--rotate`
workflow in [private-access.md](../infrastructure/private-access.md). Projected
files refresh asynchronously. Restarting the app also reloads the password
verifier and invalidates sessions. Never reuse the previously exposed key.

HTTP 401 is reported as key rejection and requires replacement/retry. HTTP 403
is access denial, which may also reflect filtering; it is not asserted to prove
revocation. Rate limits, redirects, network failures, malformed/oversized payloads,
and provider failures have fixed separate statuses without response-body/key
disclosure. A failed/empty read does not erase earlier records; the last successful
read and the latest attempt remain separate. Missing/unreadable source credentials
disable source retrieval, not authenticated access to stored history.

## Records, units, and unavailable scores

`(source, source record ID)` is the SQLite primary key. Repeated reads update or
retain that record; they cannot create duplicates. Different sources remain
distinct, with no guessed cross-source deduplication. The wellness API's date ID
is preserved as its identity. Storage/schema version 1 keeps imported categories,
read-status metadata, migration records, and independent app-owned events.

- Activities retain sport, local timestamp, explicit instant/offset when known,
  validated named time zone and original zone label, known upstream source labels,
  moving/elapsed seconds, kcal, metres, beats/minute, and separately named
  **Intervals.icu load** when provided.
- Moving/elapsed time is **recorded activity time**, never Garmin intensity minutes.
  Missing moving time is not fabricated from a Garmin intensity-minute score.
- Wellness retains provided weight (kg), body fat (%), HRV/SDNN (ms), resting heart
  rate (beats/minute), sleep seconds/source sleep score, steps, VO2 max (mL/kg/min),
  and separately named Intervals.icu ATL/CTL load. API field population and known
  origin limitations remain as in the private coverage report.
- Metric origin is only attached when its explicit API origin field identifies
  a known source. An activity's Garmin label does not identify the scale or imply
  every wellness metric came from Garmin. Unknown origins stay unknown.
- Absent/null values stay absent, while numeric zero stays zero. No fitness age,
  endurance score, or Garmin training status is derived or substituted from load,
  VO2 max, or another metric. Those proprietary scores remain unavailable.
- Numeric values must be finite, nonnegative, correctly typed, and within explicit
  transport-validation bounds. Durations/sleep/steps must be integral. Activity
  moving time cannot exceed elapsed time. Invalid IDs, dates, numeric values,
  conflicting timestamps, or out-of-request-range records are counted as rejected,
  without echoing their values. Bounds are validation limits, not medical judgments.
- Explicit UTC/offset timestamps are preserved, not guessed from offset-free text.
  Named zones use their actual rules, including daylight saving. Ambiguous/nonexistent
  DST local times or local-only dates without an offset retain uncertainty rather
  than a fabricated instant. Incomplete provenance/metrics remain visible.

No Intervals.icu HTTP result verifies Garmin-to-Intervals.icu freshness. The
latest imported record date/age and the latest read are factual app observations,
not an upstream sync-status claim. Empty wellness measurements do not mean illness.

## Network, resource, and backup constraints

Default-deny ingress remains unchanged: no Ingress, NodePort, external Service,
host port, or direct LAN/public web path. One explicit egress policy allows only
cluster DNS and TCP 443 to the three IPv4 addresses resolved for `intervals.icu`
on 2026-10-01: `104.26.14.117`, `104.26.15.117`, `172.67.73.247`.
These are **shared Cloudflare addresses**, not a hostname-exclusive network rule;
the app separately fixes the hostname, verifies TLS, and rejects redirects.
Address changes fail closed as connectivity errors; refresh the manifest and
verification expectations through a protected PR, not broad `0.0.0.0/0` egress.
This exception is not hosted-model consent, and no model client exists yet.

Reads are bounded to 8 MiB/50,000 source records per category and 45 seconds per
request (10-second connect/30-second socket budgets). One sync/removal operation
runs at a time; concurrent operations return conflict. A restart marks any
unfinished read interrupted instead of leaving an apparently running job.

The app stores only normalized data at `/data/gtrainer.sqlite3`, with migration
versioning, WAL, full synchronization, parameterized statements, and mode-600
database/sidecars. The immutable image includes the pinned SQLite 3.53.4.0 ARM64
native library in its read-only filesystem; no executable temporary directory
is needed for JNI extraction. CI verifies actual native ARM64 SQLite startup,
private read/delete, authentication, and anonymous/CSRF denial using synthetic
credentials, without making an upstream request.

The Mac scheduler's encrypted SQLite snapshots cover records and events together;
see [backup-restore-rollback.md](../infrastructure/backup-restore-rollback.md).
Restore validation now checks the actual version-1 migration/table/column layout.
Update the stable Mac backup runner with `python3 scripts/mac_backup.py setup`
after this release. Verify a fresh actual database backup and compatible restore
before the first real import or a later schema migration. Source credentials are
provisioned separately and are not stored in the record database.

All development/tests use synthetic fixtures. Never copy actual records, metric
values, dates, credentials, or decrypted backups into fixtures/reports/images/CI
or development model inputs. The running private UI may show your data; do not
capture it into automated development screenshots or issue reports.
