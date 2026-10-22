import { parseOptions, parseReport, type ScopedOptions, type ScopedReport, type ScopedRequest } from './ScopedReviewTypes'

const endpoint = '/api/reviews'

export async function loadReviewOptions(signal: AbortSignal, query?: { oldest: string; newest: string; offset?: number }): Promise<ScopedOptions> {
  const params = new URLSearchParams()
  if (query) { params.set('oldest', query.oldest); params.set('newest', query.newest); if (query.offset) params.set('offset', String(query.offset)) }
  const response = await fetch(`${endpoint}/options${params.size ? `?${params}` : ''}`, { cache: 'no-store', signal })
  if (!response.ok) throw new Error('Options unavailable')
  return parseOptions(await response.json())
}

export async function prepareReview(request: ScopedRequest, csrfToken: string, signal: AbortSignal, expectedZone?: string): Promise<ScopedReport> {
  const body = JSON.stringify(request)
  if (new TextEncoder().encode(body).length > 32_768) throw new Error('Request exceeds 32 KiB')
  const response = await fetch(`${endpoint}/prepare`, { method: 'POST', cache: 'no-store', signal,
    headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': csrfToken }, body })
  if (!response.ok) throw new Error('Review unavailable')
  return parseReport(await response.json(), request, expectedZone)
}
