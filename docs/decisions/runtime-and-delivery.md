# Runtime and delivery decisions

Status: accepted for phase-one implementation. The user selected this Mac as
the initial off-Pi backup destination and has a NAS for possible later use.
No application, backup job, CI pipeline, or Flux installation is claimed by
this decision note.

Evidence: [`../infrastructure/pi-inventory.md`](../infrastructure/pi-inventory.md)
and read-only GitHub repository inspection on 2026-09-30.

## Local store and resource budget

Use SQLite on a dedicated local-path PersistentVolumeClaim for this single-user
release. Keep app-owned events separate from source records and use versioned
schema migrations. A separate database server is unnecessary for the initial
workload. Use one backend replica to avoid concurrent migration/volume ownership
problems; use an online SQLite backup rather than copying a live database and
ignoring its journal/WAL files.

Retain imported records and manual events until the user explicitly removes
them. Avoid persisting raw source payloads and model prompts unnecessarily.
Explain that deleting live records does not erase existing backups immediately;
backup copies expire according to the agreed retention policy, and the user
must be able to remove all app backup copies when requesting full erasure.

Initial proposed app budget: 256 MiB requested memory and a 1 GiB container
limit, with a bounded JVM heap and a 2 GiB PVC. Measure actual usage before
calling this budget adequate. Reserve capacity for the OS, K3s, image layers,
and backup snapshots. Model memory/CPU limits and inference concurrency depend
on task 1.5's actual Pi benchmark; do not choose a model from free RAM alone.

## Private access and credentials

The app must authenticate the intended single user, regardless of LAN location.
Use private LAN routing and TLS, with no dashboard public ingress or router
port-forwarding. Do not assume the current Traefik LoadBalancer is isolated:
verify IPv4 and IPv6 reachability, router forwarding and firewall restrictions,
and access from outside the LAN before declaring deployment private. SSH
tunnelling is available for initial validation without creating a public app
endpoint. Preserve Wi-Fi fallback and verify the Ethernet DHCP reservation.

Provision the Intervals.icu key, app authentication secret, registry pull
credentials, and any eventual model credentials outside Git. If secrets later
need Git storage, use SOPS/age and hold the decryption key only in the cluster
or a separate private recovery location. Do not put keys, health records,
kubeconfig, prompts, or backup contents in CI or public repository files.

## Git, CI, registry, and Flux

- Git host: existing `https://github.com/blondacz/gtrainer` repository.
  GitHub reports it is **public**, with no initialized default branch at this
  inspection. Public source code is compatible with a private dashboard, but
  every committed file must be safe to publish. Never assume ignored files or
  a private deployment make Git history private.
- CI: GitHub Actions. Run backend/frontend tests and privacy/deployment checks
  before building and publishing a Linux ARM64 app image. Use test/build jobs
  with minimal token permissions; grant package write permission only to the
  gated publishing job. Do not provision cluster or health-data credentials
  to CI.
- Registry: propose a **private** GHCR package at
  `ghcr.io/blondacz/gtrainer`. Repository visibility does not establish package
  visibility; verify that setting before rollout. Flux/K3s require a narrowly
  scoped package-read credential outside Git if the package remains private.
- Flux: the Pi pulls version-pinned manifests from the repository over outbound
  HTTPS. A public repository needs no Git credential for reads; if the user
  later makes it private, provide a read-only deploy key outside Git. There is
  no CI connection to the home cluster and no inbound deployment port.
- Promotion: after tests and ARM64 build verification, propose the immutable
  image digest in a protected release/deployment path. Reconcile only approved
  Git changes; required checks must block failing releases. Promotion automation
  may need repository/PR write permissions, but no cluster access. Establish
  default branch and required checks before claiming this protection is active.
- Rollback: restore the prior Git-pinned image digest and allow Flux to
  reconcile. For a schema/data problem, restore a verified backup with a
  compatible app version instead of pretending image rollback undoes data
  changes.

## Backup and recovery

Use this Mac initially, with configurable destination
`~/Library/Application Support/gtrainer/backups`, outside the repository and
restricted to the user. The default retention is 14 daily and 8 weekly copies.
The NAS is a potential later destination, not an integration to provision now.
Moving encrypted backup files later must not require changing the database
format or exposing plaintext to the destination.

Use a Mac-side scheduled pull over the dedicated SSH connection, not an open
backup endpoint on the Pi. Request a consistent SQLite online-backup snapshot
covering both imported records and app-owned events. Encrypt the stream with
age before committing the off-Pi backup file; write to a temporary filename and
rename only after successful transfer and encryption. Restrict snapshot staging
on the Pi and keep at most one bounded temporary snapshot, deleting it after
the successful backup. Report failures without printing health data.

Keep the age private recovery key outside Git, initially in a user-only file at
`~/.config/gtrainer/backup-age.key` on the Mac. Keep it separate from the backup
directory and never copy it into CI, the registry, or a future NAS backup share.
The Mac can hold both ciphertext and the key for operational convenience; this
does not protect against compromise or loss of the entire Mac. The operator
needs a separate protected recovery-key copy and, later, an independent backup
destination to address that risk.

If the Mac is asleep, offline, or the job fails, backups may be delayed. Retry
when available and make the last successful backup time/staleness visible; do
not claim a daily recovery point that was not created. A Pi-local snapshot alone
does not protect against SD-card loss.

Restore by decrypting a selected backup into a private isolated store, running
SQLite integrity/schema checks, and verifying both imported records and manual
events before replacing the live database with a compatible app version. Test
this first with synthetic data; do not log decrypted records. Re-provision
credentials separately since the database backup must not contain API keys.

These paths and mechanisms are implementation decisions, not already-installed
jobs or existing key files. No personal data has been copied to this Mac by
this decision. The later implementation tasks separately verify scheduling,
retention, encryption, backup restore, CI gates, Flux rollout, secret
provisioning, rollback, private routing, and real resource usage.
