import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { ContextIntakeControls } from './ContextIntakeControls'
import { commitmentFrom, type ContextItem, type CommitmentItem } from './ContextIntakeClient'

const id = '11111111-1111-4111-8111-111111111111'
const note = { intakeId: id, revision: 1, content: 'Knee hurts after running', sourceCategory: 'user_report',
  authorAttribution: 'athlete', observedOn: '2026-10-14', enteredBy: 'athlete', recordedUtc: '2026-10-14T10:00:00Z' }
const response = (body: unknown, ok = true, status = 200) => ({ ok, status, json: async () => body })
function confirmResponse(body: unknown, source = note) {
  const submitted = body as { contexts: ContextItem[]; commitments: CommitmentItem[] }
  const contexts = submitted.contexts.map((item, index) => ({ ...item.context,
    contextId: item.contextId ?? `22222222-2222-4222-8222-${String(index + 1).padStart(12, '0')}`,
    revision: (item.expectedContextRevision ?? 0) + 1, enteredBy: 'authenticated_user', retired: false }))
  const commitments = submitted.commitments.map((item, index) => ({ reference: {
    commitmentId: item.commitmentId ?? `33333333-3333-4333-8333-${String(index + 1).padStart(12, '0')}`,
    revision: (item.expectedCommitmentRevision ?? 0) + 1, intakeId: source.intakeId, intakeRevision: source.revision,
    contextId: item.context?.contextId ?? (item.contextItemIndex === undefined ? null : contexts[item.contextItemIndex]?.contextId),
    contextRevision: item.context?.revision ?? (item.contextItemIndex === undefined ? null : contexts[item.contextItemIndex]?.revision) },
    definition: item.definition, eventId: null, sourceCategory: source.sourceCategory, enteredBy: 'authenticated_user', confirmedUtc: '2026-10-15T12:00:00Z',
    authorAttribution: source.authorAttribution, needsReconfirmation: false, sourceChanged: false, contextChanged: false }))
  return { replayed: false, contexts, commitments }
}
const empty = (path: string) => path.endsWith('/links') || path === '/api/commitments' ? [] : path === '/api/context-lifecycle' ? { items: [] } : [note]
function setup(fetcher = vi.fn(async (path: string, _init?: RequestInit) => response(empty(path)))) {
  vi.stubGlobal('fetch', fetcher)
  const onConfirmed = vi.fn()
  render(<ContextIntakeControls csrfToken="csrf-token" onConfirmed={onConfirmed} />)
  return { fetcher, onConfirmed }
}
async function chooseNote() {
  const select = await screen.findByLabelText('Saved original note')
  fireEvent.change(select, { target: { value: id } })
  await screen.findByText(/Source: User report/)
  await waitFor(() => expect(screen.getByRole('button', { name: 'Draft entire note as context' })).toBeEnabled())
}

