// Cycle 111 (D-124): the deck and the reveal frame behave as in the mockup
// (docs/design/108-ui-design-reference.html: drawPlayer, startRound, reveal,
// finish, showCover, pixelate, say, sys). Spec docs/cycles/111-deck-reveal.md
// and "Reveal frame" in docs/cycles/110-room-port.md.
//
// Contract with the implementer:
// - src/components/drawPlayer.js exports `function drawPlayer(canvas, mood)`
//   (a named export). The game screen calls it with the `.disc > canvas` and
//   the current mood, and again on every mood change. This file mocks it.
// - .deck classes: `idle` before the first round (also while connecting) and
//   after gameOver; neither `idle` nor `revealed` while a song is prepared or
//   playing; `revealed` from the reveal frame until the next prepare. With a
//   cover it is added once the image loads; without one at once.
// - The cover frame (.deck .cover): before the reveal no img has a src, and
//   .noimg and .cap are empty. On reveal: img src = the reveal's coverUrl,
//   alt "<title> 표지"; .noimg holds the Korean title (the frame's reveal
//   text); .shot.missing when coverUrl is null or the image fails. On load
//   --ar = clamp(naturalWidth / naturalHeight, 0.56, 0.8) on .cover and
//   --peek = round(18 + ar * 30)% on .deck; with no image --ar 0.714 and
//   --peek 39%. The next prepare empties the frame again.
// - .cap: sakura and every other mood the title (no .rar badge); pixel
//   PLAYER 1 CLEAR! / STAGE CLEAR! / TIME OVER; neon SIGNAL FOUND · CH-0<n>
//   with n the song number. .stamp: 정답 with a winner, 통과 without.
// - pixelate (pixel mood, on the image load) shows canvas.pix while it runs;
//   under matchMedia('(prefers-reduced-motion: reduce)') it is skipped and
//   .pix stays hidden. A missing window.matchMedia counts as motion allowed.
// - Each chat line .msg is i, span.tm (local HH:MM at arrival), span.t.
// - Client-side system lines (.msg.sys, not .correct) in the current
//   language: 게임을 시작해요 at game start, <n>번째 곡 시작 when song n
//   becomes audible, 게임 종료 at game over.
// - On the game screen html[data-mood] is the room theme (sunset until the
//   roomTheme frame), also while connecting and rejoining: never the own pick.
// Timer rule (cycle 039): real timers and waitFor; Date alone is faked for .tm.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, act, cleanup, within } from '@testing-library/react'
import App from './App.jsx'
import ko from './i18n/ko.json'
import en from './i18n/en.json'
import { createSocketFactory, createAudioContextFactory, createFakeFetch } from './testing/fakes.js'

const drawSpy = vi.hoisted(() => vi.fn())
vi.mock('./components/drawPlayer.js', () => ({ drawPlayer: drawSpy, default: drawSpy }))

const T0 = 1_000_000
const COVER = '/cover/AbC_12-x'
const TITLE = '진격의 거인'
const TITLES = { ko: [TITLE], en: ['Attack on Titan'], ja: ['進撃の巨人'], romaji: ['Shingeki no Kyojin'] }
const MOOD_IDS = ['sunset', 'rain', 'sakura', 'neon', 'chalk', 'pixel', 'matsuri', 'manga']
const REJOIN_KEY = 'songrush.rejoin'
const OWN_THEME_KEY = 'songrush.roomTheme'
const HANGUL = /[ㄱ-ㆎ가-힣]/

beforeEach(() => {
  window.history.pushState({}, '', '/')
  window.localStorage.clear()
  window.sessionStorage.clear()
  drawSpy.mockClear()
})

afterEach(() => {
  // Back to Korean through the switcher, so the shared i18n instance does not
  // carry a language into the next test.
  const group = screen.queryByRole('group', { name: 'Language' })
  if (group !== null) {
    const korean = within(group).queryByRole('button', { name: '한국어' })
    if (korean !== null) fireEvent.click(korean)
  }
  cleanup()
  vi.useRealTimers()
  vi.restoreAllMocks()
  delete window.matchMedia
  window.history.pushState({}, '', '/')
  window.localStorage.clear()
  window.sessionStorage.clear()
})

