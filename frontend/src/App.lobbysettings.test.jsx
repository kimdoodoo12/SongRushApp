// Cycle 116 (D-124, critic H1 of 114): the host's lobby selection is shared
// with every member while the lobby is open, so a guest's locked card is
// true (docs/cycles/116-lobby-settings.md, Protocol, Frontend and
// Acceptance 2).
//
// Contract with the implementer:
// - The message, both ways, is the start message's fields with type
//   'lobbySettings': { type: 'lobbySettings', songCount: <number>,
//   ...toFields(selection) }, that is types: ['OP'|'ED'], yearFrom, yearTo
//   (numbers), tier: 'easy'|'hard', answerMode: 'choice', gameMode:
//   'ranked', each present only when not the default (as in start and
//   settings).
// - Host: sends it once on entering the lobby as host, once after each
//   lobby control change, and once when a roster turns it from guest into
//   host. A roster that keeps it host sends nothing. A guest never sends it.
// - Guest: the stepper output, the .seg groups, the .years selects, the .oh
//   hints and the header .settings line read the received message (a
//   missing field is the default). Before any message: '—' (U+2014) in the
//   stepper output, no pressed segment, and .opts-who reads
//   '방장이 설정을 고르는 중이에요'. Leaving the room clears it.
// Timer rule (cycle 039): real timers and waitFor.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, act, cleanup } from '@testing-library/react'
import App from './App.jsx'
import { createSocketFactory, createAudioContextFactory, createFakeFetch } from './testing/fakes.js'
import {
  pressedIn,
  segGroup,
  setAnswerMode,
  setGameMode,
  setSongCount,
  setTier,
  setType,
  setYearFrom,
  setYearTo,
  songCount,
  songCountOutput,
  startGame,
  yearFrom,
  yearTo,
} from './testing/lobby.js'

const T0 = 1_000_000
const DASH = String.fromCharCode(0x2014)
const WHO_GUEST = '방장이 정한 설정이에요'
const WHO_PENDING = '방장이 설정을 고르는 중이에요'
const HINT_CHAT = '채팅으로 애니 제목을 맞혀요'
const HINT_CHOICE_4 = '표지 4장 중에서 골라요 (난이도에 따라 4·6·8장)'
const HINT_CHOICE_8 = '표지 8장 중에서 골라요 (난이도에 따라 4·6·8장)'
const HINT_FIRST = '가장 먼저 맞힌 한 명만 점수를 받아요'
const HINT_RANKED = '모두 맞히거나 고를 때까지 곡이 이어지고 순위대로 점수를 받아요'
const GROUPS = ['곡 종류', '난이도', '정답 형식', '게임 모드']

// The full selection the tests send to a guest.
const FULL = {
  type: 'lobbySettings',
  songCount: 12,
  types: ['OP'],
  yearFrom: 2000,
  yearTo: 2009,
  tier: 'easy',
  answerMode: 'choice',
  gameMode: 'ranked',
}

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

function q(selector) {
  return document.querySelector(selector)
}

function text(selector) {
  const el = q(selector)
  expect(el, selector).not.toBeNull()
  return el.textContent.trim()
}

const player = (name, { host = false, connected = true } = {}) => ({ name, score: 0, host, connected })
const roster = (players, phase = 'lobby') => ({ type: 'roster', phase, players })
const HOST_PLAYERS = [player('하루', { host: true }), player('Joon')]
const GUEST_PLAYERS = [player('Mina', { host: true }), player('Hana'), player('Joon')]
// Mina left: Hana is the earliest connected member, so the host.
const HANA_HOSTS = [player('Hana', { host: true }), player('Joon')]

async function openSocket(env, count = 1) {
  await waitFor(() => expect(env.sockets.sockets.length).toBe(count))
  const socket = env.sockets.last
  act(() => socket.open())
  await waitFor(() => expect(socket.sent.length).toBeGreaterThan(0))
  return socket
}

