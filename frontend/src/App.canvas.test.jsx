// Cycle 115 (D-124): the app mounts the mockup's two canvases and drives them
// from one requestAnimationFrame loop, as the mockup's loop does
// (docs/design/108-ui-design-reference.html). Spec
// docs/cycles/115-canvases.md, with its "Spec review" section. The drawing
// code itself is pinned by src/canvas/sky.test.js and viz.test.js.
//
// Contract with the implementer:
// - App imports the canvas code only through src/canvas/index.js
//   (createCanvasState, initSky, drawSky, drawViz, fireworkAt, sizeViz).
//   This file wraps those exports in spies that call the real code.
// - canvas#sky (aria-hidden="true") is rendered once, as the first element
//   of the app's root, followed by div.scan and then div.app, on the start,
//   lobby and game screens; it stays the same element across them.
// - initSky(state, skyCanvas, mood, dpr) runs at mount and again on every
//   html[data-mood] change, with the new mood. drawSky gets the #sky
//   canvas's 2d context, drawViz the 2d context of the deck's canvas#viz.
// - The audio graph: with the audio context and its gain, a side branch
//   gain -> DynamicsCompressor (threshold -14, ratio 4) -> AnalyserNode
//   (fftSize 256, smoothingTimeConstant 0.78), created once per context.
//   The analyser connects to nothing; the compressor only to the analyser.
// - drawViz(state, ctx, mood, t, opts): opts.live is true exactly while a
//   song is audible; then opts.levels is a 128-byte Uint8Array filled by the
//   analyser's getByteFrequencyData.
// - Reduced motion is matchMedia('(prefers-reduced-motion: reduce)') at
//   mount; a missing matchMedia counts as motion allowed. Under it drawSky
//   is never called with move=true and is not called again by later frames
//   (only the still frame, once per initSky), while drawViz runs each frame.
//   Without it drawSky(move=true) runs on frames as in the mockup's loop.
// - Re-entry guard: a requestAnimationFrame that calls back at once must not
//   make the loop recurse (no RangeError).
// - On unmount the pending frame is cancelled with cancelAnimationFrame and
//   nothing draws afterwards.
// Timer rule (cycle 039): real timers and waitFor.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, act, cleanup, within } from '@testing-library/react'
import App from './App.jsx'
import ko from './i18n/ko.json'
import { createSocketFactory, createAudioContextFactory, createFakeFetch, createRecordingContext } from './testing/fakes.js'
import { joinRoomAs } from './testing/lobby.js'
import * as canvasModule from './canvas/index.js'

vi.mock('./canvas/index.js', async (importOriginal) => {
  const real = await importOriginal()
  const wrapped = { ...real }
  for (const name of ['createCanvasState', 'initSky', 'drawSky', 'drawViz', 'fireworkAt', 'sizeViz']) {
    if (typeof real[name] === 'function') wrapped[name] = vi.fn(real[name])
  }
  return wrapped
})

const drawPlayerSpy = vi.hoisted(() => vi.fn())
vi.mock('./components/drawPlayer.js', () => ({ drawPlayer: drawPlayerSpy, default: drawPlayerSpy }))

const T0 = 1_000_000
const SPIED = ['createCanvasState', 'initSky', 'drawSky', 'drawViz', 'fireworkAt', 'sizeViz']
const ORIGINAL_RAF = window.requestAnimationFrame
const ORIGINAL_CAF = window.cancelAnimationFrame

beforeEach(() => {
  window.history.pushState({}, '', '/')
  window.localStorage.clear()
  window.sessionStorage.clear()
  for (const name of SPIED) {
    if (typeof canvasModule[name]?.mockClear === 'function') canvasModule[name].mockClear()
  }
  drawPlayerSpy.mockClear()
  stubContexts()
})

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
  window.requestAnimationFrame = ORIGINAL_RAF
  window.cancelAnimationFrame = ORIGINAL_CAF
  delete window.matchMedia
  window.history.pushState({}, '', '/')
  window.localStorage.clear()
  window.sessionStorage.clear()
})

// Every canvas's getContext('2d') returns one recording context.
function stubContexts() {
  const made = new Map()
  vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockImplementation(function getContext() {
    if (!made.has(this)) made.set(this, createRecordingContext(this))
    return made.get(this)
  })
}

