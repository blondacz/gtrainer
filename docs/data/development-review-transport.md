# Connected-review development Ollama transport

`OllamaConnectedReviewProvider` is a local-only adapter for the separately owned development runtime. It targets exactly `http://127.0.0.1:11434`; it does not use the Mac runtime on port 11435, the Pi/shared runtime, a proxy, hosted fallback, or model discovery defaults. A runtime token identifies epoch, generation, container and desired binding, but does **not** authorize access by itself: the supervisor's narrow ownership check must affirm current durable ownership before and after provider work. The unconfigured check refuses. Deploying or starting a runtime is not part of this adapter.

The adapter verifies configured Ollama version, tag and manifest digest before chat and again before returning a candidate for publication; uses bounded streaming reads with response-scoped closure, redirect refusal, explicitly zero HTTP retries and finite timeouts; requests non-streamed, non-thinking JSON with explicit immutable settings and `keep_alive: 0`; and returns local cost `null`. Only completed assistant envelopes are accepted. Correction accepts only `invalid_output`; rejected text is never included. Failures expose fixed reasons, not response bodies or transport causes. Cancellation propagates. The prompt/output remains untrusted and is independently validated by the existing draft validator. The run owns/closes its private client; the CIO connection-attempt count is one. None of these transport observations replaces authoritative process-stop evidence.

Application responses remain capped at 12 KiB UTF-8 (and the validator separately caps text at 12,000 characters). The Q3 benchmark's 24 KiB response ceiling is a different experiment and must not be conflated with application acceptance.

## Disabled example

`benchmarks/connected-review/development/qwen3-q3.example.json` records an explicit candidate binding only. It does not enable the provider or choose a default. The operator must independently verify the exact tag/digest and runtime version at startup/send; this example is not qualification evidence. No model download, benchmark run, host access, deployment, or live inference is performed by adding it.
