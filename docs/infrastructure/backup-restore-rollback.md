# Pi operations: encrypted backup, restore, rollback, removal

Status: task 2.6 verified: encrypted synthetic Pi-to-Mac backup/restore, Mac
scheduler, protected Git image-revert rollback and return to authentication.
There is no real app database
yet, so there is no production recovery point or imported personal data.

## Install and upgrade

Use [Flux bootstrap](flux-deployment.md), [protected promotion](image-promotion.md),
and [private secrets/access](private-access.md). The Pi reads Git and images
outbound; CI never receives SSH/kubeconfig/source/password/model credentials.
Provision the external Secret before deploying manifests that reference it.
Never repair a rollout by deleting the persistent volume.

All changes to `main` use passing protected PRs. Software/build changes publish
and propose a tested ARM64 digest. Deployment/docs-only pushes still run checks
but do not republish current software: otherwise a Git rollback would immediately
be undone by a new automatic release of that same current software. An explicit
main `workflow_dispatch` is the deliberate rebuild/retry path. Bot-created
promotion PR workflows may require the operator's GitHub approval.

Verify Flux Ready, the desired image, and private access after upgrades. Before
any schema-changing upgrade, require a fresh encrypted database backup and a
compatible tested restore. Image reversion does **not** reverse schema/data
migrations. Database code/ingestion are later tasks; do not import real data
until the schema and actual production backup are verified.

## Mac tooling and scheduled backup

Installed official **age v1.3.2** Mac AMD64 tools at `~/.local/bin/age` and
`~/.local/bin/age-keygen`. Archive SHA-256 was verified against the official
GitHub release asset digest:
`1d1e4bc66e1427edad7739ae7616157de0e79db8b6d2a1497d7d9925fb06a539`.
Artifact: `age-v1.3.2-darwin-amd64.tar.gz` from the FiloSottile/age v1.3.2 release.
This is checksum verification over the official HTTPS release, not a claim of
independent Sigsum proof verification. On another architecture use its official
artifact and independently verified digest, not this AMD64 binary.

```bash
python3 scripts/mac_backup.py setup
launchctl bootstrap "gui/$(id -u)" "$HOME/Library/LaunchAgents/io.gtrainer.backup.plist"
```

Setup creates/reuses `~/.config/gtrainer/backup-age.key` (mode 600), owner-only
`~/Library/Application Support/gtrainer/backups`, and a stable private copy of
the three backup scripts under `~/.local/share/gtrainer/backup`. The LaunchAgent
uses the installed copy, not whichever Git branch happens to be checked out.
Re-run setup to deliberately upgrade that copy; unload/reload the LaunchAgent
when changing its configuration. Do not bootstrap an already loaded job.

The loaded `io.gtrainer.backup` agent runs at login and retries hourly while the
Mac/user session is available, keeping one **successful** daily UTC snapshot and
the first successful snapshot each ISO week: 14 daily and 8 weekly copies.
This is not a guarantee of a daily recovery point when the Mac sleeps, is
offline, or the job fails. Default source is `/data/gtrainer.sqlite3` on the Pi.
Until ingestion creates it, the job fails honestly with “No application database
yet”; no empty database or successful-backup status is manufactured.

The operator-only tool discovers the Bound app PV via SSH and requests a SQLite
online backup using the Pi's Python/SQLite library. It reads the source with
`mode=ro`, correctly includes WAL transactions, integrity-checks the snapshot,
and normalizes it to a standalone DELETE-journal file. A root-owned lock permits
one snapshot at a time; a reserved `.gtrainer-backup.snapshot.sqlite3` staging
file is bounded to 512 MiB, with a 240-second snapshot/300-second transfer budget
and at least 256 MiB spare staging disk. The snapshot is deleted after streaming,
including ordinary transfer failures; after a hard kill, the next locked run
removes only that exact stale staging file. A cold missing database is not created.

The stream goes directly from SSH into age on the Mac. Only ciphertext is written
off-Pi during backup, initially in a mode-600 pending file; rename happens after
both SSH and encryption succeed. Failed encryption/transfer cannot commit a
backup or fresh success state. Retention deletes only exact managed ciphertext
names; it does not delete arbitrary files or source records. Filesystem/status
errors retry instead of treating a partial job as success.

Manual backup and status:

```bash
python3 scripts/mac_backup.py run
cat "$HOME/Library/Application Support/gtrainer/backups/status.json"
launchctl print "gui/$(id -u)/io.gtrainer.backup"
```

`status.json` contains only last successful UTC time, database filename, and
retention counts. Compare the time with now; no file means no successful
production backup. `job.log`/`job-error.log` contain only fixed operational
messages, never records, passwords, source keys, decrypted contents, or model
inputs. `--force` explicitly requests a fresh same-day copy. Backups stay
outside Git/images/CI. Do not configure a destination inside the repository.

## Recovery key and limitations

Keep the age recovery key separate from the backup directory. **Make a separate
protected recovery-key copy yourself**, such as in your password manager or
protected offline storage; never paste it into chat/Git/issues or place it beside
the NAS backup share. This has not been verified on your behalf. Losing that key
loses restore capability. Keeping ciphertext and key on this Mac does not protect
against loss/compromise of the whole Mac; the NAS remains deferred, not silently
provisioned. File permissions/encryption do not certify FileVault, the router,
Pi datastore encryption, or a complete independent disaster-recovery location.

## Restore (isolated first, never an automatic live overwrite)

```bash
python3 scripts/mac_backup.py restore \
  --backup "$HOME/Library/Application Support/gtrainer/backups/daily/YYYY-MM-DD.sqlite3.age" \
  --output "$HOME/Library/Application Support/gtrainer/restore/review.sqlite3"
```

