// Cycle 137 (F-039, D-136, issue #64): each player picks the mood for their
// own screen (docs/cycles/137-own-mood.md, criteria 1-5).
// - In a room (lobby and game) <html data-mood> is the player's own stored
//   pick, the same one the start screen uses (songrush.roomTheme in
//   localStorage).
// - The header picker is enabled and labelled 분위기 for the host and a guest
//   alike, in the lobby and during a game. A click applies the mood at once,
//   stores it, and sends nothing to the server.
// - A roomTheme frame from the server does not change the look.
// Timer rule (cycle 039): real timers and waitFor.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, act, cleanup, within } from '@testing-library/react'
import App from './App.jsx'
import ko from './i18n/ko.json'
import en from './i18n/en.json'
import ja from './i18n/ja.json'
import { createSocketFactory, createAudioContextFactory, createFakeFetch } from './testing/fakes.js'
import { createRoomAs, joinRoomAs } from './testing/lobby.js'

const T0 = 1_000_000
const OWN_THEME_KEY = 'songrush.roomTheme'
const MOOD_IDS = ['sunset', 'rain', 'sakura', 'neon', 'chalk', 'pixel', 'matsuri', 'manga']

beforeEach(() => {
  window.history.pushState({}, '', '/')
  window.localStorage.clear()
  window.sessionStorage.clear()
})

afterEach(() => {
  cleanup()
  window.history.pushState({}, '', '/')
  vi.restoreAllMocks()
  window.localStorage.clear()
  window.sessionStorage.clear()
})

// Renders App with fakes. clock.t is what now() returns.
function setup() {
  const sockets = createSocketFactory()
  const audio = createAudioContextFactory()
  const fetch = createFakeFetch()
  const clock = { t: T0 }
  render(
    <App
      canPlayOpus
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

async function openSocket(env) {
  await waitFor(() => expect(env.sockets.sockets.length).toBe(1))
  const socket = env.sockets.last
  act(() => socket.open())
  await waitFor(() => expect(socket.sent.length).toBeGreaterThan(0))
  return socket
}

const player = (name, { host = false, score = 0, connected = true } = {}) => ({ name, score, host, connected })
const roster = (players, phase = 'lobby') => ({ type: 'roster', phase, players })
const HOST_PLAYERS = [player('하루', { host: true }), player('Mina')]
const GUEST_PLAYERS = [player('Mina', { host: true }), player('Hana (2)'), player('Joon')]

// Creates QWERT as 하루, the host.
async function hostLobby(env) {
  createRoomAs('하루')
  const socket = await openSocket(env)
  deliver(socket, { type: 'joined', code: 'QWERT', name: '하루' })
  deliver(socket, roster(HOST_PLAYERS))
  return socket
}

// Joins ABCDE as Hana (2); Mina hosts.
async function guestLobby(env) {
  joinRoomAs('Hana', 'ABCDE')
  const socket = await openSocket(env)
  deliver(socket, { type: 'joined', code: 'ABCDE', name: 'Hana (2)' })
  deliver(socket, roster(GUEST_PLAYERS))
  return socket
}

// From the lobby: song 0 prepared, ready sent and started now.
async function songStarted(env, socket, players) {
  deliver(socket, roster(players, 'playing'))
  deliver(socket, { type: 'prepare', index: 0, total: 5, audioUrl: '/audio/abc' })
  await waitFor(() => expect(socket.sentOfType('ready').filter((m) => m.index === 0)).toHaveLength(1))
  const serverTime = T0 + 500
  deliver(socket, { type: 'start', index: 0, startAt: serverTime, deadlineAt: serverTime + 90_000, serverTime })
  await waitFor(() => expect(env.audio.last.startedSources()).toHaveLength(1))
  expect(document.querySelector('.now .deck'), 'the game screen').not.toBeNull()
}

const ROLES = {
  host: { lobby: hostLobby, players: HOST_PLAYERS },
  guest: { lobby: guestLobby, players: GUEST_PLAYERS },
}

function mood() {
  return document.documentElement.dataset.mood
}

// The one mood picker: the header's .moodbar group 분위기.
function picker() {
  const groups = screen.getAllByRole('group', { name: ko.theme.label })
  expect(groups).toHaveLength(1)
  expect(groups[0].closest('header .moodbar')).not.toBeNull()
  return groups[0]
}

function pick(id) {
  fireEvent.click(within(picker()).getByRole('button', { name: ko.theme[id] }))
}

function pressed() {
  return within(picker())
    .getAllByRole('button')
    .filter((b) => b.getAttribute('aria-pressed') === 'true')
    .map((b) => b.textContent.trim())
}

// The picker's state: its .ml label and whether any button is disabled.
function pickerState() {
  const buttons = within(picker()).getAllByRole('button')
  return {
    label: document.querySelector('header .moodbar .ml').textContent.trim(),
    buttons: buttons.length,
    anyDisabled: buttons.some((b) => b.disabled),
  }
}

describe('criterion 1: a guest picks a mood in the lobby', () => {
  it('html[data-mood] changes at once and nothing is sent', async () => {
    const env = setup()
    const socket = await guestLobby(env)
    expect(mood()).toBe('sunset')
    const sentBefore = socket.sent.length
    pick('rain')
    expect(mood()).toBe('rain')
    expect(pressed()).toEqual([ko.theme.rain])
    expect(socket.sent.slice(sentBefore)).toEqual([])
    expect(socket.sentOfType('roomTheme')).toEqual([])
  })
})

describe('criterion 2: the host picks a mood', () => {
  it('in the lobby the look changes and no roomTheme is sent', async () => {
    const env = setup()
    const socket = await hostLobby(env)
    pick('neon')
    expect(mood()).toBe('neon')
    expect(pressed()).toEqual([ko.theme.neon])
    // The lobby settings may go out (the host's selection); never roomTheme.
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 50))
    })
    expect(socket.sentOfType('roomTheme')).toEqual([])
  })

  it('during a game the look changes and no roomTheme is sent', async () => {
    const env = setup()
    const socket = await hostLobby(env)
    await songStarted(env, socket, HOST_PLAYERS)
    pick('matsuri')
    expect(mood()).toBe('matsuri')
    expect(document.querySelector('.stage-top .tag').textContent).toContain(ko.theme.matsuri)
    expect(socket.sentOfType('roomTheme')).toEqual([])
  })
})

