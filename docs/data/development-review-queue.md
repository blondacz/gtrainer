# Development queue transitions

This is an isolated synthetic queue, not the production review/import queue.
`HistoryService.sync()` remains an explicit API operation, separate from inference.

- A hash-checked preview binds a case, trusted input revision/digest, exact prompt,
  explicit model/settings and policy budgets. Preview creation starts no task and
  writes no wake-up event. Unsubmitted previews expire 24 hours after creation.
- Explicit submission creates a distinct opaque run ID with its own immutable
  `createdAt` and `expiresAt = createdAt + 24h`. A preview/run pair occupies one of
  the 32 retention slots. Submitting an unexpired preview again returns its existing
  run, without another event, TTL reset or changed binding. An expired preview ID
  cannot recreate work. Submitted payload belongs to the run until its own expiry.
- Admission atomically inserts `QUEUED` plus a durable event. Queue order is a
  durable SQLite sequence. There is no observation or inference in admission.
- The slot hashes the case, provider/model IDs and contract. Only genuinely newer
  **trusted server-catalogue** revisions with different input digests supersede
  matching `QUEUED` work. Browser input cannot supply a revision or packet.
  Older/equal/identical inputs may be explicitly admitted but cannot evict newer
  input. Different cases/models remain independent. Test-only catalogues use
  synthetic copies; frozen benchmark files are never changed.
- Superseded rows keep the replacement link/reason. A running predecessor remains
  running and may publish its original bound output; `latestInput=false` prevents
  treating it as the newer-input result.
- Atomic claim permits one active execution boundary. Terminal publication/failure
  cannot overwrite a committed outcome. Explicit cancellation transactionally
  fences publication before worker signalling; already successful runs are not
  relabeled cancelled.
- Outcome, execution and retention are independent. Publication does not confirm
  process stop or release the execution boundary. An exact durable supervised
  receipt must do that. Expiry rejects claim/publication and hides payload;
  inspection does not delete or wake anything.

The elected event consumer, authoritative stop receipts, bounded maintenance signal,
event acknowledgement/cleanup and browser APIs are documented in
`development-review-worker.md`. Their default-disabled configuration does not grant
live deployment/inference approval or qualify a model.
