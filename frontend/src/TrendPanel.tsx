import { useEffect, useState } from 'react'
import { AnalysisPanel } from './AnalysisPanel'
import { FactualReviewPanel } from './FactualReviewPanel'
import { TextPages } from './TilePrimitives'

export type ObservedValue = {
  source: string; sourceRecordId: string; category: string; field: string; date: string; value: number; unit: string
  upstreamSource: string | null; recordOrigins: string[]; timeContext: string; timeZone: string | null; startInstant: string | null
}
export type MetricSeries = {
  key: string; label: string; unit: string; aggregation: 'sum' | 'mean'; value: number | null
  sampleCount: number; observedDays: number; periodDays: number; missingRecordValues: number; rejectedValues: number; points: ObservedValue[]
}
type Period = { oldest: string; newest: string; days: number; activityRecords: number; wellnessRecords: number
  sports: { sport: string; metrics: MetricSeries[] }[]; wellness: MetricSeries[]; flags: string[] }
export type Comparison = { sport: string | null; key: string; label: string; unit: string; currentValue: number | null
  previousValue: number | null; absoluteChange: number | null; percentChange: number | null; currentSamples: number; previousSamples: number; flags: string[] }
export type TrendReport = { evaluatedOnUtc: string; selectedSport: string | null; availableSports: string[]; current: Period; previous: Period
  comparisons: Comparison[]; sourceStatus: { category: string; readStatus: string; latestObservedDate: string | null; latestObservedAgeDays: number | null }[]
  unavailable: { key: string; label: string; reason: string }[]; dateBasis: string }

const units: Record<string, string> = { activityCount: 'count', movingTime: 'seconds', elapsedTime: 'seconds', calories: 'kcal',
  intervalsTrainingLoad: 'Intervals.icu load', weight: 'kg', vo2max: 'mL/kg/min', hrv: 'ms', sleepSecs: 'seconds', hrvSDNN: 'ms',
  bodyFat: 'percent', restingHR: 'beats/minute', sleepScore: 'source sleep score', steps: 'count', atl: 'Intervals.icu load', ctl: 'Intervals.icu load' }
const number = new Intl.NumberFormat(undefined, { maximumFractionDigits: 2 })
const validNumber = (value: unknown): value is number => typeof value === 'number' && Number.isFinite(value)
const validNullable = (value: unknown) => value === null || validNumber(value)
const strings = (value: unknown): value is string[] => Array.isArray(value) && value.every(item => typeof item === 'string')
const validDate = (value: unknown): value is string => typeof value === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(value) &&
  Number.isFinite(Date.parse(value)) && new Date(value).toISOString().slice(0, 10) === value

