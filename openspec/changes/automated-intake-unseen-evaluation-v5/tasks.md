# Tasks

Apply checkpoint: user explicitly says "apply v5". Implementation and offline
validation are authorized; live execution still requires all specified admission
gates. No v5 token count or candidate inference has occurred yet.

Shared slice budget: architect child `ses_ea1733315ffeuTIW2SZE89Vj5B` used 1/2;
sole independent architecture review consumed. Routine child
`ses_ea1334ed7ffeSYXr4X4uYqSmr7` is implementing the packet bridge/replay: 2/2 used.
Primary owns integration and live operations. Commands and
restarts do not reset this unfinished budget.

## 1. Usable fresh-packet offline evaluation

- [x] 1.1 Author and freeze the eight-case u01-u08 corpus, unchanged provider schema, positive witnesses and complete prospective typed/extractive predicates under an isolated v5 manifest linked to v4.2. Bind the exact selected prompt hash without adding answers or demonstrations. Verify each original positive and declared label/question equivalence, per-field missing/wrong mutations, activity/travel confusion, omitted clarification details, stale corrections, source/identity errors and cross-case leakage. Verify parent manifests unchanged and document synthetic non-independent coverage and no-inference status in the new benchmark README.
  Context: [case matrix and candidate](design.md#1-freeze-fresh-cases-and-the-unchanged-candidate-before-inference),
  [comparison semantics](design.md#2-apply-the-frozen-v42-comparison-semantics-prospectively).
  Acceptance:7 synthetic oracle tests pass, including every declared field's
  omission/wrong-value mutations and all predeclared alternatives. New manifest and
  all parent manifests verify. Actual Kotlin and candidate evaluation remain pending.
  Evidence root `intake-v5-prospective-apply-approved-q4htyfnl` retains user apply
  scope, corpus acceptance and pre-change operational source snapshots.

- [x] 1.2 Implement the isolated private packet-aware Kotlin bridge and offline v5 replay, calling unchanged strict wire/binding/production semantic validation on each frozen new packet. Verify all eight witnesses through actual Kotlin then the prospective oracle; test packet/hash/manifest substitution before response processing, malformed wire, semantic rejection, duplicate fields, exact-excerpt binding, private exclusive files, bounds and sanitized diagnostics. Preserve old bridge/validator source and document a usable offline result with zero model calls before live plumbing.
  Context: [packet-aware bridge](design.md#4-validate-supplied-packets-through-an-isolated-kotlin-bridge),
  [evidence](design.md#6-gate-claims-and-preserve-the-denominator).
  Acceptance:12 offline Python checks pass. Detached actual Kotlin job
  `v4w-goiecv8b` completed normally without skipped gates: all8 fresh witnesses
  accepted by strict Kotlin and oracle;4 negative cases rejected at intended wire,
  binding and semantic stages. Source fingerprint and owned compiler stops verified.
  Evidence `intake-v5-prospective-apply-approved-q4htyfnl/primary-offline-kotlin-acceptance.json`.
  This is a usable offline fixture evaluation, not candidate quality or inference.

## 2. One-batch durable execution without candidate inference

- [x] 2.1 Add only the explicit legacy-v4/fresh-v5 suite selection and thin v5 entry/revision/context path needed by the existing runtime, campaign, lifecycle and supervisor. Bind the full transitive source closure and exact packet/request/predicate/phase/report/receipt identities; explicitly allow the v5 Mac profile without relaxing runtime guards. Snapshot pre-change operational sources. Verify an all-eight fake end-to-end durable batch, original-v4 compatibility, cross-suite/entrypoint/source mismatch refusal, exact request membership and manifest integrity. Document the source-bound suite seam and its non-plugin limitations.
  Context: [suite seam](design.md#3-reuse-execution-through-one-explicit-suite-selection),
  [fixed allowance](design.md#5-spend-one-immutable-batch-allowance-never-retry-the-candidate).
  Acceptance:29/29 detached v5 tests, including all-eight fake ledger/receipt and
  direct runtime request/probe/source-fence coverage; shared runtime33/33 and full
  legacy123/123 pass without skipped gates. All transitive source fingerprints and
  original/new frozen manifests verify. Pre-change and accepted source bytes retained.

- [x] 2.2 Enforce one v5 root/batch/eight scored launches/one probe with no correction, revision, retry, recovery coordinator or replacement allowance. Verify duplicate start, ninth request, second probe/batch, terminal reopening and uncertain replay are refused; exercise actual synthetic launchd observer loss, worker death, timeout, explicit cancellation, source drift and cleanup-publication failure with unchanged process/pod ownership and bounded cleanup. Run the existing lifecycle/recovery/runtime regression selections and new v5 checks, including actual Kotlin integrations without skipped acceptance gates; record actual counts and offline evidence before admission. If implementation expands beyond this behavior, explain the prerequisite instead of adding a scheduler.
  Context: [authority and safety](design.md#5-spend-one-immutable-batch-allowance-never-retry-the-candidate),
  [bounded scaffold scope](design.md#3-reuse-execution-through-one-explicit-suite-selection).
  Acceptance: new one-batch/authority-consumption/duplicate-launch checks and actual
  v5-profile synthetic launchd lifecycle scenarios pass, together with unchanged
  shared and legacy cleanup/source/publication regressions. Final source proof
  `intake-v5-prospective-apply-approved-q4htyfnl/primary-execution-acceptance.json`:
  v5 job `v4w-bthsda05`29/29, shared `v4w-euao6col`33/33, legacy
  `v4w-6grir4i1`123/123 in2396.107s including3 actual Kotlin integrations. Fresh
  Kotlin8 positive/4 negative cases remain accepted. Nested job/compiler stop
  proofs independently checked. No model requests or new recovery allowance.

## 3. Prospectively frozen bounded evaluation and readiness decision

- [x] 3.1 After offline/fake/legacy checks pass, copy and verify the unchanged prompt, freeze new exact revisions/requests and acquire fresh vocabulary-only counts for all eight rendered inputs plus probe. Verify input+1536+256<=4096, source/policy/schema/template/tokenizer/model bindings, absence of case gold in prompts and the pinned runtime/identity/pressure/Ready/unloaded gates. Record admission evidence privately. On overflow or any gate failure, retain the blocked result and stop without truncation, prompt changes, expanded limits or inference.
  Context: [freeze and context](design.md#1-freeze-fresh-cases-and-the-unchanged-candidate-before-inference),
  [suite bindings](design.md#3-reuse-execution-through-one-explicit-suite-selection).
  Acceptance: fresh cached vocabulary-only counts u01-u08 are2276/2281/2257/2289/
  2273/2286/2268/2282, probe47. Maximum2289+1536+256=4081<=4096. All nine exact
  request/render/token hashes bound in `revision-01/`; prompt/demos/schema/model
  unchanged, no answers added. Final pinned runtime/identity/storage/policy/pressure,
  exact original spec, one Ready unloaded service and no-owner checks accepted.
  Completed current test jobs removed. Evidence `primary-context-runtime-admission.json`
  and `preparation/context-01/` in the apply root. No inference at this checkpoint.

- [x] 3.2 After a separate user apply instruction and accepted admission gates, primary records one new exact authority and launches the durable v5 batch: eight fresh single scored attempts and one existing stop probe, one 14400-second native/UTC deadline with 240-second reserve and all existing phase/resource limits. Never change rules or prompt after acquisition begins. Verify durable readiness, spent request intents and progress evidence; retain every quality rejection while healthy remaining cases run, but stop on infrastructure/safety/timeout failure with no retry or replacement. Document actual running/terminal status without raw content or premature score claims.
  Context: [non-renewable allowance](design.md#5-spend-one-immutable-batch-allowance-never-retry-the-candidate),
  [claims](design.md#6-gate-claims-and-preserve-the-denominator).
  Live checkpoint: one authorized root
  `intake-v5-prospective-apply-approved-q4htyfnl/campaign`, grant starts
  2026-10-21T15:29:18.388066Z and expires2026-10-21T19:29:18.388066Z. The one-root
  authority file is exclusively consumed. Launchd supervisor `v4w-b0__rqqx` is ready;
  actual v5 worker and immutable claim/run/profile/case/campaign bindings verified.
  Evidence `model-launch-admission.json` and `first-live-worker-binding.json`.
  All eight outcomes are still pending; no extra root/retry/revision is permitted.
  Final execution acceptance: all eight responses completed normally in one batch,
  with8 strict local accepts and7 frozen prospective conformance passes. Only u03
  fails `question_roles` and `suggestion_0.commitment.label`. One probe/eight scored
  launches, no retry/revision/deadline breach; total4257.669191s. Original prompt,
  rules, packets and limits remained unchanged after acquisition began. Root is
  terminal failed/quality_failure; no replacement or further inference authorized.

- [x] 3.3 Independently inspect the actual all-eight pass/failed/incomplete outcome, exact request/packet/raw/stage/predicate/report/receipt/event hashes, source/clock/resource provenance and owned compiler/pod stopping. Verify exact original deployment restoration, one Ready unloaded shared service and normal pressure before removing only completed temporary jobs. Record whether results support considering a separate integration pilot, not app integration or production admission. Run final frozen-integrity, focused integration, strict OpenSpec, document-link, privacy and whitespace checks; preserve all historical evidence and do not start another root, publish, commit, deploy, sync or archive specs.
  Context: [denominator and readiness](design.md#6-gate-claims-and-preserve-the-denominator),
  [transition](design.md#migration-plan).
  Final verification: all9 actual request hashes, packet/raw/stage/predicate/oracle/
  report/receipt/event bindings, original source closure and frozen lineage verify.
  All8 compiler groups and both owned pods have stop proofs. Independent final
  checks establish exact original deployment spec, one Ready unloaded shared service
  and normal pressure; completed temporary supervisor registration removed. Private
  0700 directories/exclusive0600 regular single-link artifacts verified. Evidence:
  `primary-final-prospective-model-verification.json` and
  `independent-final-model-restoration.json` in the apply root. Actual result is
  prospective7/8, not qualification; the planned integration-pilot recommendation
  remains blocked. No automatic policy relaxation, response rewrite or new run.

## 4. Separately authorized offline comparison correction

After inspecting u03, the user considers the article omission and practice/rehearsal
wording acceptable, then approves correcting the checker, adding negative tests,
versioned offline re-scoring and proposing (not implementing) a guarded pilot with
"ok, do ot". This does not reopen or change the completed prospective acquisition.
New slice: architect `ses_ea0205b76ffevLrrG0eK53MTfi` used1/2 child slots and the sole
independent architecture review; primary implements and verifies the bounded rules.

- [x] 4.1 Add isolated v5.1 comparison semantics for one optional leading indefinite article in commitment labels and role-bound practice/training or practice/rehearsal question equivalence. Preserve all facts, local validation and historical bytes; refuse new question expansion for ambiguous or unsupported frozen targets. Verify actual u03 fails each old predicate independently but passes the combined corrected policy, all declared positives, per-field omissions/wrong values, wrong activities/dates/disclosures/excerpts, missing question details, role leakage and ambiguity. Freeze new policy/source/tests manifest, then re-score all eight retained responses from the one original run into a private source-hash-linked, explicitly post-hoc report with zero inference. Record actual outcome and final source/frozen/privacy/document checks without claiming original-v5 success or model improvement.
  Context: [bounded wording policy](design.md#7-correct-wording-equivalence-in-a-separate-offline-policy),
  [claims](design.md#6-gate-claims-and-preserve-the-denominator).
  Acceptance:45 old/new oracle tests pass, including all236 v5 missing/wrong-field
  mutations and targeted wrong activity/date/detail/disclosure/ambiguity cases.
  Actual u03's two original predicate causes verified independently on comparison
  copies. Same8 retained responses: original frozen-v5 **7/8**, separately labeled
  post-hoc-v5.1 **8/8**; only u03 changes. Evidence
  `intake-v51-wording-comparison-approved-v35_c_9w/retained-eight-comparison/comparison.json`
  and `user-authority-and-acceptance.json`. All historical reports/receipts/terminal,
  frozen parents and accepted runtime source remain unchanged. Zero new inference,
  no original-v5 qualification or independent semantic score claimed.

- [x] 4.2 Propose a small guarded app-integration pilot in benchmark documentation, grounded in existing suggestion validation/confirmation boundaries. State explicit user confirmation before authoritative writes, synthetic-only initial scope, rejection/ambiguity handling, acceptance checks and separate implementation approval. Verify the proposal introduces no app/runtime/personal-data operations or production admission; distinguish corrected post-hoc checker evidence from fresh qualification.
  Context: [proposal boundary](design.md#8-propose-but-do-not-implement-an-integration-pilot).
  Acceptance: `benchmarks/intake-extraction-v5-1/PILOT-PROPOSAL.md` describes one
  fixture-backed synthetic inspect/edit/resolve/stage/explicit-confirm journey in a
  disposable store. Grounded in current suggestion UI, intake editor, strict
  contract, confirmation routes/store and still-closed profile admission. Includes
  no-write-before-confirm, stale/invalid/unknown rejection, atomic/idempotent
  confirmation, auth/CSRF, isolation and no-inference tests. Validation is not
  misrepresented as universal factual truth. Proposal only: app/runtime source,
  personal stores and production admission remain unchanged; separate approval
  required before implementation.

## 5. Approved guarded synthetic interaction pilot

The user explicitly replies "approved" to the guarded pilot proposal. This
authorizes implementing and locally verifying that synthetic-only harness, not live
extraction, personal data or production deployment. Use frozen handwritten witness
pairs with honest provenance; do not copy private candidate responses into source.
Slice used both child slots: architect `ses_ea00c2151ffeXKWGvEWpm1qkYd` provided
the sole independent architecture review; routine `ses_ea005094dffeWekqgUwJdWnyys`
provided the bounded backend starting point. Primary independently inspected and
corrected that implementation, completed missing safety tests and integrated the UI.

- [x] 5.1 Implement an opt-in test-classpath loopback pilot backend with fresh private disposable storage, exactly bound synthetic source/witness pairs, a fixture-specific suggestion envelope and the existing strict validator/confirmation services. Keep normal application assembly, providers and admission out of the harness. Verify fixture/hash/source and revision rejection, zero authoritative writes on inspection, auth/origin/CSRF, explicit atomic/idempotent confirmation, stale conflicts, route allowlist, disposable-store isolation and sanitized errors through real backend tests. Document the honest handwritten-fixture provenance and launcher boundaries.
  Context: [approved harness](design.md#9-implement-the-approved-synthetic-interaction-harness).
  Acceptance: test-classpath `SyntheticIntakePilot` validates all eight frozen
  handwritten witnesses and pins their manifest/file/source/wire hashes. Its six
  tests and 41 existing validation/intake/confirmation/admission tests pass, with
  no skips. Actual temporary SQLite stores verify exact revision/content rejection,
  strict wire/excerpt/date/mode negatives, auth/origin/CSRF, route allowlist,
  correction invalidation, mixed atomic confirmation, replay/conflicting reuse,
  unchanged closed admission and generated-directory cleanup. No execution or
  publication receipts are seeded. Dedicated cookie/defaults and additive Gradle
  task inspected; normal assembly and validators remain unchanged.

- [x] 5.2 Add a separate pilot frontend entry/build and narrow injected fixture-loader seam, preserving the normal suggestion endpoint/parser and default manual intake behavior. Reuse source-bound draft parsing, unknown resolution/acknowledgment, staging and explicit confirmation; disable arbitrary new-source creation in the pilot. Verify no request before inspection, no writes on inspect/edit/stage, invalid/stale drafts blocked, every unknown blocking staging, invalid commitment dates/recurrence blocked, correction clearing drafts and uncertain-confirmation reload behavior. Run existing focused UI tests and both normal/pilot builds; document runnable synthetic journey without claiming extraction quality.
  Context: [frontend and confirmation seam](design.md#9-implement-the-approved-synthetic-interaction-harness),
  [pilot acceptance](../../../benchmarks/intake-extraction-v5-1/PILOT-PROPOSAL.md#acceptance-checks-before-considering-a-live-pilot).
  Acceptance: 12 new pilot tests plus 29 existing suggestion/intake tests pass.
  Full frontend suite passes **363/363**, 28 files, with `--maxWorkers=2`;
  normal and separate pilot builds pass. Normal build retains its existing large
  chunk warning. Concrete staging defect fixed: calendar drafts now reject invalid
  dates/timezones/recurrence and reversed intervals before appearing ready to confirm.
  Inspection reset now runs before interaction, preventing a delayed mount reset
  from erasing an inspection result. Initial full default-parallel run had one
  pilot inspection race and an unrelated amendment test timing failure; preserved
  in `frontend-full.log`, not counted as a pass. Initial harness test selection
  waits and a Kotlin test syntax error were corrected before acceptance.

- [x] 5.3 Primary starts only the new loopback harness, verifies a real browser inspect/edit/resolve/stage/explicit-confirm/reload journey against its disposable store and confirms no earlier authority writes or model operations. Run focused backend/frontend safety regressions, strict OpenSpec, source/frozen/privacy/link/whitespace checks; record actual behavior and defects, stop the owned harness and dispose only its generated store. Preserve original app/runtime admission, source manifests and historical benchmark outcomes; no git, deployment, app-data access, live inference or new evaluation allowance.
  Context: [harness boundaries](design.md#9-implement-the-approved-synthetic-interaction-harness).
  Acceptance: primary exercised the built UI at `http://127.0.0.1:8082`, signed in
  with a dedicated synthetic credential and chose u03. Changed label, explicitly
  entered dates/timezone and weekly recurrence, acknowledged all four unknowns,
  staged, inspected visible pending JSON and explicitly confirmed once. Every SQLite
  table count remained identical through inspection/edit/stage. Only confirmation
  produced one commitment and one confirmation receipt; full page reload preserved
  those counts, with no draft, automatic confirmation or execution rows. Owned
  server stopped and its generated private store was removed. Browser tooling
  click/fill/hidden-page animation issues were inspected before continuing; no
  uncertain confirmation was repeated. Evidence under approved temporary base:
  `synthetic-intake-pilot-approved-Ngv91Q27/browser-journey.json`, five
  `store-*.json` snapshots, owned-process/cleanup records and test logs. Final
  acceptance records strict OpenSpec, frozen manifests, 45 offline oracle tests,
  sanitized logs, bundle separation, privacy, links and whitespace checks.
  [Run instructions and claim boundary](../../../docs/data/synthetic-intake-pilot.md).
  No live extraction, personal/app-store access, VM operation, deployment, commit,
  push, spec sync or archive occurred. Historical 7/8 prospective and 8/8 post-hoc
  results remain distinct; this interaction pilot does not qualify extraction.
