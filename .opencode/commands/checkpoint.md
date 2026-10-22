---
description: Save compact continuation state at a meaningful checkpoint
subagent: false
---

Record a meaningful checkpoint for: $ARGUMENTS

Follow AGENTS.md. Use current context and narrow status reads, not a full history
reload or repeated broad review. Keep .opencode/SESSION-HANDOFF.md short (target
400 words or fewer): active task/context pointer, current blocker/next action,
verification status, outstanding processes/children, used child/review budget and
approval boundaries. Put detailed commands/results and failures/corrections in
the relevant task record and link its section; do not repeat completed history.
Never meet the length target by omitting a safety-critical fact.

Treat recorded checks as historical evidence, not proof that later files pass.
Preserve links to frozen evidence and qualification history; do not duplicate
large logs or erase failures and unrun cases. Update other documentation only if
the checkpoint changes a public contract or required verification record.

Keep AGENTS.md and the handoff local-only and Git-excluded. Preserve unrelated
work and private data. Do not stage, commit, push, reset/migrate a database, run
inference, download, publish, deploy, or perform live operations. Report the
checkpoint path and next action briefly.
