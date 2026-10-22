import { afterEach, expect, it, vi } from 'vitest'
import { loadReviewOptions, prepareReview } from './ReviewApi'
import { activityRequest, options, activityReport } from './scoped-review.test-fixtures'

afterEach(() => vi.unstubAllGlobals())
const response = (body: unknown) => ({ ok: true, json: async () => structuredClone(body) })

it('reads options with no-store and the exact abort signal', async () => {
  const fetch = vi.fn(async () => response(options)); vi.stubGlobal('fetch', fetch)
  const controller = new AbortController()
  await loadReviewOptions(controller.signal, { oldest: '2026-03-02', newest: '2026-03-31', offset: 200 })
  expect(fetch).toHaveBeenCalledWith('/api/reviews/options?oldest=2026-03-02&newest=2026-03-31&offset=200', { cache: 'no-store', signal: controller.signal })
})

it('posts CSRF and the exact abort signal, then binds parsed report to request and expected zone', async () => {
  const fetch = vi.fn(async () => response(activityReport())); vi.stubGlobal('fetch', fetch)
  const controller = new AbortController()
  await prepareReview(activityRequest, 'csrf-token', controller.signal, 'Europe/Prague')
  expect(fetch).toHaveBeenCalledWith('/api/reviews/prepare', expect.objectContaining({ method: 'POST', cache: 'no-store', signal: controller.signal,
    headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': 'csrf-token' }, body: JSON.stringify(activityRequest) }))
})

it('rejects oversized UTF-8 bodies before fetch and makes no retry', async () => {
  const fetch = vi.fn(); vi.stubGlobal('fetch', fetch)
  const request = { ...activityRequest, padding: 'é'.repeat(16_384) } as typeof activityRequest
  await expect(prepareReview(request, 'csrf', new AbortController().signal)).rejects.toThrow('32 KiB')
  expect(fetch).not.toHaveBeenCalled()
})

it('rejects a mismatched zone and does not retry the request', async () => {
  const report = activityReport(); report.scope.timeZone = 'Europe/London'
  const fetch = vi.fn(async () => response(report)); vi.stubGlobal('fetch', fetch)
  await expect(prepareReview(activityRequest, 'csrf', new AbortController().signal, 'Europe/Prague')).rejects.toThrow()
  expect(fetch).toHaveBeenCalledTimes(1)
})
