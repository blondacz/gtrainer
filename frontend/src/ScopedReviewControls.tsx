import { useEffect, useRef, useState } from 'react'
import { validDate, dateCount, type ScopedOptions, type ScopedRequest, type ScopedReport, type PeriodDto, type WellnessPolicy } from './ScopedReviewTypes'
import { loadReviewOptions, prepareReview } from './ReviewApi'
import { MetricCard } from './MetricCard'
import { prepareEvidence, type EvidenceSnapshot } from './ScopedEvidenceClient'
import { ScopedEvidenceInspection } from './ScopedEvidenceInspection'

type Props = { csrfToken?: string; revision?: number; idPrefix?: string }
const date = (v: string) => validDate(v)
const unitValue = (v: unknown) => typeof v === 'number' && Number.isFinite(v) ? String(v) : 'Unavailable'
export function ScopedReviewControls({ csrfToken, revision = 0, idPrefix = 'scoped' }: Props) {
  const [options, setOptions] = useState<ScopedOptions | null>(null)
  const [mode, setMode] = useState<'period'|'activities'>('period'), [intent, setIntent] = useState<'review'|'compare'>('review')
  const [scope, setScope] = useState('rolling'), [days, setDays] = useState('30'), [oldest, setOldest] = useState(''), [newest, setNewest] = useState('')
  const [comparison, setComparison] = useState(false), [cmpOld, setCmpOld] = useState(''), [cmpNew, setCmpNew] = useState('')
  const [wellness, setWellness] = useState<WellnessPolicy>('assessment'), [selected, setSelected] = useState<string[]>([]), [sport, setSport] = useState('')
  const [report, setReport] = useState<ScopedReport|null>(null), [message, setMessage] = useState('Loading activity options…')
  const [anchor, setAnchor] = useState(''), [activityCatalog, setActivityCatalog] = useState<ScopedOptions['activities']>([])
  const [browse, setBrowse] = useState<{oldest:string;newest:string;offset:number}>({oldest:'',newest:'',offset:0})
  const generation = useRef(0), controller = useRef<AbortController|null>(null)
  const [busy, setBusy] = useState(false)
  const [snapshotUncertain,setSnapshotUncertain]=useState(false)
  const [snapshot, setSnapshot] = useState<EvidenceSnapshot|null>(null), [purpose, setPurpose] = useState('period_reflection'), [question, setQuestion] = useState(''), [additionalText, setAdditionalText] = useState(''), [authorAttribution, setAuthorAttribution] = useState('')
  const [focus, setFocus] = useState<ScopedRequest['focus']>('daily_combined')
   const invalidate = () => { generation.current++; controller.current?.abort(); controller.current = null; setReport(null); setSnapshot(null); setBusy(false) }
  useEffect(() => {
    invalidate(); setOptions(null); setReport(null); setSelected([]); setActivityCatalog([])
    setMode('period'); setIntent('review'); setScope('rolling'); setWellness('assessment'); setComparison(false)
    setBrowse({ oldest: '', newest: '', offset: 0 })
    if (!csrfToken) { setMessage('No authorized review controls.'); return }
    const c = new AbortController(), epoch = generation.current; controller.current = c
     loadReviewOptions(c.signal).then(o => {
      if (generation.current !== epoch) return
      setOptions(o); setMessage(o.timeZone === null ? 'Save an explicit schedule time zone in Review controls before requesting a scoped report.' : `Options read only. Saved zone: ${o.timeZone}; server date: ${o.today ?? 'unavailable'}.`)
      if (o.today) { setNewest(o.today); setOldest(o.today); setAnchor(o.today); setActivityCatalog(o.activities) }
      if (o.browseRange) setBrowse({ ...o.browseRange, offset: 0 })
    }).catch(() => { if (!c.signal.aborted && generation.current === epoch) setMessage('Options unavailable or could not be verified.') })
    return () => { controller.current?.abort(); c.abort(); generation.current++ }
  }, [csrfToken, revision])
  function edit(work: () => void) { invalidate(); work() }
  async function browseActivities(offset = 0) {
    if (!options?.today || options.timeZone === null) return
    const a = browse.oldest || oldest || options.today, b = browse.newest || newest || options.today
    if (offset && (a !== options.browseRange?.oldest || b !== options.browseRange?.newest)) { setMessage('Browse the changed dates before paging.'); return }
    if (!date(a) || !date(b) || a > b || dateCount(a, b) > 366) { setMessage('Choose an ordered 1–366 date browse range.'); return }
    invalidate(); const epoch = generation.current, c = new AbortController(); controller.current = c
    try {
      const next = await loadReviewOptions(c.signal, { oldest:a, newest:b, offset }); if(epoch !== generation.current) return
      if (next.browseRange?.oldest !== a || next.browseRange.newest !== b || next.timeZone !== options.timeZone || next.configurationVersion !== options.configurationVersion) throw Error()
      setOptions(next)
      setActivityCatalog(prev => [...new Map([...prev.filter(x => selected.includes(x.activityId)), ...next.activities].map(x => [x.activityId, x])).values()])
      setBrowse({oldest:a,newest:b,offset}); setMessage('Activity options loaded; browsing does not save or request inference.')
    } catch { if (!c.signal.aborted && epoch === generation.current) setMessage('Activity options unavailable or could not be verified.') }
  }
  const request: ScopedRequest = mode === 'activities' ? {schemaVersion:1,mode,intent,scope:'activities',activityIds:selected,wellnessPolicy:wellness} :
    {schemaVersion:1,mode,intent,scope,focus,wellnessPolicy:wellness,sport:sport||null,...(scope==='rolling'?{days:Number(days)}:scope==='custom'?{oldest,newest}:{}),
      ...(scope!=='custom'?{anchorDate:anchor}:{}),...(comparison?{comparison:{oldest:cmpOld,newest:cmpNew}}:{})}
  async function submit(e?: React.FormEvent, prepareSnapshot = false) {
    e?.preventDefault(); if(options?.timeZone===null || !options || busy || prepareSnapshot && snapshotUncertain) return
    if(mode==='activities' && (selected.length < (intent==='compare'?2:1) || selected.length>1000 || selected.some(id=>!activityCatalog.some(a=>a.activityId===id)))) { setMessage('Select verified activity identities; comparison needs at least two.'); return }
    if(mode==='period' && (!options.today || !date(anchor)||anchor>options.today || scope==='rolling' && ![7,30,365].includes(Number(days)) || scope==='custom' && (!date(oldest)||!date(newest)||oldest>newest||newest>options.today) || comparison && (!date(cmpOld)||!date(cmpNew)||cmpOld>cmpNew||cmpNew>options.today))) { setMessage('Review the server-date bounds and selected dates.'); return }
    const dates = activityCatalog.filter(x => selected.includes(x.activityId)).map(x => x.startLocal.slice(0, 10)).sort()
    if (mode === 'activities' && dateCount(dates[0], dates.at(-1)!) > 366 || mode === 'period' && scope === 'custom' && dateCount(oldest, newest) > 366 || comparison && mode === 'period' && dateCount(cmpOld, cmpNew) > 366) { setMessage('Each selected span or comparison range must be at most 366 inclusive dates.'); return }
      if (prepareSnapshot && additionalText.trim() && !authorAttribution.trim()) { setMessage('Provide author attribution for additional text.'); return }
      if (prepareSnapshot && purpose==='question' && !question.trim()) { setMessage('Enter the question for this purpose.'); return }
      invalidate(); const epoch=generation.current, c=new AbortController(); controller.current=c; setBusy(true); setMessage(prepareSnapshot ? 'Preparing explicit evidence snapshot…' : 'Requesting factual report…')
      try { if (!csrfToken) throw Error(); if (prepareSnapshot) { const data=await prepareEvidence(request,purpose,question,additionalText,authorAttribution,csrfToken,options.timeZone,c.signal); if(epoch===generation.current){setReport(data.report);setSnapshot(data.snapshot);setMessage('Evidence snapshot received. Inspect its manifest; no inference or execution is authorized.')} }
        else { const data=await prepareReview(request,csrfToken,c.signal,options.timeZone); if(epoch===generation.current){setReport(data);setMessage('Factual report verified. No inference was requested.')} }
     } catch { if(!c.signal.aborted&&epoch===generation.current){
       if(prepareSnapshot) setSnapshotUncertain(true)
       setMessage(prepareSnapshot ? 'Snapshot preparation outcome unknown. No automatic retry. An earlier snapshot may have been saved; reload this page before starting a separate preparation.' : 'Report unavailable or could not be verified.')
     } }
    finally { if (epoch === generation.current) setBusy(false) }
  }
  function changeMode(value:'period'|'activities') { edit(()=>{setMode(value);setPurpose(value==='activities'?'activity_reflection':'period_reflection');setIntent('review');setWellness(value==='period'?'assessment':'off');setScope(value==='period'?'rolling':'activities');setSelected([]);setComparison(false)}) }
  const id=(name:string)=>`${idPrefix}-${name}`
  const browseChanged = browse.oldest !== options?.browseRange?.oldest || browse.newest !== options?.browseRange?.newest
  if (!csrfToken) return <p>No authorized review controls.</p>
  return <section className="analysis-panel" aria-labelledby={id('heading')}>
    <h3 id={id('heading')}>Scoped factual review</h3><p>Explicit factual data request only; reading and browsing never save, sync, or request inference.</p>
    <p aria-live="polite">{message}</p>
    {options?.timeZone===null && <p>Time zone is not configured. Save an explicit time zone in existing Review controls; browser time zone is never substituted.</p>}
     <form className="compact-form" onSubmit={e=>void submit(e)}><fieldset disabled={!options||options.timeZone===null||busy}>
      <label htmlFor={id('mode')}>Review mode</label><select id={id('mode')} value={mode} onChange={e=>changeMode(e.target.value as 'period'|'activities')}><option value="period">Period</option><option value="activities">Selected activities</option></select>
      <label htmlFor={id('intent')}>Intent</label><select id={id('intent')} value={intent} onChange={e=>edit(()=>setIntent(e.target.value as 'review'|'compare'))}><option value="review">Review</option><option value="compare">Compare</option></select>
      {mode==='period' ? <>
        <label htmlFor={id('scope')}>Period scope</label><select id={id('scope')} value={scope} onChange={e=>edit(()=>setScope(e.target.value))}>{['rolling','calendar_week','calendar_month','calendar_year','day','night','custom'].map(s=><option key={s} value={s}>{s==='rolling'?'Rolling':s==='calendar_week'?'Calendar week':s==='calendar_month'?'Calendar month':s==='calendar_year'?'Calendar year':s==='day'?'Calendar day':s==='night'?'Night (unverified mapping)':'Custom dates'}</option>)}</select>
        {scope!=='custom'&&<><label htmlFor={id('anchor')}>Server-zone anchor date</label><input id={id('anchor')} type="date" value={anchor} max={options?.today??undefined} onChange={e=>edit(()=>setAnchor(e.target.value))}/></>}
        {scope==='rolling'&&<label htmlFor={id('days')}>Rolling days<select id={id('days')} value={days} onChange={e=>edit(()=>setDays(e.target.value))}>{[7,30,365].map(n=><option key={n} value={n}>Last {n} days</option>)}</select></label>}
        {scope==='custom'&&<><label htmlFor={id('oldest')}>First date</label><input id={id('oldest')} type="date" value={oldest} onChange={e=>edit(()=>setOldest(e.target.value))}/><label htmlFor={id('newest')}>Last date</label><input id={id('newest')} type="date" value={newest} onChange={e=>edit(()=>setNewest(e.target.value))}/></>}
        <label htmlFor={id('sport')}>Sport filter (optional)</label><input id={id('sport')} value={sport} onChange={e=>edit(()=>setSport(e.target.value))}/>
        <label htmlFor={id('focus')}>Factual focus</label><select id={id('focus')} value={focus} onChange={e=>edit(()=>setFocus(e.target.value as ScopedRequest['focus']))}><option value="daily_combined">Activity and wellness</option><option value="activity_balance">Sport balance</option><option value="wellness">Wellness</option><option value="missing_wellness">Missing wellness</option></select>
        <label><input type="checkbox" checked={comparison} onChange={e=>edit(()=>setComparison(e.target.checked))}/>Use explicit comparison dates</label>
        {comparison&&<><label htmlFor={id('cmpOld')}>Comparison first date</label><input id={id('cmpOld')} type="date" value={cmpOld} onChange={e=>edit(()=>setCmpOld(e.target.value))}/><label htmlFor={id('cmpNew')}>Comparison last date</label><input id={id('cmpNew')} type="date" value={cmpNew} onChange={e=>edit(()=>setCmpNew(e.target.value))}/></>}
      </> : <>
        <label htmlFor={id('browseOld')}>Activity browse first date</label><input id={id('browseOld')} type="date" value={browse.oldest} onChange={e=>edit(()=>setBrowse({...browse,oldest:e.target.value,offset:0}))}/><label htmlFor={id('browseNew')}>Activity browse last date</label><input id={id('browseNew')} type="date" value={browse.newest} onChange={e=>edit(()=>setBrowse({...browse,newest:e.target.value,offset:0}))}/>
        <button type="button" onClick={()=>void browseActivities()}>Browse activities</button>
        <div><fieldset><legend>Verified activity identities</legend>{activityCatalog.map(a=><label key={a.activityId}><input type="checkbox" checked={selected.includes(a.activityId)} onChange={e=>edit(()=>setSelected(e.target.checked?[...selected,a.activityId]:selected.filter(x=>x!==a.activityId)))}/>{a.startLocal} · {a.sport} · {a.timeContext}</label>)}</fieldset></div>
        {browse.offset>0&&<button type="button" disabled={browseChanged} onClick={()=>void browseActivities(Math.max(0,browse.offset-200))}>Previous activity page</button>}{options?.nextOffset!==null&&options?.nextOffset!==undefined&&<button type="button" disabled={browseChanged} onClick={()=>void browseActivities(options.nextOffset!)}>Next activity page</button>}
      </>}
      <label htmlFor={id('wellness')}>Wellness policy</label><select id={id('wellness')} value={wellness} onChange={e=>edit(()=>setWellness(e.target.value as WellnessPolicy))}>{['off','display_only','assessment'].map(x=><option key={x} value={x}>{x==='off'?'Off':x==='display_only'?'Display only':'Assessment context'}</option>)}</select>
      <p>{mode === 'activities' ? 'Exactly the chosen sessions; compare requires at least two. Wellness is optional and is not automatically a comparison subject.' : 'Available wellness is included by default; comparisons use resolved periods, not selected session IDs.'}</p>
      <p>{wellness === 'display_only' ? 'Display-only wellness is excluded from provider packets and comparisons.' : wellness === 'assessment' ? 'Wellness selected as assessment context; no inference or transfer is authorized by this choice.' : 'Optional wellness excluded. Mandatory applicable restrictions are not disabled.'}</p>
       <button type="submit" disabled={busy}>Request factual report</button>
       <h4>Optional evidence snapshot</h4><p>Only the explicit button saves a local evidence snapshot. Questions and attributed additional text are not reusable memories or instructions. This grants no inference, hosted transfer or plan approval.</p>
       <label htmlFor={id('purpose')}>Snapshot purpose</label><select id={id('purpose')} value={purpose} onChange={e=>edit(()=>setPurpose(e.target.value))}>{['morning','evening','activity_reflection','period_reflection','question','adaptation'].map(x=><option key={x}>{x}</option>)}</select>
       {purpose==='activity_reflection' && mode==='period' && <p>Activity reflection is intended for an activity selection.</p>}
       <label htmlFor={id('question')}>Question (optional, max 2000 characters)</label><textarea id={id('question')} maxLength={2000} value={question} onChange={e=>edit(()=>setQuestion(e.target.value))}/>
       <label htmlFor={id('additionalText')}>Additional text (optional, max 4000 characters)</label><textarea id={id('additionalText')} maxLength={4000} value={additionalText} onChange={e=>edit(()=>setAdditionalText(e.target.value))}/>
       <label htmlFor={id('authorAttribution')}>Author attribution (required when additional text is supplied)</label><input id={id('authorAttribution')} maxLength={160} value={authorAttribution} onChange={e=>edit(()=>setAuthorAttribution(e.target.value))}/>
       <button type="button" disabled={busy||snapshotUncertain} onClick={()=>void submit(undefined, true)}>Prepare evidence snapshot</button>
     </fieldset></form>
      {report&&<ScopedReport report={report}/>} {snapshot&&<ScopedEvidenceInspection snapshot={snapshot} onSnapshot={setSnapshot}
        onRemoved={()=>{setSnapshot(null);setReport(null);setQuestion('');setAdditionalText('');setAuthorAttribution('')}}/>}
  </section>
}

