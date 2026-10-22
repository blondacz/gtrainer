import { parseReport, utc, validDate, type ScopeDto, type ScopedReport, type ScopedRequest } from './ScopedReviewTypes'
import {adaptationFactsFrom} from './AdaptationFindingsClient'

export type EvidenceSnapshot = {
  snapshotId: string; createdUtc: string; packetSha256: string | null; state: 'PREPARED'|'BLOCKED'|'STALE'; packet: unknown
  manifest: { scope: ScopeDto; included: string[]; displayOnly: string[]; excluded: string[]; missing: string[]; needsConfirmation: string[];
    dependencies: { kind: 'context'|'intake'|'commitment'|'plan'|'adaptation_result'; identity: string; revision: number }[];
    omitted: { wellnessObservationDetails: number; optionalContexts: number; policy: string }; blockedReasons: string[]; packetBytes: number; baselines: BaselineInspection[];
    displayOnlyRecordCount: number; detailPolicy: 'bounded_manifest_candidates_in_authenticated_record_pages' }
  admission: 'closed_not_qualified_not_execution_bound'
}
type BaselineInspection={identity:{field:string;unit:string;intermediary:string};status:string;reasons:string[];value:number|null;
  configuration:{evaluationDate:string;trailingDays:number;minimumSamples:number;minimumCoverage:number;maximumAgeDays:number}}
