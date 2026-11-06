// Cycle 061 (F-008): the filter controls. 곡 종류 and 연도 시작 / 연도 끝
// next to 곡 수 for the host, the non-default fields in start (lobby), the
// reversed-range guard, the applied-conditions line (.settings) and when it
// is cleared, the filter error texts, and one App-level selection kept
// across games and 나가기. Cycle 078 (D-093): no solo form; one player plays
// alone in a room of their own.
// Real timers only (cycle 039 timer rule): waitFor drives every wait.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, act, cleanup } from '@testing-library/react'
import App from './App.jsx'
import { createSocketFactory, createAudioContextFactory, createFakeFetch } from './testing/fakes.js'

const T0 = 1_000_000
const HINT = '시작 연도가 끝 연도보다 늦습니다.'
const OLD_NOT_ENOUGH = '출제할 곡이 부족합니다.'
const BAD_FILTER = '조건이 올바르지 않습니다.'

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
const SETTINGS = { type: 'settings', types: ['OP'], yearFrom: 2010, yearTo: 2019 }
const LINE = '오프닝만 · 2010~2019'

// The .settings elements on the page.
function settingsLines() {
  return Array.from(document.querySelectorAll('.settings'))
}

function expectLine(text) {
  const lines = settingsLines()
  expect(lines).toHaveLength(1)
  expect(lines[0].textContent).toBe(text)
}

function expectNoLine() {
  expect(settingsLines()).toEqual([])
}

async function prepareAndReady(socket, index = 0, readyCount = 1) {
  deliver(socket, prepare(index))
  await waitFor(() =>
    expect(socket.sentOfType('ready').filter((m) => m.index === index)).toHaveLength(readyCount),
  )
}

// 방 만들기 as 하루, then joined and a roster that makes 하루 host.
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

// Alone in a new room: 게임 시작 with the current selects, then
// roster{playing}. Returns the socket, ready for settings and prepare.
async function aloneStarted(env, count = 1) {
  const socket = await hostLobby(env, count, ALONE)
  await clickStartGame(socket)
  deliver(socket, roster(ALONE, 'playing'))
  return socket
}

const gameOver = (score = 1) => ({
  type: 'gameOver',
  ranking: [
    { rank: 1, name: '하루', score },
    { rank: 2, name: 'Joon', score: 0 },
  ],
})

// A room game of one song: roster{playing}, optional settings, prepare,
// gameOver, roster{lobby}. Ends on the result screen.
async function playRoomGame(socket, { settings = null, readyCount = 1 } = {}) {
  deliver(socket, roster(HOST_PLAYERS, 'playing'))
  if (settings !== null) deliver(socket, settings)
  await prepareAndReady(socket, 0, readyCount)
  deliver(socket, gameOver())
  deliver(socket, roster(HOST_PLAYERS, 'lobby'))
}

function optionsOf(label) {
  return Array.from(screen.getByLabelText(label).options).map((o) => [o.value, o.textContent])
}

function expectFilterOptions() {
  expect(screen.getByLabelText('곡 종류').tagName).toBe('SELECT')
  expect(optionsOf('곡 종류')).toEqual([
    ['all', '전체'],
    ['OP', '오프닝만'],
    ['ED', '엔딩만'],
  ])
  expect(optionsOf('연도 시작')).toEqual([
    ['', '처음부터'],
    ...[1990, 2000, 2005, 2010, 2015, 2020, 2025].map((y) => [String(y), String(y)]),
  ])
  expect(optionsOf('연도 끝')).toEqual([
    ['', '지금까지'],
    ...[1999, 2004, 2009, 2014, 2019, 2024].map((y) => [String(y), String(y)]),
  ])
  expect(valueOf('곡 종류')).toBe('all')
  expect(valueOf('연도 시작')).toBe('')
  expect(valueOf('연도 끝')).toBe('')
}

describe('the controls', () => {
  it('the host controls show the same selects with the same defaults', async () => {
    const env = setup()
    await hostLobby(env)
    expectFilterOptions()
    expect(valueOf('곡 수')).toBe('10')
  })
})

