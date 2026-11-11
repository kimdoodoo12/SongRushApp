// Cycle 051 (F-007): drift without lateness. A start frame that reaches a
// room player late is first scheduled by the smaller (receipt) offset, as in
// cycle 048, and marked provisional. Each fresh pong of the drift burst gives
// a lower bound serverTime - t1 on the true offset; one more than 20 ms above
// the offset in use moves the start earlier (a new source, the old one
// stopped with stop()). Pongs for pings sent before the drift clear are
// ignored. Fake timers throughout, driven by flushUntil / advance as in
// App.sync.test.jsx. advance() does not move ctx.currentTime; the tests set
// it themselves.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, act, cleanup } from '@testing-library/react'
import App from './App.jsx'
import { createSocketFactory, createAudioContextFactory, createFakeFetch } from './testing/fakes.js'
import { createRoomAs } from './testing/lobby.js'

const T0 = 1_000_000
const FAKE_TIMERS = { toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval'] }
// The synced offset the fake server reports through pong (server ahead 2.5 s).
const SYNC = 2500
const RTT = 20
// The lower bound serverTime - t1 of a symmetric SYNC pong.
const BOUND = SYNC - RTT / 2

beforeEach(() => {
  window.history.pushState({}, '', '/')
})

afterEach(() => {
  cleanup()
  vi.useRealTimers()
  window.history.pushState({}, '', '/')
  vi.restoreAllMocks()
})

// Renders App with fakes. clock.t is what now() returns; answered holds the
// ids of the pings answered with a pong.
function setup() {
  const sockets = createSocketFactory()
  const audio = createAudioContextFactory()
  const fetch = createFakeFetch()
  const clock = { t: T0 }
  render(
    <App
      canPlayOpus={true}
      socketFactory={sockets.factory}
      audioContextFactory={audio.factory}
      fetchFn={fetch.fetchFn}
      now={() => clock.t}
    />,
  )
  return { sockets, audio, fetch, clock, answered: new Set() }
}

function deliver(socket, msg) {
  act(() => socket.receive(msg))
}

function pingsOf(socket) {
  return socket.sentOfType('ping')
}

const player = (name, { host = false } = {}) => ({ name, score: 0, host, connected: true })
const roster = (players, phase = 'lobby') => ({ type: 'roster', phase, players })
const HOST_ROSTER = [player('하루', { host: true }), player('Joon')]
const joinedMsg = { type: 'joined', code: 'QWERT', name: '하루' }
const prepare = (index = 0) => ({ type: 'prepare', index, total: 5, audioUrl: '/audio/abc' })

const revealMsg = (index = 0) => ({
  type: 'reveal',
  index,
  reveal: '진격의 거인',
  titles: { ko: ['진격의 거인'], en: ['Attack on Titan'], ja: ['進撃の巨人'], romaji: ['Shingeki no Kyojin'] },
  song: '紅蓮の弓矢',
  artists: ['Revo'],
  themeType: 'OP',
  themeSlug: 'OP1',
  winner: null,
})

// A start message at the current clock: serverTime is now + receiptMs, so the
// receipt offset is receiptMs; startAt is startInMs away by offsetMs (the
// true offset).
function startAt(env, { receiptMs, startInMs = 3000, offsetMs = SYNC, length = 90_000, index = 0 }) {
  const c = env.clock.t
  const start = c + offsetMs + startInMs
  return { type: 'start', index, startAt: start, deadlineAt: start + length, serverTime: c + receiptMs }
}

// Advances 0 ms (running pending microtasks) until cond() holds, at most max times.
async function flushUntil(cond, max = 20) {
  for (let i = 0; i < max && !cond(); i++) {
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0)
    })
  }
}

// Moves the injected clock and the fake timers forward together.
async function advance(env, ms) {
  env.clock.t += ms
  await act(async () => {
    await vi.advanceTimersByTimeAsync(ms)
  })
}

