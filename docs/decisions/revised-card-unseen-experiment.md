# Revised instruction card on fresh synthetic cases

## Approved question and frozen design

The user approved simplifying the card after the completed v1 comparison accepted
3/6, and testing unseen synthetic cases. This is one operator-only experiment,
not permission for fine-tuning, another model, live inference or relaxed limits.

Profile `card-v2-unseen` compares **card v1 (A)** against **card v2 (B)**, not the
original published-app prompt. The synthetic proxy substitutes only system-message
content for both arms, preserving original and actual forwarded bytes separately.
The published Kotlin app still prepares facts/grammar, enforces exact HTTP report
binding, gates sparse inputs, and validates/renders the complete response unchanged.

The [revised card](../../benchmarks/ollama/skills/grounded-trend-selection-v2/SKILL.md)
prioritizes all available wellness evidence, fills remaining slots with sport
comparisons, emits no second observation when coverage is already complete, and
reserves unavailable comparisons strictly for prepared state `unavailable`.
It does not supply a fixture answer, fix model output, perform arithmetic, change
the output schema, or claim sport/health expertise. No duplicate-evidence ban is
added to the validator; avoiding redundant cross-observation references is a
prompt preference for this input shape, not a new acceptance rule.

Frozen before the first local-model response:

- A/card v1 SHA-256:
  `78f49a03d02eb3535a9a2b2bc4301afc954d1475fb5cd796a817740ea59e1850`.
- B/card v2 SHA-256:
  `0d739563727f87af8c01aa37332a4af1e50c1ff868ace6a2f0ac3cd35d53f991`.
- Compact [fresh fixture definitions](../../benchmarks/ollama/app_unseen_fixtures.py) SHA-256:
  `76edd08643627a114be99d46d026cacd91ddae2b16e880d254f092d23f98f9fc`.

Do not tune these files after inspecting model output. A subsequent revision must
be separately versioned/approved, preserving failed outputs and this comparison.

## Cases and procedure

Six usable cases: running/sleep increase with HRV decrease; unchanged swimming
and sleep with HRV decrease; two sports and wellness increasing; opposite two-sport
changes with sleep absent in both periods but HRV available; missing previous sleep
with available sport/HRV; populated activity zero baseline with wellness increasing.
Two separate sparse-activity/missing-current-wellness gates must make no inference.

New dates, sports, numeric values and direction/missingness combinations replace
the earlier development fixtures. No actual records, source credentials or live
database are read. The fresh synthetic seed has 38 activity and 32 wellness records.
These are fresh structural variants unseen by the local model, **not** a blinded
external holdout, physiological reasoning test or proof of generalization to arbitrary
health scenarios. The human-authored instructions and test patterns share a known
bounded task. Test-only recipe examples establish satisfiability, not model quality.

Each usable fixture runs once under each arm, alternating AB/BA order: 12 actual
calls, plus two no-call gates. No model-quality retries or output repair. Keep
Qwen3 4B Instruct-2507's pinned manifest, Ollama 0.35.0 and chat template, three
cores/5 GiB, 2048 context, 256 output tokens, temperature 0, seed 42, thinking off,
120/130-second bounds and unloading after every call. Hosted fallback remains off.

Use the existing ownership-isolated disposable namespace, synthetic authentication,
egress denial after staging, chart-data probes, resource health guards, final
functional/log/history checks and cleanup. Record acceptance, latency, tokens,
actual raw output, request/packet/report hashes and resources separately by arm.
Stale evidence remains unavailable, never counted as accepted raw-claim salvage.

## Status

77 benchmark tests, 47 utility tests, strict OpenSpec validation, changed local
document links and `git diff --check` passed. Read-only Pi preflight confirmed
adequate RAM/disk, absent temporary resources and an unchanged Ready live pod.
The approved 12-call paired comparison plus two no-call gates completed using
these frozen sources. Original transport, report/packet bindings, forwarded-card
hashes, whole-response validation and rendering were independently replayed.
The live dashboard and tasks 4.4/4.6/4.8 remain unchanged; AI stays off.

