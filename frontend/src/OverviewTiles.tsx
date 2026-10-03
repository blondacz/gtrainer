import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import { reportFrom, type MetricSeries, type TrendReport } from './TrendPanel'

type Section = 'charts' | 'imports' | 'reviews' | 'events'
type Variant = 'activity' | 'wellness' | 'events' | 'reviews' | 'source' | 'unknown'
type Tile = { id: string; label: string; value: string; unit?: string; detail: string; extra?: string; variant: Variant; section: Section }
type Source = { category: string; recordCount: number; readStatus: string; lastSuccessUtc: string | null; latestObservedDate: string | null }
type ManualEvent = { id: string; startDate: string; endDate: string; sport: string; goal: string; notes: string | null }
type Events = { events: ManualEvent[]; nextUpcoming: ManualEvent | null; ongoing: ManualEvent[]; evaluatedOn: string }
type Coverage = { oldest: string; newest: string; finishedUtc: string; stale: boolean }
type Reviews = { queued: number; running: boolean; executionAvailable: boolean; last: Coverage | null }
type Data<T> = { revision: number; query: object; value: T | null; failed: boolean }
const object = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v)
const integer = (v: unknown): v is number => typeof v === 'number' && Number.isSafeInteger(v) && v >= 0
const date = (v: unknown): v is string => typeof v === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(v) &&
  Number.isFinite(Date.parse(v)) && new Date(v).toISOString().slice(0, 10) === v
const utc = (v: unknown): v is string => typeof v === 'string' && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/.test(v) &&
  date(v.slice(0, 10)) && Number.isFinite(Date.parse(v)) && /T(?:[01]\d|2[0-3]):[0-5]\d:[0-5]\d/.test(v)
const optionalUtc = (v: unknown) => v === null || utc(v)
const text = (v: unknown, max = 256): v is string => typeof v === 'string' && v.length <= max
const readLabels: Record<string, string> = { NEVER_READ: 'Not imported', RUNNING: 'Read running', SUCCESS: 'Read completed',
  NOT_CONFIGURED: 'Key unavailable', KEY_REJECTED: 'Key rejected', ACCESS_DENIED: 'Access denied', RATE_LIMITED: 'Rate limited',
  TRANSPORT_ERROR: 'Connection failed', UPSTREAM_ERROR: 'Provider failed', REDIRECT_BLOCKED: 'Redirect blocked',
  RESPONSE_TOO_LARGE: 'Response too large', INVALID_RESPONSE: 'Response unusable', INTERRUPTED: 'Read interrupted' }
const number = new Intl.NumberFormat(undefined, { maximumFractionDigits: 2 })
const dayOffset = (input: string, days: number) => new Date(Date.parse(input) + days * 86400000).toISOString().slice(0, 10)

