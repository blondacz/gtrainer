import { useState } from 'react'
import type { Comparison, MetricSeries } from './ReviewMetrics'

const number = new Intl.NumberFormat(undefined, { maximumFractionDigits: 2 })
const display = (value: number | null, unit: string) => value === null ? 'unavailable' :
  unit === 'seconds' ? `${number.format(value / 3600)} hours` : `${number.format(value)} ${unit}`

export function MetricCard({ series, comparison, oldest, newest, comparisonDates }: {
  series: MetricSeries; comparison?: Comparison; oldest: string; newest: string; comparisonDates?: string
}) {
  const [view, setView] = useState<'Chart' | 'Comparison' | 'Evidence'>('Chart')
  const [page, setPage] = useState(0)
  const daily = new Map<string, number>()
  if (series.aggregation === 'sum') for (const p of series.points) daily.set(p.date, (daily.get(p.date) ?? 0) + p.value)
  const observations = series.aggregation === 'sum' ? [...daily].map(([date, value]) => ({ date, value })) : series.points
  const chartPoints = observations.filter(p=>Number.isFinite(p.value))
  const values = chartPoints.map(p => p.value)
  const min = series.aggregation === 'sum' || !values.length ? 0 : values.reduce((a, b) => Math.min(a, b), Infinity)
  const max = values.length ? values.reduce((a, b) => Math.max(a, b), -Infinity) : 0
  const span = Math.max(86400000, Date.parse(newest) - Date.parse(oldest))
  const x = (date: string) => 65 + (Date.parse(date) - Date.parse(oldest)) / span * 490
  const y = (value: number) => max === min ? 150 : 150 - (value - min) / (max - min) * 125
  const records = series.points.slice(page * 25, page * 25 + 25)
  return <section className="metric-card" aria-label={series.label}>
    <h3>{series.label}</h3><nav aria-label="Metric views">{(['Chart', 'Comparison', 'Evidence'] as const).map(name =>
      <button key={name} type="button" aria-pressed={view === name} onClick={() => { setView(name); setPage(0) }}>{name}</button>)}</nav>
    {view === 'Chart' && <>
      <p>{series.aggregation === 'mean' ? 'Mean of populated records' : 'Observed total'}: {display(series.value, series.unit)}</p>
      <p>{series.sampleCount} records; {series.observedDays}/{series.periodDays} dates observed; {series.missingRecordValues} missing; {series.rejectedValues} rejected.</p>
       <svg className="trend-chart" viewBox="0 0 620 200" role="img" aria-label={`${series.label}: observed values, ${series.unit}; no interpolated gaps`}>
         <text x="10" y="25">{number.format(max)}</text><text x="10" y="150">{number.format(min)}</text>
        <line x1="65" x2="555" y1="150" y2="150"/><text x="65" y="180">{oldest}</text><text x="555" y="180" textAnchor="end">{newest}</text>
        {chartPoints.map((p, i) => series.aggregation === 'sum' ? <rect key={`${p.date}-${i}`} x={x(p.date)-2} y={y(p.value)} width="4" height={Math.max(1, 150-y(p.value))}>
          <title>{p.date}: {p.value} {series.unit}</title></rect> : <circle key={`${p.date}-${i}`} cx={x(p.date)} cy={y(p.value)} r="3"><title>{p.date}: {p.value} {series.unit}</title></circle>)}
       </svg><p>{series.aggregation === 'sum' ? 'Daily totals of observed records' : 'Separate observed measurements'}; axis: {series.unit}. Gaps are not zero. {observations.length !== chartPoints.length && 'Overflowing totals are unavailable; inspect source evidence.'}</p>
    </>}
    {view === 'Comparison' && <><p>Selected-period value: {display(series.value, series.unit)}. Comparable current: {display(comparison?.currentValue ?? null, series.unit)}; previous: {display(comparison?.previousValue ?? null, series.unit)}.</p>
      <p>Comparison windows: {comparisonDates ?? 'dates unavailable'}; selected: {oldest}–{newest}. Change: {display(comparison?.absoluteChange ?? null, series.unit)}; percent: {comparison?.percentChange == null ? 'unavailable (including zero baseline)' : `${number.format(comparison.percentChange)}%`}.</p>
      <p>{comparison?.currentSamples ?? 0} comparable current / {comparison?.previousSamples ?? 0} previous records. Full selected coverage: {series.observedDays}/{series.periodDays} dates; {series.missingRecordValues} missing and {series.rejectedValues} rejected. Flags: {comparison?.flags.join(', ') || 'none'}. Descriptive arithmetic only; no cause or prescription inferred.</p></>}
    {view === 'Evidence' && <>{series.points.length ? <><p>Source evidence: records {page*25+1}–{page*25+records.length} of {series.points.length}</p><div className="table-scroll"><table><thead><tr>{['Date','Record ID','Source','Value / unit','Upstream','Record origins','Time zone','Instant'].map(x => <th key={x}>{x}</th>)}</tr></thead>
      <tbody>{records.map((p,i) => <tr key={`${p.source}-${p.sourceRecordId}-${i}`}><td>{p.date}</td><td>{p.sourceRecordId}</td><td>{p.source}</td><td>{p.value} {p.unit}</td><td>{p.upstreamSource ?? 'unknown'}</td><td>{p.recordOrigins.join(', ') || 'unknown'}</td><td>{p.timeZone ?? 'unknown'}</td><td>{p.startInstant ?? 'unknown'} ({p.timeContext})</td></tr>)}</tbody></table></div>
      <button disabled={!page} onClick={() => setPage(page-1)}>Previous</button><button disabled={(page+1)*25 >= series.points.length} onClick={() => setPage(page+1)}>Next</button></> : <p>No populated source evidence. Missing is not zero.</p>}</>}
  </section>
}
