import { useEffect, useState } from 'react'
import type { ReviewDateRange } from './ScopedReviewControls'

type CategoryStatus = {
  category: string; recordCount: number; lastAttemptUtc: string | null; lastSuccessUtc: string | null
  readStatus: string; rejected: number; incomplete: number; latestObservedDate: string | null
  latestObservedAgeDays: number | null; upstreamFreshness: string
}

const readLabels: Record<string, string> = {
  NEVER_READ: 'Not imported yet', RUNNING: 'Reading Intervals.icu…', SUCCESS: 'Read completed',
  NOT_CONFIGURED: 'Source key missing or unreadable', KEY_REJECTED: 'Key rejected. Replace it and retry.',
  ACCESS_DENIED: 'Access denied. Check credentials or provider filtering.', RATE_LIMITED: 'Provider rate limit. Retry later.',
  TRANSPORT_ERROR: 'Could not reach Intervals.icu. Check source connectivity.', UPSTREAM_ERROR: 'Provider read failed.',
  REDIRECT_BLOCKED: 'Unexpected redirect blocked.', RESPONSE_TOO_LARGE: 'Response too large. Choose a shorter range.',
  INVALID_RESPONSE: 'Provider response was not usable.', INTERRUPTED: 'Read interrupted. Retry when available.',
}

function statusList(value: unknown): CategoryStatus[] {
  if (!Array.isArray(value) || value.length !== 2) throw new Error('Invalid status response')
  const statuses = value.filter((entry): entry is CategoryStatus =>
    typeof entry === 'object' && entry !== null &&
    ['activities', 'wellness'].includes(entry.category as string) &&
    typeof entry.recordCount === 'number' && typeof entry.rejected === 'number' &&
    typeof entry.incomplete === 'number' && typeof entry.readStatus === 'string')
  if (statuses.length !== 2) throw new Error('Invalid status response')
  return statuses
}

function readableUtc(value: string | null) {
  if (typeof value !== 'string' || !Number.isFinite(Date.parse(value))) return 'Not recorded'
  return new Intl.DateTimeFormat('en-GB', { dateStyle: 'medium', timeStyle: 'short', timeZone: 'UTC' }).format(new Date(value)) + ' UTC'
}

