// Cycle 049 (F-006): automatic reconnect of a room session. The token and
// its sessionStorage entry, the reconnect notice, the backoff (waits of
// 0.5, 1, 2, 4, 8, then 10 s; one 30 s limit from the drop), the 5 s attempt
// timer, rejoin{token} on each new socket, the snapshot rebuild (lobby,
// PLAYING, PREPARE, song:null, scores), frames before the snapshot,
// rejoin-failed, the 4001 replaced close, no token, solo, sends while not
// open, and leaving or unmounting while reconnecting.
// Timer rule: fake timers in every test (the backoff and the 5 s / 30 s
// timers matter everywhere here), driven by flushUntil / advance, with the
// injected now() moved together with the timers.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, act, cleanup, within } from '@testing-library/react'
import App from './App.jsx'
import { createSocketFactory, createAudioContextFactory, createFakeFetch } from './testing/fakes.js'

const T0 = 1_000_000
const FAKE_TIMERS = { toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval'] }
const STORAGE_KEY = 'songrush.rejoin'
const RECONNECTING = '연결이 끊겨 다시 연결하는 중...'
const DISCONNECTED = '서버와 연결이 끊어졌습니다.'
const REJOIN_FAILED = '다시 연결하지 못했습니다. 방에 다시 참가해 주세요.'
const REPLACED = '다른 창에서 접속해 이 연결이 끊겼습니다.'
const WATCHING = '이번 곡은 구경 중입니다. 다음 곡부터 참가합니다.'
// The synced offset the fake server reports through pong (server ahead 2.5 s).
const SYNC = 2500
const RTT = 20
// The times after the drop at which a new socket is created when every
// attempt fails at once: waits of 500, 1000, 2000, 4000, 8000, 10000 ms.
const ATTEMPT_TIMES = [500, 1500, 3500, 7500, 15_500, 25_500]

beforeEach(() => {
  window.history.pushState({}, '', '/')
  // jsdom keeps sessionStorage between the tests of one file.
  window.sessionStorage.clear()
  vi.useFakeTimers(FAKE_TIMERS)
})

afterEach(() => {
  cleanup()
  vi.useRealTimers()
  window.history.pushState({}, '', '/')
  window.sessionStorage.clear()
  vi.restoreAllMocks()
})

// Renders App with fakes. clock.t is what now() returns; dropAt is the clock
// at the last drop.
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
  return { sockets, audio, fetch, clock, dropAt: null }
}

function deliver(socket, msg) {
  act(() => socket.receive(msg))
}

function setNickname(nickname) {
  fireEvent.change(screen.getByLabelText('닉네임'), { target: { value: nickname } })
}

function pageText() {
  return document.body.textContent
}

function pingsOf(socket) {
  return socket.sentOfType('ping')
}

function stored() {
  const raw = window.sessionStorage.getItem(STORAGE_KEY)
  return raw === null ? null : JSON.parse(raw)
}

// The rows of the list labelled `name` (점수판); [] when absent.
function listLines(name) {
  const list = screen.queryByRole('list', { name })
  if (list === null) return []
  return within(list)
    .queryAllByRole('listitem')
    .map((li) => li.textContent)
}

function listRows() {
  return Array.from(document.querySelectorAll('li')).map((li) => li.textContent)
}

// Started sources not stopped by hand: the scheduled stop(stopAt) has an
// argument, a stop of a playing source has none.
function liveSources(ctx) {
  return ctx.startedSources().filter((s) => !s.stop.mock.calls.some((call) => call.length === 0))
}

function stoppedByHand(source) {
  return source.stop.mock.calls.some((call) => call.length === 0)
}

const player = (name, { host = false, score = 0, connected = true } = {}) => ({ name, score, host, connected })
const roster = (players, phase = 'lobby') => ({ type: 'roster', phase, players })
const HOST_ROSTER = [player('하루', { host: true }), player('Joon')]
// The roster after the rejoin: Joon left, Mina joined meanwhile.
const REJOIN_ROSTER = [player('하루', { host: true }), player('Mina')]
const joinedMsg = { type: 'joined', code: 'QWERT', name: '하루' }
const tokenMsg = { type: 'token', token: 'T1' }
const prepare = (index = 0, audioUrl = '/audio/abc') => ({ type: 'prepare', index, total: 5, audioUrl })

