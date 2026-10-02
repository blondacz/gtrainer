# Guarded local analysis: opt-in Kotlin prototype

The user authorized guarded Kotlin integration after the
[Ministral typed retest](ministral-typed-retest.md). This implements an
**experimental, closed-vocabulary local inference path**, not a selected default,
production model qualification, hosted provider, or unrestricted health coach.
No actual-health request, model-service deployment, or Pi resource-limit change
is authorized or performed by these code changes.

## Use and configuration

The deployed chart release does not have this feature until separately promoted
through the protected image PR and Flux. The feature itself stays off unless an
operator configures a local catalogue **and** the authenticated user selects a
model **and** presses **Generate observations for displayed period**. Loading or
refreshing charts, inspecting `/api/analysis-input`, and selecting a model do not
trigger inference or import. No selection is persisted; a backend restart resets
it to off. Signing out removes browser analysis; the server rechecks the session
before returning an in-flight result.

For an isolated local development environment, prepare a JSON file outside Git:

```json
{
  "endpoint": "http://127.0.0.1:11434",
  "models": [
    {
      "id": "ministral-3-3b",
      "label": "Ministral 3 3B",
      "tag": "ministral-3:3b",
      "digest": "f04aa1c738f64e13c625b82ae92504fc0260fa6723b509ed1ece0fa188179b1d",
      "experimental": true
    }
  ]
}
```

Set `GTRAINER_LOCAL_MODELS_FILE` to that file when starting the backend. Missing
configuration yields `local_not_configured`; malformed configuration yields
`local_configuration_invalid` without breaking charts. Configuration is bounded
and read at startup. The file is a catalogue, not a place for prompts, personal
values, or provider keys. Authentication and data directories remain configured
separately; see [private access](../infrastructure/private-access.md).

Up to four distinct local options can be explicitly configured. Switching uses
only the selected option and never modifies imported records. A changed or
missing model manifest fails closed, not a repull or fallback. Different models
need their own synthetic qualification; adding a catalogue entry does not prove
that model understands the grammar, is accurate, or fits the Pi.

Only these exact endpoints are accepted:

- `http://127.0.0.1:11434` for a loopback local service.
- `http://gtrainer-ollama.gtrainer-models.svc.cluster.local:11434` reserved for a
  separately reviewed private cluster service. **That service and application
  egress permission are not added by this change.**

Hosted URLs, arbitrary LAN URLs, credentials in URLs, redirects, cloud tags,
remote-model catalogue entries, non-experimental options, and duplicate model
identities are refused. Before any summary is sent, the adapter checks the
Ollama API version is `0.35.0` and the selected tag's manifest digest matches.
It rechecks the manifest after inference. This is an identity check against the
local service, not a cryptographic attestation of the running weights.

An operator must use the pinned ARM64 runtime image from the benchmark, set
`OLLAMA_NO_CLOUD=1`, and enforce no runtime internet/remote-provider egress. Any
one-time model staging/pull requires separate operator approval and networking
review; the application has no pull/download endpoint. A local API URL alone
does not establish local execution. Do not expose Ollama's unauthenticated API
on the LAN/public internet or mount health data, source credentials, or the
application database into the inference service.

## Facts and immutable evidence

The separately implemented [deterministic factual review](deterministic-factual-reviews.md)
retains every app comparison and provides rule-selected focus without inference.
Its read-only endpoint/UI and the subsequent
[separate interpretation runtime](guarded-review-interpretation.md) do not alter
the installed prototype packet, prompt, validator or zero-retry policy described
below. The new profile defaults off and refuses live inference without a qualified
resource/live-health guard; no production guard is wired by these changes.

- The backend calculates metrics, arithmetic changes, direction, units, coverage,
  missing inputs, dates, and provenance before inference. The LLM does no
  bookkeeping or arithmetic.
- A request must bind to the **SHA-256 of the exact private `/api/trends` response
  bytes**. The report response's `X-Evidence-Report-Sha256` header and
  `/api/analysis-input` use that same existing binding. It is not replaced by
  the benchmark's canonical synthetic packet hash.
- An immutable request-local snapshot prepares at most two sports' recorded
  moving-time comparisons (alphabetical sport order, or the selected sport),
  plus sleep duration and HRV. The packet uses short generated IDs; no raw record
  IDs, point arrays, account IDs, events, notes, source keys, or arbitrary user
  instructions are sent. Aggregate values and dates **are still sensitive**.
- Weight, VO2 max, calories, elapsed time, Intervals.icu load, other wellness
  measures, and every sport remain in factual charts. This prototype does not
  interpret workout segments or physiological intensity. It is deliberately
  not a substitute for the unfinished broader phase-one flow.
