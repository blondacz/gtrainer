import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { ManualEventPanel } from './ManualEventPanel'

const token = 'synthetic-csrf-token'
const id = (number: number) => `00000000-0000-0000-0000-${String(number).padStart(12, '0')}`
type Event = { id: string; startDate: string; endDate: string; sport: string; goal: string; notes: string | null }
const event = (number: number, startDate = '2026-10-03', endDate = startDate): Event => ({
  id: id(number), startDate, endDate, sport: `Synthetic sport ${number}`, goal: `Synthetic goal ${number}`, notes: null,
})
function list(events: Event[] = [], evaluatedOn = '2026-10-02') {
  const sorted = [...events].sort((a, b) => a.startDate.localeCompare(b.startDate) || a.endDate.localeCompare(b.endDate) || a.id.localeCompare(b.id))
  return { events, evaluatedOn, nextUpcoming: sorted.find(item => item.startDate >= evaluatedOn) ?? null,
    ongoing: sorted.filter(item => item.startDate < evaluatedOn && item.endDate >= evaluatedOn) }
}
const response = (body: unknown, status = 200) => ({ status, ok: status >= 200 && status < 300, json: async () => body })
function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(done => { resolve = done })
  return { promise, resolve }
}
const tab = (name: string) => fireEvent.click(screen.getByRole('tab', { name }))
function fillDates(startDate = '2026-10-04', endDate = '2026-10-06', sport = 'Synthetic ride') {
  tab('Dates & sport')
  fireEvent.change(screen.getByLabelText('Start date (YYYY-MM-DD)'), { target: { value: startDate } })
  fireEvent.change(screen.getByLabelText('End date (YYYY-MM-DD)'), { target: { value: endDate } })
  fireEvent.change(screen.getByLabelText('Sport (up to 80 characters)'), { target: { value: sport } })
}
function fillGoal(goal = 'Synthetic personal goal') {
  tab('Goal'); fireEvent.change(screen.getByLabelText('Manual goal (up to 2000 characters)'), { target: { value: goal } })
}
function fillNotes(notes: string) {
  tab('Notes'); fireEvent.change(screen.getByLabelText('Current-state notes (optional, up to 4000 characters)'), { target: { value: notes } })
}
const save = () => fireEvent.click(screen.getByRole('button', { name: 'Save manual event' }))

