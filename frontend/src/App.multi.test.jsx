// Cycle 048 (F-005 part 1): the multiplayer game screen. Everyone's scores
// (점수판), the skip button and count, the final ranking (최종 순위) with
// shared ranks, the watching notice for a mid-game joiner, the return to the
// lobby when a game is abandoned, and the session side of the clock-jump
// guard (the drift re-sync clears stale samples; outputLatency only when
// finite).
// Cycle 114 (D-124, declared): the lobby is read through src/testing/lobby.js:
// the room code from the ticket's .tk-code, the roster rows from the seats'
// name lines (the lobby seats are li.seat now), and the start button
// .start-row .go. The abandon notice stays a <p>.
// Timer rule (cycle 039): real timers and waitFor, except the drift-clear test,
// which needs the pings' 150 ms spacing and uses fake timers.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, act, cleanup, within } from '@testing-library/react'
import App from './App.jsx'
import { createSocketFactory, createAudioContextFactory, createFakeFetch } from './testing/fakes.js'
import { createRoomAs, joinRoomAs, lobbyCode, seatNames, startButton } from './testing/lobby.js'

const T0 = 1_000_000
const FAKE_TIMERS = { toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval'] }
const WAITING = '방장이 게임을 시작하기를 기다리는 중...'
const WATCHING = '이번 곡은 구경 중입니다. 다음 곡부터 참가합니다.'
const ABANDONED = '게임이 중단되어 대기실로 돌아왔습니다.'
// The synced offset the fake server reports through pong (server ahead 2.5 s).
const SYNC = 2500
const RTT = 20
// Any skip count line, e.g. 스킵 1/2.
const SKIP_COUNT = /^스킵 \d+\/\d+$/
// Any skip button, 스킵 or 스킵 취소.
const ANY_SKIP = /^스킵/

beforeEach(() => {
  window.history.pushState({}, '', '/')
})

afterEach(() => {
  cleanup()
  vi.useRealTimers()
  window.history.pushState({}, '', '/')
  vi.restoreAllMocks()
})

// Renders App with fakes. clock.t is what now() returns.
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

function pageText() {
  return document.body.textContent
}

// The rows of the list labelled `name` (점수판, 최종 순위); [] when absent.
function listLines(name) {
  const list = screen.queryByRole('list', { name })
  if (list === null) return []
  return within(list)
    .queryAllByRole('listitem')
    .map((li) => li.textContent)
}

function skipButton() {
  return screen.queryByRole('button', { name: ANY_SKIP })
}

const player = (name, { host = false, score = 0, connected = true } = {}) => ({ name, score, host, connected })
const roster = (players, phase = 'lobby') => ({ type: 'roster', phase, players })
// Mina hosts; the player is Hana (2), second in server order.
const PLAYERS_3 = [player('Mina', { host: true }), player('Hana (2)'), player('Joon')]
const prepare = (index = 0) => ({ type: 'prepare', index, total: 5, audioUrl: '/audio/abc' })

// serverTime is T0 + 500, so the receipt offset is 500 while now() is T0.
function startMsg({ index = 0, startDelta = 3000, length = 90_000 } = {}) {
  const serverTime = T0 + 500
  const startAt = serverTime + startDelta
  return { type: 'start', index, startAt, deadlineAt: startAt + length, serverTime }
}

const revealMsg = (overrides = {}) => ({
  type: 'reveal',
  index: 0,
  reveal: '진격의 거인',
  titles: { ko: ['진격의 거인'], en: ['Attack on Titan'], ja: ['進撃の巨人'], romaji: ['Shingeki no Kyojin'] },
  song: '紅蓮の弓矢',
  artists: ['Revo'],
  themeType: 'OP',
  themeSlug: 'OP1',
  winner: null,
  ...overrides,
})

const SCORES_3 = {
  type: 'scores',
  scores: [
    { name: 'Mina', score: 3 },
    { name: 'Hana (2)', score: 1 },
    { name: 'Joon', score: 0 },
  ],
}
const SCORE_LINES = ['Mina 3점', 'Hana (2) 1점 (나)', 'Joon 0점']

// ---- real-timer helpers ----

async function openSocket(env, count = 1) {
  await waitFor(() => expect(env.sockets.sockets.length).toBe(count))
  const socket = env.sockets.last
  act(() => socket.open())
  await waitFor(() => expect(socket.sent.length).toBeGreaterThan(0))
  return socket
}

// Clicks 참가 as Hana for ABCDE; returns the opened socket (nothing received yet).
async function clickJoin(env) {
  joinRoomAs('Hana', 'ABCDE')
  const socket = await openSocket(env)
  expect(socket.sent[0]).toEqual({ type: 'join', code: 'ABCDE', nickname: 'Hana' })
  return socket
}

// Joins the lobby as Hana (2) and receives the lobby roster.
async function joinLobby(env) {
  const socket = await clickJoin(env)
  deliver(socket, { type: 'joined', code: 'ABCDE', name: 'Hana (2)' })
  deliver(socket, roster(PLAYERS_3))
  return socket
}

async function prepareAndReady(socket, index = 0, readyCount = 1) {
  deliver(socket, prepare(index))
  await waitFor(() =>
    expect(socket.sentOfType('ready').filter((m) => m.index === index)).toHaveLength(readyCount),
  )
}

// A room game in song 0, started: joined, roster{playing}, prepare, ready, start.
async function roomSongStarted(env) {
  const socket = await joinLobby(env)
  deliver(socket, roster(PLAYERS_3, 'playing'))
  await prepareAndReady(socket, 0)
  deliver(socket, startMsg())
  await waitFor(() => expect(env.audio.last.startedSources()).toHaveLength(1))
  return socket
}

// A real-time pause, so that anything already queued would have run.
async function settle(ms = 50) {
  await act(async () => {
    await new Promise((resolve) => setTimeout(resolve, ms))
  })
}

// ---- watcher helpers ----

// A song in PLAYING by the snapshot: 10 s in by the synced clock once the
// clock reads T0 + RTT (see answerFirstPing).
const LATE_START_AT = T0 + RTT + SYNC - 10_000
const PLAYING_SONG = {
  index: 2,
  total: 5,
  audioUrl: '/audio/watch',
  startAt: LATE_START_AT,
  deadlineAt: LATE_START_AT + 90_000,
}
const PREPARE_SONG = { index: 2, total: 5, audioUrl: '/audio/watch' }

const snapshot = (song, phase = 'playing', players = PLAYERS_3) => ({
  type: 'snapshot',
  phase,
  players,
  song,
  scores: phase === 'playing' ? [{ name: 'Mina', score: 1 }, { name: 'Joon', score: 0 }] : [],
})

// Joins mid-game as Hana (2): joined, snapshot{playing, song}, roster{playing}.
async function joinAsWatcher(env, song, players = PLAYERS_3) {
  const socket = await clickJoin(env)
  deliver(socket, { type: 'joined', code: 'ABCDE', name: 'Hana (2)' })
  deliver(socket, snapshot(song, 'playing', players))
  deliver(socket, roster(players, 'playing'))
  return socket
}

// Answers the first ping of the joined burst (sent at T0) after RTT ms with
// the SYNC offset; the clock is left at T0 + RTT.
function answerFirstPing(env, socket) {
  const ping = pingsOf(socket)[0]
  expect(ping, 'the joined burst should have sent a ping').toBeTruthy()
  env.clock.t = T0 + RTT
  deliver(socket, { type: 'pong', id: ping.id, serverTime: T0 + RTT / 2 + SYNC })
}

// A watcher of PLAYING_SONG whose late start has been scheduled at ctx time 7.
async function watcherPlaying(env) {
  const socket = await joinAsWatcher(env, PLAYING_SONG)
  const ctx = env.audio.last
  await waitFor(() => expect(ctx.decoded).toHaveLength(1))
  ctx.currentTime = 7
  answerFirstPing(env, socket)
  await waitFor(() => expect(ctx.startedSources()).toHaveLength(1))
  return socket
}

describe('scores (점수판)', () => {
  it('a scores message with 3 rows shows the 점수판 lines in server order, (나) on Hana (2)', async () => {
    const env = setup()
    const socket = await roomSongStarted(env)
    expect(listLines('점수판')).toEqual([])
    deliver(socket, revealMsg({ winner: 'Mina' }))
    deliver(socket, SCORES_3)
    expect(listLines('점수판')).toEqual(SCORE_LINES)
    // The cycle 041 own-score line stays.
    expect(screen.getByText('점수: 1')).toBeTruthy()
  })

  it('the next scores message replaces the lines; prepare of the next song keeps them', async () => {
    const env = setup()
    const socket = await roomSongStarted(env)
    deliver(socket, revealMsg())
    deliver(socket, SCORES_3)
    await prepareAndReady(socket, 1)
    expect(listLines('점수판')).toEqual(SCORE_LINES)
    deliver(socket, {
      type: 'scores',
      scores: [
        { name: 'Hana (2)', score: 2 },
        { name: 'Mina', score: 3 },
        { name: 'Joon', score: 0 },
      ],
    })
    expect(listLines('점수판')).toEqual(['Hana (2) 2점 (나)', 'Mina 3점', 'Joon 0점'])
  })
})

describe('skip', () => {
  it('no 스킵 before start; after start 스킵 sends skipVote{index:0} and reads 스킵 취소; a second click sends another', async () => {
    const env = setup()
    const socket = await joinLobby(env)
    deliver(socket, roster(PLAYERS_3, 'playing'))
    await prepareAndReady(socket, 0)
    expect(skipButton()).toBeNull()
    deliver(socket, startMsg())
    await waitFor(() => expect(screen.getByRole('button', { name: '스킵' })).toBeTruthy())
    expect(socket.sentOfType('skipVote')).toEqual([])

    fireEvent.click(screen.getByRole('button', { name: '스킵' }))
    expect(socket.sentOfType('skipVote')).toEqual([{ type: 'skipVote', index: 0 }])
    expect(screen.getByRole('button', { name: '스킵 취소' })).toBeTruthy()
    expect(screen.queryByRole('button', { name: '스킵' })).toBeNull()

    fireEvent.click(screen.getByRole('button', { name: '스킵 취소' }))
    expect(socket.sentOfType('skipVote')).toEqual([
      { type: 'skipVote', index: 0 },
      { type: 'skipVote', index: 0 },
    ])
    expect(screen.getByRole('button', { name: '스킵' })).toBeTruthy()
  })

  it('skipState for the current index shows 스킵 1/2; one for another index is ignored', async () => {
    const env = setup()
    const socket = await roomSongStarted(env)
    expect(screen.queryByText(SKIP_COUNT)).toBeNull()
    deliver(socket, { type: 'skipState', index: 0, votes: 1, needed: 2 })
    expect(screen.getByText('스킵 1/2')).toBeTruthy()
    deliver(socket, { type: 'skipState', index: 1, votes: 2, needed: 3 })
    expect(screen.queryByText('스킵 2/3')).toBeNull()
    expect(screen.getByText('스킵 1/2')).toBeTruthy()
  })

  it('a skipState for another index before any count shows nothing', async () => {
    const env = setup()
    const socket = await roomSongStarted(env)
    deliver(socket, { type: 'skipState', index: 3, votes: 1, needed: 2 })
    expect(screen.queryByText(SKIP_COUNT)).toBeNull()
  })

  it('a rate-limited error after a click undoes the 스킵 취소 label', async () => {
    const env = setup()
    const socket = await roomSongStarted(env)
    fireEvent.click(screen.getByRole('button', { name: '스킵' }))
    expect(screen.getByRole('button', { name: '스킵 취소' })).toBeTruthy()
    deliver(socket, { type: 'error', code: 'rate-limited', for: 'skipVote' })
    expect(screen.getByRole('button', { name: '스킵' })).toBeTruthy()
    expect(screen.queryByRole('button', { name: '스킵 취소' })).toBeNull()
  })

  it('a rate-limited error after the next skipState does not undo the counted vote', async () => {
    const env = setup()
    const socket = await roomSongStarted(env)
    fireEvent.click(screen.getByRole('button', { name: '스킵' }))
    deliver(socket, { type: 'skipState', index: 0, votes: 1, needed: 2 })
    // A chat flood, not the vote, was limited this time.
    deliver(socket, { type: 'error', code: 'rate-limited' })
    expect(screen.getByRole('button', { name: '스킵 취소' })).toBeTruthy()
  })

  it('reveal hides the button and the count; after the next prepare and start the label is 스킵 with no count', async () => {
    const env = setup()
    const socket = await roomSongStarted(env)
    fireEvent.click(screen.getByRole('button', { name: '스킵' }))
    deliver(socket, { type: 'skipState', index: 0, votes: 1, needed: 2 })
    expect(screen.getByRole('button', { name: '스킵 취소' })).toBeTruthy()
    expect(screen.getByText('스킵 1/2')).toBeTruthy()

    deliver(socket, revealMsg())
    expect(skipButton()).toBeNull()
    expect(screen.queryByText(SKIP_COUNT)).toBeNull()

    await prepareAndReady(socket, 1)
    expect(skipButton()).toBeNull()
    expect(screen.queryByText(SKIP_COUNT)).toBeNull()
    deliver(socket, startMsg({ index: 1 }))
    await waitFor(() => expect(screen.getByRole('button', { name: '스킵' })).toBeTruthy())
    expect(screen.queryByRole('button', { name: '스킵 취소' })).toBeNull()
    expect(screen.queryByText(SKIP_COUNT)).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: '스킵' }))
    expect(socket.sentOfType('skipVote')).toEqual([
      { type: 'skipVote', index: 0 },
      { type: 'skipVote', index: 1 },
    ])
  })
})

