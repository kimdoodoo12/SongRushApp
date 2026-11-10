// Cycle 111 (D-124): the disc is drawn by a port of the mockup's drawPlayer()
// (docs/design/108-ui-design-reference.html), as the module
// src/components/drawPlayer.js.
//
// Contract with the implementer:
// - export function drawPlayer(canvas, mood): draws the mockup's player
//   object for `mood` on `canvas` through canvas.getContext('2d'), sized by
//   canvas.width and canvas.height (520 x 520, no DPR scaling).
// - Every one of the eight moods draws without throwing. Each mood except
//   pixel draws paths (beginPath and arc, as in the mockup); pixel draws its
//   36 x 36 offscreen canvas onto the disc with drawImage.
// - When getContext('2d') returns null (jsdom has no canvas), it draws
//   nothing and does not throw.
// The 2d context here is a stub that records every method call; any method
// returns a gradient-like object, so createRadialGradient(...).addColorStop
// and the like work.
import { describe, it, expect, vi, afterEach } from 'vitest'
import { drawPlayer } from './drawPlayer.js'

const MOODS = ['sunset', 'rain', 'sakura', 'neon', 'chalk', 'pixel', 'matsuri', 'manga']
const PATH_MOODS = MOODS.filter((m) => m !== 'pixel')

afterEach(() => {
  vi.restoreAllMocks()
})

// A recording 2d context: calls is the list of [method, args]; property
// writes (fillStyle, lineWidth, ...) are kept on the object.
function stubContext(canvas) {
  const calls = []
  const props = { canvas }
  const ctx = new Proxy(props, {
    get(target, prop) {
      if (prop in target) return target[prop]
      if (typeof prop !== 'string' || prop === 'then') return undefined
      return (...args) => {
        calls.push([prop, args])
        return { addColorStop: () => {} }
      }
    },
    set(target, prop, value) {
      target[prop] = value
      return true
    },
  })
  return { ctx, calls }
}

// A 520 x 520 canvas whose getContext('2d') is a recording stub. Any other
// canvas made meanwhile (pixel's offscreen one) gets its own stub too.
function stubCanvas() {
  const canvas = document.createElement('canvas')
  canvas.width = 520
  canvas.height = 520
  const main = stubContext(canvas)
  canvas.getContext = (kind) => (kind === '2d' ? main.ctx : null)
  vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockImplementation(function getContext(kind) {
    return kind === '2d' ? stubContext(this).ctx : null
  })
  return { canvas, calls: main.calls }
}

function names(calls) {
  return calls.map(([name]) => name)
}

describe('drawPlayer(canvas, mood)', () => {
  it('is a named export function', () => {
    expect(typeof drawPlayer).toBe('function')
  })

  it.each(MOODS)('%s: draws without throwing and makes at least one drawing call', (mood) => {
    const { canvas, calls } = stubCanvas()
    expect(() => drawPlayer(canvas, mood)).not.toThrow()
    const drawn = names(calls).filter((n) => ['fill', 'stroke', 'fillRect', 'drawImage'].includes(n))
    expect(drawn.length, `${mood}: fill, stroke, fillRect or drawImage on the disc`).toBeGreaterThan(0)
  })

  it.each(PATH_MOODS)('%s: draws at least one path (beginPath and arc) on the disc', (mood) => {
    const { canvas, calls } = stubCanvas()
    drawPlayer(canvas, mood)
    expect(names(calls)).toContain('beginPath')
    expect(names(calls)).toContain('arc')
  })

  it('pixel: draws its offscreen canvas over the whole 520 x 520 disc', () => {
    const { canvas, calls } = stubCanvas()
    drawPlayer(canvas, 'pixel')
    const images = calls.filter(([n]) => n === 'drawImage')
    expect(images.length).toBeGreaterThan(0)
    const [source, x, y, w, h] = images[0][1]
    expect(source instanceof HTMLCanvasElement, 'the source is a canvas').toBe(true)
    expect([x, y, w, h]).toEqual([0, 0, 520, 520])
  })

  it('clears the canvas first, so a mood change does not draw over the old disc', () => {
    for (const mood of MOODS) {
      const { canvas, calls } = stubCanvas()
      drawPlayer(canvas, mood)
      expect(calls[0], mood).toEqual(['clearRect', [0, 0, 520, 520]])
    }
  })

  it.each(MOODS)('%s: with getContext returning null it draws nothing and does not throw', (mood) => {
    const canvas = document.createElement('canvas')
    canvas.width = 520
    canvas.height = 520
    const getContext = vi.fn(() => null)
    canvas.getContext = getContext
    expect(() => drawPlayer(canvas, mood)).not.toThrow()
    expect(getContext).toHaveBeenCalled()
  })
})
