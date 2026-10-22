# Temporary Mac cluster and scoped-profile qualification plan

Status: **baseline and offline replay complete; no accepted drafts; approved RAM resize complete,
2026-10-18**. The user
selected this Intel Mac as the temporary k3s host and requested candidate proposals.
After the sizing discussion, "ok, use it" approved the current-Mac six-vCPU /
8 GiB configuration and its required restart. Later, the user explicitly selected
**Approve bounded run** for staged Qwen3 8B, eight synthetic intake cases, at most
one correction per case and one bounded cancellation probe. That approval does not
authorize downloads, app relocation, personal runtime acceptance or app deployment.
Task 9.4 remains unchecked. The proposed eight-vCPU configuration is for a future
six-core/twelve-thread host, not this Mac.

## Reuse the existing cluster

Read-only local inspection found:

- Intel Core i7-7920HQ, eight logical CPUs and 16 GiB physical RAM.
- Approximately 667 GiB free on the workspace volume. This is not a measurement
  of free space inside the VM or model PVC.
- Lima 2.2.1 and an already **running** `gtrainer-models` VM with four virtual CPUs,
  8 GiB RAM and a 40 GiB disk. No VM was started or changed by this inspection.

The existing [local cluster record](../../benchmarks/connected-review/local-k3s/README.md)
documents a separate CPU-only k3s cluster, pinned Ollama 0.35.0, a three-CPU /
6656 MiB model container, persistent model PVC, loopback port 11435 and denied
runtime egress. These guest/runtime facts require fresh approved preflight checks;
the local VM listing does not verify them. No Kubernetes command, model API call
or remote Pi operation was performed during planning.

The user clarified that the requested stronger machine means **VM configuration**,
and described eventual model hosts with six physical cores / twelve threads and
"16 MB" RAM. The proposals below assume **16 GiB-class RAM**, not literally 16 MB.
The current Mac remains four physical cores / eight logical CPUs; virtual CPUs
cannot give it the physical throughput of a newer six-core host.

Suggested working configurations, leaving capacity for interactive host work:

| Physical host | VM CPU / RAM | Ollama CPU limit / threads | Ollama hard RAM limit |
| --- | --- | --- | --- |
| Current Mac, 4 cores / 8 threads | 6 vCPU / 8 GiB | 3 CPU / 3 threads | 6656 MiB |
| Proposed host, 6 cores / 12 threads | 8 vCPU / 8 GiB | 4 CPU / 4 threads | 6656 MiB |

Keep the existing 40 GiB disk on this Mac. Eight GiB guest RAM leaves approximately
eight GiB for the host before hypervisor overhead. The 6.5 GiB model ceiling leaves
1.5 GiB inside the guest for Linux, k3s and supporting processes. On the six-core
host, eight guest virtual CPUs leave four host logical CPUs outside the guest's
maximum parallelism; the four-CPU model quota leaves guest capacity for k3s. These
are scheduling budgets, not pinned or exclusively reserved physical cores, and
SMT threads do not provide independent full-core performance.

A short read-only sample on this Mac found roughly 37% aggregate CPU use, 3.7 GiB
resident memory in its existing VM process and **9.5 GiB of swap already in use**.
The pressure query did not establish current severe memory pressure, and accumulated
swap alone does not prove it. Nevertheless, these observations do not justify
raising guest RAM to 10–12 GiB while continuing local work. Sampling is not a
sustained inference/build stress test. Keep memory unchanged and verify pressure,
paging and responsiveness during any separately approved run. No larger allocation
or CPU count is a promise that an 8B model or long context will fit.

Applying CPU changes requires separately approved configuration updates and a
VM stop/restart, interrupting the guest cluster. Preserve its disk/PVC and staged
weights. That approval has now been given for this Mac only. The operation changes
only `cpus: 4` to `cpus: 6` in the existing local Lima configuration, not the
historical benchmark template or results. RAM, disk, model image, container limits
and stored weights remain unchanged. Guest restart and acceptance checks are tracked
in the operation record below.

Do not join the cluster to the Pi, install another cluster, add public ingress or
move personal records. The live Pi application and
its release/database state remain untouched. Relocating the application itself
would require a separate deployment and data-handling plan; it is not needed for
the remaining synthetic qualification task.

## Candidate order

### First: staged Qwen3 8B, intake profile only

