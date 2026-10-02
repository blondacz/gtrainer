import { useEffect, useState } from 'react'
import type { Comparison, TrendReport } from './TrendPanel'
import { TextPages } from './TilePrimitives'

type Support = { evidenceId: string; period: string; oldest: string; newest: string; sport: string | null; metric: string
  label: string; unit: string; aggregation: string; value: number | null; sampleCount: number; observedDays: number; periodDays: number
  sources: string[]; metricOrigins: string[]; recordOrigins: string[]; unknownMetricOriginCount: number
  firstObservedDate: string | null; lastObservedDate: string | null; flags: string[] }
type Fact = { id: string; state: string; text: string; comparison: Comparison; supportingMetrics: Support[] }
type Group = { id: string; periods: { previousOldest: string; previousNewest: string; currentOldest: string; currentNewest: string }
  coverage: 'available' | 'sparse' | 'unavailable'; facts: Fact[] }
type Review = { profile: 'factual-review-v1'; applicationGenerated: true; evidenceReportSha256: string; requestedFocus: string
  reason: string; groups: Group[]; focuses: { kind: string; groupId: string; factIds: string[]; text: string }[]; limitations: string[] }
const strings = (value: unknown): value is string[] => Array.isArray(value) && value.every(item => typeof item === 'string')
const sameIds = (a: string[], b: string[]) => a.length === b.length && new Set(a).size === a.length && a.every(id => b.includes(id))
const direction = (comparison: Comparison) => comparison.currentValue === null || comparison.previousValue === null || comparison.absoluteChange === null ? 'unavailable' :
  comparison.absoluteChange > 0 ? 'increased' : comparison.absoluteChange < 0 ? 'decreased' : 'unchanged'