describe('private manual event tile', () => {
  it('mounts read-only, sorts by date/end/id and pages one card with separate ongoing, past and next-upcoming status', async () => {
    const events = [event(4), event(3, '2026-10-02'), event(2, '2026-10-01', '2026-10-05'), event(1, '2020-02-01')]
    const fetchMock = vi.fn(async () => response(list(events)))
    vi.stubGlobal('fetch', fetchMock)
    render(<ManualEventPanel csrfToken={token} />)
    expect(await screen.findByRole('heading', { name: 'Synthetic sport 1' })).toBeInTheDocument()
    expect(screen.getAllByRole('article')).toHaveLength(1)
    expect(screen.getByText('Past')).toBeInTheDocument()
    expect(screen.getByText(/Evaluated on 2026-10-02 \(UTC calendar date\). Upcoming: 2; ongoing: 1; past: 1/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Next event' }))
    expect(screen.getByText('Ongoing')).toBeInTheDocument()
    expect(screen.queryByText(/Next upcoming/)).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Next event' }))
    expect(screen.getByText('Upcoming — Next upcoming')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Next event' }))
    expect(screen.getByText('Upcoming')).toBeInTheDocument()
    expect(screen.queryByText(/Next upcoming/)).not.toBeInTheDocument()
    tab('Goal'); expect(screen.getByLabelText('Manual goal text')).toHaveTextContent('Synthetic goal 4')
    tab('Notes'); expect(screen.getByText('No notes supplied.')).toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(fetchMock).toHaveBeenCalledWith('/api/events', expect.objectContaining({ credentials: 'same-origin', cache: 'no-store', signal: expect.any(AbortSignal) }))
    expect(fetchMock.mock.calls[0]).toHaveLength(2)
    expect(window.localStorage.length).toBe(0)
    expect(window.sessionStorage.length).toBe(0)
  })

  it('tie-breaks same-date cards using end date then canonical id', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => response(list([event(3, '2026-10-03', '2026-10-04'), event(2), event(1)]))))
    render(<ManualEventPanel csrfToken={token} />)
    await screen.findByRole('heading', { name: 'Synthetic sport 1' })
    fireEvent.click(screen.getByRole('button', { name: 'Next event' }))
    expect(screen.getByRole('heading', { name: 'Synthetic sport 2' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Next event' }))
    expect(screen.getByRole('heading', { name: 'Synthetic sport 3' })).toBeInTheDocument()
  })

  it('empty list never opens an editor or writes; reload is explicit and independent of paging', async () => {
    const fetchMock = vi.fn(async (_path: string) => response(list()))
    vi.stubGlobal('fetch', fetchMock)
    render(<ManualEventPanel csrfToken={token} />)
    expect(await screen.findByText('No manual events yet.')).toBeInTheDocument()
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledTimes(1)
    fireEvent.click(screen.getByRole('button', { name: 'Reload events' }))
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2))
    expect(fetchMock.mock.calls.every(([path]) => path === '/api/events')).toBe(true)
  })

  it('creates/edits/deletes a multi-day event explicitly, preserves Unicode/whitespace, uses CSRF and only event endpoints', async () => {
    let events: Event[] = []
    const changed = vi.fn()
    const fetchMock = vi.fn(async (path: string, options: RequestInit) => {
      if (!options.method) return response(list(events))
      expect(options.credentials).toBe('same-origin')
      expect(options.headers).toMatchObject({ 'X-CSRF-Token': token })
      if (options.method === 'POST' || options.method === 'PUT') {
        expect(options.headers).toMatchObject({ 'Content-Type': 'application/json' })
        const body = JSON.parse(options.body as string)
        expect(Object.keys(body).sort()).toEqual(['endDate', 'goal', 'notes', 'sport', 'startDate'])
        expect(new TextEncoder().encode(options.body as string).length).toBeLessThanOrEqual(32768)
        const saved = { ...body, id: id(5) } as Event
        events = [saved]
        expect(path).toBe(options.method === 'POST' ? '/api/events' : `/api/events/${id(5)}`)
        return response(saved, options.method === 'POST' ? 201 : 200)
      }
      expect(options.method).toBe('DELETE'); expect(path).toBe(`/api/events/${id(5)}`)
      expect(options.body).toBeUndefined(); events = []; return response(undefined, 204)
    })
    vi.stubGlobal('fetch', fetchMock)
    render(<ManualEventPanel csrfToken={token} onChanged={changed} />)
    await screen.findByText('No manual events yet.')
    fireEvent.click(screen.getByRole('button', { name: 'New manual event' }))
    fillDates('2026-10-04', '2026-10-06', '  Synthetic ride  ')
    expect(screen.queryByLabelText('Manual goal (up to 2000 characters)')).not.toBeInTheDocument()
    fillGoal('  Finish café 🚴\nmy way\t  ')
    expect(screen.queryByLabelText('Start date (YYYY-MM-DD)')).not.toBeInTheDocument()
    fillNotes('  tired? 私のメモ 🏕️\n\tkeep whitespace  ')
    expect(screen.getByRole('textbox')).toHaveAttribute('rows', '3')
    save()
    await screen.findByRole('heading', { name: 'Synthetic ride' })
    expect(events[0]).toMatchObject({ startDate: '2026-10-04', endDate: '2026-10-06', sport: '  Synthetic ride  ', goal: '  Finish café 🚴\nmy way\t  ', notes: '  tired? 私のメモ 🏕️\n\tkeep whitespace  ' })
    expect(changed).toHaveBeenCalledTimes(1)
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Edit manual event' }))
    fillDates('2020-02-28', '2020-03-02')
    tab('Notes')
    expect(screen.getByRole('textbox')).toHaveValue('  tired? 私のメモ 🏕️\n\tkeep whitespace  ')
    fillGoal('  Backdated goal  '); save()
    await screen.findByText('Past')
    expect(changed).toHaveBeenCalledTimes(2)
    fireEvent.click(screen.getByRole('button', { name: 'Delete manual event' }))
    expect(fetchMock.mock.calls.filter(([, options]) => options.method === 'DELETE')).toHaveLength(0)
    let dialog = screen.getByRole('dialog', { name: 'Confirm manual event deletion' })
    expect(within(dialog).getAllByRole('button')).toHaveLength(2)
    expect(within(dialog).getByText(/2020-02-28 to 2020-03-02/)).toBeInTheDocument()
    fireEvent.click(within(dialog).getByRole('button', { name: 'Keep event' }))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Delete manual event' }))
    dialog = screen.getByRole('dialog')
    fireEvent.click(within(dialog).getByRole('button', { name: 'Confirm delete' }))
    await screen.findByText('No manual events yet.')
    expect(changed).toHaveBeenCalledTimes(3)
    expect(fetchMock).toHaveBeenCalledTimes(7)
    expect(fetchMock.mock.calls.every(([path]) => path === '/api/events' || path === `/api/events/${id(5)}`)).toBe(true)
  })

  it('accepts omitted nullable notes without inventing or trimming draft content', async () => {
    const item = event(1)
    const { notes: _notes, ...withoutNotes } = item
    const payload = { events: [withoutNotes], ongoing: [], nextUpcoming: withoutNotes, evaluatedOn: '2026-10-02' }
    const fetchMock = vi.fn().mockResolvedValueOnce(response(payload)).mockImplementation(async (_path: string, options: RequestInit) => {
      if (!options.method) return response(payload)
      expect(JSON.parse(options.body as string).notes).toBeNull()
      return response(item)
    })
    vi.stubGlobal('fetch', fetchMock)
    const view = render(<ManualEventPanel csrfToken={token} />)
    await screen.findByRole('heading', { name: item.sport })
    fireEvent.click(screen.getByRole('button', { name: 'Edit manual event' })); tab('Notes')
    expect(screen.getByRole('textbox')).toHaveValue('')
    save()
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(3))
    view.unmount()
  })

  it.each([
    ['invalid date', '', '2026-10-06', 'Synthetic ride', 'goal', null, /real calendar dates/],
    ['non-leap date', '2025-02-29', '2025-03-01', 'Synthetic ride', 'goal', null, /real calendar dates/],
    ['reversed dates', '2026-10-06', '2026-10-04', 'Synthetic ride', 'goal', null, /End date must/],
    ['overlong range', '2020-01-01', '2031-01-01', 'Synthetic ride', 'goal', null, /3660 days later/],
    ['blank sport', '2026-10-04', '2026-10-06', ' \t ', 'goal', null, /Enter a sport/],
    ['long sport', '2026-10-04', '2026-10-06', 's'.repeat(81), 'goal', null, /Enter a sport/],
    ['blank goal', '2026-10-04', '2026-10-06', 'Synthetic ride', ' \n ', null, /Enter a manual goal/],
    ['long goal', '2026-10-04', '2026-10-06', 'Synthetic ride', 'g'.repeat(2001), null, /Enter a manual goal/],
    ['long notes', '2026-10-04', '2026-10-06', 'Synthetic ride', 'goal', 'n'.repeat(4001), /notes allow up to/],
    ['NUL notes', '2026-10-04', '2026-10-06', 'Synthetic ride', 'goal', 'before\0after', /no control characters/],
    ['C1 notes', '2026-10-04', '2026-10-06', 'Synthetic ride', 'goal', 'before\u0085after', /no control characters/],
  ] as const)('rejects %s without any write', async (_name, start, end, sport, goal, notes, message) => {
    const fetchMock = vi.fn(async () => response(list()))
    vi.stubGlobal('fetch', fetchMock)
    render(<ManualEventPanel csrfToken={token} />)
    await screen.findByText('No manual events yet.')
    fireEvent.click(screen.getByRole('button', { name: 'New manual event' }))
    fillDates(start, end, sport); fillGoal(goal); if (notes !== null) fillNotes(notes)
    save()
    expect(screen.getByRole('alert')).toHaveTextContent(message)
    expect(fetchMock).toHaveBeenCalledTimes(1)
    if (notes !== null) expect(screen.getByRole('textbox')).toHaveValue(notes)
  })

  it('pages the entire long goal/notes including Unicode and line breaks without truncation or HTML interpretation', async () => {
    const item = { ...event(1, '1999-01-01'), goal: '🏕️ café <script>not code</script>\n'.repeat(50), notes: '\n'.repeat(45) + ' n '.repeat(900) + 'END私🚴' }
    vi.stubGlobal('fetch', vi.fn(async () => response(list([item]))))
    render(<ManualEventPanel csrfToken={token} />)
    await screen.findByText('Past')
    for (const [name, label, expected] of [['Goal', 'Manual goal', item.goal], ['Notes', 'Current-state notes', item.notes]]) {
      tab(name)
      let restored = ''
      do {
        const text = screen.getByLabelText(`${label} text`).textContent ?? ''
        expect(Array.from(text).length).toBeLessThanOrEqual(300)
        expect(text.split('\n').length).toBeLessThanOrEqual(6)
        restored += text
        const next = screen.getByRole('button', { name: 'Next text page' })
        if (next.hasAttribute('disabled')) break
        fireEvent.click(next)
      } while (true)
      expect(restored).toBe(expected)
    }
    expect(document.querySelector('script')).toBeNull()
    tab('Goal'); expect(screen.getByText(/Text page 1 of/)).toBeInTheDocument()
  })

  it.each(['unknown field', 'model field', 'bad id', 'bad date', 'year zero', 'bad range', 'bad text', 'bad notes', 'duplicate', 'unknown next', 'ongoing as next', 'unknown ongoing', 'missing ongoing', 'bad evaluation', 'missing event field', 'missing list field'])('rejects %s response as a whole', async defect => {
    const first = event(1, '2026-10-01', '2026-10-04')
    const second = event(2)
    const body = list([first, second]) as unknown as Record<string, any>
    if (defect === 'unknown field') body.events[0].credentials = 'DO NOT DISPLAY'
    if (defect === 'model field') body.model = 'DO NOT DISPLAY'
    if (defect === 'bad id') body.events[0].id = 'unknown-id'
    if (defect === 'bad date') body.events[0].startDate = '2026-02-30'
    if (defect === 'year zero') body.events[0].startDate = '0000-01-01'
    if (defect === 'bad range') body.events[0].endDate = '2026-09-30'
    if (defect === 'bad text') body.events[0].sport = 'x'.repeat(81)
    if (defect === 'bad notes') body.events[0].notes = 'secret\0'
    if (defect === 'duplicate') body.events.push(first)
    if (defect === 'unknown next') body.nextUpcoming = event(9)
    if (defect === 'ongoing as next') body.nextUpcoming = first
    if (defect === 'unknown ongoing') body.ongoing = [event(9, '2026-10-01', '2026-10-05')]
    if (defect === 'missing ongoing') body.ongoing = []
    if (defect === 'bad evaluation') body.evaluatedOn = '2026-02-30'
    if (defect === 'missing event field') delete body.events[0].goal
    if (defect === 'missing list field') delete body.nextUpcoming
    vi.stubGlobal('fetch', vi.fn(async () => response(body)))
    render(<ManualEventPanel csrfToken={token} />)
    expect(await screen.findByRole('alert')).toHaveTextContent('Manual events unavailable')
    expect(screen.queryByRole('article')).not.toBeInTheDocument()
    expect(screen.queryByText(/DO NOT DISPLAY/)).not.toBeInTheDocument()
  })

  it.each([400, 404, 500])('reports a generic independent write failure (%s) while retaining draft and list', async status => {
    const item = event(1)
    const fetchMock = vi.fn().mockResolvedValueOnce(response(list([item]))).mockResolvedValue(response({ error: 'DO NOT DISPLAY' }, status))
    vi.stubGlobal('fetch', fetchMock)
    render(<ManualEventPanel csrfToken={token} />)
    await screen.findByRole('heading', { name: item.sport })
    fireEvent.click(screen.getByRole('button', { name: 'Edit manual event' }))
    fillGoal('changed goal'); save()
    expect(await screen.findByRole('alert')).toHaveTextContent(status === 404 ? 'no longer available' : status === 400 ? 'not accepted' : 'Could not save')
    expect(screen.getByRole('textbox')).toHaveValue('changed goal')
    expect(screen.queryByText(/DO NOT DISPLAY/)).not.toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledTimes(2)
    fireEvent.click(screen.getByRole('button', { name: 'Cancel edit' }))
    expect(screen.getByRole('heading', { name: item.sport })).toBeInTheDocument()
  })

  it.each(['unknown id', 'unexpected goal', 'invalid date', 'unexpected status'])('rejects an unverified update (%s) before callback/refresh', async defect => {
    const item = event(1)
    const saved = { ...item }
    if (defect === 'unknown id') saved.id = id(2)
    if (defect === 'unexpected goal') saved.goal = 'DO NOT DISPLAY'
    if (defect === 'invalid date') saved.endDate = '2026-02-30'
    const fetchMock = vi.fn().mockResolvedValueOnce(response(list([item]))).mockResolvedValue(response(saved, defect === 'unexpected status' ? 201 : 200))
    const changed = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    render(<ManualEventPanel csrfToken={token} onChanged={changed} />)
    await screen.findByRole('heading', { name: item.sport })
    fireEvent.click(screen.getByRole('button', { name: 'Edit manual event' })); save()
    expect(await screen.findByRole('alert')).toHaveTextContent(/Could not/)
    expect(changed).not.toHaveBeenCalled(); expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(screen.queryByText('DO NOT DISPLAY')).not.toBeInTheDocument()
  })

  it.each(['initial', 'reload', 'write'])('401 during %s clears all private content and cannot be retried under the old session', async phase => {
    const item = event(1)
    const fetchMock = vi.fn().mockResolvedValueOnce(phase === 'initial' ? response({}, 401) : response(list([item]))).mockResolvedValue(response({}, 401))
    vi.stubGlobal('fetch', fetchMock)
    render(<ManualEventPanel csrfToken={token} />)
    if (phase !== 'initial') {
      await screen.findByRole('heading', { name: item.sport })
      if (phase === 'reload') fireEvent.click(screen.getByRole('button', { name: 'Reload events' }))
      else { fireEvent.click(screen.getByRole('button', { name: 'Edit manual event' })); fillNotes('PRIVATE DRAFT'); save() }
    }
    expect(await screen.findByRole('alert')).toHaveTextContent('Sign in again')
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
    expect(screen.queryByRole('article')).not.toBeInTheDocument()
    expect(screen.queryByText(/PRIVATE DRAFT/)).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'New manual event' })).not.toBeInTheDocument()
  })

  it('initial read cannot reopen a cancelled draft; edit selection aborts a reload', async () => {
    const pending = deferred<ReturnType<typeof response>>()
    const fetchMock = vi.fn().mockImplementationOnce(() => pending.promise).mockResolvedValue(response(list([event(1)])))
    vi.stubGlobal('fetch', fetchMock)
    render(<ManualEventPanel csrfToken={token} />)
    fireEvent.click(screen.getByRole('button', { name: 'New manual event' }))
    fillDates(); fillGoal('local draft')
    expect(fetchMock.mock.calls[0][1].signal.aborted).toBe(true)
    await act(async () => pending.resolve(response(list([event(99)]))))
    expect(screen.getByRole('textbox')).toHaveValue('local draft')
    expect(screen.queryByText('Synthetic sport 99')).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Cancel edit' }))
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
    expect(screen.queryByRole('article')).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Reload events' }))
    await screen.findByRole('heading', { name: 'Synthetic sport 1' })
  })

  it('a late post cannot overwrite a newer draft or auto-select the created event', async () => {
    const pending = deferred<ReturnType<typeof response>>()
    const changed = vi.fn()
    const fetchMock = vi.fn().mockResolvedValueOnce(response(list())).mockImplementationOnce(() => pending.promise)
    vi.stubGlobal('fetch', fetchMock)
    render(<ManualEventPanel csrfToken={token} onChanged={changed} />)
    await screen.findByText('No manual events yet.')
    fireEvent.click(screen.getByRole('button', { name: 'New manual event' }))
    fillDates(); fillGoal('submitted goal'); save()
    const submitted = JSON.parse(fetchMock.mock.calls[1][1].body)
    fillGoal('newer local goal')
    expect(fetchMock.mock.calls[1][1].signal.aborted).toBe(true)
    await act(async () => pending.resolve(response({ ...submitted, id: id(9) }, 201)))
    expect(screen.getByRole('textbox')).toHaveValue('newer local goal')
    expect(changed).not.toHaveBeenCalled(); expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it('changing selection during a pending update prevents old responses from revealing obsolete data', async () => {
    const pending = deferred<ReturnType<typeof response>>()
    const first = event(1), second = event(2)
    const fetchMock = vi.fn().mockResolvedValueOnce(response(list([first, second]))).mockImplementationOnce(() => pending.promise)
    vi.stubGlobal('fetch', fetchMock)
    render(<ManualEventPanel csrfToken={token} />)
    await screen.findByRole('heading', { name: first.sport })
    fireEvent.click(screen.getByRole('button', { name: 'Edit manual event' })); save()
    fireEvent.click(screen.getByRole('button', { name: 'Cancel edit' }))
    fireEvent.click(screen.getByRole('button', { name: 'Next event' }))
    expect(screen.getByRole('heading', { name: second.sport })).toBeInTheDocument()
    expect(fetchMock.mock.calls[1][1].signal.aborted).toBe(true)
    await act(async () => pending.resolve(response(first)))
    expect(screen.getByRole('heading', { name: second.sport })).toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it('token changes/unmount abort late reads and remove session-bound drafts immediately', async () => {
    const pending = deferred<ReturnType<typeof response>>()
    const fetchMock = vi.fn().mockImplementationOnce(() => pending.promise).mockResolvedValue(response(list([event(2)])))
    vi.stubGlobal('fetch', fetchMock)
    const view = render(<ManualEventPanel csrfToken={token} />)
    view.rerender(<ManualEventPanel csrfToken="new-synthetic-token" />)
    expect(fetchMock.mock.calls[0][1].signal.aborted).toBe(true)
    await screen.findByRole('heading', { name: 'Synthetic sport 2' })
    await act(async () => pending.resolve(response(list([event(1)]))))
    expect(screen.queryByText('Synthetic sport 1')).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Edit manual event' })); fillNotes('PRIVATE DRAFT')
    view.rerender(<ManualEventPanel csrfToken="" />)
    expect(screen.getByRole('alert')).toHaveTextContent('Sign in again')
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
    expect(screen.queryByText(/PRIVATE DRAFT/)).not.toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledTimes(2)
    view.unmount()
    expect(fetchMock.mock.calls[1][1].signal.aborted).toBe(true)
  })

  it('unmount aborts a pending save and suppresses callbacks and refresh', async () => {
    const pending = deferred<ReturnType<typeof response>>()
    const fetchMock = vi.fn().mockResolvedValueOnce(response(list([event(1)]))).mockImplementationOnce(() => pending.promise)
    const changed = vi.fn()
    vi.stubGlobal('fetch', fetchMock)
    const view = render(<ManualEventPanel csrfToken={token} onChanged={changed} />)
    await screen.findByRole('heading', { name: 'Synthetic sport 1' })
    fireEvent.click(screen.getByRole('button', { name: 'Edit manual event' })); save()
    view.unmount()
    expect(fetchMock.mock.calls[1][1].signal.aborted).toBe(true)
    await act(async () => pending.resolve(response(event(1))))
    expect(changed).not.toHaveBeenCalled(); expect(fetchMock).toHaveBeenCalledTimes(2)
  })
})
