import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { SessionPanel } from './SessionPanel'

const csrf = 'a'.repeat(43)
const syntheticPassword = 'synthetic-test-password-not-a-real-credential'

describe('private access', () => {
  it('signs in and signs out without browser-storage credentials', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce({ ok: false, status: 401 })
      .mockResolvedValueOnce({ ok: true, json: async () => ({ authenticated: true, csrfToken: csrf, intervalsConfigured: true }) })
      .mockResolvedValueOnce({ ok: true, json: async () => [] })
      .mockResolvedValueOnce({ ok: true })
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
    expect(fetchMock).toHaveBeenLastCalledWith('/api/logout', expect.objectContaining({
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