// 방 만들기 as 하루, open the socket, joined and the lobby roster.
async function joinRoomFake(env) {
  createRoomAs('하루')
  await flushUntil(() => env.sockets.sockets.length === 1)
  expect(env.sockets.sockets).toHaveLength(1)
  const socket = env.sockets.last
  act(() => socket.open())
  await flushUntil(() => socket.sent.length > 0)
  expect(socket.sent).toEqual([{ type: 'create', nickname: '하루' }])
  deliver(socket, joinedMsg)
  deliver(socket, roster(HOST_ROSTER))
  return socket
}

// Answers `count` pings of a burst whose first ping has just gone out at the
// current clock: each pong comes back after RTT ms with the given offset,
// and the next ping is awaited 150 ms after the previous one.
async function answerPings(env, socket, count, offsetMs = SYNC) {
  for (let i = 0; i < count; i++) {
    if (i > 0) await advance(env, 150 - RTT)
    const sent = pingsOf(socket)
    expect(sent.length, 'a ping should have been sent').toBeGreaterThan(0)
    const ping = sent[sent.length - 1]
    expect(env.answered.has(ping.id), 'the latest ping should be a new one').toBe(false)
    env.answered.add(ping.id)
    const t0 = env.clock.t
    await advance(env, RTT)
    deliver(socket, { type: 'pong', id: ping.id, serverTime: t0 + RTT / 2 + offsetMs })
  }
}

// Answers one given ping at the current clock (t1) with serverTime t1 + bound,
// so that sample's lower bound serverTime - t1 is exactly `bound`.
function answerWithBound(env, socket, ping, bound) {
  expect(env.answered.has(ping.id), 'the ping should not be answered yet').toBe(false)
  env.answered.add(ping.id)
  deliver(socket, { type: 'pong', id: ping.id, serverTime: env.clock.t + bound })
}

// Delivers roster{playing} and prepare{0}; waits for ready{0}.
async function prepareFake(socket) {
  deliver(socket, roster(HOST_ROSTER, 'playing'))
  deliver(socket, prepare(0))
  await flushUntil(() => socket.sentOfType('ready').length > 0)
  expect(socket.sentOfType('ready')).toEqual([{ type: 'ready', index: 0 }])
}

// A room whose clock is synced to SYNC by 8 + 3 answered pings, with song 0
// loaded and ready sent. 11 pings in total.
async function syncedRoomReady(env) {
  const socket = await joinRoomFake(env)
  await answerPings(env, socket, 8)
  await prepareFake(socket)
  await answerPings(env, socket, 3)
  await advance(env, 500)
  expect(pingsOf(socket)).toHaveLength(11)
  return socket
}

// Synced to SYNC, ctx.currentTime 10, then a start frame 1.5 s late (receipt
// SYNC - 1500 = 1000) for a song 3000 ms away by the true clock. The first
// source is scheduled by the receipt: when 14.5. The drift burst's first ping
// has just gone out (ping 12).
async function lateFrame(env) {
  const socket = await syncedRoomReady(env)
  const ctx = env.audio.last
  ctx.currentTime = 10
  const msg = startAt(env, { receiptMs: SYNC - 1500, startInMs: 3000 })
  deliver(socket, msg)
  await flushUntil(() => ctx.startedSources().length === 1)
  expect(ctx.startedSources()).toHaveLength(1)
  const [when, offset] = ctx.startedSources()[0].start.mock.calls[0]
  expect(when).toBeCloseTo(14.5, 6)
  expect(offset).toBe(0)
  expect(pingsOf(socket)).toHaveLength(12)
  return { socket, ctx, msg }
}

function lastStopCall(source) {
  const calls = source.stop.mock.calls
  return calls[calls.length - 1]
}

