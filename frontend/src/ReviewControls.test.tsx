import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ReviewControls } from './ReviewControls'

// Synthetic dates, local artifacts and coverage only; never real records or provider calls.
const csrf = 's'.repeat(43)
const hash = 'a'.repeat(64)
const model = { id: 'synthetic-local', label: 'Synthetic local', tag: 'synthetic:3b', digest: hash, experimental: true }
const other = { ...model, id: 'synthetic-other', label: 'Synthetic other', digest: 'b'.repeat(64) }
const budget = () => ({ callTimeoutMillis: 120_000, jobTimeoutMillis: 130_000, allowCorrectiveAttempt: false })
const policy = () => ({ debounceSeconds: 120, cooldownSeconds: 300, maximumDeferralSeconds: 600 })
const trigger = (kind = 'changed_data', id = 'changed-data') => ({ id, kind, intervalMinutes: 120,
  localTime: ['daily_time', 'weekly_time'].includes(kind) ? '18:00' : null, dayOfWeek: kind === 'weekly_time' ? 1 : null,
  threshold: ['record_count', 'daily_steps'].includes(kind) ? 1 : null, period: 'day', settleSeconds: 120 })
const configuration = () => ({ enabled: false, paused: false, timeZone: 'UTC', queuePolicy: policy(), presets: [
  { id: 'daily-combined', reviewType: 'daily_combined', enabled: false, scope: { kind: 'day', days: 1, sport: null as string | null }, level: 'interim', focus: 'daily_combined', budget: budget(), triggers: [trigger(), trigger('sleep_arrival', 'sleep-arrival')] },
  { id: 'after-activity', reviewType: 'after_activity', enabled: false, scope: { kind: 'activity', days: 1, sport: null as string | null }, level: 'interim', focus: 'daily_combined', budget: budget(), triggers: [trigger('activity_arrival', 'activity-arrival')] },
  { id: 'weekly', reviewType: 'weekly', enabled: false, scope: { kind: 'week', days: 7, sport: null as string | null }, level: 'thorough', focus: 'daily_combined', budget: budget(), triggers: [] },
] })
const catalogue = (enabled = false) => ({ profile: 'review-focus-app-v1', selectedModelId: enabled ? model.id : null as string | null,
  selectionVersion: enabled ? 1 : 0, enabled, correctionEnabled: false, runtimeGuardConfigured: true, models: [model, other],
  reason: enabled ? 'experimental_review_selected' : 'interpretation_disabled', hostedEnabled: false,
  executionPolicy: { callTimeoutMillis: 240_000, jobTimeoutMillis: 600_000, allowCorrectiveAttempt: true } })
const scheduleStatus = () => ({ configuration: configuration(), configurationVersion: 3, reason: 'schedules_disabled', executionAvailable: false,
  triggers: [
    { presetId: 'daily-combined', triggerId: 'changed-data', kind: 'changed_data', reason: 'waiting_for_trigger', nextCheckUtc: '2020-06-10T16:00:00Z' as string | null, nextReviewUtc: null as string | null },
    { presetId: 'daily-combined', triggerId: 'evening', kind: 'daily_time', reason: 'waiting_for_trigger', nextCheckUtc: null as string | null, nextReviewUtc: '2020-06-10T18:00:00Z' as string | null },
  ], due: [] })
const intent = () => ({ occurrenceId: hash, presetId: 'daily-combined', configurationVersion: 3, modelId: model.id, selectionVersion: 1,
  reviewType: 'daily_combined', scope: { kind: 'day', days: 1, sport: null }, oldest: '2020-06-10', newest: '2020-06-10', activitySha256: null,
  level: 'interim', focus: 'daily_combined', budget: budget(), reasons: ['manual_request'], dueUtc: '2020-06-10T12:00:00Z', notBeforeUtc: null })
const job = (jobId = hash) => ({ id: jobId, key: hash, intent: intent(), reasons: ['manual_request'], occurrenceIds: [jobId],
  firstQueuedUtc: '2020-06-10T12:00:00Z', lastQueuedUtc: '2020-06-10T12:00:00Z', eligibleUtc: '2020-06-10T12:00:00Z',
  maximumDeferralUtc: '2020-06-10T12:10:00Z', manual: true, startedUtc: null as string | null })
const facts = () => ({ profile: 'factual-review-v1', applicationGenerated: true, evidenceReportSha256: hash, requestedFocus: 'daily_combined',
  groups: [], focuses: [], reason: 'no_supported_focus', unavailable: [], limitations: [], sourceStatus: [] })
const outcome = (outcomeId = hash) => ({ id: outcomeId, intent: intent(), status: 'factual_only', reason: 'model_unavailable',
  finishedUtc: '2020-06-10T12:02:00Z', startedUtc: '2020-06-10T12:00:00Z', facts: facts() as unknown, interpretation: null as unknown, stale: false })
type SyntheticQueue = { reason: string; executionAvailable: boolean; policy: ReturnType<typeof policy>; pending: ReturnType<typeof job>[];
  running: ReturnType<typeof job> | null; outcomes: ReturnType<typeof outcome>[]; lastSuccess: ReturnType<typeof outcome> | null }
const queueStatus = (executionAvailable = false): SyntheticQueue => ({ reason: executionAvailable ? 'review_idle' : 'explicit_local_selection_required',
  executionAvailable, policy: policy(), pending: [], running: null, outcomes: [], lastSuccess: null })
