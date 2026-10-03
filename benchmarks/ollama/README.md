# Synthetic Pi model benchmark

This is task 1.5's development benchmark, not the app's model deployment.
Do not feed it real records, keys, names, or health values.

The setup pins the official Ollama 0.35.0 Linux ARM64 image digest and creates
only resources in `gtrainer-benchmark`. It provides no Service or Ingress,
denies ordinary inbound pod traffic, and uses loopback-only port forwarding
while the runner is active. Memory is limited to 4 GiB and CPU to three cores;
one model/request is active at a time. Downloads require outbound access to
the official model registry. Cloud inference is disabled.

The two candidates are Qwen 2.5 1.5B and Llama 3.2 1B. Model digests and actual
sizes are captured after downloading, since model tags can change. Respect
their respective model licenses when distributing an eventual deployment.

Run the checks locally:

```sh
python3 -m unittest discover -s benchmarks/ollama -p 'test_*.py'
```

Apply `pi-setup.yaml` using the Pi's local `sudo k3s kubectl`, wait for the pod
to become Ready, and run `run_on_pi.py` on the Pi with permission to invoke
`k3s kubectl`. It requires only the standard Python library and prints JSON
results with synthetic model output. Its temporary port-forward process is
terminated when the runner exits.

Record cold/load and subsequent-case wall times, loaded-model size, cgroup
current/peak memory, completion/truncation, evidence/limitations smoke checks,
and manual semantic review. Container lifetime peak includes downloads and
cache and is not a separate per-model inference peak. Automatic smoke checks
are deliberately not claimed as a complete factual/safety validator.

For this interactive descriptive-summary experiment, assess whether completed
responses stay within a roughly 60-second latency budget and memory headroom
while remaining grounded. This is an engineering target, not a user-agreed SLA.
If either grounding or usability fails, do not silently choose hosted inference
or represent a candidate as a suitable default. Record the limitation and seek
a model choice as required by the design.

After results have been recorded, remove only the resources this benchmark
created:

```sh
sudo k3s kubectl delete namespace gtrainer-benchmark
```

That removes its pod and model PVC; the local-path reclaim policy is `Delete`.
Image layers can remain in the standard K3s cache until garbage collection.
Do not use this cleanup command on a namespace containing unrelated workloads.

## Precomputed-profile experiment: Ministral and Qwen 3.5

The follow-up uses `precomputed-pi-setup.yaml` and `run_profiles_on_pi.py`, not
the original manifest/runner above. Candidates are **Ministral 3 3B Instruct**
(`ministral-3:3b`) and **Qwen 3.5 4B** (`qwen3.5:4b`). Neither is automatically
selected for the app. The existing benchmark remains available as historical
evidence; its failed defaults are not reinstated.

### Work division and cases

All facts are explicitly synthetic. Fixture preparation computes differences,
percentages, directions, sport totals, and zero/missing-baseline outcomes before
inference. The production equivalents already live in Kotlin `Trends.kt`;
this benchmark does not add a Python production processing service or imply
that the app already imports workout segments.

The model receives prepared workout/trend statements with dates, units, evidence
IDs, and limitations. It interprets those facts without reconstructing sessions,
performing bookkeeping, or reproducing numbers/dates in prose. The application
would render authoritative numbers and time ranges from the referenced evidence.
This narrows the failure surface, but does not prove that interpretations are
valid or that the model knows sport physiology reliably.

Seven cases cover concurrent activity/sleep/HRV decreases, missing current
wellness, unsupported scores/prescriptions, opposite sport changes with an
unchanged total, source-reported workout segments versus unknown intensity,
zero baseline, and an untrusted record note requesting fabricated guidance.
The first prompt is repeated unchanged once warm. Temperature 0/seed 42 are
controlled experiment settings, not claimed optimal vendor settings.

### Isolation and resource limits

- Namespace: `gtrainer-profile-benchmark`, with an ownership label. Refuse to
  reuse it if it already exists or contains unrelated resources.