- At least two populated records in **each** period are required for an available
  activity and wellness comparison. This is only a minimum input gate, not a
  completeness, significance, or medical-confidence criterion. Two records on
  one date still count as two records; observed date coverage is disclosed.
- The packet's own SHA-256 guards its preparation/rendering consistency. Code
  attaches period/sport/metric evidence IDs and full `SummaryFact` support from
  the original report. No raw records are added to the model prompt to obtain
  that linkage.
- The backend recomputes the report binding after inference. Record, source
  status, or evaluation-date changes invalidate the result. Selection uses a
  monotonically increasing version, so switching away and back cannot reuse an
  old request. Changing selection cancels the client inference coroutine; a
  local server may still finish work already received. Final checks cannot
  prevent a later import after a response was emitted; refresh/import removes
  the browser's old snapshot and observations.

## Validation and rendering

The decoder grammar permits one or two observations, each with one to three
`id`/`state` references. Only `co_occurrence`, `sport_mix`, `recorded_change`, and
`unavailable_comparison` are accepted. The states are only `increased`,
`decreased`, `unchanged`, and `unavailable`.

The Kotlin validator checks the **whole response** before rendering:

- Exact JSON shape; no duplicate keys (including escaped-key aliases), unknown
  fields, prose, extra values, null entries, excessive depth/size, invalid counts,
  repeated evidence within a claim, or duplicate observations.
- Every ID belongs to this snapshot and every state exactly matches preparation.
- Co-occurrence connects activity and wellness with exactly matching earlier
  and later periods. Sport-mix requires moving time from different sports over
  the same periods. Missing-value comparisons cannot become recorded changes;
  populated or unchanged comparisons cannot be called unavailable.
- Every supplied comparison must be selected, and a real activity/wellness
  connection is mandatory. Single-metric or incomplete selections are unavailable,
  even if individual fragments would be safe. No repair or safe-fragment salvage.

After acceptance, **code**, not model prose, renders values, dates, separate
directions, non-causal wording, source support, missing Garmin labels, and
limitations. For example, the synthetic tests render recorded Ride time from
2400 to 3600 seconds, sleep from 28800 to 25200 seconds, and HRV unchanged at
42 ms over 2020-05-30–31 versus 2020-06-01–02. These concurrent descriptive
comparisons do not establish cause, illness, recovery, equivalent effort across
sports, readiness, hazard safety, or a training prescription.

Model-invented Garmin scores, diagnoses, causes, prescriptions, unsupported
states, or appended prose produce `unusable_model_output`. Sparse inputs,
outages, timeouts, busy inference, changed evidence, and changed selection have
separate unavailable reasons. Charts and source-evidence controls do not share
the inference lock. No model exception body, sensitive prompt, credential,
or raw response is logged or returned as a diagnostic. The browser never caches
analysis/selection in local storage, renders supplied HTML, or retries with a
different provider.

## Resources and qualification remain open

The subsequently authorized [published-app synthetic Pi test](guarded-app-ministral-test.md)
accepted **0/8 real Ministral responses**. Both sparse-input gates made no model
call. Whole-response rejection worked, but a final runner log-decoding failure
left the sensitive-log scan and sustained telemetry export incomplete. This
exact integration is not usable or resource-qualified; the earlier 8/10 typed
retest is not its acceptance rate. No default, production model service, or live
promotion was enabled.

The later [Granite 4.2 3B exact-app test](guarded-app-granite42-test.md) completed
the procedure, including retained telemetry and final controls, but again
accepted **0/8** real responses. Calls took 44.485–60.041 seconds; measured
Ollama lifetime peak was 4664.3 MiB, with concurrent synthetic chart-data access
available. Incomplete/invalid cross-metric selections were rejected whole.
This finite resource result does not qualify long-term production headroom or
make the unchanged prompt/model usable. No inference service or default is enabled.

