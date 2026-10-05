export async function developmentRead<T>(path: string, signal?: AbortSignal): Promise<T> {
  const response = await fetch(path, { credentials: 'same-origin', cache: 'no-store', signal })
  if (!response.ok) throw new Error('Development inspection unavailable')
  return response.json() as Promise<T>
}
export async function developmentWrite<T = unknown>(path: string, body: unknown, csrf: string): Promise<T> {
  const response = await fetch(path, { method: 'POST', credentials: 'same-origin', cache: 'no-store',
    headers: { 'Content-Type': 'application/json', ...(csrf ? { 'X-CSRF-Token': csrf } : {}) }, body: JSON.stringify(body) })
  if (!response.ok) throw new Error('Development action refused')
  return response.json() as Promise<T>
}