function setup({ navigationType } = {}) {
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
      navigationType={navigationType}
    />,
  )
  return { sockets, audio, fetch }
}

function deliver(socket, msg) {
  act(() => socket.receive(msg))
}

function chooseLanguage(label) {
  const group = screen.getByRole('group', { name: 'Language' })
  fireEvent.click(within(group).getByRole('button', { name: label }))
}

const player = (name, { host = false } = {}) => ({ name, score: 0, host, connected: true })
const roster = (players, phase = 'lobby') => ({ type: 'roster', phase, players })
// Mina hosts; the player is Hana (2).
const PLAYERS = [player('Mina', { host: true }), player('Hana (2)'), player('Joon')]
const ME = 'Hana (2)'

async function openSocket(env, count = 1) {
  await waitFor(() => expect(env.sockets.sockets.length).toBe(count))
  const socket = env.sockets.last
  act(() => socket.open())
  await waitFor(() => expect(socket.sent.length).toBeGreaterThan(0))
  return socket
}

// Joins ABCDE as Hana through the labels of `dict`.
async function guestLobby(env, dict = ko) {
  fireEvent.change(screen.getByLabelText(dict.start.nickname), { target: { value: 'Hana' } })
  fireEvent.change(screen.getByLabelText(dict.start.roomCode), { target: { value: 'ABCDE' } })
  fireEvent.click(screen.getByRole('button', { name: dict.start.join }))
  const socket = await openSocket(env)
  deliver(socket, { type: 'joined', code: 'ABCDE', name: ME })
  deliver(socket, roster(PLAYERS))
  return socket
}

function startMsg(index) {
  const serverTime = T0 + 500
  return { type: 'start', index, startAt: serverTime, deadlineAt: serverTime + 90_000, serverTime }
}

// Song `index` prepared, ready sent, started and audible.
async function playSong(env, socket, index) {
  deliver(socket, { type: 'prepare', index, total: 5, audioUrl: `/audio/s${index}` })
  await waitFor(() => expect(socket.sentOfType('ready').filter((m) => m.index === index)).toHaveLength(1))
  deliver(socket, startMsg(index))
  await waitFor(() => expect(env.audio.last.startedSources()).toHaveLength(index + 1))
  await waitFor(() => expect(document.body.classList.contains('playing')).toBe(true))
}

// The lobby, then song 0 audible.
async function playing(env, dict = ko) {
  const socket = await guestLobby(env, dict)
  deliver(socket, roster(PLAYERS, 'playing'))
  await playSong(env, socket, 0)
  return socket
}

const revealMsg = (overrides = {}) => ({
  type: 'reveal',
  index: 0,
  reveal: TITLE,
  titles: TITLES,
  song: '紅蓮の弓矢',
  artists: ['Revo'],
  themeType: 'OP',
  themeSlug: 'OP1',
  winner: null,
  coverUrl: null,
  ...overrides,
})

function gameOver(socket) {
  deliver(socket, {
    type: 'gameOver',
    ranking: [
      { rank: 1, name: 'Mina', score: 1 },
      { rank: 2, name: ME, score: 0 },
      { rank: 2, name: 'Joon', score: 0 },
    ],
  })
}

function q(selector) {
  return document.querySelector(selector)
}

function qa(selector) {
  return Array.from(document.querySelectorAll(selector))
}

function deck() {
  const el = q('.now .deck')
  expect(el, 'the .deck').not.toBeNull()
  return el
}

function deckState() {
  const el = deck()
  return { idle: el.classList.contains('idle'), revealed: el.classList.contains('revealed') }
}

function cover() {
  const el = q('.deck .cover')
  expect(el, 'the .deck .cover').not.toBeNull()
  return el
}

function coverImg() {
  return q('.deck .cover .shot img')
}

