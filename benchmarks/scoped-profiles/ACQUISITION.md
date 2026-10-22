# Bounded synthetic intake acquisition on the Mac

This additive path does not modify the frozen v1 evaluation, historical runners or
the offline-only [capture workflow](CAPTURE.md). It supports only the eight frozen
`context-intake-suggestion-v1` cases with the staged, pinned `qwen3:8b` candidate.
Inference approval, offline replay approval, independent human scoring, personal
runtime acceptance and exact personal amendment approval remain separate.

## Approval and private inputs

Use a new external directory with mode `0700`, no symlinked parents, and regular,
single-link input files with mode `0600`. Never use personal data. The runner reads:

- `acquisition-run.json`: exactly `version` (`scoped-intake-acquisition-v1`), canonical
  UUID `runId`, `profile`, ordered `caseIds` (`i01` through `i08`), `manifestSha256`,
  `model`, `settings`, `resourceLimits`, `runtime` and `toolingSha256`. The model,
  settings and limits must match `acquire.MODEL`, `SETTINGS` and `LIMITS` exactly,
  including numeric types. Runtime identity must match `mac_runtime.IDENTITY`.
  Tool hashes bind the current `acquire.py`, `mac_runtime.py`, `run_mac_intake.py`
  and unchanged `capture.py` bytes through `acquire.tooling_hashes()`.
- `inference-approval.json`: exactly `version`
  (`scoped-intake-inference-approval-v1`), `runSha256` (SHA-256 of
  `protocol.compact(run)`), `purpose` (`synthetic_intake_inference_only`),
  `recordedBy`, `recordedAt`, `expiresUtc`, `allowCorrection`,
  `allowCancellationProbe` and `approvalEvidence`. UTC timestamps use `Z` and
  span no more than 24 hours. Correction permission is Boolean; probe permission
  must be explicitly true. Record actual user permission, not assistant approval.

The CLI never creates either input or approves a run. A matching JSON file is not
authentication. Changed tooling requires a new reviewed run binding, not alteration
of an already recorded attempt. Existing outputs are never overwritten.

## Fixed envelope and runtime behavior

Settings are temperature 0, seed 42, three threads, 4096 context tokens, 1536 output
tokens, `think=false`, `stream=false`, `keep_alive="0s"`, and JSON format. Bounds are
900 seconds per attempt, 1800 seconds per case, four hours per batch, and 60 seconds
for stop verification. Probe startup/loading uses at most the same 900-second
attempt budget, shortened by the batch deadline; its separate 60-second stop timer
starts only after a loaded model and unfinished request are observed. The replacement
pod after a verified probe has at most 60 seconds to become Ready, also capped by
the remaining startup deadline. Approval expiry can shorten the batch. Case and batch time
include actual offline Kotlin validation. There is no fallback candidate or hidden
retry. At most one approved correction follows a rejected or empty response, using
only the exact frozen correction prompt. Timeout, incomplete response, missing
monitoring or infrastructure failure stops the batch.

The currently approved VM is six vCPUs / 12 GiB. Model limits are three CPUs / 10 GiB;
current container memory above 9 GiB aborts. This later change was explicitly approved
for fresh run `v3-qwen-intake-02`; historical run records retain their original limits.
The completed baseline below used
the earlier 8 GiB VM / 6656 MiB hard limit / 6144 MiB stop threshold; its evidence
and resource identity are not rewritten. A separately approved v3 non-scored probe
later hit the unchanged 7.5-GiB stop before any scored case; see the
[v3 attempt record](../intake-extraction-v3/README.md#named-live-attempt-v3-qwen-intake-01-2026-10-18).
No successful scored run under the larger envelope is established. Host pressure and node/pod health
are checked during acquisition. Samples are observations, not continuous guarantees.
Container peaks are cumulative, not isolated per-attempt RSS; unavailable RSS and
cost remain null. Decoded drafts are bounded to 12,288 UTF-8 bytes and provider
transport to 65,536 bytes.

Read-only preflight checks pinned runtime, model digest, existing empty server,
network policies and host pressure. For the pinned GPT-2 byte-BPE tokenizer and
exact template, input UTF-8 bytes plus a conservative 256-token template allowance
provide an upper bound, not a measured token count. The maximum intake bound is
2088 input tokens; adding 1536 output tokens fits within 4096. This reasoning does
not admit the two longer profiles or another template/tokenizer.

The benchmark lock is shared with the historical Mac runner. A dedicated,
non-controller pod also isolates inference: loopback-only API, no Service, no
service-account token, pinned cached image with `imagePullPolicy: Never`, no model
pull, and existing deny-ingress/egress policies. The existing model deployment is
temporarily paused. The one approved non-scored probe must demonstrate an active
request, UID-preconditioned pod deletion, empty/absent owned cgroup processes and
absence of a running CRI identity. A verified probe alone permits its one new pod
for scored requests. An HTTP timeout or API deletion acknowledgment is insufficient.
Failed stop verification forbids further inference and restoration that could
overlap an unverified process. This is coarse synthetic benchmark cancellation,
not personal-runtime acceptance. Live isolation and cancellation remain unproven
until a successful actual probe is recorded.

## Execute and inspect

Only after explicit approval and fake checks:

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 25) \
  python3 benchmarks/scoped-profiles/run_mac_intake.py --directory <private-directory>