export function reportFrom(value: unknown): TrendReport {
  if (typeof value !== 'object' || value === null) throw new Error('Invalid private response')
  const report = value as TrendReport
  function metric(series: MetricSeries): boolean {
    return series != null && typeof series.label === 'string' && typeof series.key === 'string' && typeof series.unit === 'string' && units[series.key] === series.unit &&
      ['sum', 'mean'].includes(series.aggregation) && validNullable(series.value) && (series.value === null || series.value >= 0) &&
      Number.isInteger(series.sampleCount) && series.sampleCount >= 0 && Number.isInteger(series.observedDays) && series.observedDays >= 0 &&
      Number.isInteger(series.missingRecordValues) && series.missingRecordValues >= 0 && Number.isInteger(series.rejectedValues) && series.rejectedValues >= 0 &&
      Array.isArray(series.points) && series.points.length === series.sampleCount && series.points.every(point => point != null &&
        validDate(point.date) && validNumber(point.value) && point.value >= 0 && point.unit === series.unit &&
        typeof point.source === 'string' && typeof point.sourceRecordId === 'string' && typeof point.timeContext === 'string' &&
        strings(point.recordOrigins) && (point.upstreamSource === null || typeof point.upstreamSource === 'string')) &&
      new Set(series.points.map(point => point.date)).size === series.observedDays &&
      (series.sampleCount > 0 || series.value === null || (series.key === 'activityCount' && series.value === 0))
  }
  function period(period: Period): boolean {
    return period != null && validDate(period.oldest) && validDate(period.newest) &&
      Number.isInteger(period.days) && period.days >= 1 && period.days <= 366 &&
      (Date.parse(period.newest) - Date.parse(period.oldest)) / 86400000 + 1 === period.days &&
      Number.isInteger(period.activityRecords) && period.activityRecords >= 0 && Number.isInteger(period.wellnessRecords) && period.wellnessRecords >= 0 &&
      strings(period.flags) && Array.isArray(period.sports) &&
      period.sports.every(group => group != null && typeof group.sport === 'string' && Array.isArray(group.metrics) && group.metrics.every(metric)) &&
      Array.isArray(period.wellness) && period.wellness.every(metric) &&
      [...period.wellness, ...period.sports.flatMap(group => group.metrics)].every(series => series.periodDays === period.days &&
        series.points.every(point => point.date >= period.oldest && point.date <= period.newest))
  }
  if (!period(report.current) || !period(report.previous) || !strings(report.availableSports) || !validDate(report.evaluatedOnUtc) ||
      typeof report.dateBasis !== 'string' || !Array.isArray(report.comparisons) || !report.comparisons.every(item => item != null &&
        typeof item.key === 'string' && units[item.key] === item.unit && validNullable(item.absoluteChange) && validNullable(item.percentChange) &&
        validNullable(item.currentValue) && validNullable(item.previousValue) && strings(item.flags)) ||
      !Array.isArray(report.unavailable) || !report.unavailable.every(item => item != null && typeof item.label === 'string' && typeof item.reason === 'string') ||
      !Array.isArray(report.sourceStatus) || !report.sourceStatus.every(item => item != null && typeof item.category === 'string' && typeof item.readStatus === 'string')) {
    throw new Error('Invalid private response')
  }
  return report
}

function display(value: number | null, unit: string): string {
  if (value === null) return 'unavailable'
  return unit === 'seconds' ? `${number.format(value / 3600)} hours` : `${number.format(value)} ${unit}`
}

function ObservedChart({ series, oldest, newest }: { series: MetricSeries; oldest: string; newest: string }) {
  if (!series.points.length) return null
  // Activity totals are daily buckets of observed records only. Wellness keeps
  // separate measured points. Neither chart inserts zeros or bridges data gaps.
  const daily = new Map<string, number>()
  if (series.aggregation === 'sum') for (const point of series.points) daily.set(point.date, (daily.get(point.date) ?? 0) + point.value)
  const points = series.aggregation === 'sum' ? [...daily].map(([date, value]) => ({ date, value })) : series.points
  const values = points.map(point => point.value)
  const min = series.aggregation === 'sum' ? 0 : values.reduce((a, b) => Math.min(a, b), Infinity)
  const max = values.reduce((a, b) => Math.max(a, b), -Infinity)
  const dateSpan = Math.max(86400000, Date.parse(newest) - Date.parse(oldest))
  const x = (date: string) => 65 + (Date.parse(date) - Date.parse(oldest)) / dateSpan * 490
  const y = (value: number) => max === min ? (series.aggregation === 'sum' ? 150 : 85) : 150 - (value - min) / (max - min) * 125
  return <figure>
    <svg className="trend-chart" viewBox="0 0 620 200" role="img" aria-label={`${series.label}: observed values, ${series.unit}; no interpolated gaps`}>
      <line className="chart-axis" x1="65" x2="555" y1="150" y2="150" />
      <text x="10" y="25">{number.format(max)}</text><text x="10" y="150">{number.format(min)}</text>
      <text x="65" y="180">{oldest}</text><text x="555" y="180" textAnchor="end">{newest}</text>
      {points.map((point, index) => series.aggregation === 'sum' ?
        <rect className="chart-value" key={`${point.date}-${index}`} x={x(point.date) - 2} y={y(point.value)} width="4" height={Math.max(1, 150 - y(point.value))}>
          <title>{point.date}: {display(point.value, series.unit)}</title>
        </rect> : <circle className="chart-value" key={`${point.date}-${index}`} cx={x(point.date)} cy={y(point.value)} r="3">
          <title>{point.date}: {display(point.value, series.unit)}</title>
        </circle>)}
    </svg>
    <figcaption>{series.aggregation === 'sum' ? 'Daily totals of observed records' : 'Separate observed measurements'}; axis unit: {series.unit}. Gaps are not zero values.</figcaption>
  </figure>
}

