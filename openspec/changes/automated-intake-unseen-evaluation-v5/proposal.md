# Proposal

## Why

The existing eight responses pass only after explicitly post-hoc v4.1/v4.2 checker
changes. A prospective evaluation on fresh synthetic inputs is needed before
considering an app-integration pilot; the current runner and Kotlin bridge bind
the historical packets and cannot honestly label a replay as unseen evaluation.

## What Changes

- Freeze eight new, distinct synthetic packets covering paraphrases, activity
  ambiguity, corrections and missing details before any candidate inference.
- Preserve the selected prompt, five demonstrations, provider schema, model and
  resource envelope. Apply v4.2 semantics prospectively: strict typed/extractive
  predicates, case-insensitive commitment labels and finite contextual question
  alternatives declared before outputs, not fuzzy matching.
- Add an explicit, source-bound choice between the historical v4 suite and fresh
  v5 suite in the existing durable runner. Reuse its supervisor and owned runtime;
  do not build another scheduler or modify manifest-pinned historical files.
- Add a private packet-aware offline Kotlin bridge calling unchanged strict
  validation. Prove the new path with actual fixtures and a fake durable batch
  before admitting live execution.
- After a separate apply instruction and all gates, permit one newly recorded
  four-hour evaluation: one batch, eight scored launches and one stop probe, with
  no retries, revisions or replacement root. Preserve all failed/unrun evidence.
- Report the actual complete pass, failure or incomplete result and whether it
  supports considering a separately authorized integration pilot. Production
  admission remains closed regardless of score.

## Capabilities

### New Capabilities

- `intake-extraction-evaluation`: Extend the capability introduced by the pending
  v4 change with prospectively frozen fresh-suite evaluation and suite-bound
  execution. Use the same capability path, not a parallel version-named capability.

### Modified Capabilities

None in the published main specs. The connected-review development API, its queue,
personal-data boundaries and validation/correction policy are not changed.

## Impact

New isolated benchmark code/data/tests under `benchmarks/intake-extraction-v5/`,
a new test-only Kotlin packet bridge, and narrow non-frozen operational seams in
the existing revision/runtime/campaign/lifecycle/supervisor path and Mac profile
allowlist. Legacy v4 behavior must remain unchanged and regression-tested.

No new dependencies, downloads, database migrations, application endpoints, data
access, deployment, VM/service restart or resize, model fallback, git operations,
publication, spec sync or archive. All old source freezes, reports and terminal
roots remain intact. Planning does not start model execution; OpenSpec requires
a separate apply step after these artifacts are presented.
