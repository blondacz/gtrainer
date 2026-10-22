import { commitmentDefinitionFrom, type CommitmentDefinition } from './ContextIntakeClient'

export const blankCommitment = (): CommitmentDefinition => ({ label: '', kind: 'commitment', timeZone: '', startsLocal: '', endsLocal: '', recurrence: null, exceptions: [] })
/** Staging is inert, but malformed calendar drafts must not look ready to confirm. */
export function validateCommitmentDraft(value: CommitmentDefinition): string | null {
  try { commitmentDefinitionFrom(value) } catch { return 'Provide valid explicit calendar dates, timezone and recurrence before staging.' }
  if (!value.label.trim()) return 'Provide a calendar item label before staging.'
  if (value.startsLocal >= value.endsLocal) return 'End time must follow start time before staging.'
  if (Date.parse(`${value.endsLocal}Z`) - Date.parse(`${value.startsLocal}Z`) > 366 * 86400_000) return 'Calendar spans must not exceed 366 days.'
  if (value.recurrence?.endOn && value.recurrence.endOn < value.startsLocal.slice(0, 10)) return 'Recurrence end must not precede the start date.'
  if (value.exceptions.some(row => row.action === 'reschedule' && row.startsLocal! >= row.endsLocal!)) return 'Replacement end time must follow its start.'
  return null
}
/** Typed local fallback: relative weekdays are deliberately never parsed or guessed. */
export function CommitmentFields({ value, onChange, disabled }: { value: CommitmentDefinition; onChange: (value: CommitmentDefinition) => void; disabled: boolean }) {
  const update = <K extends keyof CommitmentDefinition>(key: K, next: CommitmentDefinition[K]) => onChange({ ...value, [key]: next })
  return <>
    <p>Clarify “Wednesday”: choose exact start/end dates, timezone and whether it repeats. Multi-day spans up to 366 days are supported. No date is guessed.</p>
    <label>Calendar item label<input required maxLength={160} value={value.label} disabled={disabled} onChange={e => update('label', e.target.value)} /></label>
    <label>Calendar kind<select value={value.kind} disabled={disabled} onChange={e => update('kind', e.target.value as CommitmentDefinition['kind'])}>
      <option value="commitment">Commitment</option><option value="availability">Availability</option></select></label>
    <label>Item timezone<input required placeholder="Europe/Prague" maxLength={80} value={value.timeZone} disabled={disabled} onChange={e => update('timeZone', e.target.value)} /></label>
    <label>Starts (local)<input type="datetime-local" required value={value.startsLocal} disabled={disabled} onChange={e => update('startsLocal', e.target.value)} /></label>
    <label>Ends (local)<input type="datetime-local" required value={value.endsLocal} disabled={disabled} onChange={e => update('endsLocal', e.target.value)} /></label>
    <label>Repeats<select value={value.recurrence?.frequency ?? ''} disabled={disabled} onChange={e => update('recurrence', e.target.value ?
      { frequency: e.target.value as 'daily' | 'weekly', interval: 1, endOn: null } : null)}>
      <option value="">Once</option><option value="daily">Daily</option><option value="weekly">Weekly on the start weekday</option></select></label>
    {value.recurrence && <>
      <label>Repeat interval<input type="number" required min={1} max={366} value={value.recurrence.interval} disabled={disabled}
        onChange={e => update('recurrence', { ...value.recurrence!, interval: Number(e.target.value) })} /></label>
      <label>Last original start date (optional)<input type="date" value={value.recurrence.endOn ?? ''} disabled={disabled}
        onChange={e => update('recurrence', { ...value.recurrence!, endOn: e.target.value || null })} /></label>
    </>}
    {value.exceptions.map((exception, index) => <fieldset key={index}><legend>Exception {index + 1}</legend>
      <label>Original occurrence date<input type="date" required value={exception.originalDate} disabled={disabled}
        onChange={e => update('exceptions', value.exceptions.map((row, i) => i === index ? { ...row, originalDate: e.target.value } : row))} /></label>
      <label>Exception action<select value={exception.action} disabled={disabled} onChange={e => update('exceptions', value.exceptions.map((row, i) => i === index ?
        { ...row, action: e.target.value as 'cancel' | 'reschedule', startsLocal: null, endsLocal: null } : row))}>
        <option value="cancel">Cancel occurrence</option><option value="reschedule">Reschedule occurrence</option></select></label>
      {exception.action === 'reschedule' && (['startsLocal', 'endsLocal'] as const).map(key => <label key={key}>Replacement {key === 'startsLocal' ? 'start' : 'end'} (local)
        <input type="datetime-local" required value={exception[key] ?? ''} disabled={disabled} onChange={e => update('exceptions', value.exceptions.map((row, i) => i === index ? { ...row, [key]: e.target.value } : row))} /></label>)}
      <button type="button" disabled={disabled} onClick={() => update('exceptions', value.exceptions.filter((_, i) => i !== index))}>Remove exception</button>
    </fieldset>)}
    <button type="button" disabled={disabled || value.exceptions.length >= 1000} onClick={() => update('exceptions', [...value.exceptions,
      { originalDate: '', action: 'cancel', startsLocal: null, endsLocal: null }])}>Add exception</button>
    <p>DST gaps shift forward; overlaps use the earlier offset. Uncertain/non-positive intervals require clarification. Unsupported monthly/yearly rules are not converted.</p>
  </>
}
