// Cycle 064 (F-009): the 난이도 select. Its options and default, its place
// between 곡 종류 and 연도 시작, tier in start (lobby), the room create
// message without it, 쉬움 / 어려움 on the applied-conditions line, and the
// choice kept across games (대기실로, and 나가기 with a new room). Cycle 078
// (D-093): no solo form; one player plays alone in a room of their own.
// Real timers only (cycle 039 timer rule): waitFor drives every wait.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, act, cleanup } from '@testing-library/react'
import App from './App.jsx'
import { createSocketFactory, createAudioContextFactory, createFakeFetch } from './testing/fakes.js'

const T0 = 1_000_000

beforeEach(() => {
  window.history.pushState({}, '', '/')
})

afterEach(() => {
  cleanup()
  window.history.pushState({}, '', '/')
  vi.restoreAllMocks()
})

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

function setNickname(nickname) {
  fireEvent.change(screen.getByLabelText('닉네임'), { target: { value: nickname } })
}

function choose(label, value) {
  fireEvent.change(screen.getByLabelText(label), { target: { value } })
}

function valueOf(label) {
  return screen.getByLabelText(label).value
}

// Waits for socket number `count`, opens it and waits for its first message.
async function openSocket(env, count = 1) {
  await waitFor(() => expect(env.sockets.sockets.length).toBe(count))
  const socket = env.sockets.last
  act(() => socket.open())
  await waitFor(() => expect(socket.sent.length).toBeGreaterThan(0))
  return socket
}

const player = (name, { host = false, score = 0, connected = true } = {}) => ({ name, score, host, connected })
const roster = (players, phase = 'lobby') => ({ type: 'roster', phase, players })
const HOST_PLAYERS = [player('하루', { host: true }), player('Joon')]
// A room of one: how a player plays alone (D-093).
const ALONE = [player('하루', { host: true })]

const prepare = (index = 0) => ({ type: 'prepare', index, total: 5, audioUrl: '/audio/abc' })

function settingsLines() {
  return Array.from(document.querySelectorAll('.settings'))
}

function expectLine(text) {
  const lines = settingsLines()
  expect(lines).toHaveLength(1)
  expect(lines[0].textContent).toBe(text)
}

async function prepareAndReady(socket, index = 0, readyCount = 1) {
  deliver(socket, prepare(index))
  await waitFor(() =>
    expect(socket.sentOfType('ready').filter((m) => m.index === index)).toHaveLength(readyCount),
  )
}

async function hostLobby(env, count = 1, players = HOST_PLAYERS) {
  setNickname('하루')
  fireEvent.click(screen.getByRole('button', { name: '방 만들기' }))
  const socket = await openSocket(env, count)
  deliver(socket, { type: 'joined', code: 'QWERT', name: '하루' })
  deliver(socket, roster(players))
  expect(screen.getByRole('button', { name: '게임 시작' })).toBeTruthy()
  return socket
}

async function clickStartGame(socket, total = 1) {
  fireEvent.click(screen.getByRole('button', { name: '게임 시작' }))
  await waitFor(() => expect(socket.sentOfType('start')).toHaveLength(total))
  return socket.sentOfType('start')[total - 1]
}

const gameOver = () => ({
  type: 'gameOver',
  ranking: [
    { rank: 1, name: '하루', score: 1 },
    { rank: 2, name: 'Joon', score: 0 },
  ],
})

function optionsOf(label) {
  return Array.from(screen.getByLabelText(label).options).map((o) => [o.value, o.textContent])
}

