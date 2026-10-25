import { describe, expect, it } from 'vitest'
import { localCitation, parseLocalHistory, parseLocalJob, parseLocalPolicy, parseLocalReview } from './LocalReviewClient'
import { draft, localInspection, localPolicy, localSummary, ref, reviewId, snapshotId } from './local-review.test-fixtures'

describe('local descriptive wire boundaries', () => {
  it('keeps guarded request permission separate from qualification and physical runtime acceptance', () => {
    const wire = localInspection()
    const parsed = parseLocalReview(wire, reviewId, snapshotId)
    expect(parsed.manualRequestAdmission.manualRequestAllowed).toBe(true)
    expect(parsed.admission.qualificationState).toBe('unqualified')
    expect(wire.admission.executionAllowed).toBe(false)
    expect(() => parseLocalPolicy({ ...localPolicy(), qualificationState: 'qualified' })).toThrow()
    expect(() => parseLocalReview({ ...wire, admission: localPolicy(false) })).toThrow()
    expect(() => parseLocalReview(wire, '33333333-3333-4333-8333-333333333333')).toThrow()
    expect(() => parseLocalReview(wire, reviewId, '33333333-3333-4333-8333-333333333333')).toThrow()
    const hosted = { ...wire, binding: { ...wire.binding, providerSelection: { ...wire.binding.providerSelection, providerKind: 'hosted' } } }
    expect(() => parseLocalReview(hosted)).toThrow()
  })
  it('accepts only whole source-bound drafts and never displays stale or rejected prose', () => {
    const wire = localInspection('PUBLISHED')
    expect(parseLocalReview(wire).draft?.interpretations).toHaveLength(1)
    for (const bad of [
      { ...draft, mode: 'period' },
      { ...draft, interpretations: [{ ...draft.interpretations[0], sources: [{ ...ref, id: 'invented' }] }] },
      { ...draft, interpretations: [{ ...draft.interpretations[0], uncertainty: 'certain' }] },
      { ...draft, interpretations: [...draft.interpretations, { ...draft.interpretations[0], sources: [] }] },
    ]) expect(() => parseLocalReview({ ...wire, validatedOutputJson: JSON.stringify(bad) })).toThrow()
    expect(() => parseLocalReview({ ...wire, state: 'STALE' })).toThrow()
    expect(() => parseLocalReview({ ...wire, state: 'FAILED' })).toThrow()
    expect(parseLocalReview(localInspection('STALE')).draft).toBeNull()
    expect(localCitation(parseLocalReview(wire), ref)).toMatchObject({ sourceRecordId: 'synthetic-record', movingTime: 600 })
  })
  it('bounds history and job identities and retains historical hosted previews without authorizing them', () => {
    expect(parseLocalHistory({ items: [localSummary()], nextOffset: null }).items[0].executionId).toBe(reviewId)
    const historical = { ...localSummary(), providerSelection: { ...localSummary().providerSelection, providerKind: 'hosted' } }
    expect(parseLocalHistory({ items: [historical], nextOffset: null }).items[0].providerSelection.providerKind).toBe('hosted')
    expect(() => parseLocalHistory({ items: Array.from({ length: 26 }, () => localSummary()), nextOffset: 26 })).toThrow()
    expect(() => parseLocalHistory({ items: [localSummary(), localSummary()], nextOffset: null })).toThrow()
    expect(() => parseLocalHistory({ items: [{ ...localSummary(), coverageFrom: '2026-03-40' }], nextOffset: null })).toThrow()
    const job = { executionId: reviewId, state: 'RUNNING', reason: null, runtimePhase: 'EXECUTING', attempts: 1, deadlineUtc: '2026-03-31T12:20:00Z' }
    expect(parseLocalJob(job, reviewId).attempts).toBe(1)
    expect(() => parseLocalJob({ ...job, attempts: 2 }, reviewId)).toThrow()
    expect(() => parseLocalJob({ ...job, executionId: snapshotId }, reviewId)).toThrow()
  })
})
