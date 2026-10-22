# Tasks

**Implementation and the bounded test are approved.** On 2026-10-22 the user asks to
commit/push all accumulated code, apply this proposal and run the test, explicitly
approving it. The user additionally states Ollama is used only by this development
work and restoration is unnecessary unless needed for development. This authorizes
the proposed one-probe/one-u02 test conditionally on the offline safety checkpoint,
not immediate execution or relaxed limits. Record a fresh exact source/root-bound
grant only after that checkpoint passes. Default new-pilot cleanup to verified owned
termination with the existing deployment left paused; restoration is optional and
must be chosen explicitly before admission. Preserve all historical policies/roots.
The user also requests a visible hands-on synthetic UI demo after these tasks.

This later user instruction supersedes the original requirement for another approval
conversation after task 2.3, not the safety checkpoint itself. At that checkpoint,
freeze source evidence and record the already approved exact conditional grant.
Group 3 remains blocked until that grant passes admission. In tasks below,
restoration verification applies only when the grant explicitly selects restoration;
otherwise verify owned termination and the unchanged deployment left paused. No
user-approved operation expands the fixed request/resource/time limits.

Slice budget: architect `ses_e9fce6368ffeQ8KHNwLCcuQWYx` and routine UI builder
`ses_e9c821fb4ffeKit2JRWQiNCTt6` used **2/2 children** and the sole independent
architecture review. Primary inspected recommendations and changed files against
current source. No children remain; commands and restarts do not reset this budget.

## 1. Offline acquisition prerequisite for one complete synthetic journey

This group exists only to unlock a fake durable acquisition imported through the
existing confirmation UI. Stop rather than grow a new scheduler or recovery framework.

