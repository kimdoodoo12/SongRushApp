// Cycle 119 review (critic High): the header height is measured, not fixed.
// Frame measures its <header> with a ResizeObserver and sets --hd (px) on
// .app, so scroll.css and choice.css follow the real height when longer
// mood names in en or ja wrap the mood bar
// (docs/cycles/119-nickname-dialog.md, Pre-PR review fixes).
//
// Contract with the implementer:
// - .app carries the inline custom property --hd: "<offsetHeight of the
//   header>px" on every screen that uses Frame (start, lobby, game).
// - A ResizeObserver observes the header; when it fires, --hd takes the
//   header's new offsetHeight.
// jsdom has no layout and no ResizeObserver: both are stubbed here. The
// header's offsetHeight is read from `headerHeight`; the observer stub
// records each instance and its targets, and fire() calls every connected
// instance with entries for its targets (contentRect and borderBoxSize carry
// the same height).
// Timer rule (cycle 039): real timers and waitFor.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, act, cleanup } from '@testing-library/react'
import App from './App.jsx'
import { createSocketFactory, createAudioContextFactory, createFakeFetch } from './testing/fakes.js'
import { joinRoomAs } from './testing/lobby.js'

const T0 = 1_000_000

let headerHeight = 114
let observers = []

class FakeResizeObserver {
  constructor(callback) {
    this.callback = callback
    this.targets = new Set()
    this.connected = true
    observers.push(this)
  }

  observe(target) {
    this.targets.add(target)
  }

  unobserve(target) {
    this.targets.delete(target)
  }

  disconnect() {
    this.targets.clear()
    this.connected = false
  }
}

function fireResize() {
  for (const observer of observers) {
    if (!observer.connected || observer.targets.size === 0) continue
    const entries = Array.from(observer.targets).map((target) => ({
      target,
      contentRect: { width: 1000, height: headerHeight, top: 0, left: 0, right: 1000, bottom: headerHeight, x: 0, y: 0 },
      borderBoxSize: [{ inlineSize: 1000, blockSize: headerHeight }],
      contentBoxSize: [{ inlineSize: 1000, blockSize: headerHeight }],
    }))
    act(() => observer.callback(entries, observer))
  }
}

let realOffsetHeight

beforeEach(() => {
  window.history.pushState({}, '', '/')
  headerHeight = 114
  observers = []
  vi.stubGlobal('ResizeObserver', FakeResizeObserver)
  realOffsetHeight = Object.getOwnPropertyDescriptor(HTMLElement.prototype, 'offsetHeight')
  Object.defineProperty(HTMLElement.prototype, 'offsetHeight', {
    configurable: true,
    get() {
      return this.tagName === 'HEADER' ? headerHeight : 0
    },
  })
})

afterEach(() => {
  cleanup()
  if (realOffsetHeight !== undefined) Object.defineProperty(HTMLElement.prototype, 'offsetHeight', realOffsetHeight)
  vi.unstubAllGlobals()
  window.history.pushState({}, '', '/')
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

async function openSocket(env, count = 1) {
  await waitFor(() => expect(env.sockets.sockets.length).toBe(count))
  const socket = env.sockets.last
  act(() => socket.open())
  await waitFor(() => expect(socket.sent.length).toBeGreaterThan(0))
  return socket
}

const player = (name, { host = false } = {}) => ({ name, score: 0, host, connected: true })
const roster = (players, phase = 'lobby') => ({ type: 'roster', phase, players })
const PLAYERS = [player('Mina', { host: true }), player('Hana')]

function startMsg() {
  const serverTime = T0 + 500
  const startAt = serverTime + 3000
  return { type: 'start', index: 0, startAt, deadlineAt: startAt + 90_000, serverTime }
}

// A room game in song 0, started: joined, roster{playing}, prepare, ready, start.
async function roomSongStarted(env) {
  joinRoomAs('Hana', 'ABCDE')
  const socket = await openSocket(env)
  deliver(socket, { type: 'joined', code: 'ABCDE', name: 'Hana' })
  deliver(socket, roster(PLAYERS))
  deliver(socket, roster(PLAYERS, 'playing'))
  deliver(socket, { type: 'prepare', index: 0, total: 5, audioUrl: '/audio/abc' })
  await waitFor(() => expect(socket.sentOfType('ready')).toHaveLength(1))
  deliver(socket, startMsg())
  await waitFor(() => expect(env.audio.last.startedSources()).toHaveLength(1))
  return socket
}

function appEl() {
  const app = document.querySelector('.app')
  expect(app, '.app').not.toBeNull()
  return app
}

function hd() {
  return appEl().style.getPropertyValue('--hd').trim()
}

describe('--hd on .app', () => {
  it('the start screen sets --hd from the header height', async () => {
    setup()
    expect(document.querySelector('.app header')).not.toBeNull()
    await waitFor(() => expect(hd()).toBe('114px'))
  })

  it('the start screen updates --hd when the observer reports a new header height', async () => {
    setup()
    await waitFor(() => expect(hd()).toBe('114px'))
    headerHeight = 67
    fireResize()
    await waitFor(() => expect(hd()).toBe('67px'))
    headerHeight = 130
    fireResize()
    await waitFor(() => expect(hd()).toBe('130px'))
  })

  it('a game screen sets --hd from the header height', async () => {
    const env = setup()
    await roomSongStarted(env)
    expect(screen.getByRole('progressbar', { name: '남은 시간' })).toBeTruthy()
    await waitFor(() => expect(hd()).toBe('114px'))
  })

  it('a game screen updates --hd when the observer reports a new header height', async () => {
    const env = setup()
    await roomSongStarted(env)
    await waitFor(() => expect(hd()).toBe('114px'))
    headerHeight = 67
    fireResize()
    await waitFor(() => expect(hd()).toBe('67px'))
  })
})