function ScopedReport({report:r}:{report:ScopedReport}) {
  const s=r.scope, range=(p:PeriodDto|null)=>p?`${p.oldest}–${p.newest}`:'Unavailable'
  const [metricIndex, setMetricIndex] = useState(0)
  const metrics = r.selected ? [...r.selected.sports.flatMap(g=>g.metrics.map(m=>({sport:g.sport,m}))),...r.selected.wellness.map(m=>({sport:null,m}))] : []
  const metric = metrics[metricIndex] ?? metrics[0]
  return <div><h4>Verified factual report</h4><p>{s.status}; evaluated {s.asOf}; saved zone {s.timeZone}; date basis: {s.dateBasis}</p>
    {s.reasons.length > 0 && <p>Time/scope disclosures: {s.reasons.join(', ')}</p>}
    <p>Selected: {s.request.mode === 'activities' ? `exact activity source dates ${s.activityDates.join(', ')}` : range(r.selected)}{s.soFar?' (so far at the captured cutoff)':''}. Full calendar boundary: {s.fullCalendarRange?`${s.fullCalendarRange.oldest}–${s.fullCalendarRange.newest}`:'Unavailable'}. Comparison policy: {s.comparison.policy}; selected/comparable {range(r.comparableSelected)}; previous {range(r.comparablePrevious)}; prior calendar full {range(r.priorCalendarFull)}.</p>
    {r.selected&&<PeriodSummary title="Selected period totals" period={r.selected}/>}{r.comparisonFull&&<PeriodSummary title="Full comparison totals" period={r.comparisonFull}/>}{r.priorCalendarFull&&<PeriodSummary title="Prior calendar full totals" period={r.priorCalendarFull}/>}
    {metric && r.selected && <><label>Metric / sport<select value={metrics.indexOf(metric)} onChange={e=>setMetricIndex(Number(e.target.value))}>{metrics.map((x,i)=><option key={`${x.sport}-${x.m.key}`} value={i}>{x.sport??'Wellness'} · {x.m.label}</option>)}</select></label><MetricCard key={`${metric.sport}-${metric.m.key}`} series={metric.m} oldest={r.selected.oldest} newest={r.selected.newest} comparison={r.comparisons.find(c=>c.key===metric.m.key&&c.sport===metric.sport)} comparisonDates={`Comparable selected ${range(r.comparableSelected)}; previous ${range(r.comparablePrevious)}`}/></>}
    {r.factualReview && <section aria-label="Application-generated facts"><h4>Application-generated facts and focus — not AI</h4><p>Requested focus: {r.factualReview.requestedFocus}. {r.factualReview.reason}</p>{r.factualReview.focuses.map((f,i)=><p key={i}>{f.kind}: {f.text} · evidence {f.factIds.join(', ')}</p>)}{r.factualReview.groups.map(g=><section key={g.id}><h5>{g.coverage}: {g.periods.currentOldest}–{g.periods.currentNewest} vs {g.periods.previousOldest}–{g.periods.previousNewest}</h5><ul>{g.facts.map(f=><li key={f.id}>{f.text}<details><summary>Fact {f.id} evidence and coverage</summary><ul>{f.supportingMetrics.map(m=><li key={m.period}>{m.period}: {unitValue(m.value)} {m.unit}; {m.sampleCount} records, {m.observedDays}/{m.periodDays} dates; sources {m.sources.join(', ')||'unknown'}; metric origins {m.metricOrigins.join(', ')||'unknown'}; record origins {m.recordOrigins.join(', ')||'unknown'}; {m.unknownMetricOriginCount} unknown origins. {m.flags.join(', ')}</li>)}</ul></details></li>)}</ul></section>)}<ul>{r.factualReview.limitations.map((x,i)=><li key={i}>{x}</li>)}</ul></section>}
    <p>Comparison details: {s.comparison.unequalDuration?'durations unequal. ':''}{s.comparison.overlapping?'windows overlap. ':''}Unmatched selected dates: {(s.comparison.unmatchedSelectedDates??[]).join(', ')||'none reported'}; unmatched previous dates: {(s.comparison.unmatchedPreviousDates??[]).join(', ')||'none reported'}; prior-calendar remainder: {(s.comparison.priorCalendarRemainderDates??[]).join(', ')||'none reported'}.</p>
    {s.status==='needs_confirmation'&&<p>Needs confirmation: {s.reasons.join(', ')}. Server could not verify the requested time association. No day is labelled as a prior night.</p>}
    <h4>Sessions</h4><ul>{r.sessions.map(x=><li key={x.activityId}><strong>{x.record.startLocal} · {x.record.sport}</strong><p>Activity ID: {x.activityId}; source {x.record.source} / {x.record.sourceRecordId}; instant {x.record.startInstant ?? 'unverified'}; time context {x.record.timeContext}.</p><ul>{x.metrics.map(m=><li key={m.key}>{m.label}: {unitValue(m.value)} {m.unit} ({m.basis}); metric origin {m.upstreamSource ?? 'unknown'}.</li>)}</ul></li>)}</ul>
    <h4>Session comparisons</h4><ul>{r.sessionComparisons.map((x,i)=><li key={`${x.key}-${x.sport}-${i}`}>{x.label} ({x.unit}, basis {x.basis}); sport {x.sport??'all'}: {x.values.map(v=>`${v.activityId.slice(0,8)} ${unitValue(v.value)}`).join(' · ')}. {x.flags.join(', ')}</li>)}</ul>
    <h4>Wellness context — source dates, not prior-night mapping</h4><ul>{r.wellnessContext.map((x,i)=><li key={`${x.record.date}-${i}`}>{x.record.date}: {Object.entries(x.record.measurements).map(([key,m])=>`${key} ${unitValue(m.value)} ${m.unit} (${m.upstreamSource??'source unknown'})`).join(', ')}; linked activity references {x.activityIds.join(', ')||'none'}; usage {x.usage}; {x.flags.join(', ')}</li>)}</ul>
    <h4>Unavailable metrics and source status</h4><ul>{r.unavailable.map(x=><li key={x.key}>{x.label}: unavailable — {x.reason}</li>)}{r.sourceStatus.map((x,i)=><li key={i}>{x.category}: {x.readStatus}; {x.recordCount} stored records; last attempt {x.lastAttemptUtc??'none'}; last successful read {x.lastSuccessUtc??'none'}; latest source date {x.latestObservedDate??'unknown'} ({x.latestObservedAgeDays??'unknown'} days); {x.rejected} rejected, {x.incomplete} incomplete. Upstream freshness: {x.upstreamFreshness}</li>)}</ul>
    <ul>{r.limitations.map((limitation, index) => <li key={index}>{limitation}</li>)}</ul>
  </div>
}

function PeriodSummary({title,period}:{title:string;period:PeriodDto}) {
  const rows=[...period.sports.flatMap(group=>group.metrics.map(m=>({sport:group.sport,m}))),...period.wellness.map(m=>({sport:'Wellness',m}))]
  return <section><h4>{title}: {period.oldest}–{period.newest} ({period.days} dates)</h4><p>{period.activityRecords} activities; {period.wellnessRecords} wellness records. Flags: {period.flags.join(', ')||'none'}.</p><ul>{rows.map(({sport,m})=><li key={`${sport}-${m.key}`}>{sport} · {m.label}: {unitValue(m.value)} {m.unit}; {m.sampleCount} records, {m.observedDays}/{m.periodDays} dates; {m.missingRecordValues} missing, {m.rejectedValues} rejected.</li>)}</ul></section>
}
