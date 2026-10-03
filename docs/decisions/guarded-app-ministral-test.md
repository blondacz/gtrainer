# Published app → Ministral: rejected synthetic integration

On 2026-10-01, the separately authorized isolated Pi test exercised the exact
published guarded Kotlin application and approved Ministral artifact. **None
of eight real inference responses was accepted**, over six distinct usable
fixtures. Two additional app requests correctly returned `insufficient_input`
without sending a model request. The prior typed benchmark's 8/10 acceptance
does not transfer to this different prompt/packet.

## Retained evidence

- [Parsed actual outputs, bindings, latencies, and provenance](../../benchmarks/ollama/results/2026-10-01-guarded-app-ministral.json).
- [Operator procedure and isolation](../../benchmarks/ollama/README.md#published-kotlin-app--ministral-integration-test).
- Original synthetic-only JSONL remains in the private operator temporary
  directory, SHA-256
  `b52bacc79f37c925d331d49daa4074b4415704d273c44d5034fa368b74ab86eb`.
  It contains exact transport bytes, app reports, prepared packets, and rejected
  responses. `summarize_app_run.py` independently replayed every retained case,
  verified report/packet binding, and agreed with all eight whole-response
  rejections. The committed extraction preserves actual parsed claims rather
  than pretending its normalized whitespace has the original byte hashes.

The application image was
`ghcr.io/blondacz/gtrainer@sha256:90b7144514c27b2fc10e8498b8789ca8f42722fe3fd365ba891ad36169ff5448`,
revision `f6d879a7293ca0f07d218d31f8fe96e9fa0cc6f3`. Model/runtime identities
and the three-core/5 GiB cap were unchanged. Staging finished in 139.64 seconds;
runtime public-IP TCP egress was rejected before generation. Model selection
alone sent no inference. Each real call requested and verified unloading.

## Why the model failed

All real responses were complete, grammar-compatible JSON, under the output
budget, with `done_reason=stop`; failure was semantic, not a timeout or OOM.
The model repeatedly classified populated wellness measurements as
`unavailable_comparison`, sometimes contradicted prepared direction, and often
selected `sport_mix` with only one sport or `co_occurrence` without wellness.
The app discarded each entire response and returned no observation prose.

| Fixture | App wall time | Outcome |
|---|---:|---|
| Concurrent decreases, first cold | 63.029 s | Wrong state/kinds, no cross-metric connection |
| Same prompt, unloaded repeat | 81.026 s | Same invalid claims |
| Mixed directions | 81.023 s | One-sport mix, populated metrics called unavailable |
| Mixed sports | 93.017 s | Sport mix alone; wellness incorrectly unavailable |
| Partial HRV | 85.100 s | One-sport mix; sleep incorrectly unavailable |
| Populated zero baseline | 84.057 s | Wrong sleep state; populated metrics unavailable |
| Unchanged | 73.018 s | Wrong sleep state, all metrics unavailable |
| Sparse activity | 1.000 s | Input rejected before inference |
| Absent current wellness | 1.001 s | Input rejected before inference |
| Explicit same-weights alias | 77.019 s | Same invalid concurrent-decrease claims |

The alias switch verifies selection of a different catalogue tag, **not a
second model's quality**. Original and alias weights had identical manifest
digests. Report comparisons and prepared states were checked against synthetic
fixture totals/means; no bookkeeping or arithmetic was delegated to the model.

## Incomplete checks and harness failures

An initial attempt failed before inference because the instrument's CPU-limited
Python readiness probe exceeded kubelet's one-second default. A lightweight
bound-port marker fixed that; direct transport verification remained required.
Both the failed attempt and startup-only diagnostics cleaned up their own
temporary namespaces without touching the live app.

The corrected run completed ten requests but **did not complete the entire
qualification**: strict UTF-8 decoding of model log bytes raised
`UnicodeDecodeError` during the final marker scan. This is a runner error, not
a successful sensitive-log scan. Telemetry was collected in memory but its export
was after that scan, so no sustained health-latency, memory-peak, or thermal
series was retained. Post-unload case samples cannot establish inference peaks
or headroom. Do not cite absent telemetry as zero failures or safe resources.

Assertions for stale evidence/status, off/stale selection, injected outage and
unsupported output, unchanged synthetic history, CSRF/authentication, and logout
executed before the final scan, but their final result export was also skipped.
They are not independently replay-qualified hardware evidence. Accepted rendering
could not be observed because acceptance was zero. Actual server cancellation,
multi-model quality, sustained chart interaction, long-term resources, and
real-data end-to-end behavior remain unqualified.

Runner-only fixes now scan original log bytes without replacement decoding,
export completed controls/telemetry before scanning logs, and stream resource
samples with serialized writes. Synthetic regressions cover these paths. **These
fixes were not rerun on the Pi**; they do not retroactively repair this run or
alter its rejection rate. No further hardware run is started merely to turn a
clear model failure into a successful qualification report.

## Cleanup and decision

The owned temporary namespace, downloaded model volume, forwarding processes,
and progress mirror were removed. The live pod identity, immutable chart image,
readiness, and zero restart count remained unchanged through cleanup. No actual
health records, source key, dashboard credential, PVC, or production Secret
were mounted into the benchmark; no import or actual-health inference occurred.

**Keep AI off, no automatic default, and tasks 4.4/4.6/4.8 unchecked.** This
exact prompt/model is not usable. Do not relax the validator, salvage fragments,
raise resource limits, enable a hosted fallback, or promote/enable inference to
bypass that outcome. Live application promotion remains a separate user decision
and would not deploy a model service. Further prompt/model research, another
synthetic qualification run, or actual-data use requires applicable user direction.
