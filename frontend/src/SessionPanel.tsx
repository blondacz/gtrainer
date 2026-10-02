import { useEffect, useState } from 'react'
import { ImportPanel } from './ImportPanel'
import { TrendPanel } from './TrendPanel'
import { ReviewControls } from './ReviewControls'
import { OverviewTiles } from './OverviewTiles'
import { ManualEventPanel } from './ManualEventPanel'

type Session = { authenticated: true; csrfToken: string; intervalsConfigured: boolean }

function sessionFrom(value: unknown): Session | null {
  if (typeof value !== 'object' || value === null) return null
  if (!('authenticated' in value) || value.authenticated !== true ||
      !('csrfToken' in value) || typeof value.csrfToken !== 'string' ||
      !/^[A-Za-z0-9_-]{43}$/.test(value.csrfToken) ||
      !('intervalsConfigured' in value) || typeof value.intervalsConfigured !== 'boolean') return null
  return value as Session
}

export function SessionPanel({ onAuthenticatedChange }: { onAuthenticatedChange?: (authenticated: boolean) => void }) {
  const [session, setSession] = useState<Session | null>(null)
  const [password, setPassword] = useState('')
  const [checking, setChecking] = useState(true)
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState('')
  const [historyRevision, setHistoryRevision] = useState(0)
  const [section, setSection] = useState<'overview' | 'charts' | 'imports' | 'reviews' | 'events'>('overview')
  useEffect(() => { onAuthenticatedChange?.(session !== null) }, [session, onAuthenticatedChange])

  useEffect(() => {
    const controller = new AbortController()
    async function checkSession() {
      try {
        const response = await fetch('/api/session', { signal: controller.signal, cache: 'no-store' })
        const body: unknown = response.ok ? await response.json() : null
        if (!controller.signal.aborted) setSession(sessionFrom(body))
      } catch {
        // Never print responses, passwords, session tokens, or network payloads.
      } finally {
        if (!controller.signal.aborted) setChecking(false)
      }
    }
    void checkSession()
    return () => controller.abort()
  }, [])

  async function signIn(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const submitted = password
    setPassword('')
    setBusy(true)
    setMessage('')
    try {
      const response = await fetch('/api/login', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ password: submitted }), cache: 'no-store',
      })
      if (!response.ok) {
        setMessage(response.status === 503 ? 'Sign-in is not configured yet.' :
          'Sign-in failed. Check the password or retry later.')
        return
      }
      const body: unknown = await response.json()
      const signedIn = sessionFrom(body)
      if (!signedIn) throw new Error('Invalid session response')
      setSession(signedIn)
    } catch {
      setMessage('Sign-in unavailable. Check your connection and retry.')
    } finally {
      setBusy(false)
    }
  }

  async function signOut() {
    if (!session) return
    setBusy(true)
    setMessage('')
    try {
      const response = await fetch('/api/logout', {
        method: 'POST', headers: { 'X-CSRF-Token': session.csrfToken }, cache: 'no-store',
      })
      if (!response.ok && response.status !== 401) throw new Error('Logout failed')
      setSession(null)
    } catch {
      setMessage('Could not sign out. Retry while the connection is available.')
    } finally {
      setBusy(false)
    }
  }

  return <section className={session ? 'signed-dashboard' : 'panel'} aria-labelledby="access-heading">
    {!session && <h2 id="access-heading">Private access</h2>}
    {checking ? <p>Checking sign-in…</p> : session ? <>
      <div className="session-strip"><div><h2 id="access-heading" className="visually-hidden">Private dashboard</h2>
      <p>Signed in to your private dashboard.</p>
      <p className="source-credential-status">{session.intervalsConfigured ? 'Intervals.icu credential is configured.' :
        'Intervals.icu credential is not configured.'}</p>
      </div><button type="button" className="secondary" onClick={() => void signOut()} disabled={busy}>Sign out</button></div>
      <nav className="dashboard-tabs section-tabs" role="tablist" aria-label="Dashboard sections">
        {(['overview', 'charts', 'imports', 'reviews', 'events'] as const).map(name => <button type="button" role="tab" key={name}
          id={`dashboard-tab-${name}`} aria-selected={section === name} aria-controls="dashboard-section" tabIndex={section === name ? 0 : -1} onClick={() => setSection(name)}
          onKeyDown={event => {
            const names = ['overview', 'charts', 'imports', 'reviews', 'events'] as const
            const index = names.indexOf(name)
            const next = event.key === 'ArrowRight' ? (index + 1) % names.length : event.key === 'ArrowLeft' ? (index + names.length - 1) % names.length :
              event.key === 'Home' ? 0 : event.key === 'End' ? names.length - 1 : null
            if (next !== null) { event.preventDefault(); setSection(names[next]); document.getElementById(`dashboard-tab-${names[next]}`)?.focus() }
          }}>
          {{ overview: 'Overview', charts: 'Trends', imports: 'Source', reviews: 'Reviews', events: 'Events' }[name]}</button>)}
      </nav>
      <div id="dashboard-section" className="dashboard-content" role="tabpanel" aria-labelledby={`dashboard-tab-${section}`}>
        {section === 'overview' && <OverviewTiles revision={historyRevision} onNavigate={setSection} />}
        {section === 'imports' && <ImportPanel compact csrfToken={session.csrfToken} configured={session.intervalsConfigured} onChanged={() => setHistoryRevision(value => value + 1)} />}
        {section === 'charts' && <TrendPanel compact revision={historyRevision} csrfToken={session.csrfToken} />}
        {section === 'reviews' && <ReviewControls revision={historyRevision} csrfToken={session.csrfToken} />}
        {section === 'events' && <ManualEventPanel csrfToken={session.csrfToken} onChanged={() => setHistoryRevision(value => value + 1)} />}
      </div>
    </> : <form onSubmit={event => void signIn(event)}>
      <p>Sign in before accessing private records. Your password is never stored in browser storage.</p>
      <input type="hidden" name="username" autoComplete="username" value="owner" readOnly />
      <label htmlFor="gtrainer-password">Dashboard password</label>
      <input id="gtrainer-password" name="password" type="password" autoComplete="current-password"
        maxLength={256} value={password} onChange={event => setPassword(event.target.value)} required disabled={busy} />
      <button type="submit" disabled={busy}>{busy ? 'Signing in…' : 'Sign in'}</button>
    </form>}
    {message && <p className="access-message" aria-live="polite">{message}</p>}
  </section>
}