describe('host start', () => {
  it('with the defaults 게임 시작 sends exactly start{songCount:10}', async () => {
    const env = setup()
    const socket = await hostLobby(env)
    expect(await clickStartGame(socket)).toEqual({ type: 'start', songCount: 10 })
  })

  it('엔딩만 and 2010 to 2019 add types, yearFrom and yearTo', async () => {
    const env = setup()
    const socket = await hostLobby(env)
    choose('곡 종류', 'ED')
    choose('연도 시작', '2010')
    choose('연도 끝', '2019')
    expect(await clickStartGame(socket)).toEqual({
      type: 'start',
      songCount: 10,
      types: ['ED'],
      yearFrom: 2010,
      yearTo: 2019,
    })
  })

  it('오프닝만 adds types:["OP"]', async () => {
    const env = setup()
    const socket = await hostLobby(env)
    choose('곡 종류', 'OP')
    expect(await clickStartGame(socket)).toEqual({ type: 'start', songCount: 10, types: ['OP'] })
  })

  it('the room create message carries no filter fields, also with filters chosen before it', async () => {
    const env = setup()
    const first = await hostLobby(env)
    expect(first.sent[0]).toEqual({ type: 'create', nickname: '하루' })
    choose('곡 종류', 'ED')
    choose('연도 시작', '2010')
    fireEvent.click(screen.getByRole('button', { name: '나가기' }))
    const second = await hostLobby(env, 2)
    expect(valueOf('곡 종류')).toBe('ED')
    expect(second.sent[0]).toEqual({ type: 'create', nickname: '하루' })
  })
})

describe('reversed range guard', () => {
  it('alone: 2010 to 1999 disables 게임 시작 and shows the hint; fixing either end enables it', async () => {
    const env = setup()
    await hostLobby(env, 1, ALONE)
    expect(screen.queryByText(HINT)).toBeNull()
    choose('연도 시작', '2010')
    choose('연도 끝', '1999')
    expect(screen.getByText(HINT)).toBeTruthy()
    expect(screen.getByRole('button', { name: '게임 시작' }).disabled).toBe(true)
    choose('연도 끝', '2019')
    expect(screen.queryByText(HINT)).toBeNull()
    expect(screen.getByRole('button', { name: '게임 시작' }).disabled).toBe(false)
    choose('연도 끝', '1999')
    expect(screen.getByRole('button', { name: '게임 시작' }).disabled).toBe(true)
    choose('연도 시작', '')
    expect(screen.queryByText(HINT)).toBeNull()
    expect(screen.getByRole('button', { name: '게임 시작' }).disabled).toBe(false)
  })

  it('host: 2010 to 1999 disables 게임 시작 and shows the hint; fixing the range enables it', async () => {
    const env = setup()
    const socket = await hostLobby(env)
    choose('연도 시작', '2010')
    choose('연도 끝', '1999')
    expect(screen.getByText(HINT)).toBeTruthy()
    expect(screen.getByRole('button', { name: '게임 시작' }).disabled).toBe(true)
    fireEvent.click(screen.getByRole('button', { name: '게임 시작' }))
    expect(socket.sentOfType('start')).toEqual([])
    choose('연도 끝', '2014')
    expect(screen.queryByText(HINT)).toBeNull()
    expect(screen.getByRole('button', { name: '게임 시작' }).disabled).toBe(false)
    expect(await clickStartGame(socket)).toEqual({ type: 'start', songCount: 10, yearFrom: 2010, yearTo: 2014 })
  })
})

