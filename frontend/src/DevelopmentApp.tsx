import { useEffect, useState } from 'react'
import { DevelopmentPanel } from './DevelopmentPanel'
import { developmentRead, developmentWrite } from './development-api'

/** Isolated entry point only. Visibility has no effect on server permissions or durable tasks. */
export function DevelopmentApp() {
  const [csrf, setCsrf] = useState<string | null>(null)
  const [checking, setChecking] = useState(true)
  const [visible, setVisible] = useState(false)
  const [password, setPassword] = useState('')
  const [error, setError] = useState('')
  useEffect(() => {
    const controller = new AbortController()
    void developmentRead<{ csrfToken: string }>('/api/session', controller.signal)
      .then(session => { if (!controller.signal.aborted) setCsrf(session.csrfToken) })
      .catch(() => {})
      .finally(() => { if (!controller.signal.aborted) setChecking(false) })
    return () => controller.abort()
  }, [])
  async function login() {
    setError('')
    try { const session = await developmentWrite<{ csrfToken: string }>('/api/login', { password }, ''); setCsrf(session.csrfToken) }
    catch { setError('Development sign-in unavailable.') }
    finally { setPassword('') }
  }
  async function logout() {
    try { await developmentWrite('/api/logout', {}, csrf ?? '') }
    catch { setError('Development sign-out unavailable. Close this page if needed.') }
    finally { setCsrf(null); setVisible(false) }
  }
  return <main>
    <h1>Synthetic connected-review development</h1>
    <p>Synthetic only · Unqualified models · No personal history or production inference.</p>
    {error && <p role="alert">{error}</p>}
    {checking ? <p>Checking development session…</p> : !csrf ? <form onSubmit={event => { event.preventDefault(); void login() }}>
      <label>Development password<input type="password" autoComplete="current-password" required value={password} onChange={event => setPassword(event.target.value)} /></label>
      <button type="submit">Sign in</button>
    </form> : <>
      <button type="button" onClick={() => void logout()}>Sign out</button>
      <label><input type="checkbox" checked={visible} onChange={event => setVisible(event.target.checked)} />Developer options</label>
      <p>This switch reveals diagnostics only. It grants no execution permission and submits no work.</p>
      {visible && <DevelopmentPanel csrfToken={csrf} />}
    </>}
  </main>
}
