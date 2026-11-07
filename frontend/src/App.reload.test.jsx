// Cycle 079: a page reload rejoins the room. On mount App reads the
// sessionStorage entry songrush.rejoin {token, code}; only a real reload
// (the navigationType prop here, the Performance API in the browser) opens a
// rejoin socket that sends rejoin{token} first. joined, snapshot (lobby or a
// playing game) and roster then rebuild the screen as in cycle 049.
// rejoin-failed or a close before joined clears the entry and shows the
// start screen with the rejoin-failed text. Any other navigation type, a
// missing or malformed entry, or a blocked storage leaves the start screen.
// Timer rule: fake timers in every test, as in App.reconnect.test.jsx, with
// the injected now() moved together with the timers.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, act, cleanup } from '@testing-library/react'
import App from './App.jsx'
import { createSocketFactory, createAudioContextFactory, createFakeFetch } from './testing/fakes.js'

const T0 = 1_000_000
const FAKE_TIMERS = { toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval'] }
const STORAGE_KEY = 'songrush.rejoin'
const ENTRY = { token: 'T1', code: 'ABCDE' }
const RECONNECTING = '연결이 끊겨 다시 연결하는 중...'
const REJOIN_FAILED = '다시 연결하지 못했습니다. 방에 다시 참가해 주세요.'
// The synced offset the fake server reports through pong (server ahead 2.5 s).
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

function seed(value = JSON.stringify(ENTRY)) {
  window.sessionStorage.setItem(STORAGE_KEY, value)
}

// Renders App with fakes and the given navigation type. clock.t is what
// now() returns.
function setup(navigationType) {
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
      navigationType={navigationType}
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

function listRows() {
  return Array.from(document.querySelectorAll('li')).map((li) => li.textContent)
}

const player = (name, { host = false, score = 0, connected = true } = {}) => ({ name, score, host, connected })
const roster = (players, phase = 'lobby') => ({ type: 'roster', phase, players })
const ROSTER = [player('하루', { host: true }), player('Mina')]
const joinedMsg = { type: 'joined', code: 'ABCDE', name: '하루' }

const snapshot = (phase, song, { players = ROSTER, scores = [] } = {}) => ({
  type: 'snapshot',
  phase,
  players,
  song,
  scores,
})

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

// The reload rejoin socket: exactly one, opened, having sent exactly
// rejoin{token:"T1"}.
async function rejoinSocket(env) {
  await flushUntil(() => env.sockets.sockets.length === 1)
  expect(env.sockets.sockets).toHaveLength(1)
  const socket = env.sockets.last
  act(() => socket.open())
  await flushUntil(() => socket.sent.length > 0)
  expect(socket.sent).toEqual([{ type: 'rejoin', token: 'T1' }])
  return socket
}

// The start screen with no error and no socket.
async function expectPlainStart(env) {
  await settle()
  expect(screen.getByRole('button', { name: '방 만들기' })).toBeTruthy()
  expect(pageText()).not.toContain(REJOIN_FAILED)
  expect(pageText()).not.toContain(RECONNECTING)
  expect(env.sockets.sockets).toHaveLength(0)
}

describe('reload into the lobby', () => {
  it('an entry and reload open exactly one socket that sends rejoin{T1}; joined, a lobby snapshot and roster show the lobby', async () => {
    seed()
    const env = setup('reload')
    await flushUntil(() => env.sockets.sockets.length === 1)
    // The connecting state uses the reconnect banner.
    expect(screen.getByText(RECONNECTING)).toBeTruthy()
    expect(screen.queryByRole('button', { name: '방 만들기' })).toBeNull()

    const socket = await rejoinSocket(env)
    expect(socket.sentOfType('ping')).toEqual([])
    deliver(socket, joinedMsg)
    deliver(socket, snapshot('lobby', null))
    deliver(socket, roster(ROSTER))
    expect(pageText()).toContain('방 코드: ABCDE')
    expect(listRows()).toEqual(['하루 (방장) (나)', 'Mina'])
    expect(screen.getByRole('button', { name: '게임 시작' })).toBeTruthy()
    expect(pageText()).not.toContain(RECONNECTING)
    expect(pageText()).not.toContain(REJOIN_FAILED)
    // The clock sync burst starts after joined.
    expect(socket.sentOfType('ping').length).toBeGreaterThan(0)
    // The entry stays until the next token or a leave.
    expect(stored()).toEqual(ENTRY)

    await advance(env, 31_000)
    expect(env.sockets.sockets).toHaveLength(1)
  })

  it('frames before the snapshot are ignored, as on a cycle 049 rejoin socket', async () => {
    seed()
    const env = setup('reload')
    const socket = await rejoinSocket(env)
    deliver(socket, { type: 'chat', name: 'Mina', text: '먼저 온 채팅' })
    deliver(socket, joinedMsg)
    deliver(socket, { type: 'chat', name: 'Mina', text: '스냅샷 전 채팅' })
    deliver(socket, snapshot('lobby', null))
    deliver(socket, roster(ROSTER))
    expect(pageText()).not.toContain('먼저 온 채팅')
    expect(pageText()).not.toContain('스냅샷 전 채팅')
    expect(pageText()).toContain('방 코드: ABCDE')
  })
})

describe('reload into a game', () => {
  it('a playing snapshot shows the game stage with the round text and, after a pong, the time bar and the sound', async () => {
    seed()
    const env = setup('reload')
    const socket = await rejoinSocket(env)
    const ctx = env.audio.last
    expect(ctx).toBeTruthy()
    deliver(socket, joinedMsg)
    deliver(socket, snapshot('playing', playingSong(env, { index: 0 })))
    deliver(socket, roster(ROSTER, 'playing'))
    expect(pageText()).not.toContain(RECONNECTING)
    expect(pageText()).toContain('1 / 5')
    expect(screen.getByLabelText('정답 입력')).toBeTruthy()
    expect(pageText()).not.toContain('방 코드:')

    await flushUntil(() => ctx.decoded.length === 1)
    expect(env.fetch.urls).toEqual(['/audio/re'])
    ctx.currentTime = 7
    answerLatestPing(env, socket)
    await flushUntil(() => ctx.startedSources().length === 1 && screen.queryByRole('progressbar') !== null)
    expect(screen.getByRole('progressbar')).toBeTruthy()
    expect(ctx.startedSources()).toHaveLength(1)
    // A played round, already running: no ready is sent for it.
    expect(socket.sentOfType('ready')).toEqual([])
    expect(screen.getByRole('button', { name: '스킵' })).toBeTruthy()
  })
})

describe('reload failures', () => {
  it('rejoin-failed shows its text on the start screen, removes the entry and closes the socket', async () => {
    seed()
    const env = setup('reload')
    const socket = await rejoinSocket(env)
    deliver(socket, { type: 'error', code: 'rejoin-failed' })
    expect(screen.getByText(REJOIN_FAILED)).toBeTruthy()
    expect(screen.getByRole('button', { name: '방 만들기' })).toBeTruthy()
    expect(pageText()).not.toContain(RECONNECTING)
    expect(stored()).toBeNull()
    expect(socket.closed).toBe(true)
    expect(socket.onclose).toBeNull()
    await advance(env, 31_000)
    expect(env.sockets.sockets).toHaveLength(1)
  })

  it('a close before joined gives the same outcome, with no retry', async () => {
    seed()
    const env = setup('reload')
    const socket = await rejoinSocket(env)
    act(() => socket.serverClose(1006))
    expect(screen.getByText(REJOIN_FAILED)).toBeTruthy()
    expect(screen.getByRole('button', { name: '방 만들기' })).toBeTruthy()
    expect(pageText()).not.toContain(RECONNECTING)
    expect(stored()).toBeNull()
    await advance(env, 31_000)
    expect(env.sockets.sockets).toHaveLength(1)
  })

  it('a close before the socket opens gives the same outcome, with no retry', async () => {
    seed()
    const env = setup('reload')
    await flushUntil(() => env.sockets.sockets.length === 1)
    const socket = env.sockets.last
    act(() => socket.serverClose(1006))
    expect(screen.getByText(REJOIN_FAILED)).toBeTruthy()
    expect(screen.getByRole('button', { name: '방 만들기' })).toBeTruthy()
    expect(stored()).toBeNull()
    expect(socket.sent).toEqual([])
    await advance(env, 31_000)
    expect(env.sockets.sockets).toHaveLength(1)
  })
})

describe('not a reload', () => {
  it.each(['navigate', 'back_forward'])('an entry with %s opens no socket, removes the entry and shows the start screen', async (type) => {
    seed()
    const env = setup(type)
    await expectPlainStart(env)
    expect(stored()).toBeNull()
    await advance(env, 31_000)
    expect(env.sockets.sockets).toHaveLength(0)
  })
})

describe('no entry', () => {
  it('reload with no entry opens no socket and shows the start screen', async () => {
    const env = setup('reload')
    await expectPlainStart(env)
    expect(stored()).toBeNull()
  })

  it('reload with malformed JSON opens no socket, shows the start screen and removes the entry', async () => {
    vi.spyOn(console, 'warn').mockImplementation(() => {})
    seed('{not json')
    const env = setup('reload')
    await expectPlainStart(env)
    expect(window.sessionStorage.getItem(STORAGE_KEY)).toBeNull()
  })

  it.each([
    ['a number token', { token: 5, code: 'ABCDE' }],
    ['a missing code', { token: 'T1' }],
    ['null', null],
  ])('reload with %s opens no socket and shows the start screen', async (_label, value) => {
    vi.spyOn(console, 'warn').mockImplementation(() => {})
    seed(JSON.stringify(value))
    const env = setup('reload')
    await expectPlainStart(env)
  })
})

describe('blocked storage', () => {
  it('when sessionStorage throws, the app still renders the start screen and opens no socket', async () => {
    vi.spyOn(console, 'warn').mockImplementation(() => {})
    const blocked = () => {
      throw new Error('storage blocked')
    }
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(blocked)
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(blocked)
    vi.spyOn(Storage.prototype, 'removeItem').mockImplementation(blocked)
    const env = setup('reload')
    await expectPlainStart(env)
  })
})

describe('token refresh', () => {
  it('after a rejoin a new token frame updates the entry; 나가기 clears it', async () => {
    seed()
    const env = setup('reload')
    const socket = await rejoinSocket(env)
    deliver(socket, joinedMsg)
    deliver(socket, snapshot('lobby', null))
    deliver(socket, roster(ROSTER))
    deliver(socket, { type: 'token', token: 'T2' })
    expect(stored()).toEqual({ token: 'T2', code: 'ABCDE' })
    expect(socket.sentOfType('token')).toEqual([])

    fireEvent.click(screen.getByRole('button', { name: '나가기' }))
    expect(socket.sentOfType('leave')).toEqual([{ type: 'leave' }])
    expect(stored()).toBeNull()
    expect(screen.getByRole('button', { name: '방 만들기' })).toBeTruthy()
    await advance(env, 31_000)
    expect(env.sockets.sockets).toHaveLength(1)
  })

  it('after a rejoin an unexpected close reconnects with the stored token, as in cycle 049', async () => {
    seed()
    const env = setup('reload')
    const socket = await rejoinSocket(env)
    deliver(socket, joinedMsg)
    deliver(socket, snapshot('lobby', null))
    act(() => socket.serverClose(1006))
    expect(screen.getByText(RECONNECTING)).toBeTruthy()
    expect(pageText()).toContain('방 코드: ABCDE')
    await advance(env, 500)
    expect(env.sockets.sockets).toHaveLength(2)
    const attempt = env.sockets.last
    act(() => attempt.open())
    await flushUntil(() => attempt.sent.length > 0)
    expect(attempt.sent).toEqual([{ type: 'rejoin', token: 'T1' }])
  })
})
