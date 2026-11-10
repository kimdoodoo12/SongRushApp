// Cycle 071 (F-012): room themes. The start-screen picker and the own pick
// in localStorage, the host's lobby picker and the server's roomTheme frame,
// the room theme over the own pick, the game stage's hint and clock, and the
// reducer's roomTheme field.
// Cycle 109 (F-025): the eight moods sunset rain sakura neon chalk pixel
// matsuri manga, default sunset; an old stored or sent id (play, star,
// arcade) maps to sunset, rain and pixel (docs/cycles/109-eight-moods.md).
// Real timers only (cycle 039 timer rule): waitFor drives every wait.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, act, cleanup, within } from '@testing-library/react'
import App from './App.jsx'
import { initialState, reducer } from './game/state.js'
import { THEMES, DEFAULT_THEME, themeId } from './game/themes.js'
import { createSocketFactory, createAudioContextFactory, createFakeFetch } from './testing/fakes.js'

const T0 = 1_000_000
const STORAGE_KEY = 'songrush.roomTheme'
const THEME_IDS = ['sunset', 'rain', 'sakura', 'neon', 'chalk', 'pixel', 'matsuri', 'manga']
const THEME_NAMES = ['노을 옥상', '비 오는 밤', '벚꽃 밤', '네온 시티', '칠판 교실', '8비트 오락실', '여름 축제', '만화 원고']
const NAME_OF = Object.fromEntries(THEME_IDS.map((id, i) => [id, THEME_NAMES[i]]))
const HINT = '들리는 곡의 애니 제목을 채팅으로 맞혀 보세요'

beforeEach(() => {
  window.history.pushState({}, '', '/')
  window.localStorage.clear()
})