// Every img with a src: the cover is never requested before the reveal.
function imagesWithSrc() {
  return qa('img').filter((img) => (img.getAttribute('src') ?? '') !== '')
}

function shotMissing() {
  return q('.deck .cover .shot').classList.contains('missing')
}

function text(selector) {
  const el = q(selector)
  expect(el, selector).not.toBeNull()
  return el.textContent
}

function ar() {
  return parseFloat(cover().style.getPropertyValue('--ar'))
}

function peek() {
  return deck().style.getPropertyValue('--peek').trim()
}

// Fires load on the cover img as if it had the given natural size.
function loadCover(width, height) {
  const img = coverImg()
  expect(img, 'the cover img').not.toBeNull()
  Object.defineProperty(img, 'naturalWidth', { configurable: true, value: width })
  Object.defineProperty(img, 'naturalHeight', { configurable: true, value: height })
  Object.defineProperty(img, 'complete', { configurable: true, value: true })
  fireEvent.load(img)
}

// The client-only system lines (not the ✔ correct line), in order.
function sysLines() {
  return qa('aside.chat .msgs .msg.sys:not(.correct)').map((el) => el.textContent.replace(/\s+/g, ' ').trim())
}

function discCanvas() {
  const el = q('.deck .player .disc > canvas')
  expect(el, '.disc > canvas').not.toBeNull()
  return el
}

