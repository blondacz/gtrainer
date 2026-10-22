import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { ContextIntakeControls } from './ContextIntakeControls'
import { object, type Intake } from './ContextIntakeClient'
import { parseSuggestionDraft, type SuggestionLoader, type SuggestionView } from './ContextSuggestionControls'

const exactKeys = (value: Record<string, unknown>, keys: string[]) => {
  if (Object.keys(value).length !== keys.length || Object.keys(value).some(key => !keys.includes(key))) throw new Error('Invalid synthetic fixture response')
}
export function parseSyntheticSuggestions(value: unknown, note: Intake): SuggestionView {
  const response = object(value)
  exactKeys(response, ['profile', 'intakeId', 'intakeRevision', 'status', 'provenance', 'draft'])
  if (response.profile !== 'synthetic-intake-pilot-v1' || response.intakeId !== note.intakeId || response.intakeRevision !== note.revision) throw new Error('Mismatched synthetic fixture')
  if (response.status === 'unavailable') {
    if (response.provenance !== null || response.draft !== null) throw new Error('Invalid unavailable fixture')
    return { status: 'unavailable', draft: [], questions: [] }
  }
  if (response.status !== 'available') throw new Error('Invalid synthetic fixture status')
  const provenance = object(response.provenance)
  exactKeys(provenance, ['kind', 'caseId', 'suiteManifestSha256', 'packetSha256', 'wireSha256'])
  if (provenance.kind !== 'handwritten_frozen_witness' || typeof provenance.caseId !== 'string' || !/^u0[1-8]$/.test(provenance.caseId)) throw new Error('Invalid fixture provenance')
  for (const key of ['suiteManifestSha256', 'packetSha256', 'wireSha256']) {
    if (typeof provenance[key] !== 'string' || !/^[a-f0-9]{64}$/.test(provenance[key])) throw new Error('Invalid fixture hash')
  }
  return parseSuggestionDraft(response.draft, note)
}

export const loadSyntheticSuggestions: SuggestionLoader = async (note, signal) => {
  const response = await fetch(`/api/synthetic-intake-pilot/${note.intakeId}/suggestions?expectedRevision=${note.revision}`, {
    method: 'GET', credentials: 'same-origin', cache: 'no-store', signal,
  })
  if (!response.ok) throw new Error('Synthetic fixture unavailable')
  return parseSyntheticSuggestions(await response.json() as unknown, note)
}

function sessionToken(value: unknown): string {
  const response = object(value)
  if (response.authenticated !== true || typeof response.csrfToken !== 'string' || !response.csrfToken || response.intervalsConfigured !== false) throw new Error('Invalid pilot session')
  return response.csrfToken
}

export function SyntheticIntakePilot() {
  const [csrfToken, setCsrfToken] = useState<string | null>(null)
  const [loading, setLoading] = useState(true), [busy, setBusy] = useState(false)
  const [password, setPassword] = useState(''), [error, setError] = useState('')
  useEffect(() => {
    const controller = new AbortController()
    void fetch('/api/session', { credentials: 'same-origin', cache: 'no-store', signal: controller.signal })
      .then(async response => { if (response.ok) { const token = sessionToken(await response.json()); if (!controller.signal.aborted) setCsrfToken(token) } })
      .catch(() => { if (!controller.signal.aborted) setError('Pilot session unavailable. Sign in to the synthetic harness only.') })
      .finally(() => { if (!controller.signal.aborted) setLoading(false) })
    return () => controller.abort()
  }, [])
  async function login(event: FormEvent) {
    event.preventDefault()
    if (busy) return
    setBusy(true); setError('')
    try {
      const response = await fetch('/api/login', { method: 'POST', credentials: 'same-origin', cache: 'no-store',
        headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ password }) })
      if (!response.ok) throw new Error('Rejected')
      setCsrfToken(sessionToken(await response.json())); setPassword('')
    } catch { setPassword(''); setError('Pilot authentication failed. No automatic retry was made.') }
    finally { setBusy(false) }
  }
  return <main className="synthetic-pilot">
    <h1>Guarded synthetic intake pilot</h1>
    <p role="note" className="pilot-warning">Synthetic fixture replay. No model execution. Disposable storage. No extraction-quality claim.</p>
    <p>Use only bundled synthetic notes. Nothing is authoritative until you explicitly confirm visible selected items. Do not enter personal data.</p>
    {loading ? <p role="status">Checking pilot session…</p> : csrfToken ?
      <ContextIntakeControls csrfToken={csrfToken} onConfirmed={() => {}} loadSuggestions={loadSyntheticSuggestions}
        allowNewIntakes={false} intakeLabel={note => `${note.content.slice(0, 85)} · revision ${note.revision}`} /> :
      <form onSubmit={event => void login(event)}>
        <label>Pilot password<input type="password" required maxLength={256} autoComplete="current-password" value={password} disabled={busy} onChange={event => setPassword(event.target.value)} /></label>
        <button type="submit" disabled={busy}>Sign in to synthetic pilot</button>
      </form>}
    {error && <p role="alert">{error}</p>}
  </main>
}
