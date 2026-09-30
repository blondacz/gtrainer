# ARM64 build and publication

Status: container and workflow prepared locally. Task 2.2 is **not complete**
until the real CI publication and its failure gate have been verified. No image
has been published and no Flux rollout has been performed.

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
  commit, and records the immutable digest. Preflight rejects an existing public
  package, and postflight checks that the published package is private. New
  GitHub container packages are private by default; verify that on the first
  actual publication rather than assuming the public repository implies private
  package visibility.
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
  Bash syntax checks. Workflow guard tests check required-job gating and reject
  public registry packages; these are not a substitute for a real failing CI
  run.
- This Mac has no Docker runtime, so neither the Docker build nor the published
  image run has been claimed as locally verified.

## Remaining verification

1. Obtain user approval for the first commit/push to the public repository and
   CI's private GHCR publication. Review the selected files for secrets/personal
   values first; do not blanket-add unrelated `.opencode` files or user work.
2. Initialize `main` in the remote repository, run the workflow, and verify test
   success, private package visibility, immutable digest, and ARM64 image run.
3. Verify a failing test on a non-release test branch prevents publication;
   remove only the synthetic failure introduced for that verification. Required
   branch checks/promotion protections are separate task-2.4 work.
4. Complete Flux/private routing, runtime secret provisioning, persistent
   storage, and rollout tasks before importing any real records into the app.

Manual development invocation, on a machine with Docker/Buildx and appropriate
ARM64 support:

```sh
docker buildx build --platform linux/arm64 --load -t gtrainer:development .
```

Do not run development images publicly or treat a passing health endpoint as
proof of private network isolation. The scaffold's private API remains closed
until task 2.5's real single-user authentication is implemented.
