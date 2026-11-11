// Cycle 050 (B-008): the own skip vote. The client counts the skipVote
// clicks it sent for the current song minus the rate-limited errors tagged
// for:'skipVote'; the parity is the own vote. An error without for (a chat
// refusal) never changes it, and skipState only updates the count
// (docs/cycles/050-own-skip-vote.md, criterion 2).
// Timer rule (cycle 039): real timers and waitFor.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, act, cleanup } from '@testing-library/react'
import App from './App.jsx'
import { createSocketFactory, createAudioContextFactory, createFakeFetch } from './testing/fakes.js'
import { joinRoomAs } from './testing/lobby.js'

const T0 = 1_000_000
const ANY_SKIP = /^스킵/

beforeEach(() => {
  window.history.pushState({}, '', '/')
})

afterEach(() => {
  cleanup()
  vi.useRealTimers()
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

const player = (name, { host = false } = {}) => ({ name, score: 0, host, connected: true })
const PLAYERS_3 = [player('Mina', { host: true }), player('Hana (2)'), player('Joon')]
const roster = (phase) => ({ type: 'roster', phase, players: PLAYERS_3 })
const prepare = (index) => ({ type: 'prepare', index, total: 5, audioUrl: `/audio/s${index}` })

function startMsg(index) {
  const serverTime = T0 + 500
  const startAt = serverTime + 3000
  return { type: 'start', index, startAt, deadlineAt: startAt + 90_000, serverTime }
}

const skipState = (votes, index = 0) => ({ type: 'skipState', index, votes, needed: 2 })
const SKIP_REFUSED = { type: 'error', code: 'rate-limited', for: 'skipVote' }
const CHAT_REFUSED = { type: 'error', code: 'rate-limited' }

const revealMsg = (index) => ({
  type: 'reveal',
  index,
  reveal: '진격의 거인',
  titles: { ko: ['진격의 거인'], en: ['Attack on Titan'], ja: ['進撃の巨人'], romaji: ['Shingeki no Kyojin'] },
  song: '紅蓮の弓矢',
  artists: ['Revo'],
  themeType: 'OP',
  themeSlug: 'OP1',
  winner: null,
})

// The label of the single skip button.
function skipLabel() {
  return screen.getByRole('button', { name: ANY_SKIP }).textContent
}

function clickSkip() {
  fireEvent.click(screen.getByRole('button', { name: ANY_SKIP }))
}

// Joined as Hana (2) in ABCDE, song 0 started and playing.
async function roomSongStarted(env) {
  joinRoomAs('Hana', 'ABCDE')
  await waitFor(() => expect(env.sockets.sockets.length).toBe(1))
  const socket = env.sockets.last
  act(() => socket.open())
  await waitFor(() => expect(socket.sent.length).toBeGreaterThan(0))
  deliver(socket, { type: 'joined', code: 'ABCDE', name: 'Hana (2)' })
  deliver(socket, roster('lobby'))
  deliver(socket, roster('playing'))
  deliver(socket, prepare(0))
  await waitFor(() => expect(socket.sentOfType('ready')).toHaveLength(1))
  deliver(socket, startMsg(0))
  await waitFor(() => expect(env.audio.last.startedSources()).toHaveLength(1))
  expect(skipLabel()).toBe('스킵')
  return socket
}

describe('own skip vote (B-008)', () => {
  it('two quick clicks, only the second refused: skipState{votes:1} then the tagged error leaves 스킵 취소', async () => {
    const env = setup()
    const socket = await roomSongStarted(env)
    clickSkip()
    clickSkip()
    expect(socket.sentOfType('skipVote')).toHaveLength(2)
    expect(skipLabel()).toBe('스킵')
    // The first click was counted, the second refused.
    deliver(socket, skipState(1))
    deliver(socket, SKIP_REFUSED)
    expect(skipLabel()).toBe('스킵 취소')
    expect(screen.getByText('스킵 1/2')).toBeTruthy()
  })

  it("someone else's skipState between my click and my refusal still undoes my toggle", async () => {
    const env = setup()
    const socket = await roomSongStarted(env)
    clickSkip()
    expect(skipLabel()).toBe('스킵 취소')
    // Joon's vote is counted; mine is refused after it.
    deliver(socket, skipState(1))
    expect(skipLabel()).toBe('스킵 취소')
    deliver(socket, SKIP_REFUSED)
    expect(skipLabel()).toBe('스킵')
    expect(screen.getByText('스킵 1/2')).toBeTruthy()
  })

  it('a chat rate-limited error (no for) after a click leaves 스킵 취소', async () => {
    const env = setup()
    const socket = await roomSongStarted(env)
    clickSkip()
    expect(skipLabel()).toBe('스킵 취소')
    deliver(socket, CHAT_REFUSED)
    expect(skipLabel()).toBe('스킵 취소')
    // The skipState for the click arrives later and changes only the count.
    deliver(socket, skipState(1))
    expect(skipLabel()).toBe('스킵 취소')
  })

  it('three clicks with the second refused show 스킵 (sent 3, refused 1)', async () => {
    const env = setup()
    const socket = await roomSongStarted(env)
    clickSkip()
    clickSkip()
    clickSkip()
    expect(socket.sentOfType('skipVote')).toHaveLength(3)
    deliver(socket, skipState(1))
    deliver(socket, SKIP_REFUSED)
    deliver(socket, skipState(0))
    expect(skipLabel()).toBe('스킵')
    expect(screen.getByText('스킵 0/2')).toBeTruthy()
  })

  it('the same three clicks read 스킵 whenever the refusal arrives', async () => {
    const env = setup()
    const socket = await roomSongStarted(env)
    clickSkip()
    clickSkip()
    clickSkip()
    deliver(socket, skipState(1))
    deliver(socket, skipState(0))
    deliver(socket, SKIP_REFUSED)
    expect(skipLabel()).toBe('스킵')
  })

  it('prepare resets the label to 스킵', async () => {
    const env = setup()
    const socket = await roomSongStarted(env)
    clickSkip()
    deliver(socket, skipState(1))
    expect(skipLabel()).toBe('스킵 취소')
    deliver(socket, revealMsg(0))
    deliver(socket, prepare(1))
    await waitFor(() => expect(socket.sentOfType('ready')).toHaveLength(2))
    deliver(socket, startMsg(1))
    await waitFor(() => expect(screen.getByRole('button', { name: ANY_SKIP })).toBeTruthy())
    expect(skipLabel()).toBe('스킵')
    // The new song counts from zero: one click is one vote, and a refusal undoes it.
    clickSkip()
    expect(skipLabel()).toBe('스킵 취소')
    deliver(socket, SKIP_REFUSED)
    expect(skipLabel()).toBe('스킵')
  })
})
