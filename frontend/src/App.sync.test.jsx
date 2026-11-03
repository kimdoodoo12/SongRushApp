// Cycle 047 (F-004): clock sync and synchronized playback in App. Room
// sessions ping the server (a burst of 8 on joined, 3 after each load, 3
// every 30 s), use the synced offset at start, re-sync on drift and stop on
// close; solo sends no ping. Also: outputLatency, navigator.audioSession,
// the 탭하여 소리 켜기 prompt and resume() on 게임 시작 / 대기실로.
// Timer rule (cycle 039): fake timers only where the pings' 150 ms spacing or
// the 30 s interval matter, driven by flushUntil / advance; real timers and
// waitFor everywhere else.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, act, cleanup } from '@testing-library/react'
import App from './App.jsx'
import { createSocketFactory, createAudioContextFactory, createFakeFetch } from './testing/fakes.js'

const T0 = 1_000_000
const FAKE_TIMERS = { toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval'] }
const PROMPT = '탭하여 소리 켜기'
// The synced offset the fake server reports through pong (server ahead 2.5 s).
const SYNC = 2500
const RTT = 20

beforeEach(() => {
  window.history.pushState({}, '', '/')
})

afterEach(() => {
  cleanup()
  vi.useRealTimers()
  delete window.navigator.audioSession
  window.history.pushState({}, '', '/')
  vi.restoreAllMocks()
})

// Renders App with fakes. clock.t is what now() returns; answered holds the
// ids of the pings answered with a pong.
function setup({ ctxOptions } = {}) {
  const sockets = createSocketFactory()
  const audio = createAudioContextFactory(ctxOptions)
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

function setNickname(nickname) {
  fireEvent.change(screen.getByLabelText('닉네임'), { target: { value: nickname } })
}

const player = (name, { host = false } = {}) => ({ name, score: 0, host, connected: true })
const roster = (players, phase = 'lobby') => ({ type: 'roster', phase, players })
const HOST_ROSTER = [player('하루', { host: true }), player('Joon')]
const joinedMsg = { type: 'joined', code: 'QWERT', name: '하루' }
const prepare = (index = 0) => ({ type: 'prepare', index, total: 5, audioUrl: '/audio/abc' })

// A start message at the current clock: serverTime is now + receiptMs, so the
// receipt offset is receiptMs; startAt is startInMs away by the synced clock.
function startAt(env, { receiptMs, startInMs = 3000, offsetMs = SYNC, length = 90_000, index = 0 }) {
  const c = env.clock.t
  const start = c + offsetMs + startInMs
  return { type: 'start', index, startAt: start, deadlineAt: start + length, serverTime: c + receiptMs }
}

// ---- fake-timer helpers ----

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

// 방 만들기 as 하루 and open the socket, with fake timers.
async function createRoomFake(env) {
  setNickname('하루')
  fireEvent.click(screen.getByRole('button', { name: '방 만들기' }))
  await flushUntil(() => env.sockets.sockets.length === 1)
  expect(env.sockets.sockets).toHaveLength(1)
  const socket = env.sockets.last
  act(() => socket.open())
  await flushUntil(() => socket.sent.length > 0)
  expect(socket.sent).toEqual([{ type: 'create', nickname: '하루' }])
  return socket
}

async function joinRoomFake(env) {
  const socket = await createRoomFake(env)
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

// ---- real-timer helpers ----

async function openSocket(env, count = 1) {
  await waitFor(() => expect(env.sockets.sockets.length).toBe(count))
  const socket = env.sockets.last
  act(() => socket.open())
  await waitFor(() => expect(socket.sent.length).toBeGreaterThan(0))
  return socket
}

async function startSolo(env) {
  setNickname('하루')
  fireEvent.change(screen.getByLabelText('곡 수'), { target: { value: '5' } })
  fireEvent.click(screen.getByRole('button', { name: '시작' }))
  const socket = await openSocket(env)
  expect(socket.sent).toEqual([{ type: 'create', nickname: '하루', songCount: 5 }])
  return socket
}

async function createRoom(env) {
  setNickname('하루')
  fireEvent.click(screen.getByRole('button', { name: '방 만들기' }))
  const socket = await openSocket(env)
  expect(socket.sent[0]).toEqual({ type: 'create', nickname: '하루' })
  deliver(socket, joinedMsg)
  deliver(socket, roster(HOST_ROSTER))
  return socket
}

// A session that is about to receive prepare{0}: solo, or a room in play.
async function begin(env, mode) {
  if (mode === 'solo') return startSolo(env)
  const socket = await createRoom(env)
  deliver(socket, roster(HOST_ROSTER, 'playing'))
  return socket
}

async function prepareAndReady(socket) {
  deliver(socket, prepare(0))
  await waitFor(() => expect(socket.sentOfType('ready')).toEqual([{ type: 'ready', index: 0 }]))
}

// A start at the current clock with receipt offset 500; the song is
// startInMs away by that offset.
function receiptStart(env, { startInMs = 3000, length = 90_000 } = {}) {
  const serverTime = env.clock.t + 500
  const start = serverTime + startInMs
  return { type: 'start', index: 0, startAt: start, deadlineAt: start + length, serverTime }
}

function stubAudioSession() {
  const session = {}
  Object.defineProperty(window.navigator, 'audioSession', { value: session, configurable: true, writable: true })
  return session
}

describe('burst on joined', () => {
  it('no ping before joined; after joined 8 pings with distinct ids go out, the first at once, 150 ms apart', async () => {
    vi.useFakeTimers(FAKE_TIMERS)
    const env = setup()
    const socket = await createRoomFake(env)
    await advance(env, 1000)
    expect(pingsOf(socket)).toEqual([])
    expect(socket.sent).toEqual([{ type: 'create', nickname: '하루' }])

    deliver(socket, joinedMsg)
    deliver(socket, roster(HOST_ROSTER))
    expect(pingsOf(socket)).toHaveLength(1)
    for (let i = 1; i < 8; i++) {
      await advance(env, 149)
      expect(pingsOf(socket)).toHaveLength(i)
      await advance(env, 1)
      expect(pingsOf(socket)).toHaveLength(i + 1)
    }
    await advance(env, 1000)
    const pings = pingsOf(socket)
    expect(pings).toHaveLength(8)
    for (const p of pings) expect(p.id).not.toBeUndefined()
    expect(new Set(pings.map((p) => p.id)).size).toBe(8)
    // The opening frame is still the first one sent.
    expect(socket.sent[0]).toEqual({ type: 'create', nickname: '하루' })
  })
})

describe('synced offset', () => {
  it('start schedules source.start from the synced offset, not the receipt offset (2 s apart)', async () => {
    vi.useFakeTimers(FAKE_TIMERS)
    const env = setup()
    const socket = await syncedRoomReady(env)
    const ctx = env.audio.last
    ctx.currentTime = 10
    // Receipt offset 4500, synced offset 2500: a +2000 drift, so the song
    // uses the smaller (synced) offset and is 3 s away by it.
    deliver(socket, startAt(env, { receiptMs: 4500, startInMs: 3000 }))
    await flushUntil(() => ctx.startedSources().length === 1)
    expect(ctx.startedSources()).toHaveLength(1)
    const source = ctx.startedSources()[0]
    const [when, offset] = source.start.mock.calls[0]
    expect(when).toBeCloseTo(13, 6)
    expect(offset).toBe(0)
    expect(source.stop.mock.calls[0][0]).toBeCloseTo(103, 6)
  })

  it('a song 1 s late by the synced clock plays from 1 s in, and the bar uses the synced offset', async () => {
    vi.useFakeTimers(FAKE_TIMERS)
    const env = setup()
    const socket = await syncedRoomReady(env)
    const ctx = env.audio.last
    ctx.currentTime = 10
    // Receipt offset 4500 (a +2000 drift): the synced offset wins as the
    // smaller, so the song began 1 s ago by it.
    deliver(socket, startAt(env, { receiptMs: 4500, startInMs: -1000 }))
    await flushUntil(() => ctx.startedSources().length === 1 && screen.queryByRole('progressbar') !== null)
    const [when, offset] = ctx.startedSources()[0].start.mock.calls[0]
    expect(when).toBeCloseTo(10, 6)
    expect(offset).toBeCloseTo(1, 6)
    expect(ctx.startedSources()[0].stop.mock.calls[0][0]).toBeCloseTo(99, 6)
    const bar = screen.getByRole('progressbar')
    expect(bar.getAttribute('aria-valuemax')).toBe('90000')
    expect(bar.getAttribute('aria-valuenow')).toBe('89000')
  })

  // Cycle 048 (declared change): on drift the song uses the smaller of the
  // receipt and synced offsets, so it never starts early. With -2000 the
  // receipt (500) is smaller: when 15, offset 0. Otherwise the synced 2500.
  it.each([
    [2000, 13],
    [-2000, 15],
    [100, 13],
  ])(
    'a drift of %i ms (beyond rtt + 50 = 70) sends 3 more pings; the song uses the smaller offset (when %i)',
    async (drift, expectedWhen) => {
      vi.useFakeTimers(FAKE_TIMERS)
      const env = setup()
      const socket = await syncedRoomReady(env)
      const ctx = env.audio.last
      ctx.currentTime = 10
      deliver(socket, startAt(env, { receiptMs: SYNC + drift, startInMs: 3000 }))
      await flushUntil(() => ctx.startedSources().length === 1)
      expect(pingsOf(socket)).toHaveLength(12)
      await advance(env, 149)
      expect(pingsOf(socket)).toHaveLength(12)
      await advance(env, 1)
      expect(pingsOf(socket)).toHaveLength(13)
      await advance(env, 150)
      expect(pingsOf(socket)).toHaveLength(14)
      await advance(env, 1000)
      expect(pingsOf(socket)).toHaveLength(14)
      const [when, offset] = ctx.startedSources()[0].start.mock.calls[0]
      expect(when).toBeCloseTo(expectedWhen, 6)
      expect(offset).toBe(0)
    },
  )

  it.each([0, 30, -30])('a receipt offset %i ms from the synced one (within 70) sends no ping', async (drift) => {
    vi.useFakeTimers(FAKE_TIMERS)
    const env = setup()
    const socket = await syncedRoomReady(env)
    const ctx = env.audio.last
    ctx.currentTime = 10
    deliver(socket, startAt(env, { receiptMs: SYNC + drift, startInMs: 3000 }))
    await flushUntil(() => ctx.startedSources().length === 1)
    expect(ctx.startedSources()).toHaveLength(1)
    await advance(env, 1000)
    expect(pingsOf(socket)).toHaveLength(11)
  })

  it('with no pong answered the start falls back to the receipt offset', async () => {
    vi.useFakeTimers(FAKE_TIMERS)
    const env = setup()
    const socket = await joinRoomFake(env)
    await advance(env, 2000)
    expect(pingsOf(socket)).toHaveLength(8)
    await prepareFake(socket)
    await advance(env, 1000)
    const ctx = env.audio.last
    ctx.currentTime = 10
    deliver(socket, receiptStart(env, { startInMs: 3000 }))
    await flushUntil(() => ctx.startedSources().length === 1)
    expect(ctx.startedSources()).toHaveLength(1)
    const [when, offset] = ctx.startedSources()[0].start.mock.calls[0]
    expect(when).toBeCloseTo(13, 6)
    expect(offset).toBe(0)
  })
})

describe('re-sync', () => {
  it('prepare alone sends no ping; the load (the ready send) sends 3', async () => {
    vi.useFakeTimers(FAKE_TIMERS)
    const env = setup({ ctxOptions: { deferDecode: true } })
    const socket = await joinRoomFake(env)
    await advance(env, 2000)
    expect(pingsOf(socket)).toHaveLength(8)
    const ctx = env.audio.last
    deliver(socket, roster(HOST_ROSTER, 'playing'))
    deliver(socket, prepare(0))
    await flushUntil(() => ctx.pendingDecodes.length === 1)
    expect(ctx.pendingDecodes).toHaveLength(1)
    await advance(env, 1000)
    expect(socket.sentOfType('ready')).toEqual([])
    expect(pingsOf(socket)).toHaveLength(8)

    await act(async () => ctx.resolveDecode())
    await flushUntil(() => socket.sentOfType('ready').length > 0)
    expect(socket.sentOfType('ready')).toEqual([{ type: 'ready', index: 0 }])
    expect(pingsOf(socket)).toHaveLength(9)
    await advance(env, 300)
    expect(pingsOf(socket)).toHaveLength(11)
    await advance(env, 1000)
    expect(pingsOf(socket)).toHaveLength(11)
  })

  it('every 30 s while the session is open a burst of 3 pings goes out', async () => {
    vi.useFakeTimers(FAKE_TIMERS)
    const env = setup()
    const socket = await joinRoomFake(env)
    await advance(env, 29_000)
    expect(pingsOf(socket)).toHaveLength(8)
    await advance(env, 2_500)
    expect(pingsOf(socket)).toHaveLength(11)
    await advance(env, 30_000)
    expect(pingsOf(socket)).toHaveLength(14)
  })

  it('after 나가기 no further ping is sent', async () => {
    vi.useFakeTimers(FAKE_TIMERS)
    const env = setup()
    const socket = await joinRoomFake(env)
    await advance(env, 300)
    expect(pingsOf(socket)).toHaveLength(3)
    fireEvent.click(screen.getByRole('button', { name: '나가기' }))
    expect(socket.sentOfType('leave')).toEqual([{ type: 'leave' }])
    await advance(env, 65_000)
    expect(pingsOf(socket)).toHaveLength(3)
  })

  it('after a server close no further ping is sent', async () => {
    vi.useFakeTimers(FAKE_TIMERS)
    const env = setup()
    const socket = await joinRoomFake(env)
    await advance(env, 300)
    expect(pingsOf(socket)).toHaveLength(3)
    act(() => socket.serverClose())
    await advance(env, 65_000)
    expect(pingsOf(socket)).toHaveLength(3)
  })
})

describe('audio session', () => {
  it.each([
    ['시작', 'solo'],
    ['방 만들기', 'room'],
  ])('with navigator.audioSession, the first click (%s) sets its type to playback', async (button) => {
    const session = stubAudioSession()
    const env = setup()
    setNickname('하루')
    fireEvent.click(screen.getByRole('button', { name: button }))
    expect(env.audio.factory).toHaveBeenCalledTimes(1)
    expect(session.type).toBe('playback')
    expect(window.navigator.audioSession.type).toBe('playback')
    await openSocket(env)
  })

  it('without navigator.audioSession the click still creates, resumes and connects', async () => {
    expect('audioSession' in window.navigator).toBe(false)
    const env = setup()
    setNickname('하루')
    fireEvent.click(screen.getByRole('button', { name: '방 만들기' }))
    expect(env.audio.factory).toHaveBeenCalledTimes(1)
    expect(env.audio.last.resume).toHaveBeenCalledTimes(1)
    const socket = await openSocket(env)
    expect(socket.sent).toEqual([{ type: 'create', nickname: '하루' }])
  })
})

describe('solo', () => {
  it('a solo session sends no ping, also across 30 s and a full song', async () => {
    vi.useFakeTimers(FAKE_TIMERS)
    const env = setup()
    setNickname('하루')
    fireEvent.change(screen.getByLabelText('곡 수'), { target: { value: '5' } })
    fireEvent.click(screen.getByRole('button', { name: '시작' }))
    await flushUntil(() => env.sockets.sockets.length === 1)
    const socket = env.sockets.last
    act(() => socket.open())
    await flushUntil(() => socket.sent.length > 0)
    expect(socket.sent).toEqual([{ type: 'create', nickname: '하루', songCount: 5 }])
    // The server sends solo games the lobby frames too.
    deliver(socket, joinedMsg)
    deliver(socket, roster([player('하루', { host: true })], 'playing'))
    deliver(socket, prepare(0))
    await flushUntil(() => socket.sentOfType('ready').length > 0)
    expect(socket.sentOfType('ready')).toEqual([{ type: 'ready', index: 0 }])
    const ctx = env.audio.last
    deliver(socket, receiptStart(env))
    await flushUntil(() => ctx.startedSources().length === 1)
    expect(ctx.startedSources()).toHaveLength(1)
    await advance(env, 31_000)
    await advance(env, 31_000)
    expect(pingsOf(socket)).toEqual([])
  })
})

describe('output latency', () => {
  it.each(['solo', 'room'])('ctx.outputLatency shifts the scheduled when and stopAt (%s)', async (mode) => {
    const env = setup()
    const socket = await begin(env, mode)
    const ctx = env.audio.last
    ctx.outputLatency = 0.05
    await prepareAndReady(socket)
    ctx.currentTime = 10
    // Receipt offset 500 (no pong is answered), song 3000 ms away.
    deliver(socket, receiptStart(env, { startInMs: 3000 }))
    await waitFor(() => expect(ctx.startedSources()).toHaveLength(1))
    const source = ctx.startedSources()[0]
    const [when, offset] = source.start.mock.calls[0]
    expect(when).toBeCloseTo(12.95, 6)
    expect(offset).toBe(0)
    expect(source.stop.mock.calls[0][0]).toBeCloseTo(102.95, 6)
  })
})

describe('sound prompt', () => {
  it.each(['solo', 'room'])(
    'a suspended context at prepare shows the button; its click resumes synchronously; it goes once running (%s)',
    async (mode) => {
      const env = setup({ ctxOptions: { state: 'suspended', deferResume: true } })
      const socket = await begin(env, mode)
      const ctx = env.audio.last
      expect(screen.queryByRole('button', { name: PROMPT })).toBeNull()
      deliver(socket, prepare(0))
      await waitFor(() => expect(screen.getByRole('button', { name: PROMPT })).toBeTruthy())
      const before = ctx.resume.mock.calls.length
      fireEvent.click(screen.getByRole('button', { name: PROMPT }))
      expect(ctx.resume.mock.calls.length).toBe(before + 1)
      // The resume has not resolved yet: the button stays.
      expect(ctx.state).toBe('suspended')
      expect(screen.getByRole('button', { name: PROMPT })).toBeTruthy()
      await act(async () => ctx.resolveResume())
      await waitFor(() => expect(screen.queryByRole('button', { name: PROMPT })).toBeNull())
    },
  )

  it.each(['solo', 'room'])('a context that becomes suspended before start shows the button at start (%s)', async (mode) => {
    const env = setup()
    const socket = await begin(env, mode)
    const ctx = env.audio.last
    await prepareAndReady(socket)
    expect(screen.queryByRole('button', { name: PROMPT })).toBeNull()
    // The context is interrupted, and a resume no longer succeeds at once.
    ctx.deferResume = true
    ctx.state = 'suspended'
    deliver(socket, receiptStart(env))
    await waitFor(() => expect(screen.getByRole('button', { name: PROMPT })).toBeTruthy())
    const before = ctx.resume.mock.calls.length
    fireEvent.click(screen.getByRole('button', { name: PROMPT }))
    expect(ctx.resume.mock.calls.length).toBe(before + 1)
    await act(async () => ctx.resolveResume())
    await waitFor(() => expect(screen.queryByRole('button', { name: PROMPT })).toBeNull())
  })

  it.each(['solo', 'room'])('a running context shows no button at prepare, after the load or at start (%s)', async (mode) => {
    const env = setup()
    const socket = await begin(env, mode)
    const ctx = env.audio.last
    deliver(socket, prepare(0))
    expect(screen.queryByRole('button', { name: PROMPT })).toBeNull()
    await waitFor(() => expect(socket.sentOfType('ready')).toEqual([{ type: 'ready', index: 0 }]))
    expect(screen.queryByRole('button', { name: PROMPT })).toBeNull()
    deliver(socket, receiptStart(env))
    await waitFor(() => expect(ctx.startedSources()).toHaveLength(1))
    expect(screen.queryByRole('button', { name: PROMPT })).toBeNull()
  })
})

describe('resume on click', () => {
  it('게임 시작 calls resume() synchronously when the context is not running', async () => {
    const env = setup({ ctxOptions: { state: 'suspended', deferResume: true } })
    const socket = await createRoom(env)
    const ctx = env.audio.last
    expect(ctx.state).toBe('suspended')
    const before = ctx.resume.mock.calls.length
    fireEvent.click(screen.getByRole('button', { name: '게임 시작' }))
    expect(ctx.resume.mock.calls.length).toBe(before + 1)
    await waitFor(() => expect(socket.sentOfType('start')).toEqual([{ type: 'start', songCount: 10 }]))
  })

  it('대기실로 calls resume() synchronously when the context is not running', async () => {
    const env = setup({ ctxOptions: { state: 'suspended', deferResume: true } })
    const socket = await createRoom(env)
    const ctx = env.audio.last
    deliver(socket, roster(HOST_ROSTER, 'playing'))
    await prepareAndReady(socket)
    deliver(socket, receiptStart(env))
    deliver(socket, {
      type: 'reveal',
      index: 0,
      reveal: '진격의 거인',
      titles: { ko: ['진격의 거인'], en: ['Attack on Titan'], ja: ['進撃の巨人'], romaji: ['Shingeki no Kyojin'] },
      song: '紅蓮の弓矢',
      artists: ['Revo'],
      themeType: 'OP',
      themeSlug: 'OP1',
      winner: null,
    })
    deliver(socket, { type: 'gameOver', ranking: [{ rank: 1, name: '하루', score: 0 }] })
    deliver(socket, roster(HOST_ROSTER, 'lobby'))
    expect(ctx.state).toBe('suspended')
    const before = ctx.resume.mock.calls.length
    fireEvent.click(screen.getByRole('button', { name: '대기실로' }))
    expect(ctx.resume.mock.calls.length).toBe(before + 1)
    expect(screen.getByRole('button', { name: '게임 시작' })).toBeTruthy()
  })
})