- Same digest-pinned official Ollama 0.35.0 ARM64 image as the original experiment.
- Three CPU cores, 5 GiB memory, 5 GiB ephemeral-storage limit; one model/request.
- Disposable disk-backed `emptyDir`, **no PVC**, health records, app Secret mount,
  service-account token, Service, Ingress, host port, or host network.
- Ordinary inbound pod traffic denied; only temporary loopback port forwarding.
  Outbound registry downloads are allowed. Cloud inference is disabled.
- Context 2048, at most 256 output tokens, structured JSON, `think: false`.
- Fresh pod for each candidate, so model downloads and cgroup lifetime peaks
  do not accumulate across candidates. Deleting the exact pod discards its files.
- Anonymous application `/healthz`, readiness/restarts, host available memory,
  and temperature are measured without login or private-record reads.
- Stop if the app becomes unhealthy/restarts or sampled available host memory
  falls below 768 MiB. HTTP generation timeout is 180 seconds; pod lifetime
  deadline is two hours. The response reader is capped at 128 KiB.

The manifest allows up to 5 GiB inference memory because the earlier 4 GiB cap
was sized for much smaller models. A successful run still needs measured
headroom for the app/cluster; a download size is not a runtime-memory estimate.

### Operator procedure

Never run this in CI or supply it actual records/credentials. Check current Pi
RAM, disk space, app readiness, and that the temporary namespace is absent.
Apply the follow-up manifest using the Pi's local `sudo k3s kubectl`; wait for
`pod/ollama` in that namespace to become Ready. Stream the runner over the
existing authenticated SSH connection, running remotely as:

```sh
sudo -n python3 - --model ministral-3:3b
```

Its stdin is the complete `run_profiles_on_pi.py` file. Save stdout as synthetic
JSONL; do not interpret successful exit or smoke checks as semantic approval.
Afterwards delete **only** this namespace's `pod/ollama`, wait for removal, and
recreate just the Pod document from the manifest. Wait for Ready and run again
with `--model qwen3.5:4b`. The runner refuses a nonempty model catalogue.

Record each exact downloaded digest, actual Ollama version/capabilities, cold
and same-prompt warm response times, generation rate, completion/truncation,
loaded-model size, memory and host samples, health probes, and final app state.
Container lifetime peak includes download cache/file pages and is **not** a
model-only inference peak. Host/health samples are spaced five seconds apart;
they are not proof against every transient or a full performance/load test.

Review every synthetic response against its case evidence, including direction,
sport, coverage, intensity uncertainty, and causal/prescriptive overreach. Schema,
citations, numeric screening, and limitation IDs cannot validate the semantics.
The original approximate 60-second engineering latency target still applies;
report slower results rather than changing the target to declare success.

Finally verify the namespace ownership/identity and that it contains only the
created Pod/NetworkPolicy, then remove `gtrainer-profile-benchmark`. This deletes
temporary model files, not application data. Keep factual charts and AI-off
behaviour unchanged until selection and full app integration are separately tested.

The 2026-10-01 physical run and per-case semantic review are recorded in
[precomputed-model-benchmark.md](../../docs/decisions/precomputed-model-benchmark.md).
Ministral completed but failed grounding; Qwen was OOM-killed at the cap after
six responses. Neither is a selected default. Numeric/evidence schema screening
must not be presented as production validation or complete semantic approval.

## Authorized Ministral typed-claim retest

The user authorized a tighter evidence-linked Ministral retest after reviewing
the failures above. It uses the same setup manifest, model digest, resource cap,
sampling settings, and original seven synthetic cases. Two added cases test
mixed activity/wellness directions and mismatched comparison periods. The first
prompt is again repeated unchanged warm: ten requests across nine cases.

The runner's default `--contract freeform` retains the original experiment.
`--model ministral-3:3b --contract typed` imports `typed_contract.py` and refuses
a changed Ministral manifest digest. It does not allow a typed Qwen rerun or
increase the Pi memory cap. The operator must make both Python modules available
on the Pi; they can be loaded from SSH stdin into in-memory modules without
installing files or using application credentials.

### Closed output vocabulary, not unconstrained interpretation

