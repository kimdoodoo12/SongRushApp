// Cycle 135 (F-038, D-133): the next song's covers load during the reveal.
// Spec docs/cycles/135-preload-next-covers.md, acceptance criteria 1 to 6
// with 5a.
//
// Contract with the implementer:
// - A choice-room reveal (live, or a snapshot's song.reveal on a rejoin or a
//   late join) carrying nextChoices: [{coverUrl}] starts at once one
//   `new Image()` per cover whose URL passes the allow-list (choiceUrls),
//   with src set to the coverUrl. Nothing of it reaches the screen.
// - At the next prepare, a cover already loaded during the reveal makes no
//   new request and does not hold ready back; one still loading makes no new
//   request and holds ready until it settles; one that failed is requested
//   again and holds ready as today.
// - A reveal without nextChoices requests nothing; a chat room is unchanged.
// - The store is cleared at gameOver: a new game's covers load again.
//
// The global Image is replaced with FakeImage (as App.choice.test.jsx and
// App.prefetch.test.jsx); jsdom never loads images, so each is settled by
// hand. Tiles on screen are React <img> elements, not FakeImage instances.
// Timer rule: fake timers in every test, with the injected now() moved
// together with them (as App.prefetch.test.jsx).
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, act, cleanup } from '@testing-library/react'
import App from './App.jsx'
import { createSocketFactory, createAudioContextFactory, createFakeFetch } from './testing/fakes.js'
import { createRoomAs, joinRoomAs } from './testing/lobby.js'

