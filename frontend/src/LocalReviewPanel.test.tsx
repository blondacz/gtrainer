import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { LocalReviewPanel } from './LocalReviewPanel'
import { localInspection, localPolicy, localResponse, localSnapshot, localSummary, model, outputSha, packetSha, policySha, reviewId, snapshotId } from './local-review.test-fixtures'

afterEach(() => { cleanup(); vi.unstubAllGlobals(); vi.restoreAllMocks() })

function backend({ saved = false, initial = 'PUBLISHED', unavailable = false, running = false } = {}) {
  let armed = false, prepared = saved, state = saved ? initial : 'IN_FLIGHT', requests = 0, feedback = 0
  const fetcher = vi.fn(async (path: string, init?: RequestInit) => {
    const post = init?.method === 'POST'
    if (path.startsWith('/api/review-profiles?')) return localResponse([unavailable ? { ...localPolicy(), selectedProviderModel: null, ownerUat: null } : localPolicy(armed)])
    if (path.startsWith('/api/review-executions?')) return localResponse({ items: prepared ? [localSummary(state)] : [], nextOffset: null })
    if (path === `/api/reviews/${snapshotId}`) return localResponse(localSnapshot())
    if (path === '/api/review-executions/prepare' && post) { prepared = true; return localResponse(localInspection('IN_FLIGHT', armed)) }
    if (path.endsWith('/owner-uat/rearm') && post) { armed = true; return localResponse(null, 204) }
    if (path === `/api/review-executions/${reviewId}`) return localResponse(localInspection(state, armed))
    if (path.endsWith('/request') && post) {
      requests++; state = running ? 'RUNNING' : 'PUBLISHED'
      return localResponse({ executionId: reviewId, state: 'IN_FLIGHT', reason: null, runtimePhase: 'RESERVED', attempts: 0, deadlineUtc: '2026-03-31T12:20:00Z' }, 202)
    }
    if (path.endsWith('/status')) return localResponse({ executionId: reviewId, state, reason: null, runtimePhase: state === 'PUBLISHED' ? 'STOP_CONFIRMED' : 'EXECUTING', attempts: requests ? 1 : 0, deadlineUtc: requests ? '2026-03-31T12:20:00Z' : null })
    if (path.endsWith('/cancel') && post) {
      state = 'FAILED'
      return localResponse({ executionId: reviewId, state, reason: 'cancelled', runtimePhase: 'STOP_CONFIRMED', attempts: 1, deadlineUtc: '2026-03-31T12:20:00Z' })
    }
    if (path.endsWith('/feedback') && post) {
      feedback++; state = 'STALE'
      return localResponse({ contextId: '44444444-4444-4444-8444-444444444444', reviewId, category: 'feedback', sourceCategory: 'review_feedback' }, 201)
    }
    throw Error('Unexpected test endpoint')
  })
  vi.stubGlobal('fetch', fetcher)
  return { fetcher, requests: () => requests, feedback: () => feedback }
}

it('makes no request until opened, then only reads history and policy', async () => {
  const server = backend()
  render(<LocalReviewPanel csrfToken="csrf" snapshot={localSnapshot()} />)
  expect(server.fetcher).not.toHaveBeenCalled()
  fireEvent.click(screen.getByRole('button', { name: 'Open local model reviews' }))
  await screen.findByText(/Status and saved reviews read only/)
  expect(server.fetcher).toHaveBeenCalledTimes(2)
  expect(server.fetcher.mock.calls.every(([, init]) => !init?.method)).toBe(true)
  expect(server.requests()).toBe(0)
})

it('runs only explicit preview rearm and request actions, shows cited output and saves attributed feedback without a retry', async () => {
  const server = backend()
  render(<LocalReviewPanel csrfToken="csrf" snapshot={localSnapshot()} />)
  fireEvent.click(screen.getByRole('button', { name: 'Open local model reviews' }))
  await screen.findByText(/Status and saved reviews read only/)
  fireEvent.click(screen.getByRole('button', { name: 'Prepare local review preview' }))
  await screen.findByText(/Local review preview saved/)
  expect(server.requests()).toBe(0)
  fireEvent.click(screen.getByRole('button', { name: 'Re-arm manual local model jobs' }))
  await screen.findByText(/Manual local jobs re-armed/)
  expect(server.requests()).toBe(0)
  fireEvent.click(screen.getByRole('button', { name: 'Request local model review' }))
  await screen.findByText('Recorded activity is shown for the selected scope.')
  expect(server.requests()).toBe(1)
  expect(screen.getByText(/Source 1: session/)).toBeInTheDocument()
  expect(screen.getByText(/synthetic-record/)).toBeInTheDocument()
  expect(screen.getByText(/application does not expire/)).toBeInTheDocument()
  const prepare = server.fetcher.mock.calls.find(([path, init]) => path === '/api/review-executions/prepare' && init?.method === 'POST')
  expect(JSON.parse(String(prepare?.[1]?.body))).toEqual({ profile: 'scoped-descriptive-v1', source: { snapshotId }, providerSelection: model })
  const request = server.fetcher.mock.calls.find(([path]) => path.endsWith('/request'))
  expect(request?.[1]?.headers).toMatchObject({ 'X-CSRF-Token': 'csrf' })
  expect(JSON.parse(String(request?.[1]?.body))).toEqual({ policySha256: policySha, packetSha256: packetSha })
  fireEvent.change(screen.getByLabelText('Usefulness'), { target: { value: 'not_useful' } })
  fireEvent.change(screen.getByLabelText('Correction or feedback'), { target: { value: 'Synthetic owner correction' } })
  fireEvent.click(screen.getByRole('button', { name: 'Save feedback' }))
  await screen.findByText(/Feedback saved as your attributed report/)
  expect(server.feedback()).toBe(1); expect(server.requests()).toBe(1)
  expect(screen.queryByText('Recorded activity is shown for the selected scope.')).not.toBeInTheDocument()
  expect(screen.getByText(/Source evidence changed/)).toBeInTheDocument()
  const feedback = server.fetcher.mock.calls.find(([path]) => path.endsWith('/feedback'))
  expect(JSON.parse(String(feedback?.[1]?.body))).toEqual({ expectedOutputSha256: outputSha, feedback: { rating: 'not_useful', correction: 'Synthetic owner correction' } })
})

