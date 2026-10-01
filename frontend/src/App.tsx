import { useEffect, useState } from 'react'
import { SessionPanel } from './SessionPanel'

type Connection = 'checking' | 'available' | 'unavailable'

const connectionLabels: Record<Connection, string> = {
  checking: 'Checking backend…',
  available: 'Backend available',
  unavailable: 'Backend unavailable',
}

export function App() {
  const [connection, setConnection] = useState<Connection>('checking')

  useEffect(() => {
    const controller = new AbortController()
    async function checkConnection() {
      try {
        const response = await fetch('/healthz', { signal: controller.signal })
        if (!response.ok) throw new Error('Health check failed')
        const body: unknown = await response.json()
        const available = typeof body === 'object' && body !== null &&
          'status' in body && body.status === 'ok'
        if (!controller.signal.aborted) {
          setConnection(available ? 'available' : 'unavailable')
        }
      } catch {
        if (!controller.signal.aborted) setConnection('unavailable')
      }
    }
    void checkConnection()
    return () => controller.abort()
  }, [])

  return (
    <main>
      <header>
        <p className="eyebrow">Your private training & health journal</p>
        <h1>GTrainer</h1>
        <p className="intro">A clearer view of your activity, recovery, and upcoming events.</p>
      </header>
      <section className="panel" aria-labelledby="setup-heading">
        <h2 id="setup-heading">Foundation in progress</h2>
        <p>Private access, read-only import, and factual trend charts are available. Events and AI observations are still being built.</p>
        <p>No personal data is bundled with this app. Sign in to read and review your private imported history.</p>
        <p role="status" className={`connection ${connection}`}>
          <span aria-hidden="true" className="dot" />{connectionLabels[connection]}
        </p>
      </section>
      <SessionPanel />
      <p className="footnote">AI analysis is not configured. No data is sent to a model.</p>
    </main>
  )
}
