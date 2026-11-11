// Cycle 086 (D-101): while the song's audio is loading, and when loading
// failed, the record and the equalizer stay on screen but stand still. The
// disc area carries data-paused="true" while load is 'loading' or 'failed';
// otherwise the attribute is absent. CSS pauses the animations through it.
// 087: disc drawn from prepare (D-101 clarified). The disc is drawn whenever
// a round is current and not revealed, so also after prepare and before the
// start, with data-paused="true" until the song actually plays. The reveal
// still replaces it.
// 087 attempt 2: the disc moves only while the song is audible: started, the
// clock has reached the start (now >= startAt - offset), load is neither
// 'loading' nor 'failed', and the sound is not blocked. While the disc is
// shown, the load-text line (.load-line) keeps its box, empty when loaded.
// Declared rewrites in attempt 2 (a start in the future, paused until the
// injected clock passes it, then not paused):
//   - loading: 'a pending decode after start ...'
//   - loading: 'a pending fetch after start ...'
//   - loading: 'a song loaded before start ...'
//   - prepare, before start: 'audio loaded before start, then start ...'
// Cycle 110 (D-124, declared): the mockup's markup. The disc is the .deck's
// .player .disc canvas with its .mark, and the equalizer is the header
// .logo .eq (three <i>). "Moving" is body.playing (the mockup's CSS runs the
// disc and the bars only under .playing); "paused" is its absence, in place
// of data-paused. The load text shows in the panel as a .hint or .sub; once
// loaded it is gone (the empty .load-line box is not in the mockup). After
// the reveal the disc stands still (no body.playing) and the .reveal card
// shows. Between songs there is no playing disc: no .deck, or an .idle one.
// Timer rule (cycle 039): real timers and waitFor, except 'rejoin', which
// needs the reconnect backoff and uses fake timers with flushUntil/advance.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, act, cleanup } from '@testing-library/react'
import App from './App.jsx'
import ko from './i18n/ko.json'
import { createSocketFactory, createAudioContextFactory, createFakeFetch } from './testing/fakes.js'
import { createRoomAs, joinRoomAs } from './testing/lobby.js'

