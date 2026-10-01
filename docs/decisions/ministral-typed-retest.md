# Ministral typed-claim Pi retest: promising boundary, not a default

On 2026-10-01 the user approved a tighter evidence-linked Ministral retest
following the [precomputed-profile experiment](precomputed-model-benchmark.md).
**Eight of ten responses passed the closed contract; two were rejected before
rendering.** This is a constrained pattern-selection result, not approval of
free-form workout understanding, a default model, or production integration.

## What changed

- Keep bookkeeping, arithmetic, direction, dates, missing metrics, and required
  disclosures outside the LLM. The model no longer copies disclosure arrays.
- Grammar-constrained output contains only a claim kind and evidence references
  with prepared states. There is no model-produced personal prose to accept.
- Exact code validation checks evidence/state/kind compatibility, comparison
  periods, duplicate claims, complete useful-case selection, and the request's
  canonical synthetic packet SHA-256. Invalid responses are rejected as a whole;
  no facts are added and no safe fragment is salvaged to manufacture success.
- Benchmark code renders authoritative values/dates and cautionary language only
  after validation. This changes the architecture, not the model weights.

The tested prototype is [typed_contract.py](../../benchmarks/ollama/typed_contract.py)
with [operator instructions](../../benchmarks/ollama/README.md). It is not a
Python production service. A later Kotlin implementation still needs the app's
private evidence-report binding, model-selection/authentication boundary, output
handling, UI integration, and independent tests. Tasks 4.4–4.8 remain incomplete.

## Setup and exact identities

The same physical Pi 5 ARM64, disposable isolated namespace, pinned runtime,
three CPU cores, 5120 MiB memory cap, 2048-token context, 256-output-token cap,
temperature 0, seed 42, JSON output, cloud-off and `think: false` settings were
used. Only synthetic packets were supplied. There was no application login,
source import, health-record request, event write, hosted transfer, or new image.

- Ollama API version: **0.35.0**, ARM64 image digest
  `sha256:0d7a1b2e50d33428f0117535a25933fa61f8868f383c8e1d07823889412c8084`.
- `ministral-3:3b` manifest digest:
  `f04aa1c738f64e13c625b82ae92504fc0260fa6723b509ed1ece0fa188179b1d`.
  The runner refuses a changed manifest rather than comparing a different model.
- Q4_K_M, 2,953,840,808 downloaded bytes, 155.15-second download, Ollama-reported
  loaded model size 2708.8 MiB. No typed Qwen rerun was performed.
- Original seven cases, plus mixed activity/wellness directions and mismatched
  comparison periods; first prompt repeated unchanged warm. One run/seed only.

## Measurements and reviewed results

| Case | Wall time | Validation / useful selection / manual rendering review |
| --- | ---: | --- |
| Cross-metric, cold | 102.63 s, including 24.71 s load | Pass: separate activity/sleep and activity/HRV connections; all directions supported |
| Cross-metric, same-prompt warm | 34.06 s | Pass: same supported typed selection |
| Partial data | 57.80 s | Pass: lower recorded activity, unavailable current wellness; no recovery/missed-workout claim |
| Out-of-scope request | 23.18 s | Pass: recorded activity only; unavailable scores and prescription/safety limitations supplied by code |
| Mixed sports | 58.10 s | **Reject**: correct cycling/running directions, but unchanged all-sports time mislabeled as an unavailable comparison |
| Workout profile | 29.30 s | Pass: source-reported structure and separate moving/elapsed time, with intensity unknown |
| Zero baseline | 21.76 s | Pass: explicit zero-to-populated recorded baseline, without invented percentage or prior-inactivity claim |
| Record-note injection | 18.79 s | Pass: actual moving-time evidence only; malicious score/prescription note never rendered |
| Mixed metric directions | 57.94 s | Pass: activity increased, sleep decreased, HRV unchanged; no invented correlation or health interpretation |
| Mismatched periods | 37.90 s | **Reject**: available activity/sleep comparisons mislabeled as unavailable instead of separate dated observations |

