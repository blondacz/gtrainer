# Code-prepared descriptive focus experiment

## Approved boundary

The user approved recording the background-review agreements and continuing
synthetic local testing with Qwen3 4B Instruct and Qwen3.5 4B. The latter's previous
memory failure is not waived. Both run at three cores and a 5 GiB cgroup cap, one
candidate at a time in fresh exclusively owned temporary resources. An upgrade
or larger cap requires a separate decision and separately labeled experiment.
No production model service, source import, release promotion, hosted transfer or
actual health-data inference is authorized by this experiment.

## Frozen task: `prepared-focus-v1`

The unchanged published Kotlin app computes factual comparisons from seeded
synthetic history. Benchmark code groups compatible available comparisons and
keeps unavailable comparisons explicit, then renders every required fact before
any model call. A four-item packet is not constrained by the prototype's
three-reference-per-observation model output limit. The experiment does not
alter that installed contract or silently count these outputs as app acceptance.

The model receives complete groups and code-prepared supported descriptive
candidates. It returns one or two candidate IDs for an explicitly requested
focus: concurrent activity/wellness, wellness, sport balance or missing wellness.
No personal prose, math, causal claims, recovery conclusions or prescriptions
survive this closed output vocabulary. Code renders selected candidate text,
values, dates, limitations and support; it does not fix or supplement a rejected
model response. Complete factual output exists independently of model success.

Acceptance has two separate gates:

1. Whole-response contract validation: exact keys, bounded strict JSON, supported
   unique IDs, unchanged evidence, complete generation and no thinking trace.
2. A frozen private relevance rubric: selected candidate kinds must directly
   answer the requested focus. A supported but unrelated candidate is not useful.

Candidate positions rotate by fixture. The private expected-kind rubric is never
sent to the model. Relevance is a synthetic task rubric, not clinical ground truth.
These known six structural fixtures and two no-call sparse gates reuse the
previous card comparison's records. They are not a new unseen holdout, and the
new acceptance rates must not be pooled or directly compared with old contracts.
This measures bounded descriptive focus selection, not physiological knowledge,
open-ended interpretation, adaptive coaching or proven benefit over code alone.

## Budgets and correction

Ollama stays pinned to `0.35.0`, 2048 context, 256 output tokens, three threads,
temperature zero, seed 42, thinking off and `keep_alive: "0s"`. This separate
direct-Ollama benchmark permits a 240-second per-call timeout and a 600-second
total case budget. These are explicit experiment settings, not changes to the
installed app's 120/130-second bounds. Time alone is not the main quality gate.

At most one correction is permitted for semantic/structural selection rejection.
The same immutable packet/schema and only bounded flag feedback are sent again;
both raw responses are retained and independently validated. First-pass and
corrected outcomes are reported separately. Timeout, incomplete generation,
unexpected thinking, OOM/restart, evidence changes and resource/live-health
failures never trigger correction, increased limits or fallback.

## Execution and evidence

- [Contract and grouping](../../benchmarks/ollama/prepared_review.py)
- [Owned Pi runner and frozen SSH packaging](../../benchmarks/ollama/run_prepared_review_on_pi.py)
- [Original-byte replay and extraction](../../benchmarks/ollama/summarize_prepared_review.py)
- [Regression tests](../../benchmarks/ollama/test_prepared_review.py)

Two separately packaged runs use the same frozen task, fixture/rubric and runtime
settings; only candidate model changes. Each uses fresh synthetic SQLite and
model emptyDir volumes, loopback-only forwarding, no service-account token,
no inbound access, runtime egress denial after staging, sampled anonymous live
health/host temperature/headroom and cgroup memory, and verified owned cleanup.
Original request and HTTP response bytes, packet/report bindings, each attempt,
unfinalized failures and terminal container states are retained. No installed
Kotlin AI-validator pass is claimed for this different experimental output schema.
The live app is inspected read-only, never logged into or used as an input source.

Task 7.1's freezing and local regression verification passed: 92 benchmark tests,
47 utility tests, strict spec validation, local documentation links and diff
checks. Task 7.2 remains pending until both execution outcomes are replayed and
documented. Tasks
4.4/4.6/4.8 and production grouping/scheduling remain unqualified and unchecked.

The sequential background pair has been launched from frozen SSH packages, first
Qwen3 Instruct and then Qwen3.5, with a fresh owned namespace/volume per candidate.
A first-candidate infrastructure/resource failure prevents automatic continuation;
no failed experiment is automatically rerun. Each terminal capture is replayed
before the pair advances, and every outcome remains in its private original JSONL.
No result is claimed before the runs finish.

