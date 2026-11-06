// Cycle 047 (F-004): planPlayback's optional outputLatency (seconds). The
// cycle 039 tests in playback.test.js cover the default of 0.
import { describe, it, expect } from 'vitest'
import { planPlayback } from './playback.js'

const NOW = 1_000_000
const CTX = 10

describe('planPlayback outputLatency', () => {
  it('outputLatency 0.05 moves when and stopAt 50 ms earlier; offset stays 0', () => {
    const startAt = NOW + 3000
    const args = { startAt, deadlineAt: startAt + 90_000, offsetMs: 0, nowMs: NOW, ctxTime: CTX }
    const base = planPlayback(args)
    const plan = planPlayback({ ...args, outputLatency: 0.05 })
    expect(plan.when).toBeCloseTo(CTX + 2.95, 9)
    expect(plan.stopAt).toBeCloseTo(CTX + 92.95, 9)
    expect(plan.offset).toBe(0)
    expect(base.when - plan.when).toBeCloseTo(0.05, 9)
    expect(base.stopAt - plan.stopAt).toBeCloseTo(0.05, 9)
  })

  it('outputLatency 0 and an omitted outputLatency give the same plan', () => {
    const startAt = NOW + 1200
    const args = { startAt, deadlineAt: startAt + 60_000, offsetMs: 300, nowMs: NOW, ctxTime: CTX }
    const omitted = planPlayback(args)
    const zero = planPlayback({ ...args, outputLatency: 0 })
    expect(zero.when).toBeCloseTo(omitted.when, 9)
    expect(zero.offset).toBeCloseTo(omitted.offset, 9)
    expect(zero.stopAt).toBeCloseTo(omitted.stopAt, 9)
    expect(omitted.when).toBeCloseTo(CTX + 0.9, 9)
  })

  it('a delay smaller than the latency starts at ctxTime and the offset covers the difference', () => {
    // 20 ms of delay, 50 ms of latency: 30 ms late.
    const startAt = NOW + 20
    const plan = planPlayback({
      startAt,
      deadlineAt: startAt + 90_000,
      offsetMs: 0,
      nowMs: NOW,
      ctxTime: CTX,
      outputLatency: 0.05,
    })
    expect(plan.when).toBeCloseTo(CTX, 9)
    expect(plan.offset).toBeCloseTo(0.03, 9)
    expect(plan.stopAt).toBeCloseTo(CTX + 90.02 - 0.05, 9)
  })

  it('a late start adds the latency to the offset', () => {
    const startAt = NOW - 2500
    const plan = planPlayback({
      startAt,
      deadlineAt: startAt + 90_000,
      offsetMs: 0,
      nowMs: NOW,
      ctxTime: CTX,
      outputLatency: 0.05,
    })
    expect(plan.when).toBeCloseTo(CTX, 9)
    expect(plan.offset).toBeCloseTo(2.55, 9)
    expect(plan.stopAt).toBeCloseTo(CTX + 87.5 - 0.05, 9)
  })

  it('offsetMs and outputLatency combine', () => {
    const startAt = NOW + 3000
    const plan = planPlayback({
      startAt,
      deadlineAt: startAt + 90_000,
      offsetMs: 500,
      nowMs: NOW,
      ctxTime: CTX,
      outputLatency: 0.1,
    })
    expect(plan.when).toBeCloseTo(CTX + 2.4, 9)
    expect(plan.offset).toBe(0)
    expect(plan.stopAt).toBeCloseTo(CTX + 92.4, 9)
  })

  it('returns null when the latency-adjusted stopAt is not after when', () => {
    // The deadline is 20 ms away, inside the 50 ms latency:
    // stopAt = ctxTime + 0.02 - 0.05 < when = ctxTime. Without the latency
    // the plan would still play (stopAt = ctxTime + 0.02 > when).
    const plan = planPlayback({
      startAt: NOW - 89_980,
      deadlineAt: NOW + 20,
      offsetMs: 0,
      nowMs: NOW,
      ctxTime: CTX,
      outputLatency: 0.05,
    })
    expect(plan).toBeNull()
  })
})
