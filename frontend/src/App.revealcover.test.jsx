// Cycle 097 (F-022, D-103): the reveal shows the anime's cover when the
// reveal carries one, easy's shortage message counts series, and the dev
// server proxies /cover.
//
// Contract (spec docs/cycles/097-reveal-cover-ui.md):
// - the session keeps a reveal's coverUrl only when it is a string matching
//   ^/cover/[A-Za-z0-9_-]{1,64}$; otherwise null;
// - nothing else stores or requests a cover: coverUrl fields on prepare,
//   start or snapshot frames are ignored;
// - not-enough-songs with unit "series" uses error.availableSeries.
// Cycle 111 (D-124, declared, spec docs/cycles/111-deck-reveal.md item 4):
// the cover moves from the card's .reveal-cover slot into the deck's frame,
// .deck .cover .shot (the mockup's showCover). There is no .reveal-cover
// any more and the .reveal card holds no img. The checks above are kept,
// against the frame:
// - with a kept coverUrl the frame's img has that src, no loading attribute,
//   and alt reveal.coverAlt with the Korean title: "<title> 표지" (ko),
//   "<title> cover" (en), "<title> 表紙" (ja);
// - with null, a refused URL, or after the img fires error, .shot is
//   .missing and .noimg shows the Korean title; no img has a src then;
// - the frame keeps its size (D-109 intent): .shot is there in every case,
//   and with no image --ar is 0.714 on .cover and --peek 39% on .deck;
// - before the reveal no img has a src and no /cover/ URL is requested.
// Cycle 114 (D-124, declared): the start button is .start-row .go, clicked
// through src/testing/lobby.js in every language.
// Timer rule (cycle 039): real timers and waitFor.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, act, cleanup, within } from '@testing-library/react'
import App from './App.jsx'
import ko from './i18n/ko.json'
import en from './i18n/en.json'
import ja from './i18n/ja.json'
import viteConfig from '../vite.config.js?raw'
import { createSocketFactory, createAudioContextFactory, createFakeFetch } from './testing/fakes.js'
import { startGame } from './testing/lobby.js'

const T0 = 1_000_000
const COVER = '/cover/AbC_12-x'
const OTHER_COVER = '/cover/zz9'