function drewMood(mood) {
  const canvas = discCanvas()
  return drawSpy.mock.calls.some(([cv, m]) => cv === canvas && m === mood)
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

// jsdom has no layout and no canvas: .shot gets a 200 x 280 box, and every
// canvas a 2d context stub. Returns the canvases whose context was asked for.
function stubLayoutAndCanvas() {
  const original = Element.prototype.getBoundingClientRect
  vi.spyOn(Element.prototype, 'getBoundingClientRect').mockImplementation(function rect() {
    if (this.classList && this.classList.contains('shot')) {
      return { x: 0, y: 0, left: 0, top: 0, right: 200, bottom: 280, width: 200, height: 280, toJSON() {} }
    }
    return original.call(this)
  })
  const asked = []
  vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockImplementation(function getContext() {
    asked.push(this)
    return new Proxy(
      {},
      {
        get(target, prop) {
          if (prop in target) return target[prop]
          if (typeof prop !== 'string' || prop === 'then') return undefined
          return () => ({ addColorStop: () => {} })
        },
        set(target, prop, value) {
          target[prop] = value
          return true
        },
      },
    )
  })
  return asked
}

const pause = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

describe('deck classes', () => {
  it('idle while connecting and before the first round; none while prepared and playing; revealed after reveal; idle after gameOver', async () => {
    const env = setup()
    fireEvent.change(screen.getByLabelText(ko.start.nickname), { target: { value: 'Hana' } })
    fireEvent.change(screen.getByLabelText(ko.start.roomCode), { target: { value: 'ABCDE' } })
    fireEvent.click(screen.getByRole('button', { name: ko.start.join }))
    await waitFor(() => expect(env.sockets.sockets.length).toBe(1))
    expect(deckState(), 'connecting').toEqual({ idle: true, revealed: false })
    const socket = env.sockets.last
    act(() => socket.open())
    deliver(socket, { type: 'joined', code: 'ABCDE', name: ME })
    deliver(socket, roster(PLAYERS, 'playing'))
    deliver(socket, { type: 'prepare', index: 0, total: 5, audioUrl: '/audio/s0' })
    expect(deckState(), 'prepared').toEqual({ idle: false, revealed: false })
    await waitFor(() => expect(socket.sentOfType('ready')).toHaveLength(1))
    deliver(socket, startMsg(0))
    await waitFor(() => expect(document.body.classList.contains('playing')).toBe(true))
    expect(deckState(), 'playing').toEqual({ idle: false, revealed: false })
    deliver(socket, revealMsg())
    await waitFor(() => expect(deckState(), 'revealed').toEqual({ idle: false, revealed: true }))
    gameOver(socket)
    await waitFor(() => expect(deckState(), 'after gameOver').toEqual({ idle: true, revealed: false }))
  })

  it('revealed goes at the next prepare and is added again on the second reveal', async () => {
    const env = setup()
    const socket = await playing(env)
    deliver(socket, revealMsg())
    await waitFor(() => expect(deckState().revealed).toBe(true))
    deliver(socket, { type: 'prepare', index: 1, total: 5, audioUrl: '/audio/s1' })
    expect(deckState()).toEqual({ idle: false, revealed: false })
    await waitFor(() => expect(socket.sentOfType('ready').filter((m) => m.index === 1)).toHaveLength(1))
    deliver(socket, startMsg(1))
    await waitFor(() => expect(env.audio.last.startedSources()).toHaveLength(2))
    expect(deckState().revealed).toBe(false)

    const observer = new MutationObserver(() => {})
    observer.observe(deck(), { attributes: true, attributeFilter: ['class'], attributeOldValue: true })
    deliver(socket, revealMsg({ index: 1 }))
    await waitFor(() => expect(deckState()).toEqual({ idle: false, revealed: true }))
    const records = observer.takeRecords()
    observer.disconnect()
    expect(records.length, 'the class attribute changed on the second reveal').toBeGreaterThan(0)
  })

  it('with a cover, revealed is added once the image loads', async () => {
    const env = setup()
    const socket = await playing(env)
    deliver(socket, revealMsg({ coverUrl: COVER }))
    loadCover(460, 644)
    await waitFor(() => expect(deckState()).toEqual({ idle: false, revealed: true }))
  })
})

describe('the disc', () => {
  it('drawPlayer draws the .disc > canvas with the current mood and again on every mood change', async () => {
    const env = setup()
    const socket = await playing(env)
    await waitFor(() => expect(drewMood('sunset'), 'drawn for sunset').toBe(true))
    for (const id of ['pixel', 'manga', 'neon']) {
      const before = drawSpy.mock.calls.length
      deliver(socket, { type: 'roomTheme', theme: id })
      await waitFor(() => expect(drewMood(id), `drawn for ${id}`).toBe(true))
      expect(drawSpy.mock.calls.length).toBeGreaterThan(before)
      const last = drawSpy.mock.calls[drawSpy.mock.calls.length - 1]
      expect(last[0]).toBe(discCanvas())
      expect(last[1]).toBe(id)
    }
  })

  it('the disc canvas is 520 x 520', async () => {
    const env = setup()
    await playing(env)
    expect(discCanvas().width).toBe(520)
    expect(discCanvas().height).toBe(520)
  })
})

describe('the reveal frame', () => {
  it('before the reveal no img has a src and .noimg and .cap are empty; after it the img src is the coverUrl with alt "<title> 표지"', async () => {
    const env = setup()
    const socket = await playing(env)
    expect(imagesWithSrc()).toEqual([])
    expect(text('.deck .cover .noimg')).toBe('')
    expect(text('.deck .cover .cap')).toBe('')
    deliver(socket, revealMsg({ coverUrl: COVER }))
    const img = coverImg()
    expect(img).not.toBeNull()
    expect(img.getAttribute('src')).toBe(COVER)
    expect(img.getAttribute('alt')).toBe(`${TITLE} 표지`)
    expect(imagesWithSrc()).toEqual([img])
    expect(text('.deck .cover .noimg')).toBe(TITLE)
    expect(shotMissing()).toBe(false)
  })

  it.each([
    [460, 644, 0.714, '39%'],
    [1000, 1000, 0.8, '42%'],
    [300, 1000, 0.56, '35%'],
  ])('a %ix%i cover sets --ar %s on .cover and --peek %s on .deck after load', async (w, h, expectedAr, expectedPeek) => {
    const env = setup()
    const socket = await playing(env)
    deliver(socket, revealMsg({ coverUrl: COVER }))
    loadCover(w, h)
    await waitFor(() => expect(ar()).toBeCloseTo(expectedAr, 3))
    expect(peek()).toBe(expectedPeek)
  })

  it('a null cover: .shot.missing, .noimg holds the Korean title, --ar 0.714 and --peek 39%', async () => {
    const env = setup()
    const socket = await playing(env)
    deliver(socket, revealMsg({ coverUrl: null }))
    await waitFor(() => expect(shotMissing()).toBe(true))
    expect(text('.deck .cover .noimg')).toBe(TITLE)
    expect(imagesWithSrc()).toEqual([])
    expect(ar()).toBeCloseTo(0.714, 3)
    expect(peek()).toBe('39%')
  })

  it.each(MOOD_IDS.filter((m) => m !== 'pixel' && m !== 'neon'))('%s: .cap reads the title, with no rarity badge', async (mood) => {
    const env = setup()
    const socket = await playing(env)
    deliver(socket, { type: 'roomTheme', theme: mood })
    deliver(socket, revealMsg({ winner: 'Mina' }))
    await waitFor(() => expect(text('.deck .cover .cap')).toBe(TITLE))
    expect(q('.deck .cap .rar')).toBeNull()
  })

  it.each([
    ['the own win', 'PLAYER 1 CLEAR!', ME],
    ["another player's win", 'STAGE CLEAR!', 'Mina'],
    ['no winner', 'TIME OVER', null],
  ])('pixel: .cap after %s reads %s', async (_name, cap, winner) => {
    const env = setup()
    const socket = await playing(env)
    deliver(socket, { type: 'roomTheme', theme: 'pixel' })
    if (winner !== null) deliver(socket, { type: 'correct', index: 0, name: winner })
    deliver(socket, revealMsg({ winner }))
    await waitFor(() => expect(text('.deck .cover .cap')).toBe(cap))
  })

  it('neon: .cap reads SIGNAL FOUND · CH-0<n> with the song number', async () => {
    const env = setup()
    const socket = await playing(env)
    deliver(socket, { type: 'roomTheme', theme: 'neon' })
    deliver(socket, revealMsg())
    await waitFor(() => expect(text('.deck .cover .cap')).toBe('SIGNAL FOUND · CH-01'))
    deliver(socket, { type: 'prepare', index: 1, total: 5, audioUrl: '/audio/s1' })
    await waitFor(() => expect(socket.sentOfType('ready').filter((m) => m.index === 1)).toHaveLength(1))
    deliver(socket, startMsg(1))
    await waitFor(() => expect(env.audio.last.startedSources()).toHaveLength(2))
    deliver(socket, revealMsg({ index: 1 }))
    await waitFor(() => expect(text('.deck .cover .cap')).toBe('SIGNAL FOUND · CH-02'))
  })

  it('.stamp reads 정답 with a winner and 통과 without one', async () => {
    const env = setup()
    const socket = await playing(env)
    deliver(socket, { type: 'correct', index: 0, name: 'Mina' })
    deliver(socket, revealMsg({ winner: 'Mina' }))
    await waitFor(() => expect(text('.deck .cover .stamp')).toBe('정답'))
    deliver(socket, { type: 'prepare', index: 1, total: 5, audioUrl: '/audio/s1' })
    await waitFor(() => expect(socket.sentOfType('ready').filter((m) => m.index === 1)).toHaveLength(1))
    deliver(socket, startMsg(1))
    await waitFor(() => expect(env.audio.last.startedSources()).toHaveLength(2))
    deliver(socket, revealMsg({ index: 1, winner: null }))
    await waitFor(() => expect(text('.deck .cover .stamp')).toBe('통과'))
  })

  it('the next prepare empties the frame: no img src, empty .noimg and .cap, no title in the deck', async () => {
    const env = setup()
    const socket = await playing(env)
    deliver(socket, { type: 'roomTheme', theme: 'sakura' })
    deliver(socket, revealMsg({ coverUrl: COVER }))
    loadCover(460, 644)
    await waitFor(() => expect(text('.deck .cover .cap')).toBe(TITLE))
    deliver(socket, { type: 'prepare', index: 1, total: 5, audioUrl: '/audio/s1' })
    await waitFor(() => expect(imagesWithSrc()).toEqual([]))
    expect(text('.deck .cover .noimg')).toBe('')
    expect(text('.deck .cover .cap')).toBe('')
    expect(deck().textContent).not.toContain(TITLE)
  })
})

describe('pixelate', () => {
  it('pixel without reduced motion (no matchMedia, as in jsdom): canvas.pix is shown once the cover loads', async () => {
    expect(window.matchMedia).toBeUndefined()
    stubLayoutAndCanvas()
    const env = setup()
    const socket = await playing(env)
    deliver(socket, { type: 'roomTheme', theme: 'pixel' })
    deliver(socket, revealMsg({ coverUrl: COVER }))
    loadCover(460, 644)
    await waitFor(() => expect(q('.deck .cover .shot canvas.pix').hidden).toBe(false))
  })

  it('pixel under reduced motion: pixelate draws nothing and canvas.pix stays hidden', async () => {
    mockReducedMotion(true)
    const asked = stubLayoutAndCanvas()
    const env = setup()
    const socket = await playing(env)
    deliver(socket, { type: 'roomTheme', theme: 'pixel' })
    deliver(socket, revealMsg({ coverUrl: COVER }))
    loadCover(460, 644)
    await waitFor(() => expect(deckState().revealed).toBe(true))
    await act(async () => {
      await pause(300)
    })
    const pix = q('.deck .cover .shot canvas.pix')
    expect(pix).not.toBeNull()
    expect(pix.hidden).toBe(true)
    expect(asked.includes(pix), 'no 2d context asked of canvas.pix').toBe(false)
    expect(window.matchMedia).toHaveBeenCalledWith('(prefers-reduced-motion: reduce)')
  })
})

describe('chat line time', () => {
  it('each chat line has span.tm with the local HH:MM of its arrival, between i and .t', async () => {
    const env = setup()
    const socket = await playing(env)
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date(2026, 10, 11, 9, 5, 30))
    deliver(socket, { type: 'chat', name: 'Mina', text: '이거 뭐였지' })
    vi.setSystemTime(new Date(2026, 10, 11, 23, 41, 0))
    deliver(socket, { type: 'chat', name: 'Joon', text: '아님' })
    vi.useRealTimers()
    const lines = qa('aside.chat .msgs .msg:not(.sys)')
    expect(lines).toHaveLength(2)
    const parts = (line) => Array.from(line.children).map((c) => (c.tagName === 'I' ? 'i' : c.className))
    expect(parts(lines[0])).toEqual(['i', 'tm', 't'])
    expect(parts(lines[1])).toEqual(['i', 'tm', 't'])
    expect(lines[0].querySelector('.tm').textContent).toBe('09:05')
    expect(lines[1].querySelector('.tm').textContent).toBe('23:41')
    expect(lines[0].querySelector('.t').textContent).toBe('Mina: 이거 뭐였지')
    expect(lines[1].querySelector('.t').textContent).toBe('Joon: 아님')
  })
})

