# GTrainer

Private, single-user Garmin-derived training/health trends and manual events,
using Intervals.icu behind a replaceable read-only adapter. Kotlin/Ktor backend
and React/TypeScript frontend; intended deployment is ARM64 K3s on a home Pi.
The browser/backend boundary uses REST/JSON over HTTP, not gRPC.

## Current state

The repository contains a **development scaffold**, source-coverage checks, and
infrastructure/model decisions. No records are loaded by the scaffold. Import,
authentication, charts, events, model integration, and automated deployment
remain pending tasks. Google Calendar and adaptive plans are later phases.

The two small Pi models tested were not sufficiently grounded; no default model
or hosted fallback has been selected. See
[`docs/decisions/local-model-benchmark.md`](docs/decisions/local-model-benchmark.md).

## Build and test

Requirements: **JDK 25**, **Node.js 24+** (below 27), npm, and Python 3.9+ for
the development inspection/benchmark tests. Gradle is pinned by the wrapper;
backend dependency versions are explicit and npm dependencies are locked.

On macOS, if an existing shell still points to an old JDK:

```sh
export JAVA_HOME="$(/usr/libexec/java_home -v 25)"
export PATH="$JAVA_HOME/bin:$PATH"
```

Run everything from the repository root:

```sh
bash scripts/check.sh
```

Or run the suites separately:

```sh
./gradlew --no-daemon :backend:build
npm --prefix frontend ci
npm --prefix frontend test
npm --prefix frontend run build
```

Backend test/build reports are in `backend/build/reports/`; frontend build output
is in `frontend/dist/`. Both are ignored by Git.

## Local development

In separate terminals:

```sh
./gradlew :backend:run
```

```sh
npm --prefix frontend run dev
```

Open `http://127.0.0.1:5173`. The UI proxies the process-only `/healthz` check to
`http://127.0.0.1:8080`. Both development servers bind to loopback by default.
`GTRAINER_HOST` and `GTRAINER_PORT` configure the backend; do not expose it to a
public network. Private `/api` routes return 401 until authentication is built.
The health response contains only process availability, not personal records.

## Privacy

Keep API keys, personal records, prompts, backup keys, and kubeconfig outside
Git, fixtures, logs, CI, and model inputs used for development. All tests use
synthetic data. The production adapter will be Kotlin; Python utilities here
only inspect source coverage or run synthetic hardware benchmarks. No hosted
health-data transfer is authorized by running the scaffold or tests.

Runtime, encrypted Mac backups (NAS later), and private GitOps delivery decisions
are documented in
[`docs/decisions/runtime-and-delivery.md`](docs/decisions/runtime-and-delivery.md).

Progress is tracked in
[`openspec/changes/garmin-training-guidance-foundation/tasks.md`](openspec/changes/garmin-training-guidance-foundation/tasks.md).
