// Cycle 131 (F-037, D-129): a reload, a reconnect or a late join in a reveal
// window. Spec docs/cycles/131-window-restore.md, acceptance criteria 1 to 7.
//
// Contract with the implementer:
// - A playing snapshot whose song carries `reveal` (the broadcast reveal
//   frame without type, playUntil included) and `skipState{votes, needed}`
//   shows the answer card from song.reveal at once, before any clock
//   estimate: no source, no time bar, no body.playing yet. The snapshot's
//   skipState sets the count at once; a later skipState for that index
//   overrides it. The own vote starts as not voted.
// - At the first clock estimate, when min(playUntil, deadlineAt) is later
//   than now, the source starts late from the time since startAt, the time
//   bar counts down and body has playing until playUntil; a player (not a
//   watcher) sees the skip button. Otherwise nothing plays; the card stays.
// - No "n번째 곡 시작" line for a song whose answer is already out.
// - A choice-room snapshot with reveal.correctChoice marks that tile as a
//   live reveal does; a tile click sends no pick.
//
// The rejoin here is a page reload (App.reload.test.jsx): it starts from a
// fresh log, so a song-start line can only come from the snapshot's song.
// Timer rule: fake timers in every test, with the injected now() moved
// together with them (as App.window.test.jsx).
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, act, cleanup } from '@testing-library/react'
import App from './App.jsx'
import { createSocketFactory, createAudioContextFactory, createFakeFetch } from './testing/fakes.js'
import { joinRoomAs } from './testing/lobby.js'

const T0 = 1_000_000
const FAKE_TIMERS = { toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval'] }
const STORAGE_KEY = 'songrush.rejoin'
const ENTRY = { token: 'T1', code: 'ABCDE' }
const CTX_TIME = 10
// The synced offset the fake server reports through pong (server ahead 2.5 s).
const SYNC = 2500
const RTT = 20
// How far into the song the synced clock is when the pong is answered.
const ELAPSED = 10_000
const REVEAL_HEADING = '정답 공개'
const ANY_SKIP = /^스킵/
const SKIP = '스킵'
const tileName = (n) => `보기 ${n}`
const COVERS4 = ['/cover/a1', '/cover/b2', '/cover/c3', '/cover/d4']
const choicesOf = (urls) => urls.map((coverUrl) => ({ coverUrl }))
const MARKS = ['correct', 'wrong', 'dim', 'locked']

beforeEach(() => {
  window.history.pushState({}, '', '/')
  window.localStorage.clear()
  window.sessionStorage.clear()
  vi.useFakeTimers(FAKE_TIMERS)
})

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
  vi.useRealTimers()
  window.history.pushState({}, '', '/')
  window.localStorage.clear()
  window.sessionStorage.clear()
})

