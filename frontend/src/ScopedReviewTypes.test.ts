import { describe, expect, it } from 'vitest'
import { activityReport, activityRequest, id, options, periodReport } from './scoped-review.test-fixtures'
import { parseOptions, parseReport, validDate } from './ScopedReviewTypes'

describe('strict scoped DTO parsing', () => {
  it('accepts actual-shaped configured options', () => { expect(parseOptions(options).activities[0].activityId).toBe(id) })
  it('rejects duplicate option identities', () => { expect(() => parseOptions({ ...options, activities: [...options.activities, options.activities[0]] })).toThrow() })
  it('does not treat an import-removal revision as a confirmed timezone', () => {
    expect(parseOptions({ ...options, configurationVersion: 2, timeZone: null, today: null, browseRange: null, activities: [], nextOffset: null }).timeZone).toBeNull()
    expect(() => parseOptions({ ...options, browseRange: null })).toThrow()
  })
  it('rejects invalid IANA zone and invalid date', () => {
    expect(() => parseOptions({ ...options, timeZone: 'Mars/NotAZone' })).toThrow()
    expect(validDate('2026-02-30')).toBe(false)
  })
  it('accepts canonical request echo despite object property order', () => {
    const report = activityReport(); report.scope.request = { ...report.scope.request, activityIds: [id] }
    expect(parseReport(report, { ...activityRequest, activityIds: [id] }).scope.status).toBe('available')
  })
  it('rejects duplicate IDs even when the request and response repeat them consistently', () => {
    const request = { ...activityRequest, activityIds: [id, id] }
    expect(() => parseReport(activityReport(request), request)).toThrow()
  })
  it('accepts exact activities selection and complete record provenance', () => { expect(parseReport(activityReport(), activityRequest, options.timeZone ?? undefined).sessions[0].record.sourceRecordId).toBe('record-1') })
  it('accepts confirmation only when sessions are empty while scope echoes IDs', () => {
    const report = activityReport(activityRequest, 'needs_confirmation')
    expect(parseReport(report, activityRequest).scope.activityIds).toEqual([id])
    expect(report.sessions).toHaveLength(0)
  })
  it('rejects session without required source record identity', () => {
    const report = activityReport() as any; delete report.sessions[0].record.sourceRecordId
    expect(() => parseReport(report, activityRequest)).toThrow()
  })
  it('rejects non-finite session values', () => {
    const report = activityReport() as any; report.sessions[0].metrics[0].value = NaN
    expect(() => parseReport(report, activityRequest)).toThrow()
  })
  it('rejects metric with incorrect unit/basis', () => {
    const report = activityReport() as any; report.sessions[0].metrics[0].unit = 'metres'
    expect(() => parseReport(report, activityRequest)).toThrow()
  })
  it('rejects response request that changes submitted mode', () => {
    const report = activityReport() as any; report.scope.request.mode = 'period'
    expect(() => parseReport(report, activityRequest)).toThrow()
  })
  it('rejects invented or reordered activity identities', () => {
    const report = activityReport() as any; report.scope.activityIds = ['b'.repeat(64)]
    expect(() => parseReport(report, activityRequest)).toThrow()
  })
  it('rejects timezone mismatch', () => { expect(() => parseReport(activityReport(), activityRequest, 'UTC')).toThrow() })
  it('requires paired factual evidence and rejects unrepresented comparisons', () => {
    const request = { schemaVersion: 1 as const, mode: 'period' as const, intent: 'review' as const, scope: 'rolling' }
    const report = periodReport(request)
    expect(parseReport(report, request).factualReview?.profile).toBe('factual-review-v1')
    expect(() => parseReport({ ...report, factualReview: null }, request)).toThrow()
    const missing = structuredClone(report); missing.factualReview.groups[0].facts = []
    expect(() => parseReport(missing, request)).toThrow()
    const wrong = structuredClone(report) as any; wrong.factualReview.profile = 'inference-v1'
    expect(() => parseReport(wrong, request)).toThrow()
  })
})