const response = (body: unknown) => ({ ok: true, json: async () => structuredClone(body) })
const button = (name: string) => screen.getByRole('button', { name })
const click = (name: string) => fireEvent.click(button(name))
const change = (label: string, value: string) => {
  if (!screen.queryByLabelText(label, { exact: true }) && ['Scope', 'Rolling dates', 'Sport filter (blank means all sports)', 'Review focus'].includes(label)) click('Coverage and focus')
  if (!screen.queryByLabelText(label, { exact: true }) && ['Preset ID', 'Review type'].includes(label)) click('Preset identity')
  fireEvent.change(screen.getByLabelText(label, { exact: true }), { target: { value } })
}
const writes = (fetchMock: ReturnType<typeof vi.fn>) => fetchMock.mock.calls.filter(([, init]) => init?.method && init.method !== 'GET')
function server(enabled = false) {
  const state = { models: catalogue(enabled), schedules: scheduleStatus(), queue: queueStatus(enabled) }
  const fetchMock = vi.fn(async (path: string, init?: RequestInit) => {
    if (init?.method === 'PUT' && path === '/api/review-models') {
      const submitted = JSON.parse(init.body as string)
      state.models = { ...state.models, selectedModelId: submitted.modelId, enabled: submitted.enabled,
        correctionEnabled: submitted.allowCorrectiveAttempt, selectionVersion: state.models.selectionVersion + 1 }
      state.queue.executionAvailable = submitted.enabled && state.models.runtimeGuardConfigured && !state.schedules.configuration.paused
    }
    if (init?.method === 'PUT' && path === '/api/review-schedules') {
      const submitted = JSON.parse(init.body as string)
      state.schedules = { ...state.schedules, configuration: submitted.configuration, configurationVersion: state.schedules.configurationVersion + 1 }
      state.queue.executionAvailable = state.models.enabled && state.models.runtimeGuardConfigured && !submitted.configuration.paused
    }
    if (init?.method === 'POST' && path === '/api/review-now') state.queue = { ...state.queue, reason: 'review_queued', pending: [job()] }
    if (path === '/api/review-models') return response(state.models)
    if (path === '/api/review-schedules') return response(state.schedules)
    if (path === '/api/review-queue' || path === '/api/review-now') return response(state.queue)
    throw new Error('Unexpected endpoint — no inference endpoint permitted in these fixtures')
  })
  vi.stubGlobal('fetch', fetchMock)
  return { state, fetchMock }
}
async function ready() { await screen.findByText('Review status loaded. Reading does not request inference or save edits.') }
async function mount(enabled = false) {
  const fixture = server(enabled)
  const view = render(<ReviewControls csrfToken={csrf} revision={0} />)
  await ready()
  return { ...fixture, view }
}
afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals() })

describe('explicit, separate local review consent', () => {
  it('defaults off, mounts with three authenticated reads and zero writes or model calls', async () => {
    const { fetchMock } = await mount()
    expect(button('Run review now — enqueue')).toBeDisabled()
    expect(fetchMock).toHaveBeenCalledTimes(3)
    expect(writes(fetchMock)).toHaveLength(0)
    for (const [, init] of fetchMock.mock.calls) expect(init).toEqual(expect.objectContaining({ method: 'GET', cache: 'no-store', signal: expect.any(AbortSignal) }))
    click('Model selection')
    expect(screen.getByLabelText('Review local model')).toHaveValue('')
    expect(screen.getByLabelText('Enable optional local AI interpretation')).not.toBeChecked()
    expect(screen.getByLabelText(/Allow one corrective attempt/)).not.toBeChecked()
    expect(screen.getByText(/No hosted fallback or host transfer/)).toBeInTheDocument()
    click('Preset editor'); click('Consent and time zone')
    expect(screen.getByLabelText('Enable scheduled reviews')).not.toBeChecked()
    expect(screen.getByLabelText('Pause all reviews')).not.toBeChecked()
    expect(screen.getByLabelText('Schedule time zone')).toHaveValue('UTC')
  })

  it('edits model selection/enable/correction only on explicit save, with named profile and CSRF', async () => {
    const { fetchMock } = await mount()
    click('Model selection')
    change('Review local model', model.id)
    expect(writes(fetchMock)).toHaveLength(0)
    expect(screen.getByLabelText('Enable optional local AI interpretation')).not.toBeChecked()
    fireEvent.click(screen.getByLabelText('Enable optional local AI interpretation'))
    fireEvent.click(screen.getByLabelText(/Allow one corrective attempt/))
    expect(writes(fetchMock)).toHaveLength(0)
    click('Save model choices')
    await waitFor(() => expect(button('Save model choices')).toBeDisabled())
    expect(writes(fetchMock)[0]).toEqual(['/api/review-models', expect.objectContaining({ method: 'PUT',
      headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': csrf },
      body: JSON.stringify({ profile: 'review-focus-app-v1', modelId: model.id, enabled: true, allowCorrectiveAttempt: true }) })])
    change('Review local model', other.id)
    expect(screen.getByLabelText('Enable optional local AI interpretation')).not.toBeChecked()
    click('Save model choices')
    await waitFor(() => expect(writes(fetchMock)).toHaveLength(2))
    expect(JSON.parse(writes(fetchMock)[1][1].body)).toMatchObject({ modelId: other.id, enabled: false })
    expect(fetchMock.mock.calls.some(([path]) => /interpretation|analysis/.test(path))).toBe(false)
  })

  it('provides an explicit immediate disable, without inference or silently retaining correction', async () => {
    const { fetchMock } = await mount(true)
    click('Model selection'); click('Disable optional AI now')
    await waitFor(() => expect(screen.getByLabelText('Enable optional local AI interpretation')).not.toBeChecked())
    expect(JSON.parse(writes(fetchMock)[0][1].body)).toEqual({ profile: 'review-focus-app-v1', modelId: model.id, enabled: false, allowCorrectiveAttempt: false })
  })

  it('actual missing runtime guard disables generation and enable consent, without hiding separate charts', async () => {
    const { state, fetchMock } = server(true)
    state.models.runtimeGuardConfigured = false
    state.queue.executionAvailable = false
    state.queue.reason = 'runtime_guard_unavailable'
    render(<><section aria-label="Independent factual charts">Synthetic chart remains available</section><ReviewControls csrfToken={csrf} revision={0} /></>)
    await ready()
    expect(button('Run review now — enqueue')).toBeDisabled()
    expect(screen.getAllByText(/Runtime qualification guard is unavailable/).length).toBeGreaterThan(0)
    expect(screen.getByRole('region', { name: 'Independent factual charts' })).toBeVisible()
    click('Model selection')
    expect(screen.getByLabelText('Enable optional local AI interpretation')).toBeDisabled()
    expect(button('Disable optional AI now')).not.toBeDisabled()
    expect(writes(fetchMock)).toHaveLength(0)
  })

  it.each(['review_not_configured', 'review_configuration_invalid', 'model_unavailable'])('model outage %s leaves charts and settings separate', async reason => {
    const { state, fetchMock } = server()
    state.models.models = []; state.models.reason = reason
    render(<><p>Synthetic independent chart</p><ReviewControls csrfToken={csrf} revision={0} /></>)
    await ready(); click('Model selection')
    expect(screen.getByText('Synthetic independent chart')).toBeVisible()
    expect(screen.getByLabelText('Enable optional local AI interpretation')).toBeDisabled()
    click('Review overview')
    expect(button('Run review now — enqueue')).toBeDisabled()
    expect(writes(fetchMock)).toHaveLength(0)
  })

  it.each(['profile', 'hosted', 'shape', 'model-digest', 'operator-budget'])('rejects malformed catalogue: %s', async field => {
    const { state, fetchMock } = server(true)
    const malformed: Record<string, unknown> = state.models
    if (field === 'profile') malformed.profile = 'installed-prototype-v1'
    if (field === 'hosted') malformed.hostedEnabled = true
    if (field === 'shape') delete malformed.enabled
    if (field === 'model-digest') state.models.models[0] = { ...model, digest: 'not-a-digest' }
    if (field === 'operator-budget') malformed.executionPolicy = { ...budget(), callTimeoutMillis: 0 }
    render(<ReviewControls csrfToken={csrf} revision={0} />)
    await screen.findByText(/Some review controls are unavailable or could not be verified/)
    expect(button('Run review now — enqueue')).toBeDisabled()
    click('Model selection')
    expect(screen.getByLabelText('Review local model')).toBeDisabled()
    expect(writes(fetchMock)).toHaveLength(0)
  })

  it.each([401, 403, 500])('authentication/server error %s never echoes raw bodies', async status => {
    const raw = vi.fn(async () => ({ error: 'SECRET provider diagnostics' }))
    const fetchMock = vi.fn(async () => ({ ok: false, status, json: raw, text: raw }))
    vi.stubGlobal('fetch', fetchMock)
    render(<ReviewControls csrfToken={csrf} revision={0} />)
    await screen.findByText(/Some review controls are unavailable or could not be verified/)
    expect(raw).not.toHaveBeenCalled()
    expect(screen.queryByText(/SECRET/)).not.toBeInTheDocument()
    expect(button('Run review now — enqueue')).toBeDisabled()
    expect(writes(fetchMock)).toHaveLength(0)
  })
})

