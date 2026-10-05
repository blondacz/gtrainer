# Connected-review code quality follow-up

This is an implementation review record, not a claim that the whole change is
complete. It follows the earlier generic review task 7.3 in
`connected-insights-context`. Apply relevant principles from Joshua Bloch's
*Effective Java* idiomatically to Kotlin; do not copy Java patterns mechanically.

## Concrete findings and acceptance checks

| Finding | Scoped response | Evidence required |
| --- | --- | --- |
| `Application.module` mixes session/security plumbing with feature dispatch | Extract shared private HTTP parsing, headers and small session-access operations; keep services independently wired | Authentication, privacy-header, bounded-request and existing route tests |
| Kotlin `val`/read-only `List` do not guarantee immutable snapshot contents | Persist immutable serialized bindings and decode fresh packet copies at ownership boundaries | Mutate returned nested collections; verify stored/reopened data is unchanged |
| Large persistence methods intermingle validation, SQL binding and transitions | Repository-specific named SQL, cohesive transaction/binding/mapping operations and explicit state contracts | Atomic claim/terminal race, restart and adversarial-value tests |
| Database ownership must be checked before writes | Validate an existing database read-only before opening mutation paths; reject unrelated databases without changing their data or permissions | Compare unrelated file bytes, application ID and parent permissions |
| Resource and coroutine ownership is easy to obscure | Use `use`/`AutoCloseable`, preserve cancellation, never infer remote stop from socket closure | Resource/cancellation and supervised-process termination tests |
| Magic policy/state/reason values obscure invariants | Named validated policy/state/reason definitions with stable wire representations | Bounds, serialization compatibility and transition tests |

## Implementation progress

- Shared HTTP/security and session helpers have been extracted. Valid login,
  cookie, CSRF and response contracts remain; duplicate-key login JSON is now
  rejected through the same strict boundary as other private requests.
- The isolated development repository, atomic queue admission/supersession,
  ownership/health components and policy mechanisms passed synthetic/mock tests.
  The durable-event worker, supervisor receipt integration, APIs and isolated
  Developer bundle now have mock-backed regressions; live runtime acceptance is
  still separately approval-gated.
- Ordinary execution keeps its 300/600-second defaults and 600/1200-second
  configurable ceilings. Extended local-only limits have a separate policy.
- Persistence was split from a mixed store initializer/SQL/resource implementation
  into `DevelopmentReviewDatabase` (permissions/resources/safe failures), named
  `DevelopmentReviewSql` statements and cohesive store mapping/transition helpers.
  Schema/queries are readable multiline definitions, not opaque one-line literals.
- Storage reasons are allowlisted; accepting an arbitrary identifier-shaped reason
  was insufficient. Unrelated databases are checked using immutable read-only
  SQLite access, including a WAL/auxiliary non-mutation regression test.
- Construction validates model/runtime/timeout values before cloning HTTP resources.
  The provider explicitly disables redirects/retries and owns/closes its client;
  cancellation remains distinct from stop confirmation.

## Remaining review findings / Kotlin-specific decisions

- `ConnectedReviewExecutor.executeManually` was roughly 170 lines mixing admission,
  inference, costs, validation and publication. It now keeps admission in about 40
  lines and composes `ConnectedReviewExecutionSession`: a confined per-call loop
  with separate prepare/receive/budget/current-state/validate-publish operations.
  Existing ordinary/hosted/correction/deadline behavior is covered by the original
  execution suites, not just new helper tests.
- Normal dispatch delegates its connected-review routes to `ConnectedReviewRoutes`
  **after** its unchanged authorization/CSRF boundary and **inside** its unchanged
  safe-error boundary. Unrelated feature dispatch remains long deliberately: moving
  private imports, scheduling or existing user work would exceed this scoped audit.
- Immutable scalar model/policy DTOs use data-class structural equality; validation
  runs on decoding and `copy`. Data classes containing nested collection interfaces
  are not deeply immutable: repository boundaries use serialization/fresh decoding.
  No mutable packet serves as a hash-map key. This avoids Java builders/clone
  patterns without pretending Kotlin `val` prevents alias mutation.
