import { realDate, recordFrom, type ContextDraft, type ContextRecord, type Source } from './ContextFields'

export type Intake = { intakeId: string; revision: number; content: string; sourceCategory: Exclude<Source, 'review_feedback'>;
  authorAttribution: string; observedOn: string; enteredBy: string; recordedUtc: string }
export type Lifecycle = { resolution: 'unresolved' | 'resolved'; reviewDueOn: string | null }
export type ContextView = { context: ContextRecord; lifecycle: Lifecycle; state: string; needsReview: boolean; sourceChanged: boolean; reasonFlags: string[] }
export type CommitmentDefinition = { label: string; kind: 'commitment' | 'availability'; timeZone: string; startsLocal: string; endsLocal: string;
  recurrence: { frequency: 'daily' | 'weekly'; interval: number; endOn: string | null } | null;
  exceptions: { originalDate: string; action: 'cancel' | 'reschedule'; startsLocal: string | null; endsLocal: string | null }[] }
export type Commitment = { reference: { commitmentId: string; revision: number; intakeId: string; intakeRevision: number; contextId: string | null; contextRevision: number | null };
  definition: CommitmentDefinition; needsReconfirmation: boolean; sourceChanged: boolean; contextChanged: boolean; authorAttribution: string }
export type ContextItem = { context: ContextDraft; lifecycle: Lifecycle; contextId: string | null; expectedContextRevision: number | null }
export type CommitmentItem = { definition: CommitmentDefinition; commitmentId: string | null; expectedCommitmentRevision: number | null;
  context: { contextId: string; revision: number } | null; contextItemIndex?: number }
export type PendingItem = { key: number; selected: boolean } & ({ kind: 'context'; item: ContextItem } | { kind: 'commitment'; item: CommitmentItem })
export const object = (value: unknown): Record<string, unknown> => {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('Invalid private response')
  return value as Record<string, unknown>
}
const uuid = (value: unknown) => typeof value === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(value)
const revision = (value: unknown) => Number.isInteger(value) && Number(value) >= 1 && Number(value) <= 1000
export function intakeFrom(value: unknown): Intake {
  const item = object(value)
  if (!uuid(item.intakeId) || !revision(item.revision) || typeof item.content !== 'string' || typeof item.authorAttribution !== 'string' ||
    typeof item.observedOn !== 'string' || !realDate(item.observedOn) || typeof item.enteredBy!=='string' || typeof item.recordedUtc!=='string' ||
    !['user_report', 'coach_guidance', 'clinician_guidance'].includes(String(item.sourceCategory))) throw new Error('Invalid private response')
  return item as Intake
}
export function commitmentFrom(value: unknown): Commitment {
  const item = object(value), ref = object(item.reference), definition = commitmentDefinitionFrom(item.definition)
  if (!uuid(ref.commitmentId) || !uuid(ref.intakeId) || !revision(ref.revision) || !revision(ref.intakeRevision) ||
    !(ref.contextId===null && ref.contextRevision===null || uuid(ref.contextId) && revision(ref.contextRevision)) ||
    typeof item.needsReconfirmation !== 'boolean' || typeof item.sourceChanged!=='boolean' || typeof item.contextChanged!=='boolean' ||
    typeof item.authorAttribution!=='string' || typeof definition.label !== 'string' || !Array.isArray(definition.exceptions) || definition.exceptions.length>1000 ||
    !['commitment', 'availability'].includes(String(definition.kind))) throw new Error('Invalid private response')
  return {...item,definition} as Commitment
}
export function commitmentDefinitionFrom(value:unknown):CommitmentDefinition {
  const definition=object(value)
  if(typeof definition.label!=='string'||definition.label.length>160||!Array.isArray(definition.exceptions)||definition.exceptions.length>1000||
    !['commitment','availability'].includes(String(definition.kind)))throw new Error('Invalid private response')
  const local=(value: unknown) => typeof value==='string' && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/.test(value) && realDate(value.slice(0,10)) &&
    value.slice(11,13)<='23' && value.slice(14)<='59'
  if(typeof definition.timeZone!=='string' || !local(definition.startsLocal) || !local(definition.endsLocal)) throw new Error('Invalid private response')
  try { new Intl.DateTimeFormat('en',{ timeZone: definition.timeZone }).format() } catch { throw new Error('Invalid private response') }
  if(definition.recurrence!==null) {
    const recurrence=object(definition.recurrence)
    if(!['daily','weekly'].includes(String(recurrence.frequency)) || !Number.isInteger(recurrence.interval) || Number(recurrence.interval)<1 || Number(recurrence.interval)>366 ||
      !(recurrence.endOn===null || typeof recurrence.endOn==='string' && realDate(recurrence.endOn))) throw new Error('Invalid private response')
  }
  for(const row of definition.exceptions) {
    const exception=object(row)
    if(typeof exception.originalDate!=='string' || !realDate(exception.originalDate) || !['cancel','reschedule'].includes(String(exception.action)) ||
      (exception.action==='cancel' ? exception.startsLocal!==null || exception.endsLocal!==null : !local(exception.startsLocal) || !local(exception.endsLocal))) throw new Error('Invalid private response')
  }
  return definition as CommitmentDefinition
}

