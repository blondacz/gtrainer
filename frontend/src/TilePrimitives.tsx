import { useState } from 'react'

/** Paging preserves every character without clipping long private evidence into a scrolling page. */
export function TextPages({ text, label = 'Details' }: { text: string; label?: string }) {
  const [page, setPage] = useState(0)
  const chunks: string[] = []
  let chunk = '', characters = 0, lines = 0
  for (const character of text) {
    if (characters === 220 || (character === '\n' && lines === 5)) { chunks.push(chunk); chunk = ''; characters = 0; lines = 0 }
    chunk += character; characters++; if (character === '\n') lines++
  }
  if (chunk || !chunks.length) chunks.push(chunk)
  const pages = chunks.length
  const current = Math.min(page, pages - 1)
  return <div className="text-pages">
    <p className="private-text">{chunks[current]}</p>
    {pages > 1 && <nav className="tile-pagination" aria-label={`${label} pages`}>
      <button className="secondary" disabled={!current} onClick={() => setPage(current - 1)}>Previous {label.toLowerCase()}</button>
      <span>{current + 1}/{pages}</span>
      <button className="secondary" disabled={current + 1 >= pages} onClick={() => setPage(current + 1)}>Next {label.toLowerCase()}</button>
    </nav>}
  </div>
}
