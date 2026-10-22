import { useEffect, useState, type Dispatch, type SetStateAction } from 'react'
import {
  arrayFrom,
  commitmentFrom,
  intakeFrom,
  intakeRequest,
  object,
  viewFrom,
  PrivateIntakeError,
  type Commitment,
  type CommitmentDefinition,
  type ContextView,
  type Intake,
} from './ContextIntakeClient'
import { ContextFields, validateDraft, type ContextDraft } from './ContextFields'
import { CommitmentFields } from './CommitmentFields'

type Link = {
  contextId: string
  contextRevision: number
  intakeId: string
  intakeRevision: number
}
type Preview = {
  contextId: string
  expectedRevision: number
  digest: string
  sources: Intake[]
  survivors: ContextView[]
  sourceLinks: Link[]
  commitments: DeletionCommitment[]
  deletedContextIds: string[]
  snapshotIds: string[]
  executionIds: string[]
  proposalIds: string[]
  retainedApprovalOperationIds: string[]
  receiptCount: number
  limitation: string
}
type SourceDraft = { keep: boolean; content: string }
type SurvivorDraft = {
  approved: boolean
  intakeId: string | null
  context: ContextDraft
  lifecycle: ContextView['lifecycle']
}
type CalendarDraft = {
  remove: boolean
  definition: CommitmentDefinition
  contextId: string | null
}
type DeletionCommitment = Commitment & { eventId: string | null }

const uuid = (value: unknown): value is string =>
  typeof value === 'string' &&
  /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(value)

const revision = (value: unknown) =>
  Number.isInteger(value) && Number(value) >= 1 && Number(value) <= 1000

const validPreview = (value: unknown, expectedId: string): Preview => {
  const preview = object(value)
  if (
    preview.contextId !== expectedId ||
    !revision(preview.expectedRevision) ||
    typeof preview.digest !== 'string' ||
    !/^[0-9a-f]{64}$/.test(preview.digest) ||
    typeof preview.receiptCount !== 'number' ||
    !Number.isInteger(preview.receiptCount) ||
    preview.receiptCount < 0 ||
    preview.receiptCount > 50000 ||
    typeof preview.limitation !== 'string' ||
    preview.limitation.length > 1000
  ) {
    throw new Error('Invalid preview')
  }

  const sources = arrayFrom(preview.sources, intakeFrom)
  const survivors = arrayFrom(preview.survivors, viewFrom)
  const commitments = arrayFrom(preview.commitments, value => {
    const row = object(value)
    if (!(row.eventId === null || uuid(row.eventId))) throw new Error('Invalid event id')
    return { ...commitmentFrom(value), eventId: row.eventId } as DeletionCommitment
  })
  const links = arrayFrom(preview.sourceLinks, item => {
    const link = object(item)
    if (
      !uuid(link.contextId) ||
      !revision(link.contextRevision) ||
      !uuid(link.intakeId) ||
      !revision(link.intakeRevision)
    ) {
      throw new Error('Invalid link')
    }
    return {
      contextId: link.contextId as string,
      contextRevision: link.contextRevision as number,
      intakeId: link.intakeId as string,
      intakeRevision: link.intakeRevision as number,
    }
  })
  const ids = (input: unknown) =>
    arrayFrom(input, item => {
      if (!uuid(item)) throw new Error('Invalid id')
      return item
    })
  const deletedContextIds = ids(preview.deletedContextIds)
  const snapshotIds = ids(preview.snapshotIds)
  const executionIds = ids(preview.executionIds)
  const proposalIds = ids(preview.proposalIds)
  const retainedApprovalOperationIds = ids(preview.retainedApprovalOperationIds)
  const unique = (values: string[]) => new Set(values).size === values.length

  if (
    !deletedContextIds.includes(preview.contextId as string) ||
    survivors.some(survivor => deletedContextIds.includes(survivor.context.contextId)) ||
    !unique(sources.map(source => source.intakeId)) ||
    !unique(survivors.map(survivor => survivor.context.contextId)) ||
    !unique(commitments.map(commitment => commitment.reference.commitmentId)) ||
    !unique(deletedContextIds) ||
    !unique(snapshotIds) ||
    !unique(executionIds) ||
    !unique(proposalIds) ||
    !unique(retainedApprovalOperationIds) ||
    links.some(
      link =>
        !sources.some(
          source => source.intakeId === link.intakeId && source.revision >= link.intakeRevision,
        ) ||
        !survivors.some(
          survivor =>
            survivor.context.contextId === link.contextId &&
            survivor.context.revision === link.contextRevision,
        ),
    ) ||
    survivors.some(
      survivor =>
        links.filter(
          link =>
            link.contextId === survivor.context.contextId &&
            link.contextRevision === survivor.context.revision,
        ).length > 1,
    )
  ) {
    throw new Error('Invalid preview graph')
  }

  return {
    contextId: preview.contextId,
    expectedRevision: preview.expectedRevision as number,
    digest: preview.digest,
    sources,
    survivors,
    sourceLinks: links,
    commitments,
    deletedContextIds,
    snapshotIds,
    executionIds,
    proposalIds,
    retainedApprovalOperationIds,
    receiptCount: preview.receiptCount,
    limitation: preview.limitation,
  }
}

