import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { MetricCard } from './MetricCard'
import type { MetricSeries } from './ReviewMetrics'

const point = (date: string, value: number, id: string) => ({ source: 'device', sourceRecordId: id, category: 'activity', field: 'movingTime', date, value, unit: 'seconds', upstreamSource: 'import', recordOrigins: ['original'], timeContext: 'local', timeZone: 'UTC', startInstant: `${date}T10:00:00Z` })
const series = (aggregation: 'sum' | 'mean', points: ReturnType<typeof point>[], value: number): MetricSeries => ({ key: 'movingTime', label: 'Moving time', unit: 'seconds', aggregation, value, sampleCount: points.length, observedDays: new Set(points.map(p => p.date)).size, periodDays: 4, missingRecordValues: 1, rejectedValues: 2, points })

describe('MetricCard', () => {
  it('aggregates activity by date while charting only observed dates', () => {
    const { container } = render(<MetricCard series={series('sum', [point('2025-01-01', 10, 'a'), point('2025-01-01', 20, 'b'), point('2025-01-04', 40, 'c')], 70)} oldest="2025-01-01" newest="2025-01-04" />)
    const svg = container.querySelector('svg')!
    expect(svg.querySelectorAll('rect')).toHaveLength(2)
    expect(svg.querySelectorAll('circle')).toHaveLength(0)
    expect(svg.textContent).toContain('2025-01-01: 30 seconds')
    expect(svg.textContent).toContain('2025-01-04: 40 seconds')
  })

  it('keeps wellness observations separate and paginates exact provenance', () => {
    const points = Array.from({ length: 26 }, (_, i) => point(`2025-01-${String(i+1).padStart(2,'0')}`, i+1, `record-${i+1}`))
    const { container } = render(<MetricCard series={series('mean', points, 13.5)} oldest="2025-01-01" newest="2025-01-26" />)
    expect(container.querySelectorAll('circle')).toHaveLength(26)
    fireEvent.click(screen.getByRole('button', { name: 'Evidence' }))
    expect(screen.getByText('Source evidence: records 1–25 of 26')).toBeTruthy()
    expect(screen.getByText('record-1')).toBeTruthy()
    expect(screen.queryByText('record-26')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'Next' }))
    expect(screen.getByText('Source evidence: records 26–26 of 26')).toBeTruthy()
    expect(screen.getByText('record-26')).toBeTruthy()
    expect(screen.getByText('import')).toBeTruthy()
    expect(screen.getByText('original')).toBeTruthy()
    expect(screen.getByText('2025-01-26T10:00:00Z (local)')).toBeTruthy()
  })

  it('uses comparable current value, not full selected-period total', () => {
    const comparison = { sport: null, key: 'movingTime', label: 'Moving time', unit: 'seconds', currentValue: 600, previousValue: 300, absoluteChange: 300, percentChange: 100, currentSamples: 2, previousSamples: 1, flags: ['sparse_comparison'] }
    render(<MetricCard series={series('sum', [point('2025-01-01', 1200, 'full')], 1200)} comparison={comparison} oldest="2025-01-01" newest="2025-01-04" comparisonDates="2024-12-28–2024-12-31 / 2025-01-01–2025-01-04" />)
    fireEvent.click(screen.getByRole('button', { name: 'Comparison' }))
    expect(screen.getByText(/Selected-period value: 0.33 hours\. Comparable current: 0.17 hours; previous: 0.08 hours\./)).toBeTruthy()
    expect(screen.getByText(/Change: 0.08 hours; percent: 100%/)).toBeTruthy()
  })
})