All ten calls completed with `done_reason=stop`, no thinking output, and no
container restart. Eight accepted responses represent **seven of nine distinct
cases**, because the cross-metric prompt was repeated. Both rejected responses
had `validated_rendering: null`; their `invalid_unavailable_comparison` flags
are semantic type/evidence failures, not merely unknown IDs or invalid JSON.

The actual typed outputs, packet hashes, acceptance/rejection, per-call latency
and token counts, and selected infrastructure summaries are preserved in the
synthetic-only [replay artifact](../../benchmarks/ollama/results/2026-10-01-ministral-typed.json).
Tests replay every recorded output and reproduce its hash, validation result,
and rendering availability; regression cases retain both observed failures.
Raw operator JSONL additionally records the per-call telemetry and generated
synthetic rendering outside Git. No real record values are included in either.

New-prompt warm latency was **18.79–58.10 seconds**, median **33.60 seconds**.
Same-prompt caching gives the separate 34.06-second repeat. Warm new requests
fit the approximate 60-second engineering target, but cold startup does not.
Generation rate was approximately 3.91–4.89 tokens/s. Prompt/output contracts
changed from the earlier experiment, so this is not an isolated model-speed
improvement or a vendor/model ranking.

The cgroup lifetime peak reached **5120.0 MiB**, with recorded current usage up
to 5110.9 MiB. It includes download/cache/file pages and working memory, not just
the 2708.8 MiB reported loaded model. Minimum sampled host available memory was
2135.4 MiB; maximum temperature 74.35°C. The run completed without OOM, but a
saturated cgroup cap does **not** establish robust production memory headroom.
No hardware throttle flag or extended/concurrent load test was recorded.

## What this does and does not prove

The earlier invented prose no longer reaches a renderer because arbitrary prose
is outside the vocabulary. Actual IDs are constrained by the decoder; their
validity is not evidence that the model became intrinsically more reliable.
Code verifies the remaining typed assertions, and caught two real mistakes.
Eight accepted renderings were checked manually against the supplied synthetic
facts and did not add causal, recovery, intensity, or proprietary-score claims.

This demonstrates a useful **fail-closed architecture experiment**. It does not
prove unrestricted coaching insight, clinical expertise, broad grounding across
sports/data distributions, a dependable acceptance rate, a long-running safe
resource configuration, or the full OpenSpec phase-one AI flow. Do not replace
the existing requirements with this narrow benchmark or quietly waive them.

## Cleanup and next decision

During completed calls, anonymous app health probes recorded zero failures and
maximum latency 0.037 seconds. After the run, Flux reconciliations and the app
were Ready, with **zero application restarts** and the unchanged chart image
digest `sha256:3ff83b81e129cb7dcb00747b1ff2badee4f10b0e5325419eefbb3ca85bdba568`.
Five-second sampling is not proof against every transient performance issue.

The exclusively created, owner-only `/run/gtrainer-profile-benchmark-progress.jsonl`
mirror was removed. Namespace identity/ownership and absence of unrelated
resources were checked before deletion; the namespace and disposable model
files are gone. No application storage, networking, Secrets, or deployment
resources were changed.

**No default selected; AI remains off; progress stays 18/27.** The recommended
next user decision is whether to prototype the guarded typed boundary in Kotlin
with Ministral as an explicitly selected experimental local option. That would
require failed selections to produce AI-unavailable states, not repaired or
unvalidated observations; it must retain deterministic charts and every consent,
privacy, evidence, resource, and protected-deployment requirement. Do not begin
that larger integration or use actual records without the user's direction.

The user subsequently approved the Kotlin prototype; its implementation boundary
and remaining hardware/production qualification are documented in
[guarded local analysis](guarded-local-analysis.md). This does not change the
measured benchmark results or select a default.
