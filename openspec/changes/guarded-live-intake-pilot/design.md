# Design

## Context

See [proposal.md](proposal.md#why) for motivation and the planning-only approval.
The completed [fixture harness](../../../docs/data/synthetic-intake-pilot.md) already
provides the guarded UI and real disposable-store confirmation boundary. Its backend
is test-classpath-only; its source pairs are handwritten witnesses, not model output.
`ContextSuggestionControls.tsx` supplies `SuggestionLoader` and the shared source-bound
draft parser. Its normal production envelope still requires a real execution identity.

The existing scoped application profile is unqualified and closed. Its routes are
preview/inspection only. The established connected-review development API has a
different queue/correction contract and must not be repurposed to bypass that gate.

The durable benchmark infrastructure already owns requests, deadlines, runtime stop
and cleanup. However, `run_mac_v5.create` requires eight scored launches plus a probe;
`revision_v5` binds all eight requests and nine token counts. A wrapper that filters
those requests would violate its source/authority contract. A separate explicit
profile is therefore required, not a modified historical v5 manifest or grant.

## Goals / Non-Goals

**Goals:** One genuinely new u02 model response, acquired under a finite grant, then
strictly admitted into the guarded disposable UI. The usable offline prerequisite
is the same full journey with unmistakably fake evidence and zero runtime access.

**Non-Goals:** Browser-triggered inference, personal data, normal application/provider
admission, a new scheduler or provider registry, an eight-case quality rerun, model
tuning, fuzzy checking, response repair, new infrastructure, deployment or publication.
No reliable generation or factual-truth claim follows from this known one-case pilot.

## Decisions

### Approval and cleanup amendment of 2026-10-22

The user now explicitly approves implementation and the bounded live test, including
the previously explained service interruption. The user states the deployed Ollama
is used only by this development work and need not be restored. This supersedes the
earlier requirement to return for a further approval conversation after offline checks,
but not the checks or source-bound grant. Freeze sources at task 2.3, then record
this conditional permission in a fresh exact grant before any live observation/send.

For this new profile only, bind `restoreOriginalService: false` by default. Cleanup
must still stop all owned clients/descendants and both owned pod identities and prove
UID absence, empty cgroups and absent running CRI identities. Verify the original
deployment identity/spec remains unchanged except its authorized replica count zero;
leave it paused. Do not manufacture a restoration-success record. Selecting restoration
before admission retains exact-original-spec/readiness/resource checks. All earlier
v4/v5 grants and default runtime behavior stay restore-required. References below to
mandatory restoration and renewed approval are historical design context superseded
by this explicit amendment for the new grant only. The user requests a hands-on UI
demo after implementation/test; use only synthetic/disposable data.

### 1. Acquire once and inspect a sealed result

Use a distinct `guarded-live-intake-pilot-v1` acquisition/inspection profile with an
explicit closed allowlist: frozen v5 **u02** and its mandatory non-scored stop probe.
u02 contains a specified travel interval and uncertain training date/time/recurrence,
so one response can exercise typed extraction and unknown review. Do not switch cases
if generation fails or omits that material.

Reuse the frozen candidate prompt, five demonstrations, packet, schema, model/image
pins and settings. Generation receives no witness, gold values, case-oracle feedback
or rejected-output correction. Predeclare the existing v5.1 case comparison before
execution; record it separately from strict app validation and interaction success.
It is not a new v5 suite score or production validator. A valid empty/incomplete
extraction can fail the integration objective without a replacement generation.

Predeclare usable output for this journey as a strictly accepted draft containing
at least one commitment suggestion with disclosed unknown fields. Otherwise there
is no unknown-resolution journey and no live-mode import. The frozen case comparison
still checks the complete expected travel/training extraction independently. Report
its result and the interaction result on separate axes; a user-edited confirmation
cannot turn a failed extraction comparison into model success.

The UI starts only after the acquisition terminal outcome and successful cleanup
are sealed. It never owns a provider or has run/retry endpoints. This deliberately
tests acquisition-to-app integration, not interactive generation latency. Rejected
alternatives: adding an on-demand app provider, presenting old captures as new work,
or widening the handwritten-fixture envelope to pretend it has execution receipts.

### 2. Reuse durable safety with a new exact profile

Add isolated pilot definition/revision/receipt/import modules under a new benchmark
directory. Reuse shared claims, supervisor and runtime helpers through explicit
profile dispatch and bounded policy parameters only where necessary. Observed seams:
`suite_selection_v4.py`, `campaign_v4.py`, `run_mac_v4.py`, `lifecycle_v4.py` and
`supervisor_v4.py`. Preserve v4/v5 behavior and regressions. No monkey-patching,
hand-edited prepared revision, filtering an eight-case grant or generic plugin API.

Freeze a new source closure including the pilot modules, shared dependencies,
test bridge, importer and UI before requesting live authority. Previously accepted
source hashes are historical evidence, not admission for changed code. Real token
counting and live observations occur only under the later explicit grant; fake tests
must not disguise placeholder counts as real admission.

Consume the single authority/root claim before any possible launch. Consume one
durable request claim per probe and intake before sending. Observer loss never
cancels or resets work; a dead/uncertain worker never replays claimed inference.
No infrastructure recovery allowance, second root or new attempt is included here.

### 3. Fix budgets at every boundary

Proposed maxima, not present execution permission:

- One acquisition root; at most one probe and one u02 generation, sequentially.
- Preflight/token-count admission: **120 seconds**.
- Probe phase, including its startup and required pod replacement: **900 seconds**.
- Intake generation: **900 seconds**.
- Intake case, including generation, validation and associated work: **1800 seconds**;
  strict validation itself at most **600 seconds**.
- Whole runtime grant: **3600 seconds**, with **240 seconds reserved for cleanup**.
  An authoritative owned-stop attempt retains the existing **60-second** bound.
- At most one post-cleanup app session: **1200 seconds from its launch**, separately
  bounded and unable to renew or consume further inference authority. Its approval
  is included explicitly in the later grant; runtime cleanup does not wait for it.

Use the earliest phase, case, grant work-cutoff and owned-pod deadline. Propagate
limits into the durable supervisor, launch admission and pod lifetime; do not inherit
the old four-hour campaign/pod defaults. Wall/native-monotonic checks and local
cleanup fallback retain their existing fail-closed semantics. The bounds are caps,
not a promise that generation or restoration will finish. If budgets cannot fit,
record refusal before sending; never extend them after observing output or failure.

Keep Qwen3:8b/Ollama 0.35.0, exact existing content/image pins, CPU-only three threads,
10-GiB VM, 8-GiB hard limit and 7.5-GiB full `memory.current` stop threshold. Retain
host headroom (at least 1024 MiB), pressure, UID/ownership/cgroup/CRI and observed
mmap/no-repack checks. Retain 4096 context tokens, 1536 output reserve, 256 margin,
12288-byte raw draft and 65536-byte provider-envelope limits. Fresh exact request
counts must fit; old counts are not silently reused. No downloads, resize or fallback.

### 4. Treat the inherited service interruption as an explicit gate

`benchmarks/scoped-profiles/mac_runtime.py:cancellation_probe` scales the existing
Ollama deployment from one replica to zero, creates an owned probe pod, proves its
termination and creates the separate extraction pod. The observed mmap runtime
requires that probe evidence before generation. Final cleanup restores the original
deployment through `lifecycle_v4.CleanupRuntime` and verifies the exact original spec,
one ready shared replica, no loaded model and acceptable resource observations.

The later grant must explicitly authorize that temporary shared-service interruption,
the probe/extraction pod lifecycle and restoration. Until then, all runtime operations
remain forbidden. If the user declines, this design stops at the offline checkpoint;
do not skip the probe or send generation to the shared service. An alternative
no-interruption runtime would require a separately reviewed plan, not silent adaptation.

Stop evidence must bind both owned pod identities and all owned client descendants,
UID absence, empty cgroup and running CRI identity absence. Restore only when existing
identity/spec/ownership/pressure gates permit. Inference expiry does not revoke
cleanup-only authority. Uncertain stopping or restoration blocks import even when a
valid raw response exists. No unrelated restart or overwrite is authorized.

### 5. Import trusted acquisition evidence rather than an arbitrary response file

The durable owner seals actual packet/request/raw bytes, source snapshot, launch
claims, stage results, runtime observations, terminal decision and verified cleanup
into a bounded acquisition receipt. Its identity describes this pilot, not application
publication. The primary records its expected digest independently at supervised
completion; a digest supplied only inside an untrusted artifact is insufficient.

The test-classpath importer accepts an operator-specified private root plus that
independently recorded digest at startup only. Verify the exact registered root,
grant/claim/source/profile/case chain and live terminal mode; reject old grants,
retained captures, fake-worker provenance, traversal, symlinks, hardlinks, incomplete
or oversized artifacts. Use private 0700 directories and 0600 regular single-link
files. Snapshot verified bytes and detect changes across validation; do not follow
a mutable file path on every browser read. Hashes check integrity, not model origin.
If the completion-to-import trust anchor cannot be verified, do not import.

Rerun unchanged `IntakeExtractionV2.validate` with the exact u02 packet and raw bytes,
then `ContextIntakeSuggestionContractV1.decodeDraft`. Do not repair or normalize a
rejected response. Actual lineage, not inequality to witness text, proves acquisition:
a genuine response can legitimately match witness wording.

Fake tests use a separate test-only entry/provenance and cannot satisfy live-mode
admission. A browser fake-evidence journey is labeled as such. No arbitrary receipt
upload endpoint, execution-row seeding or production-publication fabrication exists.

### 6. Reuse the interaction boundary and keep modes distinct

Add a test-classpath live-pilot launcher/module alongside the completed fixture
harness. Share only the narrow private-store seeding/confirmation plumbing needed
for exact u02; never assemble the normal application. The original fixture launcher
must still expose only handwritten-fixture provenance and make zero model calls.

Use a separate explicit UI entry or mode selected by the launcher, the existing
`SuggestionLoader` seam and `parseSuggestionDraft`. The strict live envelope names
actual acquisition/receipt/packet/raw/draft hashes and `live_model_capture` provenance.
It cannot pass as `handwritten_frozen_witness` or the production `executionId`
envelope. Display that generation occurred before import; inspection is read-only.

Use fixed loopback `127.0.0.1:8082`, dedicated pilot authentication/cookie and a fresh
private disposable store. Refuse a busy port; the fixture and live launchers do not
run concurrently. Seed only the admitted u02 source, no authoritative items or
production execution/publication rows. No new dependencies or personal paths.

On retrieval require exact current source identity/revision/content; correction
invalidates live suggestions and discards drafts. Reuse explicit unknown resolution
and acknowledgment, inert staging, visible selected confirmation, atomic/replay and
stale-source/item checks. A network-uncertain confirmation requires reload, never
automatic retry. User edits are distinct from generated output, not response repair.

Consume a durable, exclusive app-session launch claim bound to the acquisition receipt
before opening the listener; another launcher must not reset the 1200-second budget.
Keep this claim in the pilot authority ledger, outside the disposable store and the
sealed acquisition payload. Bound the app session and stop its exact server identity
and descendants, not merely the Gradle client: the completed fixture pilot
demonstrated separate JavaExec process
groups. Remove only its generated store after shutdown; preserve private acquisition
evidence. No app session launches or extends inference.

## Risks / Trade-offs

- **Integration requires a new profile before usable behavior.** Existing v5 enforces
  eight requests and a four-hour envelope. The necessary stopping point is one fake
  durable capture imported through the complete existing UI journey, not a new framework.
- **Service pause surprises the user.** Make it explicit in the proposal and exact
  runtime grant. Without approval, remain blocked after offline verification.
- **Self-consistent forged receipts overstate provenance.** Verify registered durable
  ownership and the independent completion digest, not hash fields alone; fail closed.
- **Late output or restoration drift is mistaken for success.** Fence publication,
  preserve terminal/cleanup evidence and refuse import until all stop/restoration proofs pass.
- **Known one-case success is mistaken for reliability.** Preserve the actual raw,
  frozen comparison and edited confirmation separately. No quality score from UI replay.
- **Safety code keeps growing.** No new scheduler, recovery machinery or reusable
  provider abstraction. Stop and explain any material blocker before expanding scope.

## Migration Plan

There is no application rollout or data migration. After separate implementation
approval, deliver the fake acquisition-to-confirmation journey and negative tests,
run legacy/fixture/strict regressions, freeze sources and stop for the exact runtime
grant. Only after that grant may one durable live acquisition run. Verified cleanup
precedes import; one bounded browser journey and actual-outcome report complete it.

Rollback is disabling the opt-in test launcher/profile, stopping only owned work
under its cleanup authority and removing its generated app store. Do not remove
failure/ownership evidence or modify historical roots and manifests. No commit,
push, publication, deployment, spec sync or archive is part of this change.

Planning uses architect `ses_e9fce6368ffeQ8KHNwLCcuQWYx`, **1/2 children** and the sole
independent architecture review for this slice. Primary inspected its runtime and
authority recommendations against current code. One child remains for bounded work
after implementation approval; commands do not reset that budget.
