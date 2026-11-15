// Cycle 129 (F-035, D-129): the music through the reveal. Spec
// docs/cycles/129-music-through-reveal.md, acceptance criteria 1 to 8b.
//
// Contract with the implementer:
// - A reveal{playUntil} (server epoch ms) opens a window when this client has
//   the song's start and playUntil - offsetMs > now(). In the window the
//   source is not stopped, the time bar (role="progressbar") keeps counting,
//   body keeps the `playing` class and the reveal card shows. At
//   min(playUntil, deadlineAt) local time the tick and the source stop and
//   `playing` goes; the card stays.
// - A prepare without holdMs stops the old source and tick at once; a held
//   prepare leaves them until the hold ends, the next start, or playUntil,
//   whichever is first. gameOver and a rejoin snapshot stop them too.
// - Any other reveal (no playUntil, playUntil at or before now, no start
//   yet) behaves as today.
// - A pick stays refused after any reveal (the skip button in a window is cycle 130).
//
// "Stopped at once" means stop() or stop(t) with t no later than the
// context's currentTime (App.flow.test.jsx's convention); the scheduled
// stop(stopAt) from playback is in the future and does not count. The fake
// context's currentTime is held at CTX_TIME, so stopAt stays in the future.
//
// Timer rule: fake timers in every test (the tick, the hold and the window
// end are local timers), driven by flushUntil / advance with the injected
// now() moved together with them.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, act, cleanup } from '@testing-library/react'
import App from './App.jsx'
import { createSocketFactory, createAudioContextFactory, createFakeFetch } from './testing/fakes.js'
import { createRoomAs } from './testing/lobby.js'

const T0 = 1_000_000
const FAKE_TIMERS = { toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval'] }
const HOLD = 5000
const CTX_TIME = 10
// The receipt offset of every start here: serverTime is the clock + 500.
const OFFSET = 500
const REVEAL_HEADING = '정답 공개'
const SKIP = '스킵'
const RECONNECTING = '연결이 끊겨 다시 연결하는 중...'
const tileName = (n) => `보기 ${n}`
const COVERS4 = ['/cover/a1', '/cover/b2', '/cover/c3', '/cover/d4']
const choicesOf = (urls) => urls.map((coverUrl) => ({ coverUrl }))

// A stand-in for the browser's Image (as in App.prefetch.test.jsx).
const images = []

class FakeImage {
  constructor() {
    this.onload = null
    this.onerror = null
    this.settled = null
    this.currentSrc = ''
    this.listeners = { load: [], error: [] }
    images.push(this)
  }

  set src(value) {
    this.currentSrc = String(value)
  }

  get src() {
    return this.currentSrc
  }

  addEventListener(type, fn) {
    if (!this.listeners[type]) this.listeners[type] = []
    this.listeners[type].push(fn)
  }

  removeEventListener(type, fn) {
    if (!this.listeners[type]) return
    this.listeners[type] = this.listeners[type].filter((f) => f !== fn)
  }

  decode() {
    return new Promise(() => {})
  }

  fire(type) {
    if (this.settled !== null) throw new Error('image already settled')
    this.settled = type
    const event = { type, target: this, currentTarget: this }
    const handler = type === 'load' ? this.onload : this.onerror
    if (typeof handler === 'function') handler.call(this, event)
    for (const fn of [...(this.listeners[type] || [])]) fn.call(this, event)
  }
}

function preloadsOf(urls) {
  return images.filter((img) => urls.some((url) => img.src !== '' && img.src.endsWith(url)))
}

beforeEach(() => {
  images.length = 0
  vi.stubGlobal('Image', FakeImage)
  if (typeof window !== 'undefined' && window.Image !== FakeImage) window.Image = FakeImage
  window.history.pushState({}, '', '/')
  window.localStorage.clear()
  window.sessionStorage.clear()
  vi.useFakeTimers(FAKE_TIMERS)
})

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
  vi.useRealTimers()
  window.history.pushState({}, '', '/')
  window.localStorage.clear()
  window.sessionStorage.clear()
})

function setup({ ctxOptions } = {}) {
  const sockets = createSocketFactory()
  const audio = createAudioContextFactory({ currentTime: CTX_TIME, ...ctxOptions })
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
  return { sockets, audio, fetch, clock }
}

