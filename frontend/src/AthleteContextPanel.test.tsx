import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { AthleteContextPanel } from './AthleteContextPanel'

const csrf = 'a'.repeat(43)
const id = '00000000-0000-0000-0000-000000000001'
const current = {
  contextId: id, revision: 2, category: 'goal', sourceCategory: 'clinician_guidance',
  authorAttribution: 'clinician as reported by user', enteredBy: 'authenticated_user', observedOn: '2025-01-05',
  applicableFrom: '2025-01-01', applicableUntil: null, sport: 'Run', activityId: null, reviewId: null,
  content: 'Current synthetic goal', retired: false, restrictionKind: null, restrictionValue: null, restrictionUnit: null,
}
const prior = { ...current, revision: 1, content: 'Superseded synthetic goal' }

describe('athlete context lifecycle', () => {
  it('labels attribution and applicability, shows revision history, and saves corrections as revisions', async () => {
    let record = current
    const fetchMock = vi.fn(async (path: string, init?: RequestInit) => {
      if (path === '/api/contexts' && (!init || init.method === undefined))
        return { ok: true, status: 200, json: async () => [record] }
      if (path === `/api/contexts/${id}/history`)
        return { ok: true, status: 200, json: async () => [record, prior] }
      if (path === `/api/contexts/${id}` && init?.method === 'PUT') {
        expect(init.headers).toMatchObject({ 'X-CSRF-Token': csrf })
        const body = JSON.parse(String(init.body)) as Record<string, unknown>
        record = { ...record, revision: 3, content: String(body.content), authorAttribution: String(body.authorAttribution) }
        return { ok: true, status: 200, json: async () => record }
      }
      throw new Error('Unexpected synthetic request')
    })
    vi.stubGlobal('fetch', fetchMock)
    render(<AthleteContextPanel csrfToken={csrf} />)

    expect(await screen.findByText('Current synthetic goal')).toBeInTheDocument()
    expect(screen.getAllByText(/Clinician guidance, as entered by user/).length).toBeGreaterThan(0)
    expect(screen.getByText(/Applicable 2025-01-01 to no end date/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'View revision history' }))
    expect(await screen.findByText('Superseded synthetic goal')).toBeInTheDocument()
    expect(screen.getByText('Revision 1 (superseded)')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Correct' }))
    fireEvent.change(screen.getByLabelText('Context text'), { target: { value: 'Corrected synthetic goal' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save correction' }))
    expect(await screen.findByText('Correction saved as revision 3.')).toBeInTheDocument()
    await waitFor(() => expect(record.revision).toBe(3))
    expect(record.content).toBe('Corrected synthetic goal')
    expect(fetchMock).toHaveBeenCalledWith(`/api/contexts/${id}`, expect.objectContaining({ method: 'PUT' }))
  })

  it('creates a typed maximum-duration restriction with source and scope fields', async () => {
    let savedBody: Record<string, unknown> | null = null
    const fetchMock = vi.fn(async (path: string, init?: RequestInit) => {
      if (path === '/api/contexts' && (!init || init.method === undefined))
        return { ok: true, status: 200, json: async () => savedBody ? [{
          ...current, category: 'restriction', revision: 1, sourceCategory: 'coach_guidance',
          restrictionKind: savedBody.restrictionKind, restrictionValue: savedBody.restrictionValue,
          restrictionUnit: savedBody.restrictionUnit, content: savedBody.content,
        }] : [] }
      if (path === '/api/contexts' && init?.method === 'POST') {
        expect(init.headers).toMatchObject({ 'X-CSRF-Token': csrf })
        savedBody = JSON.parse(String(init.body)) as Record<string, unknown>
        return { ok: true, status: 201, json: async () => ({ ...current, contextId: id,
          category: 'restriction', revision: 1, sourceCategory: savedBody?.sourceCategory,
          restrictionKind: savedBody?.restrictionKind, restrictionValue: savedBody?.restrictionValue,
          restrictionUnit: savedBody?.restrictionUnit, content: savedBody?.content }) }
      }
      throw new Error('Unexpected synthetic request')
    })
    vi.stubGlobal('fetch', fetchMock)
    render(<AthleteContextPanel csrfToken={csrf} />)
    fireEvent.click(screen.getByText('Direct context entry / legacy corrections'))
    fireEvent.change(await screen.findByLabelText('Topic'), { target: { value: 'restriction' } })
    fireEvent.change(screen.getByLabelText('Source attribution'), { target: { value: 'coach_guidance' } })
    fireEvent.change(screen.getByLabelText('Restriction kind'), { target: { value: 'maximum_duration' } })
    fireEvent.change(screen.getByLabelText('Maximum duration (minutes)'), { target: { value: '30' } })
    fireEvent.change(screen.getByLabelText('Context text'), { target: { value: 'Synthetic limit' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save context' }))
    expect(await screen.findByText('Context saved.')).toBeInTheDocument()
    expect(savedBody).toMatchObject({ category: 'restriction', sourceCategory: 'coach_guidance',
      restrictionKind: 'maximum_duration', restrictionValue: '30', restrictionUnit: 'minutes', sport: null })
    expect(screen.getAllByText(/Coach guidance, as entered by user/).length).toBeGreaterThan(0)
  })

  it('requires explicit deletion confirmation and explains backup retention', async () => {
    let records = [current]
    const fetchMock = vi.fn(async (path: string, init?: RequestInit) => {
      if (path === '/api/contexts' && (!init || init.method === undefined))
        return { ok: true, status: 200, json: async () => records }
      if (path === `/api/contexts/${id}/deletion-preview` && init?.method === 'GET')
        return { ok: true, status: 200, json: async () => ({ contextId: id, expectedRevision: 2, digest: 'd'.repeat(64),
          sources: [], survivors: [], sourceLinks: [], commitments: [], deletedContextIds: [id], snapshotIds: [], executionIds: [], proposalIds: [], retainedApprovalOperationIds: [], receiptCount: 0, limitation: 'Synthetic limitation' }) }
      if (path === `/api/contexts/${id}/redact-and-delete` && init?.method === 'POST') {
        expect(init.headers).toMatchObject({ 'X-CSRF-Token': csrf })
        expect(JSON.parse(String(init.body))).toEqual({ expectedRevision: 2, previewDigest: 'd'.repeat(64), sources: [], survivors: [], commitments: [] })
        records = []
        return { ok: true, status: 200, json: async () => ({ deletedContextIds: [id], survivingContextIds: [], retainedCommitmentIds: [], deletedCommitmentIds: [], notice: 'active_dependency_histories_removed;encrypted_backups_follow_retention;not_forensic_erasure' }) }
      }
      throw new Error('Unexpected synthetic request')
    })
    vi.stubGlobal('fetch', fetchMock)
    render(<AthleteContextPanel csrfToken={csrf} />)
    await screen.findByText('Current synthetic goal')
    fireEvent.click(screen.getByRole('button', { name: 'Delete' }))
    expect(screen.getByText(/prior history may remain in backups under normal retention/)).toBeInTheDocument()
    expect(fetchMock.mock.calls.filter(([,init]) => init?.method==='POST')).toHaveLength(0)
    fireEvent.click(screen.getByRole('button', { name: 'Cancel / reload parent' }))
    expect(await screen.findByText('Current synthetic goal')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Delete' }))
    expect(await screen.findByRole('heading', { name: 'Review deletion and redaction' })).toBeInTheDocument()
    expect(screen.queryByText('Current synthetic goal')).not.toBeInTheDocument()
    expect(screen.queryByText('Superseded synthetic goal')).not.toBeInTheDocument()
    fireEvent.click(await screen.findByRole('checkbox', { name: /I explicitly approve redacting/ }))
    fireEvent.click(screen.getByRole('button', { name: 'Approve redaction and delete' }))
    expect(await screen.findByText('No current context entries.')).toBeInTheDocument()
    expect(screen.queryByText('Current synthetic goal')).not.toBeInTheDocument()
    expect(screen.queryByText('Superseded synthetic goal')).not.toBeInTheDocument()
  })

  it('records feedback against an immutable snapshot as attributed context, not measurement', async () => {
    const fetchMock = vi.fn(async (path: string, init?: RequestInit) => {
      if (path === '/api/contexts' && (!init || init.method === undefined))
        return { ok: true, status: 200, json: async () => [] }
      if (path === `/api/connected-reviews/${id}/feedback` && init?.method === 'POST') {
        expect(init.headers).toMatchObject({ 'X-CSRF-Token': csrf })
        expect(JSON.parse(String(init.body))).toEqual({ rating: 'not_useful', correction: 'Synthetic correction' })
        return { ok: true, status: 201, json: async () => ({ contextId: id, revision: 1,
          category: 'feedback', sourceCategory: 'review_feedback', authorAttribution: 'athlete',
          enteredBy: 'authenticated_user', observedOn: '2025-01-07', applicableFrom: null, applicableUntil: null,
          sport: null, activityId: null, reviewId: id, content: 'Synthetic correction', retired: false,
          restrictionKind: null, restrictionValue: null, restrictionUnit: null }) }
      }
      throw new Error('Unexpected synthetic request')
    })
    vi.stubGlobal('fetch', fetchMock)
    render(<AthleteContextPanel csrfToken={csrf} />)
    fireEvent.change(await screen.findByLabelText('Connected review snapshot ID'), { target: { value: id } })
    fireEvent.change(screen.getByLabelText('Usefulness rating'), { target: { value: 'not_useful' } })
    fireEvent.change(screen.getByLabelText('Correction or note (optional)'), { target: { value: 'Synthetic correction' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save review feedback' }))
    expect(await screen.findByText(/linked to this immutable review/)).toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledWith(`/api/connected-reviews/${id}/feedback`, expect.objectContaining({ method: 'POST' }))
  })
})
