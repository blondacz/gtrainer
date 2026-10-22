import { describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { LiveIntakePilot, parseLiveSuggestions } from './LiveIntakePilot'
import type { Intake } from './ContextIntakeClient'

const note: Intake = { intakeId: '11111111-1111-4111-8111-111111111111', revision: 1, content: 'I have a recurring training session.',
  sourceCategory: 'user_report', authorAttribution: 'Synthetic athlete', enteredBy: 'authenticated_user', observedOn: '2026-10-21', recordedUtc: '2026-10-21T12:00:00Z' }
const profile = { profile: 'guarded-live-intake-pilot-v1' as const, evidenceMode: 'synthetic_fake' as const, boundedSessionSeconds: 1200 as const }
function body(source = note) {
  return { profile: profile.profile, intakeId: source.intakeId, intakeRevision: source.revision, status: 'available',
    provenance: { kind: 'synthetic_fake_acquisition', acquisitionId: '22222222-2222-4222-8222-222222222222', receiptSha256: 'a'.repeat(64), packetSha256: 'b'.repeat(64), requestSha256: 'c'.repeat(64), rawSha256: 'd'.repeat(64), draftSha256: 'e'.repeat(64) },
    draft: { profile: 'context-intake-suggestion-v1', intakeId: source.intakeId, intakeRevision: source.revision, suggestions: [], questions: [] } }
}
describe('live intake adapter', () => {
  it('accepts expected fake acquisition and shared draft shape', () => {
    const parsed = parseLiveSuggestions(body(), note, profile)
    expect(parsed.view.status).toBe('available')
    expect(parsed.view.provenanceLabel).toContain('Acquisition provenance: synthetic_fake_acquisition')
    expect(parsed.view.questions).toEqual([])
  })
  it('rejects fake provenance when live evidence is required', () => {
    expect(() => parseLiveSuggestions(body(), note, { ...profile, evidenceMode: 'live' })).toThrow()
  })
  it.each(['fake-as-live', 'wrong-identity', 'wrong-revision', 'bad-hash', 'extra-execution-id', 'wrong-excerpt'])('rejects %s', mutation => {
    const value = structuredClone(body()) as any
    if (mutation === 'fake-as-live') value.provenance.kind = 'live_model_capture'
    if (mutation === 'wrong-identity') value.intakeId = '33333333-3333-4333-8333-333333333333'
    if (mutation === 'wrong-revision') value.intakeRevision++
    if (mutation === 'bad-hash') value.provenance.rawSha256 = 'not-a-hash'
    if (mutation === 'extra-execution-id') Object.assign(value, { executionId: '22222222-2222-4222-8222-222222222222' })
    if (mutation === 'wrong-excerpt') value.draft.suggestions.push({ kind: 'context', sourceStart: 0, sourceEnd: 5, sourceExcerpt: 'wrong', context: null, commitment: null, unresolvedFields: [] })
    expect(() => parseLiveSuggestions(value, note, profile)).toThrow()
  })
  it('does not request suggestions before explicit inspection and labels evidence mode', async () => {
    const fetcher = vi.fn(async (path: string) => {
      if (path === '/api/session') return { ok: true, json: async () => ({ authenticated: true, csrfToken: 'token', intervalsConfigured: false }) }
      if (path === '/api/live-intake-pilot/profile') return { ok: true, json: async () => profile }
      if (path === '/api/context-intakes') return { ok: true, json: async () => [note] }
      return { ok: true, json: async () => [] }
    })
    vi.stubGlobal('fetch', fetcher)
    render(<LiveIntakePilot />)
    await waitFor(() => expect(screen.getByRole('note')).toHaveTextContent('SYNTHETIC FAKE ACQUISITION; NO MODEL EXECUTION.'))
    expect(fetcher.mock.calls.some(([path]) => path.includes('/suggestions'))).toBe(false)
  })
})