describe('final ranking (최종 순위)', () => {
  it('shared ranks are shown as sent, in server order, with the own row marked; 최종 점수 stays', async () => {
    const env = setup()
    const socket = await roomSongStarted(env)
    deliver(socket, revealMsg())
    deliver(socket, {
      type: 'gameOver',
      ranking: [
        { rank: 1, name: 'Mina', score: 2 },
        { rank: 1, name: 'Hana (2)', score: 2 },
        { rank: 3, name: 'Joon', score: 0 },
      ],
    })
    expect(listLines('최종 순위')).toEqual(['1위 Mina 2점', '1위 Hana (2) 2점 (나)', '3위 Joon 0점'])
    expect(screen.getByText('최종 점수: 2점')).toBeTruthy()
  })

  it('a ranking without the own row shows no 최종 점수 line', async () => {
    const env = setup()
    const socket = await roomSongStarted(env)
    deliver(socket, revealMsg())
    deliver(socket, {
      type: 'gameOver',
      ranking: [
        { rank: 1, name: 'Mina', score: 2 },
        { rank: 2, name: 'Joon', score: 1 },
      ],
    })
    expect(listLines('최종 순위')).toEqual(['1위 Mina 2점', '2위 Joon 1점'])
    expect(pageText()).not.toContain('최종 점수')
  })

  it("a second game's prepare{index:0} clears 점수판 and 최종 순위", async () => {
    const env = setup()
    const socket = await roomSongStarted(env)
    deliver(socket, revealMsg())
    deliver(socket, SCORES_3)
    deliver(socket, {
      type: 'gameOver',
      ranking: [
        { rank: 1, name: 'Mina', score: 3 },
        { rank: 2, name: 'Hana (2)', score: 1 },
        { rank: 3, name: 'Joon', score: 0 },
      ],
    })
    deliver(socket, roster(PLAYERS_3, 'lobby'))
    expect(listLines('점수판')).toEqual(SCORE_LINES)
    expect(listLines('최종 순위')).toHaveLength(3)
    // The normal end of a game is not an abandoned one.
    expect(pageText()).not.toContain(ABANDONED)
    fireEvent.click(screen.getByRole('button', { name: '대기실로' }))
    expect(pageText()).not.toContain(ABANDONED)

    deliver(socket, roster(PLAYERS_3, 'playing'))
    await prepareAndReady(socket, 0, 2)
    expect(listLines('점수판')).toEqual([])
    expect(listLines('최종 순위')).toEqual([])
    expect(pageText()).not.toContain('최종 점수')
  })
})