```

Private outputs are `acquisition-events.jsonl`, `acquisition-result.json` and
`replay.json`. Events retain exact scored request payloads, provider response
chunks, timings and observations. The result retains all eight cases, validation
outcomes and stop-verification status. Unstarted cases remain explicitly unrun.
The replay uses the existing `scoped-private-replay-v1` structure; producing it does
not approve offline replay or create human scores. See [CAPTURE.md](CAPTURE.md) for
that separately approved step. Qualification remains incomplete and production
admission remains closed, even if all Kotlin drafts validate.

Fake tests use no network, cluster or model calls:

```sh
python3 -m unittest discover -s benchmarks/scoped-profiles -p 'test_*.py'
python3 benchmarks/scoped-profiles/protocol.py
```

## First approved attempt, 2026-10-18

The user explicitly selected **Approve bounded run** for the stated intake envelope,
including one correction per case and one bounded cancellation probe. The first
attempt stopped in metadata preflight: the bounded HTTP reader lacked `readinto`,
needed for a Content-Length response. All eight cases were retained as unrun; no
chat/generation request, probe, pod creation, scale change or download occurred.
Its private directory basename is `scoped-qwen-intake-approved-mzjqf_2b`; retain its
original inputs and outputs unchanged.

The transport now implements bounded `readinto` and has a regression using the
actual metadata response shape. All 47 scoped Python tests pass. A subsequent
read-only preflight verified Ollama 0.35.0, the staged digest, exact template and
2088 + 1536 <= 4096 context bound without inference or cluster mutation. The actual
Kotlin callback also accepted a synthetic valid intake draft and rejected `{}` in
two offline invocations. These checks do not prove live cancellation or usefulness.
No automatic rerun occurred. A new run requires explicit retry confirmation,
fresh private records and current tooling hashes. Offline replay and human scoring
remain unapproved and outstanding; task 9.4 stays unchecked.

## Explicitly approved retry, 2026-10-18

After the first attempt was explained, the user explicitly selected **Approve
bounded run** again for one fresh attempt under the unchanged envelope. Its private
directory basename is `scoped-qwen-intake-retry-approved-e9__uggv`; both attempts'
original inputs and outputs remain unchanged.

Read-only preflight passed. The existing deployment paused and one cached-image,
owned pod started. Exactly one non-scored chat request attempted the cancellation
probe. The adapter did not observe a loaded model with an unfinished request before
its probe observation window ended, so it aborted with `probe_not_active`. It sent
no scored intake requests: all eight cases remain unrun. This is a runtime/probe
readiness failure, not a measured intake-quality result.

The runner version used for this attempt gave the entire probe path a 60-second deadline,
including scaling, pod startup and model loading, and reserves 15 seconds at its
end. That is more restrictive than a separate 60-second cancellation-verification
allowance. The model was still loading during the observed window; the evidence
does not establish the time needed to become ready. Do not silently extend that
deadline or automatically run another probe. A future correction should distinguish
bounded startup from cancellation time, with fake tests and a newly approved run.

Maximum sampled current memory was **6136.18 MiB**, close to the unchanged 6144 MiB
abort threshold. Maximum observed cumulative peak was **6194.89 MiB**; that peak
is not an isolated per-attempt RSS value or proof of a sampled-current violation.
No inference-memory headroom or useful-response fit has been demonstrated.

Abort cleanup recorded UID-preconditioned deletion, empty/absent owned cgroup
processes and no running owned CRI identity; `cancellationVerified` is true. This
proves coarse cleanup of this loading workload, not the required cancellation of
observed active generation. No scored pod or further inference started. The shared
deployment restored successfully with a new Ready pod and restart count zero;
the earlier pod's restart count of one remains historical, not reset in place.
The pre-existing forwarding service initially held its deleted pod, then reconnected
through its existing systemd restart policy. Final loopback metadata checks passed:
Ollama 0.35.0, both original staged model digests, and an empty `/api/ps`. Node Ready,
Bound PVC, network policies and normal host pressure were also observed.

No new download, application operation, offline replay or independent scoring
occurred. No command remains pending and no further run is approved. Task 9.4
remains unchecked; all profiles remain unqualified and admission stays closed.

## Timer correction after the retry

At the user's request, startup/loading and stop verification now have separate
timers as described above. The 900-second startup allowance reuses the approved
attempt ceiling; it does not enlarge any scored attempt, batch, context or memory
limit. Stop verification still has its own 60-second maximum. A failed/expired
startup or unverified stop cannot create a scored pod. No automatic retry was added.

All **50 scoped Python tests** pass, including a cold-loading fake that becomes
ready after 120 seconds and still receives the full 60-second cancellation budget,
startup expiry without a scored-pod restart, and the core's 900-second probe bound.
Frozen integrity remains unchanged. No live call used this corrected version yet.
Before another run, obtain explicit confirmation of the two separate probe timers
and create fresh records bound to current tooling hashes. Offline replay needs its
own permission; independent human scoring cannot be supplied by this runner.

## Corrected-timer baseline and offline replay, 2026-10-18

The user explicitly approved the corrected probe envelope and separately approved
offline replay if responses were produced. Acquisition basename:
`scoped-qwen-intake-timers-approved-iicij2sj`. All original evidence is retained.
The active-request cancellation probe passed, followed by all eight intake cases
in frozen order. There were **15 complete responses, all rejected by the actual
Kotlin validator**, and one incomplete final correction. Completed provider calls
took **118.1–397.2 seconds** each; total acquisition elapsed **61.42 minutes**.

The last correction stopped on `memory_abort_threshold`. Maximum sampled current
memory was **6249.38 MiB**, above the original **6144 MiB** stop threshold but below
its **6656 MiB** hard limit. Maximum cumulative peak was **6255.23 MiB**. The adapter
observes sampled values, not continuous memory; it stopped after the crossing was
observed. Owned pod/cgroup/CRI termination was verified and the shared deployment
restored. This is not an OOM event or personal-runtime acceptance. No fallback,
additional correction, prompt edit or limit increase occurred during acquisition.

Separately approved unchanged `capture.py` replay completed at basename
`scoped-qwen-intake-replay-approved-vrvdt0d5`. It retained all **16 attempts / eight
cases**, with seven rejected finals and one incomplete final. Actual Kotlin replay
again rejected all 15 complete responses. Human-review materials exist privately;
all human scores, useful-final assessments and critical-failure assessments remain
blank. The replay made no inference calls. This model/settings configuration has
not passed intake qualification; the other two profiles were not run.

Observed structural failures include missing `context`/`commitment` payloads and
an unsupported `payload` key. These observations are not a second acceptance
validator or proof that every rejection has the same cause. The frozen prompt and
schema were not changed to rescue this run. A narrower, schema-guided experiment
requires a new reviewed version and separate approval, not silent refresh of v1.

## Approved memory resize after the baseline

The user explicitly selected **Resize after current run**: 10 GiB VM, 8 GiB model
hard limit and 7.5 GiB stop threshold. The operation ran only after acquisition
ended. Private operation evidence basename: `scoped-mac-ram-resize-approved-jrs2w73w`.

The VM stopped gracefully. Only `memory: 8GiB` changed to `memory: 10GiB` in the
local Lima configuration; validation/restart passed. The model deployment changed
only its container memory limit from `6656Mi` to `8Gi`, using UID/resource-version
and old-value preconditions. The acquisition preset now matches the approved
8 GiB / 7.5 GiB envelope; old resource records are refused rather than upgraded.

Acceptance verified Running **6 vCPU / 10 GiB / 40 GiB**, six guest CPUs, original
Ready node, Bound PVC, unchanged deployment identity/image/rest of spec, identical
network policies and both staged model digests. Ollama remains 0.35.0, three CPUs,
with no loaded model. Loopback `127.0.0.1:11435` is healthy; the existing forwarding
service was restarted after pod replacement. The replacement shared pod has
restart count zero; predecessor restart histories are not rewritten. Host memory
pressure was normal and swap use about 4.1 GiB at acceptance, not a sustained load
test. No model call, pull, app/Pi operation or storage resize accompanied this change.

Current fake suite: **53 scoped Python tests**, including exact new memory-stop
boundary, refusal of the old envelope and refusal of an undersized VM. Runtime
identity now binds the six-vCPU / 10-GiB allocation; metadata-only preflight verifies
it before any model request. Frozen integrity remains unchanged.
The larger envelope is not performance/quality evidence and does not authorize a
new inference run. Task 9.4 remains unchecked and production admission stays closed.
