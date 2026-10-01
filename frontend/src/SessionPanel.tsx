import { useEffect, useState } from 'react'
import { ImportPanel } from './ImportPanel'

type Session = { authenticated: true; csrfToken: string; intervalsConfigured: boolean }

function sessionFrom(value: unknown): Session | null {
  if (typeof value !== 'object' || value === null) return null
  if (!('authenticated' in value) || value.authenticated !== true ||
      !('csrfToken' in value) || typeof value.csrfToken !== 'string' ||
      !/^[A-Za-z0-9_-]{43}$/.test(value.csrfToken) ||
      !('intervalsConfigured' in value) || typeof value.intervalsConfigured !== 'boolean') return null
  return value as Session
}

export function SessionPanel() {
  const [session, setSession] = useState<Session | null>(null)
  const [password, setPassword] = useState('')
  const [checking, setChecking] = useState(true)
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState('')

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

  return <section className="panel" aria-labelledby="access-heading">
    <h2 id="access-heading">Private access</h2>
    {checking ? <p>Checking sign-in…</p> : session ? <>
      <p>Signed in to your private dashboard.</p>
      <p>{session.intervalsConfigured ? 'Intervals.icu credential is configured.' :
        'Intervals.icu credential is not configured.'}</p>
      <button type="button" onClick={() => void signOut()} disabled={busy}>Sign out</button>
      <ImportPanel csrfToken={session.csrfToken} configured={session.intervalsConfigured} />
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