it('reopens saved results after remount without identifiers or generation, and keeps stale results explicit', async () => {
  const server = backend({ saved: true })
  const first = render(<LocalReviewPanel csrfToken="csrf" snapshot={null} />)
  fireEvent.click(screen.getByRole('button', { name: 'Open local model reviews' }))
  fireEvent.click(await screen.findByRole('button', { name: /Open 2026-03-29–2026-03-29/ }))
  await screen.findByText('Recorded activity is shown for the selected scope.')
  first.unmount()
  render(<LocalReviewPanel csrfToken="csrf" snapshot={null} />)
  fireEvent.click(screen.getByRole('button', { name: 'Open local model reviews' }))
  fireEvent.click(await screen.findByRole('button', { name: /Open 2026-03-29–2026-03-29/ }))
  await screen.findByText('Recorded activity is shown for the selected scope.')
  expect(server.requests()).toBe(0)
  expect(server.fetcher.mock.calls.every(([, init]) => !init?.method)).toBe(true)
})

it.each(['STALE', 'FAILED'])('shows %s without cached model prose or automatic replacement', async state => {
  const server = backend({ saved: true, initial: state })
  render(<LocalReviewPanel csrfToken="csrf" snapshot={null} />)
  fireEvent.click(screen.getByRole('button', { name: 'Open local model reviews' }))
  fireEvent.click(await screen.findByRole('button', { name: /Open 2026-03-29–2026-03-29/ }))
  await screen.findByText(/Saved result opened without generation/)
  expect(screen.queryByText('Recorded activity is shown for the selected scope.')).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'Request local model review' })).toBeDisabled()
  expect(server.requests()).toBe(0)
})

it('keeps unavailable model controls closed while factual-source navigation remains read only', async () => {
  const server = backend({ unavailable: true })
  render(<LocalReviewPanel csrfToken="csrf" snapshot={localSnapshot()} />)
  fireEvent.click(screen.getByRole('button', { name: 'Open local model reviews' }))
  await screen.findByText(/No local model policy is installed/)
  expect(screen.getByRole('button', { name: 'Prepare local review preview' })).toBeDisabled()
  expect(server.requests()).toBe(0)
})

it('does not automatically resend a request whose browser response was lost', async () => {
  const server = backend()
  const original = server.fetcher.getMockImplementation()!
  server.fetcher.mockImplementation(async (path, init) => {
    const response = await original(path, init)
    if (path.endsWith('/request')) throw Error('synthetic connection loss')
    return response
  })
  render(<LocalReviewPanel csrfToken="csrf" snapshot={localSnapshot()} />)
  fireEvent.click(screen.getByRole('button', { name: 'Open local model reviews' }))
  await screen.findByText(/Status and saved reviews read only/)
  fireEvent.click(screen.getByRole('button', { name: 'Prepare local review preview' }))
  await screen.findByText(/Local review preview saved/)
  fireEvent.click(screen.getByRole('button', { name: 'Re-arm manual local model jobs' }))
  await screen.findByText(/Manual local jobs re-armed/)
  fireEvent.click(screen.getByRole('button', { name: 'Request local model review' }))
  await screen.findByText(/Action outcome could not be verified/)
  expect(server.requests()).toBe(1)
  fireEvent.click(screen.getByRole('button', { name: 'Refresh saved reviews (read only)' }))
  fireEvent.click(await screen.findByRole('button', { name: /Open 2026-03-29–2026-03-29/ }))
  await screen.findByText('Recorded activity is shown for the selected scope.')
  await waitFor(() => expect(server.requests()).toBe(1))
})

it('cancels only the explicitly selected running job without replacing it', async () => {
  const server = backend({ running: true })
  render(<LocalReviewPanel csrfToken="csrf" snapshot={localSnapshot()} />)
  fireEvent.click(screen.getByRole('button', { name: 'Open local model reviews' }))
  await screen.findByText(/Status and saved reviews read only/)
  fireEvent.click(screen.getByRole('button', { name: 'Prepare local review preview' }))
  await screen.findByText(/Local review preview saved/)
  fireEvent.click(screen.getByRole('button', { name: 'Re-arm manual local model jobs' }))
  await screen.findByText(/Manual local jobs re-armed/)
  fireEvent.click(screen.getByRole('button', { name: 'Request local model review' }))
  fireEvent.click(await screen.findByRole('button', { name: 'Cancel this model job' }))
  await screen.findByText(/Cancellation inspected/)
  expect(server.requests()).toBe(1)
  expect(server.fetcher.mock.calls.filter(([path]) => path.endsWith('/cancel'))).toHaveLength(1)
  expect(screen.queryByText('Recorded activity is shown for the selected scope.')).not.toBeInTheDocument()
})