/** Require complete evidence coverage and the displayed snapshot, not merely plausible IDs. */
function reviewFrom(value: unknown, report: TrendReport, digest: string, focus: string): Review {
  const review = value as Review
  if (!review || review.profile !== 'factual-review-v1' || review.applicationGenerated !== true ||
      review.evidenceReportSha256 !== digest || review.requestedFocus !== focus || !strings(review.limitations) ||
      !Array.isArray(review.groups) || !Array.isArray(review.focuses)) throw new Error('Invalid private factual review')
  const seen = new Set<string>()
  const groupIds = new Set<string>()
  for (const group of review.groups) {
    if (!group || typeof group.id !== 'string' || groupIds.has(group.id) || !group.periods ||
        group.periods.previousOldest !== report.previous.oldest || group.periods.previousNewest !== report.previous.newest ||
        group.periods.currentOldest !== report.current.oldest || group.periods.currentNewest !== report.current.newest ||
        !['available', 'sparse', 'unavailable'].includes(group.coverage) || !Array.isArray(group.facts) || !group.facts.length) throw new Error('Invalid private group')
    groupIds.add(group.id)
    for (const fact of group.facts) {
      const index = report.comparisons.findIndex(c => fact?.comparison && c.key === fact.comparison.key && c.sport === fact.comparison.sport)
      const comparison = report.comparisons[index]
      if (!fact || index < 0 || fact.id !== `r${index}` || seen.has(fact.id) || typeof fact.text !== 'string' || fact.text.length > 6000 ||
          fact.state !== direction(comparison) || !Array.isArray(fact.supportingMetrics) || fact.supportingMetrics.length !== 2 ||
          !['key', 'sport', 'label', 'unit', 'previousValue', 'currentValue', 'absoluteChange', 'percentChange', 'previousSamples', 'currentSamples'].every(key =>
            fact.comparison[key as keyof Comparison] === comparison[key as keyof Comparison]) ||
          !strings(fact.comparison.flags) || !sameIds(fact.comparison.flags, comparison.flags)) throw new Error('Invalid private comparison')
      const coverage = fact.state === 'unavailable' ? 'unavailable' : comparison.previousSamples < 2 || comparison.currentSamples < 2 ||
        comparison.flags.includes('sparse_comparison') ? 'sparse' : 'available'
      if (coverage !== group.coverage) throw new Error('Mismatched private coverage')
      seen.add(fact.id)
      for (const [index, metric] of fact.supportingMetrics.entries()) {
        const name = index === 0 ? 'previous' : 'current'
        const period = report[name]
        const series = comparison.sport === null ? period.wellness.find(m => m.key === comparison.key) :
          period.sports.find(s => s.sport === comparison.sport)?.metrics.find(m => m.key === comparison.key)
        if (!series) throw new Error('Missing private support')
        const sources = [...new Set(series.points.map(p => p.source))]
        const origins = [...new Set(series.points.flatMap(p => p.upstreamSource === null ? [] : [p.upstreamSource]))]
        const dates = series.points.map(p => p.date).sort()
        const unknownOrigins = series.points.filter(p => p.upstreamSource === null).length
        const flags = [...new Set([...period.flags,
          ...(series.value === null ? ['metric_unavailable'] : []),
          ...(series.missingRecordValues > 0 || series.rejectedValues > 0 ? ['partial_metric_coverage'] : []),
          ...(series.sampleCount < 2 ? ['sparse_metric'] : []),
          ...(unknownOrigins > 0 ? ['metric_origin_unknown'] : []),
          ...(sources.length > 1 ? ['multiple_record_sources'] : []),
          ...(origins.length > 1 ? ['mixed_metric_origins'] : []),
          ...(dates.length && (Date.parse(period.newest) - Date.parse(dates[dates.length - 1])) / 86400000 > 7 ? ['metric_observed_gap_over_7_days'] : []),
          ...(report.sourceStatus.some(s => s.category === (comparison.sport === null ? 'wellness' : 'activities') && s.readStatus !== 'SUCCESS') ? ['latest_category_read_not_successful'] : []),
        ])]
        if (!metric || metric.period !== name || metric.evidenceId !== `${name}/${comparison.sport ?? 'wellness'}/${comparison.key}` ||
            metric.oldest !== period.oldest || metric.newest !== period.newest || metric.sport !== comparison.sport || metric.metric !== comparison.key ||
            metric.value !== series.value || metric.unit !== series.unit || metric.label !== series.label || metric.aggregation !== series.aggregation ||
            metric.sampleCount !== series.sampleCount || metric.observedDays !== series.observedDays || metric.periodDays !== period.days ||
            !strings(metric.sources) || !sameIds(metric.sources, sources) || !strings(metric.metricOrigins) || !sameIds(metric.metricOrigins, origins) ||
            !strings(metric.recordOrigins) || !sameIds(metric.recordOrigins, [...new Set(series.points.flatMap(p => p.recordOrigins))]) ||
            metric.unknownMetricOriginCount !== unknownOrigins || metric.firstObservedDate !== (dates[0] ?? null) || metric.lastObservedDate !== (dates[dates.length - 1] ?? null) ||
            !strings(metric.flags) || !sameIds(metric.flags, flags)) throw new Error('Invalid private support')
      }
    }
  }
  if (seen.size !== report.comparisons.length) throw new Error('Incomplete private factual coverage')
  const usableWellness = review.groups.some(g => g.coverage === 'available' && g.facts.some(f => f.comparison.sport === null))
  const expected = review.groups.flatMap(group => {
    const activity = group.facts.filter(f => f.comparison.sport !== null && f.comparison.key === 'movingTime')
    const wellness = group.facts.filter(f => f.comparison.sport === null)
    if (focus === 'daily_combined' && group.coverage === 'available' && activity.length && wellness.length)
      return [{ kind: 'cross_metric_pattern', groupId: group.id, ids: [...activity, ...wellness].map(f => f.id) }]
    if (focus === 'activity_balance' && group.coverage === 'available' && new Set(activity.map(f => f.comparison.sport)).size >= 2)
      return [{ kind: 'sport_mix', groupId: group.id, ids: activity.map(f => f.id) }]
    if ((focus === 'missing_wellness' || (focus === 'wellness' && !usableWellness)) && group.coverage !== 'available' && wellness.length)
      return [{ kind: 'coverage_gap', groupId: group.id, ids: wellness.map(f => f.id) }]
    if (focus === 'wellness' && group.coverage === 'available' && wellness.length)
      return [{ kind: 'wellness_pattern', groupId: group.id, ids: wellness.map(f => f.id) }]
    return []
  })
  if (review.focuses.length !== expected.length || review.reason !== (expected.length ? 'supported_focus' : 'no_supported_focus') ||
      !review.focuses.every((selected, index) => selected && selected.kind === expected[index].kind && selected.groupId === expected[index].groupId &&
        strings(selected.factIds) && sameIds(selected.factIds, expected[index].ids) && typeof selected.text === 'string' && selected.text.length < 2000))
    throw new Error('Mismatched rule-selected focus')
  return review
}