Frozen system SHA-256:
`cf0ce0b109a24aad184a5d44d90b84321cbc747e9c4d5516d1d1770aaaa569e1`.
Frozen Qwen3 Instruct bootstrap SHA-256:
`d7b4151a698a144b2f27c34d1a6b04dffff3f8f20f38751a97a940f600056952`.
Frozen Qwen3.5 bootstrap SHA-256:
`4dc7c110cf0350dea99589478ce885d5a1f5d0956ad526183a0c9cac0b208441`.
Per-module source hashes are exported as the first capture row. Prompts, fixtures,
candidate ordering and relevance rubric must not be tuned while reviewing outputs.

## Recorded outcomes

### Qwen3 4B Instruct

The [retained extraction](../../benchmarks/ollama/results/2026-10-02-qwen3-prepared-focus.json)
replays all original request/response bytes. Original JSONL SHA-256:
`72e4d4d8e7f2596cbf1dea9af536d5f1eb9bfb2f124adeab6b966727f7e7ae5f`.

- All eight responses passed the closed output contract; **4/6 cases answered the
  requested focus on the first pass and 4/6 after correction**. Two allowed
  corrective attempts produced exactly the same unsuitable selections.
- For the opposing-directions daily-combined request, the model selected only
  wellness (`f1`) instead of the prepared activity/wellness connection (`f2`).
  For the zero-baseline daily-combined request, it selected only activity (`f1`)
  instead of the combined candidate (`f0`). Both failed the frozen relevance
  rubric, despite being individually supported descriptive selections.
- Wellness, two-sport balance, and both missing-sleep requests passed. Code-owned
  factual output covered every required item in all six cases independently of
  these model selections. No omitted model fact was added after rejection.
- Calls took 52.019–74.864 seconds. Completed case budgets were 60.320–122.979
  seconds, including correction/checks/unloading. Output was eight tokens per
  response, finished with `stop`, and had no thinking trace. Prompt input was
  757–992 tokens; neither context nor output caps explain the relevance failures.
- 102 resource samples: lifetime model cgroup peak 5120 MiB, maximum sampled
  current 5119.0 MiB, minimum host available 2724.5 MiB, maximum temperature
  74.35°C. No sampled live/synthetic health failures, restarts or resource guard
  failure. Memory at the cap remains a limitation, not headroom qualification.
- Two sparse gates made no model call. Every real attempt unloaded. Authenticated
  synthetic chart probes, unchanged history, no app AI/proxy calls, logout,
  byte-oriented log checks and owned cleanup passed. Independent read-only Pi
  inspection confirmed the same Ready live pod/image with zero restarts and no
  benchmark namespace, progress file or owned forwarding process.

This is not another 4/6 result for the old app contract. It measures a different,
smaller task on known fixtures. It does not establish improvement in physiology,
coaching, open-ended interpretation or value beyond deterministic selection.
The bounded correction loop provided no benefit in these two attempts.

### Qwen3.5 4B: staging failed, no inference

The [partial extraction](../../benchmarks/ollama/results/2026-10-02-qwen35-prepared-focus-staging-failed.json)
preserves this separate failure. Original JSONL SHA-256:
`24762d47bbfa35ce48d9543f5ddacced2899c0bde483f3900035636320b9c196`.

The `/api/pull` staging call returned a non-success HTTP status before a model
manifest could be verified. The shared staging helper withheld the HTTP error
body/status, so this capture cannot establish the underlying download failure.
**Zero inference calls, zero model outputs and zero completed cases** occurred.
Do not report this as 0/6 semantic acceptance or a fresh inference OOM.

All temporary containers were still running/Ready with zero restarts at failure.
Fourteen samples recorded lifetime model cgroup peak 3361.8 MiB, minimum host
available 5861.0 MiB, maximum temperature 58.4°C and no sampled health failures.
Cleanup and unchanged live pod/image/readiness/restarts were independently verified.
Final history/logout/log checks and sparse gates were not reached. The prior
Qwen3.5 OOM remains historical evidence, but this run does not retest inference or
show that a hardware upgrade would resolve the staging error. No retry, larger
cap, model substitution or fallback was performed.

## Decision after the initial pair