function MetricCard({ series, comparison, oldest, newest, compact = false }: { series: MetricSeries; comparison?: Comparison; oldest: string; newest: string; compact?: boolean }) {
  const [evidence, setEvidence] = useState(false)
  const [page, setPage] = useState(0)
  const size = 25
  const slice = series.points.slice(page * size, (page + 1) * size)
  const [view, setView] = useState('chart')
  const point = series.points[Math.min(page, Math.max(0, series.points.length - 1))]
  if (compact) return <section className={`dashboard-tile metric-detail ${series.aggregation === 'sum' ? 'activity' : 'wellness'}`} aria-label={series.label}>
    <h3>{series.label}</h3>
    <nav className="section-tabs" aria-label="Metric detail sections">{['chart', 'comparison', 'evidence'].map(name =>
      <button key={name} type="button" aria-pressed={view === name} onClick={() => { setView(name); setPage(0) }}>{name}</button>)}</nav>
    {view === 'chart' && <><p className="tile-value">{display(series.value, series.unit)}</p>
      <p>{series.aggregation === 'mean' ? 'Mean of populated records' : 'Observed total'} · {series.sampleCount} records · {oldest}–{newest}</p>
      <ObservedChart series={series} oldest={oldest} newest={newest} /></>}
    {view === 'comparison' && <TextPages label="Comparison" text={`Previous: ${display(comparison?.previousValue ?? null, series.unit)}. ` +
      `Current: ${display(series.value, series.unit)}. Arithmetic change: ${display(comparison?.absoluteChange ?? null, series.unit)}. ` +
      `Percent change: ${comparison?.percentChange == null ? 'unavailable (including zero baseline)' : `${number.format(comparison.percentChange)}%`}; ${comparison?.previousSamples ?? 'unknown'} previous / ${series.sampleCount} current records. ` +
      `${series.sampleCount} populated records across ${series.observedDays}/${series.periodDays} dates; ${series.missingRecordValues} missing and ${series.rejectedValues} rejected values. ` +
      `Flags: ${comparison?.flags.join(', ') || 'none'}. Means weight each populated record equally, not each date. Missing records do not prove inactivity or illness. No significance, cause or prescription is inferred.`} />}
    {view === 'evidence' && <>{point ? <><TextPages key={`${series.key}-${page}`} label="Evidence details" text={`${point.date}: ${point.value} ${point.unit}. Source: ${point.source}; record: ${point.sourceRecordId}. ` +
      `Metric origin: ${point.upstreamSource ?? 'unknown'}; record labels: ${point.recordOrigins.join(', ') || 'unknown'}. ` +
      `Time context: ${point.timeContext}; zone: ${point.timeZone ?? 'unknown'}; instant: ${point.startInstant ?? 'unknown'}.`} />
      <nav className="tile-pagination" aria-label="Evidence records"><button className="secondary" disabled={!page} onClick={() => setPage(page - 1)}>Previous record</button>
        <span>{page + 1}/{series.points.length}</span><button className="secondary" disabled={page + 1 >= series.points.length} onClick={() => setPage(page + 1)}>Next record</button></nav></> : <p>No populated source evidence. Missing is not zero.</p>}</>}
  </section>
  return <section className="metric-card" aria-label={series.label}>
    <h4>{series.label}</h4>
    <p className="metric-value">{series.aggregation === 'mean' ? 'Mean of measured records: ' : 'Observed total: '}{display(series.value, series.unit)}</p>
    <p>{series.sampleCount} measured records across {series.observedDays}/{series.periodDays} dates.
      {' '}{series.missingRecordValues} records missing this value; {series.rejectedValues} invalid values excluded.</p>
    {comparison && <p>{comparison.absoluteChange === null ? 'Comparison unavailable: one or both periods lack this metric.' :
      `Observed change: ${comparison.absoluteChange > 0 ? '+' : ''}${display(comparison.absoluteChange, series.unit)}${comparison.percentChange === null ? ' (percentage unavailable)' : ` (${number.format(comparison.percentChange)}%)`}.`}
      {' '}Previous: {display(comparison.previousValue, series.unit)}; {comparison.currentSamples} current / {comparison.previousSamples} previous records.
      {comparison.flags.includes('sparse_comparison') && ' Sparse records: no confident personal trend is claimed.'}
      {comparison.flags.includes('partial_metric_coverage') && ' Partial metric coverage; totals are not complete-history estimates.'}</p>}
    <ObservedChart series={series} oldest={oldest} newest={newest} />
    {series.points.length > 0 && <button type="button" className="secondary" onClick={() => setEvidence(!evidence)}>
      {evidence ? 'Hide' : 'Show'} evidence: {series.label}
    </button>}
    {evidence && <div className="table-scroll">
      <table><caption>Source evidence: {series.label}, records {page * size + 1}–{page * size + slice.length} of {series.points.length}</caption>
        <thead><tr><th scope="col">Source date</th><th scope="col">Value (original unit)</th><th scope="col">Source / record</th><th scope="col">Origin / time context</th></tr></thead>
        <tbody>{slice.map((point, index) => <tr key={`${point.source}-${point.sourceRecordId}-${index}`}>
          <td>{point.date}</td><td>{number.format(point.value)} {point.unit}</td>
          <td>{point.source} / {point.sourceRecordId}</td><td>Metric origin: {point.upstreamSource ?? 'unknown'};
            {' '}record labels: {point.recordOrigins.join(', ') || 'unknown'};
            {' '}{point.timeContext}; zone: {point.timeZone ?? 'unknown'}; instant: {point.startInstant ?? 'unknown'}</td>
        </tr>)}</tbody>
      </table>
      <button type="button" className="secondary" disabled={page === 0} onClick={() => setPage(page - 1)}>Previous evidence page</button>
      <button type="button" className="secondary" disabled={(page + 1) * size >= series.points.length} onClick={() => setPage(page + 1)}>Next evidence page</button>
    </div>}
  </section>
}

