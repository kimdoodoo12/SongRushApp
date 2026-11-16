// Cycle 139 (D-137, docs/cycles/139-song-count.md criterion 2): the host
// picks 5 to 100 songs, and can type the count as well as step it.
// - The host's 곡 수 is an <input type="number" inputmode="numeric"> named
//   곡 수 (min 5, max 100) between - and +. Typing commits on Enter or blur;
//   the value clamps to 5..100, and text that is not a number reverts to
//   the current count. The committed count goes out in lobbySettings and in
//   start as a number.
// - - and + still step by one and stop at 5 and 100.
// - A guest sees the host's count read-only: an output, no input, and
//   disabled - and +.
// Timer rule (cycle 039): real timers and waitFor.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, act, cleanup } from '@testing-library/react'
import App from './App.jsx'
import { createSocketFactory, createAudioContextFactory, createFakeFetch } from './testing/fakes.js'
import { createRoomAs, joinRoomAs, setSongCount, songCount, songCountOutput, startGame, stepperButtons, typeSongCount } from './testing/lobby.js'

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
  const audio = createAudioContextFactory()
  const fetch = createFakeFetch()
  render(
    <App
      canPlayOpus={true}
      socketFactory={sockets.factory}
      audioContextFactory={audio.factory}
      fetchFn={fetch.fetchFn}
      now={() => T0}
    />,
  )
  return { sockets, audio, fetch }
}

function deliver(socket, msg) {
  act(() => socket.receive(msg))
}

const player = (name, { host = false } = {}) => ({ name, score: 0, host, connected: true })
const roster = (players) => ({ type: 'roster', phase: 'lobby', players })

async function openSocket(env) {
  await waitFor(() => expect(env.sockets.sockets.length).toBe(1))
  const socket = env.sockets.last
  act(() => socket.open())
  await waitFor(() => expect(socket.sent.length).toBeGreaterThan(0))
  return socket
}

// 방 만들기 as 하루: joined QWERT and a lobby roster with 하루 as host.
async function hostLobby(env) {
  createRoomAs('하루')
  const socket = await openSocket(env)
  deliver(socket, { type: 'joined', code: 'QWERT', name: '하루' })
  deliver(socket, roster([player('하루', { host: true }), player('Joon')]))
  return socket
}

// 참가 to ABCDE as Hana, with Mina as host.
async function guestLobby(env) {
  joinRoomAs('Hana', 'ABCDE')
  const socket = await openSocket(env)
  deliver(socket, { type: 'joined', code: 'ABCDE', name: 'Hana' })
  deliver(socket, roster([player('Mina', { host: true }), player('Hana')]))
  return socket
}

function lastSettings(socket) {
  const all = socket.sentOfType('lobbySettings')
  return all[all.length - 1]
}

