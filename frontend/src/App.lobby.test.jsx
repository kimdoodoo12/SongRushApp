// Cycle 041 (F-003): the room screens. Create and join, the lobby, a game in
// a room, a second game on the same socket, leaving and room errors. Cycle
// 078 (D-093): the start screen has no solo controls; one player plays alone
// by creating a room and starting it.
// Cycle 114 (D-124, declared): the lobby is the mockup's #lobbyView, read
// through src/testing/lobby.js: the room code from the ticket's .tk-code, the
// roster rows from the seats' name lines (the lobby seats are li.seat now),
// 곡 수 from the stepper (5-30, step 1) and the start button .start-row .go.
// The behaviour checked is kept.
// Real timers only (cycle 039 timer rule): waitFor drives every wait.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, act, cleanup, within } from '@testing-library/react'
import App from './App.jsx'
import { createSocketFactory, createAudioContextFactory, createFakeFetch } from './testing/fakes.js'
import { confirmNickname, createRoomAs, joinRoomAs, lobbyCode, nicknameDialog, seatNames, setSongCount, songCount, songCountOutput, startButton, startGame, stepperButtons } from './testing/lobby.js'

const DISCONNECTED = '서버와 연결이 끊어졌습니다.'
const WAITING = '방장이 게임을 시작하기를 기다리는 중...'
const T0 = 1_000_000

beforeEach(() => {
  window.history.pushState({}, '', '/')
})

afterEach(() => {
  cleanup()
  window.history.pushState({}, '', '/')
  vi.restoreAllMocks()
})