export function TrendPanel({ revision = 0, csrfToken, compact = false }: { revision?: number; csrfToken?: string; compact?: boolean }) {
  const today = new Date().toISOString().slice(0, 10)
  const earlier = new Date(Date.now() - 27 * 86400000).toISOString().slice(0, 10)
  const [oldest, setOldest] = useState(earlier)
  const [newest, setNewest] = useState(today)
  const [sport, setSport] = useState('')
  const [query, setQuery] = useState({ oldest: earlier, newest: today, sport: '', refresh: 0 })
  const [report, setReport] = useState<TrendReport | null>(null)
  const [loaded, setLoaded] = useState<{ query: typeof query; revision: number } | null>(null)
  const [availableSports, setAvailableSports] = useState<string[]>([])
  const [message, setMessage] = useState('')
  const [busy, setBusy] = useState(true)
  const [digest, setDigest] = useState<string | null>(null)
  const [view, setView] = useState('metrics')
  const [metric, setMetric] = useState(0)

  useEffect(() => {
    const controller = new AbortController()
    setReport(null)
    setDigest(null)
    setBusy(true)
    setMessage('')
    async function load() {
      try {
        const params = new URLSearchParams({ oldest: query.oldest, newest: query.newest })
        if (query.sport) params.set('sport', query.sport)
        const response = await fetch(`/api/trends?${params}`, { cache: 'no-store', signal: controller.signal })
        if (!response.ok) throw new Error('History unavailable')
        const result = reportFrom(await response.json())
        if (result.current.oldest !== query.oldest || result.current.newest !== query.newest || result.selectedSport !== (query.sport || null)) {
          throw new Error('Mismatched private period')
        }
        if (!controller.signal.aborted) {
          setReport(result); setLoaded({ query, revision }); setAvailableSports(result.availableSports)
          setDigest(response.headers?.get('X-Evidence-Report-Sha256') ?? null)
        }
      } catch {
        if (!controller.signal.aborted) setMessage('History unavailable. Reconnect or sign in again; try a shorter period if needed.')
      } finally {
        if (!controller.signal.aborted) setBusy(false)
      }
    }
    void load()
    return () => controller.abort()
  }, [query, revision])

  function selectPeriod(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const days = (Date.parse(newest) - Date.parse(oldest)) / 86400000 + 1
    if (!validDate(oldest) || !validDate(newest) || days < 1 || days > 366 || newest > today) {
      setMessage('Choose an ordered period of 1–366 inclusive dates, ending no later than today.')
      return
    }
    setQuery({ oldest, newest, sport, refresh: query.refresh + 1 })
  }

  return <section className="panel" aria-labelledby="trends-heading">
    <h2 id="trends-heading">Historical trends</h2>
    {compact && <nav className="section-tabs" aria-label="Trend sections">{['metrics', 'period', 'facts', 'prototype', 'coverage'].map(name =>
      <button type="button" key={name} aria-pressed={view === name} onClick={() => setView(name)}>{name === 'prototype' ? 'Experimental AI prototype' : name}</button>)}</nav>}
    {!compact && <p>Factual imported records, not AI analysis. Recorded activity time is not Garmin intensity minutes.
      Loading charts makes no upstream read or model call. Experimental observations below require a separate request.</p>
    }
    {(!compact || view === 'period') && <form className="compact-form" onSubmit={selectPeriod}>
      <label htmlFor="trend-oldest">Trend first date</label><input id="trend-oldest" type="date" required value={oldest} onChange={event => setOldest(event.target.value)} />
      <label htmlFor="trend-newest">Trend last date</label><input id="trend-newest" type="date" required max={today} value={newest} onChange={event => setNewest(event.target.value)} />
      <label htmlFor="trend-sport">Activity sport</label><select id="trend-sport" value={sport} onChange={event => setSport(event.target.value)}>
        <option value="">All recorded sports</option>{availableSports.map(name => <option key={name} value={name}>{name}</option>)}
        {sport && !availableSports.includes(sport) && <option value={sport}>{sport} (no records in range)</option>}
      </select>
      <button type="submit">Show period / refresh</button>
    </form>}
    {busy && <p aria-live="polite">Loading private history…</p>}
    {message && <p aria-live="polite">{message}</p>}
    {compact && report && loaded?.query === query && loaded.revision === revision && <div key={`${query.refresh}-${revision}`}>
      {view === 'metrics' && (() => {
        const cards = [...report.current.sports.flatMap(group => group.metrics.map(series => ({ series, sport: group.sport }))),
          ...report.current.wellness.map(series => ({ series, sport: null }))]
        const selected = Math.min(metric, cards.length - 1)
        const card = cards[selected]
        return card ? <><label htmlFor="compact-metric">Metric / sport</label><select id="compact-metric" value={selected} onChange={event => setMetric(Number(event.target.value))}>
          {cards.map((item, index) => <option key={`${item.sport}-${item.series.key}`} value={index}>{item.sport ?? 'Wellness'} · {item.series.label}</option>)}</select>
          <MetricCard key={`${card.sport}-${card.series.key}`} compact series={card.series} oldest={report.current.oldest} newest={report.current.newest}
            comparison={report.comparisons.find(item => item.sport === card.sport && item.key === card.series.key)} /></> : <p>No imported metrics in this range. Missing imports do not prove inactivity.</p>
      })()}
      {view === 'facts' && <FactualReviewPanel compact report={report} digest={digest} />}
      {view === 'prototype' && (csrfToken ? <AnalysisPanel compact csrfToken={csrfToken} report={report} digest={digest} /> : <p>No authorized model controls.</p>)}
      {view === 'coverage' && <TextPages label="Coverage" text={`Selected ${report.current.oldest}–${report.current.newest}; previous ${report.previous.oldest}–${report.previous.newest}. ` +
        `${report.current.activityRecords} imported activities and ${report.current.wellnessRecords} wellness records. Current coverage flags: ${report.current.flags.join(', ') || 'none'}; previous: ${report.previous.flags.join(', ') || 'none'}. ${report.dateBasis}. ` +
        `Recorded moving/elapsed time is not Garmin intensity minutes. Missing imports do not prove missed activity. ` +
        report.sourceStatus.map(status => `${status.category}: ${status.readStatus}, latest stored date ${status.latestObservedDate ?? 'unknown'}, age ${status.latestObservedAgeDays ?? 'unknown'} days. Garmin-to-Intervals.icu freshness is unverified. `).join('') +
        report.unavailable.map(item => `${item.label}: unavailable; ${item.reason} `).join('')} />}
    </div>}
    {!compact && report && loaded?.query === query && loaded.revision === revision && <div key={`${query.refresh}-${revision}`}>
      <p>Selected: {report.current.oldest}–{report.current.newest} ({report.current.days} dates).
        {' '}Compared with preceding equal-length period: {report.previous.oldest}–{report.previous.newest}.</p>
      <p>{report.dateBasis} Wellness includes all sports. Means weight each populated source record equally, not each date.
        Arithmetic changes are observations, not significance, diagnoses, causes, or prescriptions.</p>
      <p>{report.current.activityRecords} imported activities; {report.current.wellnessRecords} imported wellness records in the selected period.
        Missing imports do not prove missed activity; missing health values do not imply illness.</p>
      {report.current.flags.includes('selected_period_observed_gap_over_7_days') && <p>The selected period has an observed gap over seven days at its end; no upstream cause is verified.</p>}
      {report.sourceStatus.map(status => <p key={status.category}>{status.category}: latest read status {status.readStatus};
        {' '}latest stored date {status.latestObservedDate ?? 'unavailable'}. Garmin-to-Intervals.icu freshness is unknown.
        {status.latestObservedAgeDays !== null && status.latestObservedAgeDays > 7 && ' Latest stored record is over seven days old.'}</p>)}
      <h3>Activities by sport</h3>
      {report.current.activityRecords === 0 && <p>No imported activities for this sport/period. This is not proof of inactivity.</p>}
      {report.current.sports.map(group => <section key={group.sport} aria-label={`${group.sport} trends`}>
        <h3>{group.sport}</h3><div className="metric-grid">{group.metrics.map(series => <MetricCard key={series.key} series={series}
          oldest={report.current.oldest} newest={report.current.newest} comparison={report.comparisons.find(item => item.sport === group.sport && item.key === series.key)} />)}</div>
      </section>)}
      <h3>Wellness measurements</h3>
      {report.current.wellnessRecords === 0 && <p>No imported wellness records for this period.</p>}
      <div className="metric-grid">{report.current.wellness.map(series => <MetricCard key={series.key} series={series}
        oldest={report.current.oldest} newest={report.current.newest} comparison={report.comparisons.find(item => item.sport === null && item.key === series.key)} />)}</div>
      <h3>Unavailable Garmin scores</h3>
      <ul>{report.unavailable.map(metric => <li key={metric.key}>{metric.label}: unavailable. {metric.reason}</li>)}</ul>
      <FactualReviewPanel report={report} digest={digest} />
      {csrfToken ? <AnalysisPanel csrfToken={csrfToken} report={report} digest={digest} /> :
        <p>AI analysis unavailable: no usable model is selected. Charts remain independent of inference; no hosted fallback.</p>}
    </div>}
  </section>
}
