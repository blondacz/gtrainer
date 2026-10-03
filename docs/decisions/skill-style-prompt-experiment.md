# Synthetic skill-style instruction comparison

## Authorization and question

On 2026-10-02 the user approved a background A/B experiment after an explanation:
can a compact procedural instruction card with worked examples improve a small
local model's adherence to the existing typed evidence-selection contract?
This is prompt engineering, not fine-tuning or a production agent/skill system.

## Design

Use the already approved, digest-pinned Qwen3 4B Instruct-2507 profile. Test six
known usable synthetic fixtures once per arm, alternating AB/BA order, unloading
after every request. Run two sparse gates without inference. Do not compare the
12-call paired denominator to the earlier eight-call baseline denominator.

Arm A forwards the published app's original bytes. Arm B changes only system
message content to the [instruction card](../../benchmarks/ollama/skills/grounded-trend-selection/SKILL.md).
The synthetic transport explicitly captures original and forwarded requests
separately, plus the unchanged actual model response. The published Kotlin app
still prepares facts, grammar and evidence binding and rejects/renders the whole
response using its unchanged authoritative validator. Independent replay checks
both forwarded bytes and packet identity; it does not salvage incomplete claims.

Keep Ollama 0.35.0, the model/template digests, 3 CPU cores, 5 GiB memory, 2048
context, 256 output tokens, temperature 0, seed 42, thinking off, `keep_alive:0s`
and existing HTTP/operation timeouts. Instructions add prefill/context work;
timeouts, incomplete generations, resource pressure and longer latency remain
failures or limitations, not reasons to change the limits.

All work is confined to the exclusively owned disposable benchmark namespace.
No actual records, credentials, live imports, production inference, live image
promotion or app networking changes are authorized. Chart API probes, controls,
resource monitoring, log-marker scanning and ownership-checked cleanup remain.

## Baseline and separately requested reference

The unchanged-app Qwen run completed with 0/8 responses accepted, plus two input
gates without inference. Responses finished with `stop`, 111–133 output tokens
and 72.382–89.069-second app latencies. The model commonly selected semantically
invalid combinations of sport/wellness evidence; one unchanged case also copied
a direction incorrectly. No inference timeout or restart occurred, but cgroup
lifetime peak reached the 5120 MiB cap, maximum sampled current was 5113.8 MiB,
and minimum available host memory was 2803.8 MiB. Those peaks include cache and
staging and do not establish long-term inference headroom. Controls, log scanning,
history binding, live dashboard identity and cleanup passed. Full original
synthetic capture remains operator-retained for replay.

At the user's explicit request, [GPT-6 Luna](../../benchmarks/ollama/results/2026-10-02-luna-conversational-reference.json)
produced one raw response per usable fixture in fresh conversational sessions.
All six passed the unchanged locally built Kotlin validator and independent
Python validation; no repair, retry or skill examples were supplied. This shows
the narrow task is achievable, not that hosted and local inference were compared
under identical conditions. Harness defaults, roles, budget and reasoning differ;
no resource, latency or pricing comparison is claimed. No hosted real-health
consent/provider implementation is added.

## Partial physical outcome

The [retained partial extraction](../../benchmarks/ollama/results/2026-10-02-qwen3-skill-ab-partial.json)
records ten actual calls: five per arm. Nine reached finalized `case_result`
records; the tenth completed inference and returned an unavailable app response
before a runner assertion. It must not be silently dropped from the denominator.
Original capture SHA-256:
`3c2e5c34cda37b9805c724157e3b9f4d30be2aaac9a3253e6a3cbad7346224fb`.

