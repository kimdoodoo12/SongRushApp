// Cycle 047 (F-004): NTP-style clock offset estimation. estimate() is pure;
// createClockSync() gets its clock and timers injected, so these tests drive
// both by hand and never use real or fake global timers.
import { describe, it, expect } from 'vitest'
import { estimate, createClockSync } from './clockSync.js'

// A sample with the given rtt and offset: offset = serverTime - (t0 + rtt / 2).
function sample(t0, rtt, offset) {
  return { t0, t1: t0 + rtt, serverTime: t0 + rtt / 2 + offset }
}

describe('estimate', () => {
  it('returns null for no samples', () => {
    expect(estimate([])).toBeNull()
  })

  it('one sample: its own offset and rtt', () => {
    const result = estimate([{ t0: 1000, t1: 1040, serverTime: 6020 }])
    expect(result.offsetMs).toBeCloseTo(5000, 9)
    expect(result.rttMs).toBeCloseTo(40, 9)
  })

  it('an odd rtt gives a half-millisecond midpoint', () => {
    const result = estimate([{ t0: 0, t1: 15, serverTime: 100 }])
    expect(result.offsetMs).toBeCloseTo(92.5, 9)
    expect(result.rttMs).toBeCloseTo(15, 9)
  })

  it('two samples: the mean of the two offsets; rttMs is the lower rtt', () => {
    const result = estimate([sample(0, 30, 100), sample(200, 10, 120)])
    expect(result.offsetMs).toBeCloseTo(110, 9)
    expect(result.rttMs).toBeCloseTo(10, 9)
  })

  it('three samples: the middle offset, not the mean', () => {
    const result = estimate([sample(0, 20, 100), sample(200, 30, 200), sample(400, 10, 101)])
    expect(result.offsetMs).toBeCloseTo(101, 9)
    expect(result.rttMs).toBeCloseTo(10, 9)
  })

  it('keeps the 3 lowest-rtt samples, in any input order', () => {
    const samples = [
      sample(0, 80, 900),
      sample(100, 25, 120),
      sample(200, 60, -700),
      sample(300, 10, 100),
      sample(400, 40, 130),
      sample(500, 90, 5000),
    ]
    // Kept: rtt 10 (100), 25 (120), 40 (130); median 120.
    const result = estimate(samples)
    expect(result.offsetMs).toBeCloseTo(120, 9)
    expect(result.rttMs).toBeCloseTo(10, 9)
  })

  it('a sample with a large rtt and a wild offset does not move the result', () => {
    const good = [sample(0, 20, 100), sample(200, 30, 110), sample(400, 10, 105)]
    const base = estimate(good)
    const withWild = estimate([...good, sample(600, 2000, 1_000_000)])
    expect(withWild.offsetMs).toBeCloseTo(base.offsetMs, 9)
    expect(withWild.rttMs).toBeCloseTo(base.rttMs, 9)
    expect(withWild.offsetMs).toBeCloseTo(105, 9)
  })
})

// A hand-driven clock and timer queue. schedule(fn, ms) is due at
// clock.t + ms; advanceTo(t) runs every due timer in time order, with the
// clock set to its due time, then leaves the clock at t.
function createHarness(start = 10_000) {
  const clock = { t: start }
  let timers = []
  let seq = 0
  const sent = []
  const schedule = (fn, ms) => {
    const timer = { fn, due: clock.t + ms, seq: seq++ }
    timers.push(timer)
    return () => {
      timers = timers.filter((x) => x !== timer)
    }
  }
  const send = (msg) => {
    sent.push({ msg, at: clock.t })
  }
  const now = () => clock.t
  function advanceTo(t) {
    for (let guard = 0; guard < 1000; guard++) {
      const due = timers.filter((x) => x.due <= t).sort((a, b) => a.due - b.due || a.seq - b.seq)
      if (due.length === 0) break
      const next = due[0]
      timers = timers.filter((x) => x !== next)
      clock.t = next.due
      next.fn()
    }
    clock.t = t
  }
  return { clock, sent, schedule, send, now, advanceTo, pendingTimers: () => timers.length }
}

function pings(h) {
  return h.sent.filter((s) => s.msg.type === 'ping')
}

