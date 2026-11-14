// Cycle 130 (F-036, D-129): the skip button in a reveal window. Spec
// docs/cycles/130-window-skip-button.md, acceptance criteria 1 to 5.
//
// Contract with the implementer:
// - After a reveal that opens a window (cycle 129: playUntil later than now,
//   for a song this client has started), a player (not a watcher) sees the
//   skip button and the count until the window ends: playUntil, a prepare
//   without holdMs, gameOver, or a held prepare (positive holdMs), which
//   hides them at once although the old music plays on.
// - The own vote is reset at the window reveal: the button reads 스킵 (not
//   voted) whatever was clicked during the song.
// - A click sends skipVote{index} of the revealed song and toggles the own
//   vote (스킵 / 스킵 취소) as during the song; skipState for that index sets
//   the count (스킵 votes/needed), one for another index is ignored.
// - Any other reveal (no playUntil, playUntil at or before now) shows no
//   skip button, as today.
//
// Timer rule: fake timers in every test (the tick, the hold and the window
// end are local timers), driven by flushUntil / advance with the injected
// now() moved together with them (as App.window.test.jsx).
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, act, cleanup } from '@testing-library/react'
import App from './App.jsx'
import { createSocketFactory, createAudioContextFactory, createFakeFetch } from './testing/fakes.js'
import { createRoomAs, joinRoomAs } from './testing/lobby.js'