// 방 만들기 as 하루: joined QWERT and a lobby roster with 하루 as host.
async function hostLobby(env, { count = 1, players = HOST_PLAYERS } = {}) {
  fireEvent.change(screen.getByLabelText('닉네임'), { target: { value: '하루' } })
  fireEvent.click(screen.getByRole('button', { name: '방 만들기' }))
  const socket = await openSocket(env, count)
  deliver(socket, { type: 'joined', code: 'QWERT', name: '하루' })
  deliver(socket, roster(players))
  return socket
}

// 참가 to ABCDE as Hana: joined, then `players` (Mina hosts) unless null.
async function guestLobby(env, { count = 1, players = GUEST_PLAYERS } = {}) {
  fireEvent.change(screen.getByLabelText('닉네임'), { target: { value: 'Hana' } })
  fireEvent.change(screen.getByLabelText('방 코드'), { target: { value: 'ABCDE' } })
  fireEvent.click(screen.getByRole('button', { name: '참가' }))
  const socket = await openSocket(env, count)
  deliver(socket, { type: 'joined', code: 'ABCDE', name: 'Hana' })
  if (players !== null) deliver(socket, roster(players))
  return socket
}

// The lobbySettings messages the socket has sent.
function sentSettings(socket) {
  return socket.sentOfType('lobbySettings')
}

function lastSent(socket) {
  const all = sentSettings(socket)
  return all[all.length - 1]
}

// The .oh hint under the group `name`, or null.
function hintOf(name) {
  const hint = segGroup(name).closest('.opt').querySelector('.oh')
  return hint === null ? null : hint.textContent.trim()
}

// After `act`, exactly one more lobbySettings went out, equal to `expected`.
async function sendsOne(socket, act, expected) {
  const before = sentSettings(socket).length
  act()
  await waitFor(() => expect(sentSettings(socket).length).toBe(before + 1))
  expect(lastSent(socket)).toEqual(expected)
}

