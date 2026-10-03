# Fresh Qwen3.5 prepared-review cases

## Approved scope

Following the completed known-fixture run (5/6 relevant Qwen3.5 selections), the
user approved testing Qwen3.5 on fresh review cases before production integration.
This is one isolated synthetic run, not approval for live inference, actual health
data, a model/cap/hardware change, release promotion or hosted transfer.

The unchanged `prepared-focus-v1` contract, system prompt, code-owned grouping,
supported candidate kinds, relevance rule, runtime options and correction policy
are retained. Only fixture-suite support and the new predetermined synthetic
records/focus assignments/candidate rotations are added to the operator runner
and independent replay. Earlier frozen captures and extracted results stay intact.

## Frozen suite: `fresh-v1`

Ten usable cases cover:

- Four daily-combined reviews, including opposing activity/wellness directions,
  two different zero-baseline variants and two unchanged sports with changing
  wellness.
- Two wellness reviews: wholly unchanged populated measurements, and available
  HRV with missing previous sleep. Available wellness remains the requested focus
  rather than automatically switching to its separate coverage gap.
- Two activity-balance reviews: opposing sport directions and both sports down.
- Two missing-wellness reviews: missing current HRV and missing previous HRV,
  while sleep remains available.

Two further sparse cases (one activity record per period; no current sleep/HRV)
make no inference call. The source is 58 synthetic activity records and 48
synthetic wellness records, all within the runner's existing 2020 history-check
scope. The Kotlin app still calculates facts from the seeded records.

Correct candidates occupy different positions within each requested-focus type.
Rotations and the private expected-kind rubric are frozen before any model output
review. The rubric is not transmitted to the model. The records have new
names/dates/sports/values and direction/availability combinations, but represent
known task structure, not an external blinded holdout or medical-advice evaluation.
Do not tune instructions, values, routing, rotations or scoring after inspecting
outputs; do not pool this rate with the earlier 5/6 or Qwen3's 4/6.

## Limits and evidence

- Pinned Qwen3.5 4B manifest:
  `2a654d98e6fba55d452b7043684e9b57a947e393bbffa62485a7aac05ee4eefd`.
- Pinned Ollama `0.35.0`; three cores/5 GiB, 2048 context, 256 output tokens,
  temperature zero, seed 42, thinking off and unload after each call.
- 240-second call timeout, 600-second case budget, at most one new independently
  validated correction for selection errors. At most 20 inference calls; neither
  sparse gate calls the model. No retry on staging/resource/timeout/evidence
  failure, no larger limits and no fallback.
- Retain exact original transport bytes, report/packet bindings, code-generated
  complete facts, contract validity and requested-focus relevance separately,
  first-pass and corrected outcomes, no-call gates, resource telemetry,
  controls/log/history checks, incomplete failures and owned cleanup/live state.
- Even perfect focus selection would not demonstrate physiological reasoning,
  adaptive coaching or added value over deterministic selection. Production
  grouping/scheduler/queue/UI remain unimplemented and live AI stays off.

Frozen system SHA-256:
`cf0ce0b109a24aad184a5d44d90b84321cbc747e9c4d5516d1d1770aaaa569e1`.
Unchanged contract-source SHA-256:
`309dc4e72611f094d6fdc1b9baef81f24c5a213cbfcece9baa7053501946f93d`.
Fresh fixture SHA-256:
`126e0c01af3be2a0537b2d01d113a9f7cfcc639869d0408d390fba5f56f74579`.
Fresh private rubric SHA-256:
`fee3eaa5931741eb92e15d0257d9d3dd4bfb4f6e258516edfe1ea40a67ebb2f4`.

[Fresh fixtures](../../benchmarks/ollama/prepared_review_fresh_fixtures.py),
[suite selection](../../benchmarks/ollama/prepared_review_suites.py) and
[regression tests](../../benchmarks/ollama/test_prepared_review_fresh.py) are separate
from the immutable contract and original fixtures. The new runner requires explicit
`--suite fresh-v1` and Qwen3.5 selection. No result is claimed before completion
and original-byte replay.

Prelaunch checks passed: 103 benchmark tests, 47 utility tests, strict spec
validation, local links and diff checks. All three earlier prepared-review
extractions still reproduce exactly from original bytes. Frozen SSH packaging
also verifies that the contract and existing execution dependencies are unchanged:
only the runner adds suite selection, with two new fixture/suite modules.

Frozen fresh-run bootstrap SHA-256:
`120ce14653c9b67fcc8a4f6525f37c276d1317825356940329df360dfed477f3`.
The explicitly approved run is launched in the background; final acceptance,
resources and cleanup remain pending until its original capture is replayed.

## Partial run: control-command timeout

The [retained partial extraction](../../benchmarks/ollama/results/2026-10-02-qwen35-prepared-focus-fresh-partial.json)
matches original-byte replay. Original JSONL SHA-256:
`aae4a32268d5873cca816e00c511647a050f1e757ec2b89ab9e6adbe5f25de5a`.