const T0 = 1_000_000
const FAKE_TIMERS = { toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval'] }
// A start this far ahead of the clock: the source is scheduled at once but
// the song is not audible until the clock passes it.
const AHEAD = 2000
// The synced offset the fake server reports through pong (server ahead 2.5 s).
const SYNC = 2500
const RTT = 20

beforeEach(() => {
  window.history.pushState({}, '', '/')
  window.localStorage.clear()
  window.sessionStorage.clear()
})

afterEach(() => {
  vi.restoreAllMocks()
  cleanup()
  vi.useRealTimers()
  window.history.pushState({}, '', '/')
  window.localStorage.clear()
  window.sessionStorage.clear()
})

// A fetch whose calls stay pending until release() (oldest first).
function createDeferredFetch() {
  const urls = []
  const pending = []
  const fetchFn = vi.fn((url) => {
    urls.push(url)
    return new Promise((resolve) => pending.push(resolve))
  })
  function release() {
    const resolve = pending.shift()
    if (!resolve) throw new Error('no pending fetch')
    const buffer = new ArrayBuffer(8)
    resolve({ ok: true, status: 200, arrayBuffer: async () => buffer })
  }
  return { fetchFn, urls, pending, release }
}

// Renders App with fakes. clock.t is what now() returns.
function setup({ ctxOptions, fetchOptions, fetch } = {}) {
  const sockets = createSocketFactory()
  const audio = createAudioContextFactory(ctxOptions)
  const theFetch = fetch === undefined ? createFakeFetch(fetchOptions) : fetch
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
  return { sockets, audio, fetch: theFetch, clock }
}

function deliver(socket, msg) {
  act(() => socket.receive(msg))
}

const PLAYERS = [{ name: '하루', score: 0, host: true, connected: true }]
const roster = (phase) => ({ type: 'roster', phase, players: PLAYERS })

// Creates a room alone as 하루; the game starts and prepare{0} is delivered.
async function gamePrepared(env) {
  createRoomAs('하루')
  await waitFor(() => expect(env.sockets.sockets.length).toBe(1))
  const socket = env.sockets.last
  act(() => socket.open())
  await waitFor(() => expect(socket.sentOfType('create')).toHaveLength(1))
  deliver(socket, { type: 'joined', code: 'QWERT', name: '하루' })
  deliver(socket, roster('lobby'))
  deliver(socket, roster('playing'))
  deliver(socket, { type: 'prepare', index: 0, total: 5, audioUrl: '/audio/abc' })
  return socket
}

// The server starts the song (it does not wait for a slow client's ready).
// The receipt offset is 500, so the song starts startInMs after the clock
// by the local clock.
function deliverStart(env, socket, { index = 0, startInMs = 0 } = {}) {
  const serverTime = env.clock.t + 500
  const startAt = serverTime + startInMs
  deliver(socket, { type: 'start', index, startAt, deadlineAt: startAt + 90_000, serverTime })
}

function deck() {
  const area = document.querySelector('.deck')
  expect(area).not.toBeNull()
  return area
}

// The disc canvas and its mark in the deck, and the three equalizer bars in
// the header logo.
function expectDiscDrawn() {
  const area = deck()
  expect(area.querySelector('.player .disc canvas')).not.toBeNull()
  expect(area.querySelector('.mark')).not.toBeNull()
  const eq = document.querySelector('header .logo .eq')
  expect(eq).not.toBeNull()
  expect(eq.querySelectorAll('i')).toHaveLength(3)
}

// The song is audible: body.playing moves the disc and the bars.
function playing() {
  return document.body.classList.contains('playing')
}

// The element showing the loading text, checked to be a .hint or .sub in the panel.
function loadText() {
  const el = screen.getByText(ko.stage.loading)
  expect(el.classList.contains('hint') || el.classList.contains('sub'), 'the load text is a .hint or .sub').toBe(true)
  expect(el.closest('.panel')).not.toBeNull()
  return el
}

async function settle(ms = 50) {
  await act(async () => {
    await new Promise((resolve) => setTimeout(resolve, ms))
  })
}

// Moves the clock past a start made with startInMs AHEAD and waits until the
// disc is no longer paused (the 250 ms tick re-renders).
async function passStartAndExpectMoving(env, startClock) {
  env.clock.t = startClock + AHEAD + 100
  await waitFor(() => expect(playing()).toBe(true))
  expectDiscDrawn()
}

describe('loading', () => {
  it('a pending decode after start: paused (no body.playing); after the decode and the source start still paused before startAt; body.playing once the clock passes it', async () => {
    const env = setup({ ctxOptions: { deferDecode: true } })
    const socket = await gamePrepared(env)
    const ctx = env.audio.last
    await waitFor(() => expect(ctx.pendingDecodes).toHaveLength(1))
    const startClock = env.clock.t
    deliverStart(env, socket, { startInMs: AHEAD })
    expect(loadText()).toBeTruthy()
    expectDiscDrawn()
    expect(playing()).toBe(false)
    await act(async () => ctx.resolveDecode())
    await waitFor(() => expect(ctx.startedSources()).toHaveLength(1))
    await settle(300)
    // Scheduled but not yet audible: still paused.
    expect(playing()).toBe(false)
    await passStartAndExpectMoving(env, startClock)
  })

  it('a pending fetch after start: paused (no body.playing); after the fetch and the source start still paused before startAt; body.playing once the clock passes it', async () => {
    const fetch = createDeferredFetch()
    const env = setup({ fetch })
    const socket = await gamePrepared(env)
    await waitFor(() => expect(fetch.pending).toHaveLength(1))
    const startClock = env.clock.t
    deliverStart(env, socket, { startInMs: AHEAD })
    expectDiscDrawn()
    expect(playing()).toBe(false)
    await settle()
    // Still loading: still paused.
    expect(playing()).toBe(false)
    await act(async () => fetch.release())
    const ctx = env.audio.last
    await waitFor(() => expect(ctx.startedSources()).toHaveLength(1))
    await settle(300)
    expect(playing()).toBe(false)
    await passStartAndExpectMoving(env, startClock)
  })

  it('a song loaded before start: paused after the source start until the clock passes startAt, then body.playing', async () => {
    const env = setup()
    const socket = await gamePrepared(env)
    await waitFor(() => expect(socket.sentOfType('ready')).toEqual([{ type: 'ready', index: 0 }]))
    const startClock = env.clock.t
    deliverStart(env, socket, { startInMs: AHEAD })
    await waitFor(() => expect(env.audio.last.startedSources()).toHaveLength(1))
    await settle(300)
    expectDiscDrawn()
    expect(playing()).toBe(false)
    await passStartAndExpectMoving(env, startClock)
  })
})

describe('failed load', () => {
  it('decode rejected twice after start stays paused (no body.playing)', async () => {
    const env = setup({ ctxOptions: { deferDecode: true } })
    const socket = await gamePrepared(env)
    const ctx = env.audio.last
    await waitFor(() => expect(ctx.pendingDecodes).toHaveLength(1))
    deliverStart(env, socket)
    expect(playing()).toBe(false)
    await act(async () => ctx.rejectDecode())
    // The one retry decodes again.
    await waitFor(() => expect(ctx.pendingDecodes).toHaveLength(1))
    await act(async () => ctx.rejectDecode())
    await waitFor(() => expect(screen.getByText(ko.stage.loadFailed)).toBeTruthy())
    await settle()
    expect(ctx.startedSources()).toEqual([])
    expectDiscDrawn()
    expect(playing()).toBe(false)
  })

  it('fetch rejected twice, then start: paused (no body.playing)', async () => {
    const env = setup({ fetchOptions: { failures: 2, failWith: 'reject' } })
    const socket = await gamePrepared(env)
    await waitFor(() => expect(socket.sentOfType('ready')).toEqual([{ type: 'ready', index: 0 }]))
    expect(screen.getByText(ko.stage.loadFailed)).toBeTruthy()
    deliverStart(env, socket)
    await settle()
    expect(env.audio.last.startedSources()).toEqual([])
    expectDiscDrawn()
    expect(playing()).toBe(false)
  })

  it('fetch answered 500 twice, then start: paused (no body.playing)', async () => {
    const env = setup({ fetchOptions: { failures: 2, failWith: 'status' } })
    const socket = await gamePrepared(env)
    await waitFor(() => expect(socket.sentOfType('ready')).toEqual([{ type: 'ready', index: 0 }]))
    deliverStart(env, socket)
    await settle()
    expect(env.audio.last.startedSources()).toEqual([])
    expectDiscDrawn()
    expect(playing()).toBe(false)
  })
})

// 087: disc drawn from prepare.
const revealMsg = {
  type: 'reveal',
  index: 0,
  reveal: '진격의 거인',
  titles: { ko: ['진격의 거인'], en: ['Attack on Titan'], ja: ['進撃の巨人'], romaji: ['Shingeki no Kyojin'] },
  song: '紅蓮の弓矢',
  artists: ['Revo', 'Linked Horizon'],
  themeType: 'OP',
  themeSlug: 'OP1',
  winner: null,
}

describe('prepare, before start', () => {
  it('audio loaded before start: the disc is drawn, paused (no body.playing)', async () => {
    const env = setup()
    const socket = await gamePrepared(env)
    await waitFor(() => expect(socket.sentOfType('ready')).toEqual([{ type: 'ready', index: 0 }]))
    await settle()
    expect(env.audio.last.startedSources()).toEqual([])
    expectDiscDrawn()
    expect(playing()).toBe(false)
  })

  it('audio still loading before start (pending fetch): the disc is drawn, paused (no body.playing)', async () => {
    const fetch = createDeferredFetch()
    const env = setup({ fetch })
    await gamePrepared(env)
    await waitFor(() => expect(fetch.pending).toHaveLength(1))
    expect(screen.getByText(ko.stage.loading)).toBeTruthy()
    expectDiscDrawn()
    expect(playing()).toBe(false)
  })

  it('audio still loading before start (pending decode): the disc is drawn, paused (no body.playing)', async () => {
    const env = setup({ ctxOptions: { deferDecode: true } })
    await gamePrepared(env)
    const ctx = env.audio.last
    await waitFor(() => expect(ctx.pendingDecodes).toHaveLength(1))
    expectDiscDrawn()
    expect(playing()).toBe(false)
  })

  it('audio loaded before start, then start: paused until the clock passes startAt, then body.playing', async () => {
    const env = setup()
    const socket = await gamePrepared(env)
    await waitFor(() => expect(socket.sentOfType('ready')).toEqual([{ type: 'ready', index: 0 }]))
    expectDiscDrawn()
    expect(playing()).toBe(false)
    const startClock = env.clock.t
    deliverStart(env, socket, { startInMs: AHEAD })
    await waitFor(() => expect(env.audio.last.startedSources()).toHaveLength(1))
    await settle(300)
    expectDiscDrawn()
    expect(playing()).toBe(false)
    await passStartAndExpectMoving(env, startClock)
  })
})

describe('reveal', () => {
  it('after the reveal the disc stands still and the reveal card shows', async () => {
    const env = setup()
    const socket = await gamePrepared(env)
    await waitFor(() => expect(socket.sentOfType('ready')).toEqual([{ type: 'ready', index: 0 }]))
    deliverStart(env, socket)
    await waitFor(() => expect(env.audio.last.startedSources()).toHaveLength(1))
    expectDiscDrawn()
    deliver(socket, revealMsg)
    await waitFor(() => expect(document.querySelector('.panel .reveal')).not.toBeNull())
    expect(playing()).toBe(false)
  })
})

// 087 attempt 2: audible means the sound is not blocked either.
describe('sound blocked', () => {
  it('the AudioContext stays suspended: paused even after the clock passes startAt', async () => {
    const env = setup({ ctxOptions: { state: 'suspended', deferResume: true } })
    const socket = await gamePrepared(env)
    await waitFor(() => expect(socket.sentOfType('ready')).toEqual([{ type: 'ready', index: 0 }]))
    const ctx = env.audio.last
    deliverStart(env, socket)
    env.clock.t += 1000
    await settle(400)
    expect(ctx.state).toBe('suspended')
    expect(screen.getByRole('button', { name: ko.stage.unlockSound })).toBeTruthy()
    expectDiscDrawn()
    expect(playing()).toBe(false)
  })
})

// 087 attempt 2: the load text while the disc is shown (110: in the panel).
describe('load text', () => {
  it('loading before start: the panel shows the loading text', async () => {
    const fetch = createDeferredFetch()
    const env = setup({ fetch })
    await gamePrepared(env)
    await waitFor(() => expect(fetch.pending).toHaveLength(1))
    expectDiscDrawn()
    expect(loadText().textContent).toBe(ko.stage.loading)
  })

  it('loaded before start: no loading text', async () => {
    const env = setup()
    const socket = await gamePrepared(env)
    await waitFor(() => expect(socket.sentOfType('ready')).toEqual([{ type: 'ready', index: 0 }]))
    expectDiscDrawn()
    expect(screen.queryByText(ko.stage.loading)).toBeNull()
  })

  it('loaded and playing: no loading text', async () => {
    const env = setup()
    const socket = await gamePrepared(env)
    await waitFor(() => expect(socket.sentOfType('ready')).toEqual([{ type: 'ready', index: 0 }]))
    const startClock = env.clock.t
    deliverStart(env, socket, { startInMs: AHEAD })
    await waitFor(() => expect(env.audio.last.startedSources()).toHaveLength(1))
    await passStartAndExpectMoving(env, startClock)
    expect(screen.queryByText(ko.stage.loading)).toBeNull()
  })
})

// 087 attempt 2: a mid-game joiner watching the current song.
describe('late join (watching)', () => {
  it('a playing snapshot before the late start: the disc is drawn and paused; after the first pong it plays and moves', async () => {
    const env = setup()
    joinRoomAs('Hana', 'ABCDE')
    await waitFor(() => expect(env.sockets.sockets.length).toBe(1))
    const socket = env.sockets.last
    act(() => socket.open())
    await waitFor(() => expect(socket.sentOfType('join')).toHaveLength(1))
    deliver(socket, { type: 'joined', code: 'ABCDE', name: 'Hana' })
    // 10 s in by the synced clock once the clock reads T0 + RTT.
    const startAt = T0 + RTT + SYNC - 10_000
    deliver(socket, {
      type: 'snapshot',
      phase: 'playing',
      players: PLAYERS,
      song: { index: 2, total: 5, audioUrl: '/audio/watch', startAt, deadlineAt: startAt + 90_000 },
      scores: [],
    })
    deliver(socket, roster('playing'))
    const ctx = env.audio.last
    await waitFor(() => expect(ctx.decoded).toHaveLength(1))
    await settle()
    expect(ctx.startedSources()).toEqual([])
    expectDiscDrawn()
    expect(playing()).toBe(false)

    await waitFor(() => expect(socket.sentOfType('ping').length).toBeGreaterThan(0))
    const ping = socket.sentOfType('ping')[0]
    env.clock.t = T0 + RTT
    deliver(socket, { type: 'pong', id: ping.id, serverTime: T0 + RTT / 2 + SYNC })
    await waitFor(() => expect(ctx.startedSources()).toHaveLength(1))
    await waitFor(() => expect(playing()).toBe(true))
    expectDiscDrawn()
  })
})

// 087 attempt 2: a rejoin rebuilds from the snapshot.
describe('rejoin', () => {
  beforeEach(() => {
    vi.useFakeTimers(FAKE_TIMERS)
  })

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

  // The lobby as host 하루 of QWERT with token T1; then a drop and, 500 ms
  // later, the first reconnect attempt opened and joined again.
  async function rejoined(env) {
    createRoomAs('하루')
    await flushUntil(() => env.sockets.sockets.length === 1)
    const socket = env.sockets.last
    act(() => socket.open())
    await flushUntil(() => socket.sent.length > 0)
    deliver(socket, { type: 'joined', code: 'QWERT', name: '하루' })
    deliver(socket, roster('lobby'))
    deliver(socket, { type: 'token', token: 'T1' })
    act(() => socket.serverClose(1006))
    await advance(env, 500)
    expect(env.sockets.sockets).toHaveLength(2)
    const attempt = env.sockets.last
    act(() => attempt.open())
    await flushUntil(() => attempt.sent.length > 0)
    expect(attempt.sent).toEqual([{ type: 'rejoin', token: 'T1' }])
    deliver(attempt, { type: 'joined', code: 'QWERT', name: '하루' })
    return attempt
  }

  it('a playing snapshot whose start is ahead: drawn and paused before the pong, still paused after the late start until the clock passes startAt, then moving', async () => {
    const env = setup()
    const attempt = await rejoined(env)
    // By the synced offset the song starts AHEAD ms after the pong's clock.
    const startAt = env.clock.t + RTT + SYNC + AHEAD
    deliver(attempt, {
      type: 'snapshot',
      phase: 'playing',
      players: PLAYERS,
      song: { index: 0, total: 5, audioUrl: '/audio/re', startAt, deadlineAt: startAt + 90_000 },
      scores: [],
    })
    const ctx = env.audio.last
    await flushUntil(() => ctx.decoded.length === 1)
    expect(ctx.decoded).toHaveLength(1)
    expect(ctx.startedSources()).toEqual([])
    expectDiscDrawn()
    expect(playing()).toBe(false)

    const pings = attempt.sentOfType('ping')
    expect(pings.length).toBeGreaterThan(0)
    const ping = pings[pings.length - 1]
    const t0 = env.clock.t
    env.clock.t += RTT
    deliver(attempt, { type: 'pong', id: ping.id, serverTime: t0 + RTT / 2 + SYNC })
    await flushUntil(() => ctx.startedSources().length === 1)
    expect(ctx.startedSources()).toHaveLength(1)
    await advance(env, 300)
    expectDiscDrawn()
    expect(playing()).toBe(false)

    await advance(env, AHEAD)
    expectDiscDrawn()
    expect(playing()).toBe(true)
  })

  it('a song:null snapshot (waiting for the next song): no playing disc', async () => {
    const env = setup()
    const attempt = await rejoined(env)
    deliver(attempt, { type: 'snapshot', phase: 'playing', players: PLAYERS, song: null, scores: [] })
    await flushUntil(() => false, 5)
    expect(screen.getByText(ko.stage.waitingNext)).toBeTruthy()
    expect(playing()).toBe(false)
    const area = document.querySelector('.deck')
    expect(area === null || area.classList.contains('idle'), 'no .deck, or an .idle one').toBe(true)
  })
})