// Renders App with fakes. clock.t is what now() returns.
function setup({ canPlayOpus = true } = {}) {
  const sockets = createSocketFactory()
  const audio = createAudioContextFactory()
  const fetch = createFakeFetch()
  const clock = { t: T0 }
  render(
    <App
      canPlayOpus={canPlayOpus}
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

function setCode(code) {
  fireEvent.change(screen.getByLabelText('방 코드'), { target: { value: code } })
}

// Waits for the socket the click opened, opens it and waits for one message.
async function openSocket(env, count = 1) {
  await waitFor(() => expect(env.sockets.sockets.length).toBe(count))
  const socket = env.sockets.last
  act(() => socket.open())
  await waitFor(() => expect(socket.sent.length).toBeGreaterThan(0))
  return socket
}

// Clicks 참가 with the given nickname and code; returns the opened socket.
async function clickJoin(env, { nickname = 'Hana', code = 'ABCDE' } = {}) {
  joinRoomAs(nickname, code)
  return openSocket(env)
}

const player = (name, { host = false, score = 0, connected = true } = {}) => ({ name, score, host, connected })

const roster = (players, phase = 'lobby') => ({ type: 'roster', phase, players })

// Mina hosts; the player is Hana (2), second in server order.
const ROSTER_3 = roster([player('Mina', { host: true }), player('Hana (2)'), player('Joon')])

// Joins ABCDE as Hana and receives joined{name:"Hana (2)"} and ROSTER_3.
async function joinLobby(env) {
  const socket = await clickJoin(env)
  deliver(socket, { type: 'joined', code: 'ABCDE', name: 'Hana (2)' })
  deliver(socket, ROSTER_3)
  return socket
}

function pageText() {
  return document.body.textContent
}

const prepare = (index = 0) => ({ type: 'prepare', index, total: 5, audioUrl: '/audio/abc' })

function startMsg({ index = 0, startDelta = 0, length = 90_000 } = {}) {
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
  artists: ['Revo', 'Linked Horizon'],
  themeType: 'OP',
  themeSlug: 'OP1',
  winner: null,
  ...overrides,
})

// Delivers prepare{index} and waits until ready{index} has been sent
// `readyCount` times in total.
async function prepareAndReady(socket, index = 0, readyCount = 1) {
  deliver(socket, prepare(index))
  await waitFor(() =>
    expect(socket.sentOfType('ready').filter((m) => m.index === index)).toHaveLength(readyCount),
  )
}

// Plays one room game of one song from the lobby: Mina scores twice, the
// player once. Ends on the result screen with roster{phase:"lobby"}.
async function playRoomGame(socket, readyCount = 1) {
  deliver(socket, roster(ROSTER_3.players, 'playing'))
  await prepareAndReady(socket, 0, readyCount)
  deliver(socket, startMsg())
  deliver(socket, { type: 'chat', name: 'Mina', text: '원피스' })
  deliver(socket, { type: 'correct', index: 0, name: 'Hana (2)' })
  deliver(socket, revealMsg({ winner: 'Hana (2)' }))
  deliver(socket, {
    type: 'scores',
    scores: [
      { name: 'Mina', score: 2 },
      { name: 'Hana (2)', score: 1 },
    ],
  })
  deliver(socket, {
    type: 'gameOver',
    ranking: [
      { rank: 1, name: 'Mina', score: 2 },
      { rank: 2, name: 'Hana (2)', score: 1 },
      { rank: 3, name: 'Joon', score: 0 },
    ],
  })
  deliver(socket, roster(ROSTER_3.players, 'lobby'))
}

function expectStartScreen() {
  expect(screen.getByRole('button', { name: '방 만들기' })).toBeTruthy()
  expect(screen.getByRole('button', { name: '참가' })).toBeTruthy()
  expect(screen.getByLabelText('방 코드')).toBeTruthy()
}

function expectNoLobby() {
  expect(startButton()).toBeNull()
  expect(screen.queryByRole('button', { name: '나가기' })).toBeNull()
  expect(screen.queryByText(WAITING)).toBeNull()
  expect(lobbyCode()).toBeNull()
}

describe('start screen room controls', () => {
  it('shows 방 만들기, the 방 코드 input and 참가', () => {
    setup()
    expectStartScreen()
    expect(screen.getByLabelText('방 코드').tagName).toBe('INPUT')
    expect(screen.getByRole('button', { name: '방 만들기' }).disabled).toBe(false)
  })

  // Cycle 119 (D-127, declared): 닉네임 is no longer on the start screen.
  it('has no solo controls: no 시작 button and no 곡 수 or filter select; 분위기, 방 만들기, 방 코드 and 참가 remain, 닉네임 is gone', () => {
    setup()
    expect(screen.queryByRole('button', { name: '시작' })).toBeNull()
    expect(screen.queryByLabelText('곡 수')).toBeNull()
    expect(songCountOutput()).toBeNull()
    for (const label of ['곡 종류', '난이도', '연도 시작', '연도 끝']) {
      expect(screen.queryByLabelText(label), label).toBeNull()
    }
    expect(screen.queryByText('혼자 하기')).toBeNull()
    expect(screen.queryAllByRole('combobox')).toEqual([])
    expect(screen.queryByLabelText('닉네임')).toBeNull()
    expect(screen.getByRole('group', { name: '분위기' })).toBeTruthy()
    expectStartScreen()
  })

  it('canPlayOpus={false} disables 방 만들기 and 참가', () => {
    setup({ canPlayOpus: false })
    setCode('ABCDE')
    expect(screen.getByRole('button', { name: '방 만들기' }).disabled).toBe(true)
    expect(screen.getByRole('button', { name: '참가' }).disabled).toBe(true)
  })
})

describe('create', () => {
  it('방 만들기 calls resume() in the click and sends create with exactly type and nickname', async () => {
    const env = setup()
    createRoomAs('하루')
    // Synchronously inside the click handler (audio unlock).
    expect(env.audio.factory).toHaveBeenCalledTimes(1)
    expect(env.audio.last.resume).toHaveBeenCalledTimes(1)
    await waitFor(() => expect(env.sockets.sockets.length).toBe(1))
    expect(env.sockets.factory).toHaveBeenCalledWith(`ws://${window.location.host}/ws`)
    const socket = env.sockets.last
    expect(socket.sent).toEqual([])
    act(() => socket.open())
    await waitFor(() => expect(socket.sent).toHaveLength(1))
    expect(socket.sent[0]).toEqual({ type: 'create', nickname: '하루' })
    expect(Object.keys(socket.sent[0]).sort()).toEqual(['nickname', 'type'])
  })

  it('joined after create shows the lobby with the creator as host', async () => {
    const env = setup()
    createRoomAs('하루')
    const socket = await openSocket(env)
    deliver(socket, { type: 'joined', code: 'QWERT', name: '하루' })
    deliver(socket, roster([player('하루', { host: true })]))
    expect(lobbyCode()).toBe('QWERT')
    expect(seatNames()).toEqual(['하루 (방장) (나)'])
    expect(startButton()).not.toBeNull()
    expect(screen.queryByRole('button', { name: '시작' })).toBeNull()
  })
})

describe('join', () => {
  it('upper-cases the typed code and 참가 sends join{code, nickname}', async () => {
    const env = setup()
    setCode('abcde')
    expect(screen.getByLabelText('방 코드').value).toBe('ABCDE')
    joinRoomAs('Hana', 'abcde')
    // The join gesture also unlocks audio synchronously.
    expect(env.audio.factory).toHaveBeenCalledTimes(1)
    expect(env.audio.last.resume).toHaveBeenCalledTimes(1)
    const socket = await openSocket(env)
    expect(socket.sent).toEqual([{ type: 'join', code: 'ABCDE', nickname: 'Hana' }])
  })

  it('참가 is disabled while the code is empty', () => {
    setup()
    expect(screen.getByLabelText('방 코드').value).toBe('')
    expect(screen.getByRole('button', { name: '참가' }).disabled).toBe(true)
    setCode('abcde')
    expect(screen.getByRole('button', { name: '참가' }).disabled).toBe(false)
    setCode('')
    expect(screen.getByRole('button', { name: '참가' }).disabled).toBe(true)
  })

  it('a /r/<code> path prefills the code field upper-cased', () => {
    window.history.pushState({}, '', '/r/abcde')
    setup()
    expect(screen.getByLabelText('방 코드').value).toBe('ABCDE')
    expect(screen.getByRole('button', { name: '참가' }).disabled).toBe(false)
  })

  // Cycle 119 (D-127, declared): Enter in the code field is 참가, so it
  // opens the join dialog; 확인 there sends the join.
  it('Enter in the code field opens the join dialog, whose 확인 sends join, never a create', async () => {
    const env = setup()
    setCode('abcde')
    const input = screen.getByLabelText('방 코드')
    const notPrevented = fireEvent.keyDown(input, { key: 'Enter', code: 'Enter', keyCode: 13, charCode: 13 })
    // A browser submits the enclosing form on Enter unless keydown was
    // cancelled; jsdom does not, so the test does it the same way.
    if (notPrevented && input.form !== null) fireEvent.submit(input.form)
    expect(env.sockets.factory).not.toHaveBeenCalled()
    confirmNickname('Hana')
    const socket = await openSocket(env)
    expect(socket.sent).toEqual([{ type: 'join', code: 'ABCDE', nickname: 'Hana' }])
    expect(socket.sentOfType('create')).toEqual([])
    expect(env.sockets.sockets).toHaveLength(1)
  })
})

describe('lobby screen', () => {
  it('shows the code, the share link, the roster rows and, for a non-host, the waiting text', async () => {
    const env = setup()
    await joinLobby(env)
    expect(lobbyCode()).toBe('ABCDE')
    expect(pageText()).toContain(`${window.location.origin}/r/ABCDE`)
    expect(seatNames()).toEqual(['Mina (방장)', 'Hana (2) (나)', 'Joon'])
    expect(screen.getByText(WAITING)).toBeTruthy()
    expect(startButton()).toBeNull()
    expect(screen.getByRole('button', { name: '나가기' })).toBeTruthy()
    // The start screen is gone.
    expect(screen.queryByRole('button', { name: '시작' })).toBeNull()
    expect(screen.queryByRole('button', { name: '참가' })).toBeNull()
  })

  it('복사 copies the share link with navigator.clipboard.writeText', async () => {
    const writeText = vi.fn(() => Promise.resolve())
    Object.defineProperty(window.navigator, 'clipboard', { value: { writeText }, configurable: true })
    try {
      const env = setup()
      await joinLobby(env)
      fireEvent.click(screen.getByRole('button', { name: '복사' }))
      await waitFor(() => expect(writeText).toHaveBeenCalledWith(`${window.location.origin}/r/ABCDE`))
    } finally {
      delete window.navigator.clipboard
    }
  })

  it('a roster that makes the player host shows 곡 수 (default 10, 5-30) and the start button, which sends start{songCount:10}', async () => {
    const env = setup()
    const socket = await joinLobby(env)
    deliver(socket, roster([player('Hana (2)', { host: true }), player('Joon')]))
    expect(seatNames()).toEqual(['Hana (2) (방장) (나)', 'Joon'])
    expect(screen.queryByText(WAITING)).toBeNull()
    expect(screen.getByLabelText('곡 수')).toBe(songCountOutput())
    expect(songCount()).toBe(10)
    setSongCount(5)
    expect(stepperButtons()[0].disabled).toBe(true)
    setSongCount(30)
    expect(stepperButtons()[1].disabled).toBe(true)
    setSongCount(10)
    startGame()
    await waitFor(() => expect(socket.sentOfType('start')).toHaveLength(1))
    expect(socket.sentOfType('start')[0]).toEqual({ type: 'start', songCount: 10 })
    expect(typeof socket.sentOfType('start')[0].songCount).toBe('number')
  })

  it('changing 곡 수 to 15 sends start{songCount:15}', async () => {
    const env = setup()
    const socket = await joinLobby(env)
    deliver(socket, roster([player('Hana (2)', { host: true }), player('Joon')]))
    setSongCount(15)
    startGame()
    await waitFor(() => expect(socket.sentOfType('start')).toHaveLength(1))
    expect(socket.sentOfType('start')[0]).toEqual({ type: 'start', songCount: 15 })
  })

  it('a later roster that moves the host away shows the waiting text again', async () => {
    const env = setup()
    const socket = await joinLobby(env)
    deliver(socket, roster([player('Hana (2)', { host: true }), player('Joon')]))
    expect(startButton()).not.toBeNull()
    deliver(socket, roster([player('Joon', { host: true }), player('Hana (2)')]))
    expect(seatNames()).toEqual(['Joon (방장)', 'Hana (2) (나)'])
    expect(startButton()).toBeNull()
    expect(screen.getByText(WAITING)).toBeTruthy()
  })
})

describe('game in a room', () => {
  it('prepare sends ready; scores and gameOver use the own row; roster{lobby} adds 대기실로 and no 다시 하기', async () => {
    const env = setup()
    const socket = await joinLobby(env)
    deliver(socket, roster(ROSTER_3.players, 'playing'))
    await prepareAndReady(socket, 0)
    expect(screen.getByText('1 / 5')).toBeTruthy()
    expectNoLobby()
    deliver(socket, startMsg())
    await waitFor(() => expect(env.audio.last.startedSources()).toHaveLength(1))
    deliver(socket, { type: 'chat', name: 'Mina', text: '원피스' })
    expect(screen.getByText('Mina: 원피스')).toBeTruthy()
    deliver(socket, { type: 'correct', index: 0, name: 'Hana (2)' })
    expect(screen.getByText('Hana (2) 정답!')).toBeTruthy()
    deliver(socket, revealMsg({ winner: 'Hana (2)' }))
    expect(screen.getByText('정답: Hana (2)')).toBeTruthy()
    deliver(socket, {
      type: 'scores',
      scores: [
        { name: 'Mina', score: 2 },
        { name: 'Hana (2)', score: 1 },
      ],
    })
    expect(screen.getByText('점수: 1')).toBeTruthy()
    expect(screen.queryByText('점수: 2')).toBeNull()
    deliver(socket, {
      type: 'gameOver',
      ranking: [
        { rank: 1, name: 'Mina', score: 2 },
        { rank: 2, name: 'Hana (2)', score: 1 },
      ],
    })
    expect(screen.getByText('최종 점수: 1점')).toBeTruthy()
    expect(screen.queryByText('최종 점수: 2점')).toBeNull()
    expect(screen.queryByRole('button', { name: '다시 하기' })).toBeNull()

    deliver(socket, roster(ROSTER_3.players, 'lobby'))
    // The result stays visible, with 대기실로 and no 다시 하기.
    expect(screen.getByText('최종 점수: 1점')).toBeTruthy()
    expect(screen.getByRole('button', { name: '대기실로' })).toBeTruthy()
    expect(screen.queryByRole('button', { name: '다시 하기' })).toBeNull()
    expect(socket.closed).toBe(false)

    fireEvent.click(screen.getByRole('button', { name: '대기실로' }))
    expect(lobbyCode()).toBe('ABCDE')
    expect(seatNames()).toEqual(['Mina (방장)', 'Hana (2) (나)', 'Joon'])
    expect(screen.getByText(WAITING)).toBeTruthy()
    expect(screen.queryByRole('button', { name: '대기실로' })).toBeNull()
    expect(socket.closed).toBe(false)
    expect(env.sockets.sockets).toHaveLength(1)
  })
})

describe('second game on the same socket', () => {
  async function expectFreshSecondGame(env, socket) {
    await prepareAndReady(socket, 0, 2)
    expect(env.sockets.sockets).toHaveLength(1)
    expect(screen.getByText('1 / 5')).toBeTruthy()
    expect(screen.getByLabelText('정답 입력')).toBeTruthy()
    expectNoLobby()
    // Nothing from the first game is left.
    expect(screen.queryByText('점수: 1')).toBeNull()
    expect(screen.queryByText('최종 점수: 1점')).toBeNull()
    expect(screen.queryByText('Mina: 원피스')).toBeNull()
    expect(screen.queryByText('Hana (2) 정답!')).toBeNull()
    expect(screen.queryByText('정답: Hana (2)')).toBeNull()
    expect(screen.queryByRole('button', { name: '대기실로' })).toBeNull()
    // The game is no longer over: an unexpected close is reported again.
    act(() => socket.serverClose())
    expect(screen.getByText(DISCONNECTED)).toBeTruthy()
    expect(screen.getByRole('button', { name: '처음으로' })).toBeTruthy()
  }

  it('prepare{index:0} while on the lobby screen starts a fresh game', async () => {
    const env = setup()
    const socket = await joinLobby(env)
    await playRoomGame(socket)
    fireEvent.click(screen.getByRole('button', { name: '대기실로' }))
    expect(screen.getByText(WAITING)).toBeTruthy()
    deliver(socket, roster(ROSTER_3.players, 'playing'))
    await expectFreshSecondGame(env, socket)
  })

  it('prepare{index:0} while still on the result screen starts a fresh game', async () => {
    const env = setup()
    const socket = await joinLobby(env)
    await playRoomGame(socket)
    expect(screen.getByText('최종 점수: 1점')).toBeTruthy()
    expect(screen.getByRole('button', { name: '대기실로' })).toBeTruthy()
    deliver(socket, roster(ROSTER_3.players, 'playing'))
    await expectFreshSecondGame(env, socket)
  })
})

describe('leave', () => {
  it('나가기 sends leave, closes the socket, returns to the start screen and ignores its later close', async () => {
    const env = setup()
    const socket = await joinLobby(env)
    fireEvent.click(screen.getByRole('button', { name: '나가기' }))
    expect(socket.sentOfType('leave')).toEqual([{ type: 'leave' }])
    expect(socket.closed).toBe(true)
    expectStartScreen()
    act(() => socket.serverClose())
    expect(screen.queryByText(DISCONNECTED)).toBeNull()
    expectStartScreen()
  })
})

describe('room errors', () => {
  it.each([
    ['room-not-found', '방을 찾을 수 없습니다.'],
    ['room-full', '방이 가득 찼습니다 (최대 8명).'],
  ])('error{code:"%s"} after join shows its text in the open nickname dialog and closes the socket', async (code, text) => {
    // Cycle 119 (D-127, declared): the dialog stays open over the start
    // screen and shows the error as p.warn.
    const env = setup()
    const socket = await clickJoin(env)
    deliver(socket, { type: 'error', code })
    const warn = within(nicknameDialog()).getByText(text)
    expect(warn.tagName).toBe('P')
    expect(warn.classList.contains('warn')).toBe(true)
    expectStartScreen()
    expect(socket.closed).toBe(true)
    act(() => socket.serverClose())
    expect(screen.queryByText(DISCONNECTED)).toBeNull()
  })

  it('not-host in the lobby shows its text, keeps the lobby screen and the socket', async () => {
    const env = setup()
    const socket = await joinLobby(env)
    deliver(socket, { type: 'error', code: 'not-host' })
    expect(screen.getByText('방장만 시작할 수 있습니다.')).toBeTruthy()
    expect(seatNames()).toEqual(['Mina (방장)', 'Hana (2) (나)', 'Joon'])
    expect(screen.getByText(WAITING)).toBeTruthy()
    expect(screen.queryByRole('button', { name: '시작' })).toBeNull()
    expect(socket.closed).toBe(false)
  })

  it('room-closed in the lobby returns to the start screen with 방이 닫혔습니다.', async () => {
    const env = setup()
    const socket = await joinLobby(env)
    deliver(socket, { type: 'error', code: 'room-closed' })
    expect(screen.getByText('방이 닫혔습니다.')).toBeTruthy()
    expectStartScreen()
    expect(socket.closed).toBe(true)
    act(() => socket.serverClose())
    expect(screen.queryByText(DISCONNECTED)).toBeNull()
  })

  it('an unexpected close in the lobby shows the disconnect message and 처음으로', async () => {
    const env = setup()
    const socket = await joinLobby(env)
    act(() => socket.serverClose())
    expect(screen.getByText(DISCONNECTED)).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: '처음으로' }))
    expectStartScreen()
  })
})