function expectTierSelect() {
  const select = screen.getByLabelText('난이도')
  expect(select.tagName).toBe('SELECT')
  expect(optionsOf('난이도')).toEqual([
    ['easy', '쉬움'],
    ['normal', '보통'],
    ['hard', '어려움'],
  ])
  expect(valueOf('난이도')).toBe('normal')
  const selected = Array.from(select.options).filter((o) => o.selected)
  expect(selected.map((o) => o.textContent)).toEqual(['보통'])
  // After 곡 종류 and before 연도 시작 in document order.
  const type = screen.getByLabelText('곡 종류')
  const from = screen.getByLabelText('연도 시작')
  expect(type.compareDocumentPosition(select) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  expect(select.compareDocumentPosition(from) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
}

describe('the 난이도 select', () => {
  it('the host controls offer the same options and default', async () => {
    const env = setup()
    await hostLobby(env)
    expectTierSelect()
  })
})

describe('host start', () => {
  it('with the defaults 게임 시작 sends exactly start{songCount:10}', async () => {
    const env = setup()
    const socket = await hostLobby(env)
    expect(await clickStartGame(socket)).toEqual({ type: 'start', songCount: 10 })
  })

  it('보통 chosen explicitly still sends no tier', async () => {
    const env = setup()
    const socket = await hostLobby(env)
    choose('난이도', 'hard')
    choose('난이도', 'normal')
    expect(await clickStartGame(socket)).toEqual({ type: 'start', songCount: 10 })
  })

  it('쉬움 adds tier:"easy"', async () => {
    const env = setup()
    const socket = await hostLobby(env)
    choose('난이도', 'easy')
    expect(await clickStartGame(socket)).toEqual({ type: 'start', songCount: 10, tier: 'easy' })
  })

  it('어려움 adds tier:"hard"', async () => {
    const env = setup()
    const socket = await hostLobby(env)
    choose('난이도', 'hard')
    expect(await clickStartGame(socket)).toEqual({ type: 'start', songCount: 10, tier: 'hard' })
  })

  it('the room create message carries no tier, also with 쉬움 chosen before it', async () => {
    const env = setup()
    const first = await hostLobby(env)
    expect(first.sent[0]).toEqual({ type: 'create', nickname: '하루' })
    choose('난이도', 'easy')
    fireEvent.click(screen.getByRole('button', { name: '나가기' }))
    const second = await hostLobby(env, 2)
    expect(valueOf('난이도')).toBe('easy')
    expect(second.sent[0]).toEqual({ type: 'create', nickname: '하루' })
  })
})

describe('the applied-conditions line', () => {
  it('alone in a room: settings{types:["OP"], tier:"easy"} shows 오프닝만 · 쉬움', async () => {
    const env = setup()
    const socket = await hostLobby(env, 1, ALONE)
    await clickStartGame(socket)
    deliver(socket, roster(ALONE, 'playing'))
    deliver(socket, { type: 'settings', types: ['OP'], tier: 'easy' })
    await prepareAndReady(socket, 0)
    expect(screen.getByText('1 / 5')).toBeTruthy()
    expectLine('오프닝만 · 쉬움')
  })

  it('room: settings{tier:"hard"} alone shows 어려움', async () => {
    const env = setup()
    const socket = await hostLobby(env)
    await clickStartGame(socket)
    deliver(socket, roster(HOST_PLAYERS, 'playing'))
    deliver(socket, { type: 'settings', tier: 'hard' })
    await prepareAndReady(socket, 0)
    expectLine('어려움')
  })
})

describe('kept across games', () => {
  it('the host picks 어려움; after a game and 대기실로 it is still selected and sent again', async () => {
    const env = setup()
    const socket = await hostLobby(env)
    choose('난이도', 'hard')
    const expected = { type: 'start', songCount: 10, tier: 'hard' }
    expect(await clickStartGame(socket)).toEqual(expected)
    deliver(socket, roster(HOST_PLAYERS, 'playing'))
    deliver(socket, { type: 'settings', tier: 'hard' })
    await prepareAndReady(socket, 0)
    deliver(socket, gameOver())
    deliver(socket, roster(HOST_PLAYERS, 'lobby'))
    fireEvent.click(screen.getByRole('button', { name: '대기실로' }))
    expect(screen.getByRole('button', { name: '게임 시작' })).toBeTruthy()
    expect(valueOf('난이도')).toBe('hard')
    expect(await clickStartGame(socket, 2)).toEqual(expected)
  })

  it('alone: 어려움 survives a game, 나가기 and a new room, whose 게임 시작 carries tier:"hard"', async () => {
    const env = setup()
    const first = await hostLobby(env, 1, ALONE)
    choose('난이도', 'hard')
    const expected = { type: 'start', songCount: 10, tier: 'hard' }
    expect(await clickStartGame(first)).toEqual(expected)
    deliver(first, roster(ALONE, 'playing'))
    await prepareAndReady(first, 0)
    deliver(first, { type: 'gameOver', ranking: [{ rank: 1, name: '하루', score: 1 }] })
    deliver(first, roster(ALONE, 'lobby'))
    fireEvent.click(screen.getByRole('button', { name: '대기실로' }))
    fireEvent.click(screen.getByRole('button', { name: '나가기' }))
    const second = await hostLobby(env, 2, ALONE)
    expect(second.sent[0]).toEqual({ type: 'create', nickname: '하루' })
    expect(valueOf('난이도')).toBe('hard')
    expect(await clickStartGame(second)).toEqual(expected)
  })
})
