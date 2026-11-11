// Cycle 070 (F-011): the redesigned layout through the whole app. The room
// lobby and a room game show the eight seats (list 자리); the reveal card
// title shows. Also the chat entry shape of the
// reducer: { id, name, body, text }.
// Cycle 110 (D-124, declared): chat no longer shows in seat bubbles; the
// seat-bubble test is replaced by the log test (a .msg line in the chat).
// Timer rule: real timers and waitFor, as in App.multi.test.jsx.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, act, cleanup, within } from '@testing-library/react'
import App from './App.jsx'
import { initialState, reducer } from './game/state.js'
import { createSocketFactory, createAudioContextFactory, createFakeFetch } from './testing/fakes.js'
import { joinRoomAs } from './testing/lobby.js'

const T0 = 1_000_000

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
  return { sockets, audio, fetch, clock }
}

function deliver(socket, msg) {
  act(() => socket.receive(msg))
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

async function openSocket(env, count = 1) {
  await waitFor(() => expect(env.sockets.sockets.length).toBe(count))
  const socket = env.sockets.last
  act(() => socket.open())
  await waitFor(() => expect(socket.sent.length).toBeGreaterThan(0))
  return socket
}

// Joins ABCDE as Hana and receives joined (Hana (2)) and the lobby roster.
async function joinLobby(env) {
  joinRoomAs('Hana', 'ABCDE')
  const socket = await openSocket(env)
  expect(socket.sent[0]).toEqual({ type: 'join', code: 'ABCDE', nickname: 'Hana' })
  deliver(socket, { type: 'joined', code: 'ABCDE', name: 'Hana (2)' })
  deliver(socket, roster(PLAYERS_3))
  return socket
}

async function prepareAndReady(socket, index = 0) {
  deliver(socket, prepare(index))
  await waitFor(() => expect(socket.sentOfType('ready').filter((m) => m.index === index)).toHaveLength(1))
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

function seats() {
  return within(screen.getByRole('list', { name: '자리' })).getAllByRole('listitem')
}

function seatOf(name) {
  const seat = seats().find((li) => li.textContent.includes(name))
  expect(seat, `a seat for ${name}`).toBeTruthy()
  return seat
}

describe('room lobby', () => {
  it('shows the 자리 list with 8 seats', async () => {
    const env = setup()
    await joinLobby(env)
    expect(seats()).toHaveLength(8)
    expect(seatOf('Mina').textContent).toContain('Mina (방장)')
    expect(seatOf('Hana (2)').textContent).toContain('Hana (2) (나)')
  })
})

describe('room game', () => {
  it('shows the seats, the time bar and the chat input', async () => {
    const env = setup()
    await roomSongStarted(env)
    expect(seats()).toHaveLength(8)
    expect(screen.getByRole('progressbar', { name: '남은 시간' })).toBeTruthy()
    expect(screen.getByLabelText('정답 입력')).toBeTruthy()
    expect(screen.getByRole('button', { name: '보내기' })).toBeTruthy()
  })

  it("a chat frame from another player shows as a .msg line in the chat log, not in the seats", async () => {
    const env = setup()
    const socket = await roomSongStarted(env)
    deliver(socket, { type: 'chat', name: 'Mina', text: '이거 뭐였지' })
    const line = screen.getByText('Mina: 이거 뭐였지')
    expect(line.classList.contains('t')).toBe(true)
    expect(line.closest('.msgs .msg')).not.toBeNull()
    for (const seat of seats()) expect(seat.textContent).not.toContain('이거 뭐였지')
  })

  it('after reveal the reveal card title is visible', async () => {
    const env = setup()
    const socket = await roomSongStarted(env)
    deliver(socket, revealMsg({ winner: 'Mina' }))
    // Cycle 111 (declared): the title is also in the deck's .noimg and .cap,
    // so the reveal card's Korean title line is read directly.
    expect(document.querySelector('.reveal dl.titles dd.ko').textContent).toBe('진격의 거인')
  })
})

describe('chat entries in game/state', () => {
  it('a chat action gives { id, name, body, text }', () => {
    const next = reducer(initialState, { type: 'chat', name: '민수', text: 'x' })
    expect(next.chat[0]).toEqual({ id: 0, name: '민수', body: 'x', text: '민수: x' })
    const after = reducer(next, { type: 'chat', name: '철수', text: '안녕' })
    expect(after.chat[1]).toEqual({ id: 1, name: '철수', body: '안녕', text: '철수: 안녕' })
  })
})