describe('system lines', () => {
  it('ko: 게임을 시작해요, then 1번째 곡 시작 when song 1 is audible, 2번째 곡 시작, and 게임 종료 at game over', async () => {
    const env = setup()
    const socket = await playing(env)
    await waitFor(() => expect(sysLines()).toEqual(['게임을 시작해요', '1번째 곡 시작']))
    deliver(socket, revealMsg())
    await playSong(env, socket, 1)
    await waitFor(() => expect(sysLines()).toEqual(['게임을 시작해요', '1번째 곡 시작', '2번째 곡 시작']))
    deliver(socket, revealMsg({ index: 1 }))
    gameOver(socket)
    await waitFor(() =>
      expect(sysLines()).toEqual(['게임을 시작해요', '1번째 곡 시작', '2번째 곡 시작', '게임 종료']),
    )
    for (const line of qa('aside.chat .msgs .msg.sys:not(.correct)')) {
      expect(line.textContent).not.toContain(TITLE)
    }
  })

  it('no song-start line before the song is audible', async () => {
    const env = setup()
    const socket = await guestLobby(env)
    deliver(socket, roster(PLAYERS, 'playing'))
    deliver(socket, { type: 'prepare', index: 0, total: 5, audioUrl: '/audio/s0' })
    expect(sysLines().some((l) => l.includes('번째 곡 시작'))).toBe(false)
  })

  it('en: the game-start and song-start lines are in English', async () => {
    const env = setup()
    chooseLanguage('English')
    await playing(env, en)
    await waitFor(() => expect(sysLines()).toHaveLength(2))
    const [gameStart, songStart] = sysLines()
    for (const line of [gameStart, songStart]) {
      expect(line).not.toMatch(HANGUL)
      expect(line).not.toBe('')
    }
    expect(songStart).toContain('1')
    expect(gameStart).not.toBe(songStart)
  })
})

