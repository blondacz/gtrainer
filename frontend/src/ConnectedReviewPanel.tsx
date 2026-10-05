import { useState } from 'react'

type Selection = { providerId: string; modelId: string; providerKind: 'local' | 'hosted' }
type Status = { snapshotId: string; packetDigest: string; evidenceDigest: string; context: { contextId: string; revision: number }[];
  coverageFrom: string; coverageUntil: string; sport: string | null; provider: string; model: string; contractVersion: string;
  state: string; staleReason: string | null; createdUtc: string; publishedOutput: string | null }
type Claim = { text: string; sources: { evidenceIds: string[]; context: { contextId: string; revision: number }[] };
  scope: { from: string; until: string; sport: string | null }; uncertainty: string }
type Packet = { profile: string; evidence: { evidenceReportSha256: string; selectedSport: string | null; [key: string]: unknown };
  context: { contextId: string; revision: number; category: string; sourceCategory: string; authorAttribution: string;
    observedOn: string; content: string; restrictionKind: string | null; restrictionValue: string | null; restrictionUnit: string | null }[] }
type Inspection = { status: Status; providerSelection: Selection; exactPrompt: { systemInstructions: string; userPacketJson: string };
  review: { profile: string; interpretations: Claim[]; questions: Claim[] } | null; packetAvailable: boolean }
type Conflict = { first: { contextId: string; revision: number }; second: { contextId: string; revision: number }; reason: string }
type Retrieval = { mandatoryRestrictions: Packet['context']; optionalContexts: Packet['context']; conflicts: Conflict[];
  blockedReasons: string[]; reviewBlocked: boolean }

const object = (value: unknown): value is Record<string, unknown> => !!value && typeof value === 'object' && !Array.isArray(value)
function inspectionFrom(value: unknown): Inspection {
  if (!object(value) || !object(value.status) || typeof value.status.snapshotId !== 'string' ||
      typeof value.status.state !== 'string' || typeof value.status.packetDigest !== 'string' ||
      !object(value.providerSelection) || typeof value.exactPrompt !== 'object' || value.exactPrompt === null ||
      typeof (value.exactPrompt as Record<string, unknown>).userPacketJson !== 'string' || typeof value.packetAvailable !== 'boolean')
    throw new Error('Invalid connected review')
  const body = value as unknown as Inspection
  if (body.packetAvailable) {
    const packet: unknown = JSON.parse(body.exactPrompt.userPacketJson)
    if (!object(packet) || !object(packet.evidence) || !Array.isArray(packet.context)) throw new Error('Invalid review packet')
    if (body.review && (!Array.isArray(body.review.interpretations) || !Array.isArray(body.review.questions))) throw new Error('Invalid review output')
  }
  return body
}
function retrievalFrom(value: unknown): Retrieval {
  if (!object(value) || !Array.isArray(value.conflicts) || !Array.isArray(value.mandatoryRestrictions) ||
      !Array.isArray(value.optionalContexts) || !Array.isArray(value.blockedReasons) || typeof value.reviewBlocked !== 'boolean')
    throw new Error('Invalid restriction retrieval')
  return value as unknown as Retrieval
}
const safeError = 'The connected review or restriction context is unavailable. Check the ID, date, and filters.'
const sourceName: Record<string, string> = { user_report: 'User report', clinician_guidance: 'User-entered clinician guidance',
  coach_guidance: 'User-entered coach guidance', review_feedback: 'Review feedback' }

