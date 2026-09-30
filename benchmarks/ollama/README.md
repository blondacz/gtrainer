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
