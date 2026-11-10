// Cycle 079 review: two gaps of the reload rejoin (App.reload.test.jsx is
// locked, so they live here, with the same harness).
// M1: the first socket after a reload has the cycle 049 attempt time
// (ATTEMPT_MS, 5 s) to reach joined, whether it opens and gets no answer or
// stays connecting; then the start screen shows the rejoin-failed text, the
// entry is removed and nothing reconnects.
// M2: the audio context is created at mount without a gesture, so it may be
// suspended; the 탭하여 소리 켜기 prompt shows in the rejoined game and its
// click resumes the context.
// Cycle 114 (D-124, declared): the lobby's room code is read from the
// ticket's .tk-code through src/testing/lobby.js.
// Timer rule: fake timers in every test, with the injected now() moved
// together with the timers.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, act, cleanup } from '@testing-library/react'
import App from './App.jsx'
import { createSocketFactory, createAudioContextFactory, createFakeFetch } from './testing/fakes.js'
import { lobbyCode } from './testing/lobby.js'

const T0 = 1_000_000
const FAKE_TIMERS = { toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval'] }
const STORAGE_KEY = 'songrush.rejoin'
const ENTRY = { token: 'T1', code: 'ABCDE' }
const RECONNECTING = '연결이 끊겨 다시 연결하는 중...'
const REJOIN_FAILED = '다시 연결하지 못했습니다. 방에 다시 참가해 주세요.'
const SOUND_PROMPT = '탭하여 소리 켜기'
// The cycle 049 attempt time: an attempt that has no joined by then fails.
const ATTEMPT_MS = 5000
const SYNC = 2500
const RTT = 20

beforeEach(() => {
  window.history.pushState({}, '', '/')
  // jsdom keeps sessionStorage between the tests of one file.
  window.sessionStorage.clear()
  vi.useFakeTimers(FAKE_TIMERS)
})

afterEach(() => {
  cleanup()
  vi.useRealTimers()
  vi.restoreAllMocks()
  window.history.pushState({}, '', '/')
  window.sessionStorage.clear()
})

function seed() {
  window.sessionStorage.setItem(STORAGE_KEY, JSON.stringify(ENTRY))
}

// Renders App with fakes after a reload. clock.t is what now() returns.
function setup(audioOptions) {
  const sockets = createSocketFactory()
  const audio = createAudioContextFactory(audioOptions)
  const fetch = createFakeFetch()
  const clock = { t: T0 }
  render(
    <App
      canPlayOpus={true}
      socketFactory={sockets.factory}
      audioContextFactory={audio.factory}
      fetchFn={fetch.fetchFn}
      now={() => clock.t}
      navigationType="reload"
    />,
  )
  return { sockets, audio, fetch, clock }
}

function deliver(socket, msg) {
  act(() => socket.receive(msg))
}

function pageText() {
  return document.body.textContent
}

function stored() {
  const raw = window.sessionStorage.getItem(STORAGE_KEY)
  return raw === null ? null : JSON.parse(raw)
}

const player = (name, { host = false, score = 0, connected = true } = {}) => ({ name, score, host, connected })
const roster = (players, phase = 'lobby') => ({ type: 'roster', phase, players })
const ROSTER = [player('하루', { host: true }), player('Mina')]
const joinedMsg = { type: 'joined', code: 'ABCDE', name: '하루' }

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

function answerLatestPing(env, socket) {
  const sent = socket.sentOfType('ping')
  expect(sent.length, 'a ping should have been sent').toBeGreaterThan(0)
  const ping = sent[sent.length - 1]
  const t0 = env.clock.t
  env.clock.t += RTT
  deliver(socket, { type: 'pong', id: ping.id, serverTime: t0 + RTT / 2 + SYNC })
}

// The one reload socket, created at mount (clock T0).
async function firstSocket(env) {
  await flushUntil(() => env.sockets.sockets.length === 1)
  expect(env.sockets.sockets).toHaveLength(1)
  return env.sockets.last
}

// Still waiting just before ATTEMPT_MS; at ATTEMPT_MS the start screen with
// the rejoin-failed text, the socket closed, the entry gone; then nothing.
async function expectAttemptTimeout(env, socket) {
  await advance(env, ATTEMPT_MS - 1 - (env.clock.t - T0))
  expect(screen.getByText(RECONNECTING)).toBeTruthy()
  expect(pageText()).not.toContain(REJOIN_FAILED)
  expect(socket.closed).toBe(false)
  expect(stored()).toEqual(ENTRY)

  await advance(env, 1)
  expect(screen.getByText(REJOIN_FAILED)).toBeTruthy()
  expect(screen.getByRole('button', { name: '방 만들기' })).toBeTruthy()
  expect(pageText()).not.toContain(RECONNECTING)
  expect(stored()).toBeNull()
  expect(socket.closed).toBe(true)
  expect(socket.onclose).toBeNull()
  expect(socket.onmessage).toBeNull()

  await advance(env, 31_000)
  expect(env.sockets.sockets).toHaveLength(1)
  expect(screen.getByText(REJOIN_FAILED)).toBeTruthy()
}

describe('M1: the reload socket has 5 s to reach joined', () => {
  it('an open socket that gets no answer fails at 5 s: rejoin-failed on the start screen, entry removed, no retry', async () => {
    seed()
    const env = setup()
    const socket = await firstSocket(env)
    act(() => socket.open())
    await flushUntil(() => socket.sent.length > 0)
    expect(socket.sent).toEqual([{ type: 'rejoin', token: 'T1' }])
    await expectAttemptTimeout(env, socket)
    expect(socket.sent).toEqual([{ type: 'rejoin', token: 'T1' }])
  })

  it('a socket that stays connecting fails at 5 s the same way and sends nothing', async () => {
    seed()
    const env = setup()
    const socket = await firstSocket(env)
    await expectAttemptTimeout(env, socket)
    expect(socket.onopen).toBeNull()
    expect(socket.sent).toEqual([])
  })

  it('a joined before 5 s keeps the room: no failure at 5 s or later', async () => {
    seed()
    const env = setup()
    const socket = await firstSocket(env)
    act(() => socket.open())
    await advance(env, 4000)
    deliver(socket, joinedMsg)
    deliver(socket, { type: 'snapshot', phase: 'lobby', players: ROSTER, song: null, scores: [] })
    deliver(socket, roster(ROSTER))
    await advance(env, 31_000)
    expect(lobbyCode()).toBe('ABCDE')
    expect(pageText()).not.toContain(REJOIN_FAILED)
    expect(socket.closed).toBe(false)
    expect(stored()).toEqual(ENTRY)
    expect(env.sockets.sockets).toHaveLength(1)
  })
})

describe('M2: a suspended context after a reload', () => {
  it('a playing snapshot shows 탭하여 소리 켜기; its click resumes the context and the prompt goes', async () => {
    seed()
    const env = setup({ state: 'suspended', deferResume: true })
    const socket = await firstSocket(env)
    const ctx = env.audio.last
    expect(ctx).toBeTruthy()
    expect(ctx.state).toBe('suspended')
    act(() => socket.open())
    await flushUntil(() => socket.sent.length > 0)
    deliver(socket, joinedMsg)
    deliver(socket, { type: 'snapshot', phase: 'playing', players: ROSTER, song: playingSong(env), scores: [] })
    deliver(socket, roster(ROSTER, 'playing'))
    await flushUntil(() => ctx.decoded.length === 1)
    answerLatestPing(env, socket)
    await settle()

    const prompt = screen.getByRole('button', { name: SOUND_PROMPT })
    expect(prompt).toBeTruthy()
    const resumesBefore = ctx.resume.mock.calls.length
    fireEvent.click(prompt)
    expect(ctx.resume.mock.calls.length).toBeGreaterThan(resumesBefore)
    // Still suspended until the resume settles: the prompt stays.
    expect(screen.getByRole('button', { name: SOUND_PROMPT })).toBeTruthy()

    act(() => ctx.resolveResume())
    await flushUntil(() => screen.queryByRole('button', { name: SOUND_PROMPT }) === null)
    expect(ctx.state).toBe('running')
    expect(screen.queryByRole('button', { name: SOUND_PROMPT })).toBeNull()
  })
})