function deliver(socket, msg) {
  act(() => socket.receive(msg))
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

const player = (name, { host = false } = {}) => ({ name, score: 0, host, connected: true })
const ALONE = [player('하루', { host: true })]
const roster = (phase = 'lobby') => ({ type: 'roster', phase, players: ALONE })

const prepareMsg = (index, extra = {}) => ({
  type: 'prepare',
  index,
  total: 5,
  audioUrl: `/audio/s${index}`,
  ...extra,
})

// A start at the current clock with receipt offset 500, audible at once.
function startMsg(env, index, length = 90_000) {
  const serverTime = env.clock.t + OFFSET
  return { type: 'start', index, startAt: serverTime, deadlineAt: serverTime + length, serverTime }
}

const revealMsg = (index, extra = {}) => ({
  type: 'reveal',
  index,
  reveal: '진격의 거인',
  titles: { ko: ['진격의 거인'], en: ['Attack on Titan'] },
  song: '紅蓮の弓矢',
  artists: ['Revo'],
  themeType: 'OP',
  themeSlug: 'OP1',
  winner: '하루',
  ...extra,
})

// The server's now: the local clock plus the receipt offset.
const serverNow = (env) => env.clock.t + OFFSET

function readiesFor(socket, index) {
  return socket.sentOfType('ready').filter((m) => m.index === index)
}

// stop() or stop(t) with t no later than the context's now.
function immediateStops(source, ctx) {
  return source.stop.mock.calls.filter((call) => call.length === 0 || call[0] <= ctx.currentTime).length
}

function barNow() {
  const bar = screen.queryByRole('progressbar')
  return bar === null ? null : Number(bar.getAttribute('aria-valuenow'))
}

function barMax() {
  const bar = screen.queryByRole('progressbar')
  return bar === null ? null : Number(bar.getAttribute('aria-valuemax'))
}

function bodyPlaying() {
  return document.body.classList.contains('playing')
}

function revealShown() {
  return screen.queryByText(REVEAL_HEADING) !== null
}

// Host 하루 of QWERT with token T1, alone, in a game: song 0 prepared and
// ready. choices makes it a choice room with COVERS4.
async function preparedSong(env, { choices = false } = {}) {
  createRoomAs('하루')
  await flushUntil(() => env.sockets.sockets.length === 1)
  const socket = env.sockets.last
  act(() => socket.open())
  await flushUntil(() => socket.sent.length > 0)
  deliver(socket, { type: 'joined', code: 'QWERT', name: '하루' })
  deliver(socket, roster('lobby'))
  deliver(socket, { type: 'token', token: 'T1' })
  deliver(socket, roster('playing'))
  if (choices) deliver(socket, { type: 'settings', answerMode: 'choice' })
  deliver(socket, prepareMsg(0, choices ? { choices: choicesOf(COVERS4) } : {}))
  if (choices) {
    await flushUntil(() => preloadsOf(COVERS4).length === COVERS4.length)
    await act(async () => {
      for (const img of preloadsOf(COVERS4)) if (img.settled === null) img.fire('load')
    })
  }
  return socket
}

// preparedSong, then song 0 started at the current clock (length ms long),
// playing, and `elapsed` ms into it. Returns the socket, the context, the
// playing source and the start message.
async function playingSong(env, { length = 90_000, elapsed = 10_000, choices = false } = {}) {
  const socket = await preparedSong(env, { choices })
  await flushUntil(() => readiesFor(socket, 0).length === 1)
  expect(readiesFor(socket, 0)).toEqual([{ type: 'ready', index: 0 }])
  const ctx = env.audio.last
  const start = startMsg(env, 0, length)
  deliver(socket, start)
  await flushUntil(() => ctx.startedSources().length === 1)
  expect(ctx.startedSources()).toHaveLength(1)
  const source = ctx.startedSources()[0]
  await advance(env, elapsed)
  expect(bodyPlaying(), 'the song is audible before the reveal').toBe(true)
  expect(barNow()).toBe(length - elapsed)
  expect(immediateStops(source, ctx)).toBe(0)
  return { socket, ctx, source, start }
}

// A playing song (70 s, 10 s in) revealed with playUntil = its deadlineAt,
// 60 s ahead: a window.
async function windowReveal(env, options = {}) {
  const song = await playingSong(env, { length: 70_000, elapsed: 10_000, ...options })
  deliver(song.socket, revealMsg(0, { playUntil: song.start.deadlineAt }))
  return song
}

describe('a reveal with playUntil ahead opens a window', () => {
  it('does not stop the source; the time bar keeps counting, the card shows and body keeps playing (1)', async () => {
    const env = setup()
    const { source, ctx, start } = await windowReveal(env)
    expect(start.deadlineAt - serverNow(env)).toBe(60_000)
    expect(immediateStops(source, ctx), 'the reveal does not stop the source').toBe(0)
    expect(source.stop.mock.calls.length, 'only the scheduled stop(stopAt)').toBeLessThanOrEqual(1)
    expect(revealShown(), 'the reveal card shows').toBe(true)
    expect(screen.queryByRole('progressbar'), 'the time bar still renders').not.toBeNull()
    expect(bodyPlaying(), 'body keeps playing').toBe(true)
    const first = barNow()
    expect(first).toBe(60_000)
    await advance(env, 250)
    const second = barNow()
    expect(second).toBe(59_750)
    await advance(env, 250)
    expect(barNow()).toBe(59_500)
    expect(immediateStops(source, ctx)).toBe(0)
    expect(revealShown()).toBe(true)
    expect(bodyPlaying()).toBe(true)
  })

  it('a decode that resolves in the window starts the source late, at the right offset (2)', async () => {
    const env = setup({ ctxOptions: { deferDecode: true } })
    const socket = await preparedSong(env)
    const ctx = env.audio.last
    await flushUntil(() => ctx.pendingDecodes.length === 1)
    expect(ctx.pendingDecodes).toHaveLength(1)
    const start = startMsg(env, 0, 90_000)
    deliver(socket, start)
    await advance(env, 10_000)
    deliver(socket, revealMsg(0, { playUntil: start.deadlineAt }))
    await advance(env, 2000)
    expect(ctx.startedSources()).toEqual([])
    // 12 s after the song's local start.
    await act(async () => ctx.resolveDecode())
    await flushUntil(() => ctx.startedSources().length === 1)
    expect(ctx.startedSources(), 'the late decode starts in the window').toHaveLength(1)
    const [when, offset] = ctx.startedSources()[0].start.mock.calls[0]
    expect(when).toBeCloseTo(CTX_TIME, 6)
    expect(offset).toBeCloseTo(12, 3)
  })

  it('at playUntil the tick, the source and playing stop; the card stays (3)', async () => {
    const env = setup()
    const { source, ctx } = await windowReveal(env)
    await advance(env, 60_000 - 250)
    expect(bodyPlaying(), 'still in the window just before playUntil').toBe(true)
    expect(immediateStops(source, ctx)).toBe(0)
    await advance(env, 500)
    expect(bodyPlaying(), 'body loses playing at playUntil').toBe(false)
    expect(immediateStops(source, ctx), 'the source has been stopped').toBeGreaterThanOrEqual(1)
    expect(revealShown(), 'the card stays').toBe(true)
    const at = barNow()
    await advance(env, 1000)
    expect(barNow(), 'the time bar stops changing or is gone').toBe(at)
    expect(revealShown()).toBe(true)
    expect(bodyPlaying()).toBe(false)
  })
})

describe('leaving a window early', () => {
  it('a prepare without holdMs stops the old source at once and the old time bar no longer counts (4)', async () => {
    const env = setup()
    const { socket, source, ctx } = await windowReveal(env)
    await advance(env, 1000)
    expect(immediateStops(source, ctx)).toBe(0)
    deliver(socket, prepareMsg(1))
    expect(immediateStops(source, ctx), 'stopped at once').toBeGreaterThanOrEqual(1)
    expect(revealShown()).toBe(false)
    await advance(env, 1000)
    expect(screen.queryByRole('progressbar'), 'no time bar before the next start').toBeNull()
    expect(bodyPlaying()).toBe(false)
    await flushUntil(() => readiesFor(socket, 1).length === 1)
    // The next song's bar counts from its own start only.
    deliver(socket, startMsg(env, 1, 30_000))
    await flushUntil(() => ctx.startedSources().length === 2)
    for (let i = 1; i <= 4; i++) {
      await advance(env, 250)
      expect(barMax()).toBe(30_000)
      expect(barNow(), 'only the new song ticks').toBe(30_000 - 250 * i)
    }
  })

  it('a held prepare leaves the old source playing until the hold ends (5)', async () => {
    const env = setup()
    const { socket, source, ctx } = await windowReveal(env)
    deliver(socket, prepareMsg(1, { holdMs: HOLD }))
    await flushUntil(() => readiesFor(socket, 1).length === 1)
    expect(immediateStops(source, ctx), 'a held prepare does not stop the old source').toBe(0)
    await advance(env, HOLD - 1)
    expect(immediateStops(source, ctx), 'still playing during the hold').toBe(0)
    expect(revealShown()).toBe(true)
    await advance(env, 1)
    expect(immediateStops(source, ctx), 'stopped when the hold ends').toBeGreaterThanOrEqual(1)
    expect(revealShown()).toBe(false)
    expect(bodyPlaying()).toBe(false)
  })

  it('a held prepare: the next start stops the old source before the hold ends (5)', async () => {
    const env = setup()
    const { socket, source, ctx } = await windowReveal(env)
    deliver(socket, prepareMsg(1, { holdMs: HOLD }))
    await flushUntil(() => readiesFor(socket, 1).length === 1)
    await advance(env, 2000)
    expect(immediateStops(source, ctx)).toBe(0)
    deliver(socket, startMsg(env, 1))
    await flushUntil(() => ctx.startedSources().length === 2)
    expect(ctx.startedSources()).toHaveLength(2)
    expect(immediateStops(source, ctx), 'the next start stops the old source').toBeGreaterThanOrEqual(1)
    const next = ctx.startedSources()[1]
    expect(immediateStops(next, ctx), 'the new song plays').toBe(0)
  })

  it('a held prepare: a playUntil before the hold end stops the old source at playUntil', async () => {
    const env = setup()
    const { socket, source, ctx } = await playingSong(env, { length: 90_000, elapsed: 10_000 })
    // The window is 2 s, shorter than the hold.
    deliver(socket, revealMsg(0, { playUntil: serverNow(env) + 2000 }))
    deliver(socket, prepareMsg(1, { holdMs: HOLD }))
    await flushUntil(() => readiesFor(socket, 1).length === 1)
    await advance(env, 1500)
    expect(immediateStops(source, ctx)).toBe(0)
    await advance(env, 1000)
    expect(immediateStops(source, ctx), 'stopped at playUntil').toBeGreaterThanOrEqual(1)
    expect(revealShown(), 'the hold still shows the card').toBe(true)
    expect(bodyPlaying()).toBe(false)
  })

  it('gameOver during the window stops the source (6)', async () => {
    const env = setup()
    const { socket, source, ctx } = await windowReveal(env)
    await advance(env, 1000)
    expect(immediateStops(source, ctx)).toBe(0)
    deliver(socket, { type: 'gameOver', ranking: [{ rank: 1, name: '하루', score: 1 }] })
    expect(immediateStops(source, ctx), 'gameOver stops the source').toBeGreaterThanOrEqual(1)
    await advance(env, 1000)
    expect(bodyPlaying()).toBe(false)
  })

  it('a rejoin snapshot during a held window stops the old source (7)', async () => {
    const env = setup()
    const { socket, source, ctx } = await windowReveal(env)
    deliver(socket, prepareMsg(1, { holdMs: HOLD }))
    await flushUntil(() => readiesFor(socket, 1).length === 1)
    await advance(env, 1000)
    act(() => socket.serverClose(1006))
    expect(screen.getByText(RECONNECTING)).toBeTruthy()
    await advance(env, 500)
    expect(env.sockets.sockets).toHaveLength(2)
    const attempt = env.sockets.last
    act(() => attempt.open())
    await flushUntil(() => attempt.sent.length > 0)
    expect(attempt.sent).toEqual([{ type: 'rejoin', token: 'T1' }])
    deliver(attempt, { type: 'joined', code: 'QWERT', name: '하루' })
    const before = immediateStops(source, ctx)
    expect(before, 'the old source still plays before the snapshot').toBe(0)
    deliver(attempt, {
      type: 'snapshot',
      phase: 'playing',
      players: ALONE,
      song: { index: 1, total: 5, audioUrl: '/audio/snap', picked: null },
      scores: [{ name: '하루', score: 1 }],
    })
    expect(immediateStops(source, ctx), 'the snapshot stops the old source').toBeGreaterThan(before)
    await advance(env, HOLD)
    expect(bodyPlaying()).toBe(false)
  })
})

describe('a reveal that opens no window behaves as today (7)', () => {
  it.each([
    ['without playUntil', () => ({})],
    ['with playUntil at now', (env) => ({ playUntil: serverNow(env) })],
    ['with playUntil before now', (env) => ({ playUntil: serverNow(env) - 5000 })],
  ])('a reveal %s stops the source at once and clears the time bar', async (_label, extra) => {
    const env = setup()
    const { socket, source, ctx } = await playingSong(env)
    deliver(socket, revealMsg(0, extra(env)))
    expect(immediateStops(source, ctx), 'stopped at once').toBeGreaterThanOrEqual(1)
    expect(screen.queryByRole('progressbar'), 'the time bar is cleared').toBeNull()
    expect(revealShown()).toBe(true)
    expect(bodyPlaying()).toBe(false)
    await advance(env, 1000)
    expect(screen.queryByRole('progressbar')).toBeNull()
  })
})

describe('pick and skip after a reveal (8)', () => {
  it('a pick after a window reveal sends nothing', async () => {
    const env = setup()
    const { socket } = await windowReveal(env, { choices: true })
    expect(revealShown()).toBe(true)
    fireEvent.click(screen.getByRole('button', { name: tileName(1) }))
    fireEvent.click(screen.getByRole('button', { name: tileName(3) }))
    await advance(env, 250)
    expect(socket.sentOfType('pick')).toEqual([])
  })

  it('a skip button shows during the song and no skip button after a reveal without a window', async () => {
    const env = setup()
    const { socket } = await playingSong(env)
    expect(screen.getByRole('button', { name: SKIP })).toBeTruthy()
    deliver(socket, revealMsg(0))
    expect(screen.queryByRole('button', { name: SKIP })).toBeNull()
    expect(socket.sentOfType('skipVote')).toEqual([])
  })
})

describe('a window reveal before this client start (8a)', () => {
  it('opens no window, and a later start does not start the audio', async () => {
    const env = setup()
    const socket = await preparedSong(env)
    await flushUntil(() => readiesFor(socket, 0).length === 1)
    const ctx = env.audio.last
    deliver(socket, revealMsg(0, { playUntil: serverNow(env) + 60_000 }))
    expect(revealShown()).toBe(true)
    expect(screen.queryByRole('progressbar'), 'no time bar').toBeNull()
    expect(bodyPlaying(), 'no playing class').toBe(false)
    deliver(socket, startMsg(env, 0, 70_000))
    await flushUntil(() => false, 5)
    await advance(env, 1000)
    expect(ctx.startedSources(), 'the start does not start the audio').toEqual([])
    expect(bodyPlaying()).toBe(false)
  })
})

describe('a late window end (8b)', () => {
  it('the old window end after the next start leaves the new time bar and playing alone', async () => {
    const env = setup()
    // Song 0: 30 s, revealed 10 s in; its window ends 20 s later.
    const { socket, ctx } = await windowReveal(env, { length: 30_000 })
    deliver(socket, prepareMsg(1, { holdMs: HOLD }))
    await flushUntil(() => readiesFor(socket, 1).length === 1)
    await advance(env, 1000)
    deliver(socket, startMsg(env, 1, 90_000))
    await flushUntil(() => ctx.startedSources().length === 2)
    expect(ctx.startedSources()).toHaveLength(2)
    expect(barMax()).toBe(90_000)
    expect(bodyPlaying()).toBe(true)
    // Past song 0's playUntil (19 s after the next start).
    await advance(env, 20_000)
    expect(screen.queryByRole('progressbar'), 'the new time bar stays').not.toBeNull()
    expect(barMax()).toBe(90_000)
    expect(barNow()).toBe(70_000)
    expect(bodyPlaying(), 'the new song keeps playing').toBe(true)
    const next = ctx.startedSources()[1]
    expect(immediateStops(next, ctx), 'the new source is not stopped').toBe(0)
    await advance(env, 250)
    expect(barNow()).toBe(69_750)
  })
})
