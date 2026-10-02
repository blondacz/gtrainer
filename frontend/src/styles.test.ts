import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'

const css = readFileSync('src/styles.css', 'utf8')

const luminance = (hex: string) => {
  const components = [1, 3, 5].map(index => parseInt(hex.slice(index, index + 2), 16) / 255)
    .map(value => value <= .04045 ? value / 12.92 : ((value + .055) / 1.055) ** 2.4)
  return components.reduce((sum, value, index) => sum + value * [.2126, .7152, .0722][index], 0)
}

describe('reference-inspired dashboard palette', () => {
  it('keeps the page white, non-tile text neutral and accent actions orange, not green', () => {
    const root = css.match(/:root\s*\{([^}]+)\}/)![1]
    expect(root).toContain('background: #ffffff')
    expect(root).toContain('color: #18202a')
    expect(root).toContain('color-scheme: light')
    expect(css).toContain('button { border: 0; background: #c4550b; color: #fff;')
    for (const retired of ['#17342e', '#526a60', '#287b55']) expect(css).not.toContain(retired)
  })

  it('uses white tile text on saturated category backgrounds with readable normal-text contrast', () => {
    const tileRule = css.split('\n').find(line => line.startsWith('.dashboard-tile, .review-tile {'))!
    expect(tileRule).toContain('color: #fff')
    const selectors = ['.dashboard-tile.activity', '.dashboard-tile.wellness', '.dashboard-tile.events, .events-tile',
      '.dashboard-tile.reviews, .review-tile', '.dashboard-tile.source', '.dashboard-tile.unknown', '.dashboard-tile.factual-tile']
    const colors = selectors.map(selector => css.split('\n').find(line => line.startsWith(`${selector} {`))!.match(/background: (#[a-f0-9]{6})/)![1])
    expect(new Set(colors).size).toBeGreaterThanOrEqual(6)
    for (const color of colors) expect(1.05 / (luminance(color) + .05), color).toBeGreaterThanOrEqual(4.5)
    expect(css).toContain('.dashboard-tile .chart-value { fill: #fff; }')
    expect(css).toContain('.review-tile input, .review-tile select, .review-tile textarea { color: #fff;')
  })
})
