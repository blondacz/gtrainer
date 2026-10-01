import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { ImportPanel } from './ImportPanel'

const csrf = 's'.repeat(43)
function statuses(count = 0, readStatus = 'NEVER_READ') {
  return ['activities', 'wellness'].map(category => ({ category, recordCount: count,
    lastAttemptUtc: null, lastSuccessUtc: null, readStatus, rejected: 0, incomplete: 0,
    latestObservedDate: null, latestObservedAgeDays: null, upstreamFreshness: 'unknown' }))
}
const response = (body: unknown) => ({ ok: true, json: async () => body })

describe('private read-only imports', () => {
  it('starts only after user action and sends the session CSRF token', async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(response(statuses())).mockResolvedValueOnce(response(statuses(1, 'SUCCESS')))
    vi.stubGlobal('fetch', fetchMock)
    render(<ImportPanel csrfToken={csrf} configured />)
    expect(await screen.findAllByText(/Not imported yet/)).toHaveLength(2)
    expect(fetchMock).toHaveBeenCalledTimes(1)
    fireEvent.click(screen.getByRole('button', { name: 'Read Intervals.icu' }))
    expect(await screen.findByText(/Read finished/)).toBeInTheDocument()
    expect(fetchMock).toHaveBeenLastCalledWith('/api/sync', expect.objectContaining({
      method: 'POST', headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': csrf },
    }))
    expect(screen.getAllByText(/Garmin-to-Intervals.icu freshness is unverified/)).toHaveLength(2)
  })

  it('reports key rejection and stale available records without invented upstream causes', async () => {
    const body = statuses(1, 'KEY_REJECTED').map(status => ({ ...status, latestObservedAgeDays: 20 }))
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response(body)))
    render(<ImportPanel csrfToken={csrf} configured />)
    expect(await screen.findAllByText(/Key rejected/)).toHaveLength(2)
    expect(screen.getAllByText(/does not prove an upstream sync failure or missed activity/)).toHaveLength(2)
    expect(screen.getAllByText(/Missing health values do not imply illness/)).toHaveLength(2)
  })

  it('requires confirmation before removing local imports', async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(response(statuses(1, 'SUCCESS')))
      .mockResolvedValueOnce(response({})).mockResolvedValueOnce(response(statuses()))
    vi.stubGlobal('fetch', fetchMock)
    const confirm = vi.spyOn(window, 'confirm').mockReturnValueOnce(false).mockReturnValueOnce(true)
    render(<ImportPanel csrfToken={csrf} configured />)
    const button = screen.getByRole('button', { name: 'Remove local imports' })
    await waitFor(() => expect(button).not.toBeDisabled())
    fireEvent.click(button)
    expect(fetchMock).toHaveBeenCalledTimes(1)
    fireEvent.click(button)
    expect(await screen.findByText(/Local imports removed/)).toBeInTheDocument()
    expect(confirm).toHaveBeenCalledTimes(2)
    expect(fetchMock).toHaveBeenNthCalledWith(2, '/api/imports', expect.objectContaining({ method: 'DELETE' }))
  })

  it('keeps source failures visible and disables import with no credential', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 401 }))
    render(<ImportPanel csrfToken={csrf} configured={false} />)
    expect(await screen.findByText(/Import status unavailable/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Read Intervals.icu' })).toBeDisabled()
  })

  it('rejects an oversized date range without fetching upstream', async () => {
    const fetchMock = vi.fn().mockResolvedValue(response(statuses()))
    vi.stubGlobal('fetch', fetchMock)
    render(<ImportPanel csrfToken={csrf} configured />)
    await screen.findAllByText(/Not imported yet/)
    fireEvent.change(screen.getByLabelText('First date'), { target: { value: '2020-01-01' } })
    fireEvent.change(screen.getByLabelText('Last date'), { target: { value: '2022-01-01' } })
    fireEvent.click(screen.getByRole('button', { name: 'Read Intervals.icu' }))
    expect(await screen.findByText(/at most 366 days/)).toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })
})