describe('criterion 3: a roomTheme frame from the server', () => {
  it.each(['host', 'guest'])('does not change the look in the lobby (%s)', async (role) => {
    window.localStorage.setItem(OWN_THEME_KEY, 'chalk')
    const env = setup()
    const socket = await ROLES[role].lobby(env)
    expect(mood()).toBe('chalk')
    for (const id of MOOD_IDS) {
      deliver(socket, { type: 'roomTheme', theme: id })
      expect(mood(), id).toBe('chalk')
      expect(pressed(), id).toEqual([ko.theme.chalk])
    }
  })

  it('does not change the look during a game', async () => {
    const env = setup()
    const socket = await guestLobby(env)
    await songStarted(env, socket, GUEST_PLAYERS)
    expect(mood()).toBe('sunset')
    deliver(socket, { type: 'roomTheme', theme: 'pixel' })
    expect(mood()).toBe('sunset')
    expect(document.querySelector('.stage-top .tag').textContent).toContain(ko.theme.sunset)
  })
})

describe('criterion 4: the pick is stored', () => {
  it('a pick in the lobby applies after a reload, on the start screen', async () => {
    const env = setup()
    await guestLobby(env)
    pick('sakura')
    expect(window.localStorage.getItem(OWN_THEME_KEY)).toBe('sakura')
    cleanup()
    document.documentElement.dataset.mood = 'none-yet'
    setup()
    expect(screen.getByRole('button', { name: ko.start.createRoom })).toBeTruthy()
    expect(mood()).toBe('sakura')
    expect(pressed()).toEqual([ko.theme.sakura])
  })

  it('a pick during a game stays after leaving to the start screen and in the next room', async () => {
    const env = setup()
    const socket = await hostLobby(env)
    await songStarted(env, socket, HOST_PLAYERS)
    pick('manga')
    expect(window.localStorage.getItem(OWN_THEME_KEY)).toBe('manga')
    act(() => socket.serverClose(1000))
    fireEvent.click(await screen.findByRole('button', { name: ko.game.reset }))
    expect(screen.getByRole('button', { name: ko.start.createRoom })).toBeTruthy()
    expect(mood()).toBe('manga')
    expect(pressed()).toEqual([ko.theme.manga])
  })

  it('a pick on the start screen is the look in the room', async () => {
    const env = setup()
    pick('pixel')
    await hostLobby(env)
    expect(mood()).toBe('pixel')
    expect(pressed()).toEqual([ko.theme.pixel])
  })
})

describe('criterion 5: the picker is the same for host and guest', () => {
  it.each(['host', 'guest'])('%s: enabled and labelled 분위기 in the lobby and during a game', async (role) => {
    const env = setup()
    const socket = await ROLES[role].lobby(env)
    const want = { label: ko.theme.label, buttons: MOOD_IDS.length, anyDisabled: false }
    expect(pickerState(), 'lobby').toEqual(want)
    await songStarted(env, socket, ROLES[role].players)
    expect(pickerState(), 'game').toEqual(want)
  })

  it('no dictionary keeps the guest-only label', () => {
    for (const dict of [ko, en, ja]) expect(dict.theme.labelGuest).toBeUndefined()
  })
})