- Store operations and close share an instance monitor; SQLite `IMMEDIATE`
  transactions protect inter-instance writes. Concurrent claim/submit and terminal
  races are tested. Monitors never span HTTP or process waits. Worker/process
  ownership uses a kernel lifetime lock and durable epochs, with single actor-owned
  phase state and atomic active/diagnostic state. API writes remain concurrent.
- Visibility is narrowed for constructor seams, SQL/database helpers, launcher
  configuration and runtime tokens. Existing public interfaces/wire strings remain
  compatible; new task outcome/execution/retention dimensions are typed enums.
- Attempt observation is best-effort metadata, not durable ownership or publication
  authorization. Exceptions are withheld; cancellation propagates. Callback timing
  and resource supervision must be audited again when the worker is integrated.
- This is a concrete scoped audit, not blanket Effective Java compliance. The
  runtime/worker/UI findings and remaining justified exceptions follow below.

## Review constraints

- Minimize visibility and mutable state; prefer composition and validated values.
- Shorten methods by responsibility, not arbitrary line-count compliance.
- Keep SQL in its owning repository, parameter-bind values and tightly control
  identifiers. An ORM or a giant unrelated query bucket is not a quality fix.
- Never include prompts, rejected text, credentials, SQL text or raw database
  failures in logs/diagnostics. Resource/safety failures must remain fail-closed.
- Preserve existing user changes and production gates. Document remaining long
  methods/queries and Kotlin-specific exceptions instead of declaring blanket
  compliance.
- Final acceptance requires reviewing all touched/new code and rerunning focused
  and integration checks; the checklist tasks remain the source of completion.
## Ownership/health follow-up

Task 4.2/4.3 implementation keeps owner and health SQL/commands out of routes;
bound claims use short store transactions, while node/network observations never
hold the repository monitor. Specific cancellation propagates, SSH has fixed
validated operations and no inherited agent/configuration, and owned command IO
closes streams and performs bounded cancellation cleanup. Shared node-command IO
and a focused stream-drain helper avoid duplicating magic commands/read loops.
The protected-app baseline defensively copies its collection into an unmodifiable
snapshot, with mutation and restart-laundering regression tests. Gate permission
also rechecks the authoritative durable claim rather than trusting an observer
token alone. This is a concrete follow-up, not completion of the pending whole
real-runtime stop/recovery acceptance.

## Final scoped review: findings and decisions