The model outputs one or two objects containing a **kind** and **evidence**.
Each evidence reference contains an actual ID and its prepared state. Allowed
kinds are `co_occurrence`, `sport_mix`, `recorded_change`,
`unavailable_comparison`, and `workout_profile`. States describe prepared
directions, unavailable comparisons, or documented workout-profile facts.

- The per-case JSON grammar limits references to actual evidence IDs. This is
  a decoding constraint, **not** proof that the model learned to cite reliably.
- All possible kinds/states remain in the grammar, including choices that are
  wrong for that evidence. Exact validation rejects direction/state mismatches,
  invalid kind/evidence combinations, duplicate evidence/observations, and
  cross-metric connections across mismatched date ranges.
- A safe but incomplete selection does not pass the case: useful-case coverage
  is evaluated separately. The validator never adds facts to repair a response.
- The model does not copy numbers, dates, missing-metric lists, or limitations.
  Those are already known to code and need not become LLM bookkeeping tasks.
- Only after the **whole response** passes does benchmark code render personal
  prose, authoritative numbers/dates, evidence references, and every input
  disclosure. Arbitrary model prose, reasoning, scores, diagnoses, prescriptions,
  and intensity classifications are outside the contract and rejected.
- SHA-256 binds validation/rendering to the exact canonical synthetic packet
  used for that request. This is **not** the live app's HTTP evidence-report
  binding; a later Kotlin implementation would need to preserve that boundary.
- JSON parsing rejects duplicate keys and non-finite constants. Incomplete or
  unexpected-thinking generations produce no accepted rendering.

`test_typed_contract.py` exercises supported examples and malicious/malformed
counterexamples independently of any live inference. The Python validator and
renderer are benchmark prototypes, not a production service or an implementation
of tasks 4.4/4.6. Passing this narrow pattern-selection experiment cannot prove
free-form workout understanding, clinical expertise, useful training advice, or
the full phase-one flow. Do not quietly substitute these limits for the OpenSpec
requirements or enable inference on actual health data from this result alone.

### Progress and failure evidence

The runner emits `case_started`, request index/total, completed output and
validation/rendering fields, and a bounded error-type/monitor summary on request
failure. Resource health samples and fresh-pod memory accounting remain unchanged.
The operator can mirror synthetic JSONL to an owner-only, exclusively created
temporary file on the Pi for live viewing:

```sh
sudo tail -f /run/gtrainer-profile-benchmark-progress.jsonl
```

The mirror contains only synthetic benchmark output and non-record infrastructure
telemetry. Never mirror production prompts or health payloads. Refuse to overwrite
an existing file; remove only the file created by this run when the runner exits.
Namespace/model cleanup remains the ownership-checked procedure described above.

Physical results and qualification limits are recorded in
[ministral-typed-retest.md](../../docs/decisions/ministral-typed-retest.md).
Eight of ten responses passed the closed contract; two were rejected as whole
responses. The synthetic replay artifact retains actual typed outputs and exact
packet hashes. This does not select a default or authorize app integration.

## Published Kotlin app → Ministral integration test

After approving the guarded Kotlin prototype, the user separately approved an
isolated synthetic Pi integration/resource test. `run_app_on_pi.py` uses the
**published, CI-verified ARM64 application digest**, not a Python replacement
for application processing. It does not merge the live image-promotion PR or
deploy an inference service for the user's dashboard.

### Scope and evidence

- `app_fixtures.py` exclusively creates a new version-1 SQLite database with
  synthetic normalized records. Every source/ID is explicitly synthetic. No
  production database, backup, dashboard credential, source key, or actual value
  is read/copied/mounted. This bypasses ingestion and does **not** retest the
  upstream adapter or real-data end-to-end flow.
- The temporary catalogue starts off and contains the explicitly selected candidate plus a clearly
  labeled tag alias of the **same weights**. Switching verifies option selection
  and unchanged stored records; it does not establish quality for another model.
- The real Kotlin app prepares all aggregates, states, evidence, grammar, and
  prompts, performs local HTTP inference, validates output, and renders facts.
  A separate synthetic transport instrument records exact bytes between that
  app and Ollama, preserving normal requests/responses without repairs. The
  independent oracle checks known fixture totals/means and the complete outbound
  summary/settings. Input/report hashes and replay preserve the app's binding.
