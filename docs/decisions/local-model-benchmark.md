# Pi local-model benchmark: no default selected

Task 1.5 was run on the actual Raspberry Pi 5 on 2026-09-30. All inputs and
outputs were synthetic. No real health records, activity names, credentials,
or personal notes were supplied to either model.

## Setup

- Pi: 4 Cortex-A76 cores, 7.9 GiB RAM, no swap; existing K3s core services
  remained running.
- Ollama 0.35.0, official Linux ARM64 container digest
  `sha256:0d7a1b2e50d33428f0117535a25933fa61f8868f383c8e1d07823889412c8084`.
- Isolated namespace `gtrainer-benchmark`; no Service or Ingress, ordinary
  inbound traffic denied, access through temporary loopback-only port forwarding.
- CPU limit three cores, memory limit 4096 MiB; one model and request at a time.
- Context 2048 tokens, three inference threads, temperature 0, seed 42, maximum
  384 output tokens, structured JSON schema. Cloud inference disabled.
- Three synthetic cases per model: cross-metric comparisons; incomplete wellness
  data; request for an unavailable Garmin score and a workout prescription.
- Runner, fixtures, smoke checks, and reproducible setup:
  [`../../benchmarks/ollama/`](../../benchmarks/ollama/).

## Measurements

| Candidate | Downloaded model size | Loaded-model size from Ollama | Download time |
| --- | --- | --- | --- |
| `qwen2.5:1.5b` | 986,061,892 bytes | 1240.3 MiB | 46.56 s |
| `llama3.2:1b` | 1,321,098,329 bytes | 1649.1 MiB | 68.51 s |

Model digests:
- Qwen: `65ec06548149b04c096a120e4a6da9d4017ea809c91734ea5631e89f96ddc57b`.
- Llama: `baf6a787fdffd633537aa2eb51cfd54cb93ff08e28040095462bb63daf552878`.

| Candidate / case | Wall time | Model load time | Container current memory | Container lifetime peak |
| --- | --- | --- | --- | --- |
| Qwen / cross-metric | 33.79 s | 3.61 s | 2266.2 MiB | 2275.8 MiB |
| Qwen / partial data | 22.25 s | 0.00 s | 2289.4 MiB | 2294.2 MiB |
| Qwen / out-of-scope request | 18.83 s | 0.00 s | 2303.9 MiB | 2308.7 MiB |
| Llama / cross-metric | 56.37 s | 16.72 s | 3864.2 MiB | 3875.4 MiB |
| Llama / partial data | 21.25 s | 0.00 s | 3891.2 MiB | 3897.8 MiB |
| Llama / out-of-scope request | 23.91 s | 0.00 s | 3904.4 MiB | 3910.1 MiB |

All six responses completed with `done_reason=stop`, rather than output-token
truncation. The first case for each model includes loading; subsequent cases
reuse the loaded model. Container memory includes cached downloads and file
pages; lifetime peak is cumulative across both model runs, not a distinct
per-model inference peak. In particular, do not interpret Llama's container
reading as a clean measurement of its model-only memory demand. Both models
were unloaded after their cases, and the temporary port-forward was terminated.

## Grounding review

Manual review failed both candidates, despite successful generation and
syntactically structured output:

- Qwen described a lower synthetic HRV as an improvement and inferred reduced
  stress/better health without evidence. It also invented a current sleep
  measurement and cited a nonexistent evidence ID in the incomplete-data case.
  It reduced weekly periods to individual start dates, misrepresenting coverage.
- Llama invented within-period changes and percentages not supported by the
  aggregates. It called a change from a larger value to a smaller value an
  increase, invented a missing sleep measurement, and supplied a fabricated
  Garmin fitness age in response to the out-of-scope request.
- The incomplete-data Llama response passed the simple automatic smoke checks
  while still failing semantic review. Schema conformance and citations alone
  are therefore not factual validation. Do not reuse these smoke checks as the
  production output validator.

These are synthetic benchmark failures, not observations about the user's
health. Responses echoed missing-data disclaimers but still made unsupported
claims. A disclaimer does not make such output acceptable.

## Decision and next step

Neither tested model is a suitable default for the required grounded
cross-metric observations. Their latency was under the experiment's approximate
60-second target, but quality is a blocking criterion. No default model is
selected and no hosted fallback is authorized. Factual charts must work while
AI analysis is unavailable.

This two-model, three-case experiment establishes that these configurations
failed; it does not establish that every local model is unusable. Ask the user
whether to benchmark a stronger local Pi candidate, use a separately benchmarked
local inference host, or choose a hosted alternative. Any hosted choice requires
provider-specific informed consent before transferring personal data, and must
remain behind the same model boundary. Do not silently drop the phase-one AI
requirement or mark later model-integration tasks complete.

Task 1.5's experiment and decision note are complete. Model selection remains
unresolved; a successful candidate still needs broader grounding, sparse-data,
outage, privacy, and app-resource tests in the later implementation tasks.

The user's subsequent authorization to test Ministral 3 3B and Qwen 3.5 4B with
precomputed workout/trend profiles is recorded in
[precomputed-model-benchmark.md](precomputed-model-benchmark.md). Neither follow-up
configuration qualifies as a default: semantic/evidence failures remain, and
Qwen was OOM-killed at the 5 GiB cap. The app remains AI-off; personal data was
not supplied to any model.
