// Cycle 107 (D-119): prefetch on reveal. Spec
// docs/cycles/107-prefetch-on-reveal.md, items 9-11 and the frontend
// Acceptance bullets.
//
// Contract with the implementer:
// - A prepare{index, total, audioUrl, choices?, holdMs} with holdMs > 0 that
//   arrives while a reveal is shown starts loading at once: the audio fetch,
//   the choice image preloads, and ready{index} as soon as both are done,
//   also while the hold lasts.
// - For holdMs (a local timer from receipt) the screen keeps the reveal card
//   (정답 공개), the revealed song's tiles with their correctChoice marks, and
//   the old round label. After holdMs the prepared round shows exactly as a
//   normal prepare shows it: its round label, the loading line or the loaded
//   state, the new tiles without marks, and no reveal card.
// - A start for the prepared index during the hold ends the hold at once:
//   the new round shows, then the start (the timer bar and 스킵).
// - A reconnect during the hold (close, rejoin, snapshot of the next song
//   with no holdMs) rebuilds the screen from the snapshot; when the old hold
//   time passes, nothing overwrites it.
// - A prepare without holdMs behaves as before: the reveal is cleared at once.
//
// Timer rule: fake timers in every test (the hold is a local timer), driven
// by flushUntil / advance with the injected now() moved together with them.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, act, cleanup, fireEvent } from '@testing-library/react'
import App from './App.jsx'
import ko from './i18n/ko.json'
import { createSocketFactory, createAudioContextFactory, createFakeFetch } from './testing/fakes.js'