// requestAnimationFrame and cancelAnimationFrame stubs that queue the
// callbacks; run(t) calls the queued ones with t.
function stubFrames() {
  const pending = new Map()
  let next = 1
  const raf = vi.fn((cb) => {
    const id = next
    next += 1
    pending.set(id, cb)
    return id
  })
  const caf = vi.fn((id) => {
    pending.delete(id)
  })
  vi.stubGlobal('requestAnimationFrame', raf)
  vi.stubGlobal('cancelAnimationFrame', caf)
  window.requestAnimationFrame = raf
  window.cancelAnimationFrame = caf
  return {
    pending,
    raf,
    caf,
    run(t) {
      const callbacks = [...pending.values()]
      pending.clear()
      act(() => {
        for (const cb of callbacks) cb(t)
      })
    },
  }
}

// A requestAnimationFrame that calls back at once (as App.choiceport.test
// does).
function stubSyncFrames() {
  const raf = vi.fn((cb) => {
    cb(performance.now())
    return 1
  })
  const caf = vi.fn()
  vi.stubGlobal('requestAnimationFrame', raf)
  vi.stubGlobal('cancelAnimationFrame', caf)
  window.requestAnimationFrame = raf
  window.cancelAnimationFrame = caf
  return { raf, caf }
}

function mockReducedMotion(reduce) {
  window.matchMedia = vi.fn((query) => ({
    matches: reduce && query.includes('prefers-reduced-motion') && query.includes('reduce'),
    media: query,
    onchange: null,
    addEventListener: () => {},
    removeEventListener: () => {},
    addListener: () => {},
    removeListener: () => {},
    dispatchEvent: () => false,
  }))
}

function setup() {
  const sockets = createSocketFactory()
  const audio = createAudioContextFactory()
  const fetch = createFakeFetch()
  const view = render(
    <App
      canPlayOpus={true}
      socketFactory={sockets.factory}
      audioContextFactory={audio.factory}
      fetchFn={fetch.fetchFn}
      now={() => T0}
    />,
  )
  return { sockets, audio, fetch, view }
}

function deliver(socket, msg) {
  act(() => socket.receive(msg))
}

const player = (name, { host = false } = {}) => ({ name, score: 0, host, connected: true })
const PLAYERS = [player('Mina', { host: true }), player('Hana (2)'), player('Joon')]
const ME = 'Hana (2)'
const roster = (phase) => ({ type: 'roster', phase, players: PLAYERS })

async function openSocket(env, count = 1) {
  await waitFor(() => expect(env.sockets.sockets.length).toBe(count))
  const socket = env.sockets.last
  act(() => socket.open())
  await waitFor(() => expect(socket.sent.length).toBeGreaterThan(0))
  return socket
}

// Joins ABCDE as Hana; the lobby shows.
async function guestLobby(env, count = 1) {
  joinRoomAs('Hana', 'ABCDE')
  const socket = await openSocket(env, count)
  deliver(socket, { type: 'joined', code: 'ABCDE', name: ME })
  deliver(socket, roster('lobby'))
  return socket
}

// The game screen with song 0 prepared and ready sent, not yet audible.
async function prepared(env) {
  const socket = await guestLobby(env)
  deliver(socket, roster('playing'))
  deliver(socket, { type: 'prepare', index: 0, total: 5, audioUrl: '/audio/s0' })
  await waitFor(() => expect(socket.sentOfType('ready')).toHaveLength(1))
  return socket
}

// From prepared() to song 0 audible.
async function startSong(env, socket) {
  const serverTime = T0 + 500
  deliver(socket, { type: 'start', index: 0, startAt: serverTime, deadlineAt: serverTime + 90_000, serverTime })
  await waitFor(() => expect(env.audio.last.startedSources()).toHaveLength(1))
  await waitFor(() => expect(document.body.classList.contains('playing')).toBe(true))
}

// Song 0 audible.
async function playing(env) {
  const socket = await prepared(env)
  await startSong(env, socket)
  return socket
}

function calls(name) {
  return canvasModule[name].mock.calls
}