const T0 = 1_000_000
const FAKE_TIMERS = { toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval'] }
const HOLD = 5000
const REVEAL_HEADING = '정답 공개'
const tileName = (n) => `보기 ${n}`
const STORAGE_KEY = 'songrush.rejoin'
const SYNC = 2500
const RTT = 20
const ELAPSED = 10_000

const COVERS4 = ['/cover/a1', '/cover/b2', '/cover/c3', '/cover/d4']
// The next song's covers, all different from song 0's.
const NEXT4 = ['/cover/n1', '/cover/n2', '/cover/n3', '/cover/n4']
const choicesOf = (urls) => urls.map((coverUrl) => ({ coverUrl }))
const MARKS = ['correct', 'wrong', 'dim', 'locked']

// A stand-in for the browser's Image. Every instance is recorded; fire()
// settles it the way a real load or error would.
const images = []

class FakeImage {
  constructor() {
    this.onload = null
    this.onerror = null
    this.settled = null
    this.currentSrc = ''
    this.listeners = { load: [], error: [] }
    this.decodePromise = null
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
    if (this.decodePromise === null) {
      this.decodePromise = new Promise((resolve, reject) => {
        this.decodeSettle = { resolve, reject }
      })
    }
    return this.decodePromise
  }

  fire(type) {
    if (this.settled !== null) throw new Error('image already settled')
    this.settled = type
    const event = { type, target: this, currentTarget: this }
    const handler = type === 'load' ? this.onload : this.onerror
    if (typeof handler === 'function') handler.call(this, event)
    for (const fn of [...(this.listeners[type] || [])]) fn.call(this, event)
    if (this.decodeSettle) {
      if (type === 'load') this.decodeSettle.resolve()
      else this.decodeSettle.reject(new Error('image failed'))
    }
  }
}

// The requests (FakeImage instances with a src) for exactly `url`.
function requestsOf(url) {
  return images.filter((img) => img.src !== '' && img.src.endsWith(url))
}

function preloadsOf(urls) {
  return images.filter((img) => urls.some((url) => img.src !== '' && img.src.endsWith(url)))
}

function pendingPreloads(urls) {
  return preloadsOf(urls).filter((img) => img.settled === null)
}

// How many requests were made per URL, in the order of `urls`.
function requestCounts(urls) {
  return urls.map((url) => requestsOf(url).length)
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

function setup({ fetch, navigationType } = {}) {
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
      navigationType={navigationType}
    />,
  )
  return { sockets, audio, fetch: theFetch, clock }
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

// Settles the one pending request of `url` with `type` (load or error).
async function fireOne(url, type) {
  const pending = requestsOf(url).filter((img) => img.settled === null)
  expect(pending, `one pending request for ${url}`).toHaveLength(1)
  await act(async () => {
    pending[0].fire(type)
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

function marks(list) {
  return list.map((b) => {
    const found = MARKS.filter((m) => b.classList.contains(m))
    return found.length === 0 ? null : found.join(' ')
  })
}

function revealShown() {
  return screen.queryByText(REVEAL_HEADING) !== null
}

// Every src or href in the page that names one of `urls`.
function urlsInPage(urls) {
  const found = []
  for (const el of document.body.querySelectorAll('[src], [href], [style]')) {
    const text = `${el.getAttribute('src') ?? ''} ${el.getAttribute('href') ?? ''} ${el.getAttribute('style') ?? ''}`
    for (const url of urls) if (text.includes(url)) found.push(url)
  }
  return found
}

// Host 하루 of QWERT with token T1, playing alone: song 0 prepared (with
// COVERS4 in a choice room), ready, started and audible. Returns the socket
// before the reveal.
async function playedSong(env, { choices = true } = {}) {
  createRoomAs('하루')
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
  return socket
}

// playedSong, then song 0's reveal with `next` as nextChoices (no key when
// null) and correctChoice 2; the scores follow. Returns the socket.
async function revealedWith(env, next, { nextRaw } = {}) {
  const socket = await playedSong(env)
  const extra = { correctChoice: 2 }
  if (nextRaw !== undefined) extra.nextChoices = nextRaw
  else if (next !== null) extra.nextChoices = choicesOf(next)
  deliver(socket, revealMsg(0, extra))
  deliver(socket, { type: 'scores', scores: [{ name: '하루', score: 0 }] })
  expect(revealShown()).toBe(true)
  return socket
}

// The revealed song 0 as the screen shows it, unchanged by nextChoices.
function expectRevealedScreen() {
  expect(revealShown(), 'the reveal card shows').toBe(true)
  expect(tileCount(), 'still song 0 tiles').toBe(4)
  expect(srcs(koTiles(4))).toEqual(COVERS4)
  expect(marks(koTiles(4))).toEqual(['dim', 'dim', 'correct', 'dim'])
  expect(screen.queryByText('2 / 5'), 'no next round label').toBeNull()
}

// React's generated ids differ between two renders in one test file.
function normalizedHtml() {
  return document.body.innerHTML.replace(/«[^»]*»|:r[0-9a-z]+:|_r_[0-9a-z]+_/g, 'ID')
}

describe('a choice-room reveal with nextChoices (1)', () => {
  it('requests the four next covers at once', async () => {
    const env = setup()
    await playedSong(env)
    const before = images.length
    const socket = env.sockets.last
    deliver(socket, revealMsg(0, { correctChoice: 2, nextChoices: choicesOf(NEXT4) }))
    await settle()
    expect(requestCounts(NEXT4), 'one request per next cover').toEqual([1, 1, 1, 1])
    expect(images.length - before, 'only the next covers are requested').toBe(4)
    expect(requestCounts(COVERS4), "song 0's covers are not requested again").toEqual([1, 1, 1, 1])
  })

  it('shows nothing of the next covers: tiles, marks and card as without the key', async () => {
    const env = setup()
    await revealedWith(env, NEXT4)
    await settle()
    expectRevealedScreen()
    expect(urlsInPage(NEXT4), 'no next cover in the page').toEqual([])
    // Loaded next covers still show nothing.
    await act(async () => {
      for (const img of pendingPreloads(NEXT4)) img.fire('load')
    })
    await settle()
    expectRevealedScreen()
    expect(urlsInPage(NEXT4)).toEqual([])
  })

  it('the page is the same as after a reveal without nextChoices', async () => {
    const withKey = setup()
    await revealedWith(withKey, NEXT4)
    await settle()
    const htmlWith = normalizedHtml()
    cleanup()
    images.length = 0
    window.history.pushState({}, '', '/')
    window.localStorage.clear()
    window.sessionStorage.clear()
    const without = setup()
    await revealedWith(without, null)
    await settle()
    expect(htmlWith).toBe(normalizedHtml())
  })
})

describe('the next prepare after the reveal', () => {
  it('(2) covers loaded during the reveal: no new request, ready as soon as the audio decodes', async () => {
    const fetch = createHoldingFetch(['/audio/s1'])
    const env = setup({ fetch })
    const socket = await revealedWith(env, NEXT4)
    await flushUntil(() => preloadsOf(NEXT4).length === 4)
    expect(requestCounts(NEXT4)).toEqual([1, 1, 1, 1])
    await act(async () => {
      for (const img of pendingPreloads(NEXT4)) img.fire('load')
    })
    deliver(socket, prepareMsg(1, NEXT4))
    await flushUntil(() => fetch.pending.has('/audio/s1'))
    expect(fetch.urls).toContain('/audio/s1')
    await settle()
    expect(requestCounts(NEXT4), 'no second request at the prepare').toEqual([1, 1, 1, 1])
    expect(readiesFor(socket, 1), 'no ready while the audio is pending').toEqual([])
    await act(async () => fetch.release('/audio/s1'))
    await flushUntil(() => readiesFor(socket, 1).length === 1)
    expect(readiesFor(socket, 1), 'ready right after the audio, no image fired').toEqual([{ type: 'ready', index: 1 }])
    expect(requestCounts(NEXT4)).toEqual([1, 1, 1, 1])
    // The new round shows its tiles.
    expect(tileCount()).toBe(4)
    expect(srcs(koTiles(4))).toEqual(NEXT4)
  })

  it('(2) a held prepare (holdMs) uses the loaded covers too', async () => {
    const env = setup()
    const socket = await revealedWith(env, NEXT4)
    await flushUntil(() => preloadsOf(NEXT4).length === 4)
    expect(requestCounts(NEXT4)).toEqual([1, 1, 1, 1])
    await act(async () => {
      for (const img of pendingPreloads(NEXT4)) img.fire('load')
    })
    deliver(socket, prepareMsg(1, NEXT4, { holdMs: HOLD }))
    await flushUntil(() => readiesFor(socket, 1).length === 1)
    expect(readiesFor(socket, 1), 'ready during the hold, no image fired').toEqual([{ type: 'ready', index: 1 }])
    expect(requestCounts(NEXT4)).toEqual([1, 1, 1, 1])
  })

  it('(3) one cover still loading: ready waits for it, with no second request', async () => {
    const env = setup()
    const socket = await revealedWith(env, NEXT4)
    await flushUntil(() => preloadsOf(NEXT4).length === 4)
    expect(requestCounts(NEXT4)).toEqual([1, 1, 1, 1])
    await fireOne(NEXT4[0], 'load')
    await fireOne(NEXT4[1], 'load')
    await fireOne(NEXT4[2], 'load')
    deliver(socket, prepareMsg(1, NEXT4))
    await flushUntil(() => env.fetch.urls.includes('/audio/s1'))
    await settle()
    expect(requestCounts(NEXT4), 'no second request for any cover').toEqual([1, 1, 1, 1])
    expect(readiesFor(socket, 1), 'ready waits for the cover still loading').toEqual([])
    await fireOne(NEXT4[3], 'load')
    await flushUntil(() => readiesFor(socket, 1).length === 1)
    expect(readiesFor(socket, 1)).toEqual([{ type: 'ready', index: 1 }])
    expect(requestCounts(NEXT4)).toEqual([1, 1, 1, 1])
  })

  it('(3) the cover still loading fails after the prepare: ready follows, with no second request', async () => {
    const env = setup()
    const socket = await revealedWith(env, NEXT4)
    await flushUntil(() => preloadsOf(NEXT4).length === 4)
    await fireOne(NEXT4[0], 'load')
    await fireOne(NEXT4[1], 'load')
    await fireOne(NEXT4[3], 'load')
    deliver(socket, prepareMsg(1, NEXT4))
    await flushUntil(() => env.fetch.urls.includes('/audio/s1'))
    await settle()
    expect(readiesFor(socket, 1)).toEqual([])
    await fireOne(NEXT4[2], 'error')
    await flushUntil(() => readiesFor(socket, 1).length === 1)
    expect(readiesFor(socket, 1)).toEqual([{ type: 'ready', index: 1 }])
    expect(requestCounts(NEXT4)).toEqual([1, 1, 1, 1])
  })

  it('(4) a cover that failed during the reveal is requested again and ready waits for it', async () => {
    const env = setup()
    const socket = await revealedWith(env, NEXT4)
    await flushUntil(() => preloadsOf(NEXT4).length === 4)
    expect(requestCounts(NEXT4)).toEqual([1, 1, 1, 1])
    await fireOne(NEXT4[0], 'load')
    await fireOne(NEXT4[1], 'error')
    await fireOne(NEXT4[2], 'load')
    await fireOne(NEXT4[3], 'load')
    deliver(socket, prepareMsg(1, NEXT4))
    await flushUntil(() => requestsOf(NEXT4[1]).length === 2)
    await settle()
    expect(requestCounts(NEXT4), 'only the failed cover is requested again').toEqual([1, 2, 1, 1])
    expect(readiesFor(socket, 1), 'ready waits for the new request').toEqual([])
    await fireOne(NEXT4[1], 'load')
    await flushUntil(() => readiesFor(socket, 1).length === 1)
    expect(readiesFor(socket, 1)).toEqual([{ type: 'ready', index: 1 }])
    expect(requestCounts(NEXT4)).toEqual([1, 2, 1, 1])
  })

  it('(4) the new request failing too still lets ready go', async () => {
    const env = setup()
    const socket = await revealedWith(env, NEXT4)
    await flushUntil(() => preloadsOf(NEXT4).length === 4)
    await fireOne(NEXT4[0], 'error')
    await fireOne(NEXT4[1], 'load')
    await fireOne(NEXT4[2], 'load')
    await fireOne(NEXT4[3], 'load')
    deliver(socket, prepareMsg(1, NEXT4))
    await flushUntil(() => requestsOf(NEXT4[0]).length === 2)
    await settle()
    expect(requestCounts(NEXT4)).toEqual([2, 1, 1, 1])
    expect(readiesFor(socket, 1)).toEqual([])
    await fireOne(NEXT4[0], 'error')
    await flushUntil(() => readiesFor(socket, 1).length === 1)
    expect(readiesFor(socket, 1)).toEqual([{ type: 'ready', index: 1 }])
  })

  it('a prepare with other covers than nextChoices loads them as today', async () => {
    const env = setup()
    const socket = await revealedWith(env, NEXT4)
    await flushUntil(() => preloadsOf(NEXT4).length === 4)
    expect(requestCounts(NEXT4)).toEqual([1, 1, 1, 1])
    await act(async () => {
      for (const img of pendingPreloads(NEXT4)) img.fire('load')
    })
    const OTHER = ['/cover/o1', '/cover/o2', '/cover/n1', '/cover/o4']
    deliver(socket, prepareMsg(1, OTHER))
    await flushUntil(() => preloadsOf(['/cover/o1', '/cover/o2', '/cover/o4']).length === 3)
    await settle()
    expect(requestCounts(OTHER)).toEqual([1, 1, 1, 1])
    expect(readiesFor(socket, 1)).toEqual([])
    await act(async () => {
      for (const img of pendingPreloads(OTHER)) img.fire('load')
    })
    await flushUntil(() => readiesFor(socket, 1).length === 1)
    expect(readiesFor(socket, 1)).toEqual([{ type: 'ready', index: 1 }])
  })
})

describe('the allow-list (5)', () => {
  const BAD = [
    'https://evil.example/cover/x1',
    '/cover/../x',
    '/cover/a b',
    '/audio/s1',
    '//evil.example/cover/x2',
    `/cover/${'x'.repeat(65)}`,
  ]

  it('a URL that fails the allow-list is never requested; the valid ones are', async () => {
    const env = setup()
    const raw = [...BAD.map((coverUrl) => ({ coverUrl })), { coverUrl: '/cover/ok1' }, null, { coverUrl: 5 }, {}]
    await revealedWith(env, null, { nextRaw: raw })
    await settle()
    for (const url of BAD) {
      expect(
        images.filter((img) => img.src === url),
        `${url} is not requested`,
      ).toEqual([])
    }
    expect(images.filter((img) => img.src === '/cover/ok1'), 'the valid cover is requested').toHaveLength(1)
    expectRevealedScreen()
  })

  it('nextChoices that is not a list requests nothing and changes nothing', async () => {
    const env = setup()
    const socket = await revealedWith(env, null, { nextRaw: { coverUrl: '/cover/n1' } })
    await settle()
    const before = images.length
    deliver(socket, revealMsg(0, { correctChoice: 2, nextChoices: '/cover/n1' }))
    await settle()
    expect(images.length).toBe(before)
    expect(requestCounts(['/cover/n1'])).toEqual([0])
    expectRevealedScreen()
  })
})

// The snapshot song of a reveal window (as App.windowRestore.test.jsx): with
// the clock as it is now, the synced clock is ELAPSED ms past startAt once
// the first pong is in. The reveal carries correctChoice 2 and NEXT4.
function windowSong(env, { next = NEXT4 } = {}) {
  const startAt = env.clock.t + RTT + SYNC - ELAPSED
  const estimatedNow = startAt + ELAPSED
  return {
    index: 0,
    total: 5,
    audioUrl: '/audio/re',
    startAt,
    deadlineAt: startAt + 90_000,
    choices: choicesOf(COVERS4),
    picked: null,
    // The broadcast reveal frame without type.
    reveal: Object.fromEntries(
      Object.entries({
        ...revealMsg(0, { winner: 'Mina', coverUrl: null, correctChoice: 2, playUntil: estimatedNow + 50_000 }),
        ...(next === null ? {} : { nextChoices: choicesOf(next) }),
      }).filter(([key]) => key !== 'type'),
    ),
    skipState: { votes: 0, needed: 2 },
  }
}

const snapshot = (song, players) => ({ type: 'snapshot', phase: 'playing', players, song, scores: [] })
const ROSTER = [player('하루', { host: true }), player('Mina')]
const WATCH_PLAYERS = [player('Mina', { host: true }), player('하나')]

describe('a snapshot reveal with nextChoices (5a)', () => {
  it('a rejoin snapshot (reload) requests the next covers at once, and the next prepare reuses them', async () => {
    window.sessionStorage.setItem(STORAGE_KEY, JSON.stringify({ token: 'T1', code: 'ABCDE' }))
    const env = setup({ navigationType: 'reload' })
    await flushUntil(() => env.sockets.sockets.length === 1)
    const socket = env.sockets.last
    act(() => socket.open())
    await flushUntil(() => socket.sent.length > 0)
    expect(socket.sent).toEqual([{ type: 'rejoin', token: 'T1' }])
    deliver(socket, { type: 'joined', code: 'ABCDE', name: '하루' })
    deliver(socket, snapshot(windowSong(env), ROSTER))
    deliver(socket, roster(ROSTER, 'playing'))
    await settle()
    expect(revealShown(), 'the snapshot card shows').toBe(true)
    expect(requestCounts(NEXT4), 'the next covers are requested at once, before any pong').toEqual([1, 1, 1, 1])
    expect(urlsInPage(NEXT4)).toEqual([])
    await act(async () => {
      for (const img of pendingPreloads([...COVERS4, ...NEXT4])) img.fire('load')
    })
    deliver(socket, prepareMsg(1, NEXT4))
    await flushUntil(() => readiesFor(socket, 1).length === 1)
    expect(readiesFor(socket, 1), 'ready with no image fired after the prepare').toEqual([{ type: 'ready', index: 1 }])
    expect(requestCounts(NEXT4)).toEqual([1, 1, 1, 1])
  })

  it('a watching snapshot (late join) requests the next covers at once, and its first prepare reuses them', async () => {
    const env = setup()
    joinRoomAs('하나', 'ABCDE')
    await flushUntil(() => env.sockets.sockets.length === 1)
    const socket = env.sockets.last
    act(() => socket.open())
    await flushUntil(() => socket.sent.length > 0)
    expect(socket.sent[0]).toEqual({ type: 'join', code: 'ABCDE', nickname: '하나' })
    deliver(socket, { type: 'joined', code: 'ABCDE', name: '하나' })
    deliver(socket, snapshot(windowSong(env), WATCH_PLAYERS))
    deliver(socket, roster(WATCH_PLAYERS, 'playing'))
    await settle()
    expect(revealShown(), 'the snapshot card shows').toBe(true)
    expect(requestCounts(NEXT4), 'the next covers are requested at once').toEqual([1, 1, 1, 1])
    expect(urlsInPage(NEXT4)).toEqual([])
    await act(async () => {
      for (const img of pendingPreloads([...COVERS4, ...NEXT4])) img.fire('load')
    })
    deliver(socket, prepareMsg(1, NEXT4))
    await flushUntil(() => readiesFor(socket, 1).length === 1)
    expect(readiesFor(socket, 1)).toEqual([{ type: 'ready', index: 1 }])
    expect(requestCounts(NEXT4)).toEqual([1, 1, 1, 1])
  })

  it('a snapshot reveal without nextChoices requests no next cover', async () => {
    const env = setup()
    joinRoomAs('하나', 'ABCDE')
    await flushUntil(() => env.sockets.sockets.length === 1)
    const socket = env.sockets.last
    act(() => socket.open())
    await flushUntil(() => socket.sent.length > 0)
    deliver(socket, { type: 'joined', code: 'ABCDE', name: '하나' })
    deliver(socket, snapshot(windowSong(env, { next: null }), WATCH_PLAYERS))
    await settle()
    expect(revealShown()).toBe(true)
    expect(requestCounts(NEXT4)).toEqual([0, 0, 0, 0])
    expect(preloadsOf(COVERS4).length, "only the snapshot song's own covers").toBe(images.length)
  })
})

describe('no nextChoices (6)', () => {
  it('a choice-room reveal without nextChoices requests no image', async () => {
    const env = setup()
    const socket = await playedSong(env)
    const before = images.length
    deliver(socket, revealMsg(0, { correctChoice: 2 }))
    await settle()
    expect(images.length, 'no image requested at the reveal').toBe(before)
    expectRevealedScreen()
  })

  it('a chat room: no image at all, and the next prepare sends ready as before', async () => {
    const env = setup()
    const socket = await playedSong(env, { choices: false })
    deliver(socket, revealMsg(0))
    await settle()
    expect(revealShown()).toBe(true)
    expect(images, 'a chat room requests no image').toEqual([])
    deliver(socket, prepareMsg(1, null))
    expect(revealShown()).toBe(false)
    expect(screen.queryByText('2 / 5')).not.toBeNull()
    await flushUntil(() => readiesFor(socket, 1).length === 1)
    expect(readiesFor(socket, 1)).toEqual([{ type: 'ready', index: 1 }])
    expect(images).toEqual([])
  })
})

describe('the store ends with the game', () => {
  it('after gameOver a new game requests its covers again, and ready waits for them', async () => {
    const env = setup()
    const socket = await revealedWith(env, NEXT4)
    await flushUntil(() => preloadsOf(NEXT4).length === 4)
    await act(async () => {
      for (const img of pendingPreloads(NEXT4)) img.fire('load')
    })
    deliver(socket, { type: 'gameOver', ranking: [{ rank: 1, name: '하루', score: 0 }] })
    deliver(socket, roster(ALONE))
    deliver(socket, roster(ALONE, 'playing'))
    const before = requestCounts(NEXT4)
    deliver(socket, prepareMsg(0, NEXT4))
    await flushUntil(() => requestCounts(NEXT4).every((n, i) => n === before[i] + 1))
    await settle()
    expect(requestCounts(NEXT4), 'each cover is requested again').toEqual(before.map((n) => n + 1))
    expect(readiesFor(socket, 0), 'only song 0 of the first game').toHaveLength(1)
    await act(async () => {
      for (const img of pendingPreloads(NEXT4)) img.fire('load')
    })
    await flushUntil(() => readiesFor(socket, 0).length === 2)
    expect(readiesFor(socket, 0)).toHaveLength(2)
  })
})