const T0 = 1_000_000
const FAKE_TIMERS = { toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval'] }
const HOLD = 5000
const CTX_TIME = 10
// The receipt offset of every start here: serverTime is the clock + 500.
const OFFSET = 500
const REVEAL_HEADING = '정답 공개'
// Any skip button, 스킵 or 스킵 취소.
const ANY_SKIP = /^스킵/
const SKIP = '스킵'
const SKIP_CANCEL = '스킵 취소'
const SKIP_REFUSED = { type: 'error', code: 'rate-limited', for: 'skipVote' }

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

function setup() {
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
const roster = (phase = 'lobby', players = ALONE) => ({ type: 'roster', phase, players })

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

const skipState = (index, votes, needed = 2) => ({ type: 'skipState', index, votes, needed })

// The server's now: the local clock plus the receipt offset.
const serverNow = (env) => env.clock.t + OFFSET

function readiesFor(socket, index) {
  return socket.sentOfType('ready').filter((m) => m.index === index)
}

// stop() or stop(t) with t no later than the context's now.
function immediateStops(source, ctx) {
  return source.stop.mock.calls.filter((call) => call.length === 0 || call[0] <= ctx.currentTime).length
}

function bodyPlaying() {
  return document.body.classList.contains('playing')
}

function revealShown() {
  return screen.queryByText(REVEAL_HEADING) !== null
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

// Clicks the skip button; fails by assertion when there is no button.
function clickSkip() {
  const button = skipButton()
  expect(button, 'a skip button to click').not.toBeNull()
  fireEvent.click(button)
}

// Host 하루 of QWERT with token T1, alone, in a game: song 0 started at the
// current clock (length ms long), playing, `elapsed` ms into it.
async function playingSong(env, { length = 70_000, elapsed = 10_000 } = {}) {
  createRoomAs('하루')
  await flushUntil(() => env.sockets.sockets.length === 1)
  const socket = env.sockets.last
  act(() => socket.open())
  await flushUntil(() => socket.sent.length > 0)
  deliver(socket, { type: 'joined', code: 'QWERT', name: '하루' })
  deliver(socket, roster('lobby'))
  deliver(socket, { type: 'token', token: 'T1' })
  deliver(socket, roster('playing'))
  deliver(socket, prepareMsg(0))
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
  expect(skipLabel(), 'the skip button shows during the song').toBe(SKIP)
  return { socket, ctx, source, start }
}

// A playing song (70 s, 10 s in) revealed with playUntil = its deadlineAt,
// 60 s ahead: a window. Checks that it is one (the card shows, body plays).
async function windowReveal(env, options = {}) {
  const song = await playingSong(env, options)
  deliver(song.socket, revealMsg(0, { playUntil: song.start.deadlineAt }))
  expect(revealShown(), 'the reveal card shows').toBe(true)
  expect(bodyPlaying(), 'the window keeps the music on').toBe(true)
  return song
}

describe('the skip button in a reveal window (1)', () => {
  it('shows after a window reveal; a click sends skipVote for the revealed index and shows voted', async () => {
    const env = setup()
    const { socket } = await windowReveal(env)
    expect(skipButton(), 'the skip button shows in the window').not.toBeNull()
    expect(skipLabel()).toBe(SKIP)
    expect(socket.sentOfType('skipVote')).toEqual([])
    clickSkip()
    expect(socket.sentOfType('skipVote')).toEqual([{ type: 'skipVote', index: 0 }])
    expect(skipLabel(), 'the button shows as voted').toBe(SKIP_CANCEL)
  })

  it('a second click in the window sends another skipVote and shows not voted', async () => {
    const env = setup()
    const { socket } = await windowReveal(env)
    clickSkip()
    await advance(env, 1000)
    clickSkip()
    expect(socket.sentOfType('skipVote')).toEqual([
      { type: 'skipVote', index: 0 },
      { type: 'skipVote', index: 0 },
    ])
    expect(skipLabel()).toBe(SKIP)
  })

  it('a rate-limited refusal of a window click undoes the voted look', async () => {
    const env = setup()
    const { socket } = await windowReveal(env)
    clickSkip()
    expect(skipLabel()).toBe(SKIP_CANCEL)
    deliver(socket, SKIP_REFUSED)
    expect(skipLabel()).toBe(SKIP)
  })
})

describe('the skip count in a reveal window (2)', () => {
  it('skipState for the revealed index shows its count; one for another index does not change it', async () => {
    const env = setup()
    const { socket } = await windowReveal(env)
    deliver(socket, skipState(0, 0))
    expect(screen.queryByText('스킵 0/2'), 'the count after the reveal').not.toBeNull()
    deliver(socket, skipState(0, 1))
    expect(screen.queryByText('스킵 1/2'), 'the count shows 1/2').not.toBeNull()
    deliver(socket, skipState(1, 2))
    expect(screen.queryByText('스킵 1/2'), 'another index leaves the count').not.toBeNull()
    expect(screen.queryByText('스킵 2/2')).toBeNull()
    // The own vote is not touched by the count.
    expect(skipLabel()).toBe(SKIP)
  })
})

describe('the own vote is reset at the window reveal (3)', () => {
  it('a vote made during the song shows as not voted after the window reveal; one click sends one skipVote and shows voted', async () => {
    const env = setup()
    const { socket, start } = await playingSong(env)
    clickSkip()
    expect(socket.sentOfType('skipVote')).toEqual([{ type: 'skipVote', index: 0 }])
    expect(skipLabel(), 'voted during the song').toBe(SKIP_CANCEL)
    deliver(socket, skipState(0, 1))
    deliver(socket, revealMsg(0, { playUntil: start.deadlineAt }))
    expect(bodyPlaying(), 'a window').toBe(true)
    deliver(socket, skipState(0, 0))
    expect(skipLabel(), 'not voted right after the window reveal').toBe(SKIP)
    expect(screen.queryByText('스킵 0/2')).not.toBeNull()
    clickSkip()
    expect(socket.sentOfType('skipVote'), 'exactly one more skipVote').toEqual([
      { type: 'skipVote', index: 0 },
      { type: 'skipVote', index: 0 },
    ])
    expect(skipLabel(), 'voted after the click').toBe(SKIP_CANCEL)
  })

  it('a late refusal of a vote made before the reveal leaves the reset vote alone', async () => {
    const env = setup()
    const { socket, start } = await playingSong(env)
    clickSkip()
    expect(skipLabel()).toBe(SKIP_CANCEL)
    deliver(socket, revealMsg(0, { playUntil: start.deadlineAt }))
    expect(skipLabel()).toBe(SKIP)
    // The refusal of the click before the reveal arrives after it.
    deliver(socket, SKIP_REFUSED)
    expect(skipLabel()).toBe(SKIP)
    clickSkip()
    expect(socket.sentOfType('skipVote')).toHaveLength(2)
    expect(skipLabel()).toBe(SKIP_CANCEL)
  })
})

describe('no skip button (4)', () => {
  it('after playUntil (the window ended)', async () => {
    const env = setup()
    const { socket } = await windowReveal(env)
    expect(skipButton(), 'shown in the window').not.toBeNull()
    await advance(env, 60_000 - 250)
    expect(skipButton(), 'still shown just before playUntil').not.toBeNull()
    await advance(env, 500)
    expect(bodyPlaying(), 'the window has ended').toBe(false)
    expect(revealShown(), 'the card stays').toBe(true)
    expect(skipButton(), 'no skip button after playUntil').toBeNull()
    expect(screen.queryByText(/^스킵 \d+\/\d+$/), 'no count after playUntil').toBeNull()
    expect(socket.sentOfType('skipVote')).toEqual([])
  })

  it('after a held prepare arrives in the window, while the old music still plays', async () => {
    const env = setup()
    const { socket, source, ctx } = await windowReveal(env)
    deliver(socket, skipState(0, 1))
    expect(skipButton(), 'shown in the window').not.toBeNull()
    deliver(socket, prepareMsg(1, { holdMs: HOLD }))
    expect(skipButton(), 'hidden at once by the held prepare').toBeNull()
    expect(screen.queryByText('스킵 1/2'), 'the count goes too').toBeNull()
    await flushUntil(() => readiesFor(socket, 1).length === 1)
    await advance(env, 1000)
    expect(immediateStops(source, ctx), 'the old music still plays').toBe(0)
    expect(bodyPlaying()).toBe(true)
    expect(revealShown(), 'the hold keeps the card').toBe(true)
    expect(skipButton(), 'still no skip button during the hold').toBeNull()
    expect(socket.sentOfType('skipVote')).toEqual([])
  })

  it.each([
    ['without playUntil', () => ({})],
    ['with playUntil at now', (env) => ({ playUntil: serverNow(env) })],
    ['with playUntil before now', (env) => ({ playUntil: serverNow(env) - 5000 })],
  ])('after a reveal %s (no window)', async (_label, extra) => {
    const env = setup()
    const { socket } = await playingSong(env)
    deliver(socket, revealMsg(0, extra(env)))
    expect(revealShown()).toBe(true)
    expect(bodyPlaying(), 'no window').toBe(false)
    deliver(socket, skipState(0, 1))
    expect(skipButton()).toBeNull()
    expect(screen.queryByText('스킵 1/2')).toBeNull()
    await advance(env, 1000)
    expect(skipButton()).toBeNull()
    expect(socket.sentOfType('skipVote')).toEqual([])
  })
})

describe('a watcher in a reveal window (4)', () => {
  // Mina hosts; the player joins mid-song as 하나.
  const PLAYERS = [player('Mina', { host: true }), player('하나')]
  const RTT = 20

  it('sees the window but no skip button', async () => {
    const env = setup()
    joinRoomAs('하나', 'ABCDE')
    await flushUntil(() => env.sockets.sockets.length === 1)
    const socket = env.sockets.last
    act(() => socket.open())
    await flushUntil(() => socket.sent.length > 0)
    expect(socket.sent[0]).toEqual({ type: 'join', code: 'ABCDE', nickname: '하나' })
    deliver(socket, { type: 'joined', code: 'ABCDE', name: '하나' })
    // A song 10 s in by the synced clock (offset OFFSET once the pong is in).
    const startAt = T0 + RTT + OFFSET - 10_000
    const deadlineAt = startAt + 70_000
    deliver(socket, {
      type: 'snapshot',
      phase: 'playing',
      players: PLAYERS,
      song: { index: 2, total: 5, audioUrl: '/audio/watch', startAt, deadlineAt },
      scores: [{ name: 'Mina', score: 1 }],
    })
    deliver(socket, roster('playing', PLAYERS))
    const ctx = env.audio.last
    await flushUntil(() => ctx.decoded.length === 1)
    const ping = socket.sentOfType('ping')[0]
    expect(ping, 'the joined burst sent a ping').toBeTruthy()
    env.clock.t = T0 + RTT
    deliver(socket, { type: 'pong', id: ping.id, serverTime: T0 + RTT / 2 + OFFSET })
    await flushUntil(() => ctx.startedSources().length === 1)
    expect(ctx.startedSources(), 'the watched song plays late').toHaveLength(1)
    const source = ctx.startedSources()[0]
    expect(bodyPlaying(), 'the watched song is audible').toBe(true)
    expect(skipButton(), 'a watcher has no skip button during the song').toBeNull()
    deliver(socket, revealMsg(2, { playUntil: deadlineAt }))
    expect(revealShown()).toBe(true)
    expect(immediateStops(source, ctx), 'a window: the source plays on').toBe(0)
    expect(bodyPlaying(), 'a window: body keeps playing').toBe(true)
    deliver(socket, skipState(2, 1))
    expect(skipButton(), 'a watcher has no skip button in the window').toBeNull()
    await advance(env, 1000)
    expect(skipButton()).toBeNull()
    expect(socket.sentOfType('skipVote')).toEqual([])
  })
})

describe('leaving the window (5)', () => {
  it('a prepare without hold in the window removes the button', async () => {
    const env = setup()
    const { socket } = await windowReveal(env)
    deliver(socket, skipState(0, 1))
    expect(skipButton(), 'shown in the window').not.toBeNull()
    await advance(env, 1000)
    deliver(socket, prepareMsg(1))
    expect(revealShown()).toBe(false)
    expect(skipButton(), 'the prepare removes the button').toBeNull()
    expect(screen.queryByText('스킵 1/2')).toBeNull()
    await flushUntil(() => readiesFor(socket, 1).length === 1)
    expect(skipButton(), 'no button before the next start').toBeNull()
    expect(socket.sentOfType('skipVote')).toEqual([])
  })
})
