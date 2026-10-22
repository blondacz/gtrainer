---
description: Review one slice for concrete defects and missing checks
subagent: false
---

Review the current vertical slice: $ARGUMENTS

Follow AGENTS.md and model-routing. Establish the exact slice paths and acceptance
checks from current context; inspect only relevant changes and dependencies.
Distinguish pre-existing unrelated work. Do not edit files during this review.

Check correctness, privacy, stale-state fences, approval boundaries, and missing
tests as applicable. Report concrete findings with severity, file/line evidence,
and a minimal correction. Separate defects from optional improvements. Do not
reopen settled architecture without conflicting evidence.

Use at most one independent review per slice, within its total two-child budget;
reviewers and retries count. Reuse a completed independent review rather than
launching another. Small low-risk work may be reviewed directly. The primary
session must verify every recommendation against files and check results.

Return findings first, then checks and residual risks. If no defects are found,
say so without claiming unrun checks passed. This review authorizes no commits,
pushes, inference, downloads, live operations, publication, or deployment.