describe('watching (a mid-game joiner)', () => {
  it('a lobby snapshot after joined keeps the lobby screen and shows no notice', async () => {
    const env = setup()
    const socket = await clickJoin(env)
    deliver(socket, { type: 'joined', code: 'ABCDE', name: 'Hana (2)' })
    deliver(socket, snapshot(null, 'lobby'))
    deliver(socket, roster(PLAYERS_3))
    expect(lobbyCode()).toBe('ABCDE')
    expect(screen.getByText(WAITING)).toBeTruthy()
    expect(pageText()).not.toContain(WATCHING)
  })

  it('a playing snapshot with startAt shows the notice and fetches the audioUrl; no start before the first pong; then a late start by the synced offset', async () => {
    const env = setup()
    const socket = await joinAsWatcher(env, PLAYING_SONG)
    expect(screen.getByText(WATCHING)).toBeTruthy()
    // The game screen, not the lobby.
    expect(screen.queryByText(WAITING)).toBeNull()
    expect(lobbyCode()).toBeNull()
    const ctx = env.audio.last
    await waitFor(() => expect(env.fetch.urls).toEqual(['/audio/watch']))
    await waitFor(() => expect(ctx.decoded).toHaveLength(1))
    await settle()
    expect(ctx.startedSources()).toEqual([])

    ctx.currentTime = 7
    answerFirstPing(env, socket)
    await waitFor(() => expect(ctx.startedSources()).toHaveLength(1))
    const source = ctx.startedSources()[0]
    expect(source.buffer).toBe(ctx.decoded[0])
    // 10 s in by the synced clock: at once, from 10 s, stopping 80 s later.
    const [when, offset] = source.start.mock.calls[0]
    expect(when).toBeCloseTo(7, 6)
    expect(offset).toBeCloseTo(10, 6)
    expect(source.stop.mock.calls[0][0]).toBeCloseTo(87, 6)
    expect(socket.sentOfType('ready')).toEqual([])
    expect(skipButton()).toBeNull()
    expect(screen.getByText(WATCHING)).toBeTruthy()
  })

  it('a playing snapshot with song:null shows the notice and plays nothing', async () => {
    const env = setup()
    const socket = await joinAsWatcher(env, null)
    expect(screen.getByText(WATCHING)).toBeTruthy()
    answerFirstPing(env, socket)
    await settle()
    expect(env.fetch.urls).toEqual([])
    expect(env.audio.last.sources).toEqual([])
    expect(socket.sentOfType('ready')).toEqual([])
    expect(skipButton()).toBeNull()
  })

  it('a snapshot song with only audioUrl loads, sends no ready and waits for the start of that index', async () => {
    const env = setup()
    const socket = await joinAsWatcher(env, PREPARE_SONG)
    expect(screen.getByText(WATCHING)).toBeTruthy()
    const ctx = env.audio.last
    await waitFor(() => expect(env.fetch.urls).toEqual(['/audio/watch']))
    await waitFor(() => expect(ctx.decoded).toHaveLength(1))
    await settle()
    expect(ctx.startedSources()).toEqual([])
    expect(socket.sentOfType('ready')).toEqual([])

    // No pong is answered: the start's receipt offset (500) applies.
    ctx.currentTime = 10
    deliver(socket, startMsg({ index: 2, startDelta: 3000 }))
    await waitFor(() => expect(ctx.startedSources()).toHaveLength(1))
    const [when, offset] = ctx.startedSources()[0].start.mock.calls[0]
    expect(when).toBeCloseTo(13, 6)
    expect(offset).toBe(0)
    expect(socket.sentOfType('ready')).toEqual([])
    expect(skipButton()).toBeNull()
    expect(screen.getByText(WATCHING)).toBeTruthy()
  })

  it('at the next prepare the notice is gone and ready is sent after the load', async () => {
    const env = setup()
    const socket = await watcherPlaying(env)
    expect(socket.sentOfType('ready')).toEqual([])
    deliver(socket, revealMsg({ index: 2 }))
    expect(screen.getByText(WATCHING)).toBeTruthy()
    deliver(socket, { type: 'prepare', index: 3, total: 5, audioUrl: '/audio/next' })
    expect(pageText()).not.toContain(WATCHING)
    await waitFor(() => expect(socket.sentOfType('ready')).toEqual([{ type: 'ready', index: 3 }]))
    expect(env.fetch.urls).toEqual(['/audio/watch', '/audio/next'])
    // A normal song again: the skip button appears at its start.
    deliver(socket, startMsg({ index: 3 }))
    await waitFor(() => expect(screen.getByRole('button', { name: '스킵' })).toBeTruthy())
  })

  it('from song:null, the next prepare also starts a normal song', async () => {
    const env = setup()
    const socket = await joinAsWatcher(env, null)
    deliver(socket, { type: 'prepare', index: 3, total: 5, audioUrl: '/audio/next' })
    expect(pageText()).not.toContain(WATCHING)
    await waitFor(() => expect(socket.sentOfType('ready')).toEqual([{ type: 'ready', index: 3 }]))
  })

  it('a watcher whose game ends shows the ranking without 최종 점수; the following lobby roster is not an abandon', async () => {
    const env = setup()
    const socket = await joinAsWatcher(env, null)
    deliver(socket, {
      type: 'gameOver',
      ranking: [
        { rank: 1, name: 'Mina', score: 2 },
        { rank: 2, name: 'Joon', score: 1 },
      ],
    })
    expect(listLines('최종 순위')).toEqual(['1위 Mina 2점', '2위 Joon 1점'])
    expect(pageText()).not.toContain('최종 점수')
    deliver(socket, roster(PLAYERS_3, 'lobby'))
    expect(pageText()).not.toContain(ABANDONED)
    expect(screen.getByRole('button', { name: '대기실로' })).toBeTruthy()
  })
})