## Completed result

The [actual-output extraction](../../benchmarks/ollama/results/2026-10-02-qwen3-card-v2-unseen.json)
retains all 12 raw model claims and both sparse no-inference outcomes. Original
synthetic capture SHA-256:
`9b592adf51d16480a9cd13b27f674671127c3a95800a046f1fea715ec6579591`.

| Fresh fixture | Card v1 (A) | Card v2 (B) |
| --- | --- | --- |
| Sport/sleep up, HRV down | Accepted | Accepted |
| Sport/sleep unchanged, HRV down | Rejected: unavailable kind on available evidence | Accepted |
| Two sports and wellness up | Rejected: incomplete evidence | Rejected: omitted HRV e3 |
| Two sports, sleep absent, HRV available | Rejected: incomplete evidence | Accepted |
| Previous sleep absent | Rejected: invalid kind/connection and incomplete evidence | Rejected: omitted unavailable sleep e1 |
| Populated activity zero baseline, wellness up | Rejected: invalid sport mix | Accepted |

**A accepted 1/6; B accepted 4/6 on the same six fresh cases.** This is not the
earlier v1 development-set rate of 3/6, and attempts across different fixtures
must not be pooled to improve the reported rate. Both B failures are complete,
correct-direction, otherwise valid selections with missing evidence. The app
rejects the entire response, including valid fragments; no third-party replay
repairs or accepts those incomplete answers.

The card eliminated invalid comparison kinds in this small batch and avoided
redundant observations in its four accepted responses. It did not reliably follow
the all-wellness-first ordering or its conditional second-observation rule in
the two failed cases. Better instructions therefore helped, but do not establish
reliable complete evidence handling or useful sports/medical interpretation.

### Latency, resources and controls

- A app latency 78.401–97.419 seconds; B 73.133–90.939 seconds. B was faster on
  each of the six paired fixtures. A used 906–1015 prompt tokens and 47–76 output
  tokens; B used 864–973 and 37–67. All responses finished with `stop`, no reasoning
  trace or timeout; context/output budgets did not explain incomplete coverage.
  Unloading was verified after each real call.
- 189 retained samples: model cgroup lifetime peak 5120 MiB; maximum sampled
  current 5115.1 MiB; minimum host available 2741.6 MiB; maximum temperature
  74.9°C. Zero sampled live/synthetic health failures, restarts or failed resource
  guard. Peak includes staging/cache; memory at the cap is not sustained headroom
  qualification. The 73–91-second revised-card latency remains slow for interaction.
- Concurrent authenticated synthetic chart-data probes took 0.0185–0.1875 seconds
  with unchanged report bindings. Maximum sampled anonymous live health latency
  was 0.0545 seconds. These are API probes, not physical browser rendering checks.
- Stale source/off/stale selection, injected outage/unsupported output, logout,
  byte-oriented log markers and history equality passed. Injected faults are not
  model responses. Synthetic history before/after SHA-256:
  `e9bed2d53e19a5d7f85ef9b30406987e18eb1696eff33052edb385b88e506cc6`.
- Both sparse gates made no model call. Owned namespace/model volume, progress
  and forwarding cleanup passed. Independent read-only Pi inspection confirmed
  removal and the same Ready live app image/pod with zero restarts. No actual
  health, credential, source import, production model service or promotion was used.

All 12 raw outputs also agreed with the unchanged locally built Kotlin validator.
The instruction cards and fresh fixture hashes stayed frozen throughout; no
feedback, model-quality retry, prompt tuning or output repair was used.

## Decision and suggested next boundary

Keep AI off: 4/6 on fresh structural variants is an improvement, not qualification.
The remaining failures are evidence bookkeeping, not math or resource failures.
Before more prompt tuning or fine-tuning, consider computing mandatory evidence
coverage/grouping deterministically **before inference**, leaving a model only a
separately defined interpretive task if it adds useful value. That is not permission
to fill omissions after a rejected response or to bypass validation. Any changed
model role, prompt/schema or application path needs separate design/approval and
tests; this experiment changes none of them in production.
