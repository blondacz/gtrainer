# Proposed guarded synthetic suggestion pilot

**Status: proposed only. Separate implementation approval required.** No app code,
runtime, provider selection, database, personal data or production gate changes
are made by this document or by the offline v5.1 checker correction.

## One usable behavior

In an isolated development harness, choose a bundled synthetic intake, inspect its
strictly validated unconfirmed suggestions, edit a suggestion, resolve and explicitly
acknowledge every unknown, stage the visible item and explicitly confirm it into a
disposable synthetic store. Inspection, editing and staging must not create an
authoritative context, commitment, calendar constraint or plan change.

Start with retained synthetic draft fixtures. This first pilot needs no model call,
new inference allowance or connection to the current application store. It verifies
the application safety/interaction boundary, not new extraction reliability.

## Existing boundaries to reuse

- `frontend/src/ContextSuggestionControls.tsx` performs read-only inspection bound
  to intake identity/revision and passes an explicit edit action to its parent.
  It already labels suggestions as unconfirmed and shows excerpts and unknowns.
- `frontend/src/ContextIntakeControls.tsx` opens an inert editable draft, requires
  unknown-field acknowledgment before staging and sends confirmation only through
  an explicit user action. Source changes discard unconfirmed state.
- `backend/src/main/kotlin/com/gtrainer/ContextIntakeSuggestionContractV1.kt` and
  the existing strict extraction validator remain the draft acceptance authority.
  The benchmark's case oracle is not a production validator or an auto-save rule.
- `ContextIntakeRoutes.kt` dispatches confirmation after existing authentication,
  origin and CSRF checks. `ContextConfirmation.kt` binds source revisions, rejects
  stale updates and uses atomic confirmation with idempotent receipt semantics.
- `ScopedProfileAdmission.kt` currently reports profiles unqualified and execution
  closed. The pilot must not change that state or forge published execution receipts
  to make fixture suggestions appear to be production model output.

Existing validation enforces wire structure, source binding and supported semantic
constraints; it does not independently establish the truth of every extracted fact.
Source display and deliberate user review remain necessary. Do not present a valid
draft as automatically correct.

## Scope and exclusions

- Use only bundled synthetic source/draft pairs in a test-only adapter and disposable
  store. Explicitly label the harness and its data as synthetic. Keep normal app
  behavior disabled/unchanged; no route into personal storage.
- Accept only the exact source identity/revision after strict wire, excerpt binding
  and semantic validation. Show the full excerpt and uncertainty to the user.
- Wrong or stale source, invalid draft, unresolved ambiguity or unacknowledged
  unknowns block staging/confirmation. Keep manual review available; never repair
  malformed drafts, invent dates or silently pick recurrence defaults.
- Authoritative writes require a separate explicit confirmation action for visible
  selected items. Preserve server-side validation, revision conflicts, atomicity and
  idempotency. Inspection or retrying a page load never confirms anything.
- No live extraction, provider/runtime admission, app deployment, app-data migration,
  existing database reset, personal input, auto-confirmation, plan adaptation or
  production qualification. Any later live-extractor pilot needs its own bounded
  scope and runtime admission, not this document's permission.

## Acceptance checks before considering a live pilot

1. Read-only inspection and repeated page loads issue no inference or confirmation
   requests and produce no authoritative writes.
2. Clicking Edit creates only an unconfirmed draft. Staging remains blocked until
   every unknown has been deliberately resolved and acknowledged; editing/staging
   alone still writes no authoritative item.
3. A wrong source ID/revision, changed excerpt, malformed draft, source-unsupported date,
   contradictory unknown/open mode or stale edit is rejected without partial saves.
4. Only explicit confirmation of visible selected items reaches the confirmation
   endpoint. Successful confirmation is atomic; repeating the same confirmation
   identity/body creates no duplicate, while conflicting reuse is rejected.
5. Source correction discards stale drafts. Uncertain network outcomes show an
   unknown state and require reload/inspection, never a hidden automatic retry.
6. Tests prove synthetic-store isolation, authentication/origin/CSRF behavior,
   content-free logs and unchanged production/runtime admission.

Run focused frontend journey and backend validation/confirmation tests. Record the
actual UI behavior and defects. Do not infer extraction quality from fixture replay.

## Evidence and decision boundary

The retained acquisition remains **7/8 under prospective frozen v5**. The separate
user-authorized post-hoc v5.1 wording correction scores **8/8 on the same outputs**,
with no new inference. This justifies inspecting a guarded interaction experiment,
not overriding the original readiness gate or claiming fresh qualification.

This proposal does not authorize its implementation. Review and approve the bounded
synthetic pilot separately before editing application code or starting any service.