| Fixture | A: original instructions | B: instruction card |
| --- | --- | --- |
| Concurrent decreases | Rejected: invalid kinds/connections | Accepted, with redundant sleep observation |
| Opposing directions | Rejected: invalid kinds/connections | Rejected: available sleep labeled unavailable |
| Mixed sports | Rejected: wellness-only connection | Rejected: HRV omitted |
| Missing current HRV | Rejected: invalid kinds/connections | Accepted, including unavailable HRV |
| Zero baseline | Rejected: invalid kinds/connections | Not accepted: evidence report changed during inference |
| Unchanged comparisons | Not run | Not run |

Across the **four comparable completed pairs**, A accepted 0/4 and B accepted
2/4. Across all actual attempts, A accepted 0/5 and B accepted 2/5. B's zero-baseline
raw claim passes the contract for the old packet, but that is diagnostic only:
the app correctly returned no observation, so it is **not** an accepted third
response. Do not replace app acceptance with post-hoc raw-claim validation.

The tenth request started at `2026-10-01T23:59:49.666118+00:00` with a report
evaluated on 2026-10-01 and completed at `2026-10-02T00:01:14.737289343Z`.
The report includes the evaluation date; the unchanged app rereads and rehashes
it after inference. Crossing midnight changed that binding, correctly triggering
`evidence_changed` before model-output rendering. The operator runner's incomplete
reason whitelist asserted and ended the experiment. This was neither a model
timeout nor an OOM. No retry or additional inference was started.

The subsequent runner-only fix records this legitimate stale-evidence outcome,
checks the changed report hash and preserves no-output behaviour. Replay accounts
for received/captured outcomes even if later runner bookkeeping failed, labels
them unfinalized, and never counts invalidated evidence as acceptance. These fixes
are regression-tested but **not hardware-retested**; no app validator, runtime,
prompt, model weights or limits were relaxed.
All ten actual raw outputs were also independently replayed through the unchanged
locally built Kotlin validator. It agreed with Python on every contract result,
including the diagnostic-only validity of the stale zero-baseline claim. Artifact
regressions, incomplete-run accounting and stale-gate checks bring the benchmark
suite to 72 passing tests; 47 utility tests and changed local-document links pass.

### Runtime and cleanup

- A app latency: 71.737–88.952 seconds; B: 83.037–96.941 seconds. The card did not
  make inference faster. A used 570–679 prompt tokens, B 907–1016; neither
  approached the 2048 context cap. Outputs completed with `stop`, no reasoning
  trace, and 111–133 tokens (A) or 58–77 tokens (B).
- 158 retained resource samples: model cgroup lifetime peak 5120 MiB, maximum
  sampled current 5115.8 MiB, minimum host available 2753 MiB, maximum temperature
  74.9°C. No sampled health failures, container restarts or failed resource guard.
  Cgroup peak includes cache/staging; operating at the cap is not long-term
  headroom qualification.
- Authenticated synthetic chart-data probes during all ten pending requests
  took 0.0192–0.287 seconds and preserved their then-current report bindings;
  sampled anonymous live health latency was at most 0.0716 seconds.
- Owned namespace/model volume, progress mirror and forwarding processes were
  removed. The live pod UID/image stayed unchanged, Ready, with zero restarts.
  A final read-only Pi check independently confirmed absence of temporary resources.
- The two sparse gates, final controls, final history equality and log-marker scan
  were **not reached in this A/B run**. The complete
  [unchanged-app Qwen baseline](../../benchmarks/ollama/results/2026-10-02-guarded-app-qwen3-instruct.json)
  separately passed those checks; it does not fill missing A/B evidence.

## Decision and limits

The card improved some instruction-following under unchanged constraints, but
semantic errors remain. The partial development-set result does not qualify
Qwen, and resource/latency concerns remain. AI stays off; no live changes or
additional benchmark are started automatically.

The examples reflect known development patterns. Even an improvement would need
held-out adversarial cases and separate app-prompt integration/resource review.
Nothing here completes tasks 4.4, 4.6 or 4.8, sets a default, enables live AI or
qualifies training advice. Do not interpret instruction-card results as proof
that dynamic skill loading or fine-tuning would produce the same effect.