beforeEach(() => {
  window.history.pushState({}, '', '/')
  window.localStorage.clear()
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
  window.history.pushState({}, '', '/')
  vi.restoreAllMocks()
  window.localStorage.clear()
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

function chooseLanguage(label) {
  const group = screen.getByRole('group', { name: 'Language' })
  fireEvent.click(within(group).getByRole('button', { name: label }))
}

const player = (name, { host = false } = {}) => ({ name, score: 0, host, connected: true })
const PLAYERS = [player('Kai', { host: true }), player('Mina')]
const roster = (phase) => ({ type: 'roster', phase, players: PLAYERS })

// Creates room QWERT as Kai through the labels of `dict`; returns the socket
// with the lobby roster delivered.
async function hostLobby(env, dict) {
  fireEvent.change(screen.getByLabelText(dict.start.nickname), { target: { value: 'Kai' } })
  fireEvent.click(screen.getByRole('button', { name: dict.start.createRoom }))
  await waitFor(() => expect(env.sockets.sockets.length).toBe(1))
  const socket = env.sockets.last
  act(() => socket.open())
  await waitFor(() => expect(socket.sentOfType('create')).toHaveLength(1))
  deliver(socket, { type: 'joined', code: 'QWERT', name: 'Kai' })
  deliver(socket, roster('lobby'))
  return socket
}

const startFrame = (index, extra = {}) => {
  const serverTime = T0 + 500
  return { type: 'start', index, startAt: serverTime, deadlineAt: serverTime + 90_000, serverTime, ...extra }
}

// Song `index` prepared, ready sent and started. `extra` is added to the
// prepare and start frames.
async function playSong(env, socket, index, extra = {}) {
  deliver(socket, { type: 'prepare', index, total: 5, audioUrl: `/audio/s${index}`, ...extra })
  await waitFor(() => expect(socket.sentOfType('ready').filter((m) => m.index === index)).toHaveLength(1))
  deliver(socket, startFrame(index, extra))
  await waitFor(() => expect(env.audio.last.startedSources()).toHaveLength(index + 1))
}

// The lobby, then song 0 playing.
async function songStarted(env, extra = {}) {
  const socket = await hostLobby(env, ko)
  deliver(socket, roster('playing'))
  await playSong(env, socket, 0, extra)
  return socket
}

// A reveal frame; `cover` is spread in as given, so { } leaves the field out.
const revealMsg = (index, cover) => ({
  type: 'reveal',
  index,
  reveal: '진격의 거인',
  titles: { ko: ['진격의 거인'], en: ['Attack on Titan'] },
  song: '紅蓮の弓矢',
  artists: ['Revo'],
  themeType: 'OP',
  themeSlug: 'OP1',
  winner: null,
  ...cover,
})

function revealCard() {
  const heading = screen.getByText(ko.reveal.heading)
  const card = heading.closest('.reveal')
  expect(card, 'the reveal card should be the .reveal element').not.toBeNull()
  return card
}

// The deck's cover frame, and its .shot (always present: the box keeps its
// size, D-109 intent).
function frame() {
  const cover = document.querySelector('.deck .cover')
  expect(cover, 'the .deck .cover frame').not.toBeNull()
  const shot = cover.querySelector('.shot')
  expect(shot, 'the .shot in the cover frame').not.toBeNull()
  return { cover, shot }
}

// The frame's img, or null.
function frameImg() {
  return frame().shot.querySelector('img')
}

// Every img with a src: an img without one requests nothing.
function imagesWithSrc() {
  return Array.from(document.querySelectorAll('img')).filter((img) => (img.getAttribute('src') ?? '') !== '')
}

// The card holds no cover any more, and no .reveal-cover is left anywhere.
function expectNoCardCover() {
  expect(document.querySelectorAll('.reveal-cover')).toHaveLength(0)
  for (const card of document.querySelectorAll('.reveal')) expect(card.querySelector('img')).toBeNull()
}

// No image: .shot.missing, the Korean title in .noimg, no img with a src,
// and the frame's no-image size (--ar 0.714, --peek 39%).
function expectMissing() {
  const { cover, shot } = frame()
  expect(shot.classList.contains('missing'), '.shot.missing').toBe(true)
  expect(shot.querySelector('.noimg').textContent).toBe('진격의 거인')
  expect(imagesWithSrc()).toEqual([])
  expect(parseFloat(cover.style.getPropertyValue('--ar'))).toBeCloseTo(0.714, 3)
  expect(document.querySelector('.deck').style.getPropertyValue('--peek').trim()).toBe('39%')
  expectNoCardCover()
}

// Nothing of a cover in the frame: no img with a src, empty .noimg and .cap.
function expectEmptyFrame() {
  const { cover, shot } = frame()
  expect(imagesWithSrc()).toEqual([])
  expect(shot.querySelector('.noimg').textContent).toBe('')
  expect(cover.querySelector('.cap').textContent).toBe('')
  expect(document.querySelectorAll('.reveal-cover')).toHaveLength(0)
}

// Every URL requested through the injected fetch or a global fetch.
function requestedUrls(env, globalFetch) {
  const urls = env.fetch.urls.map(String)
  if (globalFetch !== null) {
    for (const call of globalFetch.mock.calls) urls.push(String(call[0]?.url ?? call[0]))
  }
  return urls
}

function spyGlobalFetch() {
  if (typeof globalThis.fetch !== 'function') return null
  return vi.spyOn(globalThis, 'fetch').mockImplementation(async () => {
    throw new Error('no network in tests')
  })
}

// The lobby through the labels of `dict`, then song 0 playing.
async function songStartedIn(env, dict) {
  const socket = await hostLobby(env, dict)
  deliver(socket, roster('playing'))
  await playSong(env, socket, 0)
  return socket
}

describe('cover in the deck frame', () => {
  it('a reveal with coverUrl /cover/AbC_12-x sets the frame img src; it is the only img with a src and the card holds none', async () => {
    const env = setup()
    const socket = await songStarted(env)
    deliver(socket, revealMsg(0, { coverUrl: COVER }))
    expect(revealCard().querySelector('dl.titles dd.ko').textContent).toBe('진격의 거인')
    const img = frameImg()
    expect(img, 'the frame img').not.toBeNull()
    expect(img.getAttribute('src')).toBe(COVER)
    expect(imagesWithSrc()).toEqual([img])
    expect(frame().shot.classList.contains('missing'), 'a showing cover is not .missing').toBe(false)
    expectNoCardCover()
  })

  it.each([
    ['ko', ko, '한국어', '진격의 거인 표지'],
    ['en', en, 'English', '진격의 거인 cover'],
    ['ja', ja, '日本語', '진격의 거인 表紙'],
  ])('%s: the frame img alt is reveal.coverAlt with the Korean title', async (_lang, dict, label, alt) => {
    const env = setup()
    chooseLanguage(label)
    const socket = await songStartedIn(env, dict)
    deliver(socket, revealMsg(0, { coverUrl: COVER }))
    expect(frameImg().getAttribute('alt')).toBe(alt)
  })

  it('the cover img has no loading attribute', async () => {
    const env = setup()
    const socket = await songStarted(env)
    deliver(socket, revealMsg(0, { coverUrl: COVER }))
    const imgs = imagesWithSrc()
    expect(imgs).toHaveLength(1)
    for (const img of imgs) {
      expect(img.hasAttribute('loading'), `img loading="${img.getAttribute('loading')}"`).toBe(false)
    }
  })

  it.each([
    ['null', { coverUrl: null }],
    ['a missing field', {}],
    ['an absolute URL', { coverUrl: 'https://evil.example/x.png' }],
    ['a path escaping /cover/', { coverUrl: '/cover/../x' }],
    ['an audio path', { coverUrl: '/audio/abc' }],
  ])('a reveal with %s shows no image: .shot.missing with the Korean title, no img src, the no-image size', async (_name, cover) => {
    const env = setup()
    const socket = await songStarted(env)
    deliver(socket, revealMsg(0, cover))
    expect(revealCard().querySelector('dl.titles dd.ko').textContent).toBe('진격의 거인')
    await waitFor(() => expect(frame().shot.classList.contains('missing')).toBe(true))
    expectMissing()
  })

  it('after an error event on the image the frame is .missing with the Korean title and the no-image size', async () => {
    const env = setup()
    const socket = await songStarted(env)
    deliver(socket, revealMsg(0, { coverUrl: COVER }))
    const img = frameImg()
    expect(img, 'the cover img before the error').not.toBeNull()
    fireEvent.error(img)
    await waitFor(() => expect(frame().shot.classList.contains('missing')).toBe(true))
    expectMissing()
  })

  it('a cover that fails in one round does not hide the next round cover', async () => {
    const env = setup()
    const socket = await songStarted(env)
    deliver(socket, revealMsg(0, { coverUrl: COVER }))
    const first = frameImg()
    expect(first, 'the first cover img before the error').not.toBeNull()
    fireEvent.error(first)
    await waitFor(() => expect(frame().shot.classList.contains('missing')).toBe(true))
    await playSong(env, socket, 1)
    deliver(socket, revealMsg(1, { coverUrl: OTHER_COVER }))
    await waitFor(() => expect(frame().shot.classList.contains('missing')).toBe(false))
    const imgs = imagesWithSrc()
    expect(imgs).toHaveLength(1)
    expect(imgs[0]).toBe(frameImg())
    expect(imgs[0].getAttribute('src')).toBe(OTHER_COVER)
  })
})

describe('no cover before the reveal', () => {
  it('prepare and start frames with coverUrl leave the frame empty and fetch no /cover/ URL; a reveal without coverUrl shows the no-image frame', async () => {
    const globalFetch = spyGlobalFetch()
    const env = setup()
    const socket = await songStarted(env, { coverUrl: COVER })
    expect(env.fetch.urls.map(String)).toContain('/audio/s0')
    expectEmptyFrame()
    expect(requestedUrls(env, globalFetch).filter((u) => u.includes('/cover/'))).toEqual([])
    // The reveal itself has no cover: nothing from the earlier frames is used.
    deliver(socket, revealMsg(0, {}))
    await waitFor(() => expect(frame().shot.classList.contains('missing')).toBe(true))
    expectMissing()
    expect(requestedUrls(env, globalFetch).filter((u) => u.includes('/cover/'))).toEqual([])
  })

  it('a playing snapshot with coverUrl on the frame and its song leaves the frame empty and fetches no /cover/ URL', async () => {
    const globalFetch = spyGlobalFetch()
    const env = setup()
    fireEvent.change(screen.getByLabelText(ko.start.nickname), { target: { value: 'Hana' } })
    fireEvent.change(screen.getByLabelText(ko.start.roomCode), { target: { value: 'ABCDE' } })
    fireEvent.click(screen.getByRole('button', { name: ko.start.join }))
    await waitFor(() => expect(env.sockets.sockets.length).toBe(1))
    const socket = env.sockets.last
    act(() => socket.open())
    await waitFor(() => expect(socket.sentOfType('join')).toHaveLength(1))
    deliver(socket, { type: 'joined', code: 'ABCDE', name: 'Hana' })
    const players = [player('Mina', { host: true }), player('Hana')]
    const startAt = T0 - 10_000
    deliver(socket, {
      type: 'snapshot',
      phase: 'playing',
      players,
      coverUrl: COVER,
      song: { index: 2, total: 5, audioUrl: '/audio/watch', startAt, deadlineAt: startAt + 90_000, coverUrl: COVER },
      scores: [],
    })
    deliver(socket, { type: 'roster', phase: 'playing', players })
    await waitFor(() => expect(env.fetch.urls.map(String)).toContain('/audio/watch'))
    expectEmptyFrame()
    expect(requestedUrls(env, globalFetch).filter((u) => u.includes('/cover/'))).toEqual([])
  })
})

describe('shortage wording', () => {
  const SERIES = {
    ko: '출제할 수 있는 작품이 4개뿐입니다.',
    en: 'Series available: 4',
    ja: '出題できる作品は4作品だけです。',
  }
  const SONGS = {
    ko: '출제할 수 있는 곡이 4곡뿐입니다.',
    en: 'Songs available: 4',
    ja: '出題できる曲は4曲だけです。',
  }
  const LANGS = [
    ['ko', ko, '한국어'],
    ['en', en, 'English'],
    ['ja', ja, '日本語'],
  ]

  it.each(LANGS)('%s: not-enough-songs with unit "series" shows the series message with the number', async (lang, dict, label) => {
    const env = setup()
    chooseLanguage(label)
    const socket = await hostLobby(env, dict)
    startGame()
    await waitFor(() => expect(socket.sentOfType('start')).toHaveLength(1))
    deliver(socket, { type: 'error', code: 'not-enough-songs', available: 4, unit: 'series' })
    expect(screen.getByText(SERIES[lang])).toBeTruthy()
    expect(screen.queryByText(SONGS[lang])).toBeNull()
  })

  it.each(LANGS)('%s: not-enough-songs without unit keeps the song message', async (lang, dict, label) => {
    const env = setup()
    chooseLanguage(label)
    const socket = await hostLobby(env, dict)
    startGame()
    await waitFor(() => expect(socket.sentOfType('start')).toHaveLength(1))
    deliver(socket, { type: 'error', code: 'not-enough-songs', available: 4 })
    expect(screen.getByText(SONGS[lang])).toBeTruthy()
    expect(screen.queryByText(SERIES[lang])).toBeNull()
  })
})

describe('dev proxy', () => {
  it("vite.config.js proxies /cover to http://localhost:8080", () => {
    expect(typeof viteConfig).toBe('string')
    expect(viteConfig).toMatch(/'\/audio':\s*\{\s*target:\s*'http:\/\/localhost:8080'\s*\}/)
    expect(viteConfig).toMatch(/'\/cover':\s*\{\s*target:\s*'http:\/\/localhost:8080'\s*\}/)
  })
})