- Eight fixture ranges cover concurrent decreases, opposite metric directions,
  mixed sports, partial HRV, populated zero baselines, unchanged comparisons,
  sparse activity, and absent current wellness. First-prompt repeat and explicit
  alias selection produce ten app requests; two input gates should make no model
  call, leaving **eight real inference attempts over six distinct usable cases**.
- A changed source-status snapshot, off selection, stale selection version,
  missing CSRF, anonymous access, and logout have separate checks. Synthetic
  transport outage and unsupported-output injection are clearly labeled;
  **neither is a model response or part of its acceptance-rate denominator**.
  No rejected output is repaired into an apparent success.

### Isolation and limits

Namespace `gtrainer-app-benchmark` must be absent. Its namespace, pod, policies,
and immutable fixture ConfigMap carry a unique owner token. There is no Service,
Ingress, PVC, hostPath, host networking/port, service-account token, real Secret,
or production mount. A disposable non-root pod contains the app, pinned Ollama
0.35.0, and a digest-pinned Python capture instrument. All three have read-only
root filesystems and separate bounded writable disposable volumes.

Model limits remain **three CPU cores, 5 GiB RAM, 5 GiB storage**, one loaded
model/parallel request, 2048 context tokens, 256 output tokens, temperature 0,
seed 42, cloud off, and thinking off. The temporary app additionally has a
512 MiB heap/1 GiB container cap; the instrument has 128 MiB. These temporary
containers add test overhead without changing any production resource limit.
The app's `keep_alive: "0s"` requests unload after every call; the repeated
prompt is **not** the earlier benchmark's kept-loaded warm repeat.

Ordinary incoming pod traffic is denied. Temporary root-owned loopback port
forwards expose only the synthetic app/Ollama and the live app's **anonymous
`/healthz`**. Staging downloads only the approved model, then a deny-all egress
policy is installed and a public-IP TCP connection must fail before generation.
Local loopback inference remains available; there is no runtime cloud/provider
egress or application source configuration.

The runner samples live/synthetic anonymous health latency, host available RAM,
temperature, and each temporary container's current/lifetime-peak cgroup memory
roughly every five seconds. Live readiness, restart counts, pod identity, and
image must remain unchanged. Stop and remove the exclusively owned temporary pod
if the live health check fails, host availability falls below 768 MiB, or
temperature reaches 85°C. Temporary failures/restarts are not hidden or retried
as successes. The pod has a two-hour deadline; HTTP application inference retains
the application's original time bounds, not a benchmark-only increased budget.

Cgroup lifetime peak includes downloads/cache/file pages, not just model working
memory. Sampling/control-plane calls and the buffering transport instrument add
overhead. In particular, the instrument can continue an upstream read after an
app client timeout; this is not a direct-server cancellation/resource test.
Five-second samples cannot exclude every transient, prove throttle-free operation,
or qualify long-term/concurrent production resource safety. A saturated 5 GiB cap
does not become proven headroom merely because this finite batch completes.

### Operator procedure

Run local synthetic tests first; CI may run tests but is forbidden to contact
the home cluster. The packager itself creates no connection or cluster resource:

```sh
python3 -m unittest discover -s benchmarks/ollama -p 'test_*.py'
python3 benchmarks/ollama/package_app_run.py \
  --owner <new-32-character-lowercase-UUID-hex> \
  --candidate <explicitly-approved-model-tag> --approve-synthetic-run > <private-temporary-bootstrap>
```

Stream that bootstrap over the existing authenticated, host-key-checked Pi SSH
connection to `sudo -n python3 -`, retaining stdout as private synthetic JSONL
and stderr separately. Do not supply production credentials or a pre-existing
database. The runner verifies at least 5600 MiB available host RAM, 7 GiB free
disk, Ready live app, an absent benchmark namespace, and an exclusively created
mode-600 progress file before doing work. Its exclusively created fixture
database is deleted after ConfigMap staging.