/** Displays only code-validated connected snapshots; no inference is started by inspection. */
export function ConnectedReviewPanel() {
  const [snapshotId, setSnapshotId] = useState('')
  const [inspection, setInspection] = useState<Inspection | null>(null)
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const [asOf, setAsOf] = useState(new Date().toISOString().slice(0, 10))
  const [sport, setSport] = useState('')
  const [retrieval, setRetrieval] = useState<Retrieval | null>(null)

  async function inspect() {
    setBusy(true); setError(''); setInspection(null)
    try {
      if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(snapshotId)) throw new Error(safeError)
      const response = await fetch(`/api/connected-reviews/${snapshotId}`, { credentials: 'same-origin', cache: 'no-store' })
      if (!response.ok) {
        if (response.status === 404) throw new Error('This review snapshot is unavailable or was removed with deleted context. Factual dashboard data remains available.')
        throw new Error(safeError)
      }
      const parsed = inspectionFrom(await response.json())
      setInspection(parsed)
      setRetrieval(null)
    } catch (cause) { setError(cause instanceof Error ? cause.message : safeError) }
    finally { setBusy(false) }
  }

  async function inspectConflicts() {
    setBusy(true); setError('')
    try {
      if (!/^\d{4}-\d{2}-\d{2}$/.test(asOf) || !Number.isFinite(Date.parse(`${asOf}T00:00:00Z`))) throw new Error(safeError)
      const params = new URLSearchParams({ asOf, optionalLimit: '0' })
      if (sport.trim()) params.set('sport', sport.trim())
      const response = await fetch(`/api/context-retrieval?${params}`, { credentials: 'same-origin', cache: 'no-store' })
      if (!response.ok) throw new Error(safeError)
      setRetrieval(retrievalFrom(await response.json()))
    } catch (cause) { setError(cause instanceof Error ? cause.message : safeError) }
    finally { setBusy(false) }
  }

  const packet: Packet | null = inspection?.packetAvailable ? JSON.parse(inspection.exactPrompt.userPacketJson) as Packet : null
  const contextByRevision = new Map(packet?.context.map(item => [`${item.contextId}:${item.revision}`, item]) ?? [])
  const evidenceIds = packet?.evidence && Array.isArray((packet.evidence as Record<string, unknown>).facts)
    ? ((packet.evidence as Record<string, unknown>).facts as { id?: string }[]).map(item => item.id).filter((id): id is string => typeof id === 'string')
    : []

  function renderClaim(claim: Claim, kind: 'Interpretation' | 'Question', index: number) {
    return <li key={`${kind}-${index}`}>
      <p><strong>{claim.text}</strong></p>
      <p>Scope: {claim.scope.from} to {claim.scope.until}; {claim.scope.sport ?? 'sport not specified'} · Uncertainty: {claim.uncertainty}</p>
      <p>Evidence references: {claim.sources.evidenceIds.length ? claim.sources.evidenceIds.join(', ') : 'none'}</p>
      <ul>{claim.sources.context.map(ref => {
        const item = contextByRevision.get(`${ref.contextId}:${ref.revision}`)
        return <li key={`${ref.contextId}:${ref.revision}`}>{item ? `${sourceName[item.sourceCategory] ?? item.sourceCategory} · ${item.observedOn} · ${item.content}` :
          `Context ${ref.contextId}, revision ${ref.revision} is unavailable or removed.`}</li>
      })}</ul>
    </li>
  }

  return <section aria-labelledby="connected-review-heading">
    <h2 id="connected-review-heading">Connected review evidence</h2>
    <p>Inspection does not call a model. Measurements and provenance are code-generated; user reports are attributed and unverified; interpretations remain uncertain.</p>
    <form className="context-form" onSubmit={event => { event.preventDefault(); void inspect() }}>
      <label>Review snapshot ID<input required maxLength={36} value={snapshotId} onChange={event => setSnapshotId(event.target.value)} /></label>
      <button type="submit" disabled={busy}>{busy ? 'Loading…' : 'Inspect connected review'}</button>
    </form>
    {error && <p role="alert" className="access-message">{error}</p>}
    {inspection && <article className="review-snapshot">
      <h3>Snapshot status: {inspection.status.state}</h3>
      {inspection.status.staleReason && <p role="status">Stale/unavailable reason: {inspection.status.staleReason}</p>}
      <dl><dt>Snapshot</dt><dd>{inspection.status.snapshotId}</dd><dt>Created</dt><dd>{inspection.status.createdUtc}</dd>
        <dt>Provider / model</dt><dd>{inspection.status.provider} / {inspection.status.model}</dd>
        <dt>Contract</dt><dd>{inspection.status.contractVersion}</dd>
        <dt>Coverage</dt><dd>{inspection.status.coverageFrom} to {inspection.status.coverageUntil} · {inspection.status.sport ?? 'sport unspecified'}</dd>
        <dt>Evidence digest</dt><dd>{inspection.status.evidenceDigest}</dd><dt>Packet digest</dt><dd>{inspection.status.packetDigest}</dd></dl>
      {!inspection.packetAvailable || !packet ? <p role="status">The packet was removed or is no longer available. The factual dashboard remains available.</p> : <>
        <section><h4>Code-generated facts and provenance</h4><p>Evidence report: {packet.evidence.evidenceReportSha256}</p>
          <p>Evidence identifiers: {evidenceIds.length ? evidenceIds.join(', ') : 'No fact identifiers in this packet.'}</p>
          <details><summary>Inspect complete factual packet</summary><pre>{JSON.stringify(packet.evidence, null, 2)}</pre></details></section>
        <section><h4>Attributed reports and restrictions</h4>{packet.context.length === 0 ? <p>No context entries were included.</p> : <ul>
          {packet.context.map(item => <li key={`${item.contextId}:${item.revision}`}>
            <strong>{sourceName[item.sourceCategory] ?? item.sourceCategory}</strong> · {item.category} · {item.authorAttribution} · observed {item.observedOn}
            {item.restrictionKind && <p>Structured restriction: {item.restrictionKind.replaceAll('_', ' ')} {item.restrictionValue}{item.restrictionUnit ? ` ${item.restrictionUnit}` : ''}</p>}
            <p>{item.content}</p><small>Context {item.contextId}, revision {item.revision}</small>
          </li>)}
        </ul>}</section>
        <section><h4>Model interpretations</h4>{inspection.review?.interpretations.length ? <ol>
          {inspection.review.interpretations.map((claim, index) => renderClaim(claim, 'Interpretation', index))}
        </ol> : <p>No published interpretations.</p>}</section>
        <section><h4>Uncertainty and unanswered questions</h4>{inspection.review?.questions.length ? <ol>
          {inspection.review.questions.map((claim, index) => renderClaim(claim, 'Question', index))}
        </ol> : <p>No unanswered questions were returned.</p>}</section>
      </>}
    </article>}
    <section className="context-form" aria-labelledby="restriction-conflicts-heading">
      <h3 id="restriction-conflicts-heading">Restriction conflict pane</h3>
      <p>Conflicting applicable structured restrictions block connected review; neither assertion is silently preferred.</p>
      <div className="context-actions">
        <label>Applicable on<input type="date" value={asOf} onChange={event => setAsOf(event.target.value)} /></label>
        <label>Sport (optional)<input maxLength={80} value={sport} onChange={event => setSport(event.target.value)} /></label>
        <button type="button" disabled={busy} onClick={() => void inspectConflicts()}>Check restrictions</button>
      </div>
      {retrieval && (retrieval.conflicts.length ? <ul aria-label="Unresolved restriction conflicts">
        {retrieval.conflicts.map(conflict => <li key={`${conflict.first.contextId}:${conflict.second.contextId}`}>
          <strong>{conflict.reason.replaceAll('_', ' ')}</strong>
          {[conflict.first, conflict.second].map(ref => {
            const entry = [...retrieval.mandatoryRestrictions, ...retrieval.optionalContexts]
              .find(item => item.contextId === ref.contextId && item.revision === ref.revision)
            return <p key={ref.contextId}>{entry ? `${sourceName[entry.sourceCategory] ?? entry.sourceCategory} · ${entry.authorAttribution} · observed ${entry.observedOn} · ${entry.content}` :
              `Context ${ref.contextId}, revision ${ref.revision} is unavailable or removed.`}</p>
          })}
        </li>)}
      </ul> : <p>No applicable restriction conflicts for this date and sport.</p>)}
      {retrieval?.reviewBlocked && <p role="status">Connected review is blocked: {retrieval.blockedReasons.map(reason => reason.replaceAll('_', ' ')).join('; ')}.</p>}
    </section>
  </section>
}
