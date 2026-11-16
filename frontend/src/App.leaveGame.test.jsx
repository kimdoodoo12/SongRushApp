// Cycle 140 (F-041, D-138): leaving during a game. Spec
// docs/cycles/140-leave-game.md, acceptance criteria 1 to 5.
// - During a game the header's .hd-right has a 나가기 .btn, for players and
//   watchers. A click opens a modal dialog (게임을 나갈까요?, 나가기 and 취소)
//   and sends nothing.
// - 취소 or Escape closes it, sends nothing and gives focus back to 나가기.
// - 나가기 in the dialog sends leave and shows the start screen.
// - The dialog is translated in en and ja.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, act, cleanup, within } from '@testing-library/react'
import App from './App.jsx'
import en from './i18n/en.json'
import ja from './i18n/ja.json'
import { createSocketFactory, createAudioContextFactory, createFakeFetch } from './testing/fakes.js'
import * as lobby from './testing/lobby.js'

const T0 = 1_000_000
const ASK = '게임을 나갈까요?'

beforeEach(() => {
  window.history.pushState({}, '', '/')
  window.localStorage.clear()
  window.sessionStorage.clear()
})

afterEach(() => {
  // Back to Korean, so the shared i18n instance does not carry a language
  // into the next test.
  const group = screen.queryByRole('group', { name: 'Language' })
  if (group !== null) {
    const korean = within(group).queryByRole('button', { name: '한국어' })
    if (korean !== null) fireEvent.click(korean)
  }
  cleanup()
  vi.restoreAllMocks()
  window.history.pushState({}, '', '/')
  window.localStorage.clear()
  window.sessionStorage.clear()
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

const player = (name, { host = false } = {}) => ({ name, score: 0, host, connected: true })
const ALONE = [player('하루', { host: true })]
const roster = (phase, players = ALONE) => ({ type: 'roster', phase, players })

// Alone in a room, 게임 시작 sent, roster{playing} and prepare{0} received,
// ready{0} sent: the game screen.
async function playing(env) {
  lobby.createRoomAs('하루')
  await waitFor(() => expect(env.sockets.sockets.length).toBe(1))
  const socket = env.sockets.last
  act(() => socket.open())
  await waitFor(() => expect(socket.sentOfType('create')).toHaveLength(1))
  deliver(socket, { type: 'joined', code: 'QWERT', name: '하루' })
  deliver(socket, roster('lobby'))
  lobby.setSongCount(5)
  lobby.startGame()
  await waitFor(() => expect(socket.sentOfType('start')).toHaveLength(1))
  deliver(socket, roster('playing'))
  deliver(socket, { type: 'prepare', index: 0, total: 5, audioUrl: '/audio/abc' })
  await waitFor(() => expect(socket.sentOfType('ready')).toEqual([{ type: 'ready', index: 0 }]))
  expect(screen.getByText('1 / 5')).toBeTruthy()
  return socket
}

// Joins ABCDE as 하나 while a song plays: a watcher until the next song.
async function watching(env) {
  const players = [player('Mina', { host: true }), player('하나')]
  lobby.joinRoomAs('하나', 'ABCDE')
  await waitFor(() => expect(env.sockets.sockets.length).toBe(1))
  const socket = env.sockets.last
  act(() => socket.open())
  await waitFor(() => expect(socket.sentOfType('join')).toHaveLength(1))
  deliver(socket, { type: 'joined', code: 'ABCDE', name: '하나' })
  const startAt = T0 - 10_000
  deliver(socket, {
    type: 'snapshot',
    phase: 'playing',
    players,
    song: { index: 2, total: 5, audioUrl: '/audio/watch', startAt, deadlineAt: startAt + 70_000 },
    scores: [{ name: 'Mina', score: 1 }],
  })
  deliver(socket, roster('playing', players))
  await waitFor(() => expect(screen.getByText('이번 곡은 구경 중입니다. 다음 곡부터 참가합니다.')).toBeTruthy())
  return socket
}

function leaveButton(name = '나가기') {
  return within(document.querySelector('header .hd-right')).getByRole('button', { name })
}

function chooseLanguage(label) {
  const group = screen.getByRole('group', { name: 'Language' })
  fireEvent.click(within(group).getByRole('button', { name: label }))
}

// The socket's frames apart from the clock sync.
function sentBesidesPing(socket) {
  return socket.sent.filter((m) => m.type !== 'ping')
}

describe('leaving during a game', () => {
  it('(1) the header has 나가기; a click opens a modal dialog with 나가기 and 취소 and sends nothing', async () => {
    const env = setup()
    const socket = await playing(env)
    const button = leaveButton()
    expect(button.classList.contains('btn')).toBe(true)
    expect(screen.queryByRole('dialog')).toBeNull()
    const before = sentBesidesPing(socket).length
    fireEvent.click(button)
    const dialog = screen.getByRole('dialog', { name: ASK })
    expect(dialog.getAttribute('aria-modal')).toBe('true')
    expect(within(dialog).getByRole('button', { name: '나가기' })).toBeTruthy()
    expect(within(dialog).getByRole('button', { name: '취소' })).toBeTruthy()
    expect(dialog.contains(document.activeElement)).toBe(true)
    expect(sentBesidesPing(socket)).toHaveLength(before)
    expect(socket.sentOfType('leave')).toEqual([])
    expect(socket.closed).toBe(false)
  })

  it('(2) 취소 closes the dialog, sends nothing, keeps the game and focuses 나가기', async () => {
    const env = setup()
    const socket = await playing(env)
    const before = sentBesidesPing(socket).length
    fireEvent.click(leaveButton())
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: '취소' }))
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(sentBesidesPing(socket)).toHaveLength(before)
    expect(socket.closed).toBe(false)
    expect(screen.getByText('1 / 5')).toBeTruthy()
    expect(document.activeElement).toBe(leaveButton())
  })

  it('(2) Escape closes the dialog, sends nothing, keeps the game and focuses 나가기', async () => {
    const env = setup()
    const socket = await playing(env)
    const before = sentBesidesPing(socket).length
    fireEvent.click(leaveButton())
    fireEvent.keyDown(document.activeElement, { key: 'Escape' })
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(sentBesidesPing(socket)).toHaveLength(before)
    expect(socket.closed).toBe(false)
    expect(screen.getByText('1 / 5')).toBeTruthy()
    expect(document.activeElement).toBe(leaveButton())
  })

  it('(3) 나가기 in the dialog sends leave and shows the start screen', async () => {
    const env = setup()
    const socket = await playing(env)
    fireEvent.click(leaveButton())
    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: '나가기' }))
    expect(socket.sentOfType('leave')).toHaveLength(1)
    expect(socket.closed).toBe(true)
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(screen.getByRole('button', { name: '방 만들기' })).toBeTruthy()
    expect(screen.queryByText('1 / 5')).toBeNull()
  })

  it('(4) en and ja: the button and the dialog are translated', async () => {
    const env = setup()
    await playing(env)
    for (const [label, dict] of [
      ['English', en],
      ['日本語', ja],
    ]) {
      chooseLanguage(label)
      fireEvent.click(leaveButton(dict.game.leave))
      const dialog = screen.getByRole('dialog', { name: dict.game.leaveAsk })
      expect(within(dialog).getByRole('button', { name: dict.game.leaveConfirm })).toBeTruthy()
      expect(within(dialog).getByRole('button', { name: dict.game.leaveCancel })).toBeTruthy()
      expect(dialog.textContent).not.toMatch(/[가-힣]/)
      fireEvent.click(within(dialog).getByRole('button', { name: dict.game.leaveCancel }))
      expect(screen.queryByRole('dialog')).toBeNull()
    }
  })

  it('(5) a watcher has 나가기 too, and leaves the same way', async () => {
    const env = setup()
    const socket = await watching(env)
    fireEvent.click(leaveButton())
    const dialog = screen.getByRole('dialog', { name: ASK })
    expect(socket.sentOfType('leave')).toEqual([])
    fireEvent.click(within(dialog).getByRole('button', { name: '나가기' }))
    expect(socket.sentOfType('leave')).toHaveLength(1)
    expect(screen.getByRole('button', { name: '방 만들기' })).toBeTruthy()
  })

  it('the game-over screen has no header 나가기, only 대기실로', async () => {
    const env = setup()
    const socket = await playing(env)
    deliver(socket, { type: 'gameOver', ranking: [{ rank: 1, name: '하루', score: 0 }] })
    deliver(socket, roster('lobby'))
    expect(within(document.querySelector('header .hd-right')).queryByRole('button', { name: '나가기' })).toBeNull()
    expect(screen.getByRole('button', { name: '대기실로' })).toBeTruthy()
  })
})