While active, an operator may view the **synthetic-only** mirror:

```sh
sudo tail -f /run/gtrainer-app-benchmark-progress.jsonl
```

The instrument's exact-byte capture is allowed only for this explicitly synthetic
pod; never attach it to real-health inference. No dashboard cookies/CSRF headers
or login payloads go into the model transport capture or retained result report.
The app/model log check inspects distinctive synthetic record/prompt/fault and
credential markers without emitting logs or tokens. This marker scan is not a
proof against every possible sensitive numeric log entry in a future release.

The runner checks namespace UID/owner and resource identities before deleting
only its temporary namespace, then terminates its exact forwarding processes and
removes its progress mirror. Cleanup leaves the live app, data/PVC, Secrets,
Flux, networking, image, and memory limits untouched. If cleanup fails, inspect
only this namespace's ownership/resources before removal; do not issue broad
cluster or filesystem deletes. Standard image caches can remain until normal
K3s garbage collection.

`app_replay.py` is an independent **synthetic-only** whole-response comparison
oracle, not a Python production model boundary. Retain actual packet/response
bytes, report binding, per-case acceptance/rejection and rendering, and telemetry;
review every accepted observation against its synthetic support. Completed
requests, grammar-compatible JSON, an alias switch, and marker scans do not select
a production default or authorize actual-health inference.

The [actual published-app run](../../docs/decisions/guarded-app-ministral-test.md)
accepted **0/8** inference responses. Both sparse inputs made no model call.
The final log-decoding failure prevented the run from exporting its telemetry
and final controls: do not treat this as a completed resource/log qualification.
The later runner-only export/binary-log fixes passed the Granite hardware run
below, but do not retrospectively fill this run's missing checks. The [retained extraction](results/2026-10-01-guarded-app-ministral.json)
contains actual parsed outputs and original binding/hash provenance.

To independently replay the original private synthetic capture (no cluster
connection or private-health reads):

```sh
python3 benchmarks/ollama/summarize_app_run.py <operator-retained-synthetic-jsonl>
```

### Granite 4.2 3B follow-up

On 2026-10-02 the user approved starting the next isolated test with IBM Granite
4.2. `--candidate granite4.2:3b` selects only that explicitly supported profile;
`ministral-3:3b` remains the legacy profile for reproducing the earlier run.
Unknown tags/URLs and cloud entries cannot be selected, and there is no automatic
candidate fallback or second-model download. The catalogue's same-weights alias
is `granite42-synthetic:3b`, not a second model.

The official Q4_K_M Granite manifest is pinned to
`40577dc168a3a9ad34e9a1234e0c2570be86097fa75a236d4574ae985705d3c4`.
Public registry metadata lists 2,244,023,548 layer bytes and an Apache 2.0 license.
This is a download size, **not** a measured memory budget or an accepted response.
The publisher's [model card](https://huggingface.co/ibm-granite/granite-4.2-3b)
describes thinking/non-thinking modes; the actual app request retains `think:false`
and must receive no reasoning trace. The current Ollama 0.35.0 and published app
digests, 2048 context, 256 output tokens, 120/130-second app bounds, three-core/
5 GiB model cap, seed, temperature, prompt, validator, and fixtures are unchanged.
Runtime/model compatibility failures are test outcomes, not reasons to silently
upgrade the runtime, change the template, or enable thinking.

The runner checks the **full system-prompt hash** against the published Kotlin
source, not just its prefix. It records authenticated synthetic trend/input
queries while a generation request is pending, including whether the instrument
has already observed the actual model request and whether the report binding
stays unchanged. This is an API chart-data responsiveness check, not a physical
browser interaction test. Streamed resource samples survive later scan failures;
the final byte-oriented log scan and control export remain separate evidence.

The independently replayed results, latency/resource series, and cleanup outcome
must be reviewed before making any qualification claim. Authorization for this
synthetic test does not select a default, promote the application, deploy a model
service for the dashboard, or permit actual-health inference.

