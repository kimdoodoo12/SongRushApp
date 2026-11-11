// Cycle 117 addendum (D-125): the start screen has no chat, so its .main
// carries the class solo (start.css drops the chat column and centres the
// card); the lobby and the game keep the mockup layout, without solo
// (docs/cycles/117-wide-center.md, "Addendum: the start screen").
// Timer rule (cycle 039): real timers and waitFor.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, act, cleanup } from '@testing-library/react'
import App from './App.jsx'
import { createSocketFactory, createAudioContextFactory, createFakeFetch } from './testing/fakes.js'
import { createRoomAs, joinRoomAs, startGame } from './testing/lobby.js'

const T0 = 1_000_000

beforeEach(() => {
  window.history.pushState({}, '', '/')
  window.localStorage.clear()
  window.sessionStorage.clear()
  document.body.classList.remove('lobby-on')
  vi.spyOn(window, 'scrollTo').mockImplementation(() => {})
})

afterEach(() => {
  cleanup()
  window.history.pushState({}, '', '/')
  window.localStorage.clear()
  window.sessionStorage.clear()
  vi.restoreAllMocks()
})

function setup() {
  const sockets = createSocketFactory()
  render(
    <App
      canPlayOpus={true}
      socketFactory={sockets.factory}
      audioContextFactory={createAudioContextFactory().factory}
      fetchFn={createFakeFetch().fetchFn}
      now={() => T0}
    />,
  )
  return { sockets }
}

function deliver(socket, msg) {
  act(() => socket.receive(msg))
}

// The one .main outside any [hidden] subtree.
function mainEl() {
  const shown = Array.from(document.querySelectorAll('.main')).filter((el) => el.closest('[hidden]') === null)
  expect(shown, 'exactly one shown .main').toHaveLength(1)
  return shown[0]
}

const player = (name, { host = false } = {}) => ({ name, score: 0, host, connected: true })
const roster = (players, phase = 'lobby') => ({ type: 'roster', phase, players })
const HOST_PLAYERS = [player('하루', { host: true }), player('Joon')]
const GUEST_PLAYERS = [player('Mina', { host: true }), player('Hana')]

async function openSocket(env) {
  await waitFor(() => expect(env.sockets.sockets.length).toBe(1))
  const socket = env.sockets.last
  act(() => socket.open())
  await waitFor(() => expect(socket.sent.length).toBeGreaterThan(0))
  return socket
}

async function hostLobby(env) {
  createRoomAs('하루')
  const socket = await openSocket(env)
  deliver(socket, { type: 'joined', code: 'QWERT', name: '하루' })
  deliver(socket, roster(HOST_PLAYERS))
  return socket
}

describe('.main.solo on the start screen only', () => {
  it('the start screen: .main has solo', () => {
    setup()
    expect(screen.getByRole('button', { name: '방 만들기' })).toBeTruthy()
    expect(mainEl().classList.contains('solo'), '.main.solo on the start screen').toBe(true)
  })

  it('the lobby, host: .main lacks solo', async () => {
    const env = setup()
    await hostLobby(env)
    await waitFor(() => expect(document.querySelector('.ticket .tk-code')).not.toBeNull())
    expect(mainEl().classList.contains('solo'), '.main.solo in the lobby').toBe(false)
  })

  it('the lobby, guest: .main lacks solo', async () => {
    const env = setup()
    joinRoomAs('Hana', 'ABCDE')
    const socket = await openSocket(env)
    deliver(socket, { type: 'joined', code: 'ABCDE', name: 'Hana' })
    deliver(socket, roster(GUEST_PLAYERS))
    await waitFor(() => expect(document.querySelector('.ticket .tk-code')).not.toBeNull())
    expect(mainEl().classList.contains('solo'), '.main.solo in the lobby').toBe(false)
  })

  it('a game: .main lacks solo', async () => {
    const env = setup()
    const socket = await hostLobby(env)
    startGame()
    await waitFor(() => expect(socket.sentOfType('start')).toHaveLength(1))
    deliver(socket, roster(HOST_PLAYERS, 'playing'))
    deliver(socket, { type: 'prepare', index: 0, total: 5, audioUrl: '/audio/s0' })
    await waitFor(() => expect(socket.sentOfType('ready')).toHaveLength(1))
    expect(document.querySelector('aside.chat'), 'the game chat').not.toBeNull()
    expect(mainEl().classList.contains('solo'), '.main.solo in a game').toBe(false)
  })
})
