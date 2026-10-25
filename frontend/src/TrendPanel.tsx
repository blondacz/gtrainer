import { ScopedReviewControls, type ReviewDateRange } from './ScopedReviewControls'
/** Historical trends are now the single canonical, explicitly prepared review. */
export function TrendPanel({ revision = 0, csrfToken, initialRange }: { revision?: number; csrfToken?: string; initialRange?: ReviewDateRange | null }) {
  return <section className="panel" aria-labelledby="trends-heading">
    <h2 id="trends-heading">Historical trends</h2>
    <ScopedReviewControls csrfToken={csrfToken} revision={revision} idPrefix="trend-scoped" initialRange={initialRange} />
  </section>
}
