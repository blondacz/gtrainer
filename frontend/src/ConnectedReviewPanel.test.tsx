import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { ConnectedReviewPanel } from './ConnectedReviewPanel'

const snapshotId = '00000000-0000-0000-0000-000000000010'
const contextId = '00000000-0000-0000-0000-000000000001'
const packet = {
  profile: 'connected-review-v1',
  evidence: { evidenceReportSha256: 'a'.repeat(64), selectedSport: 'run', facts: [{ id: 'fact-1', value: 42 }] },
  context: [{ contextId, revision: 1, category: 'symptom', sourceCategory: 'user_report',
    authorAttribution: 'athlete', observedOn: '2025-01-07', content: 'Synthetic reported fatigue',
    restrictionKind: null, restrictionValue: null, restrictionUnit: null }],
}
const inspection = {
  status: { snapshotId, packetDigest: 'b'.repeat(64), evidenceDigest: 'a'.repeat(64), context: [{ contextId, revision: 1 }],
    coverageFrom: '2025-01-01', coverageUntil: '2025-01-07', sport: 'run', provider: 'local:synthetic',
    model: 'synthetic-model', contractVersion: 'connected-review-v1', state: 'PUBLISHED', staleReason: null,
    createdUtc: '2025-01-07T00:00:00Z', publishedOutput: '{}' },
  providerSelection: { providerId: 'synthetic', modelId: 'synthetic-model', providerKind: 'local' },
  exactPrompt: { systemInstructions: 'synthetic system', userPacketJson: JSON.stringify(packet) },
  review: { profile: 'connected-review-v1', interpretations: [{ text: 'Possible relationship',
    sources: { evidenceIds: ['fact-1'], context: [{ contextId, revision: 1 }] },
    scope: { from: '2025-01-01', until: '2025-01-07', sport: 'run' }, uncertainty: 'moderate' }],
    questions: [{ text: 'Did the reported fatigue overlap?', sources: { evidenceIds: ['fact-1'], context: [{ contextId, revision: 1 }] },
      scope: { from: '2025-01-01', until: '2025-01-07', sport: 'run' }, uncertainty: 'unknown' }] },
  packetAvailable: true,
}

describe('connected review evidence display', () => {
  it('separates facts, attributed reports, interpretations, uncertainty, and questions', async () => {
    const fetchMock = vi.fn(async (path: string) => path === `/api/connected-reviews/${snapshotId}`
      ? { ok: true, status: 200, json: async () => inspection }
      : { ok: false, status: 404, json: async () => ({}) })
    vi.stubGlobal('fetch', fetchMock)
    render(<ConnectedReviewPanel />)
    fireEvent.change(screen.getByLabelText('Review snapshot ID'), { target: { value: snapshotId } })
    fireEvent.click(screen.getByRole('button', { name: 'Inspect connected review' }))
    expect(await screen.findByText('Possible relationship')).toBeInTheDocument()
    expect(screen.getByText(/Code-generated facts and provenance/)).toBeInTheDocument()
    expect(screen.getAllByText('User report').length).toBeGreaterThan(0)
    expect(screen.getAllByText(/2025-01-07/).length).toBeGreaterThan(0)
    expect(screen.getByText(/Uncertainty: moderate/)).toBeInTheDocument()
    expect(screen.getByText('Did the reported fatigue overlap?')).toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledWith(`/api/connected-reviews/${snapshotId}`, expect.objectContaining({ cache: 'no-store' }))
  })

  it('shows deleted or stale packet distinctly without hiding review status', async () => {
    const unavailable = { ...inspection, status: { ...inspection.status, state: 'STALE', staleReason: 'context_deleted' },
      exactPrompt: { systemInstructions: '', userPacketJson: '' }, review: null, packetAvailable: false }
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => unavailable }))
    render(<ConnectedReviewPanel />)
    fireEvent.change(screen.getByLabelText('Review snapshot ID'), { target: { value: snapshotId } })
    fireEvent.click(screen.getByRole('button', { name: 'Inspect connected review' }))
    expect(await screen.findByText('Snapshot status: STALE')).toBeInTheDocument()
    expect(screen.getByText(/context_deleted/)).toBeInTheDocument()
    expect(screen.getByText(/packet was removed or is no longer available/)).toBeInTheDocument()
  })

  it('explains a purged snapshot without hiding factual dashboard access', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 404 }))
    render(<ConnectedReviewPanel />)
    fireEvent.change(screen.getByLabelText('Review snapshot ID'), { target: { value: snapshotId } })
    fireEvent.click(screen.getByRole('button', { name: 'Inspect connected review' }))
    expect(await screen.findByRole('alert')).toHaveTextContent(/removed with deleted context/)
    expect(screen.getByText(/Factual dashboard data remains available/)).toBeInTheDocument()
  })

  it('lists both sides of an unresolved restriction conflict with attribution and date', async () => {
    const first = { ...packet.context[0], contextId, revision: 1, category: 'restriction',
      restrictionKind: 'blocked_sport', restrictionValue: 'run' }
    const second = { ...first, contextId: '00000000-0000-0000-0000-000000000002', revision: 2,
      sourceCategory: 'clinician_guidance', authorAttribution: 'as reported by athlete', content: 'Synthetic allowed run' }
    const retrieval = { mandatoryRestrictions: [first, second], optionalContexts: [], reviewBlocked: true,
      blockedReasons: ['restriction_conflict'], conflicts: [{ first: { contextId, revision: 1 },
        second: { contextId: second.contextId, revision: 2 }, reason: 'blocked_allowed_activity_conflict' }] }
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => retrieval })
    vi.stubGlobal('fetch', fetchMock)
    render(<ConnectedReviewPanel />)
    fireEvent.click(screen.getByRole('button', { name: 'Check restrictions' }))
    expect(await screen.findByText('blocked allowed activity conflict')).toBeInTheDocument()
    expect(screen.getAllByText(/Synthetic reported fatigue|Synthetic allowed run/).length).toBeGreaterThanOrEqual(2)
    expect(screen.getByText(/Synthetic allowed run/)).toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledWith(expect.stringMatching(/^\/api\/context-retrieval\?/), expect.objectContaining({ cache: 'no-store' }))
  })
})