describe('a late start frame is corrected by the drift burst', () => {
  it('corrected before the start: the first fresh pong (lower bound 2490) moves the song to when 12.99', async () => {
    vi.useFakeTimers(FAKE_TIMERS)
    const env = setup()
    const { socket, ctx } = await lateFrame(env)
    // Symmetric SYNC pong after RTT: serverTime - t1 = SYNC - RTT / 2 = 2490.
    await answerPings(env, socket, 1, SYNC)
    await flushUntil(() => ctx.startedSources().length === 2)
    expect(ctx.startedSources()).toHaveLength(2)
    const [first, second] = ctx.startedSources()
    expect(lastStopCall(first)).toEqual([])
    const [when, offset] = second.start.mock.calls[0]
    // 10 + (5500 - 2490 - 20) / 1000: the clock moved 20 ms, ctx.currentTime did not.
    expect(Math.abs(when - 12.99)).toBeLessThanOrEqual(0.005)
    expect(offset).toBe(0)
    expect(second.stop.mock.calls.length).toBeGreaterThan(0)
    expect(Math.abs(second.stop.mock.calls[0][0] - 102.99)).toBeLessThanOrEqual(0.005)
  })

  it('corrected while playing: the new source continues from the corrected position and the bar follows', async () => {
    vi.useFakeTimers(FAKE_TIMERS)
    const env = setup()
    const socket = await syncedRoomReady(env)
    const ctx = env.audio.last
    ctx.currentTime = 10
    // 1 s late (receipt 1500); the song began 500 ms ago by the true clock.
    const msg = startAt(env, { receiptMs: SYNC - 1000, startInMs: -500 })
    deliver(socket, msg)
    await flushUntil(() => ctx.startedSources().length === 1 && screen.queryByRole('progressbar') !== null)
    expect(ctx.startedSources()).toHaveLength(1)
    const [firstWhen, firstOffset] = ctx.startedSources()[0].start.mock.calls[0]
    expect(firstWhen).toBeCloseTo(10.5, 6)
    expect(firstOffset).toBe(0)

    ctx.currentTime = 10.6
    await answerPings(env, socket, 1, SYNC)
    const pongNow = env.clock.t
    await flushUntil(() => ctx.startedSources().length === 2)
    expect(ctx.startedSources()).toHaveLength(2)
    const [first, second] = ctx.startedSources()
    expect(lastStopCall(first)).toEqual([])
    const [when, offset] = second.start.mock.calls[0]
    expect(Math.abs(when - 10.6)).toBeLessThanOrEqual(0.005)
    const expectedOffset = (pongNow + BOUND - msg.startAt) / 1000
    expect(expectedOffset).toBeCloseTo(0.51, 6)
    expect(Math.abs(offset - expectedOffset)).toBeLessThanOrEqual(0.005)

    await advance(env, 250)
    const bar = screen.getByRole('progressbar')
    // deadlineAt - 2490 - now(); with the uncorrected 1500 it would be clamped at 90000.
    const expectedLeft = msg.deadlineAt - BOUND - env.clock.t
    expect(expectedLeft).toBe(89_240)
    expect(bar.getAttribute('aria-valuenow')).toBe(String(expectedLeft))
  })

  it('later pongs move it again when their lower bound is higher, and not otherwise', async () => {
    vi.useFakeTimers(FAKE_TIMERS)
    const env = setup()
    const { socket, ctx, msg } = await lateFrame(env)
    const burst = pingsOf(socket).slice(11)
    expect(burst).toHaveLength(1)
    // The first fresh pong has RTT 400; meanwhile pings 13 and 14 go out.
    await advance(env, 400)
    expect(pingsOf(socket)).toHaveLength(14)
    const [p12, p13, p14] = pingsOf(socket).slice(11)
    // Symmetric: serverTime = t0 + 200 + SYNC = t1 + 2300.
    answerWithBound(env, socket, p12, 2300)
    await flushUntil(() => ctx.startedSources().length === 2)
    expect(ctx.startedSources()).toHaveLength(2)
    const second = ctx.startedSources()[1]
    expect(lastStopCall(ctx.startedSources()[0])).toEqual([])
    const [when2] = second.start.mock.calls[0]
    const expectedWhen2 = 10 + (msg.startAt - 2300 - env.clock.t) / 1000
    expect(expectedWhen2).toBeCloseTo(12.8, 6)
    expect(Math.abs(when2 - expectedWhen2)).toBeLessThanOrEqual(0.005)

    // An asymmetric path: serverTime = t1 + 2490, valid by causality.
    await advance(env, 10)
    answerWithBound(env, socket, p13, 2490)
    await flushUntil(() => ctx.startedSources().length === 3)
    expect(ctx.startedSources()).toHaveLength(3)
    const third = ctx.startedSources()[2]
    expect(lastStopCall(second)).toEqual([])
    const [when3, offset3] = third.start.mock.calls[0]
    const expectedWhen3 = 10 + (msg.startAt - 2490 - env.clock.t) / 1000
    expect(Math.abs(when3 - expectedWhen3)).toBeLessThanOrEqual(0.005)
    expect(offset3).toBe(0)
    const thirdStops = third.stop.mock.calls.length

    // serverTime = t1 + 2480: below the offset in use (2490), nothing changes.
    await advance(env, 10)
    answerWithBound(env, socket, p14, 2480)
    await flushUntil(() => ctx.startedSources().length > 3, 5)
    expect(ctx.startedSources()).toHaveLength(3)
    expect(ctx.sources).toHaveLength(3)
    expect(third.stop.mock.calls.length).toBe(thirdStops)
  })

  it('no fresh pong: the provisional start plays, one source at when 14.5 for 4 s', async () => {
    vi.useFakeTimers(FAKE_TIMERS)
    const env = setup()
    const { ctx } = await lateFrame(env)
    await advance(env, 4000)
    expect(ctx.startedSources()).toHaveLength(1)
    expect(ctx.sources).toHaveLength(1)
    expect(ctx.startedSources()[0].start.mock.calls).toHaveLength(1)
    expect(ctx.startedSources()[0].start.mock.calls[0][0]).toBeCloseTo(14.5, 6)
  })

  it('a reveal ends it: a pong after the reveal creates no new source', async () => {
    vi.useFakeTimers(FAKE_TIMERS)
    const env = setup()
    const { socket, ctx } = await lateFrame(env)
    deliver(socket, revealMsg(0))
    await answerPings(env, socket, 1, SYNC)
    await flushUntil(() => ctx.startedSources().length > 1, 5)
    expect(ctx.startedSources()).toHaveLength(1)
    expect(ctx.sources).toHaveLength(1)
  })
})

