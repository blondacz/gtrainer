export type ObservedValue = {
  source: string; sourceRecordId: string; category: string; field: string; date: string; value: number; unit: string
  upstreamSource: string | null; recordOrigins: string[]; timeContext: string; timeZone: string | null; startInstant: string | null
}
export type MetricSeries = {
  key: string; label: string; unit: string; aggregation: 'sum' | 'mean'; value: number | null
  sampleCount: number; observedDays: number; periodDays: number; missingRecordValues: number; rejectedValues: number; points: ObservedValue[]
}
export type Period = { oldest: string; newest: string; days: number; activityRecords: number; wellnessRecords: number
  sports: { sport: string; metrics: MetricSeries[] }[]; wellness: MetricSeries[]; flags: string[] }
export type Comparison = { sport: string | null; key: string; label: string; unit: string; currentValue: number | null
  previousValue: number | null; absoluteChange: number | null; percentChange: number | null; currentSamples: number; previousSamples: number; flags: string[] }
export const metricUnits: Record<string, string> = { activityCount: 'count', movingTime: 'seconds', elapsedTime: 'seconds', calories: 'kcal',
  intervalsTrainingLoad: 'Intervals.icu load', weight: 'kg', vo2max: 'mL/kg/min', hrv: 'ms', sleepSecs: 'seconds', hrvSDNN: 'ms',
  bodyFat: 'percent', restingHR: 'beats/minute', sleepScore: 'source sleep score', steps: 'count', atl: 'Intervals.icu load', ctl: 'Intervals.icu load' }
export const sessionUnits: Record<string, string> = { movingTime: 'seconds', elapsedTime: 'seconds', distance: 'metres', calories: 'kcal',
  averageHeartRate: 'beats/minute', intervalsTrainingLoad: 'Intervals.icu load' }