The run stopped during the fifth case's corrective attempt when the operator's
`kubectl get pod` health/state subprocess exceeded its 30-second timeout. This is
a controller-command failure, not a demonstrated model HTTP timeout or OOM. The
last failure-state query showed app, Ollama server and proxy running/Ready with
zero container restarts. The eighth inference request had started, but its HTTP
response was not exported before control polling failed; its outcome is unknown.
No unrecorded response is reconstructed or counted as acceptance.

- Four cases finalized: **2/4 relevant final reviews**. Five distinct first-pass
  responses were retained: **2/5 relevant first-pass selections**. These are
  incomplete subset counts, not a score for all ten planned cases.
- Daily opposed directions and wholly unchanged wellness passed. Opposing sport
  balance selected an activity-only candidate instead of the sport-mix candidate;
  missing current HRV selected available wellness instead of the coverage gap.
  Their two received corrections repeated exactly the same unsuitable choices.
- The fifth case's first response selected wellness-only for the zero-baseline
  daily-combined request and was rejected. Its corrective response is unrecorded.
  The other five usable cases and both sparse gates were not reached.
- All seven received model outputs passed the closed contract, finished with
  `stop`, and contained eight output tokens/no thinking trace. The relevance
  failures were not output truncation or malformed JSON. Received calls took
  78.917–106.666 seconds; finalized corrected cases took up to 211.050 seconds.
  Independently prepared facts covered every required item for each started case.
- 146 retained resource samples: lifetime cgroup peak 5120 MiB, maximum sampled
  current 5116.0 MiB, minimum host available 1756.7 MiB, maximum temperature 74.9°C.
  No sampled live/synthetic health failure was recorded. These measurements do not
  establish the underlying cause of the controller stall or the unrecorded call.
- Synthetic chart probes took 0.0195–0.0818 seconds with unchanged report bindings.
  Final history/logout/log-scan checks and sparse gates did not run. Owned cleanup
  passed; independent read-only Pi inspection verified the same Ready live pod,
  pinned image and zero restarts, with no benchmark namespace, progress file or
  owned forwarding process remaining.

## Decision

Keep the run partial and preserve all earlier results. The new cases already
contain genuine focus-relevance errors, but no full-suite rate or general model
ranking is claimed. Code-owned coverage still does not prove useful model
interpretation. Live AI and production qualification remain unchanged/off.

Before a separately approved rerun, make received-response export independent of
fallible synchronous control polling and retain explicit control timeout evidence.
Do not waive resource/live-health guards, silently retry the failed experiment,
change the frozen task/rubric or raise caps. A Pi upgrade is not justified solely
by this unresolved controller failure.

## Approved capture/control fix and one rerun

The user explicitly approved fixing the operator runner and rerunning this same
frozen suite once. HTTP completion now exports original response bytes from the
inference worker immediately, before fallible chart/control/postvalidation queries.
A received response remains distinct from validated/finalized acceptance; a
later guard failure cannot promote it or fill unknown outputs.

Temporary-pod state polling moves out of the inference wait loop into a background
monitor: one bounded query every ten seconds, with a ten-second command timeout
and a maximum successful-state age of 25 seconds. Unreadable, stale, unhealthy
or restarted state fails closed with explicit sanitized control-failure evidence.
The previous one-query-per-second pressure is removed, not the guard. Host/live
health checks, per-case live identity checks, post-response validation, budgets,
unloading and owned cleanup remain required. Polling stops before terminal success;
response workers must stop before progress-file removal.

Regression tests cover blocked control polling concurrent with successful response
export, subsequent controller timeouts, stale/unhealthy state, transport failure
without invented output, and bounded diagnostics without error-text credentials.
Local checks passed: 109 benchmark tests, 47 utility tests, strict specs and diff
checks. All four earlier original-byte extractions still reproduce exactly.

Frozen SSH packaging verifies that only `run_prepared_review_on_pi.py` changes.
Contract, prompt, fixtures, rubric, candidate positions, generation settings,
images and three-core/5 GiB limits are byte-identical to the partial run. This is
an explicitly authorized repeat, not an automatic quality retry or a new holdout.
The original partial artifact remains unchanged and results must not be pooled.

Frozen rerun bootstrap SHA-256:
`4714e79664daebe3cbb21f2a614d97fcd59cca8af3e048798263246bf7c865ae`.
The rerun is launched in the background; outcomes remain pending original-byte
replay. No live inference or additional rerun is enabled.

### Rerun capture remained incomplete

The [preserved incomplete rerun](../../benchmarks/ollama/results/2026-10-02-qwen35-prepared-focus-fresh-rerun-incomplete.json)
contains seven finalized cases (4/7 relevant), ten received outputs and eleven
started calls. Its eighth case's first response is unrecorded. The capture has no
terminal completion/failure/cleanup records, final controls or sparse gates.
No full-suite rate or specific failure cause is established. Read-only inspection
later found no benchmark process/namespace/progress/forwarding resources and the
same Ready live app/image with zero restarts; that independent inspection does not
manufacture missing runner records or change `cleanup_verified` in the extraction.

The user then explicitly approved continuing or restarting. Since the missing
terminal and response records cannot be recovered from the removed resources,
restart the full unchanged suite once rather than splice partial attempts into a
claimed complete run. The original partial and incomplete rerun stay separate.