describe('clock jumps and other directions', () => {
  it('a stale pong for a ping sent before a forward jump is ignored, so the next song does not start early', async () => {
    vi.useFakeTimers(FAKE_TIMERS)
    const env = setup()
    const socket = await joinRoomFake(env)
    await answerPings(env, socket, 8)
    await prepareFake(socket)
    // Two of the ready burst's 3 pings are answered; the third goes out at 300.
    await answerPings(env, socket, 2)
    await advance(env, 130)
    expect(pingsOf(socket)).toHaveLength(11)
    const stale = pingsOf(socket)[10]
    // The client clock jumps forward 1.5 s: the true offset is now 1000.
    env.clock.t += 1500
    const TRUE = SYNC - 1500
    const ctx = env.audio.last
    ctx.currentTime = 10
    deliver(socket, startAt(env, { receiptMs: TRUE, offsetMs: TRUE, startInMs: 3000 }))
    await flushUntil(() => ctx.startedSources().length === 1)
    expect(ctx.startedSources()).toHaveLength(1)
    expect(ctx.startedSources()[0].start.mock.calls[0][0]).toBeCloseTo(13, 6)
    // The drift burst has gone out; it stays unanswered.
    expect(pingsOf(socket)).toHaveLength(12)

    // The stale ping is answered now, from the true server clock.
    env.answered.add(stale.id)
    deliver(socket, { type: 'pong', id: stale.id, serverTime: env.clock.t + TRUE })
    await flushUntil(() => ctx.startedSources().length > 1, 5)
    expect(ctx.startedSources()).toHaveLength(1)

    deliver(socket, revealMsg(0))
    deliver(socket, prepare(1))
    await flushUntil(() => socket.sentOfType('ready').length > 1)
    expect(socket.sentOfType('ready')).toEqual([
      { type: 'ready', index: 0 },
      { type: 'ready', index: 1 },
    ])
    const ctxTime = 50
    ctx.currentTime = ctxTime
    // On time: the receipt is the true offset; the song is 3 s away.
    deliver(socket, startAt(env, { receiptMs: TRUE, offsetMs: TRUE, startInMs: 3000, index: 1 }))
    await flushUntil(() => ctx.startedSources().length === 2)
    expect(ctx.startedSources()).toHaveLength(2)
    const [when] = ctx.startedSources()[1].start.mock.calls[0]
    expect(when).toBeGreaterThanOrEqual(ctxTime + 3 - 0.005)
  })

  it('a real forward clock jump: scheduled by the receipt, and the burst with the true offset changes nothing', async () => {
    vi.useFakeTimers(FAKE_TIMERS)
    const env = setup()
    const socket = await syncedRoomReady(env)
    const ctx = env.audio.last
    env.clock.t += 1500
    const TRUE = SYNC - 1500
    ctx.currentTime = 10
    deliver(socket, startAt(env, { receiptMs: TRUE, offsetMs: TRUE, startInMs: 3000 }))
    await flushUntil(() => ctx.startedSources().length === 1)
    expect(ctx.startedSources()).toHaveLength(1)
    const first = ctx.startedSources()[0]
    expect(first.start.mock.calls[0][0]).toBeCloseTo(13, 6)
    expect(first.start.mock.calls[0][1]).toBe(0)
    expect(pingsOf(socket)).toHaveLength(12)
    await answerPings(env, socket, 3, TRUE)
    await flushUntil(() => ctx.startedSources().length > 1, 5)
    expect(ctx.startedSources()).toHaveLength(1)
    expect(ctx.sources).toHaveLength(1)
    expect(first.start.mock.calls).toHaveLength(1)
    expect(first.start.mock.calls[0][0]).toBeCloseTo(13, 6)
  })

  it('earlier-direction drift is unchanged: receipt SYNC + 2000, a burst answered with it, no second source', async () => {
    vi.useFakeTimers(FAKE_TIMERS)
    const env = setup()
    const socket = await syncedRoomReady(env)
    const ctx = env.audio.last
    ctx.currentTime = 10
    deliver(socket, startAt(env, { receiptMs: SYNC + 2000, startInMs: 3000 }))
    await flushUntil(() => ctx.startedSources().length === 1)
    expect(ctx.startedSources()[0].start.mock.calls[0][0]).toBeCloseTo(13, 6)
    expect(pingsOf(socket)).toHaveLength(12)
    await answerPings(env, socket, 3, SYNC + 2000)
    await flushUntil(() => ctx.startedSources().length > 1, 5)
    expect(ctx.startedSources()).toHaveLength(1)
    expect(ctx.sources).toHaveLength(1)
  })

  it('no drift: a receipt equal to SYNC sends no ping and plays one source at when 13', async () => {
    vi.useFakeTimers(FAKE_TIMERS)
    const env = setup()
    const socket = await syncedRoomReady(env)
    const ctx = env.audio.last
    ctx.currentTime = 10
    deliver(socket, startAt(env, { receiptMs: SYNC, startInMs: 3000 }))
    await flushUntil(() => ctx.startedSources().length === 1)
    await advance(env, 1000)
    expect(pingsOf(socket)).toHaveLength(11)
    expect(ctx.startedSources()).toHaveLength(1)
    expect(ctx.sources).toHaveLength(1)
    const [when, offset] = ctx.startedSources()[0].start.mock.calls[0]
    expect(when).toBeCloseTo(13, 6)
    expect(offset).toBe(0)
  })
})
