# Development connected-review execution budgets

The ordinary `ConnectedReviewExecutionLimits` policy remains the default: **300 seconds per attempt and 600 seconds total**, with configurable ceilings of 600/1,200 seconds. Its two-attempt, 32 KiB prompt, 12 KiB response and existing hosted-cost bounds are unchanged.

Only explicitly configured `DEVELOPMENT_LOCAL` execution may use profile `connected-review-development-local-v1`, capped at 900 seconds per attempt and 1,840 seconds total. Bind it at durable claim with `ConnectedReviewExecutionLimits.developmentLocal(remainingTtlMillis, attemptTimeoutMillis, totalTimeoutMillis)`: validate configured limits before clamping total to remaining creation-based TTL and attempt to that total. The one-argument factory explicitly chooses the maximum development budgets. Non-positive remaining TTL is rejected. Pass the monotonic durable-claim origin to the executor so startup/health overhead consume that same total budget; queue waiting is excluded. An exhausted deadline permits zero sends, and late replies cannot publish after expiry. Hosted execution is refused before any send; `ConnectedReviewService` accepts ordinary policy only.

The 1,840-second total is a ceiling, not a promise of two full 900-second attempts. Startup, health checks, validation and other execution overhead consume it; a correction can therefore receive less time or not start.

An optional response-free observer reports attempt start, correction, accepted,
rejected, failed/invalidated results and cancellation using fixed phases/reasons.
Its default is a no-op and ordinary callers remain compatible. Metadata callbacks
are best-effort telemetry, not publication permission, durable ownership or stop
evidence; the queue repository must persist its authoritative lifecycle itself.
Callback exceptions are withheld, while coroutine cancellation is preserved.
