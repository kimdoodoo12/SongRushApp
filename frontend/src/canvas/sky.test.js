// Cycle 115 (D-124): #sky, the per-mood background scene, ported from the
// mockup's part, initSky, drawSky and firework
// (docs/design/108-ui-design-reference.html). Spec docs/cycles/115-canvases.md,
// with its "Spec review" section.
//
// Contract with the implementer (src/canvas/index.js re-exports all of it;
// App imports the canvas code only through that file):
// - createCanvasState(): a fresh object holding every piece of the mockup's
//   module-level drawing state (parts, scene, gridOff, chalk*, SENB, sen*,
//   ...). One per mount, one per test; two states never share anything.
// - initSky(state, canvas, mood, dpr): the mockup's initSky for `mood`.
//   Sizes the canvas to innerWidth*dpr x innerHeight*dpr, rebuilds the
//   particles and the scene, and remembers mood and dpr in the state for
//   drawSky and fireworkAt. It does not draw (the caller draws the still
//   frame under reduced motion). The manga tone is made on a
//   document.createElement('canvas'); a null 2d context there skips it.
// - drawSky(state, ctx, mood, t, move, opts): one frame of the mockup's
//   drawSky(move). t is the frame time in ms (the mockup's performance.now()),
//   opts is optional: { playing = false (the mockup's phase==='play'),
//   reduce = false, focus = null (the deck's box in CSS px, {left, top,
//   width, height}, for manga's speed lines; null draws none) }. Size and
//   dpr come from ctx.canvas and the last initSky. A null ctx draws nothing
//   and does not throw.
// - fireworkAt(state, x, y, big): the mockup's firework: 90 sparks (big) or
//   46 into the matsuri scene; nothing in any other mood.
import { describe, it, expect, vi, afterEach } from 'vitest'
import { createCanvasState, initSky, drawSky, fireworkAt } from './index.js'
import { createRecordingContext } from '../testing/fakes.js'

const MOODS = ['sunset', 'rain', 'sakura', 'neon', 'chalk', 'pixel', 'matsuri', 'manga']
const DRAW_CALLS = new Set(['fill', 'fillRect', 'stroke', 'strokeRect', 'fillText', 'strokeText', 'drawImage'])
const FOCUS = { left: 300, top: 200, width: 400, height: 400 }

afterEach(() => {
  vi.restoreAllMocks()
})

// Every canvas element's getContext('2d') returns one recording context
// (offscreen canvases too, for manga's tone).
function stubContexts() {
  const made = new Map()
  vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockImplementation(function getContext() {
    if (!made.has(this)) made.set(this, createRecordingContext(this))
    return made.get(this)
  })
  return made
}

function draws(ctx) {
  return ctx.calls.filter(([name]) => DRAW_CALLS.has(name)).length
}

function count(ctx, name) {
  return ctx.calls.filter(([n]) => n === name).length
}

// A sized sky canvas, its state and a recording context for `mood`.
function skyFor(mood, dpr = 1) {
  stubContexts()
  const canvas = document.createElement('canvas')
  const state = createCanvasState()
  initSky(state, canvas, mood, dpr)
  const ctx = createRecordingContext(canvas)
  return { canvas, state, ctx }
}

// The matsuri sparks drawn in a frame: 2.2*dpr squares.
function sparks(ctx, dpr = 1) {
  return ctx.calls.filter(([n, a]) => n === 'fillRect' && a[2] === 2.2 * dpr && a[3] === 2.2 * dpr).length
}