describe('createClockSync', () => {
  it('burst(8) sends 8 pings with distinct ids, the first at once and then 150 ms apart', () => {
    const h = createHarness()
    const sync = createClockSync({ now: h.now, send: h.send, schedule: h.schedule })
    const start = h.clock.t
    sync.burst(8)
    expect(pings(h)).toHaveLength(1)
    expect(pings(h)[0].at).toBe(start)
    for (let i = 1; i < 8; i++) {
      h.advanceTo(start + 150 * i - 1)
      expect(pings(h)).toHaveLength(i)
      h.advanceTo(start + 150 * i)
      expect(pings(h)).toHaveLength(i + 1)
    }
    h.advanceTo(start + 10_000)
    const sent = pings(h)
    expect(sent).toHaveLength(8)
    expect(sent.map((s) => s.at)).toEqual(Array.from({ length: 8 }, (_, i) => start + 150 * i))
    for (const s of sent) expect(s.msg.id).not.toBeUndefined()
    expect(new Set(sent.map((s) => s.msg.id)).size).toBe(8)
    // Only pings are sent.
    expect(h.sent).toHaveLength(8)
  })

  it('current() is null before any pong', () => {
    const h = createHarness()
    const sync = createClockSync({ now: h.now, send: h.send, schedule: h.schedule })
    expect(sync.current()).toBeNull()
    sync.burst(2)
    h.advanceTo(h.clock.t + 1000)
    expect(sync.current()).toBeNull()
  })

  it('onPong records {t0, t1: now(), serverTime} for a known id', () => {
    const h = createHarness()
    const sync = createClockSync({ now: h.now, send: h.send, schedule: h.schedule })
    const t0 = h.clock.t
    sync.burst(1)
    const { id } = pings(h)[0].msg
    h.advanceTo(t0 + 40)
    // offset = serverTime - (t0 + 20) = 3000
    sync.onPong({ type: 'pong', id, serverTime: t0 + 20 + 3000 })
    const result = sync.current()
    expect(result.offsetMs).toBeCloseTo(3000, 9)
    expect(result.rttMs).toBeCloseTo(40, 9)
  })

  it('onPong for an unknown id is ignored', () => {
    const h = createHarness()
    const sync = createClockSync({ now: h.now, send: h.send, schedule: h.schedule })
    const t0 = h.clock.t
    sync.burst(1)
    const { id } = pings(h)[0].msg
    h.advanceTo(t0 + 10)
    sync.onPong({ type: 'pong', id: 'no-such-id', serverTime: t0 + 999_999 })
    sync.onPong({ type: 'pong', id: 987_654_321, serverTime: t0 + 999_999 })
    expect(sync.current()).toBeNull()
    sync.onPong({ type: 'pong', id, serverTime: t0 + 5 + 700 })
    h.advanceTo(t0 + 12)
    sync.onPong({ type: 'pong', id: 'no-such-id', serverTime: t0 - 999_999 })
    const result = sync.current()
    expect(result.offsetMs).toBeCloseTo(700, 9)
    expect(result.rttMs).toBeCloseTo(10, 9)
  })

  it('current() uses only the most recent 8 samples', () => {
    const h = createHarness()
    const sync = createClockSync({ now: h.now, send: h.send, schedule: h.schedule })
    const start = h.clock.t
    // The first 2 samples have the lowest rtt and a wild offset; the last 8
    // agree on 400. Over all 10 the 3 lowest rtts would include both wild ones.
    const rtts = [2, 3, 20, 22, 24, 26, 28, 30, 32, 34]
    const offsets = [90_000, 90_000, 400, 400, 400, 400, 400, 400, 400, 400]
    sync.burst(10)
    for (let i = 0; i < 10; i++) {
      const t0 = start + 150 * i
      h.advanceTo(t0)
      const ping = pings(h)[i]
      expect(ping.at).toBe(t0)
      h.advanceTo(t0 + rtts[i])
      sync.onPong({ type: 'pong', id: ping.msg.id, serverTime: t0 + rtts[i] / 2 + offsets[i] })
    }
    const result = sync.current()
    expect(result.offsetMs).toBeCloseTo(400, 9)
    expect(result.rttMs).toBeCloseTo(20, 9)
  })

  it('stop() cancels the remaining pings', () => {
    const h = createHarness()
    const sync = createClockSync({ now: h.now, send: h.send, schedule: h.schedule })
    const start = h.clock.t
    sync.burst(8)
    h.advanceTo(start + 300)
    expect(pings(h)).toHaveLength(3)
    sync.stop()
    h.advanceTo(start + 60_000)
    expect(pings(h)).toHaveLength(3)
    expect(h.pendingTimers()).toBe(0)
  })
})
