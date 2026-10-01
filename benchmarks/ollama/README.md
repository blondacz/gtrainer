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
