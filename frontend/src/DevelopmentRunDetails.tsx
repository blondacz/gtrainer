import type { Claim, Model, Policy, Run } from './development-types'

export function DevelopmentBinding({ model, policy }: { model: Model; policy: Policy }) {
  return <dl><dt>Model / provider</dt><dd>{model.label} · {model.providerId}/{model.modelId}</dd>
    <dt>Runtime binding</dt><dd>{model.tag} · {model.manifestDigest} · Ollama {model.ollamaVersion}</dd>
    <dt>Generation settings</dt><dd>Context {model.contextWindow}; output tokens {model.tokenLimit}; threads {model.threads}; seed {model.seed}; temperature {model.temperature}</dd>
    <dt>Development budgets</dt><dd>{policy.attemptTimeoutMillis / 1000}s per attempt; {policy.totalTimeoutMillis / 1000}s total · {policy.profile}</dd>
  </dl>
}

export function DevelopmentRunDetails({ run }: { run: Run }) {
  const task = run.task
  const available = run.inspection.packetAvailable && Date.now() < Date.parse(task.expiresUtc)
  // Never decode an expired payload, even if a stale or malformed response still contains it.
  const packet = available ? JSON.parse(run.inspection.exactPrompt.userPacketJson) as {
    evidence: { facts?: { id: string }[] }; context: { contextId: string; revision: number; sourceCategory: string;
      authorAttribution: string; observedOn: string; content: string }[] } : null
  const elapsed = Math.max(0, Math.floor((Date.now() - Date.parse(task.createdUtc)) / 1000))
  function claim(item: Claim, index: number) {
    return <li key={index}><p>{item.text}</p>
      <p>Scope: {item.scope.from} to {item.scope.until} · {item.scope.sport ?? 'sport not specified'}</p>
      <p>Uncertainty: {item.uncertainty}</p><p>Evidence references: {item.sources.evidenceIds.join(', ') || 'none'}</p>
      <ul>{item.sources.context.map(ref => {
        const context = packet?.context.find(c => c.contextId === ref.contextId && c.revision === ref.revision)
        return <li key={`${ref.contextId}:${ref.revision}`}>{context ? `${context.sourceCategory.replaceAll('_', ' ')} · ${context.authorAttribution} · observed ${context.observedOn} · ${context.content}` : `Context ${ref.contextId}, revision ${ref.revision} unavailable.`}</li>
      })}</ul>
    </li>
  }
  return <article aria-label="Run details"><h3>Run {task.id}</h3>
    <p>Outcome: {task.outcome} · Execution: {task.execution} · Retention: {task.retention}</p>
    <p>{task.reason ? `Reason: ${task.reason.replaceAll('_', ' ')}` : 'No failure recorded.'}</p>
    {(task.execution === 'STOPPING' || task.execution === 'UNKNOWN') && <p role="status">Runtime stop is unconfirmed. Request cancellation is not process termination; queued inference remains blocked.</p>}
    {!task.latestInput && <p>This run uses older input. Its result is not the latest-input view.</p>}
    {task.replacementId && <p>Superseded by run {task.replacementId}.</p>}
    <dl><dt>Queue order / input revision</dt><dd>{task.queueOrder} / {task.inputRevision}</dd>
      <dt>Slot / input digest</dt><dd>{task.slotKey} / {task.inputDigest}</dd>
      <dt>Created / expires</dt><dd>{task.createdUtc} / {task.expiresUtc}</dd>
      <dt>Age since submission (includes queue wait)</dt><dd>{elapsed}s</dd></dl>
    <DevelopmentBinding model={task.model} policy={task.policy} />
    <h4>Attempts</h4>{run.attempts.length ? <ul>{run.attempts.map(attempt => <li key={attempt.number}>
      Attempt {attempt.number}: {attempt.phase} · started {attempt.createdUtc} · updated {attempt.updatedUtc ?? attempt.createdUtc}{attempt.reason && ` · ${attempt.reason.replaceAll('_', ' ')}`}
    </li>)}</ul> : <p>No attempt recorded.</p>}
    {!available ? <p role="status">Expired or removed payload is hidden. Physical deletion awaits a later queue event or explicit maintenance; inspection does not perform cleanup.</p> : <>
      <details><summary>Exact synthetic prompt</summary><pre>{run.inspection.exactPrompt.systemInstructions}</pre><pre>{run.inspection.exactPrompt.userPacketJson}</pre></details>
      <h4>Code-generated facts and attributed sources</h4><p>Evidence IDs: {packet?.evidence.facts?.map(fact => fact.id).join(', ') || 'none'}</p>
      <ul>{packet?.context.map(context => <li key={`${context.contextId}:${context.revision}`}>
        {context.sourceCategory.replaceAll('_', ' ')} · {context.authorAttribution} · {context.observedOn}: {context.content}
      </li>)}</ul>
      {run.inspection.review ? <>
        {run.inspection.review.interpretations.length === 0 && run.inspection.review.questions.length === 0 && <p>No connected insights were provided. Structural acceptance does not qualify this model.</p>}
        <h4>Validated interpretations</h4><ol>{run.inspection.review.interpretations.map(claim)}</ol>
        <h4>Uncertainty and unanswered questions</h4><ol>{run.inspection.review.questions.map(claim)}</ol>
      </> : <p>No validated output is published.</p>}
    </>}
  </article>
}
