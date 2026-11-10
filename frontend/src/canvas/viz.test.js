// Cycle 115 (D-124): #viz, the visualizer around the disc, ported from the
// mockup's sizeViz, drawViz, chalkStaff with DOODLES and chalkPoly, and
// senbon (sakura's 170-petal storm)
// (docs/design/108-ui-design-reference.html). Spec
// docs/cycles/115-canvases.md, with its "Spec review" section.
//
// Contract with the implementer (src/canvas/index.js re-exports all of it):
// - sizeViz(canvas, dpr): the mockup's sizeViz: the canvas's
//   getBoundingClientRect() width and height times dpr.
// - drawViz(state, ctx, mood, t, opts): one frame of the mockup's
//   drawViz(t) for `mood` (its MOODS[mood].viz kind). t is the frame time in
//   ms. opts is optional: { live = false (a song is audible and the analyser
//   is there), levels = null (a Uint8Array of the analyser's byte frequency
//   data, the mockup's fd, read when live), reduce = false }. Size from
//   ctx.canvas; the colours from the --a1/--a2/--a3 custom properties of
//   <html>, as the mockup's css(). song.bpm is a fixed 120 (spec, item 1).
//   A null ctx draws nothing and does not throw.
// - state.chalkDood: the live chalk doodles (the mockup's chalkDood), each
//   with `born`, the t it was spawned at.
// - createCanvasState(): see sky.test.js.
import { describe, it, expect, vi, afterEach } from 'vitest'
import { createCanvasState, drawViz, sizeViz } from './index.js'
import { createRecordingContext } from '../testing/fakes.js'

const MOODS = ['sunset', 'rain', 'sakura', 'neon', 'chalk', 'pixel', 'matsuri', 'manga']
const DRAW_CALLS = new Set(['fill', 'fillRect', 'stroke', 'strokeRect', 'fillText', 'strokeText', 'drawImage'])
const SIZE = 480
const FRAME = 16

afterEach(() => {
  vi.restoreAllMocks()
})

function vizFor(size = SIZE) {
  const canvas = document.createElement('canvas')
  canvas.width = size
  canvas.height = size
  const ctx = createRecordingContext(canvas)
  return { canvas, ctx, state: createCanvasState() }
}

function levelsOf(value) {
  return new Uint8Array(128).fill(value)
}

// A spectrum loud in the low bins only (a kick).
function kick() {
  const levels = new Uint8Array(128)
  for (let i = 0; i < 8; i += 1) levels[i] = 255
  return levels
}

function draws(ctx) {
  return ctx.calls.filter(([name]) => DRAW_CALLS.has(name)).length
}

// The stroked paths, each as the number of lineTo calls since its beginPath.
function strokedPaths(ctx) {
  const paths = []
  let lines = 0
  for (const [name] of ctx.calls) {
    if (name === 'beginPath') lines = 0
    else if (name === 'lineTo') lines += 1
    else if (name === 'stroke') paths.push(lines)
  }
  return paths
}