Keep AI off. Code-owned completeness worked in this bounded experiment; local
model focus relevance remains unreliable and correction did not improve it.
Task 7.2 stays unchecked because Qwen3.5 inference was not exercised. Before an
explicitly authorized Qwen3.5 rerun, preserve bounded staging HTTP diagnostics
without exposing health inputs or credentials and investigate the staging failure.
Do not retune the frozen protocol or use a larger Pi/cap to bypass unknown causes.
Production grouping, scheduling, queue/UI and tasks 4.4/4.6/4.8 remain outstanding.

## Explicitly approved Qwen3.5 diagnostic rerun

The user approved one diagnostic rerun of Qwen3.5 at the same three-core/5 GiB
limits. Only execution instrumentation changes: staging now exports bounded HTTP
status, response length/hash, fixed error categories and an upstream HTTP status
when recognizable. It never exports raw registry error text, URLs, signed query
tokens, credentials or health inputs. A staging error still stops without an
automatic second pull, model substitution or fallback. If staging succeeds, the
same frozen focus protocol proceeds to its synthetic inference cases.

Packaging verifies that the only changed execution source is
`run_prepared_review_on_pi.py`. Contract, prompt, fixtures, relevance rubric,
candidate order, generation options, images and budgets are identical to the
first attempt. The initial staging-failure artifact remains unchanged. Local
checks passed: 96 benchmark tests, 47 utility tests, strict spec validation,
diff checks and byte-identical replay of both earlier prepared-focus extractions.

Diagnostic bootstrap SHA-256:
`5140c90760a1d42e1af3f4ffc81381aa33dc159fee3f74b92a4a296b3ea24966`.
No diagnostic or inference result is claimed until this authorized run finishes.

### Diagnostic rerun completed

The [retained rerun extraction](../../benchmarks/ollama/results/2026-10-02-qwen35-prepared-focus-diagnostic-rerun.json)
agrees with independent replay of every original request/response byte. Original
JSONL SHA-256:
`597af7235d80543f9211d0999d65402b1d512b334fa8d72665a23acf4a6718f9`.

Staging succeeded with HTTP 200 and `status: success`; the expected pinned model
manifest was verified. The earlier pull failure did not recur, so its original
cause remains unknown. This success does not retroactively explain it.

**Qwen3.5 answered 5/6 requested focuses on the first pass and 5/6 after one
correction**, compared with Qwen3's 4/6 and 4/6 under the identical prepared-focus
task. All seven Qwen3.5 outputs passed the closed contract. It selected the
activity/wellness connection for the opposing-directions case that Qwen3 missed.
Its remaining failure was the zero-baseline daily-combined request: it selected
wellness-only `f2` instead of the prepared combined `f0`. Correction repeated
exactly the same choice; the whole optional focus output remained withheld.
Code-rendered complete factual coverage was independent of all model choices.

- Calls took 67.841–109.283 seconds; the longest case, including its correction,
  took 192.150 seconds. Eight output tokens per response, prompt input 772–1007,
  `stop` completion and no thinking trace. The extended background budget was
  sufficient, but additional time did not fix the relevance failure.
- 137 resource samples: model lifetime cgroup peak 5120 MiB, maximum sampled
  current 5117.1 MiB, minimum host available 1770.3 MiB, maximum temperature 73.8°C.
  No observed OOM, container restart, sampled health failure or resource guard
  failure occurred. This demonstrates this particular bounded task can run at
  the existing cap, not sustained memory headroom or reliability across tasks.
- Both sparse gates made no call. Every real attempt unloaded. Authenticated
  synthetic chart probes took 0.0209–0.1811 seconds. History/no app AI calls/logout,
  byte-oriented log checks and owned cleanup passed. Independent read-only Pi
  inspection verified the unchanged Ready live pod/image with zero restarts and
  removal of the temporary namespace, progress file and owned forwarding process.
- Both models received byte-identical prepared packets/factual renderings and
  report bindings. Frozen source comparison confirms only the runner's staging
  diagnostics changed; task/prompt/fixtures/rubric/options/images stayed fixed.
  Older captures and their extractions remain unchanged.

## Current decision

Task 7.2 is complete as an executed, replayed and documented synthetic experiment,
not as model/application qualification. Qwen3.5 is the stronger candidate in this
small known-fixture batch; one extra passing case is not a robust general ranking.
Neither model's correction loop helped its rejected focus selections. A larger
Pi is not established as necessary for this task and would not automatically fix
the remaining semantic/relevance error. Keep AI off and preserve the current cap.
No further experiment, prompt/rubric tuning, hardware change, publication or live
deployment is authorized by completing this run. Production grouping/scheduling
and tasks 4.4/4.6/4.8 remain unchecked.