The [completed Granite procedure](../../docs/decisions/guarded-app-granite42-test.md)
accepted **0/8** real responses; both sparse inputs made no model call. The
byte-oriented log scan, exported controls/87 resource samples, concurrent
synthetic chart-data API probes, unchanged history, and cleanup passed. Actual
parsed claims and provenance are retained in
[the result extraction](results/2026-10-02-guarded-app-granite42.json). A completed
procedure is not successful model qualification: no accepted cross-metric result
exists, and no live default/service/promotion was enabled.

### Qwen3 Instruct and authorized skill-style A/B experiment

The user approved Qwen3 4B Instruct-2507 (`qwen3:4b-instruct`), distinct from
the previously OOM-killed Qwen3.5 4B. Its manifest is pinned to
`0edcdef34593eac1aa2be9c7d06c432dcf81945adca5eca2f27662c18f168ba0`.
The unchanged-app baseline completed ten requests: 0/8 inference outputs passed;
two sparse gates made no model call. Cgroup lifetime peak reached the 5120 MiB
cap without a restart; this is not evidence of comfortable resource headroom.
Its [actual-output extraction](results/2026-10-02-guarded-app-qwen3-instruct.json)
retains raw claims, bindings, telemetry and complete controls/cleanup evidence.

The user then separately authorized comparing the current instructions with
a compact step-by-step instruction card and worked synthetic examples. Package
with `--candidate qwen3:4b-instruct --prompt-experiment --approve-synthetic-run`.
This is explicitly **not the unchanged-prompt app benchmark**:

- Arm A forwards the original app request bytes unchanged.
- Arm B substitutes only the system-message content with
  [the instruction card](skills/grounded-trend-selection/SKILL.md). The instrument
  retains both the app's original bytes and the actual forwarded bytes/hashes,
  explicitly labeled by arm. It never modifies a model response.
- The same published Kotlin app prepares facts, grammar, input gates, and report
  binding and validates/renders the entire response. Model digest, chat template,
  runtime, thinking setting, limits, seed, and output budget remain unchanged.
- Six usable cases each run once under A and once under B, alternating AB/BA
  order. Every request unloads afterward; there is no hidden retry, repair, warm
  keep-alive, alias inference, or relaxed validator. Two sparse gates run once
  without inference. Compare six real responses per arm, not the earlier 8-call
  baseline denominator. Existing controls, chart-data probes, resource monitoring,
  isolation, log scanning and owned cleanup still apply.
- This tests manually supplied skill-style instructions, **not** OpenCode skill
  discovery, an agent/tool loop, fine-tuning, or model-weight updates. The examples
  cover known fixture patterns; this is development-set evidence, not held-out
  generalization, medical expertise, or production qualification.
- Added instructions consume context/prefill time under the same 2048-token
  context and 120-second HTTP bound. Preserve token counts, incomplete generations,
  timeouts and memory pressure as outcomes, never enlarge limits to declare success.

`summarize_app_run.py` verifies both actual forwarded prompts, paired packet
hashes and unchanged whole-response rendering, then reports acceptance, latency,
tokens and rejection flags separately by arm. Nothing in this experiment enables
live inference, deploys a production skill system, or changes the live dashboard.

The [partial physical A/B run](../../docs/decisions/skill-style-prompt-experiment.md)
completed ten actual calls, five per arm: A accepted 0/5 and B accepted 2/5.
Across the four pairs unaffected by stale evidence, the comparison is 0/4 versus
2/4. The tenth call crossed midnight UTC and the app correctly invalidated its
dated report binding; a runner assertion then stopped the procedure. The
[actual-output extraction](results/2026-10-02-qwen3-skill-ab-partial.json) includes
that received-but-unfinalized result, never counting its valid-for-old-packet raw
claim as app acceptance. Cleanup passed; unchanged/sparse cases and final
controls/log/history checks were not reached. Subsequent runner/replay accounting
fixes are regression-tested, not hardware-retested. No automatic rerun or default.