describe('viz', () => {
  for (const mood of MOODS) {
    it(`${mood}: drawViz draws, idle and live, and over many frames`, () => {
      const { ctx, state } = vizFor()
      expect(() => drawViz(state, ctx, mood, 1000, { live: false })).not.toThrow()
      expect(draws(ctx)).toBeGreaterThan(0)
      ctx.calls.length = 0
      expect(() => drawViz(state, ctx, mood, 1016, { live: true, levels: levelsOf(160) })).not.toThrow()
      expect(draws(ctx)).toBeGreaterThan(0)
      ctx.calls.length = 0
      for (let i = 0; i < 300; i += 1) {
        const levels = i % 10 === 0 ? kick() : levelsOf(60 + (i % 7) * 20)
        drawViz(state, ctx, mood, 1032 + i * FRAME, { live: i < 200, levels })
      }
      expect(draws(ctx)).toBeGreaterThan(0)
      ctx.calls.length = 0
      expect(() => drawViz(state, ctx, mood, 9000, { live: true, levels: levelsOf(100), reduce: true })).not.toThrow()
      expect(draws(ctx)).toBeGreaterThan(0)
    })
  }

  it('drawViz without opts draws the idle frame', () => {
    const { ctx, state } = vizFor()
    expect(() => drawViz(state, ctx, 'sunset', 1000)).not.toThrow()
    expect(draws(ctx)).toBeGreaterThan(0)
  })

  it('with a null context nothing throws, in every mood', () => {
    for (const mood of MOODS) {
      const state = createCanvasState()
      expect(() => drawViz(state, null, mood, 1000, { live: false })).not.toThrow()
      expect(() => drawViz(state, null, mood, 1016, { live: true, levels: kick() })).not.toThrow()
    }
  })

  it('sizeViz sizes the canvas to its box times dpr', () => {
    const canvas = document.createElement('canvas')
    vi.spyOn(canvas, 'getBoundingClientRect').mockReturnValue({
      x: 0, y: 0, left: 0, top: 0, width: 300, height: 280, right: 300, bottom: 280, toJSON() {},
    })
    sizeViz(canvas, 2)
    expect(canvas.width).toBe(600)
    expect(canvas.height).toBe(560)
  })

  describe('chalk staff', () => {
    it('draws 5 staff lines (140 segments each), idle', () => {
      const { ctx, state } = vizFor()
      drawViz(state, ctx, 'chalk', 1000, { live: false })
      expect(strokedPaths(ctx).filter((n) => n === 140)).toHaveLength(5)
    })

    it('draws 5 staff lines while live, with notes', () => {
      const { ctx, state } = vizFor()
      for (let i = 0; i < 120; i += 1) drawViz(state, ctx, 'chalk', 1000 + i * FRAME, { live: true, levels: levelsOf(150) })
      ctx.calls.length = 0
      drawViz(state, ctx, 'chalk', 1000 + 120 * FRAME, { live: true, levels: levelsOf(150) })
      expect(strokedPaths(ctx).filter((n) => n === 140)).toHaveLength(5)
    })

    it('doodles: at least 1.6 s apart, at most 2 live, gone 1.9 s after their birth', () => {
      const { ctx, state } = vizFor()
      const births = []
      let maxLive = 0
      for (let i = 0; i < 700; i += 1) {
        const t = 1000 + i * FRAME
        drawViz(state, ctx, 'chalk', t, { live: true, levels: i % 10 === 0 ? kick() : levelsOf(0) })
        expect(Array.isArray(state.chalkDood)).toBe(true)
        maxLive = Math.max(maxLive, state.chalkDood.length)
        for (const d of state.chalkDood) {
          expect(t - d.born).toBeLessThan(1900)
          if (!births.includes(d.born)) births.push(d.born)
        }
      }
      expect(births.length).toBeGreaterThanOrEqual(4)
      for (let i = 1; i < births.length; i += 1) expect(births[i] - births[i - 1]).toBeGreaterThanOrEqual(1600)
      expect(maxLive).toBe(2)
    })

    it('no doodles while idle or under reduced motion', () => {
      const { ctx, state } = vizFor()
      for (let i = 0; i < 300; i += 1) {
        const levels = i % 10 === 0 ? kick() : levelsOf(0)
        drawViz(state, ctx, 'chalk', 1000 + i * FRAME, { live: false, levels })
      }
      expect(state.chalkDood).toHaveLength(0)
      for (let i = 300; i < 600; i += 1) {
        const levels = i % 10 === 0 ? kick() : levelsOf(0)
        drawViz(state, ctx, 'chalk', 1000 + i * FRAME, { live: true, levels, reduce: true })
      }
      expect(state.chalkDood).toHaveLength(0)
    })

    it('states are isolated: doodles in one state are not in a new one', () => {
      const { ctx, state } = vizFor()
      for (let i = 0; i < 200; i += 1) {
        const levels = i % 10 === 0 ? kick() : levelsOf(0)
        drawViz(state, ctx, 'chalk', 1000 + i * FRAME, { live: true, levels })
      }
      expect(state.chalkDood.length).toBeGreaterThan(0)
      expect(createCanvasState().chalkDood).toHaveLength(0)
    })
  })

  describe('sakura petal storm', () => {
    it('draws 170 petals a frame, idle and live', () => {
      const { ctx, state } = vizFor()
      drawViz(state, ctx, 'sakura', 1000, { live: false })
      expect(ctx.calls.filter(([n]) => n === 'ellipse')).toHaveLength(170)
      ctx.calls.length = 0
      drawViz(state, ctx, 'sakura', 1016, { live: true, levels: kick() })
      expect(ctx.calls.filter(([n]) => n === 'ellipse')).toHaveLength(170)
    })

    it('trails with destination-out, draws with lighter, and ends on source-over', () => {
      const { ctx, state } = vizFor()
      drawViz(state, ctx, 'sakura', 1000, { live: true, levels: levelsOf(120) })
      const ops = ctx.sets.filter(([name]) => name === 'globalCompositeOperation').map(([, v]) => v)
      expect(ops).toContain('destination-out')
      expect(ops).toContain('lighter')
      expect(ops.indexOf('destination-out')).toBeLessThan(ops.indexOf('lighter'))
      expect(ctx.globalCompositeOperation).toBe('source-over')
    })

    it('does not clear the canvas (the trail stays)', () => {
      const { ctx, state } = vizFor()
      drawViz(state, ctx, 'sakura', 1000, { live: false })
      expect(ctx.calls.filter(([n]) => n === 'clearRect')).toHaveLength(0)
    })
  })
})
