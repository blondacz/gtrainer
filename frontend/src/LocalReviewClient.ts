import { utc, validDate } from './ScopedReviewTypes'

export const descriptiveProfile = 'scoped-descriptive-v1'
export type LocalSelection = { providerId: string; modelId: string; providerKind: 'local' | 'hosted' }
export type LocalPolicy = { selectedProviderModel: LocalSelection | null; qualificationState: 'unqualified';
  ownerUat: { state: string; policySha256: string | null; armed: boolean } | null; blockingReasons: string[] }
export type LocalReviewSummary = { executionId: string; source: { snapshotId: string }; profile: string; providerSelection: LocalSelection;
  coverageFrom: string; coverageUntil: string; createdUtc: string; state: string; staleReason: string | null; outputSha256: string | null }
export type LocalReviewHistory = { items: LocalReviewSummary[]; nextOffset: number | null }
export type LocalReference = { kind: string; id: string; revision: number | null }
export type LocalReviewItem = { kind: string; text: string; uncertainty: string; sources: LocalReference[] }
export type LocalReview = { binding: { executionId: string; profile: string; source: { snapshotId: string }; providerSelection: LocalSelection;
  consentSha256: string; coverageFrom: string; coverageUntil: string; createdUtc: string };
  state: string; staleReason: string | null; validatedOutputSha256: string | null; admission: LocalPolicy;
  manualRequestAdmission: { manualRequestAllowed: boolean; requestAdmissionState: string; blockingReasons: string[] };
  packet: Record<string, unknown>; draft: { mode: string; intent: string; interpretations: LocalReviewItem[]; questions: LocalReviewItem[] } | null }
