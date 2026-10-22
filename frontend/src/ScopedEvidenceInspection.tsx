import { useEffect, useRef, useState } from 'react'
import { EvidenceReadError, loadEvidence, loadEvidenceRecords, type EvidenceSnapshot, type EvidenceRecords } from './ScopedEvidenceClient'
import {AdaptationFindings} from './AdaptationFindings'

export function ScopedEvidenceInspection({ snapshot, onSnapshot, onRemoved, onReadStart, onReadError }: { snapshot: EvidenceSnapshot; onSnapshot: (snapshot: EvidenceSnapshot) => void; onRemoved?:()=>void; onReadStart?:()=>void; onReadError?:()=>void }) {
  const [records, setRecords] = useState<EvidenceRecords|null>(null), [error, setError] = useState(''), [busy, setBusy] = useState(false)
  const generation = useRef(0), active=useRef<AbortController|null>(null)
  const [unavailable,setUnavailable]=useState(false)
  useEffect(() => {
    generation.current++;setRecords(null);setError('');setBusy(false);setUnavailable(false)
    return () => { active.current?.abort();generation.current++ }
  }, [snapshot.snapshotId])
  async function read(offset?:number) {
    onReadStart?.()
    active.current?.abort();const epoch=++generation.current,controller=new AbortController();active.current=controller
    setBusy(true);setError('')
    try {
      if(offset===undefined) {
        const next=await loadEvidence(snapshot.snapshotId,controller.signal)
        if(epoch===generation.current) onSnapshot(next)
      } else {
        const next=await loadEvidenceRecords(snapshot.snapshotId,offset,controller.signal)
        if(epoch===generation.current) setRecords(next)
      }
    } catch(cause) { if(!controller.signal.aborted && epoch===generation.current) {
      onReadError?.()
      if(cause instanceof EvidenceReadError && cause.status===404){setRecords(null);setUnavailable(true);onRemoved?.()}
      setError('Snapshot inspection unavailable. It may have changed or been removed; no retry or inference was requested.')
    } }
    finally { if(epoch===generation.current) setBusy(false) }
  }
  const packet=snapshot.packet
  if(unavailable) return <p role="status">This snapshot is unavailable or was removed. Cached packet and record values are not displayed.</p>
  const displayPacket=!!packet && typeof packet==='object' && !Array.isArray(packet) &&
    (packet as Record<string,unknown>).profile==='scoped-descriptive-v1' &&
    (packet as Record<string,unknown>).admission==='closed_not_qualified_not_execution_bound'
  const adaptation=displayPacket?(packet as Record<string,unknown>).adaptation:undefined
  return <section aria-label="Evidence snapshot inspection"><h4>Evidence snapshot</h4>
    <p>{snapshot.state} · {snapshot.snapshotId} · created {snapshot.createdUtc} · packet SHA-256 {snapshot.packetSha256 ?? 'unavailable'} · {snapshot.manifest.packetBytes} bytes</p>
    <p>Admission remains closed. Preparation is not qualification, hosted consent, inference or plan approval.</p>
    <p>Subjects: {snapshot.manifest.scope.request.mode} · {snapshot.manifest.scope.request.intent}. Wellness: {snapshot.manifest.scope.wellnessPolicy}. Captured cutoff: {snapshot.manifest.scope.asOf}.</p>
    {(['included','displayOnly','excluded','missing','needsConfirmation'] as const).map(key=><section key={key} aria-label={key}>
      <h5>{{included:'Included',displayOnly:'Display only — not transferred',excluded:'Excluded',missing:'Missing / unsupported',needsConfirmation:'Needs confirmation'}[key]}</h5>
      <p>{key==='displayOnly' ? snapshot.manifest.displayOnlyRecordCount : snapshot.manifest[key].length} items</p><ul>{snapshot.manifest[key].slice(0,25).map((item,index)=><li key={index}>{item}</li>)}</ul>
      {(key==='displayOnly' ? snapshot.manifest.displayOnlyRecordCount : snapshot.manifest[key].length)>25 && <p>First 25 shown; captured source identities and roles can be paged using Read snapshot records.</p>}
    </section>)}
    <p>Omitted optional wellness details: {snapshot.manifest.omitted.wellnessObservationDetails}; optional contexts: {snapshot.manifest.omitted.optionalContexts}.</p>
    {adaptation!==undefined&&adaptation!==null&&<AdaptationFindings value={adaptation} stale={snapshot.state==='STALE'}/>}
    <h5>Personal baselines</h5><p>{adaptation?'Configured signal rules are shown in captured adaptation findings.':'Rule policy: not configured in this snapshot.'} Source-date windows use records available at preparation, not reconstructed past knowledge.</p>
    <p>Baseline day summaries retain candidate counts; candidate records and receive revisions are available in authenticated record pages. Blocked snapshots have no admitted packet; retained candidates are inspection evidence only.</p>
    {snapshot.manifest.baselines.length===0 ? <p>No baseline requested.</p> : <ul>{snapshot.manifest.baselines.map((baseline,index)=><li key={index}>
      {baseline.identity.field} · {baseline.identity.intermediary}: {baseline.reasons.includes('semantics_unverified') ?
        'Baseline not computed: measurement semantics are unverified.' : baseline.status} · evaluated source date {baseline.configuration.evaluationDate};
      preceding {baseline.configuration.trailingDays} dates, excluding evaluation date; minimum {baseline.configuration.minimumSamples} samples,
      coverage {baseline.configuration.minimumCoverage}, maximum source-date age {baseline.configuration.maximumAgeDays} days.
    </li>)}</ul>}
    {snapshot.manifest.blockedReasons.length>0 && <p role="status">Blocked: {snapshot.manifest.blockedReasons.join(', ')}</p>}
    <details><summary>Complete manifest and exact revision references</summary><pre>{JSON.stringify(snapshot.manifest,null,2)}</pre></details>
    <details><summary>Packet (display-only JSON; not an execution contract)</summary><pre>{displayPacket ? JSON.stringify(packet,null,2) : 'No bounded packet is available.'}</pre></details>
    <button type="button" disabled={busy} onClick={()=>void read()}>Refresh snapshot status (read only)</button>
    <button type="button" disabled={busy} onClick={()=>void read(0)}>Read snapshot records</button>
    {error && <p role="status">{error}</p>}
    {records && <><p>{records.limitation}</p><ul>{records.items.map((row,index)=><li key={index}>{row.category}: {row.source} / {row.sourceRecordId}; imported revision {row.importedRevision ?? 'unknown'}; {row.status}; roles {row.roles.join(', ')}
      <details><summary>Source values (unchanged records only)</summary><pre>{JSON.stringify({activity:row.activity,wellness:row.wellness},null,2)}</pre></details></li>)}</ul>
      {records.nextOffset!==null && <button type="button" disabled={busy} onClick={()=>void read(records.nextOffset!)}>Next snapshot record page</button>}</>}
  </section>
}