describe('abandoned game', () => {
  it('(a) a watcher playing a song who receives roster{lobby} stops the source and shows the lobby with the abandon text', async () => {
    const env = setup()
    const socket = await watcherPlaying(env)
    const source = env.audio.last.startedSources()[0]
    // The scheduled stopAt is the only stop so far.
    expect(source.stop).toHaveBeenCalledTimes(1)
    deliver(socket, roster(PLAYERS_3, 'lobby'))
    expect(source.stop.mock.calls.length).toBeGreaterThan(1)
    expect(lobbyCode()).toBe('ABCDE')
    expect(screen.getByText(WAITING)).toBeTruthy()
    const text = screen.getByText(ABANDONED)
    expect(text.tagName).toBe('P')
    // The roster rows are the seats' name lines.
    expect(seatNames()).toEqual(['Mina (방장)', 'Hana (2) (나)', 'Joon'])
    expect(pageText()).not.toContain(WATCHING)
    expect(socket.closed).toBe(false)
  })

  it('(b) a watcher with a song:null snapshot who receives roster{lobby} shows the lobby, and as host the start button', async () => {
    const env = setup()
    const socket = await joinAsWatcher(env, null)
    expect(lobbyCode()).toBeNull()
    deliver(socket, roster([player('Hana (2)', { host: true }), player('Joon')], 'lobby'))
    expect(lobbyCode()).toBe('ABCDE')
    expect(screen.getByText(ABANDONED).tagName).toBe('P')
    expect(seatNames()).toEqual(['Hana (2) (방장) (나)', 'Joon'])
    expect(startButton()).not.toBeNull()
    expect(pageText()).not.toContain(WATCHING)
  })

  it('a roster{playing} during the game does not leave the game screen', async () => {
    const env = setup()
    const socket = await watcherPlaying(env)
    deliver(socket, roster([...PLAYERS_3, player('Sora')], 'playing'))
    expect(screen.getByText(WATCHING)).toBeTruthy()
    expect(pageText()).not.toContain(ABANDONED)
    expect(lobbyCode()).toBeNull()
  })
})