function sourcesFrom(v: unknown): Source[] {
  if (!Array.isArray(v) || v.length !== 2 || !v.every(s => object(s) && ['activities', 'wellness'].includes(String(s.category)) &&
    integer(s.recordCount) && integer(s.rejected) && integer(s.incomplete) && text(s.readStatus) &&
    optionalUtc(s.lastAttemptUtc) && optionalUtc(s.lastSuccessUtc) && (s.latestObservedDate === null || date(s.latestObservedDate)) &&
    (s.latestObservedAgeDays === null || integer(s.latestObservedAgeDays)) && text(s.upstreamFreshness)) ||
    new Set(v.map(s => s.category)).size !== 2) throw new Error('Invalid source status')
  return v as Source[]
}
function eventValid(v: unknown): v is ManualEvent {
  return object(v) && typeof v.id === 'string' && /^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$/.test(v.id) &&
    date(v.startDate) && date(v.endDate) && v.startDate <= v.endDate && (Date.parse(v.endDate) - Date.parse(v.startDate)) / 86400000 <= 3660 &&
    text(v.sport, 80) && !!v.sport.trim() && text(v.goal, 2000) && !!v.goal.trim() && (v.notes == null || text(v.notes, 4000))
}
function eventsFrom(v: unknown): Events {
  if (!object(v) || !date(v.evaluatedOn) || !Array.isArray(v.events) || !v.events.every(eventValid) ||
    new Set(v.events.map(e => e.id)).size !== v.events.length || !Array.isArray(v.ongoing) || !v.ongoing.every(eventValid) ||
    (v.nextUpcoming !== null && !eventValid(v.nextUpcoming))) throw new Error('Invalid events')
  const events = [...v.events].sort((a, b) => a.startDate.localeCompare(b.startDate) || a.endDate.localeCompare(b.endDate) || a.id.localeCompare(b.id))
  const next = events.find(e => e.startDate >= (v.evaluatedOn as string)) ?? null
  const ongoing = events.filter(e => e.startDate < (v.evaluatedOn as string) && e.endDate >= (v.evaluatedOn as string))
  const same = (a: ManualEvent, b: ManualEvent) => a.id === b.id && a.startDate === b.startDate && a.endDate === b.endDate && a.sport === b.sport && a.goal === b.goal && a.notes === b.notes
  if ((next === null ? v.nextUpcoming !== null : v.nextUpcoming === null || !same(next, v.nextUpcoming as ManualEvent)) ||
    ongoing.length !== v.ongoing.length || !ongoing.every(e => (v.ongoing as ManualEvent[]).some(item => same(e, item)))) throw new Error('Mismatched events')
  return { events, nextUpcoming: next, ongoing, evaluatedOn: v.evaluatedOn }
}
function reviewsFrom(v: unknown): Reviews {
  // Validate metadata only. Stored facts and model interpretation are opaque and never rendered.
  const intent = (x: unknown) => object(x) && date(x.oldest) && date(x.newest) && x.oldest <= x.newest
  const job = (x: unknown) => object(x) && text(x.id) && intent(x.intent) && utc(x.firstQueuedUtc) && utc(x.lastQueuedUtc) &&
    utc(x.eligibleUtc) && utc(x.maximumDeferralUtc) && optionalUtc(x.startedUtc) && typeof x.manual === 'boolean'
  const outcome = (x: unknown) => object(x) && text(x.id) && intent(x.intent) && utc(x.finishedUtc) && optionalUtc(x.startedUtc) &&
    typeof x.stale === 'boolean' && ['available', 'factual_only', 'unavailable', 'cancelled', 'interrupted', 'failed'].includes(String(x.status))
  if (!object(v) || !text(v.reason) || typeof v.executionAvailable !== 'boolean' || !object(v.policy) ||
    !integer(v.policy.debounceSeconds) || !integer(v.policy.cooldownSeconds) || !integer(v.policy.maximumDeferralSeconds) ||
    !Array.isArray(v.pending) || v.pending.length > 256 || !v.pending.every(job) || (v.running !== null && !job(v.running)) ||
    !Array.isArray(v.outcomes) || v.outcomes.length > 24 || !v.outcomes.every(outcome) ||
    (v.lastSuccess !== null && (!object(v.lastSuccess) || !outcome(v.lastSuccess) || !['available', 'factual_only'].includes(String(v.lastSuccess.status))))) throw new Error('Invalid review metadata')
  const last = v.lastSuccess as (Coverage & { intent: { oldest: string; newest: string } }) | null
  return { queued: v.pending.length, running: v.running !== null, executionAvailable: v.executionAvailable,
    last: last ? { oldest: last.intent.oldest, newest: last.intent.newest, finishedUtc: last.finishedUtc, stale: last.stale } : null }
}
function pageSize(height: number) { return height <= 360 ? 1 : height <= 650 ? 2 : height <= 750 ? 4 : height <= 1000 ? 6 : 8 }

