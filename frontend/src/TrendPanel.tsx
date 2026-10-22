import { ScopedReviewControls } from './ScopedReviewControls'
/** Historical trends are now the single canonical, explicitly prepared review. */
export function TrendPanel({ revision = 0, csrfToken }: { revision?: number; csrfToken?: string }) {
  return <section className="panel" aria-labelledby="trends-heading">
    <h2 id="trends-heading">Historical trends</h2>
    <ScopedReviewControls csrfToken={csrfToken} revision={revision} idPrefix="trend-scoped" />
  </section>
}