export type PreparedEvidence = { report: ScopedReport; snapshot: EvidenceSnapshot }
const object = (v: unknown): v is Record<string, unknown> => !!v && typeof v === 'object' && !Array.isArray(v)
const text = (v: unknown) => typeof v === 'string' && v.length <= 4096
const uuid = (v: unknown): v is string => typeof v === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(v)
const list = (v: unknown): v is string[] => Array.isArray(v) && v.length <= 50_000 && v.every(text)
const bounded = (n: unknown, max = 50000) => Number.isSafeInteger(n) && Number(n) >= 0 && Number(n) <= max
export function parseSnapshot(v: unknown): EvidenceSnapshot {
  if (!object(v) || !uuid(v.snapshotId) || !utc(v.createdUtc) || !(v.packetSha256 === null || typeof v.packetSha256 === 'string' && /^[a-f0-9]{64}$/i.test(v.packetSha256)) ||
      !['PREPARED','BLOCKED','STALE'].includes(String(v.state)) || !(v.packet === null || object(v.packet)) || v.admission !== 'closed_not_qualified_not_execution_bound' || !object(v.manifest)) throw Error('Invalid evidence snapshot')
  const m = v.manifest
  const omitted=m.omitted,blocked=m.blockedReasons
  if (!object(m.scope) || !object(m.scope.request) || !utc(m.scope.asOf) || !text(m.scope.timeZone) || !validDate(m.scope.anchorDate) ||
      !['period','activities'].includes(String(m.scope.request.mode)) || !['review','compare'].includes(String(m.scope.request.intent)) ||
      !['off','display_only','assessment'].includes(String(m.scope.wellnessPolicy)) || !list(m.scope.activityIds) || !list(m.scope.activityDates) ||
      !m.scope.activityDates.every(validDate) || !['included','displayOnly','excluded','missing','needsConfirmation','blockedReasons'].every(k => list(m[k])) || !Array.isArray(m.dependencies) || m.dependencies.length > 50_000 ||
      !m.dependencies.every(d => object(d) && ['context','intake','commitment','plan','adaptation_result'].includes(String(d.kind)) &&
        (d.kind==='adaptation_result' ? typeof d.identity==='string'&&/^[a-f0-9]{64}$/.test(d.identity)&&d.revision===1 : uuid(d.identity)) && Number.isSafeInteger(d.revision) && Number(d.revision) > 0) ||
       !object(omitted) || !['wellnessObservationDetails','optionalContexts'].every(k => bounded(omitted[k],550_000)) || !text(omitted.policy) || !list(blocked) || !bounded(m.packetBytes,32_000_000) ||
       !bounded(m.displayOnlyRecordCount) || !list(m.displayOnly) || Number(m.displayOnlyRecordCount)<m.displayOnly.length || m.displayOnly.length>200 || m.detailPolicy!=='bounded_manifest_candidates_in_authenticated_record_pages' ||
      !Array.isArray(m.baselines) || m.baselines.length>20 || !m.baselines.every(b=>{
        if(!object(b) || !object(b.identity) || !text(b.identity.field) || !text(b.identity.unit) || !text(b.identity.intermediary) ||
          !['computed','not_computed'].includes(String(b.status)) || !list(b.reasons) || !(b.value===null || typeof b.value==='number'&&Number.isFinite(b.value)) || !object(b.configuration)) return false
        const c=b.configuration
        return validDate(c.evaluationDate)&&bounded(c.trailingDays,366)&&Number(c.trailingDays)>0&&bounded(c.minimumSamples,Number(c.trailingDays))&&Number(c.minimumSamples)>0&&
          typeof c.minimumCoverage==='number'&&Number.isFinite(c.minimumCoverage)&&c.minimumCoverage>=0&&c.minimumCoverage<=1&&bounded(c.maximumAgeDays,366)
      })) throw Error('Invalid evidence manifest')
  if ((v.packet === null) !== (v.packetSha256 === null) || v.state==='PREPARED' && (v.packet===null || blocked.length>0 || Number(m.packetBytes)>32768) ||
      v.state==='BLOCKED' && (v.packet!==null || blocked.length===0)) throw Error('Invalid evidence state')
  if(object(v.packet)&&v.packet.adaptation!==null&&v.packet.adaptation!==undefined){
    const facts=adaptationFactsFrom(v.packet.adaptation)
    if(!list(m.included)||!m.included.includes(`adaptation_result/${facts.workKey}`)||m.scope.wellnessPolicy!=='assessment')throw Error('Invalid adaptation inclusion')
    const references=m.dependencies.filter(d=>object(d)&&d.kind==='adaptation_result')
    if(references.some(d=>!object(d)||d.identity!==facts.workKey))throw Error('Adaptation reference mismatch')
  }
  return v as unknown as EvidenceSnapshot
}
function parseEnvelope(v: unknown, request: ScopedRequest, zone: string): PreparedEvidence {
  if (!object(v)) throw Error('Invalid evidence response')
  const report = parseReport(v.report, request, zone), snapshot = parseSnapshot(v.snapshot)
  const a = report.scope, b = snapshot.manifest.scope
  if (JSON.stringify(a)!==JSON.stringify(b)) throw Error('Snapshot/report scope mismatch')
  return { report, snapshot }
}
export class EvidenceReadError extends Error { constructor(readonly status:number){super('Evidence unavailable')} }
async function json(response: Response) { if (!response.ok) throw new EvidenceReadError(response.status); return response.json() as Promise<unknown> }
export async function prepareEvidence(request: ScopedRequest, purpose: string, question: string, additionalText: string, authorAttribution: string, csrf: string, zone: string, signal: AbortSignal): Promise<PreparedEvidence> {
  if (!['morning','evening','activity_reflection','period_reflection','question','adaptation'].includes(purpose) || question.length > 2000 ||
      purpose==='question' && !question.trim() || additionalText.length > 4000 || additionalText && !authorAttribution.trim() || authorAttribution.length > 160) throw Error('Invalid evidence request')
  const body = JSON.stringify({ schemaVersion: 2, selection: request, purpose, question: question || null, additionalText: additionalText ? { content: additionalText, authorAttribution } : null })
  if (new TextEncoder().encode(body).length > 32768) throw Error('Request exceeds 32 KiB')
  const response = await fetch('/api/reviews/prepare', { method:'POST', cache:'no-store', credentials:'same-origin', signal, headers:{'Content-Type':'application/json','X-CSRF-Token':csrf}, body })
  return parseEnvelope(await json(response), request, zone)
}
export async function loadEvidence(id: string, signal: AbortSignal): Promise<EvidenceSnapshot> {
  if (!uuid(id)) throw Error('Invalid snapshot identity')
  const snapshot=parseSnapshot(await json(await fetch(`/api/reviews/${encodeURIComponent(id)}`, { cache:'no-store', credentials:'same-origin', signal })))
  if(snapshot.snapshotId!==id) throw Error('Snapshot identity mismatch')
  return snapshot
}
export type EvidenceRecords = {snapshotId:string;items:{category:string;source:string;sourceRecordId:string;importedRevision:number|null;status:string;activity:unknown;wellness:unknown;roles:string[]}[];nextOffset:number|null;limitation:string}
export async function loadEvidenceRecords(id: string, offset: number, signal: AbortSignal): Promise<EvidenceRecords> {
  if (!uuid(id) || !bounded(offset)) throw Error('Invalid records request')
  const value = await json(await fetch(`/api/reviews/${encodeURIComponent(id)}/records?${new URLSearchParams({offset:String(offset),limit:'25'})}`, { cache:'no-store', credentials:'same-origin', signal }))
  if (!object(value) || value.snapshotId !== id || !Array.isArray(value.items) || value.items.length > 25 || !(value.nextOffset === null || bounded(value.nextOffset) && value.nextOffset===offset+25) || !text(value.limitation) || !value.items.every(x => object(x) && ['activities','wellness'].includes(String(x.category)) && text(x.source) && text(x.sourceRecordId) && (x.importedRevision === null || Number.isSafeInteger(x.importedRevision) && Number(x.importedRevision)>0) && ['unchanged','changed_since_snapshot','unavailable'].includes(String(x.status)) &&
    list(x.roles)&&x.roles.length>0&&x.roles.every(role=>['assessment_subject','assessment_context','display_only','baseline',
      'latest_available_context_not_period_subject','adaptation_support','adaptation_causal_support'].includes(role))&&
    (x.status==='unchanged' ? x.category==='activities' ? object(x.activity) && x.wellness===null : object(x.wellness) && x.activity===null : x.activity===null && x.wellness===null))) throw Error('Invalid snapshot records')
  return value as unknown as EvidenceRecords
}
