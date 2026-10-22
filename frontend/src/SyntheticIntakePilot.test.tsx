import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { SyntheticIntakePilot, parseSyntheticSuggestions } from './SyntheticIntakePilot'
import type { Commitment, CommitmentItem, Intake } from './ContextIntakeClient'

const note: Intake = { intakeId: '11111111-1111-4111-8111-111111111111', revision: 1,
  content: 'A rehearsal on Tuesday evening is tentative; its date, times, timezone and repetition are undecided.',
  sourceCategory: 'user_report', authorAttribution: 'Synthetic athlete', enteredBy: 'authenticated_user',
  observedOn: '2026-10-21', recordedUtc: '2026-10-21T12:00:00Z' }
function fixture(source = note) {
  return { profile: 'synthetic-intake-pilot-v1', intakeId: source.intakeId, intakeRevision: source.revision, status: 'available',
    provenance: { kind: 'handwritten_frozen_witness', caseId: 'u03', suiteManifestSha256: 'a'.repeat(64), packetSha256: 'b'.repeat(64), wireSha256: 'c'.repeat(64) },
    draft: { profile: 'context-intake-suggestion-v1', intakeId: source.intakeId, intakeRevision: source.revision,
      suggestions: [{ kind: 'commitment', sourceStart: 0, sourceEnd: source.content.length, sourceExcerpt: source.content, context: null,
        commitment: { label: source.content, kind: 'commitment', startsLocal: null, endsLocal: null, timeZone: null, recurrence: null },
        unresolvedFields: ['startsLocal', 'endsLocal', 'timeZone', 'recurrence'] }],
      questions: [{ text: 'What are the rehearsal date, start and end times, timezone, and recurrence details?' }] } }
}
const response = (body: unknown, status = 200) => ({ ok: status >= 200 && status < 300, status, json: async () => body })

function setup(options: { uncertain?: boolean; fixtureBody?: unknown } = {}) {
  let current = { ...note }
  const confirmed: Commitment[] = []
  const fetcher = vi.fn(async (path: string, init?: RequestInit) => {
    if (path === '/api/session') return response({ authenticated: true, csrfToken: 'synthetic-csrf', intervalsConfigured: false })
    if (path === '/api/context-intakes') return response([current])
    if (path.endsWith('/links')) return response([])
    if (path === '/api/context-lifecycle') return response({ items: [] })
    if (path === '/api/commitments') return response(confirmed)
    if (path.includes('/synthetic-intake-pilot/')) return response(options.fixtureBody ?? (current.revision === note.revision ? fixture() : {
      profile: 'synthetic-intake-pilot-v1', intakeId: current.intakeId, intakeRevision: current.revision,
      status: 'unavailable', provenance: null, draft: null,
    }))
    if (path === `/api/context-intakes/${note.intakeId}` && init?.method === 'PUT') {
      const correction = JSON.parse(String(init.body))
      current = { ...current, ...correction.note, revision: current.revision + 1 }
      return response(current)
    }
    if (path.endsWith('/confirm') && init?.method === 'POST') {
      if (options.uncertain) throw new Error('Synthetic lost reply')
      const submitted = JSON.parse(String(init.body)) as { commitments: CommitmentItem[] }
      const records = submitted.commitments.map((item): Commitment => ({
        reference: { commitmentId: '22222222-2222-4222-8222-222222222222', revision: 1,
          intakeId: current.intakeId, intakeRevision: current.revision, contextId: null, contextRevision: null },
        definition: item.definition, needsReconfirmation: false, sourceChanged: false, contextChanged: false, authorAttribution: current.authorAttribution,
      }))
      confirmed.push(...records)
      return response({ contexts: [], commitments: records, replayed: false })
    }
    throw new Error('Unexpected synthetic endpoint')
  })
  vi.stubGlobal('fetch', fetcher)
  render(<SyntheticIntakePilot />)
  return { fetcher, confirmed }
}
async function inspect() {
  await screen.findByRole('option', { name: /A rehearsal on Tuesday/ })
  fireEvent.change(await screen.findByLabelText('Saved original note'), { target: { value: note.intakeId } })
  await waitFor(() => expect(screen.getByRole('button', { name: /Inspect saved suggestions/ })).toBeEnabled())
  fireEvent.click(screen.getByRole('button', { name: /Inspect saved suggestions/ }))
  await screen.findByText(`Original source excerpt: ${note.content}`)
}
function editAndResolve() {
  fireEvent.click(screen.getByRole('button', { name: 'Edit suggestion' }))
  fireEvent.change(screen.getByLabelText('Starts (local)'), { target: { value: '2026-11-10T18:00' } })
  fireEvent.change(screen.getByLabelText('Ends (local)'), { target: { value: '2026-11-10T19:00' } })
  fireEvent.change(screen.getByLabelText('Item timezone'), { target: { value: 'UTC' } })
  for (const checkbox of screen.getAllByRole('checkbox', { name: /I resolved/ })) fireEvent.click(checkbox)
}