Decryption writes a private temporary file outside Git, requires successful age
authentication, validates SQLite integrity and schema `user_version=1`, and
requires activity/wellness/event tables. Validation treats this complete snapshot
as immutable and creates no SQLite sidecars. The final output is published
exclusively: an existing database is never overwritten. Wrong keys, tampered
ciphertext, unexpected schema, oversized input, or bad integrity leave no accepted
restore. This is **not** permission to paste/print decrypted rows.

Before live replacement, stop/suspend **only** the app reconciliation/Deployment,
ensure no database owner remains, select a compatible app/schema version, and
take a separate fresh backup if possible. Transfer the verified database privately
into the app volume, set owner/GID 10001 and private permissions, replace the live
database only with explicit operator approval, then restore the single replica
and reconciliation. Treat old WAL/SHM files as part of the old stopped database,
not files to combine with a restored snapshot. Re-provision runtime credentials
separately: database backups contain no intended credential store. The production
schema and exact live-replacement procedure must be confirmed when database
implementation lands; no live personal database restore is claimed here.

## Git image-revert rollback

1. Select a known tested, schema-compatible prior image and its matching
   `deploy/gtrainer/release.json` from Git. Revert **both** descriptor and image;
   do not substitute an arbitrary tag or directly edit the live cluster image.
2. Open a protected PR. Tests/provenance must pass, including the original
   successful CI artifact for a changed image descriptor. Expired old artifacts
   require re-verification; do not bypass checks. The original manually verified
   bootstrap digest is the only descriptor-free exception.
3. Merge normally and allow Flux to reconcile. Deployment-only changes do not
   republish/re-promote newer software. Confirm the expected revision, image,
   rollout health, privacy boundary, and persistent storage.
4. To return to the current release, restore its exact tested image/descriptor
   through another protected PR. Sessions reset whenever the app restarts.

Any database/schema rollback uses a verified compatible restore, not just step 1.
This release still has no production database or schema migrations.

## Removal without erasing data

- Stop the Mac scheduler with
  `launchctl bootout "gui/$(id -u)/io.gtrainer.backup"`. Unloading does not remove
  backups, recovery keys, credentials, or the app.
- Remove/suspend the app Flux reconciliation via a protected Git change, then
  remove only app-owned Deployment/Service/NetworkPolicy/RBAC objects as intended.
  Retain the app namespace and `gtrainer-data` PVC; both are prune-protected.
- Do not delete the PVC/PV, its backing directory, the whole namespace, K3s, or
  Flux to remove just the app. The storage class has Delete reclaim policy, so
  explicit PVC deletion can erase data even though Flux pruning is disabled.
- Credential revocation, deletion of live data, and deletion of retained encrypted
  backups/recovery keys are separate explicit decisions. Local data removal does
  not automatically erase retained backups or the upstream Intervals.icu account.

## Synthetic backup/restore evidence (2026-10-01)

- A separate `gtrainer-backup-verification.sqlite3` in the Pi app volume held only
  synthetic activity, wellness, and event records; no app database was replaced.
- Actual SSH -> consistent SQLite snapshot -> age encryption produced private
  daily/weekly ciphertext in the Mac's separate `backup-verification` directory.
- Decryption into an isolated private Mac store passed integrity/schema checks
  and preserved all three synthetic categories and the event description.
- Pi fixture and Mac plaintext restore were removed. No temporary Pi snapshot
  remained. Only synthetic ciphertext remains in that separate verification folder.
- Unit tests cover WAL inclusion, interruption cleanup, absent/symlink/traversal
  sources, retention scoping, and missing-snapshot non-commit. Operator age tests
  cover real encryption/decryption, wrong keys, corrupted ciphertext, private
  restore permissions, and refusal to overwrite an existing output.
- The Mac LaunchAgent is loaded. There is still no real app database and no
  successful production recovery point; the first real backup must be verified
  once ingestion/storage are implemented.

## Protected Git rollback/return evidence (2026-10-01)

- Protected rollback PR [7](https://github.com/blondacz/gtrainer/pull/7) passed
  app/tests and original release-artifact provenance, then merged as
  `05675d885971a5ae44da351d10f0f49c277c12d6`. Flux became Ready at that revision;
  the physical Pi's Ready container reported the exact older scaffold digest
  `ghcr.io/blondacz/gtrainer@sha256:17cb4d4aeeac6cff731ddeaf6dc67590874c8f5c53cb25211e2fd50715517fe7`.
- The rolled-back scaffold passed private health and rejected session/trends
  access with 401. No personal database existed, and no records were requested.
- Protected return PR [8](https://github.com/blondacz/gtrainer/pull/8) passed
  tests/provenance and merged as `db5f4c34ee3045267fa04d247e87b6dd70a84cf6`.
  Flux applied that revision and the Pi returned to the exact tested auth digest
  `ghcr.io/blondacz/gtrainer@sha256:cbfd0dd1cc2b8c44ce788b3c036aa2c8e8e11558de4c378c3ab2dca608c6848b`.
  Full operator login/logout, anonymous denial, Origin/CSRF rejection, and
  no-credential-in-current-logs checks passed again.
- PVC `pvc-3e9f0be6-bacf-4036-91c9-a568541294ec`, external runtime secrets, and
  default-deny/no-direct-ingress protections remained intact. No direct cluster
  image mutation, CI cluster access, or protection bypass was used.
- Main rollback run
  [36874118751](https://github.com/blondacz/gtrainer/actions/runs/36874118751)
  passed; deployment-only gating prevented a new image from undoing the rollback.
  The in-flight operational source build was cancelled when main advanced rather
  than deploying an obsolete source release. No new promotion PR was left open.
