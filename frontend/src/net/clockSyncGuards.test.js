// Cycle 048 (cycle 047 review M1): the clock-jump guard in clockSync.
// Samples with a negative RTT (the client clock stepped backwards between a
// ping and its pong) are ignored by estimate() and by current(). The drift
// clear is covered at session level in App.multi.test.jsx. Like
// clockSync.test.js, these drive a hand-made clock and timer queue.
import { describe, it, expect } from 'vitest'
import { estimate, createClockSync } from './clockSync.js'

// A sample with the given rtt and offset: offset = serverTime - (t0 + rtt / 2).
function sample(t0, rtt, offset) {
  return { t0, t1: t0 + rtt, serverTime: t0 + rtt / 2 + offset }
}

describe('estimate ignores negative-RTT samples', () => {
  it('only negative-RTT samples give null', () => {
    expect(estimate([sample(1000, -40, 5000)])).toBeNull()
    expect(estimate([sample(1000, -1, 5000), sample(2000, -500, 7000)])).toBeNull()
  })

  it('a negative-RTT sample is not kept, although its rtt is the lowest', () => {
    const good = [sample(0, 20, 100), sample(200, 30, 110), sample(400, 40, 120)]
    const base = estimate(good)
    expect(base.offsetMs).toBeCloseTo(110, 9)
    expect(base.rttMs).toBeCloseTo(20, 9)
    // Counted, the -1000 rtt would be kept first, move the median to 100 and
    // make rttMs negative.
    const result = estimate([sample(600, -1000, -50_000), ...good])
    expect(result.offsetMs).toBeCloseTo(110, 9)
    expect(result.rttMs).toBeCloseTo(20, 9)
  })

  it('several negative-RTT samples among good ones change nothing', () => {
    const good = [sample(0, 10, 300), sample(200, 12, 301), sample(400, 14, 302)]
    const bad = [sample(100, -5, 9000), sample(300, -2000, -9000), sample(500, -1, 1)]
    const result = estimate([bad[0], good[0], bad[1], good[1], bad[2], good[2]])
    expect(result.offsetMs).toBeCloseTo(301, 9)
    expect(result.rttMs).toBeCloseTo(10, 9)
  })

  it('a zero RTT is not negative and is kept', () => {
    const result = estimate([{ t0: 1000, t1: 1000, serverTime: 4000 }])
    expect(result.offsetMs).toBeCloseTo(3000, 9)
    expect(result.rttMs).toBeCloseTo(0, 9)
  })
})

// A hand-driven clock and timer queue (as in clockSync.test.js). The clock
// can also be set backwards with clock.t = ..., like an OS clock step.
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
  return { clock, sent, schedule, send, now, advanceTo }
}

function pings(h) {
  return h.sent.filter((s) => s.msg.type === 'ping')
}

describe('current() ignores negative-RTT samples from onPong', () => {
  it('a pong received after the clock stepped backwards leaves current() null', () => {
    const h = createHarness()
    const sync = createClockSync({ now: h.now, send: h.send, schedule: h.schedule })
    const t0 = h.clock.t
    sync.burst(1)
    const { id } = pings(h)[0].msg
    // The OS clock steps back 2 s before the pong arrives: t1 - t0 = -1980.
    h.clock.t = t0 - 2000 + 20
    sync.onPong({ type: 'pong', id, serverTime: t0 + 10 + 3000 })
    expect(sync.current()).toBeNull()
  })

  it('with good samples, a backwards-stepped pong does not change current()', () => {
    const h = createHarness()
    const sync = createClockSync({ now: h.now, send: h.send, schedule: h.schedule })
    const start = h.clock.t
    sync.burst(4)
    // Three good pongs: rtt 20, 30, 40, offset 500.
    const rtts = [20, 30, 40]
    for (let i = 0; i < 3; i++) {
      const t0 = start + 150 * i
      h.advanceTo(t0)
      const ping = pings(h)[i]
      expect(ping.at).toBe(t0)
      h.advanceTo(t0 + rtts[i])
      sync.onPong({ type: 'pong', id: ping.msg.id, serverTime: t0 + rtts[i] / 2 + 500 })
    }
    const before = sync.current()
    expect(before.offsetMs).toBeCloseTo(500, 9)
    expect(before.rttMs).toBeCloseTo(20, 9)

    // The fourth ping goes out; the clock then steps back 5 s.
    const t3 = start + 450
    h.advanceTo(t3)
    const fourth = pings(h)[3]
    expect(fourth.at).toBe(t3)
    h.clock.t = t3 - 5000
    // Counted, its rtt -5000 would be kept first and its offset 5500 would
    // move the median.
    sync.onPong({ type: 'pong', id: fourth.msg.id, serverTime: t3 + 500 })
    const after = sync.current()
    expect(after.offsetMs).toBeCloseTo(500, 9)
    expect(after.rttMs).toBeCloseTo(20, 9)
  })

  it('after a backwards step, later good pongs are used again', () => {
    const h = createHarness()
    const sync = createClockSync({ now: h.now, send: h.send, schedule: h.schedule })
    sync.burst(1)
    const first = pings(h)[0]
    h.clock.t = first.at - 3000
    sync.onPong({ type: 'pong', id: first.msg.id, serverTime: first.at + 800 })
    expect(sync.current()).toBeNull()

    // A new ping on the stepped clock, answered after 30 ms.
    const t0 = h.clock.t
    sync.burst(1)
    const second = pings(h)[1]
    expect(second.at).toBe(t0)
    h.advanceTo(t0 + 30)
    sync.onPong({ type: 'pong', id: second.msg.id, serverTime: t0 + 15 + 700 })
    const result = sync.current()
    expect(result.offsetMs).toBeCloseTo(700, 9)
    expect(result.rttMs).toBeCloseTo(30, 9)
  })
})