export function NumericTile({ tile, onNavigate }: { tile: Tile; onNavigate: (section: Section) => void }) {
  const icons: Record<Variant, string> = { activity: '↗', wellness: '◌', events: '▦', reviews: '≡', source: '⇄', unknown: '?' }
  return <button type="button" className={`dashboard-tile ${tile.variant} dashboard-tile--${tile.variant}`} onClick={() => onNavigate(tile.section)} aria-label={`${tile.label}: ${tile.value}${tile.unit ? ` ${tile.unit}` : ''}. ${tile.detail}${tile.extra ? `. ${tile.extra}` : ''}. View ${tile.section}`}>
    <span className="tile-icon" aria-hidden="true">{icons[tile.variant]}</span>
    <span className="tile-label">{tile.label}</span>
    <span className={`tile-value${date(tile.value) ? ' tile-value--date' : ''}`}>{tile.value}<span className="tile-unit">{tile.unit ? ` ${tile.unit}` : ''}</span></span>
    <span className="tile-detail">{tile.detail}</span>
    {tile.extra && <span className="tile-detail">{tile.extra}</span>}
    <span className="tile-link">View {tile.section === 'events' ? 'event details / full goal' : tile.section}</span>
  </button>
}

export function OverviewTiles({ revision, onNavigate }: { revision: number; onNavigate: (section: Section) => void }) {
  const container = useRef<HTMLElement | null>(null)
  const today = new Date().toISOString().slice(0, 10)
  const [oldest, setOldest] = useState(() => dayOffset(today, -13))
  const [newest, setNewest] = useState(today)
  const [query, setQuery] = useState({ oldest, newest })
  const [message, setMessage] = useState('')
  const [reportData, setReport] = useState<Data<TrendReport> | null>(null)
  const [sourceData, setSources] = useState<Data<Source[]> | null>(null)
  const [eventsData, setEvents] = useState<Data<Events> | null>(null)
  const [reviewData, setReviews] = useState<Data<Reviews> | null>(null)
  const [size, setSize] = useState(() => pageSize(window.innerHeight))
  const [page, setPage] = useState(0)
  const generation = useRef(0)
  useEffect(() => {
    const resize = () => { setSize(pageSize(window.innerHeight)); setPage(0) }
    window.addEventListener('resize', resize)
    return () => window.removeEventListener('resize', resize)
  }, [])
  useEffect(() => {
    const epoch = ++generation.current
    const controller = new AbortController()
    setReport(null); setSources(null); setEvents(null); setReviews(null); setPage(0)
    const current = () => generation.current === epoch && !controller.signal.aborted
    async function load<T>(path: string, parse: (value: unknown) => T, accept: (value: Data<T>) => void) {
      try {
        const response = await fetch(path, { method: 'GET', cache: 'no-store', signal: controller.signal })
        if (!response.ok) throw new Error('Private data unavailable')
        const value = parse(await response.json())
        if (current()) accept({ revision, query, value, failed: false })
      } catch {
        if (current()) accept({ revision, query, value: null, failed: true })
      }
    }
    void load(`/api/trends?${new URLSearchParams(query)}`, value => {
      const report = reportFrom(value)
      const days = (Date.parse(query.newest) - Date.parse(query.oldest)) / 86400000 + 1
      if (report.selectedSport !== null || report.current.oldest !== query.oldest || report.current.newest !== query.newest ||
        report.previous.oldest !== dayOffset(query.oldest, -days) || report.previous.newest !== dayOffset(query.oldest, -1) ||
        [...report.current.sports, ...report.previous.sports].some(g => g.metrics.some(m => ['movingTime', 'calories'].includes(m.key) && m.aggregation !== 'sum')) ||
        [...report.current.wellness, ...report.previous.wellness].some(m => ['weight', 'hrv', 'sleepSecs', 'vo2max'].includes(m.key) && m.aggregation !== 'mean')) throw new Error('Mismatched period')
      return report
    }, setReport)
    void load('/api/imports', sourcesFrom, setSources)
    void load('/api/events', eventsFrom, setEvents)
    void load('/api/review-queue', reviewsFrom, setReviews)
    return () => { controller.abort(); generation.current++ }
  }, [query, revision])
  function usable<T>(data: Data<T> | null) { return data?.revision === revision && data.query === query ? data : null }
  const report = usable(reportData)?.value
  const sources = usable(sourceData)?.value
  const events = usable(eventsData)?.value
  const reviews = usable(reviewData)?.value
  const state = (data: Data<unknown> | null) => usable(data)?.failed ? 'Unavailable' : 'Loading…'
  const tiles: Tile[] = []
  function sourceTile(category: string): Tile {
    const source = sources?.find(s => s.category === category)
    return { id: `source-${category}`, label: `${category === 'activities' ? 'Activity' : 'Wellness'} imports`, variant: 'source', section: 'imports',
      value: source ? number.format(source.recordCount) : state(sourceData), unit: source ? 'stored' : undefined,
      detail: source ? `${readLabels[source.readStatus] ?? 'Status unknown'} • latest ${source.latestObservedDate ?? 'unknown'}` : 'Import status could not be verified yet',
      extra: source ? `Last read ${source.lastSuccessUtc?.slice(0, 10) ?? 'none'} UTC • upstream freshness unknown` : 'Upstream freshness unknown' }
  }
  function metricTile(id: string, label: string, current: MetricSeries | undefined, previous: MetricSeries | undefined, variant: Variant, unit: string, divisor = 1): Tile {
    const display = (m: MetricSeries | undefined) => m?.value == null ? 'unavailable' : number.format(m.value / divisor)
    return { id, label, variant, section: 'charts', value: report ? display(current) : state(reportData), unit,
      detail: `${variant === 'wellness' ? 'Record mean' : 'Observed total'} • ${current ? `${current.sampleCount} ${variant === 'wellness' ? 'wellness' : 'activity'} records` : 'record population unavailable'}`,
      extra: `Prior ${display(previous)} ${unit} • ${previous ? `${previous.sampleCount} records` : 'population unavailable'}` }
  }
  tiles.push(sourceTile('activities'))
  const sports = report ? [...new Set([...report.current.sports.map(g => g.sport), ...report.previous.sports.map(g => g.sport)])] : []
  const activity: Tile[] = (sports.length ? sports : ['All sports']).flatMap(sport => {
    const current = report?.current.sports.find(g => g.sport === sport)
    const previous = report?.previous.sports.find(g => g.sport === sport)
    const name = sport.length > 32 ? 'Recorded sport (see charts)' : sport
    return [metricTile(`${sport}-time`, `${name} moving time`, current?.metrics.find(m => m.key === 'movingTime'), previous?.metrics.find(m => m.key === 'movingTime'), 'activity', 'h', 3600),
      metricTile(`${sport}-calories`, `${name} calories`, current?.metrics.find(m => m.key === 'calories'), previous?.metrics.find(m => m.key === 'calories'), 'activity', 'kcal')]
  })
  tiles.push(activity[0])
  const wellness = [['weight', 'Weight', 'kg', 1], ['hrv', 'HRV', 'ms', 1], ['sleepSecs', 'Sleep duration', 'h', 3600], ['vo2max', 'VO2 max', 'mL/kg/min', 1]] as const
  const wellnessTiles = wellness.map(([key, label, unit, divisor]) => metricTile(key, label, report?.current.wellness.find(m => m.key === key), report?.previous.wellness.find(m => m.key === key), 'wellness', unit, divisor))
  tiles.push(...wellnessTiles.slice(0, 2))
  const next = events?.nextUpcoming
  tiles.push({ id: 'event', label: 'Next manual event', variant: 'events', section: 'events', value: events ? next?.startDate ?? 'None scheduled' : state(eventsData),
    detail: next ? `${next.sport.length > 32 ? 'Sport in details' : next.sport} • ends ${next.endDate}` : `${events ? `${events.ongoing.length} ongoing` : 'Ongoing count unavailable'} • UTC date ordering`,
    extra: next ? `Your goal: ${next.goal.length > 80 ? 'View event details for full goal' : next.goal}` : 'User-authored events only' })
  tiles.push({ id: 'review', label: 'Review queue', variant: 'reviews', section: 'reviews', value: reviews ? String(reviews.queued) : state(reviewData), unit: reviews ? 'queued' : undefined,
    detail: reviews ? `${reviews.running ? 'Running' : 'Not running'} • ${reviews.executionAvailable ? 'Execution enabled' : 'Execution unavailable'}` : 'Review metadata unavailable until verified',
    extra: reviews?.last ? `Last coverage ${reviews.last.oldest}–${reviews.last.newest}${reviews.last.stale ? ' • stale snapshot' : ' • stored snapshot'}` : 'Last coverage unavailable' })
  tiles.push(...wellnessTiles.slice(2), ...activity.slice(1), sourceTile('wellness'))
  tiles.push({ id: 'garmin', label: 'Garmin training readiness', value: 'Unavailable', variant: 'unknown', section: 'charts',
    detail: 'Proprietary readiness score not supplied', extra: 'Not estimated from activity or wellness' })
  const pages = Math.ceil(tiles.length / size)
  const selectedPage = Math.min(page, pages - 1)
  function select(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const days = (Date.parse(newest) - Date.parse(oldest)) / 86400000 + 1
    if (!date(oldest) || !date(newest) || days < 1 || days > 366 || newest > today) {
      setMessage('Choose 1–366 ordered dates, ending no later than today UTC.'); return
    }
    setMessage(''); setQuery({ oldest, newest })
  }
  useLayoutEffect(() => {
    const main = container.current?.closest('main')
    if (!main) return
    function fit() {
      // Authentication commits before the parent switches from the tall sign-in layout.
      if (main!.classList.contains('dashboard-main') && main!.getBoundingClientRect().height > window.innerHeight && size > 1)
        setSize(size > 2 ? size - 2 : 1)
    }
    fit()
    if (typeof ResizeObserver === 'undefined') return
    const observer = new ResizeObserver(fit)
    observer.observe(main)
    return () => observer.disconnect()
  }, [size, selectedPage, reportData, sourceData, eventsData, reviewData])
  return <section ref={container} className="overview" aria-labelledby="overview-heading">
    <h2 id="overview-heading">Overview</h2>
    <form className="overview-range" onSubmit={select}>
      <label htmlFor="overview-oldest">First date<input id="overview-oldest" type="date" required value={oldest} onChange={e => setOldest(e.target.value)} /></label>
      <label htmlFor="overview-newest">Last date<input id="overview-newest" type="date" required max={today} value={newest} onChange={e => setNewest(e.target.value)} /></label>
      <button type="submit" className="secondary">Show range</button>
    </form>
    {message && <p role="status">{message}</p>}
    <p className="overview-basis">{query.oldest}–{query.newest}; prior {dayOffset(query.oldest, -(Date.parse(query.newest) - Date.parse(query.oldest)) / 86400000 - 1)}–{dayOffset(query.oldest, -1)}.
      {' '}Intervals.icu records, not live. Activity local/wellness source dates; means weight records, not dates.</p>
    <div className="overview-grid" aria-label="Overview cards">{tiles.slice(selectedPage * size, (selectedPage + 1) * size).map(tile => <NumericTile key={tile.id} tile={tile} onNavigate={onNavigate} />)}</div>
    <nav className="overview-pagination" aria-label="Overview card pages">
      <button type="button" className="secondary" disabled={selectedPage === 0} onClick={() => setPage(selectedPage - 1)}>Previous cards</button>
      <span aria-live="polite">Card page {selectedPage + 1} of {pages}</span>
      <button type="button" className="secondary" disabled={selectedPage + 1 >= pages} onClick={() => setPage(selectedPage + 1)}>Next cards</button>
    </nav>
    <p className="overview-footnote">Colors identify categories, not health or readiness. Gaps don't prove inactivity or illness. No model calls on opening.</p>
  </section>
}
