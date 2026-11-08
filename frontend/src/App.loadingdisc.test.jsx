// Cycle 086 (D-101): while the song's audio is loading, and when loading
// failed, the record and the equalizer stay on screen but stand still. The
// disc area carries data-paused="true" while load is 'loading' or 'failed';
// otherwise the attribute is absent. CSS pauses the animations through it.
// Timer rule (cycle 039): real timers and waitFor.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, act, cleanup } from '@testing-library/react'
import App from './App.jsx'
import ko from './i18n/ko.json'
import { createSocketFactory, createAudioContextFactory, createFakeFetch } from './testing/fakes.js'

const T0 = 1_000_000

beforeEach(() => {
  window.history.pushState({}, '', '/')
  window.localStorage.clear()
})

afterEach(() => {
  vi.restoreAllMocks()
  cleanup()
  window.history.pushState({}, '', '/')
  window.localStorage.clear()
})

// A fetch whose calls stay pending until release() (oldest first).
function createDeferredFetch() {
  const urls = []
  const pending = []
  const fetchFn = vi.fn((url) => {
    urls.push(url)
    return new Promise((resolve) => pending.push(resolve))
  })
  function release() {
    const resolve = pending.shift()
    if (!resolve) throw new Error('no pending fetch')
    const buffer = new ArrayBuffer(8)
    resolve({ ok: true, status: 200, arrayBuffer: async () => buffer })
  }
  return { fetchFn, urls, pending, release }
}

function setup({ ctxOptions, fetchOptions, fetch } = {}) {
  const sockets = createSocketFactory()
  const audio = createAudioContextFactory(ctxOptions)
  const theFetch = fetch === undefined ? createFakeFetch(fetchOptions) : fetch
  render(
    <App
      canPlayOpus={true}
      socketFactory={sockets.factory}
      audioContextFactory={audio.factory}
      fetchFn={theFetch.fetchFn}
      now={() => T0}
    />,
  )
  return { sockets, audio, fetch: theFetch }
}

function deliver(socket, msg) {
  act(() => socket.receive(msg))
}

const PLAYERS = [{ name: '하루', score: 0, host: true, connected: true }]
const roster = (phase) => ({ type: 'roster', phase, players: PLAYERS })

// Creates a room alone as 하루; the game starts and prepare{0} is delivered.
async function gamePrepared(env) {
  fireEvent.change(screen.getByLabelText(ko.start.nickname), { target: { value: '하루' } })
  fireEvent.click(screen.getByRole('button', { name: ko.start.createRoom }))
  await waitFor(() => expect(env.sockets.sockets.length).toBe(1))
  const socket = env.sockets.last
  act(() => socket.open())
  await waitFor(() => expect(socket.sentOfType('create')).toHaveLength(1))
  deliver(socket, { type: 'joined', code: 'QWERT', name: '하루' })
  deliver(socket, roster('lobby'))
  deliver(socket, roster('playing'))
  deliver(socket, { type: 'prepare', index: 0, total: 5, audioUrl: '/audio/abc' })
  return socket
}

// The server starts the song (it does not wait for a slow client's ready).
function deliverStart(socket, index = 0) {
  const serverTime = T0 + 500
  deliver(socket, { type: 'start', index, startAt: serverTime, deadlineAt: serverTime + 90_000, serverTime })
}

function discArea() {
  const area = document.querySelector('.disc-area')
  expect(area).not.toBeNull()
  return area
}

// The record and all seven equalizer bars are rendered inside the disc area.
function expectDiscDrawn() {
  const area = discArea()
  expect(area.querySelector('.record')).not.toBeNull()
  const eq = area.querySelector('.eq')
  expect(eq).not.toBeNull()
  expect(eq.querySelectorAll('span')).toHaveLength(7)
}

async function settle() {
  await act(async () => {
    await new Promise((resolve) => setTimeout(resolve, 50))
  })
}