describe('outputLatency only when finite', () => {
  it.each([NaN, Infinity, -Infinity])('ctx.outputLatency %s is treated as 0', async (latency) => {
    const env = setup()
    const socket = await joinLobby(env)
    const ctx = env.audio.last
    ctx.outputLatency = latency
    deliver(socket, roster(PLAYERS_3, 'playing'))
    await prepareAndReady(socket, 0)
    ctx.currentTime = 10
    deliver(socket, startMsg({ startDelta: 3000 }))
    await waitFor(() => expect(ctx.startedSources()).toHaveLength(1))
    const source = ctx.startedSources()[0]
    const [when, offset] = source.start.mock.calls[0]
    expect(when).toBeCloseTo(13, 6)
    expect(offset).toBe(0)
    expect(source.stop.mock.calls[0][0]).toBeCloseTo(103, 6)
  })
})

// ---- fake-timer helpers (as in App.sync.test.jsx) ----

async function flushUntil(cond, max = 20) {
  for (let i = 0; i < max && !cond(); i++) {
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0)
    })
  }
}

async function advance(env, ms) {
  env.clock.t += ms
  await act(async () => {
    await vi.advanceTimersByTimeAsync(ms)
  })
}

// Answers `count` pings of a burst whose first ping has just gone out.
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