describe('the applied-conditions line', () => {
  it('alone in a room: settings then prepare shows 오프닝만 · 2010~2019 in one .settings line', async () => {
    const env = setup()
    const socket = await aloneStarted(env)
    deliver(socket, SETTINGS)
    await prepareAndReady(socket, 0)
    expect(screen.getByText('1 / 5')).toBeTruthy()
    expectLine(LINE)
  })

  it('a game without settings shows no .settings line', async () => {
    const env = setup()
    const socket = await aloneStarted(env)
    await prepareAndReady(socket, 0)
    expect(screen.getByText('1 / 5')).toBeTruthy()
    expectNoLine()
  })

  it('room: gameOver clears it; a new game without settings, started on the result screen, has none', async () => {
    const env = setup()
    const socket = await hostLobby(env)
    await clickStartGame(socket)
    deliver(socket, roster(HOST_PLAYERS, 'playing'))
    deliver(socket, SETTINGS)
    await prepareAndReady(socket, 0)
    expectLine(LINE)
    deliver(socket, gameOver())
    expectNoLine()
    deliver(socket, roster(HOST_PLAYERS, 'lobby'))
    expect(screen.getByRole('button', { name: '대기실로' })).toBeTruthy()
    // Still on the result screen: the next game arrives without settings.
    deliver(socket, roster(HOST_PLAYERS, 'playing'))
    await prepareAndReady(socket, 0, 2)
    expect(screen.getByText('1 / 5')).toBeTruthy()
    expectNoLine()
  })

  it('room: after 대기실로, a new game without settings has no line', async () => {
    const env = setup()
    const socket = await hostLobby(env)
    await clickStartGame(socket)
    await playRoomGame(socket, { settings: SETTINGS })
    fireEvent.click(screen.getByRole('button', { name: '대기실로' }))
    expect(screen.getByRole('button', { name: '게임 시작' })).toBeTruthy()
    expectNoLine()
    await clickStartGame(socket, 2)
    deliver(socket, roster(HOST_PLAYERS, 'playing'))
    await prepareAndReady(socket, 0, 2)
    expect(screen.getByText('1 / 5')).toBeTruthy()
    expectNoLine()
  })

  it('room: a second game with its own settings shows the new line', async () => {
    const env = setup()
    const socket = await hostLobby(env)
    await clickStartGame(socket)
    await playRoomGame(socket, { settings: SETTINGS })
    deliver(socket, roster(HOST_PLAYERS, 'playing'))
    deliver(socket, { type: 'settings', types: ['ED'], yearFrom: 2020 })
    await prepareAndReady(socket, 0, 2)
    expectLine('엔딩만 · 2020~')
  })

  it('alone: after 나가기, a game in a new room without settings has no line', async () => {
    const env = setup()
    const first = await aloneStarted(env)
    deliver(first, SETTINGS)
    await prepareAndReady(first, 0)
    expectLine(LINE)
    deliver(first, { type: 'gameOver', ranking: [{ rank: 1, name: '하루', score: 1 }] })
    deliver(first, roster(ALONE, 'lobby'))
    fireEvent.click(screen.getByRole('button', { name: '대기실로' }))
    fireEvent.click(screen.getByRole('button', { name: '나가기' }))
    const second = await aloneStarted(env, 2)
    await prepareAndReady(second, 0)
    expect(screen.getByText('1 / 5')).toBeTruthy()
    expectNoLine()
  })

  it('late join: joined, a playing snapshot, settings, roster shows the line', async () => {
    const env = setup()
    setNickname('Hana')
    fireEvent.change(screen.getByLabelText('방 코드'), { target: { value: 'ABCDE' } })
    fireEvent.click(screen.getByRole('button', { name: '참가' }))
    const socket = await openSocket(env)
    const players = [player('Mina', { host: true }), player('Hana')]
    deliver(socket, { type: 'joined', code: 'ABCDE', name: 'Hana' })
    deliver(socket, { type: 'snapshot', phase: 'playing', players, song: null, scores: [] })
    deliver(socket, SETTINGS)
    deliver(socket, roster(players, 'playing'))
    expectLine(LINE)
  })
})

describe('filter errors', () => {
  it('alone: not-enough-songs with available:3 shows the count in the lobby', async () => {
    const env = setup()
    const socket = await hostLobby(env, 1, ALONE)
    choose('곡 종류', 'ED')
    await clickStartGame(socket)
    deliver(socket, { type: 'error', code: 'not-enough-songs', available: 3 })
    expect(screen.getByText('출제할 수 있는 곡이 3곡뿐입니다.')).toBeTruthy()
    expect(screen.queryByText(OLD_NOT_ENOUGH)).toBeNull()
    expect(screen.getByRole('button', { name: '게임 시작' })).toBeTruthy()
  })

  it('not-enough-songs without available keeps the old text', async () => {
    const env = setup()
    const socket = await hostLobby(env, 1, ALONE)
    await clickStartGame(socket)
    deliver(socket, { type: 'error', code: 'not-enough-songs' })
    expect(screen.getByText(OLD_NOT_ENOUGH)).toBeTruthy()
  })

  it('bad-filter shows its text', async () => {
    const env = setup()
    const socket = await hostLobby(env, 1, ALONE)
    await clickStartGame(socket)
    deliver(socket, { type: 'error', code: 'bad-filter' })
    expect(screen.getByText(BAD_FILTER)).toBeTruthy()
  })

  it('in the lobby, not-enough-songs with available and bad-filter show in the lobby', async () => {
    const env = setup()
    const socket = await hostLobby(env)
    choose('연도 시작', '2025')
    await clickStartGame(socket)
    deliver(socket, { type: 'error', code: 'not-enough-songs', available: 7 })
    expect(screen.getByText('출제할 수 있는 곡이 7곡뿐입니다.')).toBeTruthy()
    expect(screen.getByRole('button', { name: '게임 시작' })).toBeTruthy()
    deliver(socket, { type: 'error', code: 'bad-filter' })
    expect(screen.getByText(BAD_FILTER)).toBeTruthy()
    expect(socket.closed).toBe(false)
  })
})