The unchanged-app Qwen3 4B Instruct-2507 baseline also accepted 0/8. A separately
authorized [skill-style prompt experiment](skill-style-prompt-experiment.md)
improved four comparable development-fixture pairs from 0/4 to 2/4, but stopped
partway through after a request crossed midnight and correctly triggered stale
report rejection. The experimental transport changed only system instructions;
the app contract stayed unchanged. Semantic errors, 83–97-second instruction-card
latency and a 5120 MiB cgroup lifetime peak still prevent qualification. These
partial results and the separately labeled hosted Luna reference do not enable
a default, a production skill system or hosted transfer of actual health data.
An explicitly approved fresh full rerun subsequently completed: original
instructions accepted 0/6 and the unchanged card 3/6, with final controls/log/
history/cleanup checks passing. B still took 83–98 seconds and reached the same
cgroup lifetime peak; 50% development-fixture acceptance does not qualify it.
The approved [revised-card comparison on fresh structural variants](revised-card-unseen-experiment.md)
then accepted 1/6 with v1 versus 4/6 with v2. V2 still omitted mandatory evidence
in two complete responses; those were rejected whole. Its 73–91-second latency
and 5120 MiB cgroup lifetime peak also remain limitations. Controls and cleanup
passed, but no model default, live service or production prompt change is enabled.
The later [code-prepared focus experiment](code-prepared-review-experiment.md)
rendered complete facts independently of model selections. Qwen3 answered 4/6
requested focuses; two bounded corrections repeated the same irrelevant choices.
This new benchmark-only output contract is not installed-app acceptance. Qwen3.5
failed during model staging before any inference, with no new OOM established;
its quality remains untested in this protocol. Both owned cleanup checks passed.
The subsequently approved diagnostic rerun staged successfully and accepted 5/6
requested focuses under that same prepared-focus protocol, unchanged after one
corrective attempt. Peak remained 5120 MiB with no observed OOM/restart; all
controls and cleanup passed. This qualifies task 7.2's experimental procedure,
not the production prototype or coaching. The earlier staging failure remains
unexplained; no cap increase, default selection or live inference is enabled.
The later [fresh prepared-review suite](fresh-prepared-review-experiment.md)
stopped on a controller `kubectl get pod` timeout during its fifth case. It
finalized four cases (2/4 relevant) and retained five first-pass selections
(2/5 relevant); one corrective response is unrecorded and no full-suite score is
claimed. Owned cleanup/live-state checks passed, but final controls/sparse gates
were not reached. This does not establish a new model timeout/OOM or qualify
production inference; the frozen partial evidence is retained unchanged.
After separately approved capture fixes and restarts, the unchanged fresh suite
completed with durable Pi-side evidence: **5/10 first-pass and final relevant
reviews**, despite all 15 responses passing the closed contract. Five corrections
repeated unsuitable selections. Both sparse gates and final controls/cleanup passed;
peak again reached 5120 MiB with no observed restart or health/guard failure.
This demonstrates complete benchmark capture, not reliable focus relevance,
sustained-resource qualification or accepted production rendering. Earlier partial
captures remain separate, live AI stays off and no further run is authorized.

The request retains the benchmark settings: 2048-token context, 256 output
tokens, three CPU threads, temperature 0, seed 42, and thinking off. HTTP requests
have a 120-second bound; the full inference operation has a 130-second bound.
`keep_alive: "0s"` requests unloading after each response, intentionally losing
the benchmark's warm-cache advantage. Cold benchmark latency was 102.63 seconds,
so timeouts/unavailable states remain expected possibilities.

Only one application inference operation is admitted at a time. Any future
service must also set `OLLAMA_NUM_PARALLEL=1`, `OLLAMA_MAX_LOADED_MODELS=1`, and
a bounded queue, because canceled client requests may leave server work running.
Keep the previous three-core/5 GiB cap; **do not raise it** to make this work.
No deployment manifest, memory budget, live image, or source-network pin is
changed here. The typed Pi run reached 5120 MiB; sustained memory/thermal
headroom and this **new app packet/prompt** have not been qualified on hardware.
The retest's 8/10 acceptance rate cannot be attributed to this integration.

Tests use synthetic Kotlin records, fake inference providers, Ktor MockEngine,
and UI fixtures. They cover exact outbound summary/settings, identity/redirect/
cloud rejection, semantic and malformed-output rejection, zero baselines,
partial/sparse data, switching, stale snapshots, cancellation, timeouts, no
fallback, authentication/CSRF, logout during inference, unchanged stored history,
and independent charts. They establish the code boundary, not model quality or
sustained Pi resource safety.

Before enabling any Pi model service, separately approve a synthetic hardware
integration/resource run of this exact boundary while checking dashboard
responsiveness, memory/thermal headroom, identity, and no sensitive logging or
external transfer. Before using actual health data, obtain the applicable user
direction. Deploy any application release only through protected PR, tested
ARM64 publication, digest-only promotion, and Flux. Do not enable a default or
hosted fallback to bypass failures.

## Hosted consent and remaining work

There is no hosted provider or consent bypass in this prototype. A future hosted
option must disclose provider, data categories, destination, retention/sharing
risks, and the intended request, and obtain explicit informed consent before
transfer. Selecting a local model, logging in, installing this code, or opening
the input endpoint is **not** hosted consent. Task 4.5 remains unchecked.
Manual events, the full dashboard, actual-health end-to-end verification,
hosted-provider behavior, and production model qualification remain unfinished.
