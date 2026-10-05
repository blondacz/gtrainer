# Dedicated Linux process supervision

This is non-inference process-boundary implementation/testing, not a deployed
runtime or qualification. Worker integration is documented in
`development-review-worker.md`. All packaging examples are disabled; no image was built
or published, no models were downloaded and neither existing runtime was adopted.

## Kernel-owned branches and stop evidence

`backend/runtime/pid1_supervisor.py` must run as PID 1 of a **new dedicated** Linux
container. It owns one configured worker and a private Unix control socket. Kernel
`SO_PEERCRED` permits commands only from that exact worker PID, not another process
with the same UID. Commands carry an opaque run/epoch/container/generation binding;
they cannot supply an endpoint, process ID, executable or model/prompt text.

Each run gets a fresh keeper (`runtime_scope.py`) and fresh `ollama serve`, retained
through the permitted correction. The keeper uses `PR_SET_CHILD_SUBREAPER` and
kernel ancestry to own all runtime descendants, including double-forked/session-
detached and reparented children. It signals identity-stable pidfds, repeatedly
discovers descendants during shutdown, escalates TERM to KILL, and reaps until
`waitpid` returns **ECHILD**. Only that kernel condition plus the keeper's confirmed
exit yields a bound stop receipt. Waiting only for `ollama serve`, a process group,
HTTP EOF, model residency or an unload acknowledgement is never confirmation.

The whole controller stop operation has a monotonic ceiling of 60 seconds, including
receipt/keeper exit; unresolved stop returns/raises a fixed failure and blocks new
starts. The keeper stays responsible after an uncertain stop. Parent channel EOF
causes bounded cleanup even if the owner crashed without running shutdown code.
PID 1 stops the runtime and worker descendants on worker exit; it does not replay or
restart the worker. A lost keeper/startup boundary quarantines starts while the
healthy worker can continue queue/inspection work. Exiting PID 1 ultimately ends
the container namespace, but does not itself fabricate an acknowledged run receipt.

One owner-only control directory/lifetime lock prevents competing supervisors.
Confirmed receipts are atomically written/fsynced to a bounded last-receipt file
before a later runtime can start. The Kotlin client binds those receipts to the
durable SQLite ownership/publication/cancellation ledger; exact old-container
termination after a crash still requires the separate node verifier. A local
lock/receipt is not that verifier.

Only explicit new development settings reach the worker. Ollama gets a restricted
environment, `127.0.0.1:11434`, one parallel execution, zero keep-alive, cloud disabled
and an explicit persistent public-model directory. Its output/error streams are not
logged. Model files outlive per-run processes; fresh starts trade loading time for
a simpler, verifiable execution boundary. The total execution budget includes load.

## Two different memory budgets

- **Pi deployment example:** 5 GiB for the whole worker/runtime container on the
  proposed 8 GiB host, given roughly 2 GiB OS/other-process use and 1 GiB reserved
  host headroom. Worker/JVM/native overhead consumes part of that 5 GiB. It is not
  5 GiB for Ollama plus another allocation for the worker. The independent live
  `MemAvailable >= 1024 MiB` guard remains required; the 2 GiB estimate is not a
  substitute for observation. Neither fit nor useful Q3 completion is promised.
- **Local non-inference sandbox:** 1 GiB/2 CPUs suffices for small Python child
  fixtures. No Ollama, JVM, model or inference runs there; this is not the Pi
  runtime allocation and not a measured model-memory reference.

The example Kubernetes manifest has zero replicas, a pinned-node placeholder,
separate owner-only development PVC/configuration, private PID/network boundaries
and no Ollama/public Service. An operator must provision the PVC directory ownership
for UID 10001 without touching personal/existing model storage. All image/layout,
memory, verifier and network settings require separate approval/verification before
deployment. The example Dockerfile has no default base images and cannot select or
download models. It explicitly excludes the test fixtures.

## Approved local sandbox and reproducible checks

The approved sandbox uses already-installed Lima, a **new `LIMA_HOME`**, a pinned
Ubuntu 24.04 minimal OS image, plain mode, no host mounts, no container engine and
no forwarded application ports. Only `backend/runtime/` source/controlled fixtures
are copied in. Existing Mac VM/Pi state and private captures are not contacted.
Short `LIMA_HOME` names are required by macOS Unix socket limits.

Inside this disposable VM, run controlled tests in a private Linux PID/mount
namespace (the nested namespaces require root **only in the test VM**, not a
privileged application container):

```sh
sudo unshare --pid --fork --mount-proc --kill-child \
  python3 -m unittest discover -s /path/to/copied/runtime/tests -v
```

Fifteen tests passed on Linux 6.8.0 / Python 3.12.3: cooperative/forced stop,
double-fork adoption, main exit with a live orphan, descendants born during TERM,
owner EOF, exact-bound receipts, worker failure, keeper quarantine, correction-time
reuse, per-run recycling/persistent models, competing locks and foreign peer refusal.
The unrelated sentinel survived. These are genuine Linux kernel checks with fake
sleeping children—not live Ollama/container-runtime recovery verification.