## Explicitly authorized full rerun

After receiving the partial outcome, the user asked to continue. A fresh full
six-pair A/B run was launched in the background following 72 passing benchmark
tests and a read-only Pi preflight. It uses the corrected runner and the exact
same instruction-card SHA-256
`78f49a03d02eb3535a9a2b2bc4301afc954d1475fb5cd796a817740ea59e1850`.
No prompt tuning, model switch, limit change, additional training or live
deployment is included. Retain the original partial run separately rather than
replacing its failures or pooling attempts into a more favorable acceptance rate.
The [full rerun extraction](../../benchmarks/ollama/results/2026-10-02-qwen3-skill-ab-complete.json)
now records a completed procedure: 12 actual inference calls, all six comparable
pairs, and two sparse gates without inference. Its original capture SHA-256 is
`5253462327221a3d1e864ac7c1b8d83b89093912703983b8e1aba0ef5eded749`.

### Full rerun result

**A accepted 0/6; B accepted 3/6.** The card passed concurrent decreases,
partial HRV and zero-baseline cases. It still incorrectly added an unavailable
comparison for populated sleep in the opposing-direction and unchanged cases,
and omitted HRV in the mixed-sports case. Rejection remains whole-response: the
otherwise valid first observation is not rescued. Accepted concurrent-decrease
and zero-baseline outputs contain redundant second observations, permitted by
the existing contract but not an improvement in presentation quality.

All five B raw outputs common to the partial run were reproduced unchanged;
zero baseline was accepted this time because its binding did not cross midnight.
Do not pool that retry with the original invalidated request to inflate the
partial-run rate. Both captures remain separately retained.

- A latency: 70.232–88.041 seconds. B: 83.028–97.937 seconds. A prompt tokens:
  568–679; B: 905–1016. All 12 responses completed with `stop`, no reasoning
  traces or timeouts, and 111–133 output tokens (A) or 58–77 (B). Unloading was
  verified after each call. No context/output-budget failure explains B's errors.
- 185 samples: model cgroup lifetime peak 5120 MiB, maximum sampled current
  5116.1 MiB, minimum host available 2719 MiB, maximum temperature 74.9°C.
  No sampled live/synthetic health failures, restarts or failed resource guard.
  Memory at the cap, including cache/staging, still does not establish sustained
  headroom or acceptable interactive latency.
- Concurrent authenticated synthetic chart-data probes: 0.0177–0.1035 seconds,
  unchanged report binding throughout each call; sampled live health at most
  0.0393 seconds. This remains an API check, not physical chart interaction.
- Final stale-source/off/stale-selection, injected outage/unsupported-output,
  logout, byte-oriented log-marker and history-equality checks passed. Both
  synthetic history hashes were
  `ed5a2a99811794986b0989e3e9ecbaa73754bfd69854bcb05c1d57be7a3e9cac`.
  Injected faults are not model responses or part of the acceptance denominator.
- Ownership-checked namespace/model volume, forwarding and progress cleanup
  passed; an independent read-only Pi check confirmed removal and the same
  Ready live pod/image with zero restarts. No actual-health read, source import,
  credential transfer, inference deployment or promotion occurred.

Full report/packet/forwarded-prompt replay agreed with app acceptance/rendering.
All 12 raw outputs also agreed with the unchanged locally built Kotlin validator.
The corrected runner completed on hardware, though this rerun did not itself
exercise a second midnight crossing. The previous stale-boundary evidence and
regression remain separately documented.

**Decision:** clearer instructions improve this known-pattern selection task,
but 3/6 is not reliable. Keep AI off and tasks 4.4/4.6/4.8 unchecked. A next prompt
experiment should target unnecessary second observations and complete wellness
coverage, then test held-out/adversarial cases without changing the validator or
caps. That is a recommendation, not authorization for another automatic run,
model download, fine-tuning or live change.
