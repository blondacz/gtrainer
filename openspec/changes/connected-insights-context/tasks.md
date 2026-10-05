# Tasks

## 1. Store attributed context and feedback

- [x] 1.1 Add additive persistence for stable context IDs/revisions, source category, author attribution, entering user, observation/applicability dates, and optional sport/activity/review links; verify synthetic restart and migration tests preserve existing imported record/event IDs.
- [x] 1.2 Add authenticated context create/read/correct/retire/delete operations with CSRF and input validation; verify attribution, supersession, expired/retired exclusion, and active deletion using synthetic tests.

## 2. Retrieve explainable relevant context

- [x] 2.1 Implement bounded deterministic date/sport/activity filters, mandatory inclusion of applicable blocked/allowed activity/sport and maximum-duration restrictions, optional-context bounds, and fail-closed mandatory overflow; exclude superseded/retired/expired/deleted entries. Verify frozen synthetic inclusion/exclusion and ordering cases.
- [x] 2.2 Detect blocked/allowed conflicts and differing overlapping maximum-duration assertions; return both as unresolved with provenance and block connected-review packet creation until user resolution. Verify no ranking/model silently selects a winner.
- [x] 2.3 Expose exact included context identities/revisions through authenticated preview and keep free text in the untrusted packet channel; verify prompt-boundary cases. Dashboard packet inspection is in 5.2.

## 3. Add the connected-review contract

- [x] 3.1 Define a separately versioned provider-neutral structured contract that separates facts, attributed reports, interpretations, uncertainty, and questions; verify old factual/prototype contracts and retained benchmark replays remain unchanged.
- [x] 3.2 Bind reviews to evidence digest, context revisions, provider/model, and contract version; verify evidence/context/provider changes invalidate stale in-flight output and stale coverage is labeled.

## Context lifecycle and feedback after snapshot binding

- [x] 1.3 Propagate context deletion to derived indexes, in-flight reviews, and stored review text while preserving unrelated imported records; verify backup-retention disclosure and deletion tests.
- [x] 1.4 Add review usefulness ratings/corrections as dated attributed context linked to an immutable review snapshot; verify restart persistence and ensure feedback is never represented as measurement or model training.
- [x] 3.3 Add independent whole-response checks for source attribution, evidence references, periods, structured restrictions, and prohibited diagnosis/prescription claims; verify adversarial synthetic outputs are rejected whole and factual views remain usable.
- [x] 3.4 Enforce manual invocation, production qualification/health gate, immutable retry inputs, and explicit per-call/total attempt/time/cost bounds; verify no scheduled invocation, no fallback, no retry on timeout/resource/health/consent failure, and at most one independently validated correction.

## 4. Add explicit provider and consent boundary

- [x] 4.1 Add explicit per-request local or hosted provider selection without default routing or fallback; verify switching provider leaves stored records/context unchanged and old local-only adapters remain compatible.
- [x] 4.2 Preview exact evidence/context categories and selected entries before hosted transfer, then bind consent to provider and packet digest; fake transport tests MUST observe zero requests without matching consent and after packet change or revocation.
- [x] 4.3 Verify prompt, context, credentials, and rejected output never enter app logs, CI artifacts, fixtures, or public benchmark results using synthetic privacy-marker tests.

## 5. Expose context and review evidence in dashboard

- [x] 5.1 Add context lifecycle and review-feedback controls with source/applicability labels and encrypted-backup deletion notice; verify authenticated frontend/API behavior and accessible correction/deletion states.
- [x] 5.2 Present facts, reports, interpretations, questions, uncertainty, and exact context/evidence snapshot separately. Show unresolved restriction conflicts in a dedicated pane with source/date details. Verify stale/deleted context is shown as stale or removed and factual dashboard remains usable.

## 6. Freeze and evaluate usefulness before qualification