| Area / applicable principle | Concrete finding and response | Regression evidence |
| --- | --- | --- |
| Defensive copying / deep immutability | Kotlin read-only collections are not immutable. Persist packet/config/prompt JSON and decode fresh values; protected-app baseline copies into an unmodifiable sorted list. Summary input IDs/reasons are copied, bounded and serialized before returning. React owns parsed response state without persisting packets. | Nine-case deep-copy/reopen tests, baseline alias-mutation tests, summary bounds and independent fresh reads |
| Validated construction / equality | Scalar model/policy/node/claim identities validate in constructors and on data-class `copy`; hash keys are immutable strings or scalar selections, never mutable packets. Runtime handles validate keeper identity. Receipts/node proofs have private constructors and exact identity decoding; numeric receipt fields cannot be quoted strings/booleans. | Config/binding mismatch, wrong keeper/ticks/generation, forged stop mechanism, node-proof and invalid process-budget tests |
| Minimal visibility / capability boundaries | SQL/ownership/runtime helpers, worker assembly and constructors are internal/private. Public compatibility interfaces remain public. The extended policy is rejected by production service and hosted execution. Separate frontend entry point is never imported by personal `App`; visibility does not configure the worker. | Normal-app absence, catalogue/production gate, hosted-policy, visibility/no-write tests |
| Responsibility / small cohesive operations | Worker draining now separates reconcile, event disposition, submitted-task admission and active cancellation. Database binding/row mapping/transaction ownership remains central; every new statement lives in named repository SQL. HTTP routes do not contain SQL, process waits or inference. UI separates read-only single-flight hook, actions, run details and diagnostics. | Existing and new route/store/worker/correction tests; adversarial SQL fields remain parameters |
| Deterministic resource ownership | Provider owns/closes HTTP clients; per-run health source owns/closes runtime-reader clients; observers/watchdogs are cancelled; shutdown joins cleanup before store close; Unix socket/selector, SSH pipes and lifetime file lock use bounded scopes. Stop is attempted even if request-close/storage fails; a lost response can recover the exact same supervisor receipt. | Provider cancellation, source/command cleanup, storage-failure physical-stop, caller cancellation, keeper/worker failure and second-JVM lock tests |
| Thread-safety / independent transitions | Store monitor covers methods/close; `IMMEDIATE` transactions protect cross-connection cancellation/publication/deletion/claim. No database monitor spans process/network wait. Health-to-store publication lock ordering has no reverse store-to-health path. Shutdown fences active output before cancellation. Worker phase collections are coroutine-confined. | Cross-connection terminal/deletion races; FIFO/nonoverlap, preserved predecessor, maintenance serialization and same-owner stop retry |
| Typed states / safe failures | New outcome/execution/retention/attempt/causal reasons are enums. Compatibility APIs keep old wire strings. Unknown provider/SQL/command failures map to fixed reasons, not raw exception messages; diagnostic UUIDs and trigger reasons are allowlisted. | Sensitive-marker, unknown-reason, strict-schema and independent status tests |
| Bounded work / reliable persistence | Eight-pair deletion batches and small safety/maintenance reserves avoid unbounded phase loops. Four summaries per event/128 globally plus TTL prevent diagnostics growth. A durable event is not acknowledged away while its queued work is blocked; notifications alone never grant work. | Lost-notification, idle startup/no sweep, maintenance-at-capacity, expiry, deletion-failure and summary-retention tests |

### Justified Kotlin-specific exceptions and residual risks

- Data-class DTOs containing nested lists are transport values, not immutable value
  objects or hash-map keys. Fresh decoding/serialization at repository boundaries
  provides ownership isolation; cloning every DTO or adding Java builders would not
  improve this boundary. Internal provider objects remain trusted code implementing
  immutable scalar identity contracts; they are not accepted from JSON.
- `ValidatedConnectedReviewOutput` retains its existing internal constructor as a
  trusted-module compatibility capability; inference issuance remains exclusively
  after whole-draft validation. It is not decoded from a browser or used to bypass
  validator/qualification rules.
- The session's explicit lifecycle state is confined to one execution coroutine;
  making each field atomic would obscure ownership rather than improve safety.
  Active state shared with APIs uses atomic/volatile fields. Database transitions,
  not these fields, authorize publication or execution.
- The normal `Application.module` still dispatches unrelated existing features and
  retains the existing scheduler. Only shared security and connected-review routing
  were extracted. No unrelated `HistoryStore` SQL or personal frontend files were
  rewritten merely for method length. Named SQL declarations can be long when the
  atomic predicate itself is the responsibility; no ORM/new dependency is justified.
- Browser GET polling is read-only and single-flight; it is not a worker TTL timer.
  Historical health samples are labeled observational/stale, never process-stop or
  permission evidence. Attempt start/update UTC timestamps are inspection metadata;
  active deadlines remain monotonic and claim-origin/TTL-clamped.
- A closed/hung storage boundary can prevent durable confirmation even after physical
  stop. The safe result is a persistent execution block, not presumed success.
  Real Ollama/CRI boundary recovery still needs separately approved smoke testing.
- Tiny real-time legacy deadline tests and an existing `SessionPanel` effect timing
  assertion have intermittently failed under load. Their assertions were not weakened
  or removed. Record full-run results and serial reruns explicitly; mock acceptance
  does not establish qualification or real-runtime termination.
