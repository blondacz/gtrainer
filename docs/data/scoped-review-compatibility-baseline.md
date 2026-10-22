# Scoped-review compatibility baseline

> **Frozen internal calculation contract, not an HTTP compatibility guarantee.**
> Historical fixture and benchmark material below is retained unchanged. The
> consolidated current API is documented in
> [scoped reviews and comparisons](scoped-reviews-and-comparisons.md).

Task 1.1 records the pre-refactor boundary. This is synthetic-only compatibility
work: it makes no provider calls and does not qualify a model. The regression
test pins the distinct legacy profile IDs and SHA-256 hashes of cases V1/V2,
evaluations V1/V2/V3 and validator-parity V2 fixture bytes. It also pins actual
Kotlin packet serialization and prompt hashes for all nine V2 cases, plus a
nonempty synthetic factual-review/focus packet and the focus system prompt.
Fixture hashes are exact file bytes; Kotlin goldens use the existing default-
encoding serialization and do not recanonicalize historical Python requests.
Do not freeze all of `Trends` as part of this additive review work.

## Existing interface boundary

- Context: `AthleteContextRepository` exposes `contexts(today)`,
  `contextRevisions(id)`, `retrieveContexts(query)`, `createContext(request)`,
  `correctContext(id, request)`, `retireContext(id)`, `deleteContext(id)` and
  `recordReviewFeedback(snapshotId, request, observedOn)`; `retrieveAthleteContext` deterministically
  selects latest applicable revisions, keeps restrictions mandatory, and blocks
  on overflow, unstructured restrictions, or conflicts. `AthleteContext` carries
  stable `contextId` + `revision`, attribution, dates, scope, and restriction
  fields.
- Events: `ManualEventRepository.events/saveEvent/deleteEvent` and
  `ManualEventService.list/create/update/remove` own explicit date-only events;
  UTC date ordering and stable event IDs remain unchanged. These are not plans.
- Queue: existing scheduler routing persists `ReviewDue` intents; `ReviewQueueService`
  claims stored jobs and executes the legacy focus profile. Scheduling remains
  separate from manual connected review. No adaptation profile is wired into
  current queue execution, and its production runtime guard is unavailable.
  `requestNow(ReviewNowRequest)`, `status()`, `pump(CoroutineScope)` and
  `invalidate(reason)` remain its current lifecycle boundary. Status currently
  reconciles selection changes; task 6.3 must make those reads inert separately.
- Connected packet: `InterpretationPacketV1(profile, evidence, context)` and
  `InterpretationPromptV1(systemInstructions, userPacketJson)` are serialized by
  `InterpretationContractV1`; output citations bind evidence IDs and context
  ID/revisions. The executor has a qualification/health gate that defaults
  closed.

At baseline capture, `connected-insights-context` has **20 completed tasks out of
22**, with evaluation task **6.2** and independent human-review task **6.3** still
incomplete. **No candidate is qualified.** The historical progress snapshot is
not a runtime admission proof or permission to evaluate or infer.

## Dependency and synchronization order

Reuse the already implemented attributed-context/snapshot interfaces; add the
follow-on context lifecycle before new scoped evidence consumes it. Verify
existing legacy fixtures and packet behavior before adapter/refactoring work.
Event/queue interfaces remain stable and independent. Planning consumers follow
evidence and review boundaries and do not grant inference admission. Synchronize
the `connected-insights-context` athlete-context delta before this follow-on's
additive delta, without marking qualification complete. This baseline task does
not synchronize or archive either change.

Cleanup baseline: only the latest 39-row private scoring pack is retained;
older packs were removed, and no links to old packs should be reintroduced.

## Verification

Run from the repository root with the installed JDK 25 and cached dependencies:

```sh
JAVA_HOME=$(/usr/libexec/java_home -v 25) ./gradlew --offline --no-daemon \
  :backend:test --rerun --tests com.gtrainer.ScopedReviewLegacyBaselineTest \
  --tests com.gtrainer.InterpretationContractV1Test
```

Baseline values are captured constants, not expectations regenerated during
assertions. Ledger progress is a historical snapshot; verifying its current
checkboxes does not open qualification. No source/provider/runtime calls are
part of this verification.

Verified on 2026-10-06: 5 baseline tests and 13 V1 contract tests passed with
zero failures, errors or skipped cases. Six frozen fixture files also matched
committed HEAD byte-for-byte; the context ledger remained 20/22 with 6.2/6.3
unchecked. No inference, download, live migration or deployment was performed.