export function FactualReviewPanel({ report, digest, compact = false }: { report: TrendReport; digest: string | null; compact?: boolean }) {
  const [focus, setFocus] = useState('daily_combined')
  const [result, setResult] = useState<{ report: TrendReport; focus: string; review: Review } | null>(null)
  const [message, setMessage] = useState('')
  const [factIndex, setFactIndex] = useState(0)
  const [supportIndex, setSupportIndex] = useState(1)
  const [detail, setDetail] = useState('values')
  useEffect(() => {
    const controller = new AbortController()
    setResult(null)
    if (!digest || !/^[a-f0-9]{64}$/.test(digest)) {
      setMessage('Factual review requires a verified snapshot. Refresh the charts; no model is called.')
      return () => controller.abort()
    }
    setMessage('Preparing code-owned factual groups…')
    async function load() {
      try {
        const params = new URLSearchParams({ oldest: report.current.oldest, newest: report.current.newest, focus, evidenceReportSha256: digest! })
        if (report.selectedSport) params.set('sport', report.selectedSport)
        const response = await fetch(`/api/review-facts?${params}`, { cache: 'no-store', signal: controller.signal })
        if (!response.ok) throw new Error('Factual review unavailable')
        const review = reviewFrom(await response.json(), report, digest!, focus)
        if (!controller.signal.aborted) { setResult({ report, focus, review }); setMessage('Application-generated facts and rule-selected focus. No inference.') }
      } catch {
        if (!controller.signal.aborted) setMessage('Factual review unavailable or snapshot changed. Refresh the charts; no model fallback.')
      }
    }
    void load()
    return () => controller.abort()
  }, [report, digest, focus])
  const review = result?.report === report && result.focus === focus && result.review.evidenceReportSha256 === digest ? result.review : null
  return <section aria-labelledby="factual-review-heading" className="analysis-panel">
    <h3 id="factual-review-heading">Factual review — no AI</h3>
    {!compact && <p>Code keeps every comparison visible, including missing and sparse values. Focus rules only choose descriptive support; they do not assess readiness.</p>}
    <label htmlFor="factual-review-focus">Factual review focus</label>
    <select id="factual-review-focus" value={focus} onChange={event => setFocus(event.target.value)}>
      <option value="daily_combined">Combined activity and wellness</option><option value="activity_balance">Recorded time across sports</option>
      <option value="wellness">Wellness comparisons</option><option value="missing_wellness">Wellness coverage gaps</option>
    </select>
    <p aria-live="polite">{message}</p>
    {compact && review && (() => {
      const all = review.groups.flatMap(group => group.facts.map(fact => ({ group, fact })))
      const selected = Math.min(factIndex, all.length - 1)
      const entry = all[selected]
      if (!entry) return <p>No supplied comparisons. No AI inference or replacement facts.</p>
      const metric = entry.fact.supportingMetrics[supportIndex]
      const description = detail === 'values' ? `${entry.fact.text} ${metric.period}: ${metric.value ?? 'unavailable'} ${metric.unit}, ${metric.oldest}–${metric.newest}.` :
        detail === 'provenance' ? `Sources: ${metric.sources.join(', ') || 'unknown'}; metric origins: ${metric.metricOrigins.join(', ') || 'unknown'}. Record origin labels: ${metric.recordOrigins.join(', ') || 'unknown'}; ${metric.unknownMetricOriginCount} unknown metric origins. Evidence: ${metric.evidenceId}.` :
          detail === 'coverage' ? `Group ${entry.group.id}: ${entry.group.coverage}. ${metric.sampleCount} populated records across ${metric.observedDays}/${metric.periodDays} dates. First/last observation: ${metric.firstObservedDate ?? 'unavailable'} / ${metric.lastObservedDate ?? 'unavailable'}. Flags: ${metric.flags.join(', ') || 'none'}.` :
            detail === 'focus' ? review.focuses.map(focus => `${focus.text} Supported comparisons: ${focus.factIds.join(', ')}; group ${focus.groupId}.`).join(' ') || 'No supported focus. Complete facts remain available.' : review.limitations.join(' ')
      return <div className="dashboard-tile factual-tile"><label htmlFor="factual-comparison">Complete factual comparisons ({all.length})</label>
        <select id="factual-comparison" value={selected} onChange={event => setFactIndex(Number(event.target.value))}>{all.map(({ fact }, index) =>
          <option key={fact.id} value={index}>{fact.comparison.sport ?? 'Wellness'} · {fact.comparison.label} · {fact.state}</option>)}</select>
        <div className="section-tabs"><button aria-pressed={supportIndex === 0} onClick={() => setSupportIndex(0)}>Previous period</button>
          <button aria-pressed={supportIndex === 1} onClick={() => setSupportIndex(1)}>Current period</button></div>
        <nav className="section-tabs" aria-label="Factual evidence sections">{['values', 'provenance', 'coverage', 'focus', 'limits'].map(name =>
          <button key={name} aria-pressed={detail === name} onClick={() => setDetail(name)}>{name}</button>)}</nav>
        <TextPages key={`${selected}-${supportIndex}-${detail}`} label="Factual details" text={description} />
        <p className="tile-detail">All supplied comparisons retained; paging changes presentation only. Application facts, no AI or readiness judgment.</p>
      </div>
    })()}
    {!compact && review && <div>
      {review.focuses.length ? review.focuses.map(selected => <article key={`${selected.groupId}-${selected.kind}`}>
        <h4>Rule-selected focus</h4><p>{selected.text}</p><p>Supporting comparisons: {selected.factIds.join(', ')}.</p>
      </article>) : <p>No supported pattern for this focus. Facts remain available; missing imports do not prove missed activity.</p>}
      {review.groups.map(group => <details key={group.id} open={group.coverage !== 'available'}>
        <summary>Complete factual group {group.id}: {group.coverage} comparisons ({group.facts.length})</summary>
        <p>Previous: {group.periods.previousOldest}–{group.periods.previousNewest}; current: {group.periods.currentOldest}–{group.periods.currentNewest}.</p>
        <ul>{group.facts.map(fact => <li key={fact.id}><p>{fact.id}: {fact.text}</p>
          <ul>{fact.supportingMetrics.map(metric => <li key={metric.evidenceId}>
            {metric.period}: {metric.label} {metric.value ?? 'unavailable'} {metric.unit}; {metric.oldest}–{metric.newest};
            {' '}{metric.sampleCount} populated records across {metric.observedDays}/{metric.periodDays} dates.
            {' '}Sources: {metric.sources.join(', ') || 'unknown'}; metric origins: {metric.metricOrigins.join(', ') || 'unknown'}.
            {' '}Record origin labels: {metric.recordOrigins.join(', ') || 'unknown'}; {metric.unknownMetricOriginCount} records with unknown metric origin.
            {' '}First/last observed dates: {metric.firstObservedDate ?? 'unavailable'} / {metric.lastObservedDate ?? 'unavailable'}.
            {' '}Evidence: {metric.evidenceId}. Flags: {metric.flags.join(', ') || 'none'}.
          </li>)}</ul></li>)}</ul>
      </details>)}
      <details><summary>Factual review limits</summary><ul>{review.limitations.map((limit, index) => <li key={index}>{limit}</li>)}</ul></details>
    </div>}
  </section>
}
