import type { Diagnostics } from './development-types'

const reasons: Record<string, string> = {
  EXECUTED: 'An eligible synthetic run executed.', EXPIRED_BEFORE_CLAIM: 'Expired input was not executed.',
  QUEUED_INPUT_SUPERSEDED: 'Only matching queued older input was superseded.',
  RUNNING_PREDECESSOR_PRESERVED: 'The running predecessor continued on its original input.',
  RUNTIME_STOP_UNCONFIRMED: 'Prior runtime stop is unconfirmed; new inference is blocked.',
  CLEANUP_PENDING: 'Physical deletion is pending.', CLEANUP_FAILED: 'Payload deletion failed and awaits a later event.',
  MAINTENANCE_COMPLETED: 'Maintenance phases ran without inference.', EXECUTION_REFUSED: 'Execution was refused.',
  PAYLOAD_DELETED: 'Due payload was deleted.', EVENT_RECONCILED: 'A durable transition was reconciled.',
}

export function DevelopmentDiagnostics({ diagnostics }: { diagnostics: Diagnostics }) {
  return <section aria-label="Worker diagnostics"><h3>Worker / runtime health</h3>
    <p>{diagnostics.worker.reason.replaceAll('_', ' ')} · unresolved execution boundaries: {diagnostics.worker.unresolvedBoundaries}</p>
    {diagnostics.worker.blocked && <p role="status">Execution blocked: runtime stop unconfirmed. Bounded submissions remain available.</p>}
    <p>Whole-appliance ceiling: {diagnostics.worker.applianceMemoryLimitBytes === null ? 'not configured' : `${diagnostics.worker.applianceMemoryLimitBytes / 1024 ** 3} GiB (includes worker/JVM)`} · Minimum measured host headroom: {diagnostics.worker.minimumHostHeadroomBytes / 1024 ** 2} MiB.</p>
    {diagnostics.worker.health ? <p>Generation {diagnostics.worker.health.generation} · container {diagnostics.worker.health.containerId} · observed runtime ready {String(diagnostics.worker.health.runtimeReady)} · sample age {diagnostics.worker.health.oldestMeasurementAgeMillis / 1000}s{diagnostics.worker.health.oldestMeasurementAgeMillis > 70_000 && ' (stale; not current health)'} · available host memory {Math.floor(diagnostics.worker.health.availableHostBytes / 1024 ** 2)} MiB · observed appliance limit {diagnostics.worker.health.observedApplianceLimitBytes / 1024 ** 3} GiB. Observation is not permission or stop evidence.</p> : <p>No runtime health observation is available.</p>}
    <h3>Recorded worker actions</h3>{diagnostics.summaries.length ? <ul>{diagnostics.summaries.map(summary => <li key={`${summary.eventId}:${summary.createdUtc}`}>
      {summary.createdUtc} · {summary.trigger.replaceAll('_', ' ')} · deleted {summary.deleted} · executed {String(summary.executed)} · blocked {String(summary.blocked)}
      <ul>{summary.reasons.map(reason => <li key={reason}>{reasons[reason] ?? 'A fixed worker decision was recorded.'}</li>)}</ul>
      <p>Affected IDs: {summary.affectedIds.join(', ') || 'none'}</p>
    </li>)}</ul> : <p>No recorded worker actions.</p>}
  </section>
}