export type LocalJob = { executionId: string; state: string; reason: string | null; runtimePhase: string | null; attempts: number; deadlineUtc: string | null }
const obj = (v: unknown): v is Record<string, unknown> => !!v && typeof v === 'object' && !Array.isArray(v)
const text = (v: unknown, max = 4096): v is string => typeof v === 'string' && v.length <= max
const integer = (v: unknown, max = 1000): v is number => Number.isSafeInteger(v) && Number(v) >= 0 && Number(v) <= max
const uuid = (v: unknown): v is string => typeof v === 'string' && /^[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}$/.test(v)
const sha = (v: unknown): v is string => typeof v === 'string' && /^[a-f0-9]{64}$/.test(v)
const nullable = (v: unknown) => v === null || text(v)
const reasons = (v: unknown): v is string[] => Array.isArray(v) && v.length <= 32 && v.every(x => text(x, 128))
const states = ['IN_FLIGHT', 'RUNNING', 'PUBLISHED', 'FAILED', 'STALE', 'INTERRUPTED', 'UNVERIFIED', 'UNAVAILABLE']
function selection(v: unknown): v is LocalSelection { return obj(v) && text(v.providerId, 128) && text(v.modelId, 128) && ['local', 'hosted'].includes(String(v.providerKind)) }
function source(v: unknown): v is { snapshotId: string } { return obj(v) && uuid(v.snapshotId) && v.intakeId == null && v.intakeRevision == null }
function reference(v: unknown): v is LocalReference {
  return obj(v) && ['session', 'aggregate', 'wellness', 'context', 'commitment', 'plan_session', 'comparison', 'baseline', 'adaptation'].includes(String(v.kind)) &&
    text(v.id, 256) && v.id.length > 0 && (v.revision == null || integer(v.revision) && Number(v.revision) > 0)
}
const refKey = (v: LocalReference) => JSON.stringify([v.kind, v.id, v.revision ?? null])
export function parseLocalPolicy(v: unknown): LocalPolicy {
  if (!obj(v) || v.profile !== descriptiveProfile || v.supported !== true || v.qualificationState !== 'unqualified' || !reasons(v.blockingReasons) ||
      !(v.selectedProviderModel === null || selection(v.selectedProviderModel))) throw Error('Invalid local admission')
  const owner = v.ownerUat
  if (owner != null && (!obj(owner) || !['accepted', 'policy_missing', 'policy_invalid', 'policy_revoked', 'binding_changed', 'installation_unavailable'].includes(String(owner.state)) ||
      !(owner.policySha256 == null || sha(owner.policySha256)) || !(owner.armed === undefined || typeof owner.armed === 'boolean') || owner.state === 'accepted' && !sha(owner.policySha256) ||
      owner.state !== 'accepted' && owner.armed === true)) throw Error('Invalid owner policy')
  return { selectedProviderModel: v.selectedProviderModel as LocalSelection | null, qualificationState: 'unqualified', blockingReasons: v.blockingReasons,
    ownerUat: obj(owner) ? { state: String(owner.state), policySha256: owner.policySha256 as string ?? null, armed: owner.armed === true } : null }
}
export function parseLocalHistory(v: unknown): LocalReviewHistory {
  if (!obj(v) || !Array.isArray(v.items) || v.items.length > 25 || !(v.nextOffset == null || integer(v.nextOffset)) || !v.items.every(item =>
    obj(item) && uuid(item.executionId) && source(item.source) && item.profile === descriptiveProfile && selection(item.providerSelection) &&
    validDate(item.coverageFrom) && validDate(item.coverageUntil) && item.coverageFrom <= item.coverageUntil && utc(item.createdUtc) &&
    states.includes(String(item.state)) && nullable(item.staleReason) && (item.outputSha256 === null || sha(item.outputSha256))) ||
    new Set(v.items.map(item => item.executionId)).size !== v.items.length) throw Error('Invalid saved local reviews')
  return { items: v.items as LocalReviewSummary[], nextOffset: v.nextOffset as number ?? null }
}
export function parseLocalReview(v: unknown, expectedId?: string, expectedSource?: string): LocalReview {
  if (!obj(v) || !obj(v.binding) || !uuid(v.binding.executionId) || expectedId && v.binding.executionId !== expectedId || !source(v.binding.source) ||
      expectedSource && v.binding.source.snapshotId !== expectedSource || v.binding.profile !== descriptiveProfile || !selection(v.binding.providerSelection) ||
      !sha(v.binding.consentSha256) || !validDate(v.binding.coverageFrom) || !validDate(v.binding.coverageUntil) || v.binding.coverageFrom > v.binding.coverageUntil ||
      !utc(v.binding.createdUtc) || !states.includes(String(v.state)) || !nullable(v.staleReason) || !(v.validatedOutputSha256 === null || sha(v.validatedOutputSha256)) ||
      !obj(v.binding.exactPrompt) || !text(v.binding.exactPrompt.systemInstructions, 32768) || !text(v.binding.exactPrompt.userPacketJson, 32768) ||
      !obj(v.manualRequestAdmission) || typeof v.manualRequestAdmission.manualRequestAllowed !== 'boolean' || !text(v.manualRequestAdmission.requestAdmissionState, 128) ||
      !reasons(v.manualRequestAdmission.blockingReasons)) throw Error('Invalid local execution')
  const admission = parseLocalPolicy(v.admission), packet: unknown = JSON.parse(v.binding.exactPrompt.userPacketJson)
  const selected = admission.selectedProviderModel, bound = v.binding.providerSelection
  if (!selected || selected.providerId !== bound.providerId || selected.modelId !== bound.modelId || selected.providerKind !== bound.providerKind) throw Error('Invalid displayed provider binding')
  if (!obj(packet) || packet.profile !== descriptiveProfile || !obj(packet.evidence) || !obj(packet.evidence.request) || !obj(packet.evidence.request.selection) ||
      !['period', 'activities'].includes(String(packet.evidence.request.selection.mode)) || !['review', 'compare'].includes(String(packet.evidence.request.selection.intent)) ||
      !Array.isArray(packet.references) || packet.references.length > 5000 || !packet.references.every(reference)) throw Error('Invalid local evidence packet')
  const available = new Set(packet.references.map(refKey))
  let draft: LocalReview['draft'] = null
  if (v.validatedOutputJson !== null) {
    if (v.state !== 'PUBLISHED' || !text(v.validatedOutputJson, 12288) || !sha(v.validatedOutputSha256)) throw Error('Invalid local output state')
    const parsed: unknown = JSON.parse(v.validatedOutputJson)
    const validItems = (items: unknown): items is LocalReviewItem[] => Array.isArray(items) && items.length <= 8 && items.every(item =>
      obj(item) && ['observation', 'comparison', 'context'].includes(String(item.kind)) && text(item.text, 2000) && item.text.trim().length > 0 &&
      ['low', 'moderate', 'high', 'unknown'].includes(String(item.uncertainty)) && Array.isArray(item.sources) && item.sources.length >= 1 && item.sources.length <= 12 &&
      item.sources.every(s => reference(s) && available.has(refKey(s))) && new Set(item.sources.map(refKey)).size === item.sources.length)
    if (!obj(parsed) || parsed.profile !== descriptiveProfile || parsed.mode !== packet.evidence.request.selection.mode || parsed.intent !== packet.evidence.request.selection.intent ||
        !validItems(parsed.interpretations) || !validItems(parsed.questions)) throw Error('Invalid descriptive result')
    draft = parsed as LocalReview['draft']
  } else if (v.validatedOutputSha256 !== null) throw Error('Invalid withheld output binding')
  if (v.manualRequestAdmission.manualRequestAllowed && (v.state !== 'IN_FLIGHT' || v.manualRequestAdmission.requestAdmissionState !== 'guarded_request_available' ||
      admission.ownerUat?.state !== 'accepted' || !admission.ownerUat.armed || v.binding.providerSelection.providerKind !== 'local' || v.manualRequestAdmission.blockingReasons.length > 0)) throw Error('Invalid request permission')
  return { binding: v.binding as LocalReview['binding'], state: String(v.state), staleReason: v.staleReason as string | null,
    validatedOutputSha256: v.validatedOutputSha256 as string | null, admission, manualRequestAdmission: v.manualRequestAdmission as LocalReview['manualRequestAdmission'], packet, draft }
}
export function parseLocalJob(v: unknown, id: string): LocalJob {
  if (!obj(v) || v.executionId !== id || !states.includes(String(v.state)) || !nullable(v.reason) ||
      !(v.runtimePhase === null || ['RESERVED', 'START_INTENT', 'EXECUTING', 'STOPPING', 'STOP_CONFIRMED', 'UNKNOWN'].includes(String(v.runtimePhase))) ||
      !integer(v.attempts, 1) || !(v.deadlineUtc === null || utc(v.deadlineUtc))) throw Error('Invalid local job status')
  return v as unknown as LocalJob
}
export class LocalReviewReadError extends Error { constructor(readonly status: number) { super('Local review unavailable') } }
async function read(response: Response): Promise<unknown> {
  if (!response.ok) throw new LocalReviewReadError(response.status)
  const body = await response.text()
  if (new TextEncoder().encode(body).length > 384 * 1024) throw Error('Local response too large')
  return JSON.parse(body)
}
const get = (url: string, signal: AbortSignal) => fetch(url, { cache: 'no-store', credentials: 'same-origin', signal }).then(read)
async function post(url: string, body: unknown, csrf: string, signal: AbortSignal, empty = false) {
  const response = await fetch(url, { method: 'POST', cache: 'no-store', credentials: 'same-origin', signal,
    headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': csrf }, body: JSON.stringify(body) })
  if (empty) { if (response.status !== 204) throw new LocalReviewReadError(response.status); return null }
  return read(response)
}
export async function loadLocalPolicy(signal: AbortSignal) {
  const v = await get(`/api/review-profiles?profile=${descriptiveProfile}`, signal)
  if (!Array.isArray(v) || v.length !== 1) throw Error('Invalid local profile list')
  return parseLocalPolicy(v[0])
}
export const loadLocalHistory = async (signal: AbortSignal, offset = 0) => parseLocalHistory(await get(`/api/review-executions?profile=${descriptiveProfile}&offset=${offset}&limit=25`, signal))
export async function loadLocalReview(id: string, signal: AbortSignal, sourceId?: string) {
  if (!uuid(id)) throw Error('Invalid local execution identity')
  return parseLocalReview(await get(`/api/review-executions/${id}`, signal), id, sourceId)
}
export async function prepareLocalReview(snapshotId: string, providerSelection: LocalSelection, csrf: string, signal: AbortSignal) {
  if (!uuid(snapshotId) || !selection(providerSelection) || providerSelection.providerKind !== 'local') throw Error('Invalid preview selection')
  return parseLocalReview(await post('/api/review-executions/prepare', { profile: descriptiveProfile, source: { snapshotId }, providerSelection }, csrf, signal), undefined, snapshotId)
}
export const rearmLocalReview = (policySha256: string, csrf: string, signal: AbortSignal) => {
  if (!sha(policySha256)) throw Error('Invalid policy acknowledgement')
  return post('/api/review-profiles/owner-uat/rearm', { policySha256 }, csrf, signal, true)
}
export const requestLocalReview = async (review: LocalReview, csrf: string, signal: AbortSignal) => {
  const policySha256 = review.admission.ownerUat?.policySha256
  if (!sha(policySha256) || !review.manualRequestAdmission.manualRequestAllowed) throw Error('Local request unavailable')
  return parseLocalJob(await post(`/api/review-executions/${review.binding.executionId}/request`, { policySha256, packetSha256: review.binding.consentSha256 }, csrf, signal), review.binding.executionId)
}
export const loadLocalJob = async (id: string, signal: AbortSignal) => {
  if (!uuid(id)) throw Error('Invalid local execution identity')
  return parseLocalJob(await get(`/api/review-executions/${id}/status`, signal), id)
}
export const cancelLocalReview = async (review: LocalReview, csrf: string, signal: AbortSignal) =>
  parseLocalJob(await post(`/api/review-executions/${review.binding.executionId}/cancel`, { packetSha256: review.binding.consentSha256 }, csrf, signal), review.binding.executionId)
export async function saveLocalFeedback(id: string, expectedOutputSha256: string, rating: string, correction: string, csrf: string, signal: AbortSignal) {
  if (!uuid(id) || !sha(expectedOutputSha256) || !['', 'useful', 'not_useful'].includes(rating) || !text(correction, 4000) || !rating && !correction.trim()) throw Error('Invalid feedback')
  const saved = await post(`/api/review-executions/${id}/feedback`, { expectedOutputSha256, feedback: { rating: rating || null, correction: correction.trim() || null } }, csrf, signal)
  if (!obj(saved) || !uuid(saved.contextId) || saved.reviewId !== id || saved.sourceCategory !== 'review_feedback' || saved.category !== 'feedback') throw Error('Feedback could not be verified')
}

/** Display only, never a routing or model instruction. The server validated the complete reference set. */
export function localCitation(review: LocalReview, ref: LocalReference): unknown {
  if (!obj(review.packet.evidence)) return null
  const e = review.packet.evidence
  if (ref.kind === 'comparison' || ref.kind === 'baseline') {
    const bindings = review.packet.referenceBindings
    if (!Array.isArray(bindings)) return null
    const binding = bindings.find(b => obj(b) && reference(b.reference) && refKey(b.reference) === refKey(ref))
    if (!obj(binding) || !integer(binding.index, 5000)) return null
    const collection = binding.collection === 'facts.sessionComparisons' && obj(e.facts) ? e.facts.sessionComparisons :
      binding.collection === 'facts.periodComparisons' && obj(e.facts) ? e.facts.periodComparisons : binding.collection === 'baselines' ? e.baselines : null
    return Array.isArray(collection) ? collection[binding.index] ?? null : null
  }
  const find = (value: unknown, depth = 0): unknown => {
    if (depth > 32 || !value || typeof value !== 'object') return null
    if (obj(value) && (value.evidenceId === ref.id || ref.kind === 'context' && value.contextId === ref.id && value.revision === ref.revision ||
      ref.kind === 'plan_session' && value.sessionId === ref.id || ref.kind === 'commitment' && value.occurrenceId === ref.id || ref.kind === 'adaptation' && value.workKey === ref.id)) return value
    for (const child of Object.values(value)) { const found = find(child, depth + 1); if (found !== null) return found }
    return null
  }
  return find(e)
}
