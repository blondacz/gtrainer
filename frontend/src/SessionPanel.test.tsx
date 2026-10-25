import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import type { AuthoredPlan, AuthoredPlanRequest, PlansView } from './PlanClient'
import { describe, expect, it, vi } from 'vitest'
import { SessionPanel } from './SessionPanel'
import { options } from './scoped-review.test-fixtures'

const csrf = 'a'.repeat(43)
const syntheticPassword = 'synthetic-test-password-not-a-real-credential'

describe('private access', () => {
  it('distinguishes October import timestamps from the Overview filter and opens those exact selected dates in Trends', async () => {
    const fetch=vi.fn(async(path:string,_init?:RequestInit)=>{
      if(path==='/api/session')return {ok:true,json:async()=>({authenticated:true,csrfToken:csrf,intervalsConfigured:false})}
      if(path==='/api/reviews/options')return {ok:true,json:async()=>({...options,today:'2026-10-22',asOf:'2026-10-22T21:51:03Z',browseRange:{oldest:'2026-10-01',newest:'2026-10-22'},activities:[]})}
      return {ok:false,status:503}
    })
    vi.stubGlobal('fetch',fetch);render(<SessionPanel />)
    await waitFor(()=>expect(fetch.mock.calls.some(([path])=>path==='/api/reviews/prepare')).toBe(true))
    fireEvent.change(screen.getByLabelText('First date'),{target:{value:'2026-10-09'}})
    fireEvent.change(screen.getByLabelText('Last date'),{target:{value:'2026-10-15'}})
    fireEvent.click(screen.getByRole('button',{name:'Show range'}))
    await waitFor(()=>expect(fetch.mock.calls.some(([path,init])=>path==='/api/reviews/prepare'&&JSON.parse(String(init?.body)).newest==='2026-10-15')).toBe(true))
    fireEvent.click(screen.getByRole('button',{name:/^Activity imports:/}))
    expect(screen.getByText(/Your Overview range: 2026-10-09 to 2026-10-15/)).toBeInTheDocument()
    const before=fetch.mock.calls.filter(([path])=>path==='/api/reviews/prepare').length
    fireEvent.click(screen.getByRole('button',{name:'Open selected dates in Trends'}))
    await screen.findByText(/Options read only. Saved zone/)
    expect(screen.getByLabelText('First date')).toHaveValue('2026-10-09')
    expect(screen.getByLabelText('Last date')).toHaveValue('2026-10-15')
    expect(fetch.mock.calls.filter(([path])=>path==='/api/reviews/prepare')).toHaveLength(before)
  })

  it('routes missing report time zone directly to its settings without enabling a schedule', async () => {
    const fetch = vi.fn(async (path: string, _init?: RequestInit) => {
      if(path==='/api/session') return {ok:true,json:async()=>({authenticated:true,csrfToken:csrf,intervalsConfigured:false})}
      if(path==='/api/reviews/options') return {ok:true,json:async()=>({...options,timeZone:null,today:null,browseRange:null,configurationVersion:0,activities:[]})}
      return {ok:false,status:503}
    })
    vi.stubGlobal('fetch',fetch);render(<SessionPanel />)
    fireEvent.click(await screen.findByRole('button',{name:'Set report time zone in Reviews'}))
    expect(screen.getByRole('tab',{name:'Reviews'})).toHaveAttribute('aria-selected','true')
    expect(screen.getByRole('button',{name:'Preset editor'})).toHaveAttribute('aria-pressed','true')
    expect(screen.getByRole('button',{name:'Consent and time zone'})).toHaveAttribute('aria-pressed','true')
    expect(screen.getByLabelText('Schedule time zone')).toBeInTheDocument()
    expect(fetch.mock.calls.every(([,init])=>!init?.method || init.method==='GET')).toBe(true)
  })

  it('opens the exact import category, queue subsection and upcoming event promised by Overview cards', async () => {
    const past = { id: '00000000-0000-0000-0000-000000000001', startDate: '2026-03-01', endDate: '2026-03-01', sport: 'Run', goal: 'Past synthetic goal', notes: null }
    const upcoming = { ...past, id: '00000000-0000-0000-0000-000000000002', startDate: '2026-04-03', endDate: '2026-04-03', sport: 'Ride', goal: 'Upcoming synthetic goal' }
    const fetch = vi.fn(async (path: string, _init?: RequestInit) => {
      if (path === '/api/session') return { ok: true, json: async () => ({ authenticated: true, csrfToken: csrf, intervalsConfigured: false }) }
      if (path === '/api/reviews/options') return { ok: true, json: async () => options }
      if (path === '/api/events') return { ok: true, status: 200, json: async () => ({ events: [past, upcoming], nextUpcoming: upcoming, ongoing: [], evaluatedOn: '2026-03-30' }) }
      if (path === '/api/imports') return { ok: true, json: async () => ['activities', 'wellness'].map(category => ({category,recordCount:category==='activities'?2:7,readStatus:'SUCCESS',rejected:0,incomplete:0,lastAttemptUtc:null,lastSuccessUtc:null,latestObservedDate:null,latestObservedAgeDays:null,upstreamFreshness:'unknown'})) }
      return { ok: false, status: 503 }
    })
    vi.stubGlobal('fetch', fetch)
    render(<SessionPanel />)
    await waitFor(() => expect(screen.getByRole('button', { name: /^Activity imports:/ })).toHaveTextContent('2 stored'))
    fireEvent.click(screen.getByRole('button', { name: /^Activity imports:/ }))
    await screen.findByLabelText('Imported category')
    expect(screen.getByRole('button', { name: 'Import status' })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByLabelText('Imported category')).toHaveValue('activities')
    expect(screen.getByRole('tabpanel')).toHaveFocus()
    fireEvent.click(screen.getByRole('tab', { name: 'Overview' }))
    await waitFor(() => expect(screen.getByRole('button', { name: 'Show range' })).toBeEnabled())
    for (let i=0; i<6 && !screen.queryByRole('button', {name:/^Wellness imports:/}); i++) fireEvent.click(screen.getByRole('button', {name:'Next cards'}))
    fireEvent.click(screen.getByRole('button', { name: /^Wellness imports:/ }))
    expect(await screen.findByLabelText('Imported category')).toHaveValue('wellness')
    fireEvent.click(screen.getByRole('tab', { name: 'Overview' }))
    await waitFor(() => expect(screen.getByRole('button', { name: 'Show range' })).toBeEnabled())
    fireEvent.click(screen.getByRole('button', { name: /^Review queue:/ }))
    expect(screen.getByRole('button', { name: 'Queue status' })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.queryByRole('button', { name: 'Run review now — enqueue' })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('tab', { name: 'Overview' }))
    await waitFor(() => expect(screen.getByRole('button', { name: /^Next manual event:/ })).toHaveTextContent('2026-04-03'))
    fireEvent.click(screen.getByRole('button', { name: /^Next manual event:/ }))
    await screen.findByText('Upcoming synthetic goal')
    expect(screen.getByText('Event 2 of 2')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Manual events' })).toHaveFocus()
    expect(screen.getByRole('tab', { name: 'Goal' })).toHaveAttribute('aria-selected', 'true')
    expect(screen.queryByText('Past synthetic goal')).not.toBeInTheDocument()
    expect(fetch.mock.calls.filter(([,init])=>init?.method && init.method !== 'GET').every(([path])=>path==='/api/reviews/prepare')).toBe(true)
  })

  it('offers honest routes out of saved connected-review inspection without requesting a model', async () => {
    const fetch = vi.fn(async (path: string, _init?: RequestInit) => path==='/api/session' ?
      {ok:true,json:async()=>({authenticated:true,csrfToken:csrf,intervalsConfigured:false})}:{ok:false,status:503})
    vi.stubGlobal('fetch',fetch);render(<SessionPanel />)
    fireEvent.click(await screen.findByRole('tab',{name:'Connected insights'}))
    expect(screen.getByRole('heading',{name:'Connected insights — saved review inspection'})).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button',{name:'Open Trends'}))
    expect(await screen.findByRole('heading',{name:'Historical trends'})).toBeInTheDocument()
    fireEvent.click(screen.getByRole('tab',{name:'Connected insights'}))
    fireEvent.click(screen.getByRole('button',{name:'Open Context'}))
    expect(screen.getByRole('tab',{name:'Context'})).toHaveAttribute('aria-selected','true')
    expect(fetch.mock.calls.every(([,init])=>!init?.method || init.method==='GET')).toBe(true)
  })

  it('carries applied Overview dates into Trends and retains them when returning without automatic trend preparation', async () => {
    const fetchMock = vi.fn(async (path: string, _init?: RequestInit) => {
      if (path === '/api/session') return { ok: true, json: async () => ({ authenticated: true, csrfToken: csrf, intervalsConfigured: false }) }
      if (path === '/api/reviews/options') return { ok: true, json: async () => options }
      return { ok: false, status: 503 }
    })
    vi.stubGlobal('fetch', fetchMock)
    render(<SessionPanel />)
    await waitFor(() => expect(screen.getByRole('button', { name: 'Show range' })).toBeEnabled())
    await waitFor(() => expect(fetchMock.mock.calls.some(([path]) => path === '/api/reviews/prepare')).toBe(true))
    fireEvent.change(screen.getByLabelText('First date'), { target: { value: '2026-03-10' } })
    fireEvent.change(screen.getByLabelText('Last date'), { target: { value: '2026-03-12' } })
    fireEvent.click(screen.getByRole('button', { name: 'Show range' }))
    await waitFor(() => expect(fetchMock.mock.calls.some(([path, init]) => path === '/api/reviews/prepare' && JSON.parse(String(init?.body)).oldest === '2026-03-10')).toBe(true))
    const beforeNavigation = fetchMock.mock.calls.filter(([path]) => path === '/api/reviews/prepare').length
    fireEvent.click(screen.getByRole('button', { name: /^Weight:.*Open Trends/ }))
    await screen.findByText(/Options read only. Saved zone/)
    expect(screen.getByLabelText('Period scope')).toHaveValue('custom')
    expect(screen.getByLabelText('First date')).toHaveValue('2026-03-10')
    expect(screen.getByLabelText('Last date')).toHaveValue('2026-03-12')
    expect(fetchMock.mock.calls.filter(([path]) => path === '/api/reviews/prepare')).toHaveLength(beforeNavigation)
    fireEvent.click(screen.getByRole('button', { name: 'Request factual report' }))
    await waitFor(() => expect(fetchMock.mock.calls.filter(([path]) => path === '/api/reviews/prepare')).toHaveLength(beforeNavigation + 1))
    expect(JSON.parse(String(fetchMock.mock.calls.filter(([path]) => path === '/api/reviews/prepare').at(-1)?.[1]?.body))).toMatchObject({scope:'custom', oldest:'2026-03-10', newest:'2026-03-12'})
    fireEvent.click(screen.getByRole('tab', { name: 'Overview' }))
    await waitFor(() => expect(screen.getByRole('button', { name: 'Show range' })).toBeEnabled())
    expect(screen.getByLabelText('First date')).toHaveValue('2026-03-10')
    expect(screen.getByLabelText('Last date')).toHaveValue('2026-03-12')
    expect(fetchMock.mock.calls.some(([path]) => ['/api/review-now', '/api/analysis', '/api/sync'].includes(path))).toBe(false)
  })
  it('mounts only the selected section and keeps writes and model calls off during navigation', async () => {
    const fetchMock = vi.fn(async (path: string) => {
      if (path === '/api/session') return { ok: true, json: async () => ({ authenticated: true, csrfToken: csrf, intervalsConfigured: false }) }
      if (path === '/api/events') return { ok: true, status: 200, json: async () => ({ events: [], nextUpcoming: null, ongoing: [], evaluatedOn: '2020-06-02' }) }
      if (path === '/api/plans') return { ok: true, status: 200, json: async () => ({ plans: [], selection: { generation: 0, selected: null } }) }
      return { ok: false, status: 503 }
    })
    vi.stubGlobal('fetch', fetchMock)
    const authenticated = vi.fn()
    render(<SessionPanel onAuthenticatedChange={authenticated} />)
    await screen.findByRole('heading', { name: 'Overview' })
    // Rendering the section can precede the authentication notification effect.
    await waitFor(() => expect(authenticated).toHaveBeenLastCalledWith(true))
    expect(screen.getByRole('complementary', { name: 'Context deletion and backup retention' })).toHaveTextContent(
      'Retained encrypted backups may still contain deleted data until they expire under normal retention.',
    )
    expect(screen.getByRole('tab', { name: 'Overview' })).toHaveAttribute('aria-selected', 'true')
    expect(screen.queryByRole('heading', { name: 'Historical trends' })).not.toBeInTheDocument()
    expect(fetchMock.mock.calls.some(([path]) => path === '/api/review-models')).toBe(false)
    fireEvent.click(screen.getByRole('tab', { name: 'Events & plans' }))
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
    await screen.findByText(/Options unavailable or could not be verified/)
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

  it('locks click and keyboard navigation while a plan draft is dirty, then unlocks on discard', async () => {
    const fetchMock = vi.fn(async (path: string, _init?: RequestInit) => {
      if (path === '/api/session') return { ok: true, json: async () => ({ authenticated: true, csrfToken: csrf, intervalsConfigured: false }) }
      if (path === '/api/plans') return { ok: true, status: 200, json: async () => ({ plans: [], selection: { generation: 0, selected: null } }) }
      if (path === '/api/events') return { ok: true, status: 200, json: async () => ({ events: [], nextUpcoming: null, ongoing: [], evaluatedOn: '2020-06-02' }) }
      return { ok: false, status: 503 }
    })
    vi.stubGlobal('fetch', fetchMock); render(<SessionPanel />)
    fireEvent.click(await screen.findByRole('tab', { name: 'Events & plans' }))
    await waitFor(()=>expect(screen.getByRole('button',{name:'Enter authored plan'})).toBeEnabled())
    fireEvent.click(screen.getByRole('button', { name: 'Enter authored plan' }))
    fireEvent.change(screen.getByLabelText('Plan title'), { target: { value: 'Unsaved private draft' } })
    fireEvent.click(screen.getByRole('tab', { name: 'Overview' }))
    expect(screen.getByRole('tab', { name: 'Events & plans' })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByLabelText('Plan title')).toHaveValue('Unsaved private draft')
    screen.getByRole('tab', { name: 'Events & plans' }).focus()
    fireEvent.keyDown(screen.getByRole('tab', { name: 'Events & plans' }), { key: 'Home' })
    expect(screen.getByRole('tab', { name: 'Events & plans' })).toHaveFocus()
    expect(screen.getByRole('tab', { name: 'Events & plans' })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByText(/Finish or discard the authored plan draft/)).toBeInTheDocument()
    expect(fetchMock.mock.calls.some(([, init]) => init?.method === 'POST')).toBe(false)
    fireEvent.click(screen.getByRole('button', { name: 'Discard plan draft' }))
    fireEvent.click(screen.getByRole('tab', { name: 'Overview' }))
    expect(screen.getByRole('tab', { name: 'Overview' })).toHaveAttribute('aria-selected', 'true')
  })

  it('keeps navigation locked during pending save, unlocks after verification, and logout clears the next session', async () => {
    type Reply={ok:boolean;status?:number;json?:()=>Promise<unknown>}
    let finishPost: ((value: Reply) => void) | undefined
    let submitted:AuthoredPlanRequest|null=null
    let state:PlansView={plans:[],selection:{generation:0,selected:null}}
    let authenticated = true
    const fetchMock = vi.fn((path: string, init?: RequestInit): Promise<Reply> => {
      if (path === '/api/session') return Promise.resolve(authenticated ? { ok: true, json: async () => ({ authenticated: true, csrfToken: csrf, intervalsConfigured: false }) } : { ok: false, status: 401 })
      if (path === '/api/plans' && init?.method === 'POST') {submitted=JSON.parse(String(init.body)) as AuthoredPlanRequest;return new Promise(resolve => { finishPost = resolve })}
      if (path === '/api/plans') return Promise.resolve({ ok: true, status: 200, json: async () => state })
      if (path === '/api/logout') { authenticated = false; return Promise.resolve({ ok: true }) }
      if (path === '/api/login') { authenticated = true; return Promise.resolve({ ok: true, json: async () => ({ authenticated: true, csrfToken: csrf, intervalsConfigured: false }) }) }
      if (path === '/api/events') return Promise.resolve({ ok: true, status: 200, json: async () => ({ events: [], nextUpcoming: null, ongoing: [], evaluatedOn: '2020-06-02' }) })
      return Promise.resolve({ ok: false, status: 503 })
    })
    vi.stubGlobal('fetch', fetchMock); render(<SessionPanel />)
    fireEvent.click(await screen.findByRole('tab', { name: 'Events & plans' }))
    await waitFor(()=>expect(screen.getByRole('button',{name:'Enter authored plan'})).toBeEnabled())
    fireEvent.click(screen.getByRole('button', { name: 'Enter authored plan' }))
    fireEvent.change(screen.getByLabelText('Plan title'), { target: { value: 'Pending draft' } })
    fireEvent.change(screen.getByLabelText('Author attribution'), { target: { value: 'Synthetic author' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save authored revision' }))
    await waitFor(() => expect(finishPost).toBeDefined())
    fireEvent.click(screen.getByRole('tab', { name: 'Overview' }))
    expect(screen.getByRole('tab', { name: 'Events & plans' })).toHaveAttribute('aria-selected', 'true')
    const saved:AuthoredPlan={reference:{planId:'11111111-1111-4111-8111-111111111111',revision:1},previousRevision:null,
      definition:submitted!,recordedBy:'authenticated_user',recordedUtc:'2026-10-15T12:00:00Z'}
    state={...state,plans:[saved]}
    await act(async()=>{finishPost!({ok:true,status:201,json:async()=>saved})})
    await waitFor(()=>expect(screen.queryByLabelText('Unsaved authored plan')).not.toBeInTheDocument())
    fireEvent.click(screen.getByRole('tab', { name: 'Overview' }))
    expect(screen.getByRole('tab', { name: 'Overview' })).toHaveAttribute('aria-selected', 'true')
    fireEvent.click(screen.getByRole('tab', { name: 'Events & plans' }))
    await waitFor(()=>expect(screen.getByRole('button',{name:'Enter authored plan'})).toBeEnabled())
    fireEvent.click(screen.getByRole('button', { name: 'Enter authored plan' }))
    fireEvent.change(screen.getByLabelText('Plan title'), { target: { value: 'Private text to clear' } })
    fireEvent.click(screen.getByRole('button', { name: 'Sign out' }))
    await screen.findByLabelText('Dashboard password')
    fireEvent.change(screen.getByLabelText('Dashboard password'), { target: { value: syntheticPassword } })
    fireEvent.click(screen.getByRole('button', { name: 'Sign in' }))
    await screen.findByRole('tab', { name: 'Overview' })
    fireEvent.click(screen.getByRole('tab', { name: 'Events & plans' }))
    expect(screen.queryByDisplayValue('Private text to clear')).not.toBeInTheDocument()
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