export function ImportPanel({ csrfToken, configured, onChanged, compact = false, initialView = 'read', initialCategory = 'activities', overviewRange, onOpenTrends }: { csrfToken: string; configured: boolean; onChanged?: () => void; compact?: boolean; initialView?: 'read' | 'status'; initialCategory?: 'activities' | 'wellness'; overviewRange?: ReviewDateRange | null; onOpenTrends?: () => void }) {
  const today = new Date().toISOString().slice(0, 10)
  const earlier = new Date(Date.now() - 90 * 86400 * 1000).toISOString().slice(0, 10)
  const [oldest, setOldest] = useState(earlier)
  const [newest, setNewest] = useState(today)
  const [statuses, setStatuses] = useState<CategoryStatus[]>([])
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState('')
  const [view, setView] = useState<string>(initialView)
  const [category, setCategory] = useState<string>(initialCategory)

  useEffect(() => {
    const controller = new AbortController()
    async function load() {
      try {
        const response = await fetch('/api/imports', { cache: 'no-store', signal: controller.signal })
        if (!response.ok) throw new Error('Status unavailable')
        const body: unknown = await response.json()
        if (!controller.signal.aborted) setStatuses(statusList(body))
      } catch {
        if (!controller.signal.aborted) setMessage('Import status unavailable. Reconnect or sign in again.')
      }
    }
    void load()
    return () => controller.abort()
  }, [])

  async function sync(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const days = (Date.parse(newest) - Date.parse(oldest)) / 86400000
    if (!Number.isFinite(days) || days < 0 || days > 366) {
      setMessage('Choose an ordered range of at most 366 days. Older ranges can be imported separately.')
      return
    }
    setBusy(true)
    setMessage('')
    try {
      const response = await fetch('/api/sync', {
        method: 'POST', cache: 'no-store',
        headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': csrfToken },
        body: JSON.stringify({ oldest, newest }),
      })
      if (!response.ok) {
        setMessage(response.status === 409 ? 'Another private operation is running. Retry after it finishes.' :
          'Import unavailable. Check the connection, dates, or sign in again.')
        return
      }
      const body: unknown = await response.json()
      setStatuses(statusList(body))
      setView('status')
      onChanged?.()
      setMessage(`Import finished. Check Activities and Wellness ${compact ? 'in Import status' : 'below'}. Existing history is retained when a read fails.`)
    } catch {
      setMessage('Import unavailable. Reconnect and check its status before retrying.')
    } finally {
      setBusy(false)
    }
  }

  async function removeImports() {
    if (!window.confirm('Remove local imports, saved factual reviews and previews, and complete recording-match confirmation histories? Original notes, authored plans, events and skip-only confirmations are unchanged. Runtime ownership remains until stop verification; deletion does not prove a runtime stopped. Upstream accounts and retained encrypted backups are not erased.')) return
    setBusy(true)
    setMessage('')
    try {
      const response = await fetch('/api/imports', {
        method: 'DELETE', cache: 'no-store',
        headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': csrfToken },
        body: JSON.stringify({ confirmation: 'remove-local-imports' }),
      })
      if (!response.ok) throw new Error('Removal failed')
      onChanged?.()
      const status = await fetch('/api/imports', { cache: 'no-store' })
      if (!status.ok) throw new Error('Status unavailable')
      const body: unknown = await status.json()
      setStatuses(statusList(body))
      setMessage('Local imports and derived factual reviews and recording-match histories removed. Original notes, plans, events, skip-only confirmations, upstream accounts and retained backups are unchanged. Unresolved runtime ownership remains until stop verification.')
    } catch {
      setMessage('Could not verify removal. Reconnect or sign in again, then check the status.')
    } finally {
      setBusy(false)
    }
  }

  return <section className="panel" aria-labelledby="imports-heading">
    <h2 id="imports-heading">Garmin-derived history through Intervals.icu</h2>
    {compact && <nav className="section-tabs" aria-label="Source sections">{Object.entries({ read: 'Import history', status: 'Import status', remove: 'Remove imports', limits: 'Source limits' }).map(([name, label]) =>
      <button type="button" key={name} aria-pressed={view === name} onClick={() => setView(name)}>{label}</button>)}</nav>}
    {!compact && <p>Read only. No Garmin or Intervals.icu records are changed. Imported history stays on the Pi;
      backups go encrypted to your configured Mac. The read itself makes no model call; separately enabled review schedules may queue guarded analysis.</p>}
    {(!compact || view === 'read') && <form className="compact-form" onSubmit={event => void sync(event)}>
      <label htmlFor="import-oldest">First date</label>
      <input id="import-oldest" type="date" value={oldest} onChange={event => setOldest(event.target.value)} required disabled={busy} />
      <label htmlFor="import-newest">Last date</label>
      <input id="import-newest" type="date" value={newest} max={today} onChange={event => setNewest(event.target.value)} required disabled={busy} />
      <button type="submit" disabled={busy || !configured}>{busy ? 'Working…' : 'Read Intervals.icu'}</button>
    </form>}
    {!configured && <p>A source key is not configured. You can still view previously imported records; importing new history is unavailable.</p>}
    {compact && view === 'status' && <div className="dashboard-tile source">
      <h3>Import status — all stored dates</h3>
      <p>This is import information, not activity or wellness results for your Overview date range.</p>
      {overviewRange && <p>Your Overview range: {overviewRange.oldest} to {overviewRange.newest}. The import dates below are not filtered by this range.</p>}
      {overviewRange && onOpenTrends && <button type="button" onClick={onOpenTrends}>Open selected dates in Trends</button>}
      <label htmlFor="import-category">Imported category</label>
      <select id="import-category" value={category} onChange={event => setCategory(event.target.value)}><option value="activities">Activities</option><option value="wellness">Wellness</option></select>
      {(() => { const status = statuses.find(item => item.category === category); return status ? <>
        <p className="tile-value">{status.recordCount}<span className="tile-unit">stored records</span></p>
        <dl className="import-status-facts">
          <dt>Latest import result</dt><dd>{readLabels[status.readStatus] ?? 'Status unavailable'}</dd>
          <dt>Most recent stored record</dt><dd>{status.latestObservedDate ?? 'Not available'}</dd>
        </dl>
        <p>We cannot verify whether Intervals.icu has received the latest Garmin data. Missing records do not prove inactivity or illness.</p>
        <details><summary>Import timestamps and record checks</summary><dl className="import-status-facts">
          <dt>Last import attempt</dt><dd>{readableUtc(status.lastAttemptUtc)}</dd>
          <dt>Last successful import</dt><dd>{readableUtc(status.lastSuccessUtc)}</dd>
          <dt>Rejected records</dt><dd>{status.rejected}</dd>
          <dt>Incomplete records</dt><dd>{status.incomplete}</dd>
        </dl></details></> : <p>Read status unavailable.</p> })()}
    </div>}
    {compact && view === 'limits' && <section aria-label="Source limits">
      <h3>About this source</h3>
      <p>Reads only from Intervals.icu, an intermediary with its own privacy risks. No Garmin or Intervals.icu records are changed.</p>
      <p>Recent Garmin data may not yet be available through Intervals.icu. Missing values do not establish an upstream failure.</p>
      <p>Imported history stays on the app's device. Configured backups are encrypted; removing imports does not erase retained backups.</p>
      <p>The source read itself makes no model call. Separately selected, explicitly enabled review schedules can queue guarded local analysis. No hosted fallback.</p>
      <p>Recorded activity time and Intervals.icu load are not proprietary Garmin scores.</p>
    </section>}
    {!compact && <ul>{statuses.map(status => <li key={status.category}>
      <strong>{status.category === 'activities' ? 'Activities' : 'Wellness'}</strong>: {status.recordCount} stored;
      {' '}{readLabels[status.readStatus] ?? 'Status unavailable'}
      <p>Last attempt (UTC): {status.lastAttemptUtc ?? 'none'}. Last successful read (UTC): {status.lastSuccessUtc ?? 'none'}.</p>
      <p>Latest imported record date: {status.latestObservedDate ?? 'unavailable'}.
        {' '}Rejected in latest read: {status.rejected}; incomplete: {status.incomplete}.</p>
      {status.latestObservedAgeDays !== null && status.latestObservedAgeDays > 7 &&
        <p>The latest imported record is over seven days old. This does not prove an upstream sync failure or missed activity.</p>}
      <p>Garmin-to-Intervals.icu freshness is unverified. Missing health values do not imply illness.</p>
    </li>)}</ul>}
    {(!compact || view === 'remove') && <><p>Removal deletes imported records, saved factual review snapshots and their previews, and complete recording-match confirmation histories. Original notes, authored plans, manual events, skip-only confirmations and upstream accounts are unchanged. Unresolved runtime ownership records remain for stop verification; deletion does not prove a runtime stopped. Retained encrypted backups remain.</p>
    <button type="button" onClick={() => void removeImports()} disabled={busy || !statuses.some(status => status.recordCount > 0)}>
      Remove local imports
    </button></>}
    {message && <p aria-live="polite">{message}</p>}
  </section>
}