describe('ContextIntakeControls', () => {
  it('saves an original note only with authenticated POST; blank observation date stays blank and does not confirm', async () => {
    const fetcher = vi.fn(async (path: string, init?: RequestInit) => path === '/api/context-intakes' && init?.method === 'POST'
      ? response(note) : response([]))
    const { onConfirmed } = setup(fetcher)
    fireEvent.change(await screen.findByLabelText('Original wording'), { target: { value: 'Knee hurts after running' } })
    expect(screen.getByLabelText('Original observation date')).toHaveValue('')
    fireEvent.click(screen.getByRole('button', { name: 'Save original note only' }))
    expect(fetcher.mock.calls.some(([,init]) => init?.method==='POST')).toBe(false)
    fireEvent.change(screen.getByLabelText('Original observation date'), { target: { value: '2026-10-14' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save original note only' }))
    await screen.findByText(/Original note saved/)
    const post = fetcher.mock.calls.find(([, init]) => init?.method === 'POST')
    expect(post?.[0]).toBe('/api/context-intakes')
    expect(post?.[1]).toMatchObject({ credentials: 'same-origin', headers: { 'X-CSRF-Token': 'csrf-token' } })
    expect(JSON.parse(String(post?.[1]?.body))).toMatchObject({ content: 'Knee hurts after running', observedOn: '2026-10-14' })
    expect(fetcher.mock.calls.some(([path]) => String(path).includes('/confirm'))).toBe(false)
    expect(onConfirmed).toHaveBeenCalledTimes(1)
  })

  it('loads only linked current items and drafts selected wording without retyping or network persistence', async () => {
    const fetcher = vi.fn(async (path: string, _init?: RequestInit) => response(empty(path)))
    setup(fetcher); await chooseNote()
    expect(fetcher.mock.calls.some(([path]) => path === `/api/context-intakes/${id}/links`)).toBe(true)
    expect(fetcher.mock.calls.some(([path]) => path === '/api/context-lifecycle')).toBe(true)
    const wording = screen.getByLabelText('Select original wording') as HTMLTextAreaElement
    wording.setSelectionRange(0, 10); fireEvent.select(wording)
    fireEvent.click(screen.getByRole('button', { name: 'Draft context from selection' }))
    fireEvent.change(screen.getByLabelText('Topic'), { target: { value: 'symptom' } })
    fireEvent.click(screen.getByRole('button', { name: 'Stage item for confirmation' }))
    expect(screen.getByLabelText('Pending confirmation')).toHaveTextContent('Knee hurts')
    expect(fetcher.mock.calls.some(([, init]) => init?.method === 'POST')).toBe(false)
  })

  it('stages context and precise weekly commitment independently, then confirms a shared revision batch', async () => {
    const fetcher = vi.fn(async (path: string, init?: RequestInit) => path.endsWith('/confirm') ? response(confirmResponse(JSON.parse(String(init?.body)))) : response(empty(path)))
    setup(fetcher); await chooseNote()
    fireEvent.click(screen.getByRole('button', { name: 'Draft entire note as context' }))
    fireEvent.change(screen.getByLabelText('Topic'), { target: { value: 'symptom' } })
    fireEvent.click(screen.getByRole('button', { name: 'Stage item for confirmation' }))
    fireEvent.click(screen.getByRole('button', { name: 'Draft commitment or availability' }))
    fireEvent.change(screen.getByLabelText('Calendar item label'), { target: { value: 'Physio' } })
    fireEvent.change(screen.getByLabelText('Item timezone'), { target: { value: 'Europe/Prague' } })
    fireEvent.change(screen.getByLabelText('Starts (local)'), { target: { value: '2026-10-21T09:00' } })
    fireEvent.change(screen.getByLabelText('Ends (local)'), { target: { value: '2026-10-21T10:00' } })
    fireEvent.change(screen.getByLabelText('Repeats'), { target: { value: 'weekly' } })
    fireEvent.click(screen.getByRole('button', { name: 'Stage item for confirmation' }))
    expect(fetcher.mock.calls.some(([, init]) => init?.method === 'POST' && String(init.body).includes('Physio'))).toBe(false)
    fireEvent.click(screen.getByRole('button', { name: 'Confirm selected items' }))
    await waitFor(() => expect(fetcher.mock.calls.some(([path]) => String(path).endsWith('/confirm'))).toBe(true))
    const [, init] = fetcher.mock.calls.find(([path]) => String(path).endsWith('/confirm'))!
    expect(init).toMatchObject({ method: 'POST', credentials: 'same-origin', headers: { 'X-CSRF-Token': 'csrf-token' } })
    const body = JSON.parse(String(init?.body))
    expect(body).toMatchObject({ expectedRevision: 1, contexts: [{ context: { content: 'Knee hurts after running', category: 'symptom' } }],
      commitments: [{ definition: { label: 'Physio', timeZone: 'Europe/Prague', startsLocal: '2026-10-21T09:00', endsLocal: '2026-10-21T10:00', recurrence: { frequency: 'weekly', interval: 1, endOn: null }, exceptions: [] } }] })
    expect(body.confirmationId).toMatch(/^[0-9a-f-]{36}$/)
  })

  it('confirms one pending context without implicitly confirming the other', async () => {
    const fetcher = vi.fn(async (path: string, init?: RequestInit) => path.endsWith('/confirm') ? response(confirmResponse(JSON.parse(String(init?.body)))) : response(empty(path)))
    setup(fetcher); await chooseNote()
    for (const category of ['symptom', 'goal']) {
      fireEvent.click(screen.getByRole('button', { name: 'Draft entire note as context' }))
      fireEvent.change(screen.getByLabelText('Topic'), { target: { value: category } })
      fireEvent.click(screen.getByRole('button', { name: 'Stage item for confirmation' }))
    }
    const pending = screen.getByLabelText('Pending confirmation')
    const groups = within(pending).getAllByRole('group')
    fireEvent.click(within(groups[0]).getByRole('button', { name: 'Confirm this item' }))
    await waitFor(() => expect(fetcher.mock.calls.filter(([path]) => String(path).endsWith('/confirm'))).toHaveLength(1))
    const sent = fetcher.mock.calls.find(([path]) => String(path).endsWith('/confirm'))!
    expect(JSON.parse(String(sent[1]?.body)).contexts).toHaveLength(1)
    expect(within(screen.getByLabelText('Pending confirmation')).getAllByRole('group')).toHaveLength(1)
    expect(screen.getByLabelText('Pending confirmation')).toHaveTextContent('goal')
    expect(fetcher.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(1)
  })

  it('reconfirms a source-changed current link using its exact revision, retaining old wording until confirmation', async () => {
    const contextId = '22222222-2222-4222-8222-222222222222'
    const record = { contextId, revision: 2, category: 'symptom', sourceCategory: 'user_report', authorAttribution: 'athlete', observedOn: '2026-10-14',
      applicableFrom: null, applicableUntil: null, sport: 'running', activityId: 'run-1', reviewId: 'review-1', content: 'Current immutable wording',
      restrictionKind: null, restrictionValue: null, restrictionUnit: null, enteredBy: 'athlete', retired: false }
    const view = { context: record, lifecycle: { resolution: 'unresolved', reviewDueOn: '2026-11-01' }, state: 'historical', needsReview: true, sourceChanged: true, reasonFlags: [] }
    const fetcher = vi.fn(async (path: string, init?: RequestInit) => path.endsWith('/confirm') ? response(confirmResponse(JSON.parse(String(init?.body)))) :
      path === '/api/context-lifecycle' ? response({ items: [view] }) : path.endsWith('/links') ? response([
        { contextId, contextRevision: 2 }, { contextId, contextRevision: 1 }]) : response(empty(path)))
    setup(fetcher); await chooseNote()
    expect(screen.getByText(/source changed: reconfirm/)).toBeInTheDocument()
    expect(screen.getByText('Current immutable wording')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Update or reconfirm context' }))
    expect(screen.getByLabelText('Review due (optional)')).toHaveValue('2026-11-01')
    expect(screen.getByLabelText('Sport (optional)')).toHaveValue('running')
    expect(screen.getByLabelText('Activity ID (optional)')).toHaveValue('run-1')
    expect(screen.getByLabelText('Review ID (optional)')).toHaveValue('review-1')
    expect(screen.getByLabelText('Unconfirmed item editor')).toHaveTextContent('Current immutable wording')
    fireEvent.click(screen.getByRole('button', { name: 'Stage item for confirmation' }))
    fireEvent.click(within(screen.getByLabelText('Pending confirmation')).getByRole('button', { name: 'Confirm this item' }))
    await waitFor(() => expect(fetcher.mock.calls.some(([path]) => String(path).endsWith('/confirm'))).toBe(true))
    const post = fetcher.mock.calls.find(([path]) => String(path).endsWith('/confirm'))!
    expect(JSON.parse(String(post[1]?.body)).contexts[0]).toMatchObject({ contextId, expectedContextRevision: 2,
      context: { content: 'Current immutable wording', sport: 'running', activityId: 'run-1', reviewId: 'review-1', authorAttribution: 'athlete' },
      lifecycle: { resolution: 'unresolved', reviewDueOn: '2026-11-01' } })
    expect(fetcher.mock.calls.filter(([path, init]) => path === '/api/context-intakes' && init?.method === 'POST')).toHaveLength(0)
    expect(screen.getByText('Current immutable wording')).toBeInTheDocument()
  })

  it('rejects an oversized selected batch without splitting or posting', async () => {
    const fetcher = vi.fn(async (path: string, _init?: RequestInit) => response(empty(path)))
    setup(fetcher); await chooseNote()
    for (let index = 0; index < 5; index++) {
      fireEvent.click(screen.getByRole('button', { name: 'Draft entire note as context' }))
      fireEvent.change(screen.getByLabelText('Context text'), { target: { value: '😀'.repeat(2000) } })
      fireEvent.click(screen.getByRole('button', { name: 'Stage item for confirmation' }))
    }
    fireEvent.click(screen.getByRole('button', { name: 'Confirm selected items' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('exceeds 32 KiB')
    expect(fetcher.mock.calls.some(([, init]) => init?.method === 'POST')).toBe(false)
    expect(within(screen.getByLabelText('Pending confirmation')).getAllByRole('group')).toHaveLength(5)
  })

  it('blocks retry after unknown confirmation outcome and reloads latest source to clear drafts', async () => {
    let latest = note
    const fetcher = vi.fn(async (path: string, _init?: RequestInit) => {
      if (path.endsWith('/confirm')) throw new Error('network failure')
      if (path === '/api/context-intakes') return response([latest])
      return response(empty(path))
    })
    setup(fetcher); await chooseNote()
    fireEvent.click(screen.getByRole('button', { name: 'Draft entire note as context' }))
    fireEvent.click(screen.getByRole('button', { name: 'Stage item for confirmation' }))
    fireEvent.click(within(screen.getByLabelText('Pending confirmation')).getByRole('button', { name: 'Confirm this item' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Confirmation outcome unknown')
    const confirm = screen.getByRole('button', { name: 'Confirm this item' })
    expect(confirm).toBeDisabled()
    latest = { ...note, revision: 2, content: 'Externally revised note' }
    fireEvent.click(screen.getByRole('button', { name: 'Reload confirmed items and discard drafts' }))
    expect(await screen.findByText(/Externally revised note/)).toBeInTheDocument()
    expect(screen.queryByLabelText('Pending confirmation')).not.toBeInTheDocument()
    expect(fetcher.mock.calls.filter(([path]) => String(path).endsWith('/confirm'))).toHaveLength(1)
  })

  it('does not stage a relative Wednesday commitment without explicit date, times, and timezone', async () => {
    setup(); await chooseNote()
    fireEvent.click(screen.getByRole('button', { name: 'Draft commitment or availability' }))
    expect(screen.getByText(/No date is guessed/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Stage item for confirmation' }))
    expect(screen.queryByLabelText('Pending confirmation')).not.toBeInTheDocument()
  })

  it('keeps drafts after a 409 and does not automatically retry confirmation', async () => {
    const fetcher = vi.fn(async (path: string, _init?: RequestInit) => path.endsWith('/confirm') ? response({}, false, 409) : response(empty(path)))
    setup(fetcher); await chooseNote()
    fireEvent.click(screen.getByRole('button', { name: 'Draft entire note as context' }))
    fireEvent.click(screen.getByRole('button', { name: 'Stage item for confirmation' }))
    fireEvent.click(screen.getByRole('button', { name: 'Confirm this item' }))
    await screen.findByRole('alert')
    expect(screen.getByLabelText('Pending confirmation')).toBeInTheDocument()
    expect(fetcher.mock.calls.filter(([path]) => String(path).endsWith('/confirm'))).toHaveLength(1)
  })

  it('fails closed on a malformed saved intake response', async () => {
    const fetcher = vi.fn(async () => response([{ ...note, intakeId: 'not-a-uuid' }]))
    setup(fetcher)
    expect(await screen.findByRole('alert')).toHaveTextContent('Intake notes unavailable')
    expect(screen.queryByRole('button', { name: 'Draft entire note as context' })).not.toBeInTheDocument()
  })

  it.each([500, 502])('blocks retry after a %s confirmation response with unknown outcome', async status => {
    const fetcher = vi.fn(async (path: string, _init?: RequestInit) => path.endsWith('/confirm') ? response({}, false, status) : response(empty(path)))
    setup(fetcher); await chooseNote()
    fireEvent.click(screen.getByRole('button', { name: 'Draft entire note as context' }))
    fireEvent.click(screen.getByRole('button', { name: 'Stage item for confirmation' }))
    fireEvent.click(screen.getByRole('button', { name: 'Confirm this item' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Confirmation outcome unknown')
    expect(screen.getByRole('button', { name: 'Confirm this item' })).toBeDisabled()
    expect(fetcher.mock.calls.filter(([path]) => String(path).endsWith('/confirm'))).toHaveLength(1)
  })

  it('fails closed on empty HTTP 200 confirmation for a nonempty batch', async () => {
    const fetcher = vi.fn(async (path: string, _init?: RequestInit) => path.endsWith('/confirm') ? response({ replayed: false, contexts: [], commitments: [] }) : response(empty(path)))
    setup(fetcher); await chooseNote()
    fireEvent.click(screen.getByRole('button', { name: 'Draft entire note as context' }))
    fireEvent.click(screen.getByRole('button', { name: 'Stage item for confirmation' }))
    fireEvent.click(screen.getByRole('button', { name: 'Confirm this item' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Confirmation outcome unknown')
    expect(screen.getByLabelText('Pending confirmation')).toBeInTheDocument()
    expect(screen.queryByText(/confirmed successfully/i)).not.toBeInTheDocument()
    expect(fetcher.mock.calls.filter(([path]) => String(path).endsWith('/confirm'))).toHaveLength(1)
  })

  it('edits a staged item without retyping original wording or persisting the draft', async () => {
    const fetcher=vi.fn(async (path: string, _init?: RequestInit) => response(empty(path)))
    setup(fetcher); await chooseNote()
    fireEvent.click(screen.getByRole('button',{name:'Draft entire note as context'}))
    fireEvent.change(screen.getByLabelText('Topic'),{target:{value:'symptom'}})
    fireEvent.change(screen.getByLabelText('Review due (optional)'),{target:{value:'2026-10-21'}})
    fireEvent.click(screen.getByRole('button',{name:'Stage item for confirmation'}))
    fireEvent.click(screen.getByRole('button',{name:'Edit draft'}))
    expect(screen.getByLabelText('Context text')).toHaveValue(note.content)
    expect(screen.getByLabelText('Topic')).toHaveValue('symptom')
    expect(screen.getByLabelText('Review due (optional)')).toHaveValue('2026-10-21')
    expect(screen.queryByLabelText('Pending confirmation')).not.toBeInTheDocument()
    expect(fetcher.mock.calls.some(([,init]) => init?.method==='POST')).toBe(false)
  })

  it('rejects malformed nested commitment exception rows', () => {
    expect(() => commitmentFrom({ reference: { commitmentId: id, revision: 1, intakeId: id, intakeRevision: 1, contextId: null, contextRevision: null },
      definition: { label: 'Run', kind: 'commitment', timeZone: 'UTC', startsLocal: '2026-10-21T09:00', endsLocal: '2026-10-21T10:00', recurrence: null, exceptions: [null] },
      needsReconfirmation: false, sourceChanged: false, contextChanged: false, authorAttribution: 'athlete' })).toThrow('Invalid private response')
  })

  it('inspects an exact-revision context suggestion read-only and requires explicit resolution before confirmation', async () => {
    const content = 'Knee pain after running.'
    const source = { ...note, content }
    const suggestion = { kind: 'context', sourceStart: 0, sourceEnd: content.length, sourceExcerpt: content, commitment: null,
      context: { category: 'symptom', observedOn: null, applicableFrom: null, applicableUntil: null, applicableFromMode: 'unknown',
        applicableUntilMode: 'unknown', content }, unresolvedFields: ['observedOn', 'applicableFrom', 'applicableUntil'] }
    const saved = { intakeId: id, intakeRevision: 1, status: 'available', executionId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
      draft: { profile: 'context-intake-suggestion-v1', intakeId: id, intakeRevision: 1, suggestions: [suggestion],
        questions: [{ text: 'When was this observed, and when does it apply?' }] } }
    const fetcher = vi.fn(async (path: string, init?: RequestInit) => path.endsWith('/suggestions?expectedRevision=1') ? response(saved) :
      path.endsWith('/confirm') ? response(confirmResponse(JSON.parse(String(init?.body)), source), true) : response(path === '/api/context-intakes' ? [source] : empty(path)))
    setup(fetcher); await chooseNote()
    expect(fetcher.mock.calls.some(([, init]) => init?.method === 'POST')).toBe(false)
    fireEvent.click(screen.getByRole('button', { name: 'Inspect saved suggestions (read only)' }))
    expect(await screen.findByText('Question: When was this observed, and when does it apply?')).toBeInTheDocument()
    expect(fetcher.mock.calls.some(([path]) => path === `/api/context-intakes/${id}/suggestions?expectedRevision=1`)).toBe(true)
    fireEvent.click(screen.getByRole('button', { name: 'Edit suggestion' }))
    expect(screen.getByLabelText('Context text')).toHaveValue(content)
    expect(screen.getByLabelText('Observed on')).toHaveValue('')
    expect(screen.getByLabelText('Unconfirmed item editor')).toHaveTextContent('athlete')
    fireEvent.click(screen.getByRole('button', { name: 'Stage item for confirmation' }))
    expect(screen.queryByLabelText('Pending confirmation')).not.toBeInTheDocument()
    const resolve = within(screen.getByLabelText('Unconfirmed item editor')).getAllByRole('checkbox')
    expect(resolve).toHaveLength(3)
    fireEvent.change(screen.getByLabelText('Observed on'), { target: { value: '2026-10-14' } })
    resolve.forEach(box => fireEvent.click(box))
    fireEvent.click(screen.getByRole('button', { name: 'Stage item for confirmation' }))
    expect(screen.getByLabelText('Pending confirmation')).toBeInTheDocument()
    expect(fetcher.mock.calls.some(([, init]) => init?.method === 'POST')).toBe(false)
    fireEvent.click(within(screen.getByLabelText('Pending confirmation')).getByRole('button', { name: 'Confirm this item' }))
    await waitFor(() => expect(fetcher.mock.calls.some(([path]) => path.endsWith('/confirm'))).toBe(true))
    const post = fetcher.mock.calls.find(([path]) => path.endsWith('/confirm'))!
    expect(post[0]).toBe(`/api/context-intakes/${id}/confirm`)
    expect(post[1]).toMatchObject({ method: 'POST', credentials: 'same-origin', headers: { 'X-CSRF-Token': 'csrf-token' } })
    const body = JSON.parse(String(post[1]?.body))
    expect(body).toMatchObject({ expectedRevision: 1, contexts: [{ context: { content, category: 'symptom', observedOn: '2026-10-14',
      applicableFrom: null, applicableUntil: null, sourceCategory: 'user_report', authorAttribution: 'athlete' } }], commitments: [] })
    expect(body).not.toHaveProperty('activeItemId')
    expect(body).not.toHaveProperty('confirmedItemId')
  })

  it('keeps a suggestion-derived commitment blocked until unknown calendar choices are explicit', async () => {
    const content = 'Physio appointment sometime next week'
    const source = { ...note, content }
    const saved = { intakeId: id, intakeRevision: 1, status: 'available', executionId: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
      draft: { profile: 'context-intake-suggestion-v1', intakeId: id, intakeRevision: 1, suggestions: [{ kind: 'commitment', sourceStart: 0,
        sourceEnd: content.length, sourceExcerpt: content, context: null, unresolvedFields: ['kind', 'startsLocal', 'endsLocal', 'timeZone', 'recurrence'],
        commitment: { label: 'Physio appointment', kind: null, startsLocal: null, endsLocal: null, timeZone: null,
          recurrence: null } }], questions: [{ text: 'What date, time, timezone, and recurrence?' }] } }
    const fetcher = vi.fn(async (path: string, init?: RequestInit) => path.endsWith('/suggestions?expectedRevision=1') ? response(saved) :
      path.endsWith('/confirm') ? response({}, false, 409) : response(path === '/api/context-intakes' ? [source] : empty(path)))
    setup(fetcher); await chooseNote()
    fireEvent.click(screen.getByRole('button', { name: 'Inspect saved suggestions (read only)' }))
    await screen.findByText('Question: What date, time, timezone, and recurrence?')
    fireEvent.click(screen.getByRole('button', { name: 'Edit suggestion' }))
    expect(screen.getByLabelText('Repeats')).toHaveValue('')
    fireEvent.click(screen.getByRole('button', { name: 'Stage item for confirmation' }))
    expect(screen.queryByLabelText('Pending confirmation')).not.toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Item timezone'), { target: { value: 'Europe/Prague' } })
    fireEvent.change(screen.getByLabelText('Starts (local)'), { target: { value: '2026-10-21T09:00' } })
    fireEvent.change(screen.getByLabelText('Ends (local)'), { target: { value: '2026-10-21T10:00' } })
    fireEvent.change(screen.getByLabelText('Calendar kind'), { target: { value: 'commitment' } })
    fireEvent.change(screen.getByLabelText('Repeats'), { target: { value: '' } })
    within(screen.getByLabelText('Unconfirmed item editor')).getAllByRole('checkbox').forEach(box => fireEvent.click(box))
    fireEvent.click(screen.getByRole('button', { name: 'Stage item for confirmation' }))
    expect(screen.getByLabelText('Pending confirmation')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Confirm this item' }))
    expect(await screen.findByRole('alert')).toBeInTheDocument()
    expect(screen.getByLabelText('Pending confirmation')).toBeInTheDocument()
    expect(fetcher.mock.calls.filter(([path]) => path.endsWith('/confirm'))).toHaveLength(1)
    expect(fetcher.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(1)
    const post=fetcher.mock.calls.find(([path])=>path.endsWith('/confirm'))!
    expect(JSON.parse(String(post[1]?.body)).commitments[0].definition.recurrence).toBeNull()
  })
})
