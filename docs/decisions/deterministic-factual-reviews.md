# Deterministic factual reviews

After the complete Qwen3.5 fresh-suite run answered only 5/10 requested focuses
and corrections rescued none, the user approved continuing with deterministic
facts and focus selection. This implements that local application path without
another model experiment, live inference, release promotion or deployment.

## Factual view

The dashboard adds **Factual review — no AI** below the existing charts. It uses
the exact displayed trend-report digest and retains **every** comparison, not
the installed prototype's two-sport moving-time/sleep/HRV subset. All sports and
supported activity/wellness metrics remain visible, with their previous/current
values, units, dates, populated-record/date counts, provenance and flags.
Unavailable proprietary Garmin scores remain separately labeled unavailable in
the chart view; no score is estimated.

Groups have identical previous/current periods and explicit available, sparse or
unavailable comparison coverage. This coverage label refers to availability of
the arithmetic comparison and record counts, not complete imported history,
statistical confidence or source freshness. Two records per period are the
existing provisional support gate, not scientific trend qualification. Missing
values remain unknown; measured zeros and unchanged directions stay explicit.
Incompatible periods are never combined. All comparisons remain factual even
when the selected focus has no supported pattern.

Focus rules are descriptive application rules, not AI interpretation:

- **Combined activity and wellness:** connect populated moving-time and wellness
  comparisons only when the group has matching periods and at least two populated
  records per metric per period. Directions may oppose, stay unchanged or start
  at a measured zero. Do not substitute a wellness-only pattern.
- **Recorded time across sports:** cite moving time for at least two distinct
  supported sports in the same group; never equate time with effort or stimulus.
- **Wellness comparisons:** use supported populated wellness comparisons, or
  show wellness coverage gaps if none are supported.
- **Wellness coverage gaps:** show only wellness comparisons with unavailable
  values or sparse records; do not infer illness or missed activity.

The application shows all groups independently of the chosen focus. Model
selection, model failure and interpretation rejection do not change these facts.
The UI checks complete comparison/support coverage against the displayed report,
not merely schema/IDs. It withholds the whole factual review on a malformed,
incomplete, unrelated or mismatched response, while leaving verified charts
usable. Import/removal, displayed-period/sport changes, refresh and logout discard
old review content and abort outstanding fetches. Changing the factual focus
discards old focus content before the new response arrives.

## Private read-only API

`GET /api/review-facts` takes the existing `oldest`, `newest` and optional `sport`
query parameters, required `evidenceReportSha256` from the displayed private
`/api/trends` response, and optional `focus` (default `daily_combined`). Supported
focus values are `daily_combined`, `activity_balance`, `wellness`, and
`missing_wellness`. These are factual topics, not scheduled review presets.

The endpoint requires a valid user session, returns `Cache-Control: no-store`,
and rechecks the session before returning prepared facts. It uses the existing
report hash, including data/status/evaluation context. A mismatched digest returns
409 `evidence_changed` without factual payload. Invalid focus/hash/range input
returns 400; unauthenticated requests return 401. There are no storage writes,
upstream reads, model calls, automatic retries or provider fallbacks.

The response identifies `factual-review-v1` and `applicationGenerated: true`.
This is a new app-owned factual view, **not** the frozen synthetic
`prepared-focus-v1` model contract. It includes the full existing AnalysisInput
comparison set, whereas the model experiments used the smaller prototype packet.
Its deterministic rule coverage is not another model score or evidence of sports
expertise. Summary supports omit source-record IDs and point arrays; the matching
authenticated trend report still supplies full record evidence.

## Interpretation and completion boundary

The installed `/api/analysis` request, prompt, packet, validator, model-selection
behavior, 120/130-second bounds and **zero corrective retries** remain unchanged.
The optional experimental AI panel stays separately labeled and explicitly
requested; factual reviews do not enable it or replace rejected model output.
No model catalogue default, service, egress permission or automatic schedule is
added. Live inference stays off and the live Pi app/image is unchanged.

The initial continuation implemented the deterministic factual portion of task
**7.3** and left its optional runtime pending. The subsequent
[separate guarded interpretation runtime](guarded-review-interpretation.md) adds
an explicitly named protocol and independently validated bounded corrections;
it does not replace these rule-selected facts or change the installed prototype.
The later [preset/scheduler implementation](review-presets-and-scheduler.md)
completes task 7.4 locally. Queue/UI tasks 7.5/7.6 and model/hardware/physical-rendering
qualification remain outstanding. Further integration must preserve the existing
prototype contract unless an explicitly separate protocol is selected. No
requirement is narrowed or waived to claim production qualification.

Synthetic tests cover complete metrics/three sports, unavailable/unchanged/zero
comparisons, sparse support, incompatible periods, missing/malformed support,
requested-focus relevance, authenticated digest-bound API access, no model calls,
concurrent factual access during inference, model rejection/invalidation, whole
frontend response rejection, stale-response aborts, and independent charts.

Local verification passed **67 backend tests** and backend distribution build,
**73 frontend tests** and TypeScript/Vite production build, **121 benchmark tests**,
and **47 utility tests**. Strict OpenSpec validation, documentation links and diff
checks passed. Raw API assertions verify the factual profile and no-AI marker are
actually serialized, not restored by decoder defaults. Independent review found
no remaining issues after serialization, arithmetic and complete-provenance fixes.
All six earlier original-byte captures still reproduce exactly; the installed
analysis/model files and deployment/delivery configuration are unchanged.