function skyLayout() {
  const skies = document.querySelectorAll('canvas#sky')
  expect(skies).toHaveLength(1)
  const sky = skies[0]
  expect(sky.getAttribute('aria-hidden')).toBe('true')
  const scan = sky.nextElementSibling
  expect(scan, 'the element after #sky').not.toBeNull()
  expect(scan.matches('div.scan')).toBe(true)
  const app = scan.nextElementSibling
  expect(app, 'the element after .scan').not.toBeNull()
  expect(app.matches('div.app')).toBe(true)
  expect(document.querySelectorAll('.scan')).toHaveLength(1)
  return sky
}

describe('canvas#sky mount', () => {
  it('is the first element of the root, before .scan and .app, on the start, lobby and game screens', async () => {
    stubFrames()
    const env = setup()
    const sky = skyLayout()
    expect(env.view.container.firstElementChild).toBe(sky)
    const socket = await guestLobby(env)
    expect(skyLayout()).toBe(sky)
    deliver(socket, roster('playing'))
    deliver(socket, { type: 'prepare', index: 0, total: 5, audioUrl: '/audio/s0' })
    await waitFor(() => expect(document.querySelector('.deck canvas#viz')).not.toBeNull())
    expect(skyLayout()).toBe(sky)
    expect(env.view.container.firstElementChild).toBe(sky)
  })

  it('initSky runs at mount with the #sky canvas and the current mood', () => {
    stubFrames()
    setup()
    const sky = document.querySelector('canvas#sky')
    const init = calls('initSky')
    expect(init.length).toBeGreaterThan(0)
    const last = init[init.length - 1]
    expect(last[1]).toBe(sky)
    expect(last[2]).toBe('sunset')
    expect(typeof last[3]).toBe('number')
  })

  it('a data-mood change runs initSky again with the new mood', () => {
    stubFrames()
    setup()
    const sky = document.querySelector('canvas#sky')
    const before = calls('initSky').length
    const group = screen.getByRole('group', { name: ko.theme.label })
    fireEvent.click(within(group).getByRole('button', { name: ko.theme.rain }))
    expect(document.documentElement.dataset.mood).toBe('rain')
    const after = calls('initSky').slice(before)
    expect(after.some((args) => args[1] === sky && args[2] === 'rain')).toBe(true)
    fireEvent.click(within(group).getByRole('button', { name: ko.theme.chalk }))
    expect(calls('initSky').slice(before).some((args) => args[1] === sky && args[2] === 'chalk')).toBe(true)
  })
})

describe('the analyser tap', () => {
  it('gain -> compressor -> analyser, made with the context, connected to nothing else', async () => {
    stubFrames()
    const env = setup()
    await guestLobby(env)
    const ctx = env.audio.last
    expect(env.audio.contexts).toHaveLength(1)
    expect(ctx.gains).toHaveLength(1)
    expect(ctx.compressors).toHaveLength(1)
    expect(ctx.analysers).toHaveLength(1)
    const [gain] = ctx.gains
    const [comp] = ctx.compressors
    const [analyser] = ctx.analysers
    expect(gain.connect).toHaveBeenCalledWith(ctx.destination)
    expect(gain.connect).toHaveBeenCalledWith(comp)
    expect(gain.connect).not.toHaveBeenCalledWith(analyser)
    expect(comp.connect.mock.calls).toEqual([[analyser]])
    expect(analyser.connect).not.toHaveBeenCalled()
    expect(analyser.fftSize).toBe(256)
    expect(analyser.smoothingTimeConstant).toBeCloseTo(0.78, 6)
    expect(comp.threshold.value).toBe(-14)
    expect(comp.ratio.value).toBe(4)
  })

  it('the playback path is unchanged: the song source connects to the gain only', async () => {
    stubFrames()
    const env = setup()
    await playing(env)
    const ctx = env.audio.last
    const source = ctx.startedSources()[0]
    expect(source.connect.mock.calls).toEqual([[ctx.gains[0]]])
    expect(ctx.compressors[0].connect).not.toHaveBeenCalledWith(ctx.destination)
    expect(ctx.analysers[0].connect).not.toHaveBeenCalled()
  })

  it('is created once per audio context, also across a second session', async () => {
    stubFrames()
    const env = setup()
    await guestLobby(env)
    fireEvent.click(screen.getByRole('button', { name: ko.lobby.leave }))
    await guestLobby(env, 2)
    expect(env.audio.contexts).toHaveLength(1)
    expect(env.audio.last.analysers).toHaveLength(1)
    expect(env.audio.last.compressors).toHaveLength(1)
  })
})