function setup(navigationType) {
  const sockets = createSocketFactory()
  const audio = createAudioContextFactory({ currentTime: CTX_TIME })
  const fetch = createFakeFetch()
  const clock = { t: T0 }
  render(
    <App
      canPlayOpus={true}
      socketFactory={sockets.factory}
      audioContextFactory={audio.factory}
      fetchFn={fetch.fetchFn}
      now={() => clock.t}
      navigationType={navigationType}
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

// Runs pending microtasks a few times, so anything already queued has run.
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

// Answers the latest ping, sent at the current clock, after RTT ms with the
// SYNC offset; the clock is left RTT ms later.
function answerLatestPing(env, socket) {
  const sent = socket.sentOfType('ping')
  expect(sent.length, 'a ping should have been sent').toBeGreaterThan(0)
  const ping = sent[sent.length - 1]
  const t0 = env.clock.t
  env.clock.t += RTT
  deliver(socket, { type: 'pong', id: ping.id, serverTime: t0 + RTT / 2 + SYNC })
}

const player = (name, { host = false } = {}) => ({ name, score: 0, host, connected: true })
const roster = (players, phase = 'playing') => ({ type: 'roster', phase, players })
// The reload rejoin: 하루 hosts with Mina.
const ROSTER = [player('하루', { host: true }), player('Mina')]
// The late join: Mina hosts; the player joins as 하나.
const WATCH_PLAYERS = [player('Mina', { host: true }), player('하나')]

const revealOf = (index, extra = {}) => ({
  index,
  reveal: '진격의 거인',
  titles: { ko: ['진격의 거인'], en: ['Attack on Titan'] },
  song: '紅蓮の弓矢',
  artists: ['Revo'],
  themeType: 'OP',
  themeSlug: 'OP1',
  winner: 'Mina',
  coverUrl: null,
  ...extra,
})

// A snapshot song in a reveal window. With the clock at its value now, once
// answerLatestPing has run the synced clock is ELAPSED ms past startAt, and
// playUntil is playUntilIn ms after that.
function windowSong(env, { index = 0, playUntilIn = 50_000, votes = 1, extra = {}, revealExtra = {} } = {}) {
  const startAt = env.clock.t + RTT + SYNC - ELAPSED
  const estimatedNow = startAt + ELAPSED
  return {
    index,
    total: 5,
    audioUrl: '/audio/re',
    startAt,
    deadlineAt: startAt + 90_000,
    ...extra,
    reveal: revealOf(index, { playUntil: estimatedNow + playUntilIn, ...revealExtra }),
    skipState: { votes, needed: 2 },
  }
}

const snapshot = (song, players) => ({ type: 'snapshot', phase: 'playing', players, song, scores: [] })

function pageText() {
  return document.body.textContent
}

function bodyPlaying() {
  return document.body.classList.contains('playing')
}

function revealShown() {
  return screen.queryByText(REVEAL_HEADING) !== null
}

function barNow() {
  const bar = screen.queryByRole('progressbar')
  return bar === null ? null : Number(bar.getAttribute('aria-valuenow'))
}

function skipButton() {
  return screen.queryByRole('button', { name: ANY_SKIP })
}

// The skip button's label; fails by assertion when there is no button.
function skipLabel() {
  const button = skipButton()
  expect(button, 'a skip button shows').not.toBeNull()
  return button.textContent
}

// stop() or stop(t) with t no later than the context's now.
function immediateStops(source, ctx) {
  return source.stop.mock.calls.filter((call) => call.length === 0 || call[0] <= ctx.currentTime).length
}

// The mockup's startRound line for song index i (chat.songStart).
const songStartLine = (index) => `${index + 1}번째 곡 시작`

function koTiles(count) {
  return Array.from({ length: count }, (_, i) => screen.getByRole('button', { name: tileName(i + 1) }))
}

function marks(list) {
  return list.map((b) => {
    const found = MARKS.filter((m) => b.classList.contains(m))
    return found.length === 0 ? null : found.join(' ')
  })
}

// Checks the card from the snapshot's reveal is on screen.
function expectCard() {
  expect(revealShown(), 'the answer card shows').toBe(true)
  expect(screen.queryAllByText('진격의 거인').length, 'the snapshot title shows').toBeGreaterThan(0)
}

// A reload with a rejoin entry: the rejoin socket opened, joined, and the
// given snapshot song delivered with the roster. No pong yet. The App must
// come from setupReload.
async function reloadInto(env, song) {
  await flushUntil(() => env.sockets.sockets.length === 1)
  expect(env.sockets.sockets).toHaveLength(1)
  const socket = env.sockets.last
  act(() => socket.open())
  await flushUntil(() => socket.sent.length > 0)
  expect(socket.sent).toEqual([{ type: 'rejoin', token: 'T1' }])
  deliver(socket, { type: 'joined', code: 'ABCDE', name: '하루' })
  deliver(socket, snapshot(song, ROSTER))
  deliver(socket, roster(ROSTER))
  const ctx = env.audio.last
  await flushUntil(() => ctx.decoded.length === 1)
  return { socket, ctx }
}

// Renders a reload with the entry seeded before mount.
function setupReload() {
  window.sessionStorage.setItem(STORAGE_KEY, JSON.stringify(ENTRY))
  return setup('reload')
}

// A late join as 하나 into ABCDE: joined, the snapshot song and the roster.
async function lateJoinInto(env, songOf) {
  joinRoomAs('하나', 'ABCDE')
  await flushUntil(() => env.sockets.sockets.length === 1)
  const socket = env.sockets.last
  act(() => socket.open())
  await flushUntil(() => socket.sent.length > 0)
  expect(socket.sent[0]).toEqual({ type: 'join', code: 'ABCDE', nickname: '하나' })
  deliver(socket, { type: 'joined', code: 'ABCDE', name: '하나' })
  const song = songOf(env)
  deliver(socket, snapshot(song, WATCH_PLAYERS))
  deliver(socket, roster(WATCH_PLAYERS))
  const ctx = env.audio.last
  await flushUntil(() => ctx.decoded.length === 1)
  return { socket, ctx, song }
}

describe('a rejoin in a reveal window (1)', () => {
  it('shows the card at once, then plays the rest late with the time bar, playing and the skip button at 1/2', async () => {
    const env = setupReload()
    const song = windowSong(env)
    const { socket, ctx } = await reloadInto(env, song)
    await settle()
    // Before the clock estimate.
    expectCard()
    expect(ctx.startedSources(), 'no source before the clock estimate').toEqual([])
    expect(screen.queryByRole('progressbar'), 'no time bar before the clock estimate').toBeNull()
    expect(bodyPlaying()).toBe(false)

    answerLatestPing(env, socket)
    await flushUntil(() => ctx.startedSources().length === 1)
    expect(ctx.startedSources(), 'the rest of the song plays').toHaveLength(1)
    const source = ctx.startedSources()[0]
    const [when, offset] = source.start.mock.calls[0]
    expect(when).toBeCloseTo(CTX_TIME, 6)
    expect(offset, 'from the time since startAt').toBeCloseTo(ELAPSED / 1000, 3)
    await flushUntil(() => screen.queryByRole('progressbar') !== null)
    expect(screen.queryByRole('progressbar'), 'the time bar shows').not.toBeNull()
    const before = barNow()
    await advance(env, 1000)
    expect(barNow(), 'the time bar counts down').toBeLessThan(before)
    expect(bodyPlaying(), 'body has playing in the window').toBe(true)
    expectCard()
    expect(immediateStops(source, ctx)).toBe(0)
    expect(skipLabel(), 'the own vote starts as not voted').toBe(SKIP)
    expect(screen.queryByText('스킵 1/2'), "the snapshot's count").not.toBeNull()
    expect(socket.sentOfType('ready')).toEqual([])
  })
})

describe('a late join in a reveal window (2)', () => {
  it('shows the card at once, then plays the rest late with the time bar; no skip button', async () => {
    const env = setup()
    const { socket, ctx } = await lateJoinInto(env, (e) => windowSong(e, { index: 2 }))
    await settle()
    expectCard()
    expect(ctx.startedSources()).toEqual([])
    expect(screen.queryByRole('progressbar')).toBeNull()
    expect(bodyPlaying()).toBe(false)

    answerLatestPing(env, socket)
    await flushUntil(() => ctx.startedSources().length === 1)
    expect(ctx.startedSources(), 'the rest of the song plays').toHaveLength(1)
    const [when, offset] = ctx.startedSources()[0].start.mock.calls[0]
    expect(when).toBeCloseTo(CTX_TIME, 6)
    expect(offset).toBeCloseTo(ELAPSED / 1000, 3)
    await flushUntil(() => screen.queryByRole('progressbar') !== null)
    const before = barNow()
    expect(before, 'the time bar shows').not.toBeNull()
    await advance(env, 1000)
    expect(barNow(), 'the time bar counts down').toBeLessThan(before)
    expect(bodyPlaying()).toBe(true)
    expectCard()
    expect(skipButton(), 'a watcher has no skip button').toBeNull()
    expect(screen.queryByText('스킵 1/2')).toBeNull()
    expect(pageText(), 'no song-start line').not.toContain(songStartLine(2))
  })
})

describe('the window end (3)', () => {
  it('at playUntil the source stops and the time bar goes; the card stays', async () => {
    const env = setupReload()
    const { socket, ctx } = await reloadInto(env, windowSong(env))
    answerLatestPing(env, socket)
    await flushUntil(() => ctx.startedSources().length === 1)
    expect(ctx.startedSources()).toHaveLength(1)
    const source = ctx.startedSources()[0]
    await advance(env, 50_000 - 250)
    expect(bodyPlaying(), 'still in the window just before playUntil').toBe(true)
    expect(screen.queryByRole('progressbar')).not.toBeNull()
    expect(immediateStops(source, ctx)).toBe(0)
    await advance(env, 500)
    expect(bodyPlaying(), 'the window has ended').toBe(false)
    expect(immediateStops(source, ctx), 'the source has been stopped').toBeGreaterThanOrEqual(1)
    expect(screen.queryByRole('progressbar'), 'the time bar goes').toBeNull()
    expectCard()
    expect(skipButton()).toBeNull()
  })
})

describe('a window already over at the clock estimate (4, 6)', () => {
  it('shows the card; no source, no time bar, no playing, no song-start line', async () => {
    const env = setupReload()
    // playUntil 5 s before the estimated now.
    const { socket, ctx } = await reloadInto(env, windowSong(env, { playUntilIn: -5000 }))
    await settle()
    expectCard()
    answerLatestPing(env, socket)
    await settle()
    await advance(env, 1000)
    expectCard()
    expect(ctx.startedSources(), 'nothing plays').toEqual([])
    expect(screen.queryByRole('progressbar')).toBeNull()
    expect(bodyPlaying()).toBe(false)
    expect(skipButton()).toBeNull()
    expect(pageText()).not.toContain(songStartLine(0))
  })
})

describe('a newer skipState before the clock estimate (5)', () => {
  it('overrides the snapshot count', async () => {
    const env = setupReload()
    const { socket, ctx } = await reloadInto(env, windowSong(env, { votes: 1 }))
    deliver(socket, { type: 'skipState', index: 0, votes: 0, needed: 2 })
    expectCard()
    answerLatestPing(env, socket)
    await flushUntil(() => ctx.startedSources().length === 1)
    expect(ctx.startedSources()).toHaveLength(1)
    await advance(env, 300)
    expect(skipLabel()).toBe(SKIP)
    expect(screen.queryByText('스킵 0/2'), 'the newer count').not.toBeNull()
    expect(screen.queryByText('스킵 1/2'), "not the snapshot's").toBeNull()
  })
})

describe('no song-start line in the window (6)', () => {
  it('a rejoin into the window posts no song-start line before or after the clock estimate', async () => {
    const env = setupReload()
    const { socket, ctx } = await reloadInto(env, windowSong(env))
    await settle()
    expect(pageText()).not.toContain(songStartLine(0))
    answerLatestPing(env, socket)
    await flushUntil(() => ctx.startedSources().length === 1)
    expect(ctx.startedSources()).toHaveLength(1)
    await advance(env, 1000)
    expect(bodyPlaying(), 'the window plays').toBe(true)
    expect(pageText()).not.toContain(songStartLine(0))
    await advance(env, 50_000)
    expect(pageText()).not.toContain(songStartLine(0))
  })
})

describe('a choice room in the window (7)', () => {
  it('marks the correct tile as a live reveal does; a tile click sends no pick', async () => {
    const env = setupReload()
    const song = windowSong(env, {
      extra: { choices: choicesOf(COVERS4), picked: null },
      revealExtra: { correctChoice: 2 },
    })
    const { socket, ctx } = await reloadInto(env, song)
    await settle()
    expectCard()
    expect(marks(koTiles(4)), 'marked at once').toEqual(['dim', 'dim', 'correct', 'dim'])
    fireEvent.click(koTiles(4)[0])
    await settle()
    expect(socket.sentOfType('pick')).toEqual([])

    answerLatestPing(env, socket)
    await flushUntil(() => ctx.startedSources().length === 1)
    expect(ctx.startedSources()).toHaveLength(1)
    await advance(env, 300)
    expect(marks(koTiles(4)), 'still marked in the window').toEqual(['dim', 'dim', 'correct', 'dim'])
    expect(koTiles(4).map((t) => t.disabled)).toEqual([true, true, true, true])
    fireEvent.click(koTiles(4)[1])
    await advance(env, 50)
    expect(socket.sentOfType('pick')).toEqual([])
  })
})