- [x] 1.1 Add an isolated exact pilot profile/revision/authority definition admitting only known v5 u02 plus its required stop probe. Pin unchanged packet, prompt, schema, settings and model/runtime identities; predeclare the existing v5.1 case comparison separately from strict validation and the minimum usable-output rule in design §1. Document count/time/resource caps and approval boundaries with the code. Verify offline positive definitions and negative tests for a second root/launch, wrong case/profile/source, old v5 authority, altered pins, fabricated token evidence and missing probe. Historical manifests and the old eight-case authority remain unchanged.
  Context: [one acquisition](design.md#1-acquire-once-and-inspect-a-sealed-result),
  [exact profile](design.md#2-reuse-durable-safety-with-a-new-exact-profile),
  [budgets](design.md#3-fix-budgets-at-every-boundary).
  Acceptance: `benchmarks/live-intake-pilot/` contains an independently selected
  exact profile, derived source/predicate manifest, immutable request/context binding,
  explicit one-root authority consumption and a documented fake/live distinction.
  Five focused profile tests pass, including missing probe, wrong case/library pin,
  token overflow, forbidden generation evidence, expanded limits and duplicate root.
  The fake-context mode cannot admit a live runtime. The combined 11-test pilot
  suite also passes through a real cross-process fake acquisition and unchanged
  Kotlin validation. No tokenizer/model/runtime operation occurred. Detailed evidence:
  `approved-versioning-and-live-pilot-qyuj6g75/pilot-offline-pipeline-tests.log`.

- [x] 1.2 Wire only the necessary explicit shared supervisor/claim/runtime policy seams for this profile and produce a sealed acquisition/cleanup receipt through fake transport. Keep all live entry points closed without the new exact grant. Verify a real cross-process fake durable run, duplicate admission refusal, observer loss, worker interruption, no uncertain replay, cancellation/late-output fencing, phase/case/pod/grant limits, descendant stop proofs, cleanup/restoration failure and immutable source bindings. Run affected shared-runtime and v4/v5 regressions with no skips; document actual failures and corrections. No real tokenizer, API probe, pod/deployment or model operation occurs.
  Context: [durable reuse](design.md#2-reuse-durable-safety-with-a-new-exact-profile),
  [runtime gate and cleanup](design.md#4-treat-the-inherited-service-interruption-as-an-explicit-gate),
  [receipt chain](design.md#5-import-trusted-acquisition-evidence-rather-than-an-arbitrary-response-file).
  Acceptance: 13 pilot tests pass, including a real disconnected-observer fake worker,
  sealed acquisition, exact two launch claims, duplicate refusal, context/resource
  bounds and negative import checks. Existing interruption, late-output, phase lease,
  cleanup-owner and descendant-stop coverage passes in 123 v4 +33 shared +29 v5
  tests with no skips. The first v5 invocation failed because the operator omitted
  `INTAKE_V4_RETAIN_INTEGRATION`; the corrected command passes actual Kotlin stages.
  Original failed log and corrected log remain separate. Source-frozen fake root:
  `pilot-integration-fake-556m6shs/campaign`; receipt
  `53ef944229e838cdcfa263b08eae5004926ab9ec4788e6465c80deef8f45d8ee`.

## 2. Offline capture-to-confirmation journey and mandatory execution stop gate

- [x] 2.1 Add a bounded test-classpath startup importer and live-pilot module reusing only necessary private-store/confirmation plumbing. Snapshot verified artifact bytes; bind registered root/grant/claims/source/receipt to the independently recorded completion digest; rerun unchanged strict wire/binding/semantic validation and draft decoding. Seed only u02 into a newly generated disposable store. Verify real-store tests for correct import, wrong source/revision/content, fake-as-live and retained-capture substitution, missing independent trust anchor, symlink/hardlink/traversal/oversize/mutation, failed cleanup, empty/rejected output, zero production execution rows, auth/origin/CSRF and explicit atomic/idempotent/stale confirmation. Document live versus fake provenance and keep the completed handwritten-fixture launcher unchanged.
  Context: [import boundary](design.md#5-import-trusted-acquisition-evidence-rather-than-an-arbitrary-response-file),
  [interaction reuse](design.md#6-reuse-the-interaction-boundary-and-keep-modes-distinct).
  Acceptance: the pure Python registered-chain verifier and startup-only JVM bridge
  revalidate bytes without any provider access. Real store/API tests cover isolated
  seed, immutable old source, source correction, auth/origin/CSRF, stale/mismatched
  confirmation, replay safety and excluded production routes. Pilot integration tests
  reject mutated/oversized/linked/traversing captures, failed cleanup and fake-as-live.
  Actual fake Ktor startup and browser journey pass under the final source closure.

- [x] 2.2 Add the separate explicit live-pilot UI adapter/entry using the existing loader and source-bound parser, with honest acquisition provenance and no run/upload/provider endpoint. Preserve all unknown acknowledgments, invalid-calendar staging checks, source correction and uncertain-confirmation behavior. Add the exclusive 1200-second app-session claim and exact-server/descendant cleanup. Verify focused frontend/launcher tests, normal and pilot builds, the existing fixture regressions, busy-port refusal, duplicate app launch, deadline cleanup and no writes/model calls before explicit confirmation. Document the runnable fake and future live journeys without labeling fake evidence as acquired output.
  Context: [separate modes and bounded session](design.md#6-reuse-the-interaction-boundary-and-keep-modes-distinct),
  [confirmation requirement](specs/intake-extraction-evaluation/spec.md#requirement-live-pilot-authority-requires-explicit-reviewed-confirmation).
  Acceptance: separate bundle/entry and strict adapter pass 372 frontend tests and
  both builds. The primary corrected UI provenance initially inserted as a model
  question; it now renders separately without changing production response parsing.
  Six launcher/store/API tests include monotonic deadline cleanup, regressed clocks,
  exclusive durable private session claim and correction/replay boundaries. A real
  busy-port bind refuses takeover. Browser requests through staging contain no write
  except login. All four unknown acknowledgments are necessary before staging.
  Runnable instructions: `docs/data/live-intake-pilot.md`.

- [x] 2.3 Primary exercises one real-browser, unmistakably fake-evidence acquisition/import/inspect/edit/resolve/stage/explicit-confirm/reload journey and verifies every store table count before and after confirmation, with no runtime access or production execution rows. Stop its owned launcher and remove only its generated store. Run appropriate backend/frontend/shared/legacy safety regressions, strict OpenSpec, frozen manifests, source/private-file/link/whitespace checks and content-free diagnostics checks. Freeze the actual source closure and evidence, retain any failures, and record the exact conditional runtime grant using the user's separately stated approval. No tokenizer, model, live API or runtime mutation occurs until task 3.1 admits that grant.
  Context: [budgets](design.md#3-fix-budgets-at-every-boundary),
  [service interruption gate](design.md#4-treat-the-inherited-service-interruption-as-an-explicit-gate),
  [migration and rollback](design.md#migration-plan).
  Acceptance, 2026-10-22: final frozen fake receipt above passed actual bounded
  app startup and real-browser u02 inspection/edit/four unknown acknowledgments/
  staging/explicit selected confirmation/reload. Every SQLite table count was
  unchanged before confirmation; afterward exactly one commitment revision and
  one receipt existed. Reload changed no counts and made no suggestion/model call.
  The exact owned server exited and its private generated store was removed.
  Evidence: `approved-versioning-and-live-pilot-qyuj6g75/fake-final-browser-journey.json`,
  `fake-final-store-*.json`, `fake-final-inspector-terminal.json` and
  `pilot-offline-sourcefreeze-tests.log`. Full offline backend build passes in
  1m43s; frontend372, v4 123, shared33, v5 29, oracle45 and pilot13 checks pass.
  Strict OpenSpec, frozen lineage, whitespace and changed-file publication checks
  pass; no credential candidates, links, private capture or generated files appear.
  These are offline/fake results only. The runtime gate is now eligible for the
  fresh source/root-bound grant, not for expanded execution or retries.

## 3. Separately authorized one-shot live acquisition and actual outcome

All tasks here are blocked by task 2.3 and a fresh exact grant recording the user's
bounded-test approval. No old authority, successful test alone, implementation
approval alone or model catalog listing opens this gate.

- [ ] 3.1 Record the exact non-renewable execution grant against the verified source closure, private root, model/image/runtime identities, one probe/one u02 request, fixed deadlines and separate one-session app budget. The grant records the user's approved tokenizer/live metadata observations, temporary Ollama deployment pause from one replica to zero, owned probe/extraction pod creation/termination and verified cleanup leaving the original deployment paused, without restoration, per the user's later instruction. Verify durable root consumption and fresh real token-count/context/resource/ownership admission under the grant before generation. If any permission, safety evidence or budget is absent, record blocked admission with zero unauthorized sends or mutations; do not silently revise the plan or reuse an old root.
  Context: [fixed caps](design.md#3-fix-budgets-at-every-boundary),
  [required runtime operations](design.md#4-treat-the-inherited-service-interruption-as-an-explicit-gate).

- [ ] 3.2 Primary starts the durable owned worker only after task 3.1 admits it. Run at most the one mandatory stop probe and one u02 generation, with no correction, retry, alternate packet, fallback or replacement root. Retain raw response, strict stages, case comparison, claim/ownership/resource events and all failure/unrun evidence. Verify actual request count, deadlines, source/request/raw bindings, both owned stop proofs and exact authorized restoration; seal the terminal acquisition receipt and independently record its expected digest. On rejection, timeout, unusable output or uncertain cleanup, close/fence the allowance and report the actual outcome without import or another run.
  Context: [acquire once](design.md#1-acquire-once-and-inspect-a-sealed-result),
  [durable safety](design.md#2-reuse-durable-safety-with-a-new-exact-profile),
  [cleanup](design.md#4-treat-the-inherited-service-interruption-as-an-explicit-gate),
  [receipt trust](design.md#5-import-trusted-acquisition-evidence-rather-than-an-arbitrary-response-file).

- [ ] 3.3 If and only if genuine usable output and cleanup are admitted, primary imports that sealed capture into the one authorized bounded loopback session and exercises actual generated suggestion inspection/editing/unknown resolution/staging/explicit confirmation/reload. Verify all authoritative table counts unchanged until explicit confirmation, selected writes only afterward, no duplicate/reload/model requests and zero production execution rows. Stop the exact owned server/descendants and dispose only its generated store. Preserve original generation/comparison and edited confirmation separately; report actual success/failure/incomplete status, source/private/frozen checks and cleanup without a reliability or qualification claim. If live output cannot support the journey, record that failure without using fake evidence to complete it. No deployment, git, spec sync/archive or additional inference follows automatically.
  Context: [separate bounded interaction](design.md#6-reuse-the-interaction-boundary-and-keep-modes-distinct),
  [honest outcomes](specs/intake-extraction-evaluation/spec.md#requirement-the-live-pilot-has-bounded-private-inspection-and-honest-outcomes).