afterEach(() => {
  cleanup()
  window.history.pushState({}, '', '/')
  vi.restoreAllMocks()
  window.localStorage.clear()
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

function setNickname(nickname) {
  fireEvent.change(screen.getByLabelText('닉네임'), { target: { value: nickname } })
}

// The applied theme id on the outer app-shell element.
function rootTheme() {
  const root = document.querySelector('[data-room-theme]')
  expect(root).not.toBeNull()
  return root.getAttribute('data-room-theme')
}

function picker() {
  return screen.getByRole('group', { name: '분위기' })
}

function themeButton(name) {
  return within(picker()).getByRole('button', { name })
}

// The names of the picker buttons with aria-pressed="true".
function pressedNames() {
  return within(picker())
    .getAllByRole('button')
    .filter((b) => b.getAttribute('aria-pressed') === 'true')
    .map((b) => b.textContent.trim())
}

// Waits for the socket the click opened, opens it and waits for one message.
async function openSocket(env, count = 1) {
  await waitFor(() => expect(env.sockets.sockets.length).toBe(count))
  const socket = env.sockets.last
  act(() => socket.open())
  await waitFor(() => expect(socket.sent.length).toBeGreaterThan(0))
  return socket
}

const player = (name, { host = false, score = 0, connected = true } = {}) => ({ name, score, host, connected })

const roster = (players, phase = 'lobby') => ({ type: 'roster', phase, players })

// Creates room QWERT as 하루, the host.
async function hostLobby(env) {
  setNickname('하루')
  fireEvent.click(screen.getByRole('button', { name: '방 만들기' }))
  const socket = await openSocket(env)
  deliver(socket, { type: 'joined', code: 'QWERT', name: '하루' })
  deliver(socket, roster([player('하루', { host: true }), player('Joon')]))
  return socket
}

// Joins ABCDE as Hana (2); Mina hosts.
async function guestLobby(env) {
  setNickname('Hana')
  fireEvent.change(screen.getByLabelText('방 코드'), { target: { value: 'ABCDE' } })
  fireEvent.click(screen.getByRole('button', { name: '참가' }))
  const socket = await openSocket(env)
  deliver(socket, { type: 'joined', code: 'ABCDE', name: 'Hana (2)' })
  deliver(socket, roster([player('Mina', { host: true }), player('Hana (2)'), player('Joon')]))
  return socket
}

function startMsg({ index = 0, startDelta = 0, length = 90_000 } = {}) {
  const serverTime = T0 + 500
  const startAt = serverTime + startDelta
  return { type: 'start', index, startAt, deadlineAt: startAt + length, serverTime }
}

describe('start screen picker', () => {
  it('shows the 분위기 group with eight buttons in order, 노을 옥상 pressed by default', () => {
    setup()
    const names = within(picker())
      .getAllByRole('button')
      .map((b) => b.textContent.trim())
    expect(names).toEqual(THEME_NAMES)
    for (const name of THEME_NAMES) {
      const button = themeButton(name)
      expect(button.getAttribute('type')).toBe('button')
      expect(button.disabled).toBe(false)
    }
    expect(pressedNames()).toEqual(['노을 옥상'])
    expect(rootTheme()).toBe('sunset')
  })

  it('clicking 칠판 교실 applies chalk to the root and stores it', () => {
    setup()
    fireEvent.click(themeButton('칠판 교실'))
    expect(rootTheme()).toBe('chalk')
    expect(pressedNames()).toEqual(['칠판 교실'])
    expect(window.localStorage.getItem(STORAGE_KEY)).toBe('chalk')
  })

  it.each(THEME_IDS)('clicking the %s button applies it and stores it', (id) => {
    setup()
    fireEvent.click(themeButton(NAME_OF[id]))
    expect(rootTheme()).toBe(id)
    expect(pressedNames()).toEqual([NAME_OF[id]])
    expect(window.localStorage.getItem(STORAGE_KEY)).toBe(id)
  })

  it('a stored rain is applied on mount', () => {
    window.localStorage.setItem(STORAGE_KEY, 'rain')
    setup()
    expect(rootTheme()).toBe('rain')
    expect(pressedNames()).toEqual(['비 오는 밤'])
  })

  it.each([
    ['arcade', 'pixel'],
    ['star', 'rain'],
    ['play', 'sunset'],
  ])('a stored old %s loads as %s', (stored, mood) => {
    window.localStorage.setItem(STORAGE_KEY, stored)
    setup()
    expect(rootTheme()).toBe(mood)
    expect(pressedNames()).toEqual([NAME_OF[mood]])
  })

  it('a stored unknown id falls back to sunset', () => {
    window.localStorage.setItem(STORAGE_KEY, 'lava')
    setup()
    expect(rootTheme()).toBe('sunset')
    expect(pressedNames()).toEqual(['노을 옥상'])
  })

  it('a throwing localStorage still renders with sunset, and a pick still applies', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('storage blocked')
    })
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('storage blocked')
    })
    setup()
    expect(rootTheme()).toBe('sunset')
    expect(screen.getByRole('button', { name: '방 만들기' })).toBeTruthy()
    fireEvent.click(themeButton('칠판 교실'))
    expect(rootTheme()).toBe('chalk')
  })
})

describe('lobby picker', () => {
  it('the host clicking 네온 시티 sends exactly roomTheme neon; pressed changes only on the server frame', async () => {
    const env = setup()
    const socket = await hostLobby(env)
    expect(rootTheme()).toBe('sunset')
    expect(pressedNames()).toEqual(['노을 옥상'])
    for (const name of THEME_NAMES) expect(themeButton(name).disabled).toBe(false)

    fireEvent.click(themeButton('네온 시티'))
    await waitFor(() => expect(socket.sentOfType('roomTheme')).toHaveLength(1))
    expect(socket.sentOfType('roomTheme')).toEqual([{ type: 'roomTheme', theme: 'neon' }])
    expect(Object.keys(socket.sentOfType('roomTheme')[0]).sort()).toEqual(['theme', 'type'])
    // Not yet confirmed by the server.
    expect(pressedNames()).toEqual(['노을 옥상'])
    expect(rootTheme()).toBe('sunset')

    deliver(socket, { type: 'roomTheme', theme: 'neon' })
    expect(pressedNames()).toEqual(['네온 시티'])
    expect(rootTheme()).toBe('neon')
    // The host's lobby pick never touches the own pick.
    expect(window.localStorage.getItem(STORAGE_KEY)).toBeNull()
  })

  it('a non-host sees every button disabled; a server pixel frame applies and presses 8비트 오락실', async () => {
    const env = setup()
    const socket = await guestLobby(env)
    for (const name of THEME_NAMES) expect(themeButton(name).disabled).toBe(true)
    expect(pressedNames()).toEqual(['노을 옥상'])

    fireEvent.click(themeButton('비 오는 밤'))
    expect(socket.sentOfType('roomTheme')).toEqual([])

    deliver(socket, { type: 'roomTheme', theme: 'pixel' })
    expect(rootTheme()).toBe('pixel')
    expect(pressedNames()).toEqual(['8비트 오락실'])
  })

  it('a roomTheme frame sets data-room-theme on the shell for each of the eight ids', async () => {
    const env = setup()
    const socket = await guestLobby(env)
    for (const id of THEME_IDS) {
      deliver(socket, { type: 'roomTheme', theme: id })
      const shell = document.querySelector('.app-shell')
      expect(shell, id).not.toBeNull()
      expect(shell.getAttribute('data-room-theme'), id).toBe(id)
      expect(pressedNames(), id).toEqual([NAME_OF[id]])
    }
  })
})

