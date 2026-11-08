// Cycle 097 (F-022, D-103): the reveal card shows the anime's cover when the
// reveal carries one, easy's shortage message counts series, and the dev
// server proxies /cover.
//
// Contract (spec docs/cycles/097-reveal-cover-ui.md):
// - the session keeps a reveal's coverUrl only when it is a string matching
//   ^/cover/[A-Za-z0-9_-]{1,64}$; otherwise null;
// - with a cover the card holds a div.reveal-cover (classes h-32 and w-24)
//   with <img src={coverUrl} alt="">; after the img fires error the img is
//   removed and the div stays; with null there is neither;
// - nothing else stores or requests a cover: coverUrl fields on prepare,
//   start or snapshot frames are ignored;
// - not-enough-songs with unit "series" uses error.availableSeries.
// Timer rule (cycle 039): real timers and waitFor.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, act, cleanup, within } from '@testing-library/react'
import App from './App.jsx'
import ko from './i18n/ko.json'
import en from './i18n/en.json'
import ja from './i18n/ja.json'
import viteConfig from '../vite.config.js?raw'
import { createSocketFactory, createAudioContextFactory, createFakeFetch } from './testing/fakes.js'

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
  const card = heading.closest('section')
  expect(card, 'the reveal card should be a <section>').not.toBeNull()
  return card
}

function allImages() {
  return Array.from(document.querySelectorAll('img'))
}