export function ContextDeletionControls({
  contextId,
  csrfToken,
  onDeleted,
  onCancel,
}: {
  contextId: string
  csrfToken: string
  onDeleted: () => void
  onCancel: () => void
}) {
  const [preview, setPreview] = useState<Preview | null>(null)
  const [source, setSource] = useState<Record<string, SourceDraft>>({})
  const [survivor, setSurvivor] = useState<Record<string, SurvivorDraft>>({})
  const [calendar, setCalendar] = useState<Record<string, CalendarDraft>>({})
  const [error, setError] = useState('')
  const [message, setMessage] = useState('')
  const [busy, setBusy] = useState(false)
  const [uncertain, setUncertain] = useState(false)
  const [attest, setAttest] = useState(false)

  useEffect(() => {
    const abort = new AbortController()
    void intakeRequest(
      `/api/contexts/${encodeURIComponent(contextId)}/deletion-preview`,
      csrfToken,
      'GET',
      undefined,
      abort.signal,
    )
      .then(value => validPreview(value, contextId))
      .then(loadedPreview => {
        if (abort.signal.aborted) return
        setPreview(loadedPreview)
        setSource(
          Object.fromEntries(
            loadedPreview.sources.map(item => [item.intakeId, { keep: true, content: item.content }]),
          ),
        )
        setSurvivor(
          Object.fromEntries(
            loadedPreview.survivors.map(item => {
              const {
                contextId: _id,
                revision: _rev,
                enteredBy: _by,
                retired: _retired,
                ...draft
              } = item.context
              const origin = loadedPreview.sourceLinks.find(
                link =>
                  link.contextId === item.context.contextId &&
                  link.contextRevision === item.context.revision,
              )
              return [item.context.contextId, {
                approved: false,
                intakeId: origin?.intakeId ?? null,
                context: draft,
                lifecycle: item.lifecycle,
              }]
            }),
          ),
        )
        setCalendar(
          Object.fromEntries(
            loadedPreview.commitments.map(commitment => [
              commitment.reference.commitmentId,
              {
                remove: false,
                definition: commitment.definition,
                contextId: commitment.reference.contextId,
              },
            ]),
          ),
        )
      })
      .catch(() => {
        if (!abort.signal.aborted) {
          setError('Deletion preview unavailable or malformed. Cancel and reload before continuing.')
        }
      })
    return () => abort.abort()
  }, [contextId, csrfToken])

  function changed<T>(
    setter: Dispatch<SetStateAction<Record<string, T>>>,
    key: string,
    patch: Partial<T>,
  ) {
    setter(current => ({ ...current, [key]: { ...current[key], ...patch } }))
    setAttest(false)
  }

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!preview || busy || uncertain) return
    if (preview.survivors.some(item => !survivor[item.context.contextId]?.approved)) {
      setError('Review and approve every surviving context.')
      return
    }
    if (!attest) {
      setError('Explicitly attest to the redaction and deletion notice.')
      return
    }
    for (const item of preview.survivors) {
      const invalid = validateDraft(survivor[item.context.contextId].context)
      if (invalid) {
        setError(invalid)
        return
      }
    }

    const sources = preview.sources
      .filter(item => source[item.intakeId].keep)
      .map(item => ({
        intakeId: item.intakeId,
        expectedRevision: item.revision,
        note: {
          content: source[item.intakeId].content,
          sourceCategory: item.sourceCategory,
          authorAttribution: item.authorAttribution,
          observedOn: item.observedOn,
        },
      }))
    const survivors = preview.survivors.map(item => {
      const draft = survivor[item.context.contextId]
      const origin = preview.sourceLinks.find(
        link =>
          link.contextId === item.context.contextId &&
          link.contextRevision === item.context.revision,
      )
      const attributed = origin
        ? preview.sources.find(sourceItem => sourceItem.intakeId === origin.intakeId)
        : null
      const context = {
        ...draft.context,
        sourceCategory: attributed?.sourceCategory ?? item.context.sourceCategory,
        authorAttribution: attributed?.authorAttribution ?? item.context.authorAttribution,
      }
      return {
        intakeId: draft.intakeId,
        item: {
          context,
          lifecycle: draft.lifecycle,
          contextId: item.context.contextId,
          expectedContextRevision: item.context.revision,
        },
      }
    })
    const commitments = preview.commitments.map(commitment => {
      const draft = calendar[commitment.reference.commitmentId]
      const origin = preview.sources.find(
        item => item.intakeId === commitment.reference.intakeId,
      )
      const linked =
        draft.contextId &&
        preview.survivors.find(item => item.context.contextId === draft.contextId)
      return {
        commitmentId: commitment.reference.commitmentId,
        expectedRevision: commitment.reference.revision,
        confirmation: draft.remove
          ? null
          : {
              intakeId: commitment.reference.intakeId,
              expectedIntakeRevision: origin ? origin.revision + 1 : -1,
              definition: draft.definition,
              contextId: draft.contextId,
              expectedContextRevision: draft.contextId
                ? linked
                  ? linked.context.revision + 1
                  : null
                : null,
              eventId: commitment.eventId,
            },
      }
    })
    if (
      commitments.some(
        item =>
          item.confirmation &&
          (!revision(item.confirmation.expectedIntakeRevision) ||
            (item.confirmation.contextId !== null &&
              item.confirmation.expectedContextRevision === null)),
      )
    ) {
      setError('Choose a surviving context or explicitly detach each retained projection.')
      return
    }
    if (
      preview.sources.some(
        item =>
          !source[item.intakeId].keep &&
          (survivors.some(survivorItem => survivorItem.intakeId === item.intakeId) ||
            commitments.some(
              commitment => commitment.confirmation?.intakeId === item.intakeId,
            )),
      )
    ) {
      setError('A source used by a survivor or retained calendar item cannot be omitted.')
      return
    }
    const body = { expectedRevision: preview.expectedRevision, previewDigest: preview.digest, sources, survivors, commitments }
    if (new TextEncoder().encode(JSON.stringify(body)).length > 32768) {
      setError('Request exceeds 32 KiB. No request was sent; reduce edits.')
      return
    }

    setBusy(true)
    setError('')
    setMessage('')
    try {
      const result = object(
        await intakeRequest(
          `/api/contexts/${encodeURIComponent(contextId)}/redact-and-delete`,
          csrfToken,
          'POST',
          body,
        ),
      )
      const deleted = arrayFrom(result.deletedContextIds, value => {
        if (!uuid(value)) throw new Error()
        return value
      })
      const kept = arrayFrom(result.survivingContextIds, value => {
        if (!uuid(value)) throw new Error()
        return value
      })
      const keptC = arrayFrom(result.retainedCommitmentIds, value => {
        if (!uuid(value)) throw new Error()
        return value
      })
      const removedC = arrayFrom(result.deletedCommitmentIds, value => {
        if (!uuid(value)) throw new Error()
        return value
      })
      const expectedKept = preview.survivors.map(item => item.context.contextId).sort()
      const expectedKc = commitments
        .filter(item => item.confirmation)
        .map(item => item.commitmentId)
        .sort()
      const expectedDc = commitments
        .filter(item => !item.confirmation)
        .map(item => item.commitmentId)
        .sort()
      if (
        typeof result.notice !== 'string' ||
        !result.notice.includes('backups') ||
        !result.notice.includes('forensic') ||
        JSON.stringify(deleted.slice().sort()) !==
          JSON.stringify(preview.deletedContextIds.slice().sort()) ||
        JSON.stringify(kept.slice().sort()) !== JSON.stringify(expectedKept) ||
        JSON.stringify(keptC.slice().sort()) !== JSON.stringify(expectedKc) ||
        JSON.stringify(removedC.slice().sort()) !== JSON.stringify(expectedDc)
      ) {
        throw new Error()
      }
      onDeleted()
    } catch (cause) {
      if (cause instanceof PrivateIntakeError) {
        setError(cause.message)
      } else {
        setUncertain(true)
        setError('Outcome unknown or response invalid. Do not retry; cancel and reload parent data.')
      }
    } finally {
      setBusy(false)
    }
  }

  return (
    <section className="context-intake" aria-label="Context deletion and redaction">
      <h3>Review deletion and redaction</h3>
      <p>
        Correction is not privacy deletion: prior history may remain in backups under normal
        retention. No forensic-erasure guarantee; prior provider transfers cannot be recalled.
        Imported activities and independent events are unaffected. This follows declared
        dependencies, not semantic discovery of independently copied phrases. No automatic
        inference or provider calls.
      </p>
      <form onSubmit={event => void submit(event)}>
        {preview && (
          <>
            <p>
              Target and feedback context IDs slated for deletion: {preview.deletedContextIds.join(', ')}.
              {' '}Snapshots: {preview.snapshotIds.length}; execution prompts/results: {preview.executionIds.length}; amendment proposals: {preview.proposalIds.length}; receipts: {preview.receiptCount}.
              {' '}Retained content-free approval audit IDs: {preview.retainedApprovalOperationIds.length}. No source/context text or sensitive hashes are retained in this audit. Limitation:{' '}
              {preview.limitation}
            </p>
            <h4>Original sources</h4>
            {preview.sources.map(item => {
              const used =
                preview.survivors.some(
                  survivorItem =>
                    survivor[survivorItem.context.contextId]?.intakeId === item.intakeId,
                ) ||
                preview.commitments.some(
                  commitment =>
                    !calendar[commitment.reference.commitmentId]?.remove &&
                    commitment.reference.intakeId === item.intakeId,
                )
              return (
                <div key={item.intakeId}>
                  <p>
                    {item.authorAttribution} · {item.sourceCategory} · observed {item.observedOn} ·
                    {' '}revision {item.revision}
                  </p>
                  <label>
                    Approved replacement wording
                    <textarea
                      required={source[item.intakeId]?.keep ?? true}
                      maxLength={4000}
                      rows={3}
                      disabled={busy}
                      value={source[item.intakeId]?.content ?? ''}
                      onChange={event => {
                        changed(setSource, item.intakeId, { content: event.target.value })
                        setAttest(false)
                        setSurvivor(current =>
                          Object.fromEntries(
                            Object.entries(current).map(([id, draft]) => [
                              id,
                              { ...draft, approved: false },
                            ]),
                          ),
                        )
                      }}
                    />
                  </label>
                  <label>
                    <input
                      type="checkbox"
                      disabled={busy || used}
                      checked={source[item.intakeId]?.keep ?? true}
                      onChange={event => {
                        changed(setSource, item.intakeId, { keep: event.target.checked })
                        setAttest(false)
                      }}
                    />
                    Retain redacted source{used ? ' (required by a surviving item)' : ''}
                  </label>
                </div>
              )
            })}

            <h4>Surviving contexts</h4>
            {preview.survivors.map(item => {
              const draft = survivor[item.context.contextId]
              const origin = preview.sourceLinks.find(
                link =>
                  link.contextId === item.context.contextId &&
                  link.contextRevision === item.context.revision,
              )
              const attribution = origin
                ? preview.sources.find(sourceItem => sourceItem.intakeId === origin.intakeId)
                : null
              return (
                <div key={item.context.contextId}>
                  <ContextFields
                    value={draft.context}
                    disabled={busy}
                    attribution={false}
                    onChange={context => {
                      changed(setSurvivor, item.context.contextId, { context, approved: false })
                      setAttest(false)
                    }}
                  />
                  <p>
                    Attribution remains {attribution?.authorAttribution ?? item.context.authorAttribution}{' '}
                    / {attribution?.sourceCategory ?? item.context.sourceCategory}. Lifecycle remains{' '}
                    {item.lifecycle.resolution}; review due {item.lifecycle.reviewDueOn ?? 'not set'}
                    {' '}(read-only).{' '}
                    {origin
                      ? `Current origin ${origin.intakeId} at source revision ${attribution?.revision}.`
                      : 'Standalone origin.'}
                  </p>
                  <label>
                    <input
                      type="checkbox"
                      checked={draft.approved}
                      disabled={busy}
                      onChange={event => {
                        changed(setSurvivor, item.context.contextId, {
                          approved: event.target.checked,
                        })
                        setAttest(false)
                      }}
                    />
                    I reviewed this surviving context and approve the displayed replacement.
                  </label>
                </div>
              )
            })}

            <h4>Calendar projections</h4>
            {preview.commitments.map(commitment => {
              const draft = calendar[commitment.reference.commitmentId]
              return (
                <div key={commitment.reference.commitmentId}>
                  <label>
                    <input
                      type="checkbox"
                      disabled={busy}
                      checked={draft.remove}
                      onChange={event =>
                        changed(setCalendar, commitment.reference.commitmentId, {
                          remove: event.target.checked,
                        })
                      }
                    />
                    Delete this projection
                  </label>
                  {!draft.remove && (
                    <>
                      <CommitmentFields
                        value={draft.definition}
                        disabled={busy}
                        onChange={definition =>
                          changed(setCalendar, commitment.reference.commitmentId, { definition })
                        }
                      />
                      <label>
                        Associate with surviving context
                        <select
                          value={draft.contextId ?? ''}
                          disabled={busy}
                          onChange={event =>
                            changed(setCalendar, commitment.reference.commitmentId, {
                              contextId: event.target.value || null,
                            })
                          }
                        >
                          <option value="">NONE — detach explicitly</option>
                          {commitment.reference.contextId &&
                            !preview.survivors.some(
                              item => item.context.contextId === commitment.reference.contextId,
                            ) && (
                              <option disabled value={commitment.reference.contextId}>
                                Deleted original context — choose another or NONE
                              </option>
                            )}
                          {preview.survivors.map(item => (
                            <option key={item.context.contextId} value={item.context.contextId}>
                              {item.context.category} · {item.context.contextId}
                            </option>
                          ))}
                        </select>
                      </label>
                    </>
                  )}
                </div>
              )
            })}
            <label>
              <input
                type="checkbox"
                checked={attest}
                disabled={busy}
                onChange={event => setAttest(event.target.checked)}
              />
              I explicitly approve redacting the displayed sources and deleting the listed context
              and dependent projections.
            </label>
            <button type="submit" disabled={busy || uncertain}>
              Approve redaction and delete
            </button>
          </>
        )}
      </form>
      {error && <p role="alert">{error}</p>}
      {message && <p role="status">{message}</p>}
      <button type="button" disabled={busy} onClick={onCancel}>
        Cancel / reload parent
      </button>
    </section>
  )
}