describe('room theme over the own pick', () => {
  it('a room shows sunset before any frame and the room theme after, whatever the own pick', async () => {
    window.localStorage.setItem(STORAGE_KEY, 'sakura')
    const env = setup()
    expect(rootTheme()).toBe('sakura')
    const socket = await guestLobby(env)
    expect(rootTheme()).toBe('sunset')
    deliver(socket, { type: 'roomTheme', theme: 'rain' })
    expect(rootTheme()).toBe('rain')
    expect(window.localStorage.getItem(STORAGE_KEY)).toBe('sakura')
  })

  it('leaving the room shows the own pick again', async () => {
    const env = setup()
    fireEvent.click(themeButton('칠판 교실'))
    expect(rootTheme()).toBe('chalk')
    const socket = await guestLobby(env)
    expect(rootTheme()).toBe('sunset')
    deliver(socket, { type: 'roomTheme', theme: 'rain' })
    expect(rootTheme()).toBe('rain')

    fireEvent.click(screen.getByRole('button', { name: '나가기' }))
    expect(screen.getByRole('button', { name: '방 만들기' })).toBeTruthy()
    expect(rootTheme()).toBe('chalk')
    expect(pressedNames()).toEqual(['칠판 교실'])
  })
})

describe('room game stage', () => {
  it('after start the stage shows the hint and a clock in whole seconds, under the room theme', async () => {
    const env = setup()
    const socket = await guestLobby(env)
    deliver(socket, { type: 'roomTheme', theme: 'neon' })
    deliver(socket, roster([player('Mina', { host: true }), player('Hana (2)'), player('Joon')], 'playing'))
    deliver(socket, { type: 'prepare', index: 0, total: 5, audioUrl: '/audio/abc' })
    await waitFor(() => expect(socket.sentOfType('ready').filter((m) => m.index === 0)).toHaveLength(1))
    deliver(socket, startMsg())

    expect(await screen.findByText(HINT)).toBeTruthy()
    const clocks = await screen.findAllByText(/^\d+초$/)
    expect(clocks.length).toBeGreaterThan(0)
    expect(rootTheme()).toBe('neon')
  })
})

describe('reducer roomTheme', () => {
  it('starts null, a roomTheme action sets it and reset clears it', () => {
    expect(initialState.roomTheme).toBeNull()
    const themed = reducer(initialState, { type: 'roomTheme', theme: 'rain' })
    expect(themed.roomTheme).toBe('rain')
    expect(reducer(themed, { type: 'reset' }).roomTheme).toBeNull()
  })
})

describe('themes module', () => {
  it('lists the eight moods in picker order, with sunset the default', () => {
    expect(THEMES.map((t) => t.id)).toEqual(THEME_IDS)
    expect(DEFAULT_THEME).toBe('sunset')
    for (const theme of THEMES) {
      expect(typeof theme.swatch, theme.id).toBe('string')
      expect(theme.swatch.trim(), theme.id).not.toBe('')
    }
  })

  it('themeId maps the old ids, keeps a known id and sends anything else to sunset', () => {
    expect(themeId('play')).toBe('sunset')
    expect(themeId('star')).toBe('rain')
    expect(themeId('arcade')).toBe('pixel')
    for (const id of THEME_IDS) expect(themeId(id)).toBe(id)
    for (const other of ['lava', '', null, undefined, 'NEON', ' rain', 'Play', 5, {}]) {
      expect(themeId(other)).toBe('sunset')
    }
  })
})
