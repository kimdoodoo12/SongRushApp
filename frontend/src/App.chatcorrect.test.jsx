// Cycle 080 (D-094): the winning guess is shown in the chat log. The correct
// frame adds a system line '✔ <name> 정답!'; the winner's chat, which the
// server sends right after correct, goes just above that line. Every other
// chat line appends in arrival order. The stage banner '<name> 정답!' stays.
// Cycle 114 (D-124, declared): 곡 수 and the start button through
// src/testing/lobby.js (the stepper and .start-row .go).
// Timer rule (cycle 039): real timers and waitFor.
import { describe, it, expect, afterEach } from 'vitest'
import { render, screen, waitFor, act, cleanup } from '@testing-library/react'
import App from './App.jsx'
import { createSocketFactory, createAudioContextFactory, createFakeFetch } from './testing/fakes.js'
import { createRoomAs, setSongCount, startGame } from './testing/lobby.js'

const T0 = 1_000_000

afterEach(() => {
  cleanup()
})

// Renders App with fakes. now() is T0.
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
const PLAYERS = [player('하루', { host: true }), player('민수'), player('지수')]
const roster = (phase) => ({ type: 'roster', phase, players: PLAYERS })

// serverTime is T0 + 500; the song starts at once.
function startMsg() {
  const serverTime = T0 + 500
  return { type: 'start', index: 0, startAt: serverTime, deadlineAt: serverTime + 90_000, serverTime }
}

// 하루 creates a room with 민수 and 지수, starts a game of 5 and plays song 0.
async function playing(env) {
  createRoomAs('하루')
  await waitFor(() => expect(env.sockets.sockets.length).toBe(1))
  const socket = env.sockets.last
  act(() => socket.open())
  await waitFor(() => expect(socket.sentOfType('create')).toHaveLength(1))
  deliver(socket, { type: 'joined', code: 'QWERT', name: '하루' })
  deliver(socket, roster('lobby'))
  setSongCount(5)
  startGame()
  await waitFor(() => expect(socket.sentOfType('start')).toEqual([{ type: 'start', songCount: 5 }]))
  deliver(socket, roster('playing'))
  deliver(socket, { type: 'prepare', index: 0, total: 5, audioUrl: '/audio/abc' })
  await waitFor(() => expect(socket.sentOfType('ready')).toEqual([{ type: 'ready', index: 0 }]))
  deliver(socket, startMsg())
  return socket
}

// The chat log lines in order, whitespace collapsed (the check mark may sit
// in its own aria-hidden span). Cycle 110 (D-124, declared): each line is a
// div.msg in the chat's .msgs (the mockup's markup). Cycle 111 (D-124,
// declared): a chat line is read from its .t, as the .tm time sits beside
// it; the client-only system lines (.msg.sys without .correct: game start,
// song start, game over) are left out here and tested in App.deck.test.jsx.
function chatLines() {
  return Array.from(document.querySelectorAll('aside.chat .msgs .msg:not(.sys), aside.chat .msgs .msg.sys.correct')).map(
    (el) => (el.querySelector(':scope > .t') ?? el).textContent.replace(/\s+/g, ' ').trim(),
  )
}

describe('the correct frame in the chat log (D-094)', () => {
  it('the winner chat goes above the system line: 민수: 진격의 거인, then ✔ 민수 정답!', async () => {
    const env = setup()
    const socket = await playing(env)
    deliver(socket, { type: 'chat', name: '지수', text: '원피스' })
    deliver(socket, { type: 'correct', index: 0, name: '민수' })
    deliver(socket, { type: 'chat', name: '민수', text: '진격의 거인' })
    expect(chatLines()).toEqual(['지수: 원피스', '민수: 진격의 거인', '✔ 민수 정답!'])
  })

  it('the system line appears on correct alone, before the winner chat arrives', async () => {
    const env = setup()
    const socket = await playing(env)
    deliver(socket, { type: 'correct', index: 0, name: '민수' })
    expect(chatLines()).toEqual(['✔ 민수 정답!'])
  })

  it('the stage banner 민수 정답! is still found by getByText exactly once', async () => {
    const env = setup()
    const socket = await playing(env)
    deliver(socket, { type: 'correct', index: 0, name: '민수' })
    deliver(socket, { type: 'chat', name: '민수', text: '진격의 거인' })
    expect(screen.getByText('민수 정답!')).toBeTruthy()
    expect(screen.getAllByText('민수 정답!')).toHaveLength(1)
  })

  it('wrong guesses append in order and never add a system line', async () => {
    const env = setup()
    const socket = await playing(env)
    deliver(socket, { type: 'chat', name: '민수', text: '원피스' })
    deliver(socket, { type: 'chat', name: '지수', text: '나루토' })
    deliver(socket, { type: 'chat', name: '민수', text: '블리치' })
    expect(chatLines()).toEqual(['민수: 원피스', '지수: 나루토', '민수: 블리치'])
    expect(chatLines().some((line) => line.includes('정답!'))).toBe(false)
  })

  it('a chat from another name after correct appends after the system line', async () => {
    const env = setup()
    const socket = await playing(env)
    deliver(socket, { type: 'correct', index: 0, name: '민수' })
    deliver(socket, { type: 'chat', name: '지수', text: '원피스' })
    expect(chatLines()).toEqual(['✔ 민수 정답!', '지수: 원피스'])
  })

  it('after the winner chat is placed, later lines (any name) append after the system line', async () => {
    const env = setup()
    const socket = await playing(env)
    deliver(socket, { type: 'correct', index: 0, name: '민수' })
    deliver(socket, { type: 'chat', name: '민수', text: '진격의 거인' })
    deliver(socket, { type: 'chat', name: '지수', text: '아깝다' })
    deliver(socket, { type: 'chat', name: '민수', text: 'ㅋㅋ' })
    expect(chatLines()).toEqual(['민수: 진격의 거인', '✔ 민수 정답!', '지수: 아깝다', '민수: ㅋㅋ'])
  })
})