describe('mood on the game screen', () => {
  function recordMood() {
    const observer = new MutationObserver(() => {})
    observer.observe(document.documentElement, {
      attributes: true,
      attributeFilter: ['data-mood'],
      attributeOldValue: true,
    })
    const seen = []
    return {
      // Every value data-mood has had since recording began, and the current one.
      values() {
        for (const record of observer.takeRecords()) seen.push(record.oldValue)
        return [...seen, document.documentElement.dataset.mood]
      },
      stop() {
        observer.disconnect()
      },
    }
  }

  it('a reload rejoin keeps data-mood the room theme (sunset, then the frame) and never takes the own pick', async () => {
    window.localStorage.setItem(OWN_THEME_KEY, 'neon')
    window.sessionStorage.setItem(REJOIN_KEY, JSON.stringify({ token: 'T1', code: 'ABCDE' }))
    document.documentElement.dataset.mood = 'none-yet'
    const record = recordMood()
    const env = setup({ navigationType: 'reload' })
    expect(document.documentElement.dataset.mood, 'while connecting').toBe('sunset')
    const socket = await openSocket(env)
    expect(socket.sent[0]).toEqual({ type: 'rejoin', token: 'T1' })
    expect(document.documentElement.dataset.mood, 'rejoin socket open').toBe('sunset')
    deliver(socket, { type: 'joined', code: 'ABCDE', name: '하루' })
    const startAt = T0 - 10_000
    deliver(socket, {
      type: 'snapshot',
      phase: 'playing',
      players: [player('하루', { host: true }), player('Mina')],
      song: { index: 0, total: 5, audioUrl: '/audio/re', startAt, deadlineAt: startAt + 90_000 },
      scores: [],
    })
    expect(document.documentElement.dataset.mood, 'after the snapshot').toBe('sunset')
    deliver(socket, { type: 'roomTheme', theme: 'pixel' })
    expect(document.documentElement.dataset.mood).toBe('pixel')
    expect(q('.stage-top .tag').textContent).toContain(ko.theme.pixel)
    const values = record.values()
    record.stop()
    expect(values, 'data-mood never took the own pick').not.toContain('neon')
  })

  it('creating a room: the start screen uses the own pick, the connecting game screen sunset', async () => {
    window.localStorage.setItem(OWN_THEME_KEY, 'neon')
    const env = setup()
    expect(document.documentElement.dataset.mood, 'start screen').toBe('neon')
    fireEvent.change(screen.getByLabelText(ko.start.nickname), { target: { value: 'Hana' } })
    fireEvent.click(screen.getByRole('button', { name: ko.start.createRoom }))
    await waitFor(() => expect(env.sockets.sockets.length).toBe(1))
    expect(q('.now .deck'), 'the game screen while connecting').not.toBeNull()
    expect(document.documentElement.dataset.mood, 'connecting').toBe('sunset')
    expect(q('.stage-top .tag').textContent).toContain(ko.theme.sunset)
  })
})