describe('loading', () => {
  it('a pending decode after start: data-paused="true"; after the decode and the source start: absent', async () => {
    const env = setup({ ctxOptions: { deferDecode: true } })
    const socket = await gamePrepared(env)
    const ctx = env.audio.last
    await waitFor(() => expect(ctx.pendingDecodes).toHaveLength(1))
    deliverStart(socket)
    expect(screen.getByText(ko.stage.loading)).toBeTruthy()
    expectDiscDrawn()
    expect(discArea().getAttribute('data-paused')).toBe('true')
    await act(async () => ctx.resolveDecode())
    await waitFor(() => expect(ctx.startedSources()).toHaveLength(1))
    await waitFor(() => expect(discArea().hasAttribute('data-paused')).toBe(false))
    expectDiscDrawn()
  })

  it('a pending fetch after start: data-paused="true"; after the fetch and the source start: absent', async () => {
    const fetch = createDeferredFetch()
    const env = setup({ fetch })
    const socket = await gamePrepared(env)
    await waitFor(() => expect(fetch.pending).toHaveLength(1))
    deliverStart(socket)
    expectDiscDrawn()
    expect(discArea().getAttribute('data-paused')).toBe('true')
    await settle()
    // Still loading: still paused.
    expect(discArea().getAttribute('data-paused')).toBe('true')
    await act(async () => fetch.release())
    const ctx = env.audio.last
    await waitFor(() => expect(ctx.startedSources()).toHaveLength(1))
    await waitFor(() => expect(discArea().hasAttribute('data-paused')).toBe(false))
    expectDiscDrawn()
  })

  it('a song loaded before start: the attribute is absent once it plays', async () => {
    const env = setup()
    const socket = await gamePrepared(env)
    await waitFor(() => expect(socket.sentOfType('ready')).toEqual([{ type: 'ready', index: 0 }]))
    deliverStart(socket)
    await waitFor(() => expect(env.audio.last.startedSources()).toHaveLength(1))
    expectDiscDrawn()
    expect(discArea().hasAttribute('data-paused')).toBe(false)
  })
})

describe('failed load', () => {
  it('decode rejected twice after start keeps data-paused="true"', async () => {
    const env = setup({ ctxOptions: { deferDecode: true } })
    const socket = await gamePrepared(env)
    const ctx = env.audio.last
    await waitFor(() => expect(ctx.pendingDecodes).toHaveLength(1))
    deliverStart(socket)
    expect(discArea().getAttribute('data-paused')).toBe('true')
    await act(async () => ctx.rejectDecode())
    // The one retry decodes again.
    await waitFor(() => expect(ctx.pendingDecodes).toHaveLength(1))
    await act(async () => ctx.rejectDecode())
    await waitFor(() => expect(screen.getByText(ko.stage.loadFailed)).toBeTruthy())
    await settle()
    expect(ctx.startedSources()).toEqual([])
    expectDiscDrawn()
    expect(discArea().getAttribute('data-paused')).toBe('true')
  })

  it('fetch rejected twice, then start: data-paused="true"', async () => {
    const env = setup({ fetchOptions: { failures: 2, failWith: 'reject' } })
    const socket = await gamePrepared(env)
    await waitFor(() => expect(socket.sentOfType('ready')).toEqual([{ type: 'ready', index: 0 }]))
    expect(screen.getByText(ko.stage.loadFailed)).toBeTruthy()
    deliverStart(socket)
    await settle()
    expect(env.audio.last.startedSources()).toEqual([])
    expectDiscDrawn()
    expect(discArea().getAttribute('data-paused')).toBe('true')
  })

  it('fetch answered 500 twice, then start: data-paused="true"', async () => {
    const env = setup({ fetchOptions: { failures: 2, failWith: 'status' } })
    const socket = await gamePrepared(env)
    await waitFor(() => expect(socket.sentOfType('ready')).toEqual([{ type: 'ready', index: 0 }]))
    deliverStart(socket)
    await settle()
    expect(env.audio.last.startedSources()).toEqual([])
    expectDiscDrawn()
    expect(discArea().getAttribute('data-paused')).toBe('true')
  })
})