describe('the host sends lobbySettings', () => {
  it('once on entering the lobby as host, with the default selection', async () => {
    const env = setup()
    const socket = await hostLobby(env)
    await waitFor(() => expect(sentSettings(socket)).toHaveLength(1))
    expect(lastSent(socket)).toEqual({ type: 'lobbySettings', songCount: 10 })
    // Another roster that keeps 하루 host sends nothing more.
    deliver(socket, roster([...HOST_PLAYERS, player('Sora')]))
    await new Promise((resolve) => setTimeout(resolve, 20))
    expect(sentSettings(socket)).toHaveLength(1)
  })

  it('after each control change, with the fields of start', async () => {
    const env = setup()
    const socket = await hostLobby(env)
    await waitFor(() => expect(sentSettings(socket)).toHaveLength(1))

    await sendsOne(socket, () => setSongCount(11), { type: 'lobbySettings', songCount: 11 })
    await sendsOne(socket, () => setType('오프닝만'), { type: 'lobbySettings', songCount: 11, types: ['OP'] })
    await sendsOne(socket, () => setTier('쉬움'), { type: 'lobbySettings', songCount: 11, types: ['OP'], tier: 'easy' })
    await sendsOne(socket, () => setYearFrom('2000'), {
      type: 'lobbySettings',
      songCount: 11,
      types: ['OP'],
      yearFrom: 2000,
      tier: 'easy',
    })
    await sendsOne(socket, () => setYearTo('2009'), {
      type: 'lobbySettings',
      songCount: 11,
      types: ['OP'],
      yearFrom: 2000,
      yearTo: 2009,
      tier: 'easy',
    })
    await sendsOne(socket, () => setAnswerMode('객관식'), {
      type: 'lobbySettings',
      songCount: 11,
      types: ['OP'],
      yearFrom: 2000,
      yearTo: 2009,
      tier: 'easy',
      answerMode: 'choice',
    })
    await sendsOne(socket, () => setGameMode('순위전'), {
      type: 'lobbySettings',
      songCount: 11,
      types: ['OP'],
      yearFrom: 2000,
      yearTo: 2009,
      tier: 'easy',
      answerMode: 'choice',
      gameMode: 'ranked',
    })
    // Back to the defaults: the fields drop out again.
    await sendsOne(socket, () => setType('엔딩만'), {
      type: 'lobbySettings',
      songCount: 11,
      types: ['ED'],
      yearFrom: 2000,
      yearTo: 2009,
      tier: 'easy',
      answerMode: 'choice',
      gameMode: 'ranked',
    })
    await sendsOne(socket, () => setGameMode('선착순'), {
      type: 'lobbySettings',
      songCount: 11,
      types: ['ED'],
      yearFrom: 2000,
      yearTo: 2009,
      tier: 'easy',
      answerMode: 'choice',
    })
  })

  it('the start message carries the same fields as the last lobbySettings', async () => {
    const env = setup()
    const socket = await hostLobby(env)
    setSongCount(7)
    setType('엔딩만')
    setTier('어려움')
    await waitFor(() => expect(lastSent(socket)).toEqual({ type: 'lobbySettings', songCount: 7, types: ['ED'], tier: 'hard' }))
    startGame()
    await waitFor(() => expect(socket.sentOfType('start')).toHaveLength(1))
    expect({ ...socket.sentOfType('start')[0], type: 'lobbySettings' }).toEqual(lastSent(socket))
  })

  it('when a roster makes a guest the host, its own selection', async () => {
    const env = setup()
    const socket = await guestLobby(env)
    deliver(socket, FULL)
    expect(sentSettings(socket)).toEqual([])
    deliver(socket, roster(HANA_HOSTS))
    await waitFor(() => expect(sentSettings(socket)).toHaveLength(1))
    // Hana's own selection, never the received one.
    expect(lastSent(socket)).toEqual({ type: 'lobbySettings', songCount: 10 })
    // As host, its controls are enabled and read its own selection.
    expect(songCount()).toBe(10)
    expect(songCountOutput().closest('.stepper').querySelector('button:not([disabled])')).not.toBeNull()
    await sendsOne(socket, () => setType('오프닝만'), { type: 'lobbySettings', songCount: 10, types: ['OP'] })
  })

  it('on entering the lobby again after a game (대기실로)', async () => {
    const env = setup()
    const socket = await hostLobby(env)
    await waitFor(() => expect(sentSettings(socket)).toHaveLength(1))
    startGame()
    await waitFor(() => expect(socket.sentOfType('start')).toHaveLength(1))
    deliver(socket, roster(HOST_PLAYERS, 'playing'))
    deliver(socket, { type: 'prepare', index: 0, total: 5, audioUrl: '/audio/s0' })
    await waitFor(() => expect(socket.sentOfType('ready')).toHaveLength(1))
    deliver(socket, {
      type: 'reveal',
      index: 0,
      reveal: '진격의 거인',
      titles: { ko: ['진격의 거인'], en: ['Attack on Titan'], ja: ['進撃の巨人'], romaji: ['Shingeki no Kyojin'] },
      song: '紅蓮の弓矢',
      artists: ['Revo'],
      themeType: 'OP',
      themeSlug: 'OP1',
      winner: null,
    })
    deliver(socket, {
      type: 'gameOver',
      ranking: HOST_PLAYERS.map((p, i) => ({ rank: i + 1, name: p.name, score: 0 })),
    })
    deliver(socket, roster(HOST_PLAYERS, 'lobby'))
    fireEvent.click(screen.getByRole('button', { name: '대기실로' }))
    await waitFor(() => expect(sentSettings(socket).length).toBeGreaterThanOrEqual(2))
    expect(lastSent(socket)).toEqual({ type: 'lobbySettings', songCount: 10 })
  })

  it('a guest never sends lobbySettings', async () => {
    const env = setup()
    const socket = await guestLobby(env)
    deliver(socket, FULL)
    deliver(socket, roster([...GUEST_PLAYERS, player('Sora')]))
    await new Promise((resolve) => setTimeout(resolve, 20))
    expect(sentSettings(socket)).toEqual([])
  })
})

