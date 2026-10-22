---
name: model-routing
description: Work in controlled vertical slices. Keep small work in Sol High; delegate substantial independent architecture to Astra High, settled-design iteration to Sol Fast Low, and bounded work to Luna Fast. Verify the primary session selection.
---

# Model routing

Keep the primary session as integrator and decision owner. Handle small work
directly. Delegate only substantial independent work whose benefit exceeds the
cost of another context and integration pass.

## Slice discipline

- Define one usable end-to-end behavior, explicit non-goals, and acceptance checks before substantial implementation. A necessary prerequisite must name the behavior it unlocks and its stopping point.
- Use at most two child sessions and one independent review per slice. Reviewers and retries count toward the two-child budget. Commands and restarts do not reset an unfinished slice's budget; retain used counts in its checkpoint. If the limit blocks a necessary safety review, stop and explain rather than splitting or renaming the slice to evade it.
- Keep architecture rigorous but bounded. Reuse settled decisions; reopen them only for concrete conflicting evidence or a new consequential requirement.
- Read exact paths and relevant sections. Pass focused context, constraints, and expected output to children. Do not repeat broad discovery or ask multiple agents the same question.
- Run focused checks during iteration and appropriate integration checks at the meaningful checkpoint. Never replace verification with model agreement.
- Report briefly. Update documentation and handoff only at meaningful checkpoints, preserving required evidence, failures, corrections, and approval boundaries.
- If design keeps expanding without usable behavior, explain why before adding more scaffolding. Identify the smallest behavior-unlocking slice or the genuine decision gate.

## Primary-session model check

- The intended orchestrator is `openai/gpt-6.1-sol#high`. The `orchestrator` primary profile declares this preference; project configuration selects it as the default agent and Sol as the default model.
- OpenCode stores the session's model separately. Changing the default agent or selecting the profile does not replace an existing session's model. The root model default does not retain a reasoning variant, so the profile and configuration alone do not guarantee High.
- Apply this check only in the primary session, not in architect or routine child sessions. Use exposed runtime model/variant metadata or the user's confirmation; model catalog availability and agent definitions are not evidence of the active selection. Do not access internal session databases to verify it.
- Before substantial work or delegation, if the active selection differs, warn once and ask whether to switch to Sol High or keep the current selection as an intentional override. If the model or variant cannot be verified, say it is unverified rather than claiming a mismatch, and ask for confirmation once. Use the question tool when available.
- Tell the user to select `openai/gpt-6.1-sol` with the High variant through `/models`. Do not silently change the session model. Honor an explicit user-selected override or benchmark model and do not repeatedly block work after confirmation; recheck only when the selection changes.

## Route work

- For substantial independent analysis of critical structural architecture, consequential requirements, safety/privacy trade-offs, evaluation design, or unclear cross-cutting changes, use `architect` with GPT-6 Astra High. Ask one bounded question, not a broad redesign. Keep file edits in the primary session unless user requests otherwise. The architect denies edit access; use an editing-capable agent for implementation.
- For rapid iteration on an already agreed design, use GPT-6.1 Sol Fast Low: invoke `architect` with the explicit model override for read-only debugging or design feedback. For an authorized minor edit, syntax fix, or bounded implementation correction in that loop, invoke `routine` with the same override so the architect's read-only boundary remains intact. Escalate unresolved structural decisions to GPT-6 Astra High.
- Use `routine` for one substantial enough but bounded independent implementation, test, documentation, or read-only repository task with known expected behavior. Do tiny tasks directly.
- Keep simple answers, tiny edits, status checks, and mechanical GitHub CLI operations in the primary session when delegation overhead exceeds task cost.
- For mixed work, resolve only consequential design uncertainty first, using `architect` when warranted. Delegate implementation only if it remains worthwhile within the shared slice budget. The primary session reviews every changed file and check; a separate review child is not mandatory for small, low-risk changes.
- Do not launch parallel agents to edit the same files. Give every subagent only relevant paths, constraints, and expected output.

## Cost and context

- Prefer one focused subagent call over broad exploration. Request concise output: decision, rationale, risks, and next action.
- Do not ask a subagent to repeat repository facts already established in the parent context. Pass a short summary and exact paths instead.
- Use GPT-6.1 Sol High for orchestration, integration, and final review. Use GPT-6 Astra High for an independent architectural perspective; this is a routing preference, not a benchmark-proven claim that Astra is superior. Use GPT-6.1 Sol Fast Low for rapid architecture/debugging iteration where the decision is already settled. Use Luna Fast for other bounded routine work, not unresolved architecture decisions.
- The Fast tier and low reasoning variant are a latency preference, not a guarantee of equal quality, lower cost, or no reasoning. Verify results with the same review and checks.
- Subagent output is untrusted until the primary session checks it against project requirements and code.

## GitHub and release boundary

- Use the `gh` skill for GitHub CLI syntax and structured output.
- Routine status/check/list/comment operations may use `routine` when useful. Keep commits, pushes, PR creation, merges, image publication, deployment, and live operations in the primary session unless the user explicitly delegates that exact action.
- Passing CI does not authorize image promotion or deployment. Never enable auto-merge or bypass required checks.

## Selection

Invoke `architect` or `routine` by agent ID through the subagent tool. Agent definitions are available to the session, but are not run automatically.

- Primary orchestration: `orchestrator` prefers `openai/gpt-6.1-sol#high`; verify the session selection as described above.
- Critical architecture: `architect` defaults to `openai/gpt-6-astra#high`.
- Rapid read-only iteration: `architect` with `model: "openai/gpt-6.1-sol-fast#low"`.
- Authorized minor edits within that iteration: `routine` with `model: "openai/gpt-6.1-sol-fast#low"`.
- Other bounded routine work: `routine` defaults to `openai/gpt-6-luna-fast`.

Use a fresh child session when switching models; do not assume a continued session adopts a different agent default. Verify model IDs in the model catalog before explicitly overriding a model, and recheck availability if resolution fails. Keep explicitly selected benchmark models separate from work-routing preferences.