const HOST_ROSTER = [player('하루', { host: true }), player('Joon')]

// A room created as 하루, synced to SYNC by 8 + 3 answered pings, song 0 ready.
async function syncedRoomReady(env) {
  createRoomAs('하루')
  await flushUntil(() => env.sockets.sockets.length === 1)
  const socket = env.sockets.last
  act(() => socket.open())
  await flushUntil(() => socket.sent.length > 0)
  deliver(socket, { type: 'joined', code: 'QWERT', name: '하루' })
  deliver(socket, roster(HOST_ROSTER))
  await answerPings(env, socket, 8)
  deliver(socket, roster(HOST_ROSTER, 'playing'))
  deliver(socket, prepare(0))
  await flushUntil(() => socket.sentOfType('ready').length > 0)
  expect(socket.sentOfType('ready')).toEqual([{ type: 'ready', index: 0 }])
  await answerPings(env, socket, 3)
  await advance(env, 500)
  expect(pingsOf(socket)).toHaveLength(11)
  return socket
}

// A start at the current clock: receipt offset receiptMs; startAt is
// startInMs away by the offset offsetMs.
function startAtClock(env, { receiptMs, offsetMs, startInMs = 3000, index = 0 }) {
  const c = env.clock.t
  const start = c + offsetMs + startInMs
  return { type: 'start', index, startAt: start, deadlineAt: start + 90_000, serverTime: c + receiptMs }
}