function coverSlots() {
  return Array.from(document.querySelectorAll('.reveal-cover'))
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

describe('cover in the reveal card', () => {
  it('a reveal with coverUrl /cover/AbC_12-x renders one img with that src and alt="" inside the card', async () => {
    const env = setup()
    const socket = await songStarted(env)
    deliver(socket, revealMsg(0, { coverUrl: COVER }))
    const card = revealCard()
    const imgs = Array.from(card.querySelectorAll('img'))
    expect(imgs, 'one img in the reveal card').toHaveLength(1)
    expect(imgs[0].getAttribute('src')).toBe(COVER)
    expect(imgs[0].getAttribute('alt')).toBe('')
    expect(allImages()).toHaveLength(1)
  })

  it('with a cover, .reveal-cover exists in the card with h-32 and w-24 and holds the img', async () => {
    const env = setup()
    const socket = await songStarted(env)
    deliver(socket, revealMsg(0, { coverUrl: COVER }))
    const card = revealCard()
    const slots = Array.from(card.querySelectorAll('.reveal-cover'))
    expect(slots).toHaveLength(1)
    expect(coverSlots()).toHaveLength(1)
    expect(slots[0].classList.contains('h-32')).toBe(true)
    expect(slots[0].classList.contains('w-24')).toBe(true)
    expect(slots[0].querySelector('img')).not.toBeNull()
  })

  it.each([
    ['null', { coverUrl: null }],
    ['a missing field', {}],
    ['an absolute URL', { coverUrl: 'https://evil.example/x.png' }],
    ['a path escaping /cover/', { coverUrl: '/cover/../x' }],
    ['an audio path', { coverUrl: '/audio/abc' }],
  ])('a reveal with %s renders no img and no .reveal-cover', async (_name, cover) => {
    const env = setup()
    const socket = await songStarted(env)
    deliver(socket, revealMsg(0, cover))
    const card = revealCard()
    expect(within(card).getByRole('heading', { name: '진격의 거인' })).toBeTruthy()
    expect(card.querySelectorAll('img')).toHaveLength(0)
    expect(allImages()).toEqual([])
    expect(coverSlots()).toEqual([])
  })

  it('after an error event on the image the card has no img and .reveal-cover remains', async () => {
    const env = setup()
    const socket = await songStarted(env)
    deliver(socket, revealMsg(0, { coverUrl: COVER }))
    const card = revealCard()
    const img = card.querySelector('img')
    expect(img, 'the cover img before the error').not.toBeNull()
    fireEvent.error(img)
    await waitFor(() => expect(revealCard().querySelectorAll('img')).toHaveLength(0))
    expect(allImages()).toEqual([])
    const slots = Array.from(revealCard().querySelectorAll('.reveal-cover'))
    expect(slots).toHaveLength(1)
    expect(slots[0].classList.contains('h-32')).toBe(true)
    expect(slots[0].classList.contains('w-24')).toBe(true)
  })

  it('a failed cover does not hide the next round cover: the error state is keyed by coverUrl', async () => {
    const env = setup()
    const socket = await songStarted(env)
    deliver(socket, revealMsg(0, { coverUrl: COVER }))
    const first = revealCard().querySelector('img')
    expect(first, 'the first cover img before the error').not.toBeNull()
    fireEvent.error(first)
    await waitFor(() => expect(allImages()).toEqual([]))
    await playSong(env, socket, 1)
    deliver(socket, revealMsg(1, { coverUrl: OTHER_COVER }))
    const imgs = Array.from(revealCard().querySelectorAll('img'))
    expect(imgs).toHaveLength(1)
    expect(imgs[0].getAttribute('src')).toBe(OTHER_COVER)
  })

  it('the game-over screen keeps the last reveal card with its cover', async () => {
    const env = setup()
    const socket = await songStarted(env)
    deliver(socket, revealMsg(0, { coverUrl: COVER }))
    deliver(socket, { type: 'scores', scores: [{ name: 'Kai', score: 0 }, { name: 'Mina', score: 0 }] })
    deliver(socket, {
      type: 'gameOver',
      ranking: [
        { rank: 1, name: 'Kai', score: 0 },
        { rank: 1, name: 'Mina', score: 0 },
      ],
    })
    deliver(socket, roster('lobby'))
    expect(screen.getByRole('button', { name: ko.game.toLobby })).toBeTruthy()
    const card = revealCard()
    const imgs = Array.from(card.querySelectorAll('img'))
    expect(imgs).toHaveLength(1)
    expect(imgs[0].getAttribute('src')).toBe(COVER)
    expect(card.querySelectorAll('.reveal-cover')).toHaveLength(1)
  })
})

describe('no cover before the reveal', () => {
  it('prepare and start frames with coverUrl show no img and fetch no /cover/ URL; a reveal without coverUrl shows none either', async () => {
    const globalFetch = spyGlobalFetch()
    const env = setup()
    const socket = await songStarted(env, { coverUrl: COVER })
    expect(env.fetch.urls.map(String)).toContain('/audio/s0')
    expect(allImages()).toEqual([])
    expect(coverSlots()).toEqual([])
    expect(requestedUrls(env, globalFetch).filter((u) => u.includes('/cover/'))).toEqual([])
    // The reveal itself has no cover: nothing from the earlier frames is used.
    deliver(socket, revealMsg(0, {}))
    revealCard()
    expect(allImages()).toEqual([])
    expect(coverSlots()).toEqual([])
    expect(requestedUrls(env, globalFetch).filter((u) => u.includes('/cover/'))).toEqual([])
  })

  it('a playing snapshot with coverUrl on the frame and its song shows no img and fetches no /cover/ URL', async () => {
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
    expect(allImages()).toEqual([])
    expect(coverSlots()).toEqual([])
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
    fireEvent.click(screen.getByRole('button', { name: dict.lobby.startGame }))
    await waitFor(() => expect(socket.sentOfType('start')).toHaveLength(1))
    deliver(socket, { type: 'error', code: 'not-enough-songs', available: 4, unit: 'series' })
    expect(screen.getByText(SERIES[lang])).toBeTruthy()
    expect(screen.queryByText(SONGS[lang])).toBeNull()
  })

  it.each(LANGS)('%s: not-enough-songs without unit keeps the song message', async (lang, dict, label) => {
    const env = setup()
    chooseLanguage(label)
    const socket = await hostLobby(env, dict)
    fireEvent.click(screen.getByRole('button', { name: dict.lobby.startGame }))
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
