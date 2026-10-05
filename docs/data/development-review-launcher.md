# Isolated synthetic development launcher

The normal `ApplicationKt` entry point never adds development routes. Use the
separate `:backend:runDevelopmentReview` task only after creating an explicit
development configuration and **separate** password verifier. It never calls
normal environment-backed history/source/model constructors or opens the personal
database. The `gtrainer_development_session` cookie and session/CSRF ledger are
separate from the normal app's cookie/credentials.

Both `GTRAINER_DEVELOPMENT_REVIEW_ENABLED=true` and the configuration's `enabled`
must be explicit. Otherwise the launcher exits without opening storage or reading
personal settings. `GTRAINER_DEVELOPMENT_REVIEW_CONFIG_FILE` is the only path read
from the environment for operator configuration. Enabled Linux worker assembly
also requires the supervisor's injected `GTRAINER_SUPERVISOR_SOCKET`. No normal
`GTRAINER_*` credential/storage/model variables
are inherited. Configuration files are strict/bounded and operator-only.

Copy `benchmarks/connected-review/development/launcher.example.json` to an
owner-controlled location; set new absolute development database/verifier paths,
verify every selected model identity/setting/budget, and deliberately enable it.
The database directory must be dedicated, owner-only and outside personal storage.
The server rejects anything except literal `127.0.0.1`; its authentication origin
is the exact configured loopback port, not a browser-provided host. Normal auth's
existing default loopback policy remains unchanged.

```sh
GTRAINER_DEVELOPMENT_REVIEW_ENABLED=true \
GTRAINER_DEVELOPMENT_REVIEW_CONFIG_FILE=/absolute/operator/development.json \
./gradlew :backend:runDevelopmentReview
```

Launcher APIs include authenticated configuration, explicit synthetic preview,
durable submission/status/cancellation and maintenance; see
`development-review-worker.md` for exact schemas. Writes require exact origin and CSRF, strict
small JSON and an explicit bundled case/provider/model selection. No default model
is chosen. Packets, paths, revisions, settings overrides and personal snapshot IDs
are not accepted from HTTP requests. Logs/errors contain no submitted text.

**Execution remains disabled by default (`development_worker_not_configured`)**.
An explicit future worker configuration additionally requires the new dedicated
Linux PID-1 appliance, bound supervisor socket and authenticated owning-node reads.
The example's launcher, supervisor and worker enable flags are false, and its pod
replica count is zero. No example is a deployment/inference approval.

Build the separate UI with `npm run build:development` from `frontend/` before
rebuilding backend resources. The normal personal bundle never imports it.
Enabling its Developer options switch, opening configuration/preview, polling or
reconnecting cannot submit inference or run cleanup. Only explicit authenticated
submit/cancel/maintenance writes record durable events. This remains an isolated
security boundary, not a production bypass or qualification.