describe('a guest renders the received selection', () => {
  it('song count, type, difficulty, years, answer mode, game mode, hints and the header .settings line', async () => {
    const env = setup()
    const socket = await guestLobby(env)
    deliver(socket, FULL)
    expect(songCount()).toBe(12)
    expect(pressedIn('곡 종류')).toEqual(['오프닝만'])
    expect(pressedIn('난이도')).toEqual(['쉬움'])
    expect(pressedIn('정답 형식')).toEqual(['객관식'])
    expect(pressedIn('게임 모드')).toEqual(['순위전'])
    expect(yearFrom()).toBe('2000')
    expect(yearTo()).toBe('2009')
    expect(hintOf('정답 형식')).toBe(HINT_CHOICE_4)
    expect(hintOf('게임 모드')).toBe(HINT_RANKED)
    expect(text('header .roominfo .settings')).toBe('오프닝만 · 2000~2009 · 쉬움 · 객관식 · 순위전')
    expect(text('section.opts .opts-hd .opts-who')).toBe(WHO_GUEST)
    // Still locked.
    for (const c of document.querySelectorAll('section.opts .opt-grid button, section.opts .opt-grid select')) {
      expect(c.disabled, c.outerHTML).toBe(true)
    }
  })

  it('a later message replaces the shown values; missing fields are the defaults', async () => {
    const env = setup()
    const socket = await guestLobby(env)
    deliver(socket, FULL)
    deliver(socket, { type: 'lobbySettings', songCount: 25, types: ['ED'], tier: 'hard', answerMode: 'choice' })
    expect(songCount()).toBe(25)
    expect(pressedIn('곡 종류')).toEqual(['엔딩만'])
    expect(pressedIn('난이도')).toEqual(['어려움'])
    expect(pressedIn('정답 형식')).toEqual(['객관식'])
    expect(pressedIn('게임 모드')).toEqual(['선착순'])
    expect(yearFrom()).toBe('')
    expect(yearTo()).toBe('')
    expect(hintOf('정답 형식')).toBe(HINT_CHOICE_8)
    expect(hintOf('게임 모드')).toBe(HINT_FIRST)
    expect(text('header .roominfo .settings')).toBe('엔딩만 · 어려움 · 객관식')

    deliver(socket, { type: 'lobbySettings', songCount: 5 })
    expect(songCount()).toBe(5)
    expect(pressedIn('곡 종류')).toEqual(['전체'])
    expect(pressedIn('난이도')).toEqual(['보통'])
    expect(pressedIn('정답 형식')).toEqual(['채팅'])
    expect(pressedIn('게임 모드')).toEqual(['선착순'])
    expect(hintOf('정답 형식')).toBe(HINT_CHAT)
    expect(q('header .roominfo .settings')).toBeNull()
    expect(text('section.opts .opts-hd .opts-who')).toBe(WHO_GUEST)
  })
})

describe('a guest before any lobbySettings', () => {
  it('sees — in the stepper output, no pressed segment and the pending .opts-who', async () => {
    const env = setup()
    await guestLobby(env)
    expect(songCountOutput().textContent.trim()).toBe(DASH)
    for (const name of GROUPS) expect(pressedIn(name), name).toEqual([])
    expect(text('section.opts .opts-hd .opts-who')).toBe(WHO_PENDING)
    expect(q('header .roominfo .settings')).toBeNull()
    for (const c of document.querySelectorAll('section.opts .opt-grid button, section.opts .opt-grid select')) {
      expect(c.disabled, c.outerHTML).toBe(true)
    }
  })

  it('the selection is cleared on leaving: the next room starts pending again', async () => {
    const env = setup()
    const first = await guestLobby(env)
    deliver(first, FULL)
    expect(songCount()).toBe(12)
    fireEvent.click(screen.getByRole('button', { name: '나가기' }))
    await guestLobby(env, { count: 2 })
    expect(songCountOutput().textContent.trim()).toBe(DASH)
    for (const name of GROUPS) expect(pressedIn(name), name).toEqual([])
    expect(text('section.opts .opts-hd .opts-who')).toBe(WHO_PENDING)
    expect(q('header .roominfo .settings')).toBeNull()
  })
})
