import { useCallback, useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { ContextIntakeControls } from './ContextIntakeControls'
import { object, type Intake } from './ContextIntakeClient'
import { parseSuggestionDraft, type SuggestionLoader, type SuggestionView } from './ContextSuggestionControls'

type Profile = { profile: 'guarded-live-intake-pilot-v1'; evidenceMode: 'live' | 'synthetic_fake'; boundedSessionSeconds: 1200 }
type Provenance = { kind: 'live_model_capture' | 'synthetic_fake_acquisition'; acquisitionId: string; receiptSha256: string; packetSha256: string; requestSha256: string; rawSha256: string; draftSha256: string }
const exactKeys = (value: Record<string, unknown>, keys: string[]) => { if (Object.keys(value).length !== keys.length || Object.keys(value).some(key => !keys.includes(key))) throw new Error('Invalid live pilot response') }
const digest = (value: unknown) => typeof value === 'string' && /^[a-f0-9]{64}$/.test(value)

export function parseLiveSuggestions(value: unknown, note: Intake, profile: Profile): { view: SuggestionView; provenance: Provenance | null } {
  const response = object(value)
  exactKeys(response, ['profile', 'intakeId', 'intakeRevision', 'status', 'provenance', 'draft'])
  if (response.profile !== profile.profile || response.intakeId !== note.intakeId || response.intakeRevision !== note.revision) throw new Error('Mismatched live pilot response')
  if (response.status === 'unavailable') {
    if (response.provenance !== null || response.draft !== null) throw new Error('Invalid unavailable response')
    return { view: { status: 'unavailable', draft: [], questions: [] }, provenance: null }
  }
  if (response.status !== 'available') throw new Error('Invalid live pilot status')
  const provenance = object(response.provenance)
  exactKeys(provenance, ['kind', 'acquisitionId', 'receiptSha256', 'packetSha256', 'requestSha256', 'rawSha256', 'draftSha256'])
  const expectedKind = profile.evidenceMode === 'live' ? 'live_model_capture' : 'synthetic_fake_acquisition'
  if (provenance.kind !== expectedKind || typeof provenance.acquisitionId !== 'string' || !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(provenance.acquisitionId) ||
      !['receiptSha256', 'packetSha256', 'requestSha256', 'rawSha256', 'draftSha256'].every(key => digest(provenance[key]))) throw new Error('Invalid acquisition provenance')
  const view = parseSuggestionDraft(response.draft, note)
  const record = provenance as unknown as Provenance
  view.provenanceLabel = `Acquisition provenance: ${record.kind}; acquisition ${record.acquisitionId}; receipt SHA-256 ${record.receiptSha256.slice(0, 12)}.`
  return { view, provenance: record }
}

function sessionToken(value: unknown): string {
  const response = object(value)
  if (response.authenticated !== true || typeof response.csrfToken !== 'string' || !response.csrfToken || response.intervalsConfigured !== false) throw new Error('Invalid pilot session')
  return response.csrfToken
}
function parseProfile(value: unknown): Profile {
  const result = object(value)
  exactKeys(result, ['profile', 'evidenceMode', 'boundedSessionSeconds'])
  if (result.profile !== 'guarded-live-intake-pilot-v1' || !['live', 'synthetic_fake'].includes(String(result.evidenceMode)) || result.boundedSessionSeconds !== 1200) throw new Error('Invalid live pilot profile')
  return result as unknown as Profile
}

export function LiveIntakePilot() {
  const [csrfToken, setCsrfToken] = useState<string | null>(null), [profile, setProfile] = useState<Profile | null>(null)
  const [loading, setLoading] = useState(true), [busy, setBusy] = useState(false), [password, setPassword] = useState(''), [error, setError] = useState('')
  useEffect(() => {
    const controller = new AbortController()
    void fetch('/api/session', { credentials: 'same-origin', cache: 'no-store', signal: controller.signal })
      .then(async response => { if (response.ok) { const token = sessionToken(await response.json()); if (!controller.signal.aborted) setCsrfToken(token) } })
      .catch(() => { if (!controller.signal.aborted) setError('Pilot session unavailable. Sign in to the bounded pilot only.') })
      .finally(() => { if (!controller.signal.aborted) setLoading(false) })
    return () => controller.abort()
  }, [])
  useEffect(() => {
    if (!csrfToken) return
    const controller = new AbortController()
    void fetch('/api/live-intake-pilot/profile', { credentials: 'same-origin', cache: 'no-store', signal: controller.signal })
      .then(async response => { if (!response.ok) throw new Error(); const parsed = parseProfile(await response.json()); if (!controller.signal.aborted) setProfile(parsed) })
      .catch(() => { if (!controller.signal.aborted) setError('Pilot profile unavailable. No suggestions were requested.') })
    return () => controller.abort()
  }, [csrfToken])
  async function login(event: FormEvent) {
    event.preventDefault(); if (busy) return
    setBusy(true); setError('')
    try {
      const response = await fetch('/api/login', { method: 'POST', credentials: 'same-origin', cache: 'no-store', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ password }) })
      if (!response.ok) throw new Error()
      setCsrfToken(sessionToken(await response.json())); setPassword('')
    } catch { setPassword(''); setError('Pilot authentication failed. No automatic retry was made.') }
    finally { setBusy(false) }
  }
  const loader: SuggestionLoader = useCallback(async (note, signal) => {
    const response = await fetch(`/api/live-intake-pilot/${note.intakeId}/suggestions?expectedRevision=${note.revision}`, { method: 'GET', credentials: 'same-origin', cache: 'no-store', signal })
    if (!response.ok) throw new Error('Pilot suggestions unavailable')
    const parsed = parseLiveSuggestions(await response.json(), note, profile!)
    return parsed.view
  }, [profile])
  return <main className="synthetic-pilot">
    <h1>Guarded live-intake pilot</h1>
    <p role="note" className="pilot-warning">{!profile ? 'Bounded synthetic intake pilot. Evidence mode is verified after sign-in.' : profile.evidenceMode === 'live' ? 'REAL MODEL CAPTURE acquired before this app session; inspection never runs a model.' : 'SYNTHETIC FAKE ACQUISITION; NO MODEL EXECUTION.'} Disposable store. No quality claim. No authority until explicit confirmation.</p>
    <p>Bounded 20-minute session. Use only seeded synthetic source. Do not enter personal data.</p>
    {loading ? <p role="status">Checking pilot session…</p> : csrfToken && profile ? <ContextIntakeControls csrfToken={csrfToken} onConfirmed={() => {}} loadSuggestions={loader} allowNewIntakes={false} intakeLabel={note => `${note.content.slice(0, 85)} · revision ${note.revision}`} /> :
      !csrfToken ? <form onSubmit={event => void login(event)}><label>Pilot password<input type="password" required maxLength={256} autoComplete="current-password" value={password} disabled={busy} onChange={event => setPassword(event.target.value)} /></label><button type="submit" disabled={busy}>Sign in to live-intake pilot</button></form> : <p role="status">Loading bounded pilot profile…</p>}
    {error && <p role="alert">{error}</p>}
  </main>
}