describe('drift re-sync clears the stored samples', () => {
  it('after a drift, the 3 new pongs alone set the offset for the next song (stale samples do not outvote them)', async () => {
    vi.useFakeTimers(FAKE_TIMERS)
    const env = setup()
    const socket = await syncedRoomReady(env)
    const ctx = env.audio.last
    const NEW = SYNC + 2000
    ctx.currentTime = 10
    // The receipt drifts by +2000: the song uses the smaller (synced) offset.
    deliver(socket, startAtClock(env, { receiptMs: NEW, offsetMs: SYNC }))
    await flushUntil(() => ctx.startedSources().length === 1)
    expect(ctx.startedSources()[0].start.mock.calls[0][0]).toBeCloseTo(13, 6)
    expect(pingsOf(socket)).toHaveLength(12)
    // The drift burst's 3 pings are answered with the new offset. Kept with
    // the 5 stale SYNC samples of equal rtt, the estimate would stay SYNC.
    await answerPings(env, socket, 3, NEW)
    expect(pingsOf(socket)).toHaveLength(14)

    deliver(socket, revealMsg())
    deliver(socket, prepare(1))
    await flushUntil(() => socket.sentOfType('ready').length > 1)
    expect(socket.sentOfType('ready')).toEqual([
      { type: 'ready', index: 0 },
      { type: 'ready', index: 1 },
    ])
    // The ready burst's 3 pings go unanswered.
    await advance(env, 500)
    expect(pingsOf(socket)).toHaveLength(17)

    ctx.currentTime = 50
    // The receipt agrees with the new estimate: no drift, song 3 s away.
    // With the stale estimate (SYNC) it would drift and play 5 s away.
    deliver(socket, startAtClock(env, { receiptMs: NEW, offsetMs: NEW, index: 1 }))
    await flushUntil(() => ctx.startedSources().length === 2)
    expect(ctx.startedSources()).toHaveLength(2)
    const [when, offset] = ctx.startedSources()[1].start.mock.calls[0]
    expect(when).toBeCloseTo(53, 6)
    expect(offset).toBe(0)
    await advance(env, 1000)
    expect(pingsOf(socket)).toHaveLength(17)
  })
})
