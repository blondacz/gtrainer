import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { TextPages } from './TilePrimitives'

describe('lossless private-text paging', () => {
  it('preserves whitespace and unicode without truncation or replacement', () => {
    const text = '  Synthetic 水🚣\n\t'.repeat(50)
    const view = render(<TextPages text={text} label="Evidence" />)
    let collected = ''
    for (;;) {
      collected += view.container.querySelector('.private-text')!.textContent
      const next = screen.getByRole('button', { name: 'Next evidence' })
      if (next.hasAttribute('disabled')) break
      fireEvent.click(next)
    }
    expect(collected).toBe(text)
    expect(collected).not.toContain('\uFFFD')
    fireEvent.click(screen.getByRole('button', { name: 'Previous evidence' }))
    expect(screen.getByRole('button', { name: 'Next evidence' })).not.toBeDisabled()
  })
  it('keeps short text simple and clamps a presentation page when text shrinks', () => {
    const view = render(<TextPages text={'a'.repeat(450)} />)
    fireEvent.click(screen.getByRole('button', { name: 'Next details' }))
    fireEvent.click(screen.getByRole('button', { name: 'Next details' }))
    view.rerender(<TextPages text="Synthetic short" />)
    expect(screen.getByText('Synthetic short')).toBeInTheDocument()
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
  })
})