After explicit continuation approval, a fresh
[full paired rerun](results/2026-10-02-qwen3-skill-ab-complete.json) completed:
original instructions accepted **0/6**, the unchanged card **3/6**. Both sparse
gates, final controls/log/history checks and cleanup passed. B took 83–98 seconds
and model lifetime peak again reached 5120 MiB. The full rerun validates the
corrected operator procedure, not another midnight crossing or model usability.
Its outputs are retained separately from the partial run; no pooled success rate,
default, inference deployment, prompt tuning or real-health transfer is implied.

### Revised card on fresh structural variants

After explicit approval, `--prompt-experiment --prompt-experiment-profile card-v2-unseen`
with `--candidate qwen3:4b-instruct` compares the existing card **v1 as A** and
the revised card **v2 as B**. Both arms substitute only the original app system
message, with original and actually forwarded bytes retained and independently
verified; the production preparer/schema/validator/rendering remain unchanged.

The frozen [design](../../docs/decisions/revised-card-unseen-experiment.md),
[v2 card](skills/grounded-trend-selection-v2/SKILL.md) and
[fresh fixtures](app_unseen_fixtures.py) preserve v1 and all earlier artifacts.
Six new usable cases and two sparse gates seed only fresh synthetic records.
This is an unseen-by-local-model structural-variant check, not a blinded external
holdout or medical-advice evaluation. No outcome-dependent prompt tuning/retries,
validator relaxation, model switch, resource increase or live change is permitted.
The default `baseline-v1` profile retains the earlier experiment for replay.

### Code-prepared review experiment

The separately approved [prepared-focus experiment](../../docs/decisions/code-prepared-review-experiment.md)
removes factual grouping/coverage from model output. Code first prepares and
renders all facts; the model only selects supported descriptive focus candidates.
Its output is a new benchmark-only contract, **not published-app acceptance** or
unrestricted coaching. Supported-but-unrelated selections fail a frozen relevance
rubric. Six known structural cases and two no-call gates are reused, not a fresh
holdout. Earlier results stay unchanged.

`run_prepared_review_on_pi.py` packages/runs explicitly approved Qwen3 4B Instruct
or Qwen3.5 4B with pinned manifests, three cores/5 GiB, 2048 context/256 output,
thinking off and unload after every call. The separate synthetic call/job budgets
are 240/600 seconds, with at most one independently validated corrective attempt
for selection errors only. Resource/timeout/evidence failures stop without retry,
larger caps or hosted fallback. Use `summarize_prepared_review.py` on original
JSONL to preserve every attempt and distinguish first-pass, final and incomplete
outcomes. It verifies exact request/response bytes and independent strict output
validation; the installed app prompt, validator, deployment and live AI remain
unchanged.

The first [Qwen3 prepared-focus run](results/2026-10-02-qwen3-prepared-focus.json)
passed the closed contract for all eight responses, but answered the requested
focus in **4/6 cases**, both before and after two corrective attempts. The two
failed corrections repeated the same supported-but-unrelated selections. Code
rendered complete facts independently in every case. Calls took 52–75 seconds;
model cgroup peak remained 5120 MiB. Controls, two sparse gates and cleanup passed.
This different task is not comparable to prior app acceptance rates.

[Qwen3.5 staging failed](results/2026-10-02-qwen35-prepared-focus-staging-failed.json)
during `/api/pull`, before any inference. Its HTTP error detail was withheld by
the staging helper; no new OOM is established. Zero outputs means no quality score,
not 0/6 acceptance. Cleanup/live-state checks passed. Task 7.2 remains incomplete;
further diagnostic staging or inference requires an explicitly approved rerun.

The subsequently approved [Qwen3.5 diagnostic rerun](results/2026-10-02-qwen35-prepared-focus-diagnostic-rerun.json)
staged successfully and answered **5/6 requested focuses**, unchanged after one
correction. Its zero-baseline combined-review failure repeated wellness-only
selection. All seven responses passed the closed contract; complete factual
coverage stayed code-owned. Calls took 68–109 seconds and the longest corrected
case 192 seconds. Peak again reached 5120 MiB, without observed OOM/restart or
health/guard failure. Two sparse gates, controls/log/history/unload and cleanup
passed. The earlier staging error did not recur; its cause is still unknown.
The prepared packets/task match Qwen3's 4/6 run; this is a small known-fixture
comparison, not coaching or production qualification. Task 7.2's experimental
procedure is complete, but AI stays off and no further run/cap change is implied.