export function commitmentReferenceFrom(value: unknown): Commitment['reference'] {
  const ref=object(value)
  if(!uuid(ref.commitmentId) || !uuid(ref.intakeId) || !revision(ref.revision) || !revision(ref.intakeRevision) ||
    !(ref.contextId===null && ref.contextRevision===null || uuid(ref.contextId) && revision(ref.contextRevision))) throw new Error('Invalid private response')
  return ref as Commitment['reference']
}
export function viewFrom(value: unknown): ContextView {
  const item = object(value), lifecycle = object(item.lifecycle)
  if (!['unresolved', 'resolved'].includes(String(lifecycle.resolution)) || typeof item.sourceChanged !== 'boolean' ||
    !(lifecycle.reviewDueOn===null || typeof lifecycle.reviewDueOn==='string' && realDate(lifecycle.reviewDueOn)) ||
    typeof item.needsReview !== 'boolean' || !['current','upcoming','resolved','historical','needs_review'].includes(String(item.state)) ||
    !Array.isArray(item.reasonFlags) || !item.reasonFlags.every(flag => typeof flag==='string')) throw new Error('Invalid private response')
  return { ...item, context: recordFrom(item.context) } as ContextView
}
export function arrayFrom<T>(value: unknown, parse: (item: unknown) => T): T[] {
  if (!Array.isArray(value) || value.length > 1000) throw new Error('Invalid private response')
  return value.map(parse)
}
export class PrivateIntakeError extends Error {
  constructor(public status: number) { super(status === 401 ? 'Sign in again to manage private context.' : status === 409 ?
    'Source or item changed. Reload and review the current revision before confirming.' : 'Could not save. Check explicit dates, source and fields; no automatic retry was made.') }
}
export function sameValue(first: unknown, second: unknown): boolean {
  if(first===second) return true
  if(Array.isArray(first) && Array.isArray(second)) return first.length===second.length && first.every((value,index) => sameValue(value,second[index]))
  if(first && second && typeof first==='object' && typeof second==='object') {
    const left=object(first), right=object(second)
    return Object.keys(left).length===Object.keys(right).length && Object.keys(left).every(key => sameValue(left[key],right[key]))
  }
  return false
}
export function validateConfirmation(value: unknown, source: Intake, contexts: ContextItem[], commitments: CommitmentItem[]) {
  const result=object(value)
  if(!Array.isArray(result.contexts) || !Array.isArray(result.commitments) || result.contexts.length!==contexts.length ||
    result.commitments.length!==commitments.length || typeof result.replayed!=='boolean') throw new Error('Incomplete confirmation response')
  const records=result.contexts.map(recordFrom), calendar=result.commitments.map(commitmentFrom)
  records.forEach((record,index) => {
    const item=contexts[index]
    if(record.revision!==(item.expectedContextRevision ?? 0)+1 || item.contextId!==null && item.contextId!==record.contextId ||
      !Object.entries(item.context).every(([key,value]) => sameValue(value,record[key as keyof ContextRecord]))) throw new Error('Mismatched confirmation response')
  })
  calendar.forEach((record,index) => {
    const item=commitments[index], ref=record.reference
    if(ref.intakeId!==source.intakeId || ref.intakeRevision!==source.revision || ref.revision!==(item.expectedCommitmentRevision ?? 0)+1 ||
      item.commitmentId!==null && ref.commitmentId!==item.commitmentId || !sameValue(item.definition,record.definition) || record.authorAttribution!==source.authorAttribution ||
      item.context && (ref.contextId!==item.context.contextId || ref.contextRevision!==item.context.revision)) throw new Error('Mismatched confirmation response')
    if(item.contextItemIndex!==undefined) {
      const context=records[item.contextItemIndex]
      if(!context || ref.contextId!==context.contextId || ref.contextRevision!==context.revision) throw new Error('Mismatched confirmation response')
    }
  })
  if(new Set(records.map(record => record.contextId)).size!==records.length || new Set(calendar.map(record => record.reference.commitmentId)).size!==calendar.length)
    throw new Error('Duplicate confirmation response')
}
export async function intakeRequest(path: string, csrfToken: string, method = 'GET', body?: unknown, signal?: AbortSignal): Promise<unknown> {
  const response = await fetch(path, { method, credentials: 'same-origin', cache: 'no-store', signal,
    headers: method === 'GET' ? undefined : { 'Content-Type': 'application/json', 'X-CSRF-Token': csrfToken },
    body: body === undefined ? undefined : JSON.stringify(body) })
  if (!response.ok) {
    if(method!=='GET' && response.status>=500) throw new Error('Unknown mutation outcome')
    throw new PrivateIntakeError(response.status)
  }
  return response.json() as Promise<unknown>
}
