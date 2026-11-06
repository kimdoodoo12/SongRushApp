import { describe, it, expect, vi } from 'vitest'
import { planPlayback, canPlayOggOpus, loadTrack } from './playback.js'
import { FakeAudioContext, createFakeFetch } from '../testing/fakes.js'

const NOW = 1_000_000
const CTX = 10

describe('planPlayback', () => {
  it('on time: a delay of 3000 ms gives when = ctxTime + 3 and offset 0', () => {
    const startAt = NOW + 3000
    const plan = planPlayback({ startAt, deadlineAt: startAt + 90_000, offsetMs: 0, nowMs: NOW, ctxTime: CTX })
    expect(plan.when).toBeCloseTo(13, 9)
    expect(plan.offset).toBe(0)
  })

  it('late by 2500 ms: when = ctxTime and offset = 2.5', () => {
    const startAt = NOW - 2500
    const plan = planPlayback({ startAt, deadlineAt: startAt + 90_000, offsetMs: 0, nowMs: NOW, ctxTime: CTX })
    expect(plan.when).toBeCloseTo(CTX, 9)
    expect(plan.offset).toBeCloseTo(2.5, 9)
  })

  it('stopAt follows deadlineAt', () => {
    const startAt = NOW + 3000
    const a = planPlayback({ startAt, deadlineAt: startAt + 90_000, offsetMs: 0, nowMs: NOW, ctxTime: CTX })
    const b = planPlayback({ startAt, deadlineAt: startAt + 30_000, offsetMs: 0, nowMs: NOW, ctxTime: CTX })
    expect(a.stopAt).toBeCloseTo(CTX + 93, 9)
    expect(b.stopAt).toBeCloseTo(CTX + 33, 9)
  })

  it('offsetMs shifts when, offset and stopAt', () => {
    const startAt = NOW + 3000
    const deadlineAt = startAt + 90_000
    // Server 500 ms ahead: the start is 500 ms closer.
    const ahead = planPlayback({ startAt, deadlineAt, offsetMs: 500, nowMs: NOW, ctxTime: CTX })
    expect(ahead.when).toBeCloseTo(CTX + 2.5, 9)
    expect(ahead.offset).toBe(0)
    expect(ahead.stopAt).toBeCloseTo(CTX + 92.5, 9)
    // Server 4000 ms ahead: the start is 1000 ms in the past.
    const late = planPlayback({ startAt, deadlineAt, offsetMs: 4000, nowMs: NOW, ctxTime: CTX })
    expect(late.when).toBeCloseTo(CTX, 9)
    expect(late.offset).toBeCloseTo(1, 9)
    expect(late.stopAt).toBeCloseTo(CTX + 89, 9)
  })

  it('returns null after the deadline', () => {
    const startAt = NOW - 100_000
    expect(planPlayback({ startAt, deadlineAt: NOW - 10_000, offsetMs: 0, nowMs: NOW, ctxTime: CTX })).toBeNull()
    // Exactly at the deadline stopAt equals when, which is also over.
    expect(planPlayback({ startAt, deadlineAt: NOW, offsetMs: 0, nowMs: NOW, ctxTime: CTX })).toBeNull()
  })
})

describe('canPlayOggOpus', () => {
  const element = (answer) => ({ canPlayType: vi.fn(() => answer) })

  it('is true for "probably" and asks for ogg opus', () => {
    const el = element('probably')
    expect(canPlayOggOpus(el)).toBe(true)
    expect(el.canPlayType).toHaveBeenCalledWith('audio/ogg; codecs="opus"')
  })

  it('is true for "maybe"', () => {
    expect(canPlayOggOpus(element('maybe'))).toBe(true)
  })

  it('is false for ""', () => {
    expect(canPlayOggOpus(element(''))).toBe(false)
  })
})

describe('loadTrack', () => {
  it('fetches the url and decodes the body', async () => {
    const ctx = new FakeAudioContext()
    const fake = createFakeFetch()
    const buffer = await loadTrack(ctx, '/audio/abc', fake.fetchFn)
    expect(fake.urls).toEqual(['/audio/abc'])
    expect(ctx.decodeCalls).toEqual([fake.buffers[0]])
    expect(buffer).toBe(ctx.decoded[0])
  })

  it('retries once after a rejected fetch', async () => {
    const ctx = new FakeAudioContext()
    const fake = createFakeFetch({ failures: 1, failWith: 'reject' })
    const buffer = await loadTrack(ctx, '/audio/abc', fake.fetchFn)
    expect(fake.urls).toEqual(['/audio/abc', '/audio/abc'])
    expect(buffer).toBe(ctx.decoded[0])
  })

  it('retries once after an ok:false response', async () => {
    const ctx = new FakeAudioContext()
    const fake = createFakeFetch({ failures: 1, failWith: 'status' })
    const buffer = await loadTrack(ctx, '/audio/abc', fake.fetchFn)
    expect(fake.urls).toEqual(['/audio/abc', '/audio/abc'])
    expect(ctx.decodeCalls).toEqual([fake.buffers[0]])
    expect(buffer).toBe(ctx.decoded[0])
  })

  it('throws after two failures and does not try a third time', async () => {
    const ctx = new FakeAudioContext()
    const fake = createFakeFetch({ failures: 2, failWith: 'status' })
    await expect(loadTrack(ctx, '/audio/abc', fake.fetchFn)).rejects.toBeTruthy()
    expect(fake.fetchFn).toHaveBeenCalledTimes(2)
    expect(ctx.decodeCalls).toEqual([])
  })
})
