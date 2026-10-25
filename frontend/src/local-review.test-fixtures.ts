import { createHash } from 'node:crypto'
import { activityReport, asOf, id } from './scoped-review.test-fixtures'
import type { EvidenceSnapshot } from './ScopedEvidenceClient'
import { descriptiveProfile } from './LocalReviewClient'

export const reviewId = '22222222-2222-4222-8222-222222222222'
export const snapshotId = '11111111-1111-4111-8111-111111111111'
export const policySha = 'a'.repeat(64)
export const packetSha = 'c'.repeat(64)
export const model = { providerId: 'synthetic', modelId: 'fake-descriptive-model', providerKind: 'local' }
export const ref = { kind: 'session', id, revision: null }
export const draft = { profile: descriptiveProfile, mode: 'activities', intent: 'review', interpretations: [
  { kind: 'observation', text: 'Recorded activity is shown for the selected scope.', sources: [ref], uncertainty: 'high' },
], questions: [] }
export const outputJson = JSON.stringify(draft)
export const outputSha = createHash('sha256').update(outputJson).digest('hex')
export function localSnapshot(): EvidenceSnapshot {
  return { snapshotId, createdUtc: asOf, packetSha256: packetSha, state: 'PREPARED',
    packet: { profile: descriptiveProfile, admission: 'closed_not_qualified_not_execution_bound' },
    admission: 'closed_not_qualified_not_execution_bound', manifest: { scope: activityReport().scope,
      included: [`session/${id}`], displayOnly: [], excluded: [], missing: ['trainingReadiness'], needsConfirmation: [], dependencies: [],
      omitted: { wellnessObservationDetails: 0, optionalContexts: 0, policy: 'category_atomic_optional_detail_only' }, blockedReasons: [], packetBytes: 1024,
      baselines: [], displayOnlyRecordCount: 0, detailPolicy: 'bounded_manifest_candidates_in_authenticated_record_pages' } }
}
export function localPolicy(armed = false) {
  return { profile: descriptiveProfile, supported: true, selectedProviderModel: model, qualificationState: 'unqualified',
    runtimeAdmissionState: 'closed_not_accepted', executionAllowed: false, executionMode: 'owner_local_uat',
    ownerUat: { state: 'accepted', policySha256: policySha, armed }, blockingReasons: armed ? ['runtime_admission_closed'] : ['owner_uat_rearm_required', 'runtime_admission_closed'],
    policyLimits: { attemptTimeoutMillis: 900000, totalTimeoutMillis: 1200000, maxAttempts: 1, maxPromptBytes: 32768, maxResponseBytes: 12288, maxHostedCostPerAttemptUsd: '0', maxHostedCostTotalUsd: '0' } }
}
export function localInspection(state = 'IN_FLIGHT', armed = true) {
  const allowed = state === 'IN_FLIGHT' && armed
  return { binding: { executionId: reviewId, requestId: `scoped-execution:${descriptiveProfile}:${snapshotId}`, generation: 1, profile: descriptiveProfile,
    source: { snapshotId, intakeId: null, intakeRevision: null }, providerSelection: model, consentSha256: packetSha, dependencyBindingSha256: 'd'.repeat(64),
    exactPrompt: { systemInstructions: 'Synthetic code-owned instructions', userPacketJson: JSON.stringify({ profile: descriptiveProfile,
      evidence: { request: { selection: { mode: 'activities', intent: 'review' } }, facts: { sessions: [{ evidenceId: id, sourceRecordId: 'synthetic-record', movingTime: 600, unit: 'seconds', date: '2026-03-29' }] } }, references: [ref], referenceBindings: [] }) },
    coverageFrom: '2026-03-29', coverageUntil: '2026-03-29', createdUtc: asOf }, state, staleReason: state === 'FAILED' ? 'invalid_output' : state === 'STALE' ? 'source_changed' : null,
    validatedOutputJson: state === 'PUBLISHED' ? outputJson : null, validatedOutputSha256: state === 'PUBLISHED' ? outputSha : null, admission: localPolicy(armed),
    manualRequestAdmission: { manualRequestAllowed: allowed, requestAdmissionState: allowed ? 'guarded_request_available' : 'guarded_request_blocked',
      blockingReasons: allowed ? [] : !armed ? ['owner_uat_rearm_required'] : ['already_requested'] } }
}
export function localSummary(state = 'PUBLISHED') {
  return { executionId: reviewId, source: { snapshotId }, profile: descriptiveProfile, providerSelection: model,
    coverageFrom: '2026-03-29', coverageUntil: '2026-03-29', createdUtc: asOf, state, staleReason: null,
    outputSha256: ['PUBLISHED', 'STALE'].includes(state) ? outputSha : null }
}
export const localResponse = (v: unknown, status = 200) => ({ ok: status >= 200 && status < 300, status,
  json: async () => structuredClone(v), text: async () => JSON.stringify(v) })