## Durable Pi-side capture for the authorized restart

The [capture supervisor](../../benchmarks/ollama/prepared_review_capture.py) runs the
exact frozen bootstrap once in a detached Pi process and writes original stdout,
stderr and atomic lifecycle metadata to a unique root-owned mode-0700 directory
under `/run`; files are mode 0600. It receives only benchmark code/synthetic data,
not live health records, login/source secrets or cluster credentials. Output no
longer depends on a continuously connected Mac SSH reader.

The observer blocks on the owned Linux process descriptor, without a repeated
status/output polling loop. If SSH observation fails, the private capture remains
on the Pi for later hash-verified recovery; observation never relaunches inference.
The supervisor bounds execution to 7600 seconds, requests interrupt-based runner
cleanup on expiry and records any forced termination as cleanup-unverified.

After terminal metadata and byte lengths/hashes match local copies, replay the
original JSONL. Only then remove the exact known files in that owned finished
capture directory; refuse unknown contents, symlinks, owner/hash mismatch or
nonterminal state. This adds durable synthetic evidence retention, not another
model retry loop. Prompt/contract/fixtures/rubric/runtime/caps and the repaired
benchmark runner remain unchanged. No model-quality result is claimed yet.

Local checks before this restart passed 119 benchmark tests and 47 utility tests.
Packaging verifies every bundled source is byte-identical to the incomplete
rerun; only the unique owner and external capture mechanism differ. The frozen
restart bootstrap SHA-256 is
`a46b1314dea953bbb883e8ebf200dd9aabbc74b97bc9c568a41ea30d0052396f`;
the capture-supervisor source SHA-256 is
`0a2bef7ca6d4dadb51a81addb5db321663686b3291da61103275168bcc4cace2`.
The single authorized restart is observed in the background. No further launch,
production inference or qualification claim follows automatically.

## Complete durable restart: 5/10 relevant reviews

The [retained complete extraction](../../benchmarks/ollama/results/2026-10-02-qwen35-prepared-focus-fresh-durable-complete.json)
reproduces exactly from the original capture. Original JSONL SHA-256:
`004e2b783808779d5d758b38c06f20fe3d5716e459ba595a1be2cb686a0bf7ec`.
The supervisor recorded exit code zero, no deadline/forced termination and empty
stderr. The full isolated run lasted about 28 minutes, including staging and
cleanup. Every planned case, final control check and both sparse gates completed.

- **5/10 first-pass and 5/10 final requested-focus relevance**. All 15 received
  responses passed the closed contract. Five corrective attempts repeated the
  exact unsuitable first-pass selection; none rescued a rejected review.
- Daily opposed directions, both wellness cases, both-sports-decreasing balance
  and missing previous HRV passed. Opposing sport balance selected activity-only;
  missing current HRV selected available wellness; both zero-baseline daily
  reviews and unchanged-sports daily review selected wellness-only instead of
  the supported combined pattern. All five irrelevant reviews were withheld whole.
- All facts, including unavailable measurements, stayed independently code-owned.
  Valid IDs and syntax therefore did not establish requested-focus relevance.
  No response repair, prompt/rubric tuning or splicing of older partial attempts
  occurred. Keep this ten-case rate separate from the known-suite 5/6 result and
  both incomplete fresh captures. This is still not an external blinded holdout.
- Calls took **67.622–103.871 seconds**; the longest corrected case took
  **207.841 seconds**, within the unchanged 240/600-second experimental budgets.
  Installed-app 120/130-second bounds and three-core/5 GiB limits remain unchanged.
- Across 270 resource samples, peak reached **5120 MiB**, minimum host available
  memory was **1697.8 MiB**, and maximum temperature was **75.45°C**. No sampled
  live/synthetic health failure or resource-guard failure occurred. All 144
  background container-state samples were healthy, with zero restarts. Reaching
  the memory cap is not evidence of spare capacity or sustained qualification.
- Both sparse gates made zero model calls. Unload checks, unchanged synthetic
  history, no app AI calls, logout and bounded log scans passed. Runner cleanup
  removed the owned namespace/progress/forwarding resources and retained the
  same Ready live pod, UID/image and zero restarts.

Terminal metadata, original capture bytes, bootstrap and supervisor hashes were
verified against local copies before deleting only the known files in the owned
finished Pi capture directory. Independent read-only inspection confirmed the
unchanged Ready live app and no benchmark namespace/progress/forwarding leftovers.
Original private captures remain on this Mac; earlier artifacts are unchanged.
Post-result checks passed 121 benchmark tests and 47 utility tests; artifact
regressions distinguish valid-but-irrelevant output, ineffective corrections and
the earlier incomplete rerun's missing terminal records.

The capture mechanism worked for this complete run, but focus relevance remains
unreliable. Neither production integration, physiological reasoning, coaching,
physical UI acceptance nor sustained-resource tasks are qualified. Live AI stays
off, no default or hosted fallback is selected, and no further run is authorized.
The recommended next direction is deterministic factual rendering/focus selection
before considering another explicitly scoped model experiment.