The user then approved a separate [fresh Qwen3.5 review suite](../../docs/decisions/fresh-prepared-review-experiment.md):
`--suite fresh-v1` selects ten usable structural variants plus two sparse no-call
gates, with new zero-baseline, unchanged, HRV-gap and two-sport combinations.
The prepared-focus contract/system prompt, budgets, validator and caps remain
unchanged. Fixture values, private rubric and varied candidate positions are
frozen before output review. This is a new predetermined synthetic suite, not an
external blinded holdout. Replay distinguishes suites and preserves every earlier
extraction byte-for-byte; acceptance rates across different suites are not pooled.

The [fresh run stopped partway](results/2026-10-02-qwen35-prepared-focus-fresh-partial.json)
when a 30-second `kubectl get pod` control query timed out during the fifth case's
correction. Four cases finalized (2/4 relevant); five first-pass outputs were
received (2/5 relevant). Seven retained responses passed the closed contract;
two completed corrections repeated unsuitable focus selections. The eighth
request's response is unrecorded, and the remaining cases/sparse gates and final
controls/log checks were not reached. This is not a full ten-case result or a
demonstrated model HTTP timeout/OOM. Cleanup and unchanged live state passed.
No automatic rerun occurred. The subsequent explicitly approved capture/control
fix exported HTTP completion independently of bounded background state polling.
Its [incomplete rerun](results/2026-10-02-qwen35-prepared-focus-fresh-rerun-incomplete.json)
finalized seven reviews (4/7 relevant), but missing terminal records and the eighth
response prevent a complete result or specific failure diagnosis.

After separate restart approval, [durable Pi-side capture](prepared_review_capture.py)
completed the unchanged suite and hash-verified recovery independently of the
observing SSH session. The [complete result](results/2026-10-02-qwen35-prepared-focus-fresh-durable-complete.json)
accepted **5/10 first-pass and 5/10 final focuses**. All 15 responses passed the
closed contract, but five corrections repeated unsuitable selections. Both sparse
gates made zero calls; final controls/history/log/unload/cleanup checks passed.
Calls took 68–104 seconds; peak again reached 5120 MiB without observed restart or
health/guard failure. This demonstrates complete experimental capture, not reliable
relevance, sustained-resource qualification or production acceptance. All older
partial artifacts remain separate; live AI stays off and no further run is implied.

The [completed fresh-case comparison](results/2026-10-02-qwen3-card-v2-unseen.json)
accepted **1/6 with v1 and 4/6 with v2**. V2 avoided invalid comparison kinds but
still omitted HRV in one four-item case and unavailable sleep in another. Whole
responses were rejected; no valid fragments were salvaged. B took 73–91 seconds,
with cgroup lifetime peak again at 5120 MiB. Final controls/log/history checks,
both no-call gates and cleanup passed; all 12 actual raw claims were independently
checked against the unchanged Kotlin validator. This does not qualify the model,
complete physical UI/resource tasks, enable live AI or authorize another run.

`extract_app_run.py <operator-retained-synthetic-jsonl>` generates a compact
artifact with exact raw claims and original hashes, omitting lengthy rendered
fact text. `summarize_app_run.py` retains full independently checked rendering.

`ValidateSyntheticClaims.java` can also replay synthetic summary/claim files
locally against `backend/build/install/backend/lib/*` after `:backend:installDist`.
It performs no inference and emits only acceptance, packet hash and observation
count. A hosted conversational reference must be labeled separately from an
Ollama transport/hardware benchmark; no actual health records are permitted.

The separately requested [GPT-6 Luna conversational reference](results/2026-10-02-luna-conversational-reference.json)
passed all six usable fixtures through both the unchanged locally built Kotlin
validator and independent Python validation. It used the baseline instructions
without the skill examples, but task-message roles and harness defaults differ
from Ollama; this proves neither a fair provider comparison nor production advice.