describe('synthetic pilot provenance parser', () => {
  it('uses the shared source-bound parser without a fake execution identity', () => {
    expect(parseSyntheticSuggestions(fixture(), note).draft).toHaveLength(1)
    expect(fixture()).not.toHaveProperty('executionId')
  })
  it.each(['wrong-source', 'wrong-revision', 'wrong-origin', 'wrong-hash', 'forged-excerpt', 'execution-masquerade'])('rejects %s', mutation => {
    const body = structuredClone(fixture())
    if (mutation === 'wrong-source') body.intakeId = 'different'
    if (mutation === 'wrong-revision') body.intakeRevision++
    if (mutation === 'wrong-origin') body.provenance.kind = 'model_output'
    if (mutation === 'wrong-hash') body.provenance.packetSha256 = 'wrong'
    if (mutation === 'forged-excerpt') body.draft.suggestions[0].sourceExcerpt = 'Absent source'
    if (mutation === 'execution-masquerade') Object.assign(body, { executionId: '22222222-2222-4222-8222-222222222222' })
    expect(() => parseSyntheticSuggestions(body, note)).toThrow()
  })
})

describe('guarded synthetic pilot journey', () => {
  it('inspects and edits without writes, blocks every unknown, stages inertly and confirms only by explicit action', async () => {
    const { fetcher, confirmed } = setup()
    expect(await screen.findByRole('note')).toHaveTextContent('No model execution. Disposable storage.')
    expect(screen.queryByLabelText('Original wording')).not.toBeInTheDocument()
    expect(fetcher.mock.calls.some(([path]) => path.includes('/synthetic-intake-pilot/'))).toBe(false)
    await inspect()
    fireEvent.click(screen.getByRole('button', { name: 'Edit suggestion' }))
    const stage = screen.getByRole('button', { name: 'Stage item for confirmation' })
    const unknowns = screen.getAllByRole('checkbox', { name: /I resolved/ })
    expect(unknowns).toHaveLength(4)
    for (const checkbox of unknowns.slice(0, -1)) { fireEvent.click(checkbox); expect(stage).toBeDisabled() }
    fireEvent.click(unknowns.at(-1)!)
    fireEvent.submit(screen.getByLabelText('Unconfirmed item editor'))
    expect(await screen.findByRole('alert')).toHaveTextContent('Provide valid explicit calendar dates')
    fireEvent.change(screen.getByLabelText('Starts (local)'), { target: { value: '2026-11-10T18:00' } })
    fireEvent.change(screen.getByLabelText('Ends (local)'), { target: { value: '2026-11-10T19:00' } })
    fireEvent.change(screen.getByLabelText('Item timezone'), { target: { value: 'UTC' } })
    fireEvent.click(stage)
    expect(await screen.findByLabelText('Pending confirmation')).toBeInTheDocument()
    expect(confirmed).toHaveLength(0)
    expect(fetcher.mock.calls.every(([, init]) => !init?.method || init.method === 'GET')).toBe(true)
    fireEvent.click(screen.getByRole('button', { name: 'Confirm selected items' }))
    await screen.findByText(/1 item\(s\) confirmed/)
    expect(confirmed).toHaveLength(1)
    const writes = fetcher.mock.calls.filter(([, init]) => init?.method === 'POST')
    expect(writes).toHaveLength(1)
    expect(writes[0][0]).toBe(`/api/context-intakes/${note.intakeId}/confirm`)
    expect(writes[0][1]?.headers).toMatchObject({ 'X-CSRF-Token': 'synthetic-csrf' })
    fireEvent.click(screen.getByRole('button', { name: 'Reload confirmed items and discard drafts' }))
    await waitFor(() => expect(screen.getByText(/commitment: A rehearsal/)).toBeInTheDocument())
    expect(fetcher.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(1)
  })

  it('blocks reversed time and invalid recurrence before staging', async () => {
    const { confirmed } = setup()
    await inspect(); editAndResolve()
    fireEvent.change(screen.getByLabelText('Ends (local)'), { target: { value: '2026-11-10T17:00' } })
    fireEvent.submit(screen.getByLabelText('Unconfirmed item editor'))
    expect(await screen.findByRole('alert')).toHaveTextContent('End time must follow start')
    expect(screen.queryByLabelText('Pending confirmation')).not.toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Ends (local)'), { target: { value: '2026-11-10T19:00' } })
    fireEvent.change(screen.getByLabelText('Repeats'), { target: { value: 'weekly' } })
    fireEvent.change(screen.getByLabelText('Repeat interval'), { target: { value: '0' } })
    fireEvent.submit(screen.getByLabelText('Unconfirmed item editor'))
    expect(await screen.findByRole('alert')).toHaveTextContent('valid explicit calendar dates')
    expect(confirmed).toHaveLength(0)
  })

  it('source correction clears drafts and makes the old fixture unavailable', async () => {
    const { fetcher, confirmed } = setup()
    await inspect(); editAndResolve()
    fireEvent.click(screen.getByRole('button', { name: 'Stage item for confirmation' }))
    expect(screen.getByLabelText('Pending confirmation')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Correct original source' }))
    expect(screen.queryByLabelText('Pending confirmation')).not.toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Original wording'), { target: { value: note.content + ' Synthetic correction.' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save source revision' }))
    await screen.findByText(/Original note saved/)
    await waitFor(() => expect(screen.getByRole('button', { name: /Inspect saved suggestions/ })).toBeEnabled())
    fireEvent.click(screen.getByRole('button', { name: /Inspect saved suggestions/ }))
    await screen.findByText(/Suggestions are unavailable/)
    expect(screen.queryByRole('button', { name: 'Edit suggestion' })).not.toBeInTheDocument()
    expect(confirmed).toHaveLength(0)
    expect(fetcher.mock.calls.some(([path]) => path.endsWith('/confirm'))).toBe(false)
  })

  it('unknown confirmation outcome blocks retries until explicit reload', async () => {
    const { fetcher } = setup({ uncertain: true })
    await inspect(); editAndResolve()
    fireEvent.click(screen.getByRole('button', { name: 'Stage item for confirmation' }))
    fireEvent.click(screen.getByRole('button', { name: 'Confirm selected items' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Confirmation outcome unknown')
    expect(screen.getByRole('button', { name: 'Confirm selected items' })).toBeDisabled()
    expect(fetcher.mock.calls.filter(([path]) => path.endsWith('/confirm'))).toHaveLength(1)
    fireEvent.click(screen.getByRole('button', { name: 'Reload confirmed items and discard drafts' }))
    await waitFor(() => expect(screen.queryByLabelText('Pending confirmation')).not.toBeInTheDocument())
    expect(fetcher.mock.calls.filter(([path]) => path.endsWith('/confirm'))).toHaveLength(1)
  })

  it('invalid fixture never offers edit or staging', async () => {
    const bad = fixture(); bad.draft.suggestions[0].sourceExcerpt = 'Forged'
    setup({ fixtureBody: bad })
    await screen.findByRole('option', { name: /A rehearsal on Tuesday/ })
    fireEvent.change(await screen.findByLabelText('Saved original note'), { target: { value: note.intakeId } })
    await waitFor(() => expect(screen.getByRole('button', { name: /Inspect saved suggestions/ })).toBeEnabled())
    fireEvent.click(screen.getByRole('button', { name: /Inspect saved suggestions/ }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Could not inspect')
    expect(screen.queryByRole('button', { name: 'Edit suggestion' })).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Pending confirmation')).not.toBeInTheDocument()
  })
})
