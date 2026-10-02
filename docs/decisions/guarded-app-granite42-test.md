# Published app → IBM Granite 4.2 3B: faster, but rejected

The user authorized this isolated synthetic test on 2026-10-02 (operator-local
date; Pi records use 2026-10-01 UTC). The complete procedure finished and cleanup
passed. **Model usability did not: 0/8 real inference responses were accepted**
across six distinct usable fixtures. Two sparse-input app requests correctly
returned `insufficient_input` without calling the model.

## Reproducibility and boundary

- [Actual parsed claims, hashes, latencies, and resource summary](../../benchmarks/ollama/results/2026-10-02-guarded-app-granite42.json).
- [Operator procedure](../../benchmarks/ollama/README.md#granite-42-3b-follow-up).
- Original synthetic-only JSONL SHA-256:
  `43999b6f8417ad21a1bb707e5abcb58029476db9d60b32fbcee7914ede175b34`.
  It retains full app reports, exact transport bytes, prepared packets, app
  responses, chart probes, controls, and 87 resource samples in the private
  operator temporary directory. Independent `summarize_app_run.py` replay agreed
  with every whole-response rejection and verified the report/packet bindings.

The CI-verified app image/revision is the same as the
[Ministral integration run](guarded-app-ministral-test.md). The fixtures, complete
system prompt (verified by hash), generated schema, validator, arithmetic,
2048-token context, 256-output-token cap, temperature 0, seed 42, three threads,
three-core/5 GiB model container, Ollama 0.35.0 ARM64 image, and 120/130-second app
bounds were unchanged. `think:false` returned no reasoning trace; every real
generation completed with `done_reason=stop`, without timeout or restart. Unloading
after every call was checked, including the repeat and same-weights alias.

The selected Q4_K_M Granite manifest was
`40577dc168a3a9ad34e9a1234e0c2570be86097fa75a236d4574ae985705d3c4`.
The catalogue alias selected exactly the same weights, not another qualified
model. The Apache 2.0 model downloaded in 94.08 seconds; Ollama reported
2,244,023,965 bytes including its 417-byte config. Runtime outbound TCP to a
public IP was rejected before generation; local loopback inference stayed usable.
No actual-health data, source key, production password, Secret, or PVC was read
or mounted into the temporary pod.

## Actual output

Granite copied each selected direction correctly but repeatedly emitted
`sport_mix` with only one comparison, omitted required supplied evidence, and
failed to connect activity to wellness. In the mixed-sports case it made **two
separate one-sport claims**, rather than a valid multi-sport relationship. All
eight responses triggered `invalid_sport_mix`, `incomplete_evidence_selection`,
and `cross_metric_connection_missing`; the whole response was rejected.

| Fixture | App wall time | Result |
|---|---:|---|
| Concurrent decreases, first cold | 46.447 s | Invalid single-sport selection; wellness omitted |
| Same prompt, unloaded repeat | 44.746 s | Identical rejected claims |
| Mixed directions | 56.460 s | Invalid sport mix; no connection; HRV omitted |
| Mixed sports | 60.041 s | Two invalid single-sport mixes; wellness omitted |
| Partial HRV | 57.138 s | Invalid sport mixes; missing HRV comparison omitted |
| Populated zero baseline | 44.537 s | Invalid single-sport selection; wellness omitted |
| Unchanged | 44.670 s | Invalid single-sport selection; wellness omitted |
| Sparse activity | 0.010 s | Input gate; no inference |
| Absent current wellness | 0.010 s | Input gate; no inference |
| Selected same-weights alias | 44.485 s | Identical rejected concurrent-decrease claims |

The complete outputs used only 63–115 generated tokens. Increasing the output
budget would not fix truncation: there was no truncated output here. Finishing
faster, valid JSON, and correct copied directions do not qualify the missing
cross-metric synthesis. Conversely, this is evidence about **this exact
prompt/quantization/runtime and non-thinking mode**, not proof that Granite is
generally incapable of the task. Shared failures across models warrant examining
prompt/schema/template handling before assuming that another model alone fixes it.
No prompt, schema, validator, or app change is made to bypass these rejections.

## Resources and controls

The retained 87-sample series measured:

| Measurement | Observed |
|---|---:|
| Ollama cgroup lifetime peak | 4664.3 MiB (about 4.56 GiB) |
| Maximum sampled Ollama current memory | 4647.8 MiB |
| Minimum sampled host available memory | 3379.2 MiB |
| Maximum sampled temperature | 73.80°C |
| Temporary app lifetime peak | 138.5 MiB |
| Capture instrument lifetime peak | 32.8 MiB |
| Maximum live anonymous health latency | 0.0329 s |
| Maximum synthetic anonymous health latency | 0.1016 s |
| Health failures / container restarts | 0 / 0 |

Each of the eight real inference attempts also read authenticated synthetic
trend and analysis-input reports while the request was pending and the instrument
had seen the model call. Their combined probe time was 0.0289–0.2429 seconds,
with exact report binding unchanged. This verifies concurrent **API chart-data
access**, not physical browser usability. Only anonymous `/healthz` was requested
from the live dashboard; no actual private chart data or credentials were used.

The final exported controls confirmed stale source-status invalidation, off
selection, stale selection version, injected outage and unsupported-output
rejection, logout, and unchanged synthetic history. The history SHA-256 before
and after was
`ed5a2a99811794986b0989e3e9ecbaa73754bfd69854bcb05c1d57be7a3e9cac`.
Anonymous analysis/model reads and requests without CSRF were rejected. Distinctive
credential, record, prompt-field, and injected-output markers were absent from
app/model/instrument logs in the byte-oriented scan. Neither injected fault is
counted as a Granite inference response or affects its 0/8 rate.

The previous runner's binary-log/telemetry-export fixes worked in this run. Still,
cgroup lifetime peak includes cached/staged pages; five-second sampling and a
finite batch do not prove long-term or concurrent production headroom, exclude
every transient, or establish thermal throttling status. Marker scans do not
prove the absence of all numeric sensitive logging in another release. The proxy
can continue upstream work after client cancellation; actual direct-server
cancellation, multi-model quality, accepted hardware rendering, and real-data
end-to-end behavior remain unqualified.

## Cleanup and decision

The temporary namespace/model volume, exact forwarding processes, and progress
mirror were removed. The live chart pod identity and immutable image stayed
unchanged, Ready, with zero restarts. No live promotion, production inference
service, default selection, resource increase, or import occurred.

**Keep AI off.** Granite fits this finite resource test better than the previous
Ministral setup, but it is not usable through the current contract. Tasks 4.4,
4.6, and 4.8 remain unchecked; no required behavior is narrowed or waived. A
later model test or a separately reviewed prompt/contract experiment must keep
deterministic factual processing, whole-response validation, privacy, explicit
selection, and resource bounds. Do not silently switch to a hosted provider or
deploy this rejected integration.