describe('typed preset editor and strict validation', () => {
  it('pages form subsections and one trigger at a time, preserving edits between sections', async () => {
    const { fetchMock } = await mount(true)
    expect(screen.queryByLabelText('Preset ID')).not.toBeInTheDocument()
    click('Preset editor')
    expect(screen.getByLabelText('Preset ID')).toBeInTheDocument()
    expect(screen.queryByLabelText('Scope')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Call timeout (milliseconds)')).not.toBeInTheDocument()
    change('Scope', 'rolling'); change('Rolling dates', '30'); change('Sport filter (blank means all sports)', 'Ride')
    expect(screen.queryByLabelText('Preset ID')).not.toBeInTheDocument()
    click('Preset identity')
    expect(screen.queryByLabelText('Scope')).not.toBeInTheDocument()
    click('Coverage and focus')
    expect(screen.getByLabelText('Rolling dates')).toHaveValue(30)
    click('Level and budgets'); change('Scheduled review level', 'thorough'); change('Call timeout (milliseconds)', '1000'); change('Job timeout (milliseconds)', '2000')
    fireEvent.click(screen.getByLabelText(/Permit preset corrective attempt/))
    click('Trigger editor')
    expect(screen.getByLabelText('Trigger 1 type')).toBeInTheDocument()
    expect(screen.queryByLabelText('Trigger 2 type')).not.toBeInTheDocument()
    click('Next trigger')
    expect(screen.queryByLabelText('Trigger 1 type')).not.toBeInTheDocument()
    change('Trigger 2 settle delay (seconds)', '60')
    click('Queue policy'); change('Debounce (seconds)', '15'); change('Cooldown (seconds)', '45'); change('Maximum deferral (seconds)', '90')
    click('Consent and time zone'); change('Schedule time zone', 'Europe/Prague')
    expect(writes(fetchMock)).toHaveLength(0)
    click('Save schedule changes')
    await waitFor(() => expect(button('Save schedule changes')).toBeDisabled())
    const [path, init] = writes(fetchMock)[0]
    expect(path).toBe('/api/review-schedules')
    expect(init.headers).toEqual({ 'Content-Type': 'application/json', 'X-CSRF-Token': csrf })
    const payload = JSON.parse(init.body)
    expect(payload.expectedVersion).toBe(3)
    expect(payload.configuration).toMatchObject({ enabled: false, paused: false, timeZone: 'Europe/Prague', queuePolicy: { debounceSeconds: 15, cooldownSeconds: 45, maximumDeferralSeconds: 90 } })
    expect(payload.configuration.presets[0]).toMatchObject({ scope: { kind: 'rolling', days: 30, sport: 'Ride' }, level: 'thorough',
      budget: { callTimeoutMillis: 1000, jobTimeoutMillis: 2000, allowCorrectiveAttempt: true } })
    expect(payload.configuration.presets[0].triggers[1].settleSeconds).toBe(60)
  })

  it('explicit scope assignments use 1/7 dates and never silently change the time zone', async () => {
    const { fetchMock } = await mount(true)
    click('Preset editor'); change('Scope', 'week'); click('Save schedule changes')
    await waitFor(() => expect(writes(fetchMock)).toHaveLength(1))
    expect(JSON.parse(writes(fetchMock)[0][1].body).configuration.presets[0].scope.days).toBe(7)
    await waitFor(() => expect(button('Save schedule changes')).toBeDisabled())
    change('Scope', 'day'); click('Save schedule changes')
    await waitFor(() => expect(writes(fetchMock)).toHaveLength(2))
    expect(JSON.parse(writes(fetchMock)[1][1].body).configuration.presets[0].scope.days).toBe(1)
    expect(JSON.parse(writes(fetchMock)[1][1].body).configuration.timeZone).toBe('UTC')
  })

  it.each([
    ['Level and budgets', 'Call timeout (milliseconds)', '0'], ['Level and budgets', 'Call timeout (milliseconds)', '240001'],
    ['Level and budgets', 'Call timeout (milliseconds)', '1.5'], ['Level and budgets', 'Call timeout (milliseconds)', ''],
    ['Level and budgets', 'Job timeout (milliseconds)', '119999'], ['Level and budgets', 'Job timeout (milliseconds)', '600001'],
    ['Queue policy', 'Debounce (seconds)', '-1'], ['Queue policy', 'Debounce (seconds)', '3601'],
    ['Queue policy', 'Cooldown (seconds)', '86401'], ['Queue policy', 'Maximum deferral (seconds)', '0'],
    ['Queue policy', 'Maximum deferral (seconds)', '119'], ['Queue policy', 'Maximum deferral (seconds)', '86401'],
    ['Trigger editor', 'Trigger 1 check interval (minutes)', '0'], ['Trigger editor', 'Trigger 1 check interval (minutes)', '10081'],
    ['Consent and time zone', 'Schedule time zone', 'Mars/Olympus'], ['Consent and time zone', 'Schedule time zone', ''],
    ['Preset and scope', 'Preset ID', 'Invalid!'], ['Preset and scope', 'Sport filter (blank means all sports)', '🚴'],
  ])('rejects invalid %s / %s = %s without writes or repairs', async (tab, label, value) => {
    const { fetchMock } = await mount(true)
    click('Preset editor'); click(tab); change(label, value)
    expect(button('Save schedule changes')).toBeDisabled()
    expect(screen.getByRole('status')).toHaveTextContent('No clamping or automatic repair')
    fireEvent.click(button('Save schedule changes'))
    expect(writes(fetchMock)).toHaveLength(0)
    if (value !== '') expect(screen.getByLabelText(label)).toHaveValue(label.includes('zone') || label.includes('ID') || label.includes('Sport') ? value : Number(value))
  })

  it.each(['0', '91', '1.2'])('rejects rolling bounds %s', async value => {
    const { fetchMock } = await mount(true)
    click('Preset editor'); change('Scope', 'rolling'); change('Rolling dates', value)
    expect(button('Save schedule changes')).toBeDisabled()
    expect(screen.getByLabelText('Rolling dates')).toHaveValue(Number(value))
    expect(writes(fetchMock)).toHaveLength(0)
  })

  it.each(['0', '1000001'])('rejects threshold bounds %s', async value => {
    const { fetchMock } = await mount(true)
    click('Preset editor'); click('Trigger editor'); change('Trigger 1 type', 'record_count'); change('Trigger 1 threshold', value)
    expect(button('Save schedule changes')).toBeDisabled()
    expect(writes(fetchMock)).toHaveLength(0)
  })

  it.each(['-1', '3601'])('rejects arrival settle bounds %s', async value => {
    const { fetchMock } = await mount(true)
    click('Preset editor'); click('Trigger editor'); change('Trigger 1 type', 'sleep_arrival'); change('Trigger 1 settle delay (seconds)', value)
    expect(button('Save schedule changes')).toBeDisabled()
    expect(writes(fetchMock)).toHaveLength(0)
  })

  it('rejects empty scheduled time and activity/time assignment rather than repairing', async () => {
    const { fetchMock } = await mount(true)
    click('Preset editor'); click('Trigger editor'); change('Trigger 1 type', 'daily_time'); change('Trigger 1 local time', '')
    expect(button('Save schedule changes')).toBeDisabled()
    change('Trigger 1 local time', '23:59')
    expect(button('Save schedule changes')).not.toBeDisabled()
    click('Preset and scope'); change('Scope', 'activity')
    expect(button('Save schedule changes')).toBeDisabled()
    click('Trigger editor')
    expect(screen.getByLabelText('Trigger 1 type')).toHaveValue('daily_time')
    expect(writes(fetchMock)).toHaveLength(0)
  })

  it('adds/removes typed triggers and preserves weekly-time weekday/period fields', async () => {
    const { fetchMock } = await mount(true)
    click('Preset editor'); click('Trigger editor'); click('Add trigger')
    change('Trigger 3 type', 'weekly_time'); change('Trigger 3 local time', '21:30'); change('Trigger 3 weekday', '7')
    click('Previous trigger'); click('Remove trigger 2')
    click('Save schedule changes')
    await waitFor(() => expect(writes(fetchMock)).toHaveLength(1))
    const saved = JSON.parse(writes(fetchMock)[0][1].body).configuration.presets[0].triggers
    expect(saved).toHaveLength(2)
    expect(saved[1]).toEqual({ id: 'trigger-1', kind: 'weekly_time', localTime: '21:30', dayOfWeek: 7, threshold: null, intervalMinutes: 120, period: 'day', settleSeconds: 120 })
  })

  it('enforces operator caps/correction permission even below absolute budget maxima', async () => {
    const { state } = server(true)
    state.models.executionPolicy = { callTimeoutMillis: 120_000, jobTimeoutMillis: 130_000, allowCorrectiveAttempt: false }
    render(<ReviewControls csrfToken={csrf} revision={0} />); await ready()
    click('Model selection')
    expect(screen.getByLabelText(/Allow one corrective attempt/)).toBeDisabled()
    click('Preset editor'); click('Level and budgets')
    expect(screen.getByLabelText(/Permit preset corrective attempt/)).toBeDisabled()
    change('Job timeout (milliseconds)', '130001')
    expect(button('Save schedule changes')).toBeDisabled()
  })

  it('explicit immediate pause/disable saves only flags and retains unrelated dirty edits', async () => {
    const { state, fetchMock } = server(true)
    state.schedules.configuration.enabled = true
    render(<ReviewControls csrfToken={csrf} revision={0} />); await ready()
    click('Preset editor'); change('Preset ID', 'unsaved-name'); click('Consent and time zone')
    click('Pause reviews now')
    await waitFor(() => expect(writes(fetchMock)).toHaveLength(1))
    await waitFor(() => expect(button('Pause reviews now')).toBeDisabled())
    click('Preset and scope')
    expect(screen.getByLabelText('Preset ID')).toHaveValue('unsaved-name')
    expect(JSON.parse(writes(fetchMock)[0][1].body)).toMatchObject({ expectedVersion: 3, configuration: { enabled: true, paused: true } })
    expect(JSON.parse(writes(fetchMock)[0][1].body).configuration.presets[0].id).toBe('daily-combined')
    click('Consent and time zone'); click('Disable schedules now')
    await waitFor(() => expect(writes(fetchMock)).toHaveLength(2))
    await waitFor(() => expect(button('Disable schedules now')).toBeDisabled())
    click('Preset and scope')
    expect(screen.getByLabelText('Preset ID')).toHaveValue('unsaved-name')
    expect(JSON.parse(writes(fetchMock)[1][1].body)).toMatchObject({ expectedVersion: 4, configuration: { enabled: false, paused: true } })
  })

  it('rejects malformed schedule/policy rather than populating editable repaired defaults', async () => {
    const { state, fetchMock } = server()
    state.schedules.configuration.queuePolicy.maximumDeferralSeconds = 119
    render(<ReviewControls csrfToken={csrf} revision={0} />)
    await screen.findByText(/Some review controls are unavailable/)
    click('Preset editor')
    expect(button('Save schedule changes')).toBeDisabled()
    expect(screen.getByLabelText('Review preset')).toBeDisabled()
    expect(writes(fetchMock)).toHaveLength(0)
  })

  it('arming a schedule is an explicit versioned save, not an enable-checkbox side effect', async () => {
    const { fetchMock } = await mount(true)
    click('Preset editor'); click('Consent and time zone')
    fireEvent.click(screen.getByLabelText('Enable scheduled reviews'))
    expect(writes(fetchMock)).toHaveLength(0)
    click('Save schedule changes')
    await waitFor(() => expect(writes(fetchMock)).toHaveLength(1))
    expect(JSON.parse(writes(fetchMock)[0][1].body)).toMatchObject({ expectedVersion: 3, configuration: { enabled: true, paused: false } })
    expect(fetchMock.mock.calls.some(([path]) => /interpretation|analysis/.test(path))).toBe(false)
  })

  it('all typed arrival/count/step rules are editable and eight triggers is a hard add limit', async () => {
    const { fetchMock } = await mount(true)
    click('Preset editor'); click('Trigger editor')
    change('Trigger 1 type', 'wellness_arrival')
    change('Trigger 1 settle delay (seconds)', '0')
    click('Next trigger'); change('Trigger 2 type', 'record_count'); change('Trigger 2 count period', 'week'); change('Trigger 2 threshold', '1000000')
    click('Add trigger'); change('Trigger 3 type', 'daily_steps'); change('Trigger 3 threshold', '5000')
    expect(screen.queryByLabelText('Trigger 3 count period')).not.toBeInTheDocument()
    for (let i = 0; i < 5; i++) click('Add trigger')
    expect(button('Add trigger')).toBeDisabled()
    click('Save schedule changes')
    await waitFor(() => expect(writes(fetchMock)).toHaveLength(1))
    const saved = JSON.parse(writes(fetchMock)[0][1].body).configuration.presets[0].triggers
    expect(saved).toHaveLength(8)
    expect(saved[0]).toMatchObject({ kind: 'wellness_arrival', settleSeconds: 0 })
    expect(saved[1]).toMatchObject({ kind: 'record_count', threshold: 1000000, period: 'week' })
    expect(saved[2]).toMatchObject({ kind: 'daily_steps', threshold: 5000, period: 'day' })
  })

  it('duplicate preset IDs fail closed and presets are explicitly added/removed up to twelve', async () => {
    const { fetchMock } = await mount(true)
    click('Preset editor'); change('Preset ID', 'weekly')
    expect(button('Save schedule changes')).toBeDisabled()
    change('Preset ID', 'daily-combined')
    for (let i = 0; i < 9; i++) click('Add preset')
    expect(button('Add preset')).toBeDisabled()
    expect(screen.getByLabelText('Preset ID')).toHaveValue('preset-9')
    click('Remove preset')
    expect(button('Add preset')).not.toBeDisabled()
    click('Save schedule changes')
    await waitFor(() => expect(writes(fetchMock)).toHaveLength(1))
    expect(JSON.parse(writes(fetchMock)[0][1].body).configuration.presets).toHaveLength(11)
  })

  it('write errors and version conflicts preserve drafts and show only fixed generic text', async () => {
    const raw = vi.fn(async () => ({ message: 'SECRET conflict or provider details' }))
    const fetchMock = vi.fn(async (path: string, init: RequestInit) => init.method === 'PUT' ? { ok: false, status: 409, json: raw } :
      response(path === '/api/review-models' ? catalogue(true) : path === '/api/review-schedules' ? scheduleStatus() : queueStatus(true)))
    vi.stubGlobal('fetch', fetchMock)
    render(<ReviewControls csrfToken={csrf} revision={0} />); await ready()
    click('Preset editor'); change('Preset ID', 'dirty-conflicting-preset'); click('Save schedule changes')
    await screen.findByText(/Review request failed or response could not be verified/)
    expect(screen.getByLabelText('Preset ID')).toHaveValue('dirty-conflicting-preset')
    expect(raw).not.toHaveBeenCalled()
    expect(screen.queryByText(/SECRET/)).not.toBeInTheDocument()
    expect(JSON.parse(writes(fetchMock)[0][1].body).expectedVersion).toBe(3)
  })
})

describe('queue requests and metadata separation', () => {
  it('immediate interim enqueues with CSRF/version despite an evening thorough rule and disabled schedule', async () => {
    const { state, fetchMock } = server(true)
    state.schedules.configuration.presets[0].triggers = [trigger('daily_time', 'evening')]
    state.schedules.configuration.presets[0].level = 'thorough'
    render(<ReviewControls csrfToken={csrf} revision={0} />); await ready()
    expect(button('Run review now — enqueue')).not.toBeDisabled()
    click('Run review now — enqueue')
    await screen.findByText(/Immediate review enqueued, not a synchronous model call/)
    expect(writes(fetchMock)[0]).toEqual(['/api/review-now', expect.objectContaining({ method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': csrf },
      body: JSON.stringify({ expectedVersion: 3, presetId: 'daily-combined', level: 'interim' }) })])
    expect(state.schedules.configuration.presets[0].level).toBe('thorough')
    expect(state.schedules.configuration.presets[0].triggers[0].localTime).toBe('18:00')
    expect(fetchMock.mock.calls.some(([path]) => path === '/api/review-interpretation' || path === '/api/analysis')).toBe(false)
  })

  it('requires received activity identity for activity-scope Run now', async () => {
    const { fetchMock } = await mount(true)
    change('Immediate review preset', '1')
    expect(button('Run review now — enqueue')).toBeDisabled()
    expect(screen.getByText(/Activity-scope review needs a received activity identity/)).toBeInTheDocument()
    expect(writes(fetchMock)).toHaveLength(0)
  })

  it('blocks paused and unsaved requests; level changes do not request anything', async () => {
    const { state, fetchMock } = server(true)
    state.schedules.configuration.paused = true
    state.queue.executionAvailable = false
    render(<ReviewControls csrfToken={csrf} revision={0} />); await ready()
    change('Immediate review level', 'thorough')
    expect(button('Run review now — enqueue')).toBeDisabled()
    click('Preset editor'); change('Preset ID', 'unsaved')
    click('Review overview')
    expect(button('Run review now — enqueue')).toBeDisabled()
    expect(screen.getByText(/Save or discard edits/)).toBeInTheDocument()
    expect(writes(fetchMock)).toHaveLength(0)
  })

  it('pages running/pending jobs, dates, reasons and eligibility without promising start times', async () => {
    const { state } = server(true)
    state.queue.pending = [job('b'.repeat(64)), { ...job('c'.repeat(64)), intent: { ...intent(), scope: { kind: 'rolling', days: 7, sport: null } } }]
    state.queue.running = { ...job(), startedUtc: '2020-06-10T12:00:00Z' }
    render(<ReviewControls csrfToken={csrf} revision={0} />); await ready()
    click('Queue status')
    expect(screen.getByText('Pending: 2. Running: 1.')).toBeInTheDocument()
    expect(screen.getByText(/Started UTC: 2020-06-10T12:00:00Z/)).toBeInTheDocument()
    expect(screen.queryByText(/Maximum deferral:/)).not.toBeInTheDocument()
    click('Next queued job')
    expect(screen.getByText(/Maximum deferral:.*2020-06-10T12:10:00Z/)).toBeInTheDocument()
    expect(screen.getByText(/Estimated eligibility only, not a guaranteed start/)).toBeInTheDocument()
    expect(screen.getByText(/Metric coverage dates: 2020-06-10–2020-06-10. Reasons: Explicit immediate request/)).toBeInTheDocument()
    click('Next queued job')
    expect(screen.getByText(/Rolling dates, 7 date/)).toBeInTheDocument()
    expect(button('Next queued job')).toBeDisabled()
  })

  it('distinguishes conditional next checks from eligible local-zone reviews, and unsupported steps have no guessed date', async () => {
    const { state } = server(true)
    state.schedules.configuration.timeZone = 'Europe/Prague'
    state.schedules.triggers.push({ presetId: 'daily-combined', triggerId: 'steps', kind: 'daily_steps', reason: 'verified_daily_steps_unavailable', nextCheckUtc: null, nextReviewUtc: null })
    render(<ReviewControls csrfToken={csrf} revision={0} />); await ready()
    click('Schedule timing')
    expect(screen.getByText(/Next check:.*Europe\/Prague.*conditional on changed data/)).toBeInTheDocument()
    expect(screen.queryByText(/Next review eligible:/)).not.toBeInTheDocument()
    click('Next trigger status')
    expect(screen.getByText(/Next review eligible:.*Europe\/Prague.*2020-06-10T18:00:00Z/)).toBeInTheDocument()
    click('Next trigger status')
    expect(screen.getByText(/Verified daily steps are unavailable; this rule cannot run/)).toBeInTheDocument()
    expect(screen.getByText(/No known next review time; none is fabricated/)).toBeInTheDocument()
    expect(screen.queryByText(/Next review eligible:/)).not.toBeInTheDocument()
  })

  it('unknown status rules never fabricate a next date, even when an unrecognized response supplies one', async () => {
    const { state } = server(true)
    state.schedules.triggers = [{ ...state.schedules.triggers[0], kind: 'unknown_future_rule', reason: 'PRIVATE arbitrary text', nextReviewUtc: '2020-06-10T18:00:00Z' }]
    render(<ReviewControls csrfToken={csrf} revision={0} />); await ready(); click('Schedule timing')
    expect(screen.getByText(/No known next review time; none is fabricated/)).toBeInTheDocument()
    expect(screen.queryByText(/18:00/)).not.toBeInTheDocument()
    expect(screen.queryByText(/PRIVATE/)).not.toBeInTheDocument()
  })

  it.each(['2020-02-30T18:00:00Z', '2020-06-10T24:00:00Z', 'unknown'])('rejects invalid next-time %s rather than normalizing a fabricated date', async timestamp => {
    const { state } = server(true)
    state.schedules.triggers[0].nextCheckUtc = timestamp
    render(<ReviewControls csrfToken={csrf} revision={0} />)
    await screen.findByText(/Some review controls are unavailable/)
    click('Schedule timing')
    expect(screen.getByText(/Schedule status unavailable. No next review time is inferred/)).toBeInTheDocument()
    expect(screen.queryByText(/Next check:/)).not.toBeInTheDocument()
  })

  it('shows last-success UTC/coverage/digest and stale status; completed outcomes are individually paged', async () => {
    const { state } = server(true)
    state.queue.lastSuccess = { ...outcome(), stale: true }
    state.queue.outcomes = [state.queue.lastSuccess, { ...outcome('b'.repeat(64)), status: 'unavailable', facts: null, reason: 'unusable_model_output' }]
    render(<ReviewControls csrfToken={csrf} revision={0} />); await ready(); click('Completed coverage')
    expect(screen.getByRole('heading', { name: 'Last successful review' })).toBeInTheDocument()
    expect(screen.getByText(/Finished UTC: 2020-06-10T12:02:00Z. Metric coverage dates: 2020-06-10–2020-06-10/)).toBeInTheDocument()
    expect(screen.getByText(/Older than the latest data or settings/)).toBeInTheDocument()
    expect(screen.getByText(/Factual snapshot: recorded.*Evidence digest: a{64}/)).toBeInTheDocument()
    expect(screen.getByText(/Optional AI result: not recorded/)).toBeInTheDocument()
    click('Next completed review')
    expect(screen.getByText(/Factual snapshot: unavailable or unverified/)).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Last successful review' })).not.toBeInTheDocument()
  })

  it.each(['unsupported-prose', 'wrong-profile', 'wrong-digest', 'malformed-shape', 'unsupported-facts'])('withholds the whole optional interpretation: %s; metadata/charts survive', async variant => {
    const { state } = server(true)
    const stored = outcome()
    stored.interpretation = { profile: variant === 'wrong-profile' ? 'prototype' : 'review-focus-app-v1', status: 'available',
      reason: 'validated_focus_selection', evidenceReportSha256: variant === 'wrong-digest' ? 'b'.repeat(64) : hash,
      interpretations: variant === 'malformed-shape' ? 'invalid' : [{ text: 'SECRET unsupported illness/workout/calendar claim', kind: 'unsupported', groupId: 'g0', supportingFacts: [] }] }
    if (variant === 'unsupported-facts') stored.facts = { ...facts(), groups: [{ text: 'SECRET unsupported fact prose', value: -50 }] }
    state.queue.lastSuccess = stored
    render(<><p>Independent synthetic metric chart</p><ReviewControls csrfToken={csrf} revision={0} /></>); await ready(); click('Completed coverage')
    expect(screen.queryByText(/SECRET/)).not.toBeInTheDocument()
    expect(screen.getByText(/Optional AI result: withheld/)).toBeInTheDocument()
    expect(screen.getByText(/Metric coverage dates: 2020-06-10–2020-06-10/)).toBeInTheDocument()
    expect(screen.getByText('Independent synthetic metric chart')).toBeInTheDocument()
  })

  it.each(['profile', 'marker', 'digest', 'focus', 'shape'])('unverified stored facts %s never become factual prose or verified values', async variant => {
    const { state } = server(true)
    const stored = facts() as Record<string, unknown>
    if (variant === 'profile') stored.profile = 'model-generated'
    if (variant === 'marker') stored.applicationGenerated = false
    if (variant === 'digest') stored.evidenceReportSha256 = 'invalid'
    if (variant === 'focus') stored.requestedFocus = 'unsupported'
    if (variant === 'shape') stored.groups = 'SECRET arbitrary body'
    state.queue.lastSuccess = { ...outcome(), facts: stored }
    render(<ReviewControls csrfToken={csrf} revision={0} />); await ready(); click('Completed coverage')
    expect(screen.getByText(/Factual snapshot: unavailable or unverified/)).toBeInTheDocument()
    expect(screen.queryByText(/SECRET/)).not.toBeInTheDocument()
    expect(screen.queryByText(/Evidence digest:/)).not.toBeInTheDocument()
  })

  it('rejects malformed queue payload instead of rendering arbitrary status/body text', async () => {
    const { state } = server(true)
    state.queue.pending = [{ ...job(), eligibleUtc: 'arbitrary provider text' }]
    render(<ReviewControls csrfToken={csrf} revision={0} />)
    await screen.findByText(/Some review controls are unavailable/)
    expect(button('Run review now — enqueue')).toBeDisabled()
    expect(screen.queryByText(/arbitrary provider text/)).not.toBeInTheDocument()
  })
})

describe('active polling and request generations', () => {
  async function settleTimers(ms = 0) { await act(async () => { await vi.advanceTimersByTimeAsync(ms) }) }

  it('polls every 10 seconds only while active, never writes, and unmount stops timers', async () => {
    vi.useFakeTimers()
    const { state, fetchMock } = server(true)
    state.queue.pending = [job()]
    const view = render(<ReviewControls csrfToken={csrf} revision={0} />)
    await settleTimers()
    expect(fetchMock).toHaveBeenCalledTimes(3)
    await settleTimers(9999); expect(fetchMock).toHaveBeenCalledTimes(3)
    await settleTimers(1); expect(fetchMock).toHaveBeenCalledTimes(6)
    expect(writes(fetchMock)).toHaveLength(0)
    state.queue.pending = []
    await settleTimers(10000); expect(fetchMock).toHaveBeenCalledTimes(9)
    await settleTimers(20000); expect(fetchMock).toHaveBeenCalledTimes(9)
    view.unmount()
    await settleTimers(10000); expect(fetchMock).toHaveBeenCalledTimes(9)
  })

  it('does not poll idle/off settings', async () => {
    vi.useFakeTimers()
    const { fetchMock } = server()
    render(<ReviewControls csrfToken={csrf} revision={0} />)
    await settleTimers(); await settleTimers(30000)
    expect(fetchMock).toHaveBeenCalledTimes(3)
  })

  it('polling never overwrites dirty schedule/model drafts or silently rebases their expectedVersion', async () => {
    vi.useFakeTimers()
    const { state, fetchMock } = server(true)
    state.queue.pending = [job()]
    render(<ReviewControls csrfToken={csrf} revision={0} />); await settleTimers()
    click('Preset editor'); change('Preset ID', 'dirty-preset')
    click('Model selection'); change('Review local model', other.id)
    state.schedules.configuration.presets[0].id = 'remote-preset'; state.schedules.configurationVersion = 9
    state.models.selectedModelId = model.id; state.models.enabled = true
    await settleTimers(10000)
    expect(screen.getByLabelText('Review local model')).toHaveValue(other.id)
    expect(screen.getByLabelText('Enable optional local AI interpretation')).not.toBeChecked()
    click('Preset editor')
    expect(screen.getByLabelText('Preset ID')).toHaveValue('dirty-preset')
    expect(screen.getByRole('status')).toHaveTextContent('Configuration changed remotely')
    click('Save schedule changes'); await settleTimers()
    expect(JSON.parse(writes(fetchMock)[0][1].body).expectedVersion).toBe(3)
  })

  it.each(['revision', 'session', 'unmount'])('aborts in-flight reads on %s and ignores stale completions even if abort is ignored', async mode => {
    const old = catalogue(true)
    const fresh = catalogue(false)
    const held: { resolve: (v: unknown) => void; signal: AbortSignal }[] = []
    let delay = true
    vi.stubGlobal('fetch', vi.fn(async (path: string, init: RequestInit) => {
      if (delay) return { ok: true, json: () => new Promise(resolve => { held.push({ resolve, signal: init.signal as AbortSignal }) }) }
      return response(path === '/api/review-models' ? fresh : path === '/api/review-schedules' ? scheduleStatus() : queueStatus())
    }))
    const view = render(<ReviewControls csrfToken={csrf} revision={0} />)
    await waitFor(() => expect(held).toHaveLength(3))
    delay = false
    if (mode === 'unmount') view.unmount()
    else view.rerender(<ReviewControls csrfToken={mode === 'session' ? 't'.repeat(43) : csrf} revision={mode === 'revision' ? 1 : 0} />)
    expect(held.every(h => h.signal.aborted)).toBe(true)
    if (mode !== 'unmount') await ready()
    await act(async () => held.forEach((h, i) => h.resolve(i === 0 ? old : i === 1 ? scheduleStatus() : queueStatus(true))))
    if (mode !== 'unmount') {
      click('Model selection')
      expect(screen.getByLabelText('Enable optional local AI interpretation')).not.toBeChecked()
      expect(screen.getByLabelText('Review local model')).toHaveValue('')
    }
  })

  it('model save aborts a pending status poll; late status cannot restore an old model', async () => {
    vi.useFakeTimers()
    const state = { models: catalogue(true), schedules: scheduleStatus(), queue: { ...queueStatus(true), pending: [job()] } }
    const held: { resolve: (v: unknown) => void; signal: AbortSignal; path: string }[] = []
    let poll = false
    const fetchMock = vi.fn(async (path: string, init: RequestInit) => {
      if (init.method === 'PUT') {
        state.models = { ...state.models, selectedModelId: other.id, enabled: false, selectionVersion: 2 }
        poll = false
        return response(state.models)
      }
      if (poll) return { ok: true, json: () => new Promise(resolve => held.push({ resolve, signal: init.signal as AbortSignal, path })) }
      return response(path === '/api/review-models' ? state.models : path === '/api/review-schedules' ? state.schedules : state.queue)
    })
    vi.stubGlobal('fetch', fetchMock)
    render(<ReviewControls csrfToken={csrf} revision={0} />); await settleTimers()
    click('Model selection'); change('Review local model', other.id)
    poll = true; await settleTimers(10000)
    expect(held).toHaveLength(3)
    click('Save model choices'); await settleTimers()
    expect(held.every(h => h.signal.aborted)).toBe(true)
    await act(async () => held.forEach(h => h.resolve(h.path === '/api/review-models' ? catalogue(true) : h.path === '/api/review-schedules' ? scheduleStatus() : queueStatus(true))))
    expect(screen.getByLabelText('Review local model')).toHaveValue(other.id)
    expect(screen.getByLabelText('Enable optional local AI interpretation')).not.toBeChecked()
  })

  it('schedule save aborts status reads and cannot resurrect an old version/dirty draft', async () => {
    vi.useFakeTimers()
    const state = { schedules: scheduleStatus(), models: catalogue(true), queue: { ...queueStatus(true), pending: [job()] } }
    let poll = false
    const held: { resolve: (v: unknown) => void; signal: AbortSignal; path: string }[] = []
    vi.stubGlobal('fetch', vi.fn(async (path: string, init: RequestInit) => {
      if (init.method === 'PUT') {
        state.schedules = { ...state.schedules, configuration: JSON.parse(init.body as string).configuration, configurationVersion: 4 }
        poll = false; return response(state.schedules)
      }
      if (poll) return { ok: true, json: () => new Promise(resolve => held.push({ resolve, signal: init.signal as AbortSignal, path })) }
      return response(path === '/api/review-models' ? state.models : path === '/api/review-schedules' ? state.schedules : state.queue)
    }))
    render(<ReviewControls csrfToken={csrf} revision={0} />); await settleTimers()
    click('Preset editor'); change('Preset ID', 'saved-preset')
    poll = true; await settleTimers(10000)
    click('Save schedule changes'); await settleTimers()
    expect(held.every(h => h.signal.aborted)).toBe(true)
    await act(async () => held.forEach(h => h.resolve(h.path === '/api/review-models' ? catalogue(true) : h.path === '/api/review-schedules' ? scheduleStatus() : queueStatus(true))))
    expect(screen.getByLabelText('Preset ID')).toHaveValue('saved-preset')
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
  })

  it('revision/session changes abort pending writes; late successful writes cannot restore discarded client state', async () => {
    let finish!: (v: unknown) => void
    let signal!: AbortSignal
    vi.stubGlobal('fetch', vi.fn(async (path: string, init: RequestInit) => {
      if (init.method === 'PUT') { signal = init.signal as AbortSignal; return { ok: true, json: () => new Promise(resolve => { finish = resolve }) } }
      return response(path === '/api/review-models' ? catalogue() : path === '/api/review-schedules' ? scheduleStatus() : queueStatus())
    }))
    const view = render(<ReviewControls csrfToken={csrf} revision={0} />); await ready()
    click('Model selection'); change('Review local model', model.id); click('Save model choices')
    await waitFor(() => expect(finish).toBeDefined())
    view.rerender(<ReviewControls csrfToken={'t'.repeat(43)} revision={1} />)
    expect(signal.aborted).toBe(true)
    await ready()
    await act(async () => finish(catalogue(true)))
    expect(screen.getByLabelText('Review local model')).toHaveValue('')
    expect(screen.getByLabelText('Enable optional local AI interpretation')).not.toBeChecked()
  })
})