describe('sky', () => {
  it('createCanvasState returns a fresh object each time', () => {
    const a = createCanvasState()
    const b = createCanvasState()
    expect(a).toBeTypeOf('object')
    expect(a).not.toBeNull()
    expect(a).not.toBe(b)
  })

  for (const mood of MOODS) {
    it(`${mood}: initSky sizes the canvas and drawSky draws, still and moving, idle and playing`, () => {
      const { canvas, state, ctx } = skyFor(mood)
      expect(canvas.width).toBe(Math.round(window.innerWidth))
      expect(canvas.height).toBe(Math.round(window.innerHeight))
      expect(() => drawSky(state, ctx, mood, 1000, false)).not.toThrow()
      expect(draws(ctx)).toBeGreaterThan(0)
      ctx.calls.length = 0
      expect(() => drawSky(state, ctx, mood, 1016, true)).not.toThrow()
      expect(draws(ctx)).toBeGreaterThan(0)
      ctx.calls.length = 0
      expect(() =>
        drawSky(state, ctx, mood, 1032, true, { playing: true, reduce: false, focus: FOCUS }),
      ).not.toThrow()
      expect(draws(ctx)).toBeGreaterThan(0)
      ctx.calls.length = 0
      for (let i = 0; i < 120; i += 1) drawSky(state, ctx, mood, 1048 + i * 16, true, { playing: i % 2 === 0 })
      expect(draws(ctx)).toBeGreaterThan(0)
    })
  }

  it('initSky sizes the canvas by the device pixel ratio', () => {
    const { canvas } = skyFor('neon', 2)
    expect(canvas.width).toBe(Math.round(window.innerWidth * 2))
    expect(canvas.height).toBe(Math.round(window.innerHeight * 2))
  })

  it('rain: one drop per 1e4 css px^2 x 1.1 (at most 220), each a stroke, over 28 bokeh', () => {
    const { state, ctx } = skyFor('rain')
    drawSky(state, ctx, 'rain', 1000, false)
    const area = (window.innerWidth * window.innerHeight) / 1e4
    expect(count(ctx, 'stroke')).toBe(Math.min(220, Math.round(area * 1.1)))
  })

  it('sakura: 0.3 falling petals per 1e4 css px^2 (at most 220), each an ellipse', () => {
    const { state, ctx } = skyFor('sakura')
    drawSky(state, ctx, 'sakura', 1000, false)
    const area = (window.innerWidth * window.innerHeight) / 1e4
    // The moon is an arc; every petal is one ellipse.
    expect(count(ctx, 'ellipse')).toBe(Math.min(220, Math.round(area * 0.3)))
  })

  it('with a null context nothing throws, in every mood', () => {
    for (const mood of MOODS) {
      const state = createCanvasState()
      const canvas = document.createElement('canvas')
      vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue(null)
      expect(() => initSky(state, canvas, mood, 1)).not.toThrow()
      expect(() => drawSky(state, null, mood, 1000, false)).not.toThrow()
      expect(() => drawSky(state, null, mood, 1016, true, { playing: true, focus: FOCUS })).not.toThrow()
      expect(() => fireworkAt(state, 10, 10, true)).not.toThrow()
      vi.restoreAllMocks()
    }
  })

  it('matsuri: fireworkAt adds 90 sparks (big) or 46', () => {
    const { state, ctx } = skyFor('matsuri')
    drawSky(state, ctx, 'matsuri', 1000, false)
    expect(sparks(ctx)).toBe(0)
    fireworkAt(state, 200, 150, true)
    ctx.calls.length = 0
    drawSky(state, ctx, 'matsuri', 1016, false)
    expect(sparks(ctx)).toBe(90)
    fireworkAt(state, 400, 150, false)
    ctx.calls.length = 0
    drawSky(state, ctx, 'matsuri', 1032, false)
    expect(sparks(ctx)).toBe(136)
  })

  it('fireworkAt does nothing outside matsuri', () => {
    const { state, ctx } = skyFor('sunset')
    expect(() => fireworkAt(state, 200, 150, true)).not.toThrow()
    drawSky(state, ctx, 'sunset', 1000, false)
    expect(sparks(ctx)).toBe(0)
  })

  it('states are isolated: sparks in one state never show in another', () => {
    const a = skyFor('matsuri')
    const b = skyFor('matsuri')
    fireworkAt(a.state, 200, 150, true)
    drawSky(b.state, b.ctx, 'matsuri', 1000, false)
    expect(sparks(b.ctx)).toBe(0)
    drawSky(a.state, a.ctx, 'matsuri', 1000, false)
    expect(sparks(a.ctx)).toBe(90)
  })

  it('a second initSky (a mood change) rebuilds the scene: matsuri sparks are gone', () => {
    const { canvas, state, ctx } = skyFor('matsuri')
    fireworkAt(state, 200, 150, true)
    initSky(state, canvas, 'neon', 1)
    initSky(state, canvas, 'matsuri', 1)
    drawSky(state, ctx, 'matsuri', 1000, false)
    expect(sparks(ctx)).toBe(0)
  })
})