Proposed candidate: `qwen3:8b`, Q4_K_M, manifest SHA-256
`500a1f067a9f782620b40bee6f7b0c89e17ae61f686b92c24933e4ca4b2b8b41`.
The historical local record says these weights are already staged; current presence
and digest must be checked before use. Do not pull missing or mismatched weights.

Run all eight `context-intake-suggestion-v1` cases only after explicit approval and
preflight. This candidate is first because it avoids a new download, not because
its legacy results prove new-profile usefulness. Its intake result cannot qualify
descriptive review or amendment selection. Defer staged `llama3.1:8b`: its historical
empty review arrays provide little reason to prioritize another scarce CPU run.

### Later: one smaller candidate for the long profiles

Shortlist `qwen3.5:4b` and `granite4.2:3b` for a separate artifact/context assessment.
Approve at most one new download initially. Neither parameter count nor download
size proves inference-memory fit, runtime compatibility or useful output.

Read-only public registry inspection on 2026-10-18 found the current GGUF
[`qwen3.5:4b` manifest](https://registry.ollama.ai/v2/library/qwen3.5/manifests/4b)
has SHA-256
`d8b0f5e9760cd1682034f292d7ef72ec46f432149be0df7574bf2d6e92e38c04`.
Its declared layers total **3,324,173,757 bytes**, including the projector; the model
layer alone is 2,648,593,216 bytes. Only public metadata was fetched, not weights.
The tag has changed from the historical Pi manifest. The old manifest-by-digest
lookup returned 404; do not silently substitute the new tag in a legacy experiment.
Compatibility with the installed pinned Ollama version is unverified. A runtime
upgrade, alternate quantization or changed model pin requires another reviewed
proposal and approval, not automatic fallback. Granite's current artifact has not
yet been checked; it is an alternative, not a selected download.

## Context is the immediate constraint

The frozen prompt objects have these maximum UTF-8 sizes, including their JSON
encoding. They are **not token counts**:

| Profile | Initial | Correction |
| --- | ---: | ---: |
| Intake | 1,742 bytes | 1,926 bytes |
| Amendment selection | 20,758 bytes | 20,942 bytes |
| Descriptive review | 32,552 bytes | 32,736 bytes |

The historical 4096-token setting is not established as sufficient for the two
long profiles. Preflight must verify the candidate's effective rendered input,
chat template and reserved output fit without truncation. If exact fit cannot be
verified, refuse the run. Do not shorten packets, remove cases or alter frozen
prompts. An 8192- or 16384-token context is only a possible later configuration,
not a measured fit or an approved resource increase.

## First-run envelope, subsequently approved

- Scope: eight intake cases in frozen order; synthetic data only; no app route,
  personal store, hosted transfer or production admission.
- On this Mac: proposed **six virtual CPUs / 8 GiB / 40 GiB**, keeping the container
  at **three CPUs / 6656 MiB** while host work continues. On a verified six-core /
  twelve-thread host, propose eight virtual CPUs and four model CPUs instead.
  Record that as a distinct hardware/settings configuration, not an interchangeable
  result. The current-Mac VM change is approved; the future-host sizing remains
  a proposal. No inference benchmark has been run on the new sizing.
- Additional conservative stop threshold: **6144 MiB current container memory**.
  Historical shared-pod peaks reached 6469.7–6656 MiB, so this threshold may refuse
  Qwen. This is deliberate headroom, not proof that the candidate will fit. Record
  cumulative peaks as cumulative; do not label them isolated per-attempt peaks.
- Requested first-run settings on this Mac: `temperature=0`, `seed=42`, `num_thread=3`,
  `num_ctx=4096`, `num_predict=1536`, `think=false`, `stream=false`,
  `keep_alive="0s"`, `format="json"`. Record the exact request and model/template
  identity; no implicit setting escalation or hidden reasoning allowance. The
  stronger-host proposal uses four threads and needs its own frozen run record.
- Limits: **900 seconds per attempt, 1800 seconds per case, four hours per batch**.
  Case/batch deadlines include validation overhead. Allow at most 60 additional
  seconds for cancellation verification, not continued generation. No latency
  forecast is implied. A deadline leaves remaining cases explicitly unrun.
- At most two attempts per case. One correction requires explicit permission and
  a correctable whole-draft rejection under the actual Kotlin validator. Send only
  the exact frozen correction prompt. No legacy feedback injection, fallback or
  correction after timeout, incomplete generation or infrastructure failure.
- Maximum decoded draft: **12,288 UTF-8 bytes**. Separately bound the provider
  wrapper/transport to **65,536 bytes** and the wire request to **65,536 bytes**;
  these transport bounds do not enlarge the frozen 32 KiB prompt limit.
- Require healthy host memory pressure and functioning host/VM/container monitors.
  Abort on missing monitoring, memory pressure, threshold crossing, identity drift,
  OOM/restart, failed isolation, unexpected thinking, truncation or deadline.
  Do not raise limits or restart automatically after a failure.

## Smallest implementation slice

Implement one **additive acquisition harness** and fake tests first. The existing
legacy Mac runner sends different packets and uses different validation. Do not
repurpose it for these profiles. Keep frozen files and the offline-only
[`capture.py` workflow](../../benchmarks/scoped-profiles/CAPTURE.md) unchanged.

The new harness must bind the exact model/runtime/settings, frozen manifest,
case list, initial/correction bytes and explicit permissions before any call. It
must retain private request/response bytes, hashes, every outcome and observed
resources. Use the actual Kotlin validator, not a second Python schema. Acquisition
and offline replay need distinct approval records; the harness must not manufacture
either approval or write qualification/runtime configuration.

Acceptance for that implementation slice is usable fake acquisition covering:
zero calls without valid approval; digest/identity/context refusal; exact prompt
bytes; complete eight-case accounting; bounded correction; deadlines and response
limits; memory/monitor failure; cancellation verification; no automatic retries;
and private evidence that the existing offline replay accepts. No live model call
is needed to implement or verify these paths with fakes.

Before a later live run, separately approve read-only runtime preflight and any
explicitly bounded synthetic cancellation probe. A probe is non-scored evidence,
never a replacement for a frozen case. Establish exclusive ownership and permission
to interrupt every workload in the targeted benchmark pod. A local advisory lock
alone does not exclude unrelated callers. If ownership is not established, stop.

On abort, close the transport and stop only the approved owned inference. Verify
targeted pod/process termination and absence of replacement inference within the
cancellation bound. A scale-to-zero acknowledgment or HTTP timeout alone is not
proof of stopping. Failed verification blocks further calls and automatic restart.
This coarse benchmark mechanism does not accept future personal-runtime cancellation.

## Completion and approval gates

After approved acquisition, separately approved offline replay produces the private
human-review packet. Obtain independent human scores for all eight intake cases;
empty or schema-valid output is not automatically useful. Keep intake, descriptive
and amendment decisions separate. All scored cases/dimensions need at least 2/4,
weighted mean at least 80%, complete useful finals and zero critical failures.

Task 9.4 remains incomplete until the required per-profile decisions are supported
by the frozen evaluations and independent human assessment. The other 20 scored
cases remain outstanding after an intake-only run. Production qualification,
personal runtime acceptance, exact personal amendment approval and deployment
remain separately gated. No weights were downloaded and no inference ran during
planning or the separately approved sizing operation.

## Current-Mac sizing operation

The approved operation began with read-only inspection of the explicitly targeted
cluster. The sole node was `gtrainer-mac-vm`, Ready without memory/disk/PID pressure.
Only the expected model deployment and k3s system workloads were present; there was
no personal application workload. Ollama 0.35.0 was healthy, its two staged model
digests matched the historical record, and `/api/ps` returned no loaded models.
No chat/generation request was sent. The deployment already had the approved
three-CPU / 6656 MiB limits, so it required no resource edit.

The VM shut down gracefully without force. The local configuration then changed
only its CPU field; its SHA-256 changed from
`290d03b65bbfa0e4ce73338b41ac78f38e0f9747ac035db4bf317abec5048763` to
`80e6ea46c015434d0687ff668e43b8316615e2393d4be8accd5b7ec1875b1621`.
`limactl validate` and the bounded restart succeeded. Acceptance checks passed:

- Lima reports Running with **six virtual CPUs / 8 GiB / 40 GiB**; guest `nproc`
  reports six. The post-start local configuration still has the exact CPU-only hash.
- The original Kubernetes node is Ready and reports six CPUs, with memory, disk
  and PID pressure all false. The model deployment is available and its pod Ready.
- The deployment/image and Bound PVC identities are unchanged. Ollama is still
  **0.35.0**, with the existing **three-CPU / 6656 MiB** limits and both original
  staged model manifest digests. No image/resource edit or model pull was needed.
- Network-policy specifications have the same before/after SHA-256, and the model
  API listener is still **127.0.0.1:11435**, not public. This is configuration
  preservation, not a new runtime-egress enforcement or cancellation test.
- `/api/ps` remains empty. Only metadata endpoints were read; no generation call
  was made. No qualification, scoring or personal runtime acceptance is implied.

The model container's restart count is now **one**, consistent with the approved
VM restart. Do not reset that history or pretend it is zero to satisfy a legacy
runner. A future acquisition harness needs a recorded starting count and refusal
on an unexpected increment during its owned run. Existing historical captures and
runner files remain untouched.

Lima emitted its Intel/macOS warning about Linux 6.12 and newer. The existing guest
actually runs **6.8.0-147-generic** and booted successfully. No macOS, kernel,
hypervisor, disk or memory upgrade was performed. This sizing operation is complete;
there are no pending operational commands.

Three inference threads remain a **per-request setting for a future approved run**,
not a claimed global server setting or a reason to change the pinned model manifest.
The currently enforced Ollama resource controls are its CPU and memory limits.

## Acquisition implementation and first attempt

The [additive acquisition workflow](../../benchmarks/scoped-profiles/ACQUISITION.md)
now implements private run/approval binding, bounded orchestration, actual Kotlin
validation and an owned-pod adapter. All **47 scoped Python tests** pass; the actual
Kotlin callback accepted a fake valid intake draft and rejected `{}` in separate
offline invocations. Frozen integrity still verifies all three profiles / 28 cases.
These checks do not prove actual model quality or live cancellation.

The first approved attempt stopped in read-only metadata preflight because the
HTTP reader lacked `readinto` for a Content-Length response. It retained all eight
cases as unrun. No generation/probe call, scaling, pod creation, model download or
application operation occurred. Original private evidence remains at basename
`scoped-qwen-intake-approved-mzjqf_2b`, without overwriting its inputs or outputs.

After a bounded transport fix and regression, read-only preflight passed: the
pinned model/runtime/template match and a conservative byte-BPE upper bound gives
2088 input tokens plus 1536 reserved output tokens within 4096. This is not a
measured tokenizer count and applies only to intake on this pinned template.
The original shared pod remains Ready at its unchanged restart count of one.
Actual owned isolation and cancellation remain unproven. No automatic rerun took
place; obtain explicit retry confirmation and fresh run/approval records before
another acquisition. Offline replay, independent scores and per-profile decisions
remain separate. Task 9.4 is still unchecked and all admission remains closed.

The user subsequently explicitly approved one fresh retry under the same limits.
That retry passed preflight and sent one non-scored cancellation-probe request in
an owned pod. It stopped with `probe_not_active` before observing a loaded model
and unfinished request; **all eight scored cases remain unrun**. The probe path's
60-second budget currently includes startup/loading and a reserved 15 seconds,
rather than applying 60 seconds only to stop verification. Correcting that distinction
requires fake checks and another explicitly approved run, not a silent extension.

Cleanup verified owned pod/cgroup/CRI termination and restored the shared deployment.
This is coarse stop evidence during loading, not proof of cancellation of observed
active generation. Maximum sampled current memory was **6136.18 MiB** against the
unchanged **6144 MiB** stop threshold; cumulative peak was **6194.89 MiB**. There is
no basis to claim memory headroom or raise limits. Final loopback metadata is healthy,
both staged digests match and no model is loaded. The replacement shared pod is Ready
with restart count zero; its predecessor's count of one remains historical.
Full retry evidence and limitations are in the
[acquisition record](../../benchmarks/scoped-profiles/ACQUISITION.md#explicitly-approved-retry-2026-10-18).
No further inference, download or automatic retry is authorized.

The user then requested the timer correction and continuation until completion or
a question. Startup/loading now uses at most the existing 900-second attempt budget,
bounded by the batch deadline; stop verification receives its separate 60 seconds
only after loaded/unfinished inference is observed. The verified-probe replacement
pod has at most 60 seconds, capped by the remaining startup deadline. All **50 scoped
Python tests** pass, including delayed readiness and startup-expiry checks. Memory,
scored attempt/case/batch limits and frozen inputs are unchanged. No live request
has used the corrected version. Explicit confirmation of this clarified probe
envelope and fresh tooling-bound records are required before another run.

## Corrected baseline outcome and approved RAM resize

The user explicitly approved the corrected probe and separately approved offline
replay. The baseline ran for **61.42 minutes**: the active-request cancellation
probe passed, then **15 complete responses were rejected** by the actual Kotlin
validator. The last of 16 attempts stopped incomplete on the original **6144 MiB**
current-memory threshold. Maximum sampled current memory was **6249.38 MiB**, with
**6255.23 MiB** cumulative peak, below the **6656 MiB** hard ceiling. Stop verification
and restoration passed. Offline replay retained all eight cases and 16 attempts;
finals are seven rejected / one incomplete. Human scores remain blank. No profile
is qualified and task 9.4 remains incomplete. See the
[complete acquisition/replay record](../../benchmarks/scoped-profiles/ACQUISITION.md#corrected-timer-baseline-and-offline-replay-2026-10-18).

The user then explicitly chose **Resize after current run**, approving **10 GiB VM /
8 GiB model hard limit / 7.5 GiB model stop threshold**. Applied only after the baseline
ended: graceful VM stop, memory-only Lima edit and validated restart, followed by a
preconditioned memory-only model deployment update. VM CPUs remain six; model CPUs
and future request threads remain three. Disk, PVC, pinned image, policies, model
digests, loopback binding and the application/Pi are unchanged. The local Lima hash
changed from `80e6ea46c015434d0687ff668e43b8316615e2393d4be8accd5b7ec1875b1621`
to `0e75ea928ad95e5136170950daf332a2d72278769d38250419eca6595d0a9e6d`.

Acceptance verified **Running 6 vCPU / 10 GiB / 40 GiB**, original Ready node and
Bound PVC, Ollama 0.35.0, both unchanged staged digests, healthy loopback metadata
and no loaded model. Host pressure was normal; swap used about 4.1 GiB. This is not
sustained inference headroom evidence. Current acquisition code matches the new
memory envelope; **53 scoped Python tests** cover it, including old-envelope and
undersized-VM refusal. Runtime identity binds six vCPUs / 10 GiB; metadata-only
preflight passes without inference under the resized configuration.
Historical runs keep their original memory settings. No new inference, downloads,
application deployment or personal runtime acceptance is authorized by the resize.

## Separately approved v3 probe after resizing

The later named run `v3-qwen-intake-01` was explicitly approved only after v3 offline
implementation and focused shared-adapter safety fixes. It passed metadata admission,
then stopped during its non-scored probe at **8023.86 MiB sampled current memory**,
above the unchanged **7680-MiB** stop threshold. The active-request probe was not
verified and **all eight scored cases remain unrun**. This is not memory headroom,
usefulness, model qualification or OOM-diagnosis evidence.

Owned UID/cgroup/CRI termination, restoration to one Ready deployment replica and
primary read-only final verification passed. No retry, context/resource increase,
download or real-response replay occurred. The larger envelope therefore has a
retained failed probe, not a successful scored experiment. See the
[v3 outcome and private evidence pointers](../../benchmarks/intake-extraction-v3/README.md#named-live-attempt-v3-qwen-intake-01-2026-10-18).

## Explicitly approved 12-GiB resize for v3 run-02

The user later selected **9 GiB stop; resize and run**, approving only this Mac's
12-GiB VM, 10-GiB model hard limit and 9-GiB stop threshold, necessary restart and
acceptance, then one fresh named `v3-qwen-intake-02` run if admission passes. Six VM
CPUs, three model CPUs/threads, 40-GiB disk, context/output/time limits and all frozen
evidence remain unchanged. No further automatic retry or real-response replay is
approved. The 16-GiB physical host still must pass pressure checks.

The memory-only resize and acceptance completed successfully with original runtime/
storage/image/model/policy identities preserved and no model loaded. Host pressure
is normal, with 3286.75 MiB swap used at acceptance; this is not sustained headroom
proof. All offline checks and historical freezes pass. See the
[run-02 resize checkpoint](../../benchmarks/intake-extraction-v3/README.md#approved-run-02-memory-resize-2026-10-19)
for source snapshots, precise resource bindings, hashes and private operation evidence.

The subsequently executed run-02 hit **9504.02 MiB** sampled current memory above its
**9216-MiB** stop during the probe. No active-request probe pass or scored request
occurred. Owned inference termination was verified, but host pressure warning
blocked restoration; primary read-only diagnostics confirmed the original shared
deployment at **zero replicas**, no remaining owned inference identity and **6304.50
MiB swap used** at that observation. No automatic restart/retry occurred. Recovery
now requires an explicit user decision. See the
[run-02 outcome and recovery fence](../../benchmarks/intake-extraction-v3/README.md#named-live-attempt-v3-qwen-intake-02-2026-10-19).

The user subsequently authorized restart and one fresh attempt at the unchanged
envelope. Restart/restoration acceptance passed, but run-03 again stopped during
its non-scored probe: **9462.32 MiB** current memory, including **4757.46 MiB
anonymous** and **4682.15 MiB file** memory. No scored request occurred. Owned
inference termination passed. Later read-only checks showed one Ready shared replica
with preserved specification/storage/policies, but host pressure warning and
**4773.50 MiB swap used**; cancellation remained unverified. Restart did not solve
the loading peak, and no further execution is approved. See the
[run-03 outcome and memory diagnostics](../../benchmarks/intake-extraction-v3/README.md#named-live-attempt-v3-qwen-intake-03-2026-10-19).

The separately approved no-repack run-04 also aborted during its non-scored probe
at **9759.79 MiB** full current memory. Retained runner argv/logs expose
**`--load-mode none`** and an allocated **4977.62-MiB CPU model buffer** alongside
file cache, despite verified no-repack environment. No scored request occurred.
Owned termination, restoration and primary read-only final checks passed: one Ready
original shared replica, preserved spec/storage/policies/model metadata and normal
host pressure. Observed swap use **7835.50 MiB** is not headroom proof. Any mmap-enabled
request/runtime variant needs separately reviewed bindings and explicit approval;
no further restart or run is authorized. See the
[run-04 loader evidence and outcome](../../benchmarks/intake-extraction-v3/README.md#named-live-attempt-v3-qwen-intake-04-2026-10-19).

Separately approved run-05 added only an exactly bound `use_mmap:true` wire option,
without restarting or changing resources. Actual logs now positively show mmap and
a 4977.62-MiB `CPU_Mapped` model buffer. The host-pressure guard nevertheless
aborted before active-request verification or any scored request. Retained normal-
pressure samples do not establish fully resident-model or abort-time memory usage.
Termination/restoration and independent read-only checks passed: original service
one Ready replica, preserved identities/spec/storage/policies/model metadata and
normal pressure afterward, with **6323 MiB swap used**. A smaller VM/cold restart is
a new host-headroom candidate, not an approved action or proven fix. No additional
runtime change or attempt is authorized. See the
[run-05 mapped-loader and host-pressure outcome](../../benchmarks/intake-extraction-v3/README.md#named-live-attempt-v3-qwen-intake-05-2026-10-19).

The user subsequently approved one graceful cold restart at the prior **10-GiB VM /
8-GiB hard / 7.5-GiB full-current-memory stop**, matching tooling/checks and one fresh
run-06 after gates. That single restart completed; the memory-only YAML and tested
deployment patch preserve all other settings and identities. Normal-pressure,
storage/policy/model/spec/loopback acceptance and offline checks passed. Observed
swap use was **1715.75 MiB**, not sustained-headroom proof. Mmap/no-repack and all
CPU/context/output/time/attempt/pressure guards stay intact. No generation/download
occurred in preparation. See [run-06 preparation](../../benchmarks/intake-extraction-v3/README.md#approved-cold-resize-and-run-06-preparation-2026-10-19).

Run-06 subsequently completed its active-request stop probe and all eight sequential
responses under that envelope. All453 retained host samples report normal pressure;
maximum observed cgroup current5662.11 MiB/cumulative5665.85 MiB remained below the
7680-MiB stop. Verified termination/restoration and primary read-only final checks
passed: one original Ready replica, no loaded model, preserved accepted spec/storage/
policies/model identities. Six responses passed strict local validation; two failed.
This establishes this attempt's bounded acquisition behavior, not usefulness or
production qualification. Separate replay/human assessment gates remain closed;
no additional live operation is approved. See [run-06 outcome](../../benchmarks/intake-extraction-v3/README.md#named-live-attempt-v3-qwen-intake-06-2026-10-19).

Separately approved private replay and subsequent user-supplied independent human
assessment are now complete. All 48 human dimension scores and eight usefulness/
critical-failure assessments are retained privately. Human judgments marked all
eight useful, minimum score 3 and zero critical failures, but i01/i06 remain rejected
by strict local validation. The unchanged outcome-gated rubric gives **71.71875%**,
below 80%, so the experimental decision is **failed**. Completed execution/assessment
tasks do not complete parent 9.4 or production/v1 qualification. No new inference,
replay, runtime mutation or relaxed guard was used for the human review. See the
[final assessment record](../../benchmarks/intake-extraction-v3/README.md#interactive-independent-human-review-and-failed-decision-2026-10-19).
