# Development runtime health (not qualification)

Health adds refusals to the synthetic-only development gate; it cannot enable the
ordinary production gate or prove execution stopped. The launcher remains
`development_worker_not_configured` unless its separate worker is explicitly
enabled in a dedicated Linux PID-1 appliance. All checks here were exercised with mock HTTP,
recorded/synthetic command output and fake clocks—not an existing runtime/host.

## What is checked

The gate rechecks the durable current claim/epoch/generation/container, remaining
TTL/monotonic deadline, frozen case packet and prompt hash, exact configured model
binding, local provider selection and fresh successful health before a send or
publication. An external process-boundary check is also required and defaults to
false. A forged or old token, closed/unconfigured source or mismatched packet cannot
authorize a request. This grants synthetic development permission only and never
records model qualification. The owned run executor composes this gate with the worker.

One per-run observer checks trusted supervisor status plus bounded GET-only
`/api/version`, `/api/tags` and `/api/ps` on the new owned `127.0.0.1:11434` runtime.
The tag/version/digest/settings and runtime boundary must match; main-process exit,
unexpected restarts or unrelated loaded models refuse execution. Residency is
only negative evidence for an unrelated model, **never** idle/stop evidence. Status
is checked again after reads. No generation, unload, retry, redirect or endpoint
override exists in the identity reader.

The optional operator-pinned SSH node reader uses the same separate owner-only
identity/known-hosts policy as the termination verifier. Its only operations are:

```text
sudo -n /usr/local/libexec/gtrainer-development-node-observer observe <exact container ID> <canonical boot ID>
sudo -n /usr/local/libexec/gtrainer-development-node-observer application
```

The root-owned, isolated-Python helper `backend/runtime/node_health_observer.py`
reads host `MemAvailable`, verifies the same boot before/after the bounded CRI
inspection, requires the exact running dedicated container/namespace and reads its
actual OCI memory limit and restart attempt. It projects only safe values, not raw
inspect fields. The whole-appliance limit is compared independently with configured
policy; available host memory must be at least **1024 MiB**. The proposed Pi policy
has a **5 GiB** whole-worker/runtime ceiling, not a 1 GiB inference allocation.

The `application` operation runs only the fixed read-only K3s query for the existing
`gtrainer` namespace and `app.kubernetes.io/name=gtrainer` label. It projects bounded
pod/container/image identities, ready status and restart counts; empty, deleting,
not-ready, foreign or malformed workloads fail closed. No logs, secrets, pod exec,
modification, generalized selector or browser command is allowed. An operator must
separately approve/provision the helper and its shared root-owned
`bounded_node_read.py`/`node_termination_verifier.py` dependencies. They are not
installed by the disabled worker image recipe.

## Timing and the protected Pi application

All constituent timestamps are taken on the worker monotonic clock **before**
their read, including before remote SSH. One collection has a single **60-second
total bound**, not 60 seconds per command. Each command also has a 15-second ceiling,
bounded output and bounded kill/reap cleanup. Calls while a collection is active
share that one result; explicit scheduling opportunities less than five seconds
apart reuse it. The observer never starts an idle timer or background housekeeping
loop itself. Its owner closes/cancels it deterministically.

Freshness is calculated from the oldest relevant live measurement, not collection
completion. Pre-send/publication refuse stale evidence. A separate **70-second-age
watchdog**, active only during startup/inference, can stop work even if the collection
is hung or cancelled. Active health/deadline scheduling is not an idle TTL timer.
The owned run executor routes these refusals to fenced failure and owned-runtime
stop; uncertain stop keeps subsequent queue execution blocked.

On Pi, the initial observation captures ready application state twice at least ten
seconds apart under that same total budget. Historical nonzero restart counts are
allowed; identity, readiness and counts must stay unchanged. The first baseline is
copied/immutable and cannot be reset to hide a later restart or pod replacement.
Later observations compare fresh app measurements to that historical reference;
the historical reference is not mistaken for a new live measurement. During the
initial collection, its first sample also contributes to oldest-measurement age.
Mac development policy can omit this Pi-only protected-application check, but never
omits runtime identity, host headroom or the actual appliance memory ceiling.

Failures carry only `runtime_health_unavailable`, `runtime_health_failed`,
`resource_health_failed` or the existing fixed gate reason. No raw command/HTTP
body, exception, prompt, private environment or rejected draft is exposed.
These are synthetic acceptance checks, not measurements of Q3 fit/usefulness or
real-runtime/container recovery acceptance. Separate approval remains required.
