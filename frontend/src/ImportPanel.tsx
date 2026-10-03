import { useEffect, useState } from 'react'
import { TextPages } from './TilePrimitives'

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

export function ImportPanel({ csrfToken, configured, onChanged, compact = false }: { csrfToken: string; configured: boolean; onChanged?: () => void; compact?: boolean }) {
  const today = new Date().toISOString().slice(0, 10)
  const earlier = new Date(Date.now() - 90 * 86400 * 1000).toISOString().slice(0, 10)
  const [oldest, setOldest] = useState(earlier)
  const [newest, setNewest] = useState(today)
  const [statuses, setStatuses] = useState<CategoryStatus[]>([])
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState('')
  const [view, setView] = useState('read')
  const [category, setCategory] = useState('activities')

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
      onChanged?.()
      setMessage('Read finished. Review each category below; existing history is retained during failures.')
    } catch {
      setMessage('Import unavailable. Reconnect and check its status before retrying.')
    } finally {
      setBusy(false)
    }
  }

  async function removeImports() {
    if (!window.confirm('Remove locally imported history? Events and upstream accounts are unchanged. Retained encrypted backups are not erased.')) return
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
      setMessage('Local imports removed. Events, upstream accounts, and retained backups are unchanged.')
    } catch {
      setMessage('Could not verify removal. Reconnect or sign in again, then check the status.')
    } finally {
      setBusy(false)
    }
  }

  return <section className="panel" aria-labelledby="imports-heading">
    <h2 id="imports-heading">Garmin-derived history through Intervals.icu</h2>
    {compact && <nav className="section-tabs" aria-label="Source sections">{['read', 'status', 'remove', 'limits'].map(name =>
      <button key={name} aria-pressed={view === name} onClick={() => setView(name)}>{name}</button>)}</nav>}
    {!compact && <p>Read only. No Garmin or Intervals.icu records are changed. Imported history stays on the Pi;
      backups go encrypted to your configured Mac. The read itself makes no model call; separately enabled review schedules may queue guarded analysis.</p>}
    {(!compact || view === 'read') && <form className="compact-form" onSubmit={event => void sync(event)}>
      <label htmlFor="import-oldest">First date</label>
      <input id="import-oldest" type="date" value={oldest} onChange={event => setOldest(event.target.value)} required disabled={busy} />
      <label htmlFor="import-newest">Last date</label>
      <input id="import-newest" type="date" value={newest} max={today} onChange={event => setNewest(event.target.value)} required disabled={busy} />
      <button type="submit" disabled={busy || !configured}>{busy ? 'Working…' : 'Read Intervals.icu'}</button>
    </form>}
    {!configured && <p>Configure the source key to import. Existing private records can still be retained.</p>}
    {compact && view === 'status' && <div className="dashboard-tile source"><label htmlFor="import-category">Imported category</label>
      <select id="import-category" value={category} onChange={event => setCategory(event.target.value)}><option value="activities">Activities</option><option value="wellness">Wellness</option></select>
      {(() => { const status = statuses.find(item => item.category === category); return status ? <>
        <p className="tile-value">{status.recordCount}<span className="tile-unit">stored records</span></p>
        <TextPages key={category} label="Read status" text={`${readLabels[status.readStatus] ?? 'Status unavailable'}. Last attempt UTC: ${status.lastAttemptUtc ?? 'none'}; last successful read UTC: ${status.lastSuccessUtc ?? 'none'}. Latest stored date ${status.latestObservedDate ?? 'unavailable'}; ${status.rejected} rejected / ${status.incomplete} incomplete. Garmin-to-Intervals.icu freshness is unverified. Missing imports do not prove inactivity or illness.`} /></> : <p>Read status unavailable.</p> })()}
    </div>}
    {compact && view === 'limits' && <TextPages label="Source limits" text="Reads only from Intervals.icu, an intermediary with its own privacy risks. No Garmin or Intervals.icu records are changed. Missing recent values do not establish an upstream failure. Imported history stays on the Pi, backups are encrypted to your configured Mac. The source read itself makes no model call; separately selected, explicitly enabled review schedules can queue guarded local analysis. No hosted fallback. Recorded activity time and Intervals.icu load are not proprietary Garmin scores." />}
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
    {(!compact || view === 'remove') && <><p>Removal deletes imported records and review snapshots, not manual events or upstream accounts. Retained encrypted backups remain.</p>
    <button type="button" onClick={() => void removeImports()} disabled={busy || !statuses.some(status => status.recordCount > 0)}>
      Remove local imports
    </button></>}
    {message && <p aria-live="polite">{message}</p>}
  </section>
}