const snapshot = (phase, song, { players = HOST_ROSTER, scores = [] } = {}) => ({
  type: 'snapshot',
  phase,
  players,
  song,
  scores,
})

// A start at the current clock with receipt offset 500; the song is
// startInMs away by that offset (negative: already playing).
function receiptStart(env, { index = 0, startInMs = -1000, length = 90_000 } = {}) {
  const serverTime = env.clock.t + 500
  const start = serverTime + startInMs
  return { type: 'start', index, startAt: start, deadlineAt: start + length, serverTime }
}

// A PLAYING snapshot song that is 10 s in by the synced clock once the clock
// has moved RTT ms past now (see answerLatestPing).
function playingSong(env, { index = 0, audioUrl = '/audio/re' } = {}) {
  const startAt = env.clock.t + RTT + SYNC - 10_000
  return { index, total: 5, audioUrl, startAt, deadlineAt: startAt + 90_000 }
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

// Advances to `ms` after the last drop.
async function advanceToSinceDrop(env, ms) {
  const delta = env.dropAt + ms - env.clock.t
  expect(delta, 'the target time is not in the past').toBeGreaterThanOrEqual(0)
  await advance(env, delta)
}

// Closes the socket from the server side and records the drop time.
function drop(env, socket, code = 1006) {
  env.dropAt = env.clock.t
  act(() => socket.serverClose(code))
}

// Answers the latest ping, sent at the current clock, after RTT ms with the
// SYNC offset; the clock is left RTT ms later.
function answerLatestPing(env, socket) {
  const sent = pingsOf(socket)
  expect(sent.length, 'a ping should have been sent').toBeGreaterThan(0)
  const ping = sent[sent.length - 1]
  const t0 = env.clock.t
  env.clock.t += RTT
  deliver(socket, { type: 'pong', id: ping.id, serverTime: t0 + RTT / 2 + SYNC })
}

// 방 만들기 as 하루 and open the socket.
async function createRoom(env) {
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

// The lobby as host 하루 of QWERT: joined, roster, then token{T1}.
async function lobbyWithToken(env) {
  const socket = await createRoom(env)
  deliver(socket, joinedMsg)
  deliver(socket, roster(HOST_ROSTER))
  deliver(socket, tokenMsg)
  return socket
}

// Song 0 of a room game playing (1 s in), with the token received.
async function songPlaying(env) {
  const socket = await lobbyWithToken(env)
  deliver(socket, roster(HOST_ROSTER, 'playing'))
  deliver(socket, prepare(0))
  await flushUntil(() => socket.sentOfType('ready').length > 0)
  expect(socket.sentOfType('ready')).toEqual([{ type: 'ready', index: 0 }])
  const ctx = env.audio.last
  deliver(socket, receiptStart(env))
  await flushUntil(() => ctx.startedSources().length === 1 && screen.queryByRole('progressbar') !== null)
  expect(ctx.startedSources()).toHaveLength(1)
  return socket
}

// Advances to `ms` after the drop, where attempt number `count` (sockets in
// total) must appear and not 1 ms earlier. Returns the new socket, not opened.
async function attemptAt(env, ms, count) {
  await advanceToSinceDrop(env, ms - 1)
  expect(env.sockets.sockets, `no socket ${count} before ${ms} ms`).toHaveLength(count - 1)
  await advance(env, 1)
  expect(env.sockets.sockets, `socket ${count} at ${ms} ms`).toHaveLength(count)
  const socket = env.sockets.last
  expect(socket.url).toBe(env.sockets.sockets[0].url)
  return socket
}

// Opens an attempt and checks that it sends exactly rejoin{token:"T1"}.
async function openAttempt(socket) {
  act(() => socket.open())
  await flushUntil(() => socket.sent.length > 0)
  expect(socket.sent).toEqual([{ type: 'rejoin', token: 'T1' }])
}

// A reconnect at 500 ms after a drop: the new socket, opened.
async function reconnect(env, socket) {
  drop(env, socket)
  const attempt = await attemptAt(env, 500, env.sockets.sockets.length + 1)
  await openAttempt(attempt)
  return attempt
}

describe('token storage', () => {
  it('token{T1} stores {token:"T1", code} under songrush.rejoin; 나가기 clears it', async () => {
    const env = setup()
    const socket = await createRoom(env)
    deliver(socket, joinedMsg)
    deliver(socket, roster(HOST_ROSTER))
    expect(stored()).toBeNull()
    deliver(socket, tokenMsg)
    expect(stored()).toEqual({ token: 'T1', code: 'QWERT' })
    // The token is private: nothing is sent back for it.
    expect(socket.sentOfType('token')).toEqual([])

    fireEvent.click(screen.getByRole('button', { name: '나가기' }))
    expect(socket.sentOfType('leave')).toEqual([{ type: 'leave' }])
    expect(stored()).toBeNull()
    expect(screen.getByRole('button', { name: '방 만들기' })).toBeTruthy()
  })
})

describe('reconnect into the lobby', () => {
  it('a 1006 close shows the notice; at 500 ms a new socket sends exactly rejoin{T1}; joined + lobby snapshot show the snapshot roster', async () => {
    const env = setup()
    const socket = await lobbyWithToken(env)
    drop(env, socket)
    expect(screen.getByText(RECONNECTING)).toBeTruthy()
    // The screen keeps its content.
    expect(pageText()).toContain('방 코드: QWERT')
    expect(pageText()).not.toContain(DISCONNECTED)

    const attempt = await attemptAt(env, 500, 2)
    await openAttempt(attempt)
    // Nothing more goes to the dropped socket.
    expect(socket.sentOfType('rejoin')).toEqual([])

    deliver(attempt, joinedMsg)
    expect(pageText()).not.toContain(RECONNECTING)
    deliver(attempt, snapshot('lobby', null, { players: REJOIN_ROSTER }))
    expect(pageText()).toContain('방 코드: QWERT')
    expect(listRows()).toEqual(['하루 (방장) (나)', 'Mina'])
    expect(screen.getByRole('button', { name: '게임 시작' })).toBeTruthy()
    expect(pageText()).not.toContain(RECONNECTING)
    expect(pageText()).not.toContain(DISCONNECTED)

    // A fresh joined burst of 8 pings on the new socket.
    expect(pingsOf(attempt)).toHaveLength(1)
    await advance(env, 1100)
    expect(pingsOf(attempt)).toHaveLength(8)
    expect(env.sockets.sockets).toHaveLength(2)
  })
})

describe('backoff', () => {
  it('attempts that close at once come at 0.5, 1.5, 3.5, 7.5, 15.5 and 25.5 s; at 30 s the disconnect message, then no socket', async () => {
    const env = setup()
    const socket = await lobbyWithToken(env)
    drop(env, socket)
    for (let i = 0; i < ATTEMPT_TIMES.length; i++) {
      const attempt = await attemptAt(env, ATTEMPT_TIMES[i], i + 2)
      act(() => attempt.serverClose(1006))
      expect(screen.getByText(RECONNECTING)).toBeTruthy()
    }
    await advanceToSinceDrop(env, 29_999)
    expect(env.sockets.sockets).toHaveLength(7)
    expect(pageText()).not.toContain(DISCONNECTED)
    await advance(env, 1)
    expect(screen.getByText(DISCONNECTED)).toBeTruthy()
    expect(screen.getByRole('button', { name: '처음으로' })).toBeTruthy()
    expect(pageText()).not.toContain(RECONNECTING)
    await advance(env, 60_000)
    expect(env.sockets.sockets).toHaveLength(7)
  })
})

describe('attempt timing', () => {
  it('an attempt that opens but gets no joined within 5 s is closed; the next follows after the next wait (1 s)', async () => {
    const env = setup()
    const socket = await lobbyWithToken(env)
    drop(env, socket)
    const attempt = await attemptAt(env, 500, 2)
    await openAttempt(attempt)
    await advanceToSinceDrop(env, 5499)
    expect(attempt.closed).toBe(false)
    await advance(env, 1)
    expect(attempt.closed).toBe(true)
    // Closed with its handlers cleared.
    expect(attempt.onclose).toBeNull()
    expect(attempt.onmessage).toBeNull()
    expect(screen.getByText(RECONNECTING)).toBeTruthy()
    const next = await attemptAt(env, 6500, 3)
    await openAttempt(next)
  })

  it('an attempt that stays connecting for 5 s is dropped, sends nothing, and the next follows after the next wait', async () => {
    const env = setup()
    const socket = await lobbyWithToken(env)
    drop(env, socket)
    const attempt = await attemptAt(env, 500, 2)
    await advanceToSinceDrop(env, 5499)
    expect(attempt.closed).toBe(false)
    await advance(env, 1)
    expect(attempt.closed).toBe(true)
    expect(attempt.onopen).toBeNull()
    expect(attempt.onclose).toBeNull()
    expect(attempt.sent).toEqual([])
    const next = await attemptAt(env, 6500, 3)
    await openAttempt(next)
  })

  it('at exactly 30 s after the drop the disconnect message appears while an attempt is in progress, which is closed', async () => {
    const env = setup()
    const socket = await lobbyWithToken(env)
    drop(env, socket)
    for (let i = 0; i < 5; i++) {
      const failed = await attemptAt(env, ATTEMPT_TIMES[i], i + 2)
      act(() => failed.serverClose(1006))
    }
    // The 6th attempt opens at 25.5 s and stays silent; its own 5 s timer
    // would end at 30.5 s.
    const attempt = await attemptAt(env, 25_500, 7)
    await openAttempt(attempt)
    await advanceToSinceDrop(env, 29_999)
    expect(attempt.closed).toBe(false)
    expect(pageText()).not.toContain(DISCONNECTED)
    await advance(env, 1)
    expect(screen.getByText(DISCONNECTED)).toBeTruthy()
    expect(attempt.closed).toBe(true)
    expect(attempt.onclose).toBeNull()
    expect(attempt.onmessage).toBeNull()
    await advance(env, 60_000)
    expect(env.sockets.sockets).toHaveLength(7)
  })
})

describe('reconnect into a song', () => {
  it('the drop stops the source and the timer; a PLAYING snapshot loads the audio and starts it late after a pong, as a played round', async () => {
    const env = setup()
    const socket = await songPlaying(env)
    const ctx = env.audio.last
    const first = ctx.startedSources()[0]
    // The remaining-time bar ticks while the song plays.
    const before = screen.getByRole('progressbar').getAttribute('aria-valuenow')
    await advance(env, 400)
    const ticking = screen.getByRole('progressbar').getAttribute('aria-valuenow')
    expect(ticking).not.toBe(before)
    // A skip vote before the drop.
    fireEvent.click(screen.getByRole('button', { name: '스킵' }))
    expect(screen.getByRole('button', { name: '스킵 취소' })).toBeTruthy()

    drop(env, socket)
    expect(screen.getByText(RECONNECTING)).toBeTruthy()
    expect(stoppedByHand(first)).toBe(true)
    expect(pageText()).toContain('1 / 5')
    const atDrop = screen.queryByRole('progressbar')
    const valueAtDrop = atDrop === null ? null : atDrop.getAttribute('aria-valuenow')
    await advanceToSinceDrop(env, 400)
    const bar = screen.queryByRole('progressbar')
    if (bar !== null) expect(bar.getAttribute('aria-valuenow')).toBe(valueAtDrop)

    const attempt = await attemptAt(env, 500, 2)
    await openAttempt(attempt)
    deliver(attempt, joinedMsg)
    const song = playingSong(env, { index: 0 })
    deliver(attempt, snapshot('playing', song, { players: HOST_ROSTER }))
    deliver(attempt, roster(HOST_ROSTER, 'playing'))
    expect(pageText()).not.toContain(RECONNECTING)
    await flushUntil(() => ctx.decoded.length === 2)
    expect(env.fetch.urls).toEqual(['/audio/abc', '/audio/re'])
    await settle()
    // No clock estimate on the fresh sync yet: no start.
    expect(ctx.startedSources()).toHaveLength(1)

    ctx.currentTime = 7
    answerLatestPing(env, attempt)
    await flushUntil(() => ctx.startedSources().length === 2)
    expect(ctx.startedSources()).toHaveLength(2)
    const source = ctx.startedSources()[1]
    expect(source.buffer).toBe(ctx.decoded[1])
    // 10 s in by the synced clock: at once, from 10 s, stopping 80 s later.
    const [when, offset] = source.start.mock.calls[0]
    expect(when).toBeCloseTo(7, 6)
    expect(offset).toBeCloseTo(10, 6)
    expect(source.stop.mock.calls[0][0]).toBeCloseTo(87, 6)
    // A played round: no watching notice, and the skip button starts as 스킵.
    expect(pageText()).not.toContain(WATCHING)
    expect(screen.getByRole('button', { name: '스킵' })).toBeTruthy()
    expect(screen.queryByRole('button', { name: '스킵 취소' })).toBeNull()
    expect(screen.getByRole('progressbar')).toBeTruthy()
  })

  it('a PREPARE snapshot loads the audio and sends ready{index}; its start then plays', async () => {
    const env = setup()
    const socket = await songPlaying(env)
    const ctx = env.audio.last
    const attempt = await reconnect(env, socket)
    deliver(attempt, joinedMsg)
    deliver(attempt, snapshot('playing', { index: 1, total: 5, audioUrl: '/audio/re' }))
    await flushUntil(() => attempt.sentOfType('ready').length > 0)
    expect(attempt.sentOfType('ready')).toEqual([{ type: 'ready', index: 1 }])
    expect(env.fetch.urls).toEqual(['/audio/abc', '/audio/re'])
    expect(pageText()).toContain('2 / 5')
    expect(pageText()).not.toContain(WATCHING)
    expect(ctx.startedSources()).toHaveLength(1)

    deliver(attempt, receiptStart(env, { index: 1, startInMs: 3000 }))
    await flushUntil(() => ctx.startedSources().length === 2)
    expect(ctx.startedSources()).toHaveLength(2)
    expect(ctx.startedSources()[1].buffer).toBe(ctx.decoded[1])
    expect(screen.getByRole('button', { name: '스킵' })).toBeTruthy()
  })

  it('a song:null snapshot shows the game screen; the next prepare plays normally', async () => {
    const env = setup()
    const socket = await songPlaying(env)
    const ctx = env.audio.last
    const attempt = await reconnect(env, socket)
    deliver(attempt, joinedMsg)
    deliver(attempt, snapshot('playing', null))
    expect(pageText()).not.toContain(RECONNECTING)
    // The game screen, not the lobby.
    expect(screen.getByLabelText('정답 입력')).toBeTruthy()
    expect(pageText()).not.toContain('방 코드:')
    expect(pageText()).not.toContain(WATCHING)
    await settle()
    expect(attempt.sentOfType('ready')).toEqual([])

    deliver(attempt, prepare(1, '/audio/next'))
    await flushUntil(() => attempt.sentOfType('ready').length > 0)
    expect(attempt.sentOfType('ready')).toEqual([{ type: 'ready', index: 1 }])
    deliver(attempt, receiptStart(env, { index: 1, startInMs: 3000 }))
    await flushUntil(() => ctx.startedSources().length === 2)
    expect(ctx.startedSources()).toHaveLength(2)
    expect(liveSources(ctx)).toHaveLength(1)
    expect(screen.getByRole('button', { name: '스킵' })).toBeTruthy()
  })
})

describe('snapshot data', () => {
  it('a playing snapshot with scores shows 점수판 and the own score from it', async () => {
    const env = setup()
    const socket = await songPlaying(env)
    const attempt = await reconnect(env, socket)
    deliver(attempt, joinedMsg)
    deliver(
      attempt,
      snapshot('playing', null, {
        scores: [
          { name: 'Joon', score: 3 },
          { name: '하루', score: 2 },
        ],
      }),
    )
    expect(listLines('점수판')).toEqual(['Joon 3점', '하루 2점 (나)'])
    expect(screen.getByText('점수: 2')).toBeTruthy()
  })
})

describe('frames before the snapshot', () => {
  it('a start for the old index before joined plays nothing; joined + PLAYING snapshot give one source; a second pair does not add another', async () => {
    const env = setup()
    const socket = await songPlaying(env)
    const ctx = env.audio.last
    const attempt = await reconnect(env, socket)
    expect(liveSources(ctx)).toHaveLength(0)

    // Queued broadcasts flushed to the new socket before joined.
    deliver(attempt, receiptStart(env, { index: 0, startInMs: -2000 }))
    deliver(attempt, { type: 'chat', name: 'Joon', text: '늦은 채팅' })
    deliver(attempt, roster(REJOIN_ROSTER, 'playing'))
    await settle()
    expect(ctx.startedSources()).toHaveLength(1)
    expect(pageText()).not.toContain('Joon: 늦은 채팅')

    deliver(attempt, joinedMsg)
    deliver(attempt, snapshot('playing', playingSong(env, { index: 0 })))
    await flushUntil(() => ctx.decoded.length === 2)
    ctx.currentTime = 7
    answerLatestPing(env, attempt)
    await flushUntil(() => ctx.startedSources().length === 2)
    await settle()
    expect(ctx.startedSources()).toHaveLength(2)
    expect(liveSources(ctx)).toHaveLength(1)

    // The same pair again rebuilds to the same result: one playing source.
    deliver(attempt, joinedMsg)
    deliver(attempt, snapshot('playing', playingSong(env, { index: 0 })))
    await flushUntil(() => ctx.decoded.length === 3)
    answerLatestPing(env, attempt)
    await settle()
    expect(liveSources(ctx)).toHaveLength(1)
    expect(pageText()).not.toContain(WATCHING)
  })
})

describe('failures', () => {
  it('rejoin-failed shows its message on the start screen, closes the attempt and clears the storage entry', async () => {
    const env = setup()
    const socket = await lobbyWithToken(env)
    expect(stored()).not.toBeNull()
    const attempt = await reconnect(env, socket)
    deliver(attempt, { type: 'error', code: 'rejoin-failed' })
    expect(screen.getByText(REJOIN_FAILED)).toBeTruthy()
    expect(screen.getByRole('button', { name: '방 만들기' })).toBeTruthy()
    expect(pageText()).not.toContain(RECONNECTING)
    expect(stored()).toBeNull()
    expect(attempt.closed).toBe(true)
    expect(attempt.onclose).toBeNull()
    await advance(env, 31_000)
    expect(env.sockets.sockets).toHaveLength(2)
  })

  it('a 4001 close shows the replaced message with 처음으로 and opens no socket within 30 s', async () => {
    const env = setup()
    const socket = await lobbyWithToken(env)
    drop(env, socket, 4001)
    expect(screen.getByText(REPLACED)).toBeTruthy()
    expect(screen.getByRole('button', { name: '처음으로' })).toBeTruthy()
    expect(pageText()).not.toContain(RECONNECTING)
    expect(stored()).toBeNull()
    await advance(env, 31_000)
    expect(env.sockets.sockets).toHaveLength(1)
    fireEvent.click(screen.getByRole('button', { name: '처음으로' }))
    expect(screen.getByRole('button', { name: '방 만들기' })).toBeTruthy()
  })

  it('a 4001 close of a reconnect attempt also ends the session with the replaced message', async () => {
    const env = setup()
    const socket = await lobbyWithToken(env)
    const attempt = await reconnect(env, socket)
    act(() => attempt.serverClose(4001))
    expect(screen.getByText(REPLACED)).toBeTruthy()
    expect(pageText()).not.toContain(RECONNECTING)
    expect(stored()).toBeNull()
    await advance(env, 31_000)
    expect(env.sockets.sockets).toHaveLength(2)
  })
})

describe('without a token', () => {
  it('an unexpected close shows the disconnect message at once and no new socket appears', async () => {
    const env = setup()
    const socket = await createRoom(env)
    deliver(socket, joinedMsg)
    deliver(socket, roster(HOST_ROSTER))
    drop(env, socket)
    expect(screen.getByText(DISCONNECTED)).toBeTruthy()
    expect(pageText()).not.toContain(RECONNECTING)
    await advance(env, 31_000)
    expect(env.sockets.sockets).toHaveLength(1)
    expect(stored()).toBeNull()
  })
})

describe('solo', () => {
  it('a solo session never reconnects, also after a token', async () => {
    const env = setup()
    setNickname('하루')
    fireEvent.change(screen.getByLabelText('곡 수'), { target: { value: '5' } })
    fireEvent.click(screen.getByRole('button', { name: '시작' }))
    await flushUntil(() => env.sockets.sockets.length === 1)
    const socket = env.sockets.last
    act(() => socket.open())
    await flushUntil(() => socket.sent.length > 0)
    expect(socket.sent).toEqual([{ type: 'create', nickname: '하루', songCount: 5 }])
    deliver(socket, joinedMsg)
    deliver(socket, tokenMsg)
    deliver(socket, prepare(0))
    await flushUntil(() => socket.sentOfType('ready').length > 0)
    drop(env, socket)
    expect(screen.getByText(DISCONNECTED)).toBeTruthy()
    expect(pageText()).not.toContain(RECONNECTING)
    await advance(env, 31_000)
    expect(env.sockets.sockets).toHaveLength(1)
  })
})

describe('sends while not open', () => {
  it('while reconnecting, submitting chat sends nothing, on the dropped socket or the connecting one', async () => {
    const env = setup()
    const socket = await songPlaying(env)
    drop(env, socket)
    const sentBefore = socket.sent.length
    fireEvent.change(screen.getByLabelText('정답 입력'), { target: { value: '진격의 거인' } })
    fireEvent.click(screen.getByRole('button', { name: '보내기' }))
    expect(socket.sent).toHaveLength(sentBefore)

    const attempt = await attemptAt(env, 500, 2)
    fireEvent.change(screen.getByLabelText('정답 입력'), { target: { value: '진격의 거인' } })
    fireEvent.click(screen.getByRole('button', { name: '보내기' }))
    expect(attempt.sent).toEqual([])
    expect(socket.sent).toHaveLength(sentBefore)
    await openAttempt(attempt)
  })
})

describe('leaving while reconnecting', () => {
  it('나가기 during a reconnect returns to the start screen; no socket within 30 s; the storage entry is gone', async () => {
    const env = setup()
    const socket = await lobbyWithToken(env)
    drop(env, socket)
    await advanceToSinceDrop(env, 200)
    expect(screen.getByText(RECONNECTING)).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: '나가기' }))
    expect(screen.getByRole('button', { name: '방 만들기' })).toBeTruthy()
    expect(pageText()).not.toContain(RECONNECTING)
    expect(stored()).toBeNull()
    await advance(env, 31_000)
    expect(env.sockets.sockets).toHaveLength(1)
    expect(pageText()).not.toContain(DISCONNECTED)
  })

  it('나가기 while an attempt is connecting closes it with handlers cleared and sends nothing on it', async () => {
    const env = setup()
    const socket = await lobbyWithToken(env)
    drop(env, socket)
    const attempt = await attemptAt(env, 500, 2)
    await advance(env, 100)
    fireEvent.click(screen.getByRole('button', { name: '나가기' }))
    expect(screen.getByRole('button', { name: '방 만들기' })).toBeTruthy()
    expect(attempt.closed).toBe(true)
    expect(attempt.onopen).toBeNull()
    expect(attempt.onclose).toBeNull()
    expect(attempt.sent).toEqual([])
    expect(stored()).toBeNull()
    await advance(env, 31_000)
    expect(env.sockets.sockets).toHaveLength(2)
  })

  it('unmounting during a reconnect cancels it: no socket afterwards', async () => {
    const env = setup()
    const socket = await lobbyWithToken(env)
    drop(env, socket)
    const attempt = await attemptAt(env, 500, 2)
    act(() => attempt.serverClose(1006))
    cleanup()
    await advance(env, 31_000)
    expect(env.sockets.sockets).toHaveLength(2)
    expect(stored()).toBeNull()
  })
})
