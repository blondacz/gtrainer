# Tasks

Planning is approved. Checkboxes and acceptance records track implementation; each
group is a bounded delivery checkpoint, not permission to install services or send data.
Use [design decisions](design.md#decisions) and the named spec requirements as the
context index. Keep detailed checks/outcomes beside the completed task, not in a
growing session summary. Offline tests use isolated invented data and fake transport;
they are not real-model acceptance evidence.

## 1. Preserve the candidate and settle the admission boundary

- [x] 1.1 Preserve the pending frontend fixes and four V2 status edits; verify frontend tests, typecheck/build, strict OpenSpec validation and whitespace checks against the current tree. Record failures without resetting unrelated edits or changing frozen experiment hashes.
- [x] 1.2 Produce the old-change disposition map from design decision 6, including exact remaining task IDs and successor/deferred obligations. Verify every unchecked task remains traceable and no historical outcome or permission is relabeled as passed or reusable.
- [x] 1.3 Reconcile the connected/scoped qualification requirement with the explicit experimental owner-UAT exception through the appropriate approved spec-update/sync workflow. Verify strict validation and a requirement-level diff showing ordinary qualification and synthetic-only development restrictions remain intact; no archive or runtime activation is implied.

## 2. Deliver the descriptive normal-app journey offline

- [x] 2.1 Add the distinct owner-UAT admission state and installed-policy binding, preserving closed defaults and unchanged PERSONAL/DEVELOPMENT limits. Verify tests for absent/revoked/drifted policy, unsupported profiles, hosted refusal, restart re-arming and honest unqualified status; document the owner opt-in and proposed job limits.
- [x] 2.2 Wire durable manual execution, status, cancellation and result inspection into normal authenticated routes using the existing personal claim/executor/repository path and a fake owned provider. Verify authentication/Origin/CSRF, duplicate submission, one-job occupancy, cancellation/deletion/revocation fencing, and no sends from GET/navigation/reload.
- [x] 2.3 Complete the descriptive UI from exact factual snapshot through validated source-linked result, saved-result navigation and attributed feedback. Verify component/API integration tests cover successful fake output, stale evidence, rejection, unavailable model and feedback without retry; document this journey and its descriptive-only limits.
- [x] 2.4 Exercise the complete descriptive journey in the normal application with an isolated synthetic store and fake transport, including browser reload and service restart. Verify retained results, no duplicate send, no pilot/test routes or secret/source credentials, and usable factual views during failure; record that this is offline integration, not model acceptance.

## 3. Implement the accepted Mac-owned execution path

- [ ] 3.1 Implement the production local provider and exact cached model/runtime identity checks without downloads, shared-runtime adoption or changing the paused original deployment. Verify fake-control tests cover model/image drift, lossless compact-input parity, effective prompt/schema/token accounting, the initial 4096/1536/256 budget and refusal before any oversized send. A separately tested, explicitly accepted larger context requires a new binding, never automatic fallback or retry. Document the adapter's versioned binding and practical review-scope limits without making token management an owner workflow.
- [x] 3.2 Connect a separately supervised owner journal and sleep-inclusive monotonic job deadline/boot identity to runtime startup, generation, publication and cleanup. Verify fake-clock/process tests for the proposed 1200-second total, 900-second generation, startup/validation bounds and 60-second cleanup reserve, including browser loss, app death, supervisor restart, boot change, delayed startup and sleep across expiry. Verify no renewed budget/retry or publication before recovery stop proof; document that suspended-host cleanup cannot be guaranteed at the deadline.
- [ ] 3.3 Implement the owned runtime health/stop observer using the accepted CPU/memory/mmap/pressure and UID/cgroup/CRI boundaries. Verify mocked control-plane tests for physical descendant stop proof, identity mismatch, uncertain-stop occupancy, restart fencing and late publication; document explicit recovery actions without running a real probe yet.
- [ ] 3.4 Expose content-free operational status for service/admission/jobs and fixed failure reasons, separate from explicit runtime mutation. Verify read-only inspection cannot start/reconcile/import/generate, logs exclude secrets and payloads, and factual views survive runtime loss; document on-demand monitoring and private evidence retention/deletion rules.

## 4. Deliver source-note extraction and confirmation

- [ ] 4.1 Implement a versioned main-source intake prompt/schema and mechanical wire adapter into the unchanged strict draft validator, without modifying frozen benchmark helpers or their hashes. Verify parity/negative tests for exact UTF-16 spans, nullable payloads, unknown dates/times, whole-output rejection, context overflow and no inferred repair.
- [ ] 4.2 Connect accepted intake execution to the saved note's exact revision and existing suggestion staging/confirmation flow. Verify API tests distinguish source-note revision/deletion, which invalidates old work, from staged suggestion corrections, which preserve immutable model output and remain confirmable owner edits against the unchanged source. Verify replay safety and no authoritative writes before confirmation.
- [ ] 4.3 Complete the ordinary note UI: request, running/cancel/status, accepted/rejected result, owner edits, explicit confirmation and saved-result reload. Verify browser/component integration with fake output creates only the explicitly confirmed context/commitments; document unknown-field handling and distinguish this check from a real-model extraction.

## 5. Deliver permitted authored-plan amendments

- [ ] 5.1 Implement deterministic permitted candidates for an existing selected authored plan and explicit amendment permissions, then open amendment preparation only for that source. Verify tests reject absent plans, disabled permissions, unsupported candidates and stale constraints; preserve manual authoring and no AI initial-plan generation.
- [ ] 5.2 Bind the amendment model request/selection contract and existing exact-diff approval path to those candidates. Verify strict rejection of invented sessions/explanations, source/plan/permission drift, cancellation and repeated approval; confirm only an exact current approved local diff applies atomically and no upstream write occurs.
- [ ] 5.3 Complete the ordinary plan UI from explicit request to inspected proposal and exact approval, with useful no-candidate and unavailable states. Verify fake-output browser/component tests, reload and plan-conflict behavior; document the permissions and authored-plan prerequisite without inventing the owner's plan.

## 6. Package persistent private Mac use and recovery

- [ ] 6.1 Build a versioned normal-app bundle and explicit non-root launchd installation/uninstallation tooling outside the checkout, with loopback authentication, private persistent storage and secret-file references. Verify staged installation tests preserve records, refuse occupied ports/incompatible stores, expose no pilot routes, have no app deadline and uninstall no owner data; document Mac sleep/login limitations. Do not bootstrap the real service yet.
- [ ] 6.2 Add explicit local-store encrypted backup support and isolated restore validation using the current schema manifest, without repointing or overwriting the existing Pi backup job. Verify WAL-consistent snapshots, private files, encryption failure, wrong/tampered input, schema refusal, exclusive restore and version rollback against an isolated store; document key custody and the unverified separate-device recovery limitation.
- [ ] 6.3 Run broader normal-app integration checks across all three offline journeys, persistent restart, shared occupancy and failure isolation; run backend/frontend tests, typecheck/build, strict OpenSpec validation and whitespace checks. Verify the packaged candidate matches tested source and has no personal/test configuration mixed into its artifact; retain reproducible provenance and document any blocker.

## 7. Activate and verify real owner UAT only after approval

- [ ] 7.1 Present the exact candidate, persistent paths, source date range, model/runtime/profile policy, resource/time limits, backup plan and bounded readiness sends for explicit operational approval. Verify the recorded approval separately covers installation, first read-only import and real inference; never reuse the spent pilot allowance, and never ask for credentials in chat.
- [ ] 7.2 After installation authority, privately verify existing configuration, install/start the Mac service and create/restore-check a fresh encrypted recovery point. Verify authenticated loopback access, persistent schema compatibility, version/status and credential redaction without displaying secret contents or mutating the Pi; stop on incompatibility rather than migrate/reset automatically.
- [ ] 7.3 After import authority, run one owner-selected bounded Intervals read without model execution. Verify recognizable activity/wellness dates and supported fields against owner feedback/Garmin, source coverage and missing/stale categories; record whether credentials were valid without logging tokens or private rows in diagnostics.
- [ ] 7.4 After model authority, run at most one approved synthetic stop probe and one request per named profile through the normal app, with no correction/retry/replacement acceptance run. Verify actual output, strict acceptance, owner-confirmed intake and an owner-entered permitted plan amendment, reload and independent stop proof; mark any absent prerequisite/refusal/failure blocked, never substitute fake success or broaden the run.
- [ ] 7.5 Hand over the persistent application with its accepted manual owner-UAT policy, saved results, documented start/stop/backup/status procedures and owner feedback. Verify availability beyond the former demo deadline, unchanged records after sign-in/restart and explicit model re-arming; distinguish structural acceptance from independent usefulness qualification and continuous monitoring.

## 8. Close completed and superseded work honestly

- [ ] 8.1 Reconcile/sync/archive completed V3/V4/V5/pilot changes in dependency order through the appropriate approved workflow. Verify strict validation, retained evidence links and unchanged original outcomes/hashes/allowance; no archive operation counts as model or deployment acceptance.
- [ ] 8.2 Record explicit disposition/approval for administrative closure of incomplete predecessors, or leave them open when transfer is not accepted. Verify V2 retains 5/11, independent qualification remains outstanding, and Pi release obligations have a named deferred destination; do not check unfinished boxes to force archival.
- [ ] 8.3 Reconcile the final main specs, release evidence and remaining obligations with the delivered app. Verify strict OpenSpec validation and a source/spec diff, preserve unrelated edits, and record the next iterative improvement from actual owner feedback rather than expanding the UAT framework. Commits, pushes and publication remain separately authorized primary-session actions.

## Acceptance records

### Task 1.1 — preserved candidate checks, 2026-10-23

- Current normal frontend: `npm test` **386/386 passed, 29 files**;
  `npm run build` passed, including `tsc --noEmit`. Existing bundle-size warning
  remains informational. No dependencies were installed or downloaded.
- Strict OpenSpec validation **14/14 passed**; `git diff --check` passed.
  Pending UI/V2 edits are retained. No frozen experiment file/hash was changed,
  no backend/runtime operation occurred and no credential content was read.
- Detailed generated logs remain private in the approved temporary root
  `integrated-uat-baseline-NG0qXa/frontend-tests.log` and `frontend-build.log`.
  These checks do not establish installation or real-model readiness.

### Task 1.2 — predecessor disposition map, 2026-10-23

This map records all **14 unchecked predecessor tasks**. The linked source files
remain authoritative and unchanged. Administrative closeout is task 8.2, not evidence
that any of these tasks passed. The new owner-UAT policy in task 1.3 is not independent
qualification or renewed historical execution authority.

| Source task | Remaining obligation | Disposition / destination |
| --- | --- | --- |
| [V2 3.1](../schema-guided-intake-experiment-v2/tasks.md#3-bounded-acquisition-preparation-with-fake-checks-only) | Frozen schema-bearing Mac adapter seam | Retain deferred in V2: incompatible frozen provider/schema constraints. New UAT adapter work in 3.1/4.1 is not V2 completion. |
| V2 3.2 | Exact run/approval and context admission | Retain deferred in V2. UAT policy/context checks in 2.1/3.1 have distinct bindings and authority. |
| V2 3.3 | Eight-case bounded acquisition orchestration | Retain deferred in V2; no renewed batch is scheduled by this change. |
| V2 3.4 | Fake acquisition/readiness checks | Retain deferred in V2; UAT checks do not satisfy the frozen V2 batch contract. |
| [V2 4.1](../schema-guided-intake-experiment-v2/tasks.md#4-separately-approved-experiment-and-decision) | Separately approved probe and eight-case experiment | Retain deferred/unrun in V2; no inference authority transfers. |
| V2 4.2 | Offline replay, independent human scores and experimental decision | Retain deferred in V2; no fabricated assessment or claimed usefulness. |
| [Connected 6.2](../connected-insights-context/tasks.md#6-freeze-and-evaluate-usefulness-before-qualification) | Complete approved synthetic candidate comparison | Retain open upstream; partial/reference captures and uncontrolled-setting limits remain explicit. Task 7.4 personal owner-UAT acceptance does not replace it. |
| Connected 6.3 | Independent human review and qualification decision | Retain open upstream; no candidate is qualified by this change. |
| [Scoped 9.4](../scoped-reviews-and-adaptive-planning/tasks.md#9-new-realistic-evaluation-and-qualification-gates) | Approved named-profile evaluation, independent scores and qualification decision | Retain open upstream. New experimental manual permission is separate from qualified/automatic admission. |
| [Foundation 1.1](../foundation-release-verification/tasks.md#1-verify-foundation-release-path-transferred-from-foundation-task-61) | Protected main/ARM64 image and provenance verification | Retain deferred in foundation for Pi release. Mac candidate provenance in 6.3 does not verify the protected ARM64 path. |
| Foundation 1.2 | Fresh compatible backup/restore and approved promotion/deployment | Retain open for Pi. Mac backup/install counterparts are 6.2/7.1/7.2, without silently repointing Pi backups or approving promotion. |
| Foundation 1.3 | Approved Pi Flux/image/private-access/storage verification | Retain deferred in foundation; Mac launchd installation is not Pi rollout. |
| [Foundation 2.1](../foundation-release-verification/tasks.md#2-verify-application-flow) | Imported factual history/trends and manually entered next event | Retain open for the original release. Mac integration/import counterparts are 6.3/7.3 and require their own evidence/approval. |
| Foundation 2.2 | Private release flow with no hosted transfer or inference | Retain open for the original release. Verify the Mac import phase without inference in 7.3; the later local-model phase is separately authorized and cannot satisfy a no-inference claim for the whole release. |

Completed historical change counts independently checked from their actual task
checkboxes: **V3 10/10, V4 20/20, V5 12/12, guarded pilot 8/8**. These are task counts,
not model case scores. In particular, retain original V5 prospective 7/8 versus its
separate post-hoc 8/8. Task 8.1 owns evidence reconciliation and archival; no archive,
sync, old-hash refresh, replay, replacement run or new pilot occurred at this checkpoint.
V2 remains **5/11**; connected **20/22**; scoped **44/45**; foundation **0/5**.

### Task 1.3 — explicit policy reconciliation, 2026-10-23

The owner separately confirmed the exact clarification and scoped predecessor
spec/design/proposal/task cross-references through **Confirm and continue**. Independent
human assessment/runtime approval remain required for ordinary admission. Manual
experimental owner UAT keeps qualification unqualified, automatic/ordinary gates
closed, and runtime/binding/validation/resource controls mandatory.

Synced only the complete `personalized-training-guidance/spec.md` entries reported by
the connected, scoped and UAT change inventories, in that dependency order. No other
capability spec or historical evaluation was changed. Checked all 4 connected,
5 scoped and 4 UAT requirement blocks and every scenario against main; only the
approved qualification wording changes the parent's admission prerequisite. The
legacy manual connected-review gate and independent automatic gate remain intact.
`connected-review-development` is unchanged and still prohibits personal storage/data.

Strict spec validation **5/5** and all-project validation **14/14** passed; whitespace
passed. The initial additional block-comparison script mistakenly included a delta
operation header as requirement content; excluding those headers made all 13 complete
blocks match. This was a verifier issue, not a discarded scenario or validator bypass.
No task in the predecessors was completed or archived, no runtime gate was activated,
and no installation, import or inference was performed.

### Task 2.1 — installed policy and honest admission status, 2026-10-23

Implemented the private bounded strict policy reader, installation/profile/model/
runtime/resource binding, process-local explicit re-arming and policy-only refusal
predicate in `OwnerUatAdmission.kt`. Normal authenticated profile/execution inspection
can report that separate policy state; production defaults still have no configured
owner policy or runtime. File acceptance and arming never claim runtime acceptance,
qualification or `executionAllowed=true`. Existing PERSONAL/DEVELOPMENT limits and
synthetic-only execution remain unchanged. See
[owner policy documentation](../../../docs/infrastructure/local-owner-uat.md).

Latest offline focused checks: **45 tests passed** across new admission, personal
runtime authority, profile executor, evidence API and execution storage. No model,
source API, download or installed service was used. One initial new test incorrectly
expected inert execution state PREPARED; fixed to verify the actual pre-existing
state is unchanged (preparation uses IN_FLIGHT, not an inference claim).

One independent review identified two real gaps, independently checked and fixed:
observed policy invalidation now clears arming so restoration cannot re-arm; stored
execution inspection reports its own incompatible provider/prompt binding instead
of implying the installed local policy accepts it. Added revoke/delete/drift/restore
and mismatched-selection regressions. Inspection can clear only the process-local
fail-closed latch; it cannot activate or reconcile a runtime or stored claim.

An earlier full backend suite passed before those two fixes; final focused results
include both fixes. Logs are private under `integrated-uat-baseline-NG0qXa`, notably
`owner-uat-reviewed-tests.log`; broader integration must be rerun at the next code
checkpoint. Slice budget: routine obligation map plus one independent code review,
**2/2 children used**, sole review consumed; no further child/retry for this slice.

### Task 2.2 — normal-app manual backend journey, 2026-10-23

The optional normal-app backend now supports explicit policy re-arming/disarming,
exact-preview asynchronous request, durable status, cancellation and existing whole
validated-result inspection. A request receives 202 only after a persisted ownership
reservation. Duplicate claimed IDs return their saved state even after stop/restart,
never a replacement send. Uncertain stopping retains domain occupancy. Browser loss
does not parent/cancel the application-owned worker. Execution deletion fences and
cancels its owned job before removing the private result.

Added a separate one-attempt local-UAT executor policy, leaving existing policies and
legacy validation unchanged. The shared engine receives 1140 seconds work within a
1200-second reservation, preserving 60 seconds for cleanup. Policy permission is not
passed through the qualification seam. Exact factory runtime identity is checked in
addition to local provider/model selection; policy is rechecked at sends/publication,
and publication uses the policy-monitor-before-store lock order. Real sleep-inclusive
supervision, accepted resource observations and deployment remain tasks 3/6/7; no real
runtime is configured by default. See the [route and policy documentation](../../../docs/infrastructure/local-owner-uat.md).

Latest full offline backend suite: **1018/1018 passed, 152 classes, zero skipped**.
This includes fake normal-app API and service tests for durable one-shot execution,
read-only inspection, auth/Origin/CSRF/strict body boundaries, exact packet, overlap,
invalid output with no correction, wrong runtime identity, late-result cancellation/
disarm, source correction, execution deletion, stop uncertainty and restart refusal.
Fake empty intake output is only a lifecycle witness, not useful model acceptance or
confirmation of authoritative context. No model/provider/source API was called.

An initial focused run exposed real concurrent status-capture conflicts and repeated
close against an already closed store. Fixed reservation acknowledgement to use its
committed receipt instead of racing a fresh evidence read; status reports a transient
UNVERIFIED/inspection_conflict without sending or renewing work; close is idempotent.
Corrected focused run passed and final full suite includes these fixes. Detailed logs:
private `integrated-uat-baseline-NG0qXa/owner-uat-jobs-tests.log`,
`owner-uat-jobs-tests-corrected.log`, `backend-owner-jobs-suite.log`.

This completes the backend manual-execution slice. The normal UI/result navigation/
feedback slice (2.3) and real runtime acceptance are not represented as complete.

### Task 2.3 — normal descriptive UI, history and feedback, 2026-10-24

The normal factual form now opens a collapsed-by-default local review workspace.
Opening/history/source reads never prepare or generate; explicit actions prepare the
exact model preview, re-arm manual permission and request/cancel one guarded job.
Saved rows reopen their result and source snapshot without ID copying. Complete
source-bound drafts show uncertainty and exact cited records; stale/rejected/removed/
unverified prose is withheld. Lost responses do not cause retries. Feedback is an
attributed user report linked to the immutable result, not verified evidence or a
rewrite/generation action. See [dashboard journey](../../../docs/dashboard.md#experimental-local-descriptive-reviews)
and [operator boundaries](../../../docs/infrastructure/local-owner-uat.md).

Added bounded authenticated history and exact-output-hash descriptive feedback APIs.
Feedback/delete races are serialized without orphans. Historical/retired/corrected
feedback revisions and downstream snapshots enter the existing deletion closure;
new feedback changes preview fingerprints. Direct result deletion preflights before
cancellation and has a transactional dependency guard. Existing shared-note redaction,
confirmation, rollback and idempotent deletion semantics remain intact.

The UI uses separate `manualRequestAdmission` metadata for a guarded submission,
never redefines `executionAllowed` as runtime acceptance or fabricates qualification.
No GET probes a runtime. The accepted prompt set now pins a bounded canonical list
of the four code-owned descriptive mode/intent variants. The renderer retains original
prompt bytes; an old singular policy shape or changed set is rejected, never widened
automatically. No real owner policy had been installed or migrated.

Checks after the final code changes: **1028/1028 backend tests, 153 classes, zero
skipped; 397/397 frontend tests, 31 files; typecheck/normal build passed**. Strict
OpenSpec **14/14** and tracked/new-file whitespace passed. Included fake normal-API
journeys for all four mode/intent combinations, restart persistence, no GET sends,
lost-response UI recovery, cancellation, exact feedback, pagination, stale retained
output, concurrent feedback/delete and rollback/unrelated-context preservation.
These are synthetic/fake structural checks, not real-model or browser acceptance.

Observed and retained limitation: default-wellness period comparison exceeded the
existing 32-KiB mandatory packet budget even for a small fixture. Four-mode positive
fake tests explicitly choose wellness **off** and minimal invented activity records;
the default overflowing case remains a negative test with zero provider calls.
Nothing was silently truncated, no byte/context limit was increased, and real
4096-token context fit is still unproven. Earlier test failures also exposed a
repeated-delete compatibility regression and an incorrect fixture assumption that
arming resets between jobs; both were corrected without weakening guards.

Detailed private logs under `integrated-uat-baseline-NG0qXa` include
`descriptive-ui-frontend-complete.log`, `descriptive-ui-backend-complete.log`,
`local-review-build-checked.log` and earlier diagnostic/corrected runs. The interrupted
`descriptive-ui-safety-final.log` is not completion evidence. Architecture slice used
two bounded turns of `ses_e96648711ffeDZkSSVcnvov097`; primary inspected its advice,
all changed responsibilities and checks. Budget **2/2 used**; no additional child or
independent review. Task 2.4 normal browser/restart verification remains pending.

### Task 2.4 — normal browser, reload and service restart, 2026-10-24

Added the opt-in test-classpath `LocalDescriptiveUiCheck` launcher. It stages the
current normal frontend, uses normal authentication/routes and the existing invented
fixture with an in-process fake provider. It never loads environment credentials,
contacts a source/model endpoint, starts a runtime process or exposes pilot APIs.
Fixture startup seeds only a fresh synthetic store; restart preserves records/policy
and starts a new disarmed policy gate. Compilation/focused fixture-policy tests passed.
The ordinary test suite does not launch this opt-in server.
The built main backend jar was inspected and excludes the new fake launcher, fixture
and test classes; staged browser assets matched the current normal frontend build.

Primary browser verified normal sign-in, activity selection, explicit factual snapshot,
local preview, separate re-arm and exactly one guarded fake request. Preparation,
navigation and re-arming sent no generation. A read-only refresh showed a whole
validated PUBLISHED result, one attempt and fake STOP_CONFIRMED state. Its cited
session opened the selected invented source record, units and receive revision.

Reload reopened the saved dated result and its source without IDs or another request.
The first Java owner then exited and port 8080 was independently absent. Restart used
the same database, required a new sign-in, showed policy not armed, and reopened the
same saved result with Request disabled. No model request or fake generation occurred
in that phase. Explicit feedback returned201 and appeared in normal Context as
**Source: Review feedback (athlete)**, without rewriting the result or generating again.
Factual options remained available; normal pilot-prefix requests returned unhandled501,
not a pilot handler. Source/model credentials were absent and both source-call counts0.

Evidence: approved private root `synthetic-local-ui-RWl4Xd`, including first/restart
owner/terminal/browser-check JSON and `cleanup-verification.json`. First phase had
**one fake provider call**, restart **zero**; both had **zero source calls and zero
unresolved claims before stop**. Before cleanup the retained store had two invented
activities, one attributed feedback context, one result and one stopped claim. The
PUBLISHED output digest was `811ef07306eb464371e7f7dfdd1afa28dd8a73506fcb52fa55966c628f77d26a`.
The served normal frontend index matched the built index byte-for-byte.

Both exact Java PIDs (72866,73266) exited, no8080 listener remained, then only the
generated `data/` directory was removed after owner/marker/path/type checks. Evidence
was retained. A stopped WAL-mode SQLite read first refused missing sidecars; immutable
read-only inspection after verified stop/WAL absence succeeded without data changes.

Limitations retained: this is **fake structural integration, not real-model usefulness,
resource acceptance or deployment**. The fixture deliberately disables ordinary queue
services (expected queue501); restart invalidated the old session (expected401).
One navigation preceded listener startup (502) and was repeated only after a new owner
existed. Some visual automation clicks/finds failed; later actions used normal DOM
handlers, with actual referenced controls used where available. One read-only script
syntax error was corrected. No exhaustive physical keyboard/mouse, device, contrast
or accessibility certification is claimed. No live pilot authority was reused.

### Task 3.1 prerequisite — bounded vocabulary-only feasibility, 2026-10-24

**Task 3.1 remains unchecked.** The owner explicitly approved one check of up to
four invented descriptive prompts, capped at 300 seconds, using verified cached
assets in the existing running VM. No inference, personal input, download, runtime
startup, deployment change or old pilot authority was included. That check completed;
its authority is now spent and does not permit a replacement/retry or real inference.

Added test-classpath `OwnerUatContextFeasibilityExport` and opt-in Gradle export task.
They use the unchanged normal immutable preparation path and compiled instructions
for all four mode/intent variants, minimal invented activity records and explicit
wellness **off**. They assert zero source, provider and runtime-observation calls,
then close and remove their generated synthetic database. No personal store is opened.

The separate `scripts/owner_uat_context_count.py` does not import, modify or repurpose
the frozen counter/campaign. It verifies full model and tokenizer-library content
hashes before loading the audited native ABI, selects vocabulary-only/no-allocation
flags, tokenizes the exact historically audited non-thinking rendering, and frees
the vocabulary. The output is explicitly feasibility-only, with production admission
closed and no implemented production schema binding. A fitting fixture is not a
certificate for future dynamic requests or a completed native-schema provider.

Current cache inspection verified the model manifest
`500a1f067a9f782620b40bee6f7b0c89e17ae61f686b92c24933e4ca4b2b8b41`,
template `ae370d884f108d16e7cc8fd5259ebc5773a0afa6e078b11f4ed7e39a27e0dfc4`,
tokenizer library `8dc7056ee6548c619bded63829696b439478c357df2c4f6c41594e4f86d56324`
and complete model blob
`a3de86cd1c132c822487ededd47a324c50491393e6565cd14bafa40d0b8e686f`.
Full blob hashing reads disk bytes but does not instantiate tensor weights. The
original running-container rootfs was absent, as expected after its pause; the same
pinned library was available in existing cached image snapshot 35. No container,
server, mount, extraction, download or deployment restart was used. A supplementary
read-only `readelf` inspection was unavailable; no package was installed. The cached
manifest had no additional system layer. Native diagnostics confirmed `vocab_only=1`
and `vocab only - skipping tensors`; no inference context or generation API was called.

The exact current packets produced these measured counts. Input allowance is
**2304 = 4096 − 1536 − 256** tokens:

| Invented case | Rendered UTF-8 bytes | Input tokens | Input headroom | Fits this rendering |
| --- | ---: | ---: | ---: | --- |
| activities / review | 8964 | 2230 | 74 | Yes, this minimal fixture only |
| activities / compare | 12843 | 3945 | −1641 | No |
| period / review | 10079 | 2427 | −123 | No |
| period / compare | 21576 | 5927 | −3623 | No |

All four packets passed the separate 32-KiB byte guard. Thus increasing only that
guard cannot fix measured token overflow. The earlier default-wellness comparison
byte refusal remains valid and separate. Do not silently truncate evidence, increase
context/resources or call the three overflowing modes supported real-model behavior.
Before substantial provider work, choose an explicitly approved model-input
representation that preserves required evidence/provenance and strict validation,
or retain the blocker. The existing representation cannot support this minimal
four-variant journey under the accepted budget. No design exception was applied.

One native child counted all four prompts in **49.553 seconds**, exit 0. Its remote
wrapper limited address space to 1 GiB, CPU to 270 seconds, child work to 280 seconds
and outer execution to less than 300 seconds. The child was reaped, its process group
was absent, and a separate read-only `/proc` check verified PID **2216446** absent.
The primary independently recomputed helper/input/rendering hashes and every budget
calculation. Private evidence is in `synthetic-owner-uat-context-mCZyCk`, including
`input.json`, single-use launch/terminal records, native output/diagnostics and the
exact temporary supervisor script. No generated database remains.

Offline checks: **11/11 focused backend tests** (new exporter plus existing descriptive
journey) and **6/6 mocked-native Python tests** passed. These are not new full-suite
results. Primary corrected a redundant CLI peek and added malformed-input/cleanup
coverage before the one authorized native execution. Architecture child
`ses_e90e5c0d3ffepO8En2TVuM7SRA` and bounded helper child
`ses_e907e2306ffeqWemz00gf85nrd` consume **2/2 children** for this slice; no independent
review remains. Primary inspected all changes and checks. No new production adapter,
resource acceptance, installed policy, personal import or real-model result is claimed.

### Task 3.1 checkpoint — lossless compact input, 2026-10-24

The owner approved a versioned lossless model-input format and then clarified that
token management is not an owner workflow for a free local model. The design now
treats 4096 as an initial conservative execution setting, not an inherent model limit
or permanent product constraint. A larger context still requires separate resource/
context testing and explicit installed-policy acceptance. No larger setting, automatic
fallback, replacement count or additional inference was activated by that clarification.

Implemented `OwnerUatDescriptiveWireV1`: repeated object field layouts and repeated
string values use explicit tables and typed positional nodes. The decoder preserves
canonical JSON order/bytes, numeric lexemes, Unicode/control characters, unknown/null/
empty distinctions, identities, source dates, receive revisions, units and attribution.
Prepared input binds the original packet, codec version and complete effective prompt;
inspection refuses drift. Existing compiled safety instructions remain intact, with
explicit decoding instructions appended. Output still passes the unchanged original
strict validator and requires complete original source references, not table indexes.
Duplicate keys, malformed nodes, incorrect bindings and excessive expansion are refused.

The opt-in synthetic exporter can produce compact inputs without any provider/runtime
request. A separately selected fake transport path round-trips this representation
through the guarded normal execution service and publishes nonempty original-contract
results for all four mode/intent variants. Original immutable stored prompts are not
rewritten. The default production path remains closed; this codec is not a finished
provider, installed-policy migration, context proof or model-comprehension acceptance.

Focused checks passed: **19 tests** across wire parity/negative tests, exporter tests
and existing descriptive journeys. Final full backend checks passed **1037/1037 tests,
155 classes, zero skipped/failures/errors**, including the final canonical-input
size/depth/duplicate-key prechecks. Strict OpenSpec **14/14** and tracked/new-file
whitespace passed. Frontend source was unchanged in this slice; its previously
reported checks were not rerun. Final full-check log:
`integrated-uat-baseline-NG0qXa/compact-descriptive-backend-final.log`.

Independent offline byte inspection of exported invented inputs found reductions of
**3.3%, 19.2%, 14.1% and 42.7%** in the complete serialized prompt, including appended
decoding instructions, for activity review/comparison and period review/comparison
respectively. These percentages are **bytes, not tokens or latency**. Exact exported
inputs remain in private `synthetic-owner-uat-context-compact-9QroxH`; its generated
database was closed and removed. No native tokenizer was called for these inputs.
The original counting grant remains spent. The separate default-wellness comparison
byte-limit refusal is unchanged. Task 3.1 and overall progress remain **7/28**.

### Task 3.1 checkpoint — owned Ollama transport and native schema, 2026-10-25

Implemented main-source `OwnerUatOllamaContractV1.kt` and
`OwnerUatOllamaProvider.kt`. These supply a pinned runtime binding, descriptive native
response schema, exact compact request/rendering assembly, owned observation and
request-specific context-proof validation, and a per-claim provider factory over the
existing personal admission path. No new queue, scheduler or browser-selected endpoint
was introduced. Ordinary/development paths and frozen benchmark helpers are unchanged.

The factory checks installed runtime/model/resource/schema/prompt pins before acquiring
an owned token or constructing a client. It remains descriptive-only until the other
profile contracts are implemented. Current policy refuses larger-context factories;
constructing an offline candidate binding is not acceptance or an automatic fallback.
The provider binds the canonical source consent digest to the durable claim before
counting or HTTP. Missing/drifted/replayed/over-budget context evidence and changed
owned image/model/blob/template/tokenizer/resource/incarnation observations send no
HTTP. Trusted physical observations are required; HTTP metadata never creates them.

Generation sends one exact native-schema request with CPU-only three-thread options,
1536 output tokens, seed42, temperature0, non-thinking/non-streaming and no keep-alive.
The source snapshot and original output validator are unchanged. Version/tag/digest
and ownership are rechecked before/after generation. Returned input count must match
the admitted rendering; incomplete/length-truncated/thinking/tool/invalid-envelope
responses are refused. No redirect, retry, corrective prompt or fallback is enabled.
Bounded UTF-8 parsing and fixed, content-free failures preserve privacy. The shared
personal admission now closes owned AutoCloseable providers even on pre-send failure;
close exceptions never skip independent physical-stop cleanup or free occupancy.

Fake-control tests cover exact payload/schema/options/source bindings, inclusive
2304-input boundary, oversized and stale receipts, changed asset/ownership observations,
metadata drift, revocation before send/after response, invalid UTF-8/large bodies,
response bounds, redirects/retries, cancellation, and cleanup after client-close
failure. A normal guarded service journey using the actual main-source factory and
provider with fake HTTP publishes nonempty strict source-linked drafts for all four
mode/intent variants and confirms the existing fake stop state. An independent schema
subset check shows structurally valid invented references still fail the original
semantic validator. These are synthetic structural checks, not native grammar/model,
tokenizer, physical identity/resource or stop acceptance.

Primary inspection found and corrected a missing provider-level claim/prompt digest
comparison before final verification; this was not accepted as delegated or model-
agreement evidence. A new schema test initially had a Kotlin parenthesis error; the
corrected focused suite passed. Logs in `integrated-uat-baseline-NG0qXa` include
`owner-provider-source-bound.log`, `owner-provider-source-bound-corrected.log` and
`owner-provider-backend-full.log`. Final full checks passed **1048/1048 backend tests,
156 classes, zero failures/errors/skipped**, including **11 owned-provider tests**.
Strict change validation and tracked/new-file whitespace passed. Frontend source was
unchanged and its earlier checks were not rerun.

**Task 3.1 remains unchecked.** The physical control/supervisor and production
per-request vocabulary worker are not yet connected. The next concrete behavior is
independently supervised owned execution/cleanup across app/browser death and restart
(3.2/3.3), not starting a live model prematurely or treating an interface as physical
acceptance. No new child was used for this provider work; the existing slice budget
remains 2/2, with primary review and no additional independent review. No installation,
personal import, native count, model run, deployment or publication occurred.

The transport component's controlled slice is now checked and closed; the parent task
is not falsely completed. Work proceeds to the distinct task 3.2 behavior: a separate
supervisor retains the original boot/deadline/start fence and cleanup responsibility
after app death/restart. This is not an additional provider review or a reset of its
spent child budget. Physical control (3.3) and production vocabulary-worker integration
remain explicit dependencies; fake ownership receipts do not implement either one.

### Task 3.2 — independent lifecycle authority and process acceptance, 2026-10-25

Implemented a separate JVM supervisor, private same-user Unix IPC and singleton OS
lock over the existing ownership journal. A content-free companion row stores exact
claim, original continuous origin/boot/timebase, immutable deadlines, application and
supervisor process identities, one-shot launch nonce/consumption, worker identity,
tombstone and stop observation. The raw supervisor connection never constructs
`HistoryStore` or advances its owner epoch. No parallel execution queue was added.

Owner-UAT origin now precedes external preflight. Registration commits with reservation;
a durable supervisor responsibility receipt precedes request acknowledgment and launch.
Native factory/provider entry requires the supervision marker. START_INTENT precedes
dispatch; a child activation gate opens only after exact worker registration. Consumed
uncertain launches cannot retry. App death aborts outstanding work; browser loss alone
does not. Recovery fences old records before accepting new mutations. All phase and
publication authority retains the original 120/900/30/1140/1200-second bounds. Cleanup
uses the 60-second reserve/remaining total; recovery cleanup after expiry does not
authorize renewed work. Stop-observation timestamps do not claim termination at an
unobserved deadline.

The bounded Mac clock implementation uses lazy JDK25 FFM, boot UUID checks and exact
integer timebase arithmetic. Primary inspection corrected uppercase native boot UUID
normalization. No actual host-sleep test or native model/tokenizer operation was run.
The native clock source is implemented, not declared physically qualified. Fake-clock
tests cover missing/changed boot/timebase, backwards/overflow ticks, exact phase limits,
preflight consuming startup time and expired publication. Core revocation/tombstones
and publication share one SQLite transaction; final-check failure rolls back both
output and consumed permission. Missing supervision or dead/mismatched supervisor
identity cannot publish. Normal owned-provider HTTP tests now require fake supervision
registration rather than bypassing the new gate.

Separate synthetic app, supervisor and worker OS processes verify complete strict
publication, duplicate submission, singleton ownership, readonly status, app death,
supervisor death/restart, unchanged original clock, fake sleep across work/total expiry,
boot discontinuity, and EOF before worker identity/activation. In the uncertain-start
case the slot stays UNKNOWN/STOPPING with publication disabled and no redispatch;
neither EOF nor an unregistered missing PID is treated as physical stop proof. Tests
verify exact owned process exit before deleting generated stores. Main-jar inspection
excludes all fake worker/backend/app fixture classes. The production backend remains
unavailable until task 3.3: this does not claim model/CRI/cgroup or hardware acceptance.

The new companion table changes the exact schema manifest (56 tables). A fixture with
the prior layout is refused without migration, reset, epoch rotation or loss of records.
No personal/Pi store was accessed or upgraded. Package/restore compatibility remains
task 6; equal numeric schema versions alone are not sufficient compatibility proof.

Final current-source checks: **1064/1064 backend tests, 159 classes, zero failures,
errors or skipped**. Strict all-item validation **14/14** and tracked/new-file whitespace passed;
frontend code was unchanged. Logs in `integrated-uat-baseline-NG0qXa` include
`supervision-resume-baseline.log` (initial unfinished test-fixture compile failure),
`owner-supervision-backend-full.log` (two failures),
`owner-supervision-corrected-focused.log` and
`owner-supervision-backend-corrected.log` (final pass). The first full run exposed an
expected schema-count assertion still at 55 and a test incorrectly requiring UNKNOWN
during an active STOPPING probe. Both were corrected without weakening the requirement
that uncertain work remains occupied and cannot publish. The marker writer compile
failure was corrected to create/fsync a private file with the proper API.

Supervision slice used architect `ses_e8dcce049ffehaEVlU95RVMDDD` and clock helper
`ses_e8dc9b9fbffeI41i4Qls2Nyhip`: **2/2 children used**, no extra independent review.
Primary inspected recommendations, native clock declarations, journal/process code
and all checks. Status is now **8/28**. Task 3.1 remains open for concrete native
counting/physical-control integration; 3.3 is the next implementation slice. No new
real model run, installation, personal import, deployment, external publication,
commit or push was authorized or performed by this implementation checkpoint.

Persistent-delivery follow-up for task 6.1: the inherited personal ledger currently
caps total retained claims at 1000. Owner-UAT must not acquire a hidden lifetime quota
or require deleting records to keep using the app. Resolve owner-specific retention/
query behavior before handoff while preserving ordinary/development safety limits.

### Task 3.3 checkpoint — concrete read-only descendant verification, 2026-10-25

**Task 3.3 remains unchecked; overall progress stays 8/28.** The bounded architecture
review identified two required integrations absent from the existing process tests:
physical UID/cgroup/CRI proof before releasing occupancy, and a fresh supervisor-bound
health receipt before publication. An OS worker exit or Boolean backend receipt is not
model descendant proof. The existing production backend still refuses execution.

Primary implemented `backend/runtime/owner_uat_node_probe.py`, a concrete read-only
Linux boundary verifier, with `backend/runtime/tests/test_owner_uat_node_probe.py`.
The source validates exact claim/launch/boot/pod/container/cgroup references, the
cgroup-v2 mount and complete readable tree, then verifies every matching pod subtree
and recursive population counter is empty. Both CRI running-container and sandbox
reads must exclude the pod UID and all recorded IDs. Rechecking cgroups and boot
after CRI prevents treating an incomplete one-shot observation or reboot as proof.
Filesystem traversal/read sizes, subprocess output, process-group cleanup and the
whole observation have finite limits; errors remain `UNCONFIRMED` without diagnostics
containing source payloads or exception details.

The affirmative result is **`BOUNDARY_EMPTY` only**, deliberately not `STOPPED`.
Creation fencing, safe workload isolation, UID-precondition Kubernetes deletion,
host worker stopping and supervisor claim/epoch checks remain separate requirements.
This source is not installed, invoked on a node, connected to the supervisor or
treated as model/runtime acceptance. It does not release occupancy. The smallest
next behavior is to combine this exact boundary observation with durable create/
runtime identity and an owned stop coordinator, then connect resource/pressure/
runner-mmap health and final publication receipts. No additional generic execution
framework is needed.

Checks: **12 new mocked/isolated-filesystem tests passed**, and **25/25** including
the existing development node-health/termination tests. The new command-IO tests
mock process creation, selection, reads and process-group cleanup; no real control
command, native count or model operation ran. Evidence:
`integrated-uat-baseline-NG0qXa/owner-node-proof-mocked.log`. The first focused run
found one negative test changing an unrelated ancestor rather than the pod identity;
the assertion was corrected to change the actual pod component. The substantive
source guard was not weakened. Backend Kotlin/frontend sources were unchanged by
this checkpoint; the last backend result remains 1064/1064, not a newly rerun suite.

Slice budget is **2/2 children used**, including architect
`ses_e8d4b038fffeVoC4p3BsTdj9uo` and routine
`ses_e8d47725effe73w4hbEC8lIxhi`; no child remains pending and no independent review
was added. The routine return explicitly reported incomplete fail-closed scaffolding
and five tests, not an implemented observer. Primary inspected and discarded that
host scaffold and its tests, replacing the placeholder node helper with the concrete
verifier above. Those five scaffold tests are not acceptance evidence. Continue this
same unfinished slice in the primary session; a restart does not replenish its child
budget.

The architecture review also found a native-loader mismatch to preserve as a real
acceptance risk: the request/runtime hash currently pins `use_mmap=false`, while the
accepted observed-loader gate requires mmap/no-repack evidence. Do not silently
change the request, infer the runtime ignores it, relax the observed gate, refresh
frozen hashes or run another probe. Any runtime-binding revision needs renewed
installed-policy acceptance; actual behavior remains unverified.
