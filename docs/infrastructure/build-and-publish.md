# ARM64 build and publication

Status: task 2.2 is **complete**. CI tests, publication, and immutable-digest
ARM64 startup verification passed; a deliberate failing-test run verified that
publication is skipped. The user approved keeping the code-only GHCR package
public. No Flux rollout or real-record import has been performed.

## Prepared behaviour

- The GitHub Actions `checks` job runs `scripts/check.sh`: backend and frontend
  builds/tests, source-inspection tests, synthetic benchmark checks, and delivery
  guard tests. It verifies the Gradle wrapper checksum first.
- The `publish` job requires successful `checks`; no `always()` or
  `continue-on-error` overrides exist. It only runs from `main`, never from a
  pull request. All third-party Actions are pinned to reviewed commit IDs.
- Only the publishing job receives package-write permission. Neither job
  receives cluster credentials, the Intervals.icu key, records, model keys,
  backup keys, or prompts. CI does not connect to the home network.
- GHCR publication targets `ghcr.io/blondacz/gtrainer`, tagged with the source
  commit, and records the immutable digest. The user approved this **public
  software image** on 2026-09-30. Runtime records and secrets are not packaged;
  dashboard authentication and LAN-only routing remain required. Publishing
  uses the job's short-lived `GITHUB_TOKEN`; public image pulls need no personal
  token, registry-read CI secret, or cluster image-pull secret.
- The Dockerfile builds the UI and JVM distribution on the builder's native
  platform, then places the architecture-independent app on the requested
  Linux ARM64 JRE. Base images are pinned by digest. The final image runs as
  UID/GID 10001, serves the built UI and REST/JSON API from one process, and
  contains no Node runtime or build tooling.
- `.dockerignore` allowlists only build inputs. Git state, OpenCode artifacts,
  credentials, reports, backups, personal records, and local build caches are
  not intended build inputs.
- The workflow pulls the published image by digest and runs it under ARM64
  emulation with loopback-only port binding, memory/CPU limits, a read-only
  filesystem, and bounded `/tmp`. It checks architecture, process health, the
  public static shell, and a 401 for private API access. Actual Pi rollout is
  verified later through Flux, not by giving CI access to the Pi.

## Local verification performed

- Java 25 is installed and selected by the Mac's `java`, `javac`, and
  `/usr/libexec/java_home`. Previous Java installations were preserved.
- The Gradle distribution and wrapper checksums matched their published values.
- A clean temporary Git clone of the task-2.1 scaffold built and passed its
  backend/frontend and Python suites, without committing or pushing this repo.
- After adding static UI serving and delivery guards, the normal repeatable
  check passed 5 backend tests, 6 UI tests, 11 utility/delivery tests, and 4
  benchmark tests (26 total).
- The workflow passed checksum-verified actionlint 1.7.12. Shell scripts passed
   Bash syntax checks. Workflow guard tests check required-job gating, public
   publication without personal credentials, restricted build inputs, and
   immutable ARM64 image verification.
- This Mac has no Docker runtime, so neither the Docker build nor the published
  image run has been claimed as locally verified.

## Remaining verification

Complete Flux/private routing, runtime secret provisioning, persistent storage,
and rollout tasks before importing any real records into the app. Required
branch checks and promotion protections are separate task-2.4 work.

## Verified CI evidence (2026-09-30)

- Source commit: `f0b88e2011343397ce38f6fe517450de20693d34`.
- Successful pipeline:
  [36780726075](https://github.com/blondacz/gtrainer/actions/runs/36780726075).
  All 5 backend, 6 UI, 12 utility/delivery, and 4 benchmark tests passed.
- Published and executed immutable reference:
  `ghcr.io/blondacz/gtrainer@sha256:ccb2c1af5a44c37b15a06656e081012f108fa0e89db81dcbad2b5bbf0c768d96`.
  CI checked `arm64`/`aarch64`, then ran the published image under QEMU with
  restricted loopback ports and resources. Health and UI checks passed;
  `/api/trends` returned 401. This verifies Pi architecture compatibility, not
  physical Pi rollout or LAN isolation. API denial is still a scaffold
  placeholder, not completed single-user authentication.
- Failing-test gate for the same source:
  [36780752400](https://github.com/blondacz/gtrainer/actions/runs/36780752400).
  Only `PublicationGateVerificationTest` failed; the publishing job was skipped.
  To repeat, dispatch `verify_failure_gate=true`; the synthetic test exists only
  in the CI workspace, not committed source. No personal input is used.

## Registry decision history

The initial public package was created at `2026-09-30T20:59:42Z` by the first
publication run, with three OCI versions and only the `0b49b8f` source tag. No
personal records or credentials are part of its scaffold image. GitHub does
not permit changing a public package back to private.

With explicit user approval, a one-time identity-guarded maintenance job removed
only that original package in run
[36779426643](https://github.com/blondacz/gtrainer/actions/runs/36779426643).
Republication created a new package at `2026-09-30T21:30:25Z` which was again
public. Both runs stopped before startup verification under the original
private-image policy; the cause of the unexpected visibility is not established.

The user then explicitly approved public code-only images. The temporary
deletion workflow/scripts and private-package guard are removed; no further
package deletion or source-repository visibility change is planned. The now
unused `GHCR_READ_TOKEN` CI secret is removed. Its local source file is left
untouched; the user can revoke that read-only PAT in GitHub settings if no other
use is intended. This is not consent to publish data or expose the dashboard.

Manual development invocation, on a machine with Docker/Buildx and appropriate
ARM64 support:

```sh
docker buildx build --platform linux/arm64 --load -t gtrainer:development .
```

Do not run development images publicly or treat a passing health endpoint as
proof of private network isolation. The scaffold's private API remains closed
until task 2.5's real single-user authentication is implemented.