describe('the frame loop', () => {
  it('drawViz gets the #viz context, live only while a song is audible, with the analyser levels', async () => {
    const frames = stubFrames()
    const env = setup()
    const socket = await prepared(env)
    frames.run(1000)
    const viz = document.querySelector('.deck canvas#viz')
    expect(viz).not.toBeNull()
    let last = calls('drawViz').at(-1)
    expect(last, 'a drawViz call').toBeDefined()
    expect(last[1].canvas).toBe(viz)
    expect(last[4]?.live ?? false).toBe(false)
    await startSong(env, socket)
    env.audio.last.analysers[0].level = 77
    frames.run(1016)
    last = calls('drawViz').at(-1)
    expect(last[1].canvas).toBe(viz)
    expect(last[4].live).toBe(true)
    expect(last[4].levels).toBeInstanceOf(Uint8Array)
    expect(last[4].levels).toHaveLength(128)
    expect(last[4].levels[3]).toBe(77)
    expect(env.audio.last.analysers[0].getByteFrequencyData).toHaveBeenCalled()
  })

  it('without reduced motion drawSky(move=true) runs on frames with the #sky context', () => {
    const frames = stubFrames()
    setup()
    const sky = document.querySelector('canvas#sky')
    for (let i = 0; i < 4; i += 1) frames.run(1000 + i * 16)
    const moving = calls('drawSky').filter((args) => args[4] === true)
    expect(moving.length).toBeGreaterThanOrEqual(2)
    for (const args of moving) expect(args[1].canvas).toBe(sky)
  })

  it('under reduced motion drawSky is not called after the first frame, while drawViz is', async () => {
    mockReducedMotion(true)
    const frames = stubFrames()
    const env = setup()
    await playing(env)
    frames.run(1000)
    const skyAfterFirst = calls('drawSky').length
    const vizAfterFirst = calls('drawViz').length
    for (let i = 1; i <= 5; i += 1) frames.run(1000 + i * 16)
    expect(calls('drawSky').length).toBe(skyAfterFirst)
    expect(calls('drawSky').some((args) => args[4] === true)).toBe(false)
    expect(calls('drawViz').length).toBeGreaterThanOrEqual(vizAfterFirst + 5)
  })

  it('under reduced motion the still sky is drawn (move=false)', () => {
    mockReducedMotion(true)
    const frames = stubFrames()
    setup()
    frames.run(1000)
    const sky = document.querySelector('canvas#sky')
    const still = calls('drawSky').filter((args) => args[4] === false && args[1]?.canvas === sky)
    expect(still.length).toBeGreaterThanOrEqual(1)
  })

  it('with a requestAnimationFrame that calls back at once, a game screen renders without a RangeError', async () => {
    const errors = []
    const onError = (event) => errors.push(event.error ?? event)
    window.addEventListener('error', onError)
    const consoleError = vi.spyOn(console, 'error')
    try {
      stubSyncFrames()
      const env = setup()
      await playing(env)
      expect(document.querySelector('.deck canvas#viz')).not.toBeNull()
      await waitFor(() => expect(calls('drawViz').length).toBeGreaterThan(0))
    } finally {
      window.removeEventListener('error', onError)
    }
    expect(errors.filter((e) => e instanceof RangeError)).toHaveLength(0)
    const logged = consoleError.mock.calls.flat().filter((a) => a instanceof RangeError || /Maximum call stack/.test(String(a)))
    expect(logged).toHaveLength(0)
  })

  it('the loop is cancelled on unmount and nothing draws afterwards', async () => {
    const frames = stubFrames()
    const env = setup()
    await prepared(env)
    frames.run(1000)
    frames.run(1016)
    expect(frames.pending.size).toBe(1)
    const [pendingId] = frames.pending.keys()
    env.view.unmount()
    expect(frames.caf).toHaveBeenCalledWith(pendingId)
    expect(frames.pending.size).toBe(0)
    const rafCalls = frames.raf.mock.calls.length
    const skyCalls = calls('drawSky').length
    const vizCalls = calls('drawViz').length
    await new Promise((resolve) => setTimeout(resolve, 30))
    frames.run(1032)
    expect(frames.raf.mock.calls.length).toBe(rafCalls)
    expect(calls('drawSky').length).toBe(skyCalls)
    expect(calls('drawViz').length).toBe(vizCalls)
  })
})

