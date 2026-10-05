import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { SessionPanel } from './SessionPanel'

const csrf = 'a'.repeat(43)
const syntheticPassword = 'synthetic-test-password-not-a-real-credential'

describe('private access', () => {
  it('mounts only the selected section and keeps writes and model calls off during navigation', async () => {
    const fetchMock = vi.fn(async (path: string) => {
      if (path === '/api/session') return { ok: true, json: async () => ({ authenticated: true, csrfToken: csrf, intervalsConfigured: false }) }
      if (path === '/api/events') return { ok: true, status: 200, json: async () => ({ events: [], nextUpcoming: null, ongoing: [], evaluatedOn: '2020-06-02' }) }
      return { ok: false, status: 503 }
    })
    vi.stubGlobal('fetch', fetchMock)
    const authenticated = vi.fn()
    render(<SessionPanel onAuthenticatedChange={authenticated} />)
    await screen.findByRole('heading', { name: 'Overview' })
    expect(authenticated).toHaveBeenLastCalledWith(true)
    expect(screen.getByRole('complementary', { name: 'Context deletion and backup retention' })).toHaveTextContent(
      'Retained encrypted backups may still contain deleted data until they expire under normal retention.',
    )
    expect(screen.getByRole('tab', { name: 'Overview' })).toHaveAttribute('aria-selected', 'true')
    expect(screen.queryByRole('heading', { name: 'Historical trends' })).not.toBeInTheDocument()
    expect(fetchMock.mock.calls.some(([path]) => path === '/api/review-models')).toBe(false)
    fireEvent.click(screen.getByRole('tab', { name: 'Events' }))
    await screen.findByText('No manual events yet.')
    expect(screen.queryByRole('heading', { name: 'Overview' })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('tab', { name: 'Reviews' }))
    await screen.findByRole('heading', { name: /Review controls/ })
    expect(screen.queryByRole('heading', { name: 'Manual events' })).not.toBeInTheDocument()
    expect(fetchMock.mock.calls.some(([path]) => path === '/api/review-now' || path === '/api/analysis' || path === '/api/sync')).toBe(false)
    fireEvent.keyDown(screen.getByRole('tab', { name: 'Reviews' }), { key: 'Home' })
    expect(screen.getByRole('tab', { name: 'Overview' })).toHaveFocus()
    fireEvent.keyDown(screen.getByRole('tab', { name: 'Overview' }), { key: 'ArrowRight' })
    expect(screen.getByRole('tab', { name: 'Trends' })).toHaveFocus()
    await screen.findByText(/History unavailable/)
    expect(screen.queryByRole('heading', { name: /Review controls/ })).not.toBeInTheDocument()
  })

  it('signs in and signs out without browser-storage credentials', async () => {
    // Import-status effects and logout can overlap: mock by endpoint rather
    // than assuming a particular scheduling order across operating systems.
    const fetchMock = vi.fn(async (path: string) => {
      if (path === '/api/session') return { ok: false, status: 401 }
      if (path === '/api/login') return { ok: true, json: async () => ({ authenticated: true, csrfToken: csrf, intervalsConfigured: true }) }
      if (path === '/api/imports') return { ok: true, json: async () => ['activities', 'wellness'].map(category => ({
        category, recordCount: 0, readStatus: 'NEVER_READ', rejected: 0, incomplete: 0,
        lastAttemptUtc: null, lastSuccessUtc: null, latestObservedDate: null, latestObservedAgeDays: null,
        upstreamFreshness: 'unknown',
      })) }
      if (path === '/api/logout') return { ok: true }
      if (path.startsWith('/api/trends?')) return { ok: false, status: 503 }
      throw new Error('Unexpected synthetic request')
    })
    vi.stubGlobal('fetch', fetchMock)
    render(<SessionPanel />)
    const input = await screen.findByLabelText('Dashboard password')
    fireEvent.change(input, { target: { value: syntheticPassword } })
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }))
    expect(await screen.findByText('Signed in to your private dashboard.')).toBeInTheDocument()
    expect(screen.getByText('Intervals.icu credential is configured.')).toBeInTheDocument()
    expect(window.localStorage.length).toBe(0)
    expect(window.sessionStorage.length).toBe(0)
    fireEvent.click(screen.getByRole('button', { name: 'Sign out' }))
    expect(await screen.findByLabelText('Dashboard password')).toHaveValue('')
    expect(screen.queryByRole('heading', { name: 'Historical trends' })).not.toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledWith('/api/logout', expect.objectContaining({
      method: 'POST', headers: { 'X-CSRF-Token': csrf },
    }))
  })

  it.each([401, 503])('clears password and keeps private content hidden on login error %s', async status => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status }))
    render(<SessionPanel />)
    const input = await screen.findByLabelText('Dashboard password')
    fireEvent.change(input, { target: { value: syntheticPassword } })
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }))
    await waitFor(() => expect(input).toHaveValue(''))
    expect(await screen.findByText(status === 503 ? 'Sign-in is not configured yet.' :
      'Sign-in failed. Check the password or retry later.')).toBeInTheDocument()
    expect(screen.queryByText(/Signed in to your private dashboard/)).not.toBeInTheDocument()
  })

  it('does not claim authenticated access from malformed session data', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => ({ authenticated: true }) }))
    render(<SessionPanel />)
    expect(await screen.findByLabelText('Dashboard password')).toBeInTheDocument()
    expect(screen.queryByText(/Signed in to your private dashboard/)).not.toBeInTheDocument()
  })

  it('fails closed when checking a session is offline', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('synthetic network failure')))
    render(<SessionPanel />)
    expect(await screen.findByLabelText('Dashboard password')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Sign out' })).not.toBeInTheDocument()
  })
})