describe('the host types 곡 수', () => {
  it('is a number input named 곡 수, 5 to 100, at 10', async () => {
    const env = setup()
    await hostLobby(env)
    const input = songCountOutput()
    expect(screen.getByLabelText('곡 수')).toBe(input)
    expect(input.tagName).toBe('INPUT')
    expect(input.type).toBe('number')
    expect(input.getAttribute('inputmode')).toBe('numeric')
    expect(input.min).toBe('5')
    expect(input.max).toBe('100')
    expect(input.value).toBe('10')
    expect(input.disabled).toBe(false)
  })

  it('50 and Enter: the lobby shows 50, lobbySettings carries it, and start sends songCount 50', async () => {
    const env = setup()
    const socket = await hostLobby(env)
    await waitFor(() => expect(socket.sentOfType('lobbySettings')).toHaveLength(1))
    typeSongCount('50')
    expect(songCount()).toBe(50)
    await waitFor(() => expect(lastSettings(socket)).toEqual({ type: 'lobbySettings', songCount: 50 }))
    startGame()
    await waitFor(() => expect(socket.sentOfType('start')).toEqual([{ type: 'start', songCount: 50 }]))
    expect(typeof socket.sentOfType('start')[0].songCount).toBe('number')
  })

  it('commits on blur as well', async () => {
    const env = setup()
    const socket = await hostLobby(env)
    typeSongCount('64', 'blur')
    expect(songCount()).toBe(64)
    await waitFor(() => expect(lastSettings(socket)).toEqual({ type: 'lobbySettings', songCount: 64 }))
  })

  it('nothing is sent while typing, before Enter or blur', async () => {
    const env = setup()
    const socket = await hostLobby(env)
    await waitFor(() => expect(socket.sentOfType('lobbySettings')).toHaveLength(1))
    fireEvent.change(songCountOutput(), { target: { value: '42' } })
    await new Promise((resolve) => setTimeout(resolve, 20))
    expect(socket.sentOfType('lobbySettings')).toHaveLength(1)
    fireEvent.keyDown(songCountOutput(), { key: 'Enter' })
    await waitFor(() => expect(lastSettings(socket)).toEqual({ type: 'lobbySettings', songCount: 42 }))
  })

  it('200 gives 100 and 2 gives 5', async () => {
    const env = setup()
    const socket = await hostLobby(env)
    typeSongCount('200')
    expect(songCount()).toBe(100)
    expect(stepperButtons()[1].disabled).toBe(true)
    await waitFor(() => expect(lastSettings(socket)).toEqual({ type: 'lobbySettings', songCount: 100 }))
    typeSongCount('2')
    expect(songCount()).toBe(5)
    expect(stepperButtons()[0].disabled).toBe(true)
    await waitFor(() => expect(lastSettings(socket)).toEqual({ type: 'lobbySettings', songCount: 5 }))
  })

  it('letters or an empty field revert to the current count and send nothing', async () => {
    const env = setup()
    const socket = await hostLobby(env)
    typeSongCount('37')
    await waitFor(() => expect(lastSettings(socket)).toEqual({ type: 'lobbySettings', songCount: 37 }))
    const sent = socket.sentOfType('lobbySettings').length
    typeSongCount('abc')
    expect(songCount()).toBe(37)
    expect(songCountOutput().value).toBe('37')
    typeSongCount('', 'blur')
    expect(songCountOutput().value).toBe('37')
    await new Promise((resolve) => setTimeout(resolve, 20))
    expect(socket.sentOfType('lobbySettings')).toHaveLength(sent)
    startGame()
    await waitFor(() => expect(socket.sentOfType('start')).toEqual([{ type: 'start', songCount: 37 }]))
  })

  it('- and + still step by one and stop at 5 and 100', async () => {
    const env = setup()
    const socket = await hostLobby(env)
    const [down, up] = stepperButtons()
    fireEvent.click(up)
    expect(songCount()).toBe(11)
    fireEvent.click(down)
    fireEvent.click(down)
    expect(songCount()).toBe(9)
    typeSongCount('99')
    fireEvent.click(stepperButtons()[1])
    expect(songCount()).toBe(100)
    expect(stepperButtons()[1].disabled).toBe(true)
    fireEvent.click(stepperButtons()[1])
    expect(songCount()).toBe(100)
    typeSongCount('6')
    fireEvent.click(stepperButtons()[0])
    expect(songCount()).toBe(5)
    expect(stepperButtons()[0].disabled).toBe(true)
    fireEvent.click(stepperButtons()[0])
    expect(songCount()).toBe(5)
    setSongCount(100)
    expect(songCount()).toBe(100)
    startGame()
    await waitFor(() => expect(socket.sentOfType('start')).toEqual([{ type: 'start', songCount: 100 }]))
  })
})

describe('a guest', () => {
  it('sees the host count read-only: an output, no input, - and + disabled', async () => {
    const env = setup()
    const socket = await guestLobby(env)
    deliver(socket, { type: 'lobbySettings', songCount: 77 })
    const output = songCountOutput()
    expect(screen.getByLabelText('곡 수')).toBe(output)
    expect(output.tagName).toBe('OUTPUT')
    expect(songCount()).toBe(77)
    expect(document.querySelector('.stepper input')).toBeNull()
    const [down, up] = stepperButtons()
    expect(down.disabled).toBe(true)
    expect(up.disabled).toBe(true)
    fireEvent.click(up)
    expect(songCount()).toBe(77)
    expect(socket.sentOfType('lobbySettings')).toEqual([])
  })
})