const T0 = 1_000_000
const FAKE_TIMERS = { toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval'] }
const HOLD = 5000
const REVEAL_HEADING = '정답 공개'
const LOADING = '음원을 불러오는 중...'
const SKIP = '스킵'
const RECONNECTING = '연결이 끊겨 다시 연결하는 중...'
const tileName = (n) => `보기 ${n}`

const COVERS4 = ['/cover/a1', '/cover/b2', '/cover/c3', '/cover/d4']
const COVERS6 = ['/cover/e5', '/cover/f6', '/cover/g7', '/cover/h8', '/cover/i9', '/cover/j10']
// The snapshot's covers in the reconnect test differ from the prepare's, so
// a late write of the held prepare would show on the tiles.
const COVERS_SNAP = ['/cover/k1', '/cover/l2', '/cover/m3', '/cover/n4']
const choicesOf = (urls) => urls.map((coverUrl) => ({ coverUrl }))

// A stand-in for the browser's Image (as in App.choice.test.jsx); fire()
// settles it the way a real load or error would.
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

function pendingPreloads(urls) {
  return preloadsOf(urls).filter((img) => img.settled === null)
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

// A fetch whose calls succeed at once, except URLs listed in `held`, which
// stay pending until release(url).
function createHoldingFetch(held = []) {
  const urls = []
  const pending = new Map()
  const fetchFn = vi.fn((url) => {
    urls.push(url)
    const ok = () => {
      const buffer = new ArrayBuffer(8)
      return { ok: true, status: 200, arrayBuffer: async () => buffer }
    }
    if (!held.includes(url)) return Promise.resolve(ok())
    return new Promise((resolve) => pending.set(url, () => resolve(ok())))
  })
  function release(url) {
    const resolve = pending.get(url)
    if (!resolve) throw new Error(`no pending fetch for ${url}`)
    pending.delete(url)
    resolve()
  }
  return { fetchFn, urls, pending, release }
}

function setup({ fetch } = {}) {
  const sockets = createSocketFactory()
  const audio = createAudioContextFactory()
  const theFetch = fetch === undefined ? createFakeFetch() : fetch
  const clock = { t: T0 }
  render(
    <App
      canPlayOpus={true}
      socketFactory={sockets.factory}
      audioContextFactory={audio.factory}
      fetchFn={theFetch.fetchFn}
      now={() => clock.t}
    />,
  )
  return { sockets, audio, fetch: theFetch, clock, dropAt: null }
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

async function settle() {
  await flushUntil(() => false, 5)
}

// Moves the injected clock and the fake timers forward together.
async function advance(env, ms) {
  env.clock.t += ms
  await act(async () => {
    await vi.advanceTimersByTimeAsync(ms)
  })
}

async function settlePreloads(urls) {
  await flushUntil(() => preloadsOf(urls).length === urls.length)
  expect(preloadsOf(urls), 'every choice cover is preloaded').toHaveLength(urls.length)
  await act(async () => {
    for (const img of pendingPreloads(urls)) img.fire('load')
  })
}

const player = (name, { host = false, score = 0, connected = true } = {}) => ({ name, score, host, connected })
const ALONE = [player('하루', { host: true })]
const roster = (players, phase = 'lobby') => ({ type: 'roster', phase, players })

const prepareMsg = (index, urls, extra = {}) => ({
  type: 'prepare',
  index,
  total: 5,
  audioUrl: `/audio/s${index}`,
  ...(urls === null ? {} : { choices: choicesOf(urls) }),
  ...extra,
})

const revealMsg = (index, extra = {}) => ({
  type: 'reveal',
  index,
  reveal: '진격의 거인',
  titles: { ko: ['진격의 거인'], en: ['Attack on Titan'] },
  song: '紅蓮の弓矢',
  artists: ['Revo'],
  themeType: 'OP',
  themeSlug: 'OP1',
  winner: null,
  ...extra,
})

// A start at the current clock with receipt offset 500, audible at once.
function startMsg(env, index) {
  const serverTime = env.clock.t + 500
  return { type: 'start', index, startAt: serverTime, deadlineAt: serverTime + 90_000, serverTime }
}

function readiesFor(socket, index) {
  return socket.sentOfType('ready').filter((m) => m.index === index)
}

function koTiles(count) {
  return Array.from({ length: count }, (_, i) => screen.getByRole('button', { name: tileName(i + 1) }))
}

function tileCount() {
  let n = 0
  while (screen.queryByRole('button', { name: tileName(n + 1) }) !== null) n += 1
  return n
}

function srcs(list) {
  return list.map((t) => t.querySelector('img').getAttribute('src'))
}

// Cycle 112 (declared): the mark is the tile's class (correct, wrong, dim or
// locked; the mockup's renderChoices), no longer data-choice. null with none.
const MARKS = ['correct', 'wrong', 'dim', 'locked']

function marks(list) {
  return list.map((b) => {
    const found = MARKS.filter((m) => b.classList.contains(m))
    return found.length === 0 ? null : found.join(' ')
  })
}

function revealShown() {
  return screen.queryByText(REVEAL_HEADING) !== null
}

function roundShown(label) {
  return screen.queryByText(label) !== null
}

// The load text: LOADING while it shows, '' otherwise. Cycle 110 (D-124,
// declared): the load text is a .hint or .sub in the panel; the always-there
// .load-line box is not in the mockup, so '' now means "not shown".
function loadLineText() {
  return screen.queryByText(LOADING) === null ? '' : LOADING
}

// Host 하루 of QWERT with token T1, in a choice room: song 0 prepared with
// COVERS4, ready, started and audible, then revealed with correctChoice 2.
// Returns the socket with the reveal on screen.
async function revealedChoiceSong(env, { choices = true } = {}) {
  fireEvent.change(screen.getByLabelText(ko.start.nickname), { target: { value: '하루' } })
  fireEvent.click(screen.getByRole('button', { name: ko.start.createRoom }))
  await flushUntil(() => env.sockets.sockets.length === 1)
  const socket = env.sockets.last
  act(() => socket.open())
  await flushUntil(() => socket.sent.length > 0)
  deliver(socket, { type: 'joined', code: 'QWERT', name: '하루' })
  deliver(socket, roster(ALONE))
  deliver(socket, { type: 'token', token: 'T1' })
  deliver(socket, roster(ALONE, 'playing'))
  if (choices) deliver(socket, { type: 'settings', answerMode: 'choice' })
  deliver(socket, prepareMsg(0, choices ? COVERS4 : null))
  if (choices) await settlePreloads(COVERS4)
  await flushUntil(() => readiesFor(socket, 0).length === 1)
  expect(readiesFor(socket, 0)).toEqual([{ type: 'ready', index: 0 }])
  const ctx = env.audio.last
  deliver(socket, startMsg(env, 0))
  await flushUntil(() => ctx.startedSources().length === 1)
  expect(ctx.startedSources()).toHaveLength(1)
  await advance(env, 300)
  deliver(socket, revealMsg(0, choices ? { correctChoice: 2 } : {}))
  deliver(socket, { type: 'scores', scores: [{ name: '하루', score: 0 }] })
  expect(revealShown()).toBe(true)
  if (choices) expect(marks(koTiles(4))).toEqual(['dim', 'dim', 'correct', 'dim'])
  return socket
}

// The reveal card, song 0's tiles with the correct mark, and the old label.
function expectHeldReveal() {
  expect(revealShown(), 'the reveal card stays during the hold').toBe(true)
  expect(roundShown('1 / 5'), 'the old round label stays during the hold').toBe(true)
  expect(roundShown('2 / 5')).toBe(false)
  expect(tileCount(), 'the revealed song keeps its 4 tiles').toBe(4)
  expect(srcs(koTiles(4))).toEqual(COVERS4)
  expect(marks(koTiles(4))).toEqual(['dim', 'dim', 'correct', 'dim'])
}

// Song 1's round as a fresh prepare shows it: label, 6 unmarked tiles, no reveal.
function expectNewRound() {
  expect(revealShown(), 'the reveal card is gone').toBe(false)
  expect(roundShown('2 / 5'), 'the new round label shows').toBe(true)
  expect(tileCount()).toBe(6)
  expect(srcs(koTiles(6))).toEqual(COVERS6)
  expect(marks(koTiles(6))).toEqual([null, null, null, null, null, null])
}

describe('prepare with holdMs during a reveal', () => {
  it('keeps the reveal card and the revealed grid for holdMs, loads at once and sends ready during the hold', async () => {
    const env = setup()
    const socket = await revealedChoiceSong(env)
    deliver(socket, prepareMsg(1, COVERS6, { holdMs: HOLD }))
    // The download starts at once.
    await flushUntil(() => env.fetch.urls.includes('/audio/s1'))
    expect(env.fetch.urls).toContain('/audio/s1')
    expectHeldReveal()
    await settlePreloads(COVERS6)
    await flushUntil(() => readiesFor(socket, 1).length === 1)
    expect(readiesFor(socket, 1), 'ready goes out during the hold').toEqual([{ type: 'ready', index: 1 }])
    await advance(env, 2000)
    expectHeldReveal()
    await advance(env, HOLD - 2000 - 1)
    expectHeldReveal()
    expect(readiesFor(socket, 1)).toHaveLength(1)
  })

  it('after holdMs the new round shows: label, new tiles, reveal gone; a load still running shows the loading line until it ends', async () => {
    const fetch = createHoldingFetch(['/audio/s1'])
    const env = setup({ fetch })
    const socket = await revealedChoiceSong(env)
    deliver(socket, prepareMsg(1, COVERS6, { holdMs: HOLD }))
    await flushUntil(() => fetch.pending.has('/audio/s1'))
    expect(fetch.urls).toContain('/audio/s1')
    await settlePreloads(COVERS6)
    await settle()
    expect(readiesFor(socket, 1), 'no ready while the audio is pending').toEqual([])
    await advance(env, HOLD - 1)
    expectHeldReveal()
    await advance(env, 1)
    expectNewRound()
    expect(loadLineText()).toBe(LOADING)
    await act(async () => fetch.release('/audio/s1'))
    await flushUntil(() => readiesFor(socket, 1).length === 1)
    expect(readiesFor(socket, 1)).toEqual([{ type: 'ready', index: 1 }])
    expect(loadLineText()).toBe('')
    expectNewRound()
  })

  it('a load finished during the hold: no loading line after the hold', async () => {
    const env = setup()
    const socket = await revealedChoiceSong(env)
    deliver(socket, prepareMsg(1, COVERS6, { holdMs: HOLD }))
    await settlePreloads(COVERS6)
    await flushUntil(() => readiesFor(socket, 1).length === 1)
    expect(readiesFor(socket, 1)).toHaveLength(1)
    expectHeldReveal()
    await advance(env, HOLD)
    expectNewRound()
    expect(screen.queryByText(LOADING)).toBeNull()
    expect(loadLineText()).toBe('')
    await advance(env, 1000)
    expect(screen.queryByText(LOADING)).toBeNull()
  })

  it('a start for the new index during the hold ends it at once; the timer and 스킵 show and stay after the old hold time', async () => {
    const env = setup()
    const socket = await revealedChoiceSong(env)
    const ctx = env.audio.last
    deliver(socket, prepareMsg(1, COVERS6, { holdMs: HOLD }))
    await settlePreloads(COVERS6)
    await flushUntil(() => readiesFor(socket, 1).length === 1)
    await advance(env, 2000)
    expectHeldReveal()
    deliver(socket, startMsg(env, 1))
    await flushUntil(() => ctx.startedSources().length === 2)
    expectNewRound()
    expect(screen.queryByText(LOADING)).toBeNull()
    expect(screen.getByRole('progressbar')).toBeTruthy()
    expect(screen.getByRole('button', { name: SKIP })).toBeTruthy()
    expect(ctx.startedSources()).toHaveLength(2)
    // The old hold end passes: nothing resets the started round.
    await advance(env, HOLD)
    expectNewRound()
    expect(screen.getByRole('progressbar')).toBeTruthy()
    expect(screen.getByRole('button', { name: SKIP })).toBeTruthy()
    expect(ctx.startedSources()).toHaveLength(2)
  })

  it('a reconnect during the hold: the snapshot screen is restored and the old hold time overwrites nothing', async () => {
    const env = setup()
    const socket = await revealedChoiceSong(env)
    deliver(socket, prepareMsg(1, COVERS6, { holdMs: HOLD }))
    await settlePreloads(COVERS6)
    await flushUntil(() => readiesFor(socket, 1).length === 1)
    await advance(env, 1000)
    expectHeldReveal()
    act(() => socket.serverClose(1006))
    expect(screen.getByText(RECONNECTING)).toBeTruthy()
    await advance(env, 500)
    expect(env.sockets.sockets).toHaveLength(2)
    const attempt = env.sockets.last
    act(() => attempt.open())
    await flushUntil(() => attempt.sent.length > 0)
    expect(attempt.sent).toEqual([{ type: 'rejoin', token: 'T1' }])
    deliver(attempt, { type: 'joined', code: 'QWERT', name: '하루' })
    deliver(attempt, {
      type: 'snapshot',
      phase: 'playing',
      players: ALONE,
      song: { index: 1, total: 5, audioUrl: '/audio/snap', choices: choicesOf(COVERS_SNAP), picked: null },
      scores: [{ name: '하루', score: 0 }],
    })
    await settlePreloads(COVERS_SNAP)
    await flushUntil(() => readiesFor(attempt, 1).length === 1)
    expect(readiesFor(attempt, 1)).toEqual([{ type: 'ready', index: 1 }])
    const restored = () => {
      expect(revealShown()).toBe(false)
      expect(roundShown('2 / 5')).toBe(true)
      expect(tileCount()).toBe(4)
      expect(srcs(koTiles(4))).toEqual(COVERS_SNAP)
      expect(marks(koTiles(4))).toEqual([null, null, null, null])
      expect(screen.queryByText(LOADING)).toBeNull()
      expect(loadLineText()).toBe('')
    }
    restored()
    // Past the old hold end (1000 + 500 ms have gone by).
    await advance(env, HOLD)
    restored()
    expect(readiesFor(attempt, 1)).toHaveLength(1)
    expect(readiesFor(socket, 1)).toHaveLength(1)
  })
})

describe('prepare without holdMs', () => {
  it('clears the reveal at once and shows the new round, as before', async () => {
    const env = setup()
    const socket = await revealedChoiceSong(env)
    deliver(socket, prepareMsg(1, COVERS6))
    expectNewRound()
    await settlePreloads(COVERS6)
    await flushUntil(() => readiesFor(socket, 1).length === 1)
    expect(readiesFor(socket, 1)).toEqual([{ type: 'ready', index: 1 }])
    expect(loadLineText()).toBe('')
  })

  it('a chat room: the reveal card goes at once and 2 / 5 shows', async () => {
    const env = setup()
    const socket = await revealedChoiceSong(env, { choices: false })
    deliver(socket, prepareMsg(1, null))
    expect(revealShown()).toBe(false)
    expect(roundShown('2 / 5')).toBe(true)
    await flushUntil(() => readiesFor(socket, 1).length === 1)
    expect(readiesFor(socket, 1)).toEqual([{ type: 'ready', index: 1 }])
  })
})