- [x] 6.1 Build synthetic matched facts-only, context-present, and context-absent cases plus an independent rubric for grounding, attribution, relevance, useful connections/questions, uncertainty, and inappropriate advice; verify rubric, case count, prompt/contract version, and pass threshold are frozen before any model call.
- [ ] 6.2 After separate explicit user approval for inference, compare only explicitly selected local/hosted candidates on identical synthetic packets; retain every first-pass/correction/failure outcome and record model/settings, hashes, latency, resource use, and cost where available. Without approval, verify harness refuses to call providers. **In progress:** V2 and all partial captures remain private. Complete V3 runs: Granite 0/9 final drafts accepted; Qwen3.5 4B 5/9 accepted, 4/9 rejected. Ministral's latest V3 run rejected case 1 and timed out on case 2; cleanup succeeded. After Qwen completed, the approved GPT-5.6 Luna conversational reference produced 9/9 drafts accepted by Python and the actual Kotlin validator. All three context-present Luna drafts ignored supplied context. Session IDs, hashes, raw responses, and uncontrolled-setting limitations are retained privately; this reference is not a controlled provider comparison. The incomplete Ministral run and comparison limitations remain explicit. Production execution gate remains closed.
- Additional approved Pi V3 runs: DeepSeek R1 1.5B completed 9/9 with zero protocol passes (thinking emitted in every case); Phi-4 Mini 3.8B completed 9/9 with three context-present accepts and six final rejections for unsupported context references. Gemma 4 E2B's latest retry with 60-second monitoring command timeouts staged successfully, then disconnected during first inference near the 5 GiB limit; no response or completed cases, and no confirmed OOM diagnosis. Cleanup succeeded. Separately approved Intel Mac references completed: Qwen3 8B accepted all nine final drafts after six corrections; Llama 3.1 8B accepted all nine first drafts, but each was empty (no interpretations/questions). Actual Kotlin replay matched all 24 Python outcomes. Identical packets/output settings but larger Mac timeout/memory budgets preclude an identical-runtime comparison.
- The separately approved Pi Qwen3 8B Q3_K_M / 2048-context reference uses a hash-pinned GGUF and persistent microSD storage (explicitly approved after no SSD was detected), with a 1024 MiB sampled host-headroom guard. Its first run timed out on first inference after 480.1 seconds without a response. The 5376 MiB / 600-second-attempt / 1240-second-case retry accepted its first two cases (586.400 / 482.014 seconds, including a context citation), then stopped on case three at 600.101 seconds. User-requested hash-linked resume accepted cases three through six (case five required a clinician-attribution correction), then stopped on case seven at 600.099 seconds while generation was still progressing. Cases eight/nine were not run. All continuation samples passed their guards (5376.0 MiB container peak / 1773.3 MiB minimum host headroom / 74.35°C maximum temperature); containers had zero restarts, cleanup succeeded, and the app remained healthy. Six distinct final drafts are automatically accepted across preserved captures, not one completed nine-case quality comparison or a qualification. Ordinary V3 defaults and the disabled production gate remain unchanged.
- [ ] 6.3 Require independent human review of all rubric outcomes and document qualification decision/limitations; verify schema validity or model self-approval alone cannot qualify a candidate. **Blocked:** independent human scoring and qualification decision remain outstanding. Luna and the separately approved GPT-6.1 Sol reference each have 9/9 drafts accepted by Python and Kotlin, but both omit supplied personal context in all three context-present cases. Qwen3.5 has four rejected final drafts; incomplete runs cannot be scored as completed comparisons. Mac Qwen3 cites supplied context in all three context-present cases, but usefulness/attribution require human assessment; Mac Llama's empty drafts demonstrate that schema passes alone are insufficient. The latest private review pack includes ten candidates and 116 rows, with distinct Mac/Pi/hosted provenance. Earlier packs and captures are preserved. No candidate is qualified.

## 7. Document operation and compatibility

- [x] 7.1 Document context attribution, retrieval, correction/deletion and backup retention, model/provider selection, packet consent, safety gates, and review limits; verify instructions match tested behavior and contain no personal data.
- [x] 7.2 Run full backend/frontend tests, migration/restore compatibility checks, strict OpenSpec validation, and existing review/queue regressions; verify no scheduler/queue behavior changes and no live data/model operations occur without separate approval.
- [x] 7.3 Review and refactor touched code for idiomatic Kotlin, Gradle, and React practices; clear package/class responsibilities; functional and immutable design where it stays simple; removal of avoidable duplication; and dependencies only when they provide a concrete benefit. Verify refactors with focused tests and document justified exceptions. No dependency was added; the snapshot-claim state transition was added to prevent concurrent manual submissions from exceeding execution budgets.
