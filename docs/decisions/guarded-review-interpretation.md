# Separate guarded review interpretation

The user approved continuing task 7.3 after deterministic factual reviews. This
adds an independently selected, closed-vocabulary app interpretation runtime and
bounded correction policy. It does not deploy or enable inference, qualify a
model, rerun benchmarks, broaden Pi resources or replace the installed prototype.

## Separate contracts and selection

`review-focus-app-v1` is a new app protocol, distinct from both the installed
`/api/analysis` contract and the frozen synthetic `prepared-focus-v1` benchmark.
The existing model prompt, packet, validator, selection and 120/130-second bounds
stay unchanged. Both profiles share the same single inference slot in normal app
wiring, but never share selection, prompts, validation or correction policy.

The application prepares every factual comparison before optional interpretation.
The new model packet includes only generated comparison/candidate IDs, supported
kind, sport/metric, direction, coverage and periods. It excludes measured numeric
values, raw record IDs/point arrays, source credentials, notes and events. Large
packets above 6000 UTF-8 bytes are refused, not truncated. The full factual view
remains available even when optional inference is refused or rejected.

Model output must be exactly `{"focusIds":["candidate ID"]}` with one or two
unique supported IDs. Strict parsing rejects duplicate/escaped duplicate keys,
malformed/trailing/oversized/deep JSON, additional prose/fields and invalid types.
Validation checks the exact immutable packet and factual snapshot, candidate
support and requested-focus relevance. Valid-but-irrelevant IDs fail the whole
response. A partly relevant response cannot be salvaged. All accepted text, dates,
values and support are rendered from code-owned facts; no personal model prose is
admitted. This validates descriptive selection, not physiological reasoning or
added value over the deterministic rules.

## Explicit private API

- `GET /api/review-models` reports the separate catalogue, selection version,
  enablement, correction setting and whether a qualification guard is configured.
  Reading configuration never calls a provider.
- `PUT /api/review-models` requires `profile`, nullable `modelId` and explicit
  `enabled`. `allowCorrectiveAttempt` defaults false and cannot exceed the operator
  policy. Model/profile/enablement/correction changes increment the version,
  cancel in-flight work and prevent obsolete publication. Selection is memory-only
  and starts disabled after restart.
- `POST /api/review-interpretation` requires `profile`, `oldest`, `newest`, optional
  `sport`, `focus`, the displayed `evidenceReportSha256`, `modelId` and
  `selectionVersion`. It returns separately named `interpretations` and bounded
  attempt metadata, not replacement factual groups or repaired model fragments.

All endpoints use existing authentication and `no-store` responses. Writes require
the existing exact Origin and CSRF checks and bounded strict private request
parsing. The session and selection are rechecked before publication; logout denies
in-flight responses. There are no storage/upstream writes or automatic imports.
The contracts remain distinct. The subsequent [dashboard controls](../dashboard.md)
provide explicit separate review selection, configuration and queue metadata;
they do not silently switch the old prototype's model selection or validator.

## Deadlines, correction and faults

The new operator execution policy defaults to 120000 ms per call and 130000 ms
per job, with correction disabled. Its separately configurable bounded ceilings
are 240000/600000 ms; these are implementation guardrails for this experimental
profile, not a claim that a fixed production background budget was approved.
The per-call deadline covers the whole attempt, including pre/post qualification
and evidence checks, packet copying, loading, generation and validation; all of
that also consumes the total-job budget. Explicit monotonic checks withhold
acceptance after non-suspending work overruns. Correction headroom is rechecked
immediately before generation, after its preflight checks. `attempt_timeout`
distinguishes whole-attempt expiry from `job_timeout`; neither permits correction.
The original prototype's limits do not change.

At most one corrective call is allowed, only when operator policy, the explicit
user selection and (for queued work) the preset budget enable it, enough full-call budget remains, and the first
whole response failed an allowlisted structural/relevance check. It receives only
a fixed validator reason and the same packet, never the rejected output text.
The second response must pass all validation independently. Facts or fragments
from the first response are never merged into the second.

Timeout, transport/provider failure, cancellation, changed selection/evidence or
qualification-guard failure never causes correction, larger limits, another model
or hosted fallback. Attempts retain number/status/fixed reason only; personal raw
inputs/outputs and exception diagnostics are not logged or persisted.

## Local adapter and deliberate live gate

Only `GTRAINER_REVIEW_MODELS_FILE` can configure the new catalogue; the original
`GTRAINER_LOCAL_MODELS_FILE` cannot silently opt into this protocol. Its private
JSON file requires `profile: "review-focus-app-v1"`, the existing restricted local
`endpoint`/`models` structure, and optional `policy`. Parsing is bounded and strict;
hosted URLs, URL credentials, cloud/remote models and invalid/duplicate options
remain forbidden. Invalid configuration fails closed without hiding facts.

The separate Ollama adapter verifies runtime `0.35.0` and the selected manifest
before sending any packet and rechecks the artifact afterward. It forbids
redirects, pull endpoints, thinking/truncated/wrong-identity envelopes, oversized
responses and sensitive diagnostics. Generation retains 2048 context, 256 output
tokens, three threads, temperature zero, seed 42 and `keep_alive: "0s"`.

**No qualified production resource/live-health guard is wired.** The runtime
requires an injected guard observing model restart/OOM/resource state and live
app health; HTTP availability alone is insufficient. Missing, failed or unreadable
guard state prevents inference and never retries. Environment configuration plus
user selection cannot bypass this missing guard. Tests inject synthetic guards
and MockEngine providers only. Three-core/5 GiB limits remain unchanged; no model
service, networking, cluster credentials or actual health-data test is added.

## Completion boundary

Task 7.3 covers app code, independently validated attempts and factual/optional
separation and is complete as local implementation with synthetic verification.
This is not completion of production tasks 4.4/4.6/4.8, sustained Pi
qualification, accepted physical rendering or end-to-end actual-data verification.
The subsequent [preset/scheduler implementation](review-presets-and-scheduler.md)
completes task 7.4 locally and now documents the subsequent durable queue and
review controls. A queue receiver is wired; a production inference guard is not.
The earlier Qwen3.5 fresh result remains 5/10 relevant with no corrective rescue;
synthetic adapter/service tests are not another model-quality result. Live AI
stays off and no publication/promotion/deployment or further benchmark is implied.

Local verification passed **99 backend tests** and the backend distribution build,
**73 frontend tests** and TypeScript/Vite build, **121 benchmark tests**, and
**47 utility tests**. Strict specs, documentation links and diff checks passed.
Regressions include slow pre/postflight checks, non-suspending deadline overrun,
correction headroom consumed by preflight, cancellation/shared-slot release,
failed qualification guards, stale source/model evidence, API auth/CSRF/logout,
strict adapter envelopes/configuration and whole validity/relevance rejection.
Independent review confirmed deadline/headroom fixes with no remaining findings.
All six earlier captures still replay exactly; frozen benchmark and installed
prototype prompt/packet/adapter files remain unchanged. Overall implementation
progress at this runtime milestone was 22/33 (23/33 after task 7.4). Later queue/UI
work does not supply or qualify the production guard through these synthetic tests.
