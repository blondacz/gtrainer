import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { ContextSuggestionControls } from './ContextSuggestionControls'
import type { Intake } from './ContextIntakeClient'

const id = '11111111-1111-4111-8111-111111111111'
const note: Intake = { intakeId: id, revision: 2, content: 'Knee hurts after running; physio every week', sourceCategory: 'user_report', authorAttribution: 'athlete', observedOn: '2026-10-14', enteredBy: 'athlete', recordedUtc: '2026-10-14T10:00:00Z' }
const context = { kind: 'context', sourceStart: 0, sourceEnd: 24, sourceExcerpt: note.content.slice(0, 24), context: { category: 'symptom', observedOn: null, applicableFrom: null, applicableUntil: null, applicableFromMode: 'unknown', applicableUntilMode: 'unknown', content: 'Knee hurts after running' }, commitment: null, unresolvedFields: ['observedOn', 'applicableFrom', 'applicableUntil'] }
const commitment = { kind: 'commitment', sourceStart: 26, sourceEnd: note.content.length, sourceExcerpt: note.content.slice(26), context: null, commitment: { label: 'Physio', kind: 'commitment', startsLocal: null, endsLocal: null, timeZone: null, recurrence: { frequency: 'weekly', interval: 1, endMode: 'unknown', untilDate: null } }, unresolvedFields: ['startsLocal', 'endsLocal', 'timeZone', 'recurrence.end'] }
const response = (body: unknown, status = 200) => ({ ok: status >= 200 && status < 300, status, json: async () => body })
const packet = (suggestions: unknown[] = [context, commitment]) => ({ intakeId: id, intakeRevision: 2, status: 'available', executionId: '22222222-2222-4222-8222-222222222222', draft: { profile: 'context-intake-suggestion-v1', intakeId: id, intakeRevision: 2, suggestions, questions: [{ text: 'When did this begin?' }] } })
function setup(fetcher = vi.fn(async (_path: string,_init?: RequestInit) => response(packet()))) {
  vi.stubGlobal('fetch', fetcher); const onEdit = vi.fn()
  const rendered = render(<ContextSuggestionControls note={note} csrfToken="csrf" onEdit={onEdit} />)
  return { fetcher, onEdit, ...rendered }
}
describe('ContextSuggestionControls', () => {
  it('makes no request until explicit inspect, then reads exact endpoint without caching', async () => {
    const { fetcher } = setup(); expect(fetcher).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: 'Inspect saved suggestions (read only)' }))
    await screen.findByText('Read-only suggestions for source revision 2; not staged or confirmed.')
    expect(fetcher).toHaveBeenCalledWith(`/api/context-intakes/${id}/suggestions?expectedRevision=2`, expect.objectContaining({ method: 'GET', credentials: 'same-origin', cache: 'no-store', signal: expect.any(AbortSignal) }))
  })
  it('offers manual fallback when unavailable', async () => {
    setup(vi.fn(async () => response({ intakeId: id, intakeRevision: 2, status: 'unavailable', executionId: null, draft: null })))
    fireEvent.click(screen.getByRole('button', { name: /Inspect saved/ }))
    expect(await screen.findByText(/Suggestions are unavailable/)).toBeInTheDocument()
  })
  it('parses context and commitment as inert edit-only callbacks, never POSTing', async () => {
    const { fetcher, onEdit } = setup(); fireEvent.click(screen.getByRole('button', { name: /Inspect saved/ }))
    expect(await screen.findByText('Proposed draft: Knee hurts after running')).toBeInTheDocument()
    expect(screen.getByText('Original source excerpt: Knee hurts after running')).toBeInTheDocument()
    expect(screen.getByText('Proposed draft: Physio')).toBeInTheDocument()
    fireEvent.click(screen.getAllByRole('button', { name: 'Edit suggestion' })[1])
    expect(onEdit).toHaveBeenCalledWith(expect.objectContaining({ kind: 'commitment', commitment: expect.objectContaining({ recurrence: expect.objectContaining({ frequency: 'weekly' }) }) }))
    expect(fetcher.mock.calls.every(([, init]) => init?.method === undefined || init.method === 'GET')).toBe(true)
  })
  it.each([
    ['mismatched revision', { ...packet(), intakeRevision: 1 }],
    ['forged excerpt', packet([{ ...context, sourceExcerpt: 'forged' }])],
    ['author override', packet([{ ...context, authorAttribution: 'forged' }])],
    ['confirmation override', packet([{ ...context, confirmed: true }])],
    ['array status', { ...packet(),status:['available'] }],
    ['array calendar kind', packet([{ ...commitment,commitment:{ ...commitment.commitment,kind:['commitment'] } }])],
    ['array recurrence', packet([{ ...commitment,commitment:{ ...commitment.commitment,recurrence:{...commitment.commitment.recurrence,frequency:['weekly']} } }])],
    ['array end mode', packet([{ ...commitment,commitment:{ ...commitment.commitment,recurrence:{...commitment.commitment.recurrence,endMode:['unknown']} } }])],
  ])('rejects %s', async (_label, body) => {
    setup(vi.fn(async () => response(body))); fireEvent.click(screen.getByRole('button', { name: /Inspect saved/ }))
    expect(await screen.findByRole('alert')).toHaveTextContent(/Could not inspect/)
    expect(screen.queryByRole('button', { name: 'Edit suggestion' })).not.toBeInTheDocument()
    expect(screen.queryByText(/Proposed draft:/)).not.toBeInTheDocument()
  })
  it('aborts and clears old results on note revision change; stale reply cannot leak', async () => {
    let resolve!: (value: ReturnType<typeof response>) => void
    const fetcher = vi.fn((_path: string,_init?: RequestInit) => new Promise<ReturnType<typeof response>>(r => { resolve = r }))
    const { rerender } = setup(fetcher); fireEvent.click(screen.getByRole('button', { name: /Inspect saved/ }))
    const signal = fetcher.mock.calls[0][1]?.signal as AbortSignal
    rerender(<ContextSuggestionControls note={{ ...note, revision: 3 }} csrfToken="csrf" onEdit={vi.fn()} />)
    expect(signal.aborted).toBe(true); resolve(response(packet()))
    await waitFor(() => expect(screen.queryByText('Proposed draft: Physio')).not.toBeInTheDocument())
  })
})
