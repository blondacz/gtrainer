import { render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { App } from './App'

describe('development foundation', () => {
  it('shows a real connection status, not invented health data', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => ({ status: 'ok' }) })
    vi.stubGlobal('fetch', fetchMock)
    render(<App />)
    expect(screen.getByRole('heading', { level: 1, name: 'GTrainer' })).toBeInTheDocument()
    expect(screen.getByText(/No personal records have been loaded/)).toBeInTheDocument()
    expect(await screen.findByText('Backend available')).toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledWith('/healthz', expect.objectContaining({ signal: expect.any(AbortSignal) }))
    expect(screen.getByText(/No data is sent to a model/)).toBeInTheDocument()
  })

  it('keeps the foundation visible when the backend is down', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('synthetic network failure')))
    render(<App />)
    expect(await screen.findByText('Backend unavailable')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Foundation in progress' })).toBeInTheDocument()
  })

  it.each([
    { ok: false, json: async () => ({ status: 'ok' }) },
    { ok: true, json: async () => ({ status: 'unknown' }) },
    { ok: true, json: async () => null },
    { ok: true, json: async () => { throw new Error('synthetic invalid JSON') } },
  ])('does not claim the backend is healthy for an invalid response', async (response) => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response))
    render(<App />)
    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('Backend unavailable'))
  })
})