describe('kept across games', () => {
  it('the host picks 20, 엔딩만 and 2000; after the game and 대기실로 the selects keep them and start sends the same', async () => {
    const env = setup()
    const socket = await hostLobby(env)
    choose('곡 수', '20')
    choose('곡 종류', 'ED')
    choose('연도 시작', '2000')
    const expected = { type: 'start', songCount: 20, types: ['ED'], yearFrom: 2000 }
    expect(await clickStartGame(socket)).toEqual(expected)
    await playRoomGame(socket)
    fireEvent.click(screen.getByRole('button', { name: '대기실로' }))
    expect(valueOf('곡 수')).toBe('20')
    expect(valueOf('곡 종류')).toBe('ED')
    expect(valueOf('연도 시작')).toBe('2000')
    expect(valueOf('연도 끝')).toBe('')
    expect(await clickStartGame(socket, 2)).toEqual(expected)
  })

  it('alone: selections survive a game, 나가기 and a new room, and are sent again', async () => {
    const env = setup()
    const first = await hostLobby(env, 1, ALONE)
    choose('곡 수', '15')
    choose('곡 종류', 'ED')
    choose('연도 시작', '2010')
    const expected = { type: 'start', songCount: 15, types: ['ED'], yearFrom: 2010 }
    expect(await clickStartGame(first)).toEqual(expected)
    deliver(first, roster(ALONE, 'playing'))
    await prepareAndReady(first, 0)
    deliver(first, { type: 'gameOver', ranking: [{ rank: 1, name: '하루', score: 1 }] })
    deliver(first, roster(ALONE, 'lobby'))
    fireEvent.click(screen.getByRole('button', { name: '대기실로' }))
    fireEvent.click(screen.getByRole('button', { name: '나가기' }))
    const second = await hostLobby(env, 2, ALONE)
    expect(valueOf('곡 수')).toBe('15')
    expect(valueOf('곡 종류')).toBe('ED')
    expect(valueOf('연도 시작')).toBe('2010')
    expect(await clickStartGame(second)).toEqual(expected)
  })

  it('one selection, kept through 나가기 and into the next room', async () => {
    const env = setup()
    const first = await hostLobby(env)
    choose('곡 수', '15')
    choose('곡 종류', 'OP')
    choose('연도 끝', '2009')
    fireEvent.click(screen.getByRole('button', { name: '나가기' }))
    expect(screen.getByRole('button', { name: '방 만들기' })).toBeTruthy()
    expect(first.sentOfType('start')).toEqual([])
    const socket = await hostLobby(env, 2)
    expect(valueOf('곡 수')).toBe('15')
    expect(valueOf('곡 종류')).toBe('OP')
    expect(valueOf('연도 끝')).toBe('2009')
    choose('연도 시작', '1990')
    expect(await clickStartGame(socket)).toEqual({
      type: 'start',
      songCount: 15,
      types: ['OP'],
      yearFrom: 1990,
      yearTo: 2009,
    })
    fireEvent.click(screen.getByRole('button', { name: '나가기' }))
    expect(screen.getByRole('button', { name: '방 만들기' })).toBeTruthy()
    await hostLobby(env, 3)
    expect(valueOf('곡 수')).toBe('15')
    expect(valueOf('곡 종류')).toBe('OP')
    expect(valueOf('연도 시작')).toBe('1990')
    expect(valueOf('연도 끝')).toBe('2009')
  })
})
