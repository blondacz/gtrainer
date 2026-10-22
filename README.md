# GTrainer

Private, single-user Garmin-derived training/health trends and manual events,
using Intervals.icu behind a replaceable read-only adapter. Kotlin/Ktor backend
and React/TypeScript frontend; intended deployment is ARM64 K3s on a home Pi.
The browser/backend boundary uses REST/JSON over HTTP, not gRPC.

## Current state

The private application supports authenticated read-only imports, normalized
SQLite history, factual charts, and source-linked comparisons. Tested ARM64
images reach the Pi through protected promotion PRs and Flux; access uses an
authenticated SSH tunnel, not direct LAN/public application ingress.

The optional local analysis prototype is guarded and separate from factual
reviews. It does not deploy a model service or select a default. See
[`docs/decisions/guarded-local-analysis.md`](docs/decisions/guarded-local-analysis.md)
for configuration, limits, and outstanding hardware qualification. Hosted
inference and full phase-one verification remain unfinished. Local manual-event
CRUD and the rounded, tabbed dashboard are implemented with synthetic tests.
Google Calendar and adaptive plans are later phases.

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

The canonical scoped factual-review API is `GET /api/reviews/options` and
`POST /api/reviews/prepare`. It uses shared review controls and bounded factual
reports; see [scoped factual reviews](docs/data/scoped-reviews-and-comparisons.md).
The [review presets and scheduler](docs/decisions/review-presets-and-scheduler.md)
documents preserved, guarded personal review-now/queue/model capabilities; there
is no automatic inference or review on opening the dashboard.
See [dashboard and manual events](docs/dashboard.md) for section navigation,
lossless evidence paging, explicit writes and the two separate model selections.
Live schema/helper migration is not authorized by these changes.
The application database is a fresh version-1 schema; obsolete prior application
schemas are refused, not migrated or reset. No live database operation is implied.

In separate terminals:

```sh
./gradlew :backend:run
```

```sh
npm --prefix frontend run dev
```

Open `http://127.0.0.1:5173`. The UI proxies the process-only `/healthz` check to
`http://127.0.0.1:8080`, including private `/api` routes. Both development servers bind to loopback by default.
`GTRAINER_HOST` and `GTRAINER_PORT` configure the backend; do not expose it to a
public network. Private `/api` routes require a configured single-user session;
see [`docs/infrastructure/private-access.md`](docs/infrastructure/private-access.md).
The health response contains only process availability, not personal records.

The plaintext SSH exception applies only to `http://127.0.0.1:8080`, not Vite's
5173 origin. Exercise private UI writes through the packaged loopback dashboard
or synthetic tests; do not broaden the authentication Origin/cookie exception
to make a development proxy work.

## Pi dashboard

Run `bash scripts/pi-tunnel.sh` on the operator Mac and keep it running. Open
`http://127.0.0.1:8080`; stop the tunnel with Ctrl-C. No direct LAN/public web
port is exposed. Source import requires an explicit user action. Model generation
requires an explicit request or explicitly enabled review schedules; opening the
dashboard does not perform either. New local-source features are not yet deployed.
See [`docs/infrastructure/flux-deployment.md`](docs/infrastructure/flux-deployment.md)
for the deployment, access boundary, and verified limitations.

## Privacy

Keep API keys, personal records, prompts, backup keys, and kubeconfig outside
Git, fixtures, logs, CI, and model inputs used for development. All tests use
synthetic data. The production adapter is Kotlin; Python utilities here
only inspect source coverage or run synthetic hardware benchmarks. No hosted
health-data transfer is authorized by running the application or tests.

Runtime, encrypted Mac backups (NAS later), and private GitOps delivery decisions
are documented in
[`docs/decisions/runtime-and-delivery.md`](docs/decisions/runtime-and-delivery.md).

Current work is tracked in
[`scoped-reviews-and-adaptive-planning`](openspec/changes/scoped-reviews-and-adaptive-planning/tasks.md),
[`connected-insights-context`](openspec/changes/connected-insights-context/tasks.md),
and [`foundation-release-verification`](openspec/changes/foundation-release-verification/tasks.md).
Completed foundation work is recorded in its
[archived ledger](openspec/changes/archive/2026-10-03-garmin-training-guidance-foundation/tasks.md).
