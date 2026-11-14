// Cycle 123 (F-033, D-130): the pick count and the wrong-pick chat line of a
// choice room. Spec docs/cycles/123-pick-count-ui.md, criteria 1 to 7.
//
// Contract with the implementer:
// - pickState{index, picked, total} for the current song of a choice room
//   shows a span.skipcount.pickcount in the panel's div.row, after the skip
//   button and its vote count. Its text is stage.pickCount:
//   선택 {{picked}}/{{total}} / Picked {{picked}}/{{total}} /
//   選択 {{picked}}/{{total}}. It shows only while the grid is shown, the song
//   has started and no reveal is up; a value received before the start shows
//   once the song has started. A watching late joiner has no skip button and
//   sees the count alone in the row.
// - A pickState for another index, or whose picked and total are not
//   integers with 0 <= picked <= total and total >= 1, changes nothing.
// - wrong{index, name} for the current song of a choice room adds one
//   client-side notice line (.msg.sys, not .correct) with chat.wrongLine:
//   U+2718 then "{{name}} 오답" / "{{name}} picked wrong" / "{{name}} 不正解".
//   Another index, or a name that is not a non-empty string, adds nothing.
// - In a chat room both frames are ignored. Neither type calls console.warn,
//   in any room, valid or not.
// - snapshot: song.pickState {picked, total} is read with the same
//   validation, on a reload and for a watching late joiner.
//
// The texts are literals here: the two i18n keys are new in this cycle, so a
// missing key fails on the missing UI, not on the import.
//
// Timer rule: fake timers in every test, as in App.reload.test.jsx and
// App.prefetch.test.jsx (the hold and the reload need them), driven by
// flushUntil / advance with the injected now() moved together with them.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, act, cleanup, within } from '@testing-library/react'
import App from './App.jsx'
import ko from './i18n/ko.json'
import { createSocketFactory, createAudioContextFactory, createFakeFetch } from './testing/fakes.js'
import { createRoomAs, joinRoomAs, setAnswerMode, startGame } from './testing/lobby.js'

const T0 = 1_000_000
const FAKE_TIMERS = { toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval'] }
const HOLD = 5000
// The synced offset the fake server reports through pong (server ahead 2.5 s).
const SYNC = 2500
const RTT = 20
const STORAGE_KEY = 'songrush.rejoin'
const ENTRY = { token: 'T1', code: 'ABCDE' }
const ANY_SKIP = /^스킵/
const tileName = (n) => `보기 ${n}`

// U+2718, built from its code point so the file holds no escape.
const CROSS = String.fromCharCode(0x2718)
const COUNT = {
  ko: (picked, total) => `선택 ${picked}/${total}`,
  en: (picked, total) => `Picked ${picked}/${total}`,
  ja: (picked, total) => `選択 ${picked}/${total}`,
}
const WRONG = {
  ko: (name) => `${CROSS} ${name} 오답`,
  en: (name) => `${CROSS} ${name} picked wrong`,
  ja: (name) => `${CROSS} ${name} 不正解`,
}

const COVERS4 = ['/cover/a1', '/cover/b2', '/cover/c3', '/cover/d4']
const COVERS6 = ['/cover/e5', '/cover/f6', '/cover/g7', '/cover/h8', '/cover/i9', '/cover/j10']
const choicesOf = (urls) => urls.map((coverUrl) => ({ coverUrl }))

// A stand-in for the browser's Image (as in App.choice.test.jsx); fire()
// settles it the way a real load or error would.
const images = []

class FakeImage {
  constructor() {
    this.onload = null
    this.onerror = null
    this.settled = null
    this.currentSrc = ''
    this.listeners = { load: [], error: [] }
    this.decodePromise = null
    images.push(this)
  }

  set src(value) {
    this.currentSrc = String(value)
  }

  get src() {
    return this.currentSrc
  }

  addEventListener(type, fn) {
    if (!this.listeners[type]) this.listeners[type] = []
    this.listeners[type].push(fn)
  }

  removeEventListener(type, fn) {
    if (!this.listeners[type]) return
    this.listeners[type] = this.listeners[type].filter((f) => f !== fn)
  }

  decode() {
    if (this.decodePromise === null) {
      this.decodePromise = new Promise((resolve, reject) => {
        this.decodeSettle = { resolve, reject }
      })
    }
    return this.decodePromise
  }

  fire(type) {
    if (this.settled !== null) throw new Error('image already settled')
    this.settled = type
    const event = { type, target: this, currentTarget: this }
    const handler = type === 'load' ? this.onload : this.onerror
    if (typeof handler === 'function') handler.call(this, event)
    for (const fn of [...(this.listeners[type] || [])]) fn.call(this, event)
    if (this.decodeSettle) {
      if (type === 'load') this.decodeSettle.resolve()
      else this.decodeSettle.reject(new Error('image failed'))
    }
  }
}

// The preloads whose src is one of `urls`, in creation order.
function preloadsOf(urls) {
  return images.filter((img) => urls.some((url) => img.src !== '' && img.src.endsWith(url)))
}

beforeEach(() => {
  images.length = 0
  vi.stubGlobal('Image', FakeImage)
  if (typeof window !== 'undefined' && window.Image !== FakeImage) window.Image = FakeImage
  window.history.pushState({}, '', '/')
  window.localStorage.clear()
  window.sessionStorage.clear()
  vi.useFakeTimers(FAKE_TIMERS)
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
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
  vi.useRealTimers()
  window.history.pushState({}, '', '/')
  window.localStorage.clear()
  window.sessionStorage.clear()
})

// Renders App with fakes. clock.t is what now() returns; navigationType is
// 'reload' in the reload tests.
function setup(navigationType) {
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
      navigationType={navigationType}
    />,
  )
  return { sockets, audio, fetch, clock }
}

function deliver(socket, msg) {
  act(() => socket.receive(msg))
}

// ---- fake-timer helpers ----

// Advances 0 ms (running pending microtasks) until cond() holds, at most max times.
async function flushUntil(cond, max = 20) {
  for (let i = 0; i < max && !cond(); i++) {
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0)
    })
  }
}

// Moves the injected clock and the fake timers forward together.
async function advance(env, ms) {
  env.clock.t += ms
  await act(async () => {
    await vi.advanceTimersByTimeAsync(ms)
  })
}

// Settles every pending preload of `urls` with load.
async function settlePreloads(urls) {
  await flushUntil(() => preloadsOf(urls).length === urls.length)
  expect(preloadsOf(urls)).toHaveLength(urls.length)
  await act(async () => {
    for (const img of preloadsOf(urls).filter((i) => i.settled === null)) img.fire('load')
  })
}

// Answers the latest ping, sent at the current clock, after RTT ms with the
// SYNC offset; the clock is left RTT ms later.
function answerLatestPing(env, socket) {
  const sent = socket.sentOfType('ping')
  expect(sent.length, 'a ping should have been sent').toBeGreaterThan(0)
  const ping = sent[sent.length - 1]
  const t0 = env.clock.t
  env.clock.t += RTT
  deliver(socket, { type: 'pong', id: ping.id, serverTime: t0 + RTT / 2 + SYNC })
}

function chooseLanguage(label) {
  const group = screen.getByRole('group', { name: 'Language' })
  fireEvent.click(within(group).getByRole('button', { name: label }))
}

// ---- frames ----

const player = (name, { host = false, score = 0, connected = true } = {}) => ({ name, score, host, connected })
const TRIO = [player('하루', { host: true }), player('Joon'), player('Mina')]
const GUEST_PLAYERS = [player('Mina', { host: true }), player('Joon'), player('Hana')]
const RELOAD_PLAYERS = [player('하루', { host: true }), player('Mina')]
const roster = (players, phase = 'lobby') => ({ type: 'roster', phase, players })

const prepareMsg = (index, urls, extra = {}) => ({
  type: 'prepare',
  index,
  total: 5,
  audioUrl: `/audio/s${index}`,
  ...(urls === null ? {} : { choices: choicesOf(urls) }),
  ...extra,
})

const pickState = (picked, total, index = 0) => ({ type: 'pickState', index, picked, total })
const wrongMsg = (name, index = 0) => ({ type: 'wrong', index, name })
const skipState = (votes, index = 0) => ({ type: 'skipState', index, votes, needed: 2 })

const revealMsg = (index, extra = {}) => ({
  type: 'reveal',
  index,
  reveal: '진격의 거인',
  titles: { ko: ['진격의 거인'], en: ['Attack on Titan'] },
  song: '紅蓮の弓矢',
  artists: ['Revo'],
  themeType: 'OP',
  themeSlug: 'OP1',
  winner: null,
  ...extra,
})

// A playing snapshot song that is 10 s in by the synced clock once the clock
// has moved RTT ms past now (see answerLatestPing). extra adds pickState.
function choiceSong(env, { index = 0, extra = {} } = {}) {
  const startAt = env.clock.t + RTT + SYNC - 10_000
  return {
    index,
    total: 5,
    audioUrl: '/audio/re',
    startAt,
    deadlineAt: startAt + 90_000,
    choices: choicesOf(COVERS4),
    picked: null,
    ...extra,
  }
}

const snapshot = (players, song) => ({ type: 'snapshot', phase: 'playing', players, song, scores: [] })

function readiesFor(socket, index) {
  return socket.sentOfType('ready').filter((m) => m.index === index)
}

// The server starts the song; the receipt offset is 500, so by the local
// clock the song starts now.
function deliverStart(env, socket, index = 0) {
  const serverTime = env.clock.t + 500
  deliver(socket, { type: 'start', index, startAt: serverTime, deadlineAt: serverTime + 90_000, serverTime })
}

// ---- what the screen shows ----

function qa(selector) {
  return Array.from(document.querySelectorAll(selector))
}

// The text of every pick count in the panel; [] when none shows.
function pickCounts() {
  return qa('.panel .pickcount').map((el) => el.textContent)
}

// The client-side system lines of the chat log (not the correct line).
function sysLines() {
  return qa('aside.chat .msgs .msg.sys:not(.correct)').map((el) => el.textContent.replace(/\s+/g, ' ').trim())
}

function allSysCount() {
  return qa('aside.chat .msgs .msg.sys').length
}

function revealShown() {
  return screen.queryByText(ko.reveal.heading) !== null
}

function koTiles(count) {
  return Array.from({ length: count }, (_, i) => screen.getByRole('button', { name: tileName(i + 1) }))
}

const MARKS = ['correct', 'wrong', 'dim', 'locked']

function marks(list) {
  return list.map((b) => {
    const found = MARKS.filter((m) => b.classList.contains(m))
    return found.length === 0 ? null : found.join(' ')
  })
}

// The console.warn calls made while `run` runs.
function warnsDuring(warn, run) {
  const before = warn.mock.calls.length
  run()
  return warn.mock.calls.slice(before)
}

// ---- rooms ----

// 방 만들기 as 하루, host of QWERT with Joon and Mina in the lobby.
async function hostLobby(env) {
  createRoomAs('하루')
  await flushUntil(() => env.sockets.sockets.length === 1)
  const socket = env.sockets.last
  act(() => socket.open())
  await flushUntil(() => socket.sent.length > 0)
  deliver(socket, { type: 'joined', code: 'QWERT', name: '하루' })
  deliver(socket, roster(TRIO))
  return socket
}

// A choice room as host 하루: song 0 prepared with COVERS4 and ready sent,
// not started.
async function choiceRoomPrepared(env) {
  const socket = await hostLobby(env)
  setAnswerMode(ko.answer.choice)
  startGame()
  await flushUntil(() => socket.sentOfType('start').length === 1)
  deliver(socket, roster(TRIO, 'playing'))
  deliver(socket, { type: 'settings', answerMode: 'choice' })
  deliver(socket, prepareMsg(0, COVERS4))
  await settlePreloads(COVERS4)
  await flushUntil(() => readiesFor(socket, 0).length === 1)
  expect(readiesFor(socket, 0)).toEqual([{ type: 'ready', index: 0 }])
  return socket
}

// The song at `index` starts and is audible: its tiles are enabled.
async function startSong(env, socket, { index = 0, sources = 1, tileCount = 4 } = {}) {
  deliverStart(env, socket, index)
  const ctx = env.audio.last
  await flushUntil(() => ctx.startedSources().length === sources)
  expect(ctx.startedSources()).toHaveLength(sources)
  await advance(env, 300)
  expect(koTiles(tileCount).map((b) => b.disabled)).toEqual(Array(tileCount).fill(false))
}

// choiceRoomPrepared, then started and audible.
async function choiceSongAudible(env) {
  const socket = await choiceRoomPrepared(env)
  await startSong(env, socket)
  return socket
}

// A chat room as host 하루: song 0 started and audible.
async function chatSongAudible(env) {
  const socket = await hostLobby(env)
  startGame()
  await flushUntil(() => socket.sentOfType('start').length === 1)
  deliver(socket, roster(TRIO, 'playing'))
  deliver(socket, { type: 'settings', answerMode: 'chat' })
  deliver(socket, prepareMsg(0, null))
  await flushUntil(() => readiesFor(socket, 0).length === 1)
  expect(readiesFor(socket, 0)).toEqual([{ type: 'ready', index: 0 }])
  deliverStart(env, socket)
  const ctx = env.audio.last
  await flushUntil(() => ctx.startedSources().length === 1)
  expect(ctx.startedSources()).toHaveLength(1)
  await advance(env, 300)
  expect(screen.getByRole('button', { name: ANY_SKIP })).toBeTruthy()
  return socket
}

// 참가 to ABCDE as Hana while song 2 of a choice game plays: joined and a
// playing snapshot whose song is `song`. The song is not running yet.
async function lateJoiner(env, songExtra = {}) {
  joinRoomAs('Hana', 'ABCDE')
  await flushUntil(() => env.sockets.sockets.length === 1)
  const socket = env.sockets.last
  act(() => socket.open())
  await flushUntil(() => socket.sent.length > 0)
  deliver(socket, { type: 'joined', code: 'ABCDE', name: 'Hana' })
  deliver(socket, snapshot(GUEST_PLAYERS, choiceSong(env, { index: 2, extra: songExtra })))
  deliver(socket, roster(GUEST_PLAYERS, 'playing'))
  await flushUntil(() => env.audio.last.decoded.length === 1)
  expect(env.audio.last.decoded).toHaveLength(1)
  return socket
}

// A page reload as 하루 into ABCDE: the socket has sent rejoin{T1} and got
// joined.
async function reloadJoined(env) {
  await flushUntil(() => env.sockets.sockets.length === 1)
  expect(env.sockets.sockets).toHaveLength(1)
  const socket = env.sockets.last
  act(() => socket.open())
  await flushUntil(() => socket.sent.length > 0)
  expect(socket.sent).toEqual([{ type: 'rejoin', token: 'T1' }])
  deliver(socket, { type: 'joined', code: 'ABCDE', name: '하루' })
  return socket
}

// The playing snapshot of a reload, with the roster; the audio is decoded
// and no pong has been answered, so the song is not running yet.
async function reloadSnapshot(env, socket, songExtra = {}) {
  deliver(socket, snapshot(RELOAD_PLAYERS, choiceSong(env, { extra: songExtra })))
  deliver(socket, roster(RELOAD_PLAYERS, 'playing'))
  await flushUntil(() => env.audio.last.decoded.length === 1)
  expect(env.audio.last.decoded).toHaveLength(1)
  expect(koTiles(4)).toHaveLength(4)
}

// The pong that lets a snapshot's song start late; afterwards it is running.
async function runSnapshotSong(env, socket) {
  const ctx = env.audio.last
  answerLatestPing(env, socket)
  await flushUntil(() => ctx.startedSources().length === 1)
  expect(ctx.startedSources()).toHaveLength(1)
  await advance(env, 300)
  expect(screen.getByRole('progressbar')).toBeTruthy()
}

describe('the pick count of a choice song (criterion 1)', () => {
  it('pickState{picked:1,total:3} shows 선택 1/3 as span.skipcount.pickcount in the panel row; picked:2 updates it', async () => {
    const env = setup()
    const socket = await choiceSongAudible(env)
    expect(pickCounts()).toEqual([])
    deliver(socket, pickState(1, 3))
    expect(pickCounts()).toEqual([COUNT.ko(1, 3)])
    const count = document.querySelector('.panel .pickcount')
    expect(count.tagName).toBe('SPAN')
    expect(count.classList.contains('skipcount')).toBe(true)
    expect(count.parentElement.matches('.panel > div.row')).toBe(true)
    deliver(socket, pickState(2, 3))
    expect(pickCounts()).toEqual([COUNT.ko(2, 3)])
    // total follows the connected players.
    deliver(socket, pickState(2, 2))
    expect(pickCounts()).toEqual([COUNT.ko(2, 2)])
  })

  it('without a skip vote the row holds the skip button, then the count', async () => {
    const env = setup()
    const socket = await choiceSongAudible(env)
    deliver(socket, pickState(0, 3))
    expect(pickCounts()).toEqual([COUNT.ko(0, 3)])
    expect(qa('.panel .row')).toHaveLength(1)
    const row = document.querySelector('.panel .row')
    expect(Array.from(row.children).map((el) => el.tagName)).toEqual(['BUTTON', 'SPAN'])
    expect(row.children[0]).toBe(screen.getByRole('button', { name: ANY_SKIP }))
    expect(row.children[1].classList.contains('pickcount')).toBe(true)
  })

  it('with a skip vote standing the skip count keeps 스킵 1/2 and the pick count comes after it in the same row', async () => {
    const env = setup()
    const socket = await choiceSongAudible(env)
    deliver(socket, skipState(1))
    deliver(socket, pickState(1, 3))
    expect(pickCounts()).toEqual([COUNT.ko(1, 3)])
    expect(qa('.panel .row')).toHaveLength(1)
    const row = document.querySelector('.panel .row')
    const spans = Array.from(row.querySelectorAll('.skipcount'))
    expect(spans.map((el) => el.textContent)).toEqual(['스킵 1/2', COUNT.ko(1, 3)])
    expect(spans.map((el) => el.classList.contains('pickcount'))).toEqual([false, true])
    expect(Array.from(row.children).map((el) => el.tagName)).toEqual(['BUTTON', 'SPAN', 'SPAN'])
    // A new skip count changes only its own text.
    deliver(socket, skipState(0))
    deliver(socket, pickState(2, 3))
    expect(Array.from(row.querySelectorAll('.skipcount')).map((el) => el.textContent)).toEqual(['스킵 0/2', COUNT.ko(2, 3)])
  })

  it('English and Japanese: the count follows the language, also after an update', async () => {
    const env = setup()
    const socket = await choiceSongAudible(env)
    deliver(socket, pickState(1, 3))
    expect(pickCounts()).toEqual([COUNT.ko(1, 3)])
    chooseLanguage('English')
    expect(pickCounts()).toEqual([COUNT.en(1, 3)])
    deliver(socket, pickState(2, 3))
    expect(pickCounts()).toEqual([COUNT.en(2, 3)])
    chooseLanguage('日本語')
    expect(pickCounts()).toEqual([COUNT.ja(2, 3)])
    deliver(socket, pickState(3, 3))
    expect(pickCounts()).toEqual([COUNT.ja(3, 3)])
  })

  it('a watching late joiner has no skip button and sees the count alone in the row', async () => {
    const env = setup()
    const socket = await lateJoiner(env)
    await runSnapshotSong(env, socket)
    expect(screen.queryByRole('button', { name: ANY_SKIP })).toBeNull()
    expect(koTiles(4).map((b) => b.disabled)).toEqual([true, true, true, true])
    deliver(socket, pickState(1, 3, 2))
    expect(pickCounts()).toEqual([COUNT.ko(1, 3)])
    expect(qa('.panel .row')).toHaveLength(1)
    const row = document.querySelector('.panel .row')
    expect(row.children).toHaveLength(1)
    expect(row.children[0].matches('span.skipcount.pickcount')).toBe(true)
    expect(screen.queryByRole('button', { name: ANY_SKIP })).toBeNull()
    deliver(socket, pickState(2, 3, 2))
    expect(pickCounts()).toEqual([COUNT.ko(2, 3)])
  })
})

describe('when the pick count shows (criterion 2)', () => {
  it('a pickState before the start shows nothing; after the start the last value received shows', async () => {
    const env = setup()
    const socket = await choiceRoomPrepared(env)
    deliver(socket, pickState(1, 3))
    expect(pickCounts()).toEqual([])
    deliver(socket, pickState(2, 3))
    expect(pickCounts()).toEqual([])
    await startSong(env, socket)
    expect(pickCounts()).toEqual([COUNT.ko(2, 3)])
  })

  it('the count is gone once the reveal is up, and a pickState during the reveal shows nothing', async () => {
    const env = setup()
    const socket = await choiceSongAudible(env)
    deliver(socket, pickState(2, 3))
    expect(pickCounts()).toEqual([COUNT.ko(2, 3)])
    deliver(socket, revealMsg(0, { correctChoice: 2 }))
    expect(revealShown()).toBe(true)
    expect(pickCounts()).toEqual([])
    deliver(socket, pickState(3, 3))
    expect(pickCounts()).toEqual([])
  })

  it('reveal up with a held prepare for song 2: no count during the reveal, none of song 1 after the start, then song 2 its own', async () => {
    const env = setup()
    const socket = await choiceSongAudible(env)
    deliver(socket, pickState(2, 3))
    expect(pickCounts()).toEqual([COUNT.ko(2, 3)])
    deliver(socket, revealMsg(0, { correctChoice: 2 }))
    expect(revealShown()).toBe(true)
    deliver(socket, prepareMsg(1, COVERS6, { holdMs: HOLD }))
    await settlePreloads(COVERS6)
    await flushUntil(() => readiesFor(socket, 1).length === 1)
    expect(readiesFor(socket, 1)).toEqual([{ type: 'ready', index: 1 }])
    expect(revealShown(), 'the reveal card stays during the hold').toBe(true)
    expect(pickCounts()).toEqual([])
    // A late count of the song that ended.
    deliver(socket, pickState(3, 3, 0))
    expect(pickCounts()).toEqual([])
    await startSong(env, socket, { index: 1, sources: 2, tileCount: 6 })
    expect(revealShown()).toBe(false)
    expect(pickCounts(), "the new song never shows the last song's count").toEqual([])
    deliver(socket, pickState(1, 3, 1))
    expect(pickCounts()).toEqual([COUNT.ko(1, 3)])
  })

  it('a prepare without a hold clears the count: the next song starts with none', async () => {
    const env = setup()
    const socket = await choiceSongAudible(env)
    deliver(socket, pickState(2, 3))
    expect(pickCounts()).toEqual([COUNT.ko(2, 3)])
    deliver(socket, revealMsg(0, { correctChoice: 2 }))
    deliver(socket, prepareMsg(1, COVERS6))
    await settlePreloads(COVERS6)
    await flushUntil(() => readiesFor(socket, 1).length === 1)
    expect(revealShown()).toBe(false)
    expect(pickCounts()).toEqual([])
    await startSong(env, socket, { index: 1, sources: 2, tileCount: 6 })
    expect(pickCounts()).toEqual([])
    deliver(socket, pickState(0, 3, 1))
    expect(pickCounts()).toEqual([COUNT.ko(0, 3)])
  })
})

describe('a pickState that is ignored (criterion 3)', () => {
  it.each([
    ['another index', { type: 'pickState', index: 1, picked: 2, total: 3 }],
    ['no index', { type: 'pickState', picked: 2, total: 3 }],
    ['picked as a string', { type: 'pickState', index: 0, picked: '2', total: 3 }],
    ['a fractional picked', { type: 'pickState', index: 0, picked: 1.5, total: 3 }],
    ['a fractional total', { type: 'pickState', index: 0, picked: 2, total: 3.5 }],
    ['no numbers', { type: 'pickState', index: 0 }],
    ['null numbers', { type: 'pickState', index: 0, picked: null, total: null }],
    ['picked above total', { type: 'pickState', index: 0, picked: 4, total: 3 }],
    ['total 0', { type: 'pickState', index: 0, picked: 0, total: 0 }],
    ['a negative picked', { type: 'pickState', index: 0, picked: -1, total: 3 }],
  ])('%s leaves 선택 1/3 as it is, without a warning', async (_label, frame) => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    const env = setup()
    const socket = await choiceSongAudible(env)
    deliver(socket, pickState(1, 3))
    expect(pickCounts()).toEqual([COUNT.ko(1, 3)])
    const warned = warnsDuring(warn, () => deliver(socket, frame))
    expect(pickCounts()).toEqual([COUNT.ko(1, 3)])
    expect(warned).toEqual([])
  })

  it('with no count yet, an invalid pickState shows none', async () => {
    const env = setup()
    const socket = await choiceSongAudible(env)
    deliver(socket, pickState(4, 3))
    deliver(socket, pickState(0, 0))
    deliver(socket, pickState(1, 3, 1))
    expect(pickCounts()).toEqual([])
    // The row itself works: a valid one shows.
    deliver(socket, pickState(1, 3))
    expect(pickCounts()).toEqual([COUNT.ko(1, 3)])
  })
})

describe('the wrong-pick chat line (criterion 4)', () => {
  it('wrong{index:0,name} adds exactly one .msg.sys line, not .correct, with the Korean text', async () => {
    const env = setup()
    const socket = await choiceSongAudible(env)
    const before = sysLines()
    const beforeAll = allSysCount()
    deliver(socket, wrongMsg('Joon'))
    expect(sysLines()).toEqual([...before, WRONG.ko('Joon')])
    expect(allSysCount()).toBe(beforeAll + 1)
    expect(qa('aside.chat .msgs .msg.sys.correct')).toEqual([])
    // It is the last line of the log.
    const lines = qa('aside.chat .msgs .msg')
    expect(lines[lines.length - 1].className).toBe('msg sys wrong')
    // A second wrong pick is a second line.
    deliver(socket, wrongMsg('Mina'))
    expect(sysLines()).toEqual([...before, WRONG.ko('Joon'), WRONG.ko('Mina')])
  })

  // Cycle 124 (F-034, D-131): only the wrong line carries .wrong; a plain
  // notice and the correct line keep their classes
  // (docs/cycles/124-wrong-line-red.md, criterion 1).
  it('in one log the song-start line is msg sys, the correct line msg sys correct and the wrong line msg sys wrong', async () => {
    const env = setup()
    const socket = await choiceSongAudible(env)
    deliver(socket, wrongMsg('Joon'))
    deliver(socket, { type: 'correct', index: 0, name: 'Mina' })
    const classOf = (text) =>
      qa('aside.chat .msgs .msg')
        .filter((el) => el.textContent.replace(/\s+/g, ' ').trim() === text)
        .map((el) => el.className)
    expect(classOf(ko.chat.songStart.replace('{{n}}', '1'))).toEqual(['msg sys'])
    expect(qa('aside.chat .msgs .msg.correct').map((el) => el.className)).toEqual(['msg sys correct'])
    expect(classOf(WRONG.ko('Joon'))).toEqual(['msg sys wrong'])
  })

  it('English: a line already shown changes language after the switch, and a new one is English', async () => {
    const env = setup()
    const socket = await choiceSongAudible(env)
    deliver(socket, wrongMsg('Joon'))
    expect(sysLines()).toContain(WRONG.ko('Joon'))
    const count = sysLines().length
    chooseLanguage('English')
    expect(sysLines()).toContain(WRONG.en('Joon'))
    expect(sysLines()).not.toContain(WRONG.ko('Joon'))
    expect(sysLines()).toHaveLength(count)
    deliver(socket, wrongMsg('Mina'))
    expect(sysLines().slice(-2)).toEqual([WRONG.en('Joon'), WRONG.en('Mina')])
  })

  it('Japanese: a line already shown changes language after the switch, and a new one is Japanese', async () => {
    const env = setup()
    const socket = await choiceSongAudible(env)
    deliver(socket, wrongMsg('Joon'))
    expect(sysLines()).toContain(WRONG.ko('Joon'))
    const count = sysLines().length
    chooseLanguage('日本語')
    expect(sysLines()).toContain(WRONG.ja('Joon'))
    expect(sysLines()).not.toContain(WRONG.ko('Joon'))
    expect(sysLines()).toHaveLength(count)
    deliver(socket, wrongMsg('Mina'))
    expect(sysLines().slice(-2)).toEqual([WRONG.ja('Joon'), WRONG.ja('Mina')])
  })

  it.each([
    ['another index', { type: 'wrong', index: 1, name: 'Mina' }],
    ['no index', { type: 'wrong', name: 'Mina' }],
    ['no name', { type: 'wrong', index: 0 }],
    ['an empty name', { type: 'wrong', index: 0, name: '' }],
    ['a name that is not a string', { type: 'wrong', index: 0, name: 7 }],
    ['a null name', { type: 'wrong', index: 0, name: null }],
  ])('%s adds nothing, without a warning', async (_label, frame) => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    const env = setup()
    const socket = await choiceSongAudible(env)
    deliver(socket, wrongMsg('Joon'))
    const shown = sysLines()
    expect(shown[shown.length - 1]).toBe(WRONG.ko('Joon'))
    const logSize = qa('aside.chat .msgs .msg').length
    const warned = warnsDuring(warn, () => deliver(socket, frame))
    expect(sysLines()).toEqual(shown)
    expect(qa('aside.chat .msgs .msg')).toHaveLength(logSize)
    expect(warned).toEqual([])
  })

  it('the wrong line does not change the count, and the count does not change the log', async () => {
    const env = setup()
    const socket = await choiceSongAudible(env)
    deliver(socket, pickState(1, 3))
    deliver(socket, wrongMsg('Joon'))
    expect(pickCounts()).toEqual([COUNT.ko(1, 3)])
    const shown = sysLines()
    expect(shown[shown.length - 1]).toBe(WRONG.ko('Joon'))
    deliver(socket, pickState(2, 3))
    expect(sysLines()).toEqual(shown)
    expect(pickCounts()).toEqual([COUNT.ko(2, 3)])
  })
})

describe("the player's own wrong pick (criterion 5)", () => {
  it('pick-result marks the tile and shows the pickmsg; the wrong frame that follows adds the chat line too', async () => {
    const env = setup()
    const socket = await choiceSongAudible(env)
    const pickMsg = ko.stage.pickWrong.replace('{{n}}', '2')
    fireEvent.click(koTiles(4)[1])
    expect(socket.sentOfType('pick')).toEqual([{ type: 'pick', index: 0, choice: 1 }])
    deliver(socket, { type: 'pick-result', index: 0, choice: 1 })
    expect(marks(koTiles(4))).toEqual(['locked', 'wrong', 'locked', 'locked'])
    expect(qa('.panel .pickmsg').map((el) => el.textContent)).toEqual([pickMsg])
    const before = sysLines()
    expect(before).not.toContain(WRONG.ko('하루'))
    deliver(socket, pickState(1, 3))
    deliver(socket, wrongMsg('하루'))
    expect(sysLines()).toEqual([...before, WRONG.ko('하루')])
    expect(marks(koTiles(4))).toEqual(['locked', 'wrong', 'locked', 'locked'])
    expect(koTiles(4).map((b) => b.disabled)).toEqual([true, true, true, true])
    expect(qa('.panel .pickmsg').map((el) => el.textContent)).toEqual([pickMsg])
    expect(pickCounts()).toEqual([COUNT.ko(1, 3)])
  })
})

describe('chat rooms and the console (criterion 6)', () => {
  it('a chat room that receives pickState and wrong shows no count and no line, and warns about neither', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    const env = setup()
    const socket = await chatSongAudible(env)
    const before = sysLines()
    const logSize = qa('aside.chat .msgs .msg').length
    const warned = warnsDuring(warn, () => {
      deliver(socket, pickState(1, 3))
      deliver(socket, wrongMsg('Joon'))
    })
    expect(pickCounts()).toEqual([])
    expect(qa('.pickcount')).toEqual([])
    expect(sysLines()).toEqual(before)
    expect(qa('aside.chat .msgs .msg')).toHaveLength(logSize)
    expect(document.body.textContent).not.toContain(CROSS)
    // The skip row is as before.
    expect(Array.from(document.querySelector('.panel .row').children).map((el) => el.tagName)).toEqual(['BUTTON'])
    expect(warned).toEqual([])
  })

  it('in the lobby, with no game yet, both frames are ignored without a warning', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    const env = setup()
    const socket = await hostLobby(env)
    const warned = warnsDuring(warn, () => {
      deliver(socket, pickState(1, 3))
      deliver(socket, wrongMsg('Joon'))
    })
    expect(document.body.textContent).not.toContain(CROSS)
    expect(qa('.pickcount')).toEqual([])
    expect(warned).toEqual([])
  })

  it('a choice room warns about neither frame: before the start, during the song and during the reveal', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    const env = setup()
    const socket = await choiceRoomPrepared(env)
    const prepared = warnsDuring(warn, () => deliver(socket, pickState(0, 3)))
    expect(prepared).toEqual([])
    await startSong(env, socket)
    const during = warnsDuring(warn, () => {
      deliver(socket, pickState(1, 3))
      deliver(socket, wrongMsg('Joon'))
    })
    expect(during).toEqual([])
    expect(pickCounts()).toEqual([COUNT.ko(1, 3)])
    deliver(socket, revealMsg(0, { correctChoice: 2 }))
    const revealed = warnsDuring(warn, () => {
      deliver(socket, pickState(2, 3))
      deliver(socket, wrongMsg('Mina'))
    })
    expect(revealed).toEqual([])
    // An unknown type still warns: the spy does see the session's warnings.
    deliver(socket, { type: 'noSuchFrame' })
    expect(warn).toHaveBeenCalledWith('unknown message', 'noSuchFrame')
  })
})

describe('reload during a choice song (criterion 7)', () => {
  beforeEach(() => {
    window.sessionStorage.setItem(STORAGE_KEY, JSON.stringify(ENTRY))
  })

  it('a snapshot with song.pickState {1,2} shows 선택 1/2 once the song is running again', async () => {
    const env = setup('reload')
    const socket = await reloadJoined(env)
    await reloadSnapshot(env, socket, { pickState: { picked: 1, total: 2 } })
    expect(pickCounts(), 'no count before the song runs').toEqual([])
    await runSnapshotSong(env, socket)
    expect(pickCounts()).toEqual([COUNT.ko(1, 2)])
    const row = document.querySelector('.panel .row')
    expect(Array.from(row.children).map((el) => el.tagName)).toEqual(['BUTTON', 'SPAN'])
    // The next broadcast replaces the snapshot's value.
    deliver(socket, pickState(2, 2))
    expect(pickCounts()).toEqual([COUNT.ko(2, 2)])
  })

  it('a snapshot without pickState shows no count; the next pickState shows one', async () => {
    const env = setup('reload')
    const socket = await reloadJoined(env)
    await reloadSnapshot(env, socket)
    await runSnapshotSong(env, socket)
    expect(pickCounts()).toEqual([])
    deliver(socket, pickState(1, 2))
    expect(pickCounts()).toEqual([COUNT.ko(1, 2)])
  })

  it.each([
    ['picked above total', { picked: 3, total: 2 }],
    ['total 0', { picked: 0, total: 0 }],
    ['strings', { picked: '1', total: '2' }],
    ['null', null],
  ])('a snapshot whose pickState is invalid (%s) shows no count; the next pickState shows one', async (_label, value) => {
    const env = setup('reload')
    const socket = await reloadJoined(env)
    await reloadSnapshot(env, socket, { pickState: value })
    await runSnapshotSong(env, socket)
    expect(pickCounts()).toEqual([])
    deliver(socket, pickState(1, 2))
    expect(pickCounts()).toEqual([COUNT.ko(1, 2)])
  })

  it('the order of a real reload: snapshot with {0,1}, pickState{1,2} before any pong, then the pong shows 선택 1/2', async () => {
    const env = setup('reload')
    const socket = await reloadJoined(env)
    await reloadSnapshot(env, socket, { pickState: { picked: 0, total: 1 } })
    deliver(socket, pickState(1, 2))
    expect(env.audio.last.startedSources()).toEqual([])
    expect(pickCounts(), 'no count before the song runs').toEqual([])
    await runSnapshotSong(env, socket)
    expect(pickCounts()).toEqual([COUNT.ko(1, 2)])
  })
})

describe("a late joiner's watching snapshot (criterion 7)", () => {
  it('song.pickState {1,2} shows 선택 1/2 once the watched song is running', async () => {
    const env = setup()
    const socket = await lateJoiner(env, { pickState: { picked: 1, total: 2 } })
    expect(pickCounts(), 'no count before the song runs').toEqual([])
    await runSnapshotSong(env, socket)
    expect(pickCounts()).toEqual([COUNT.ko(1, 2)])
    expect(document.querySelector('.panel .row').children).toHaveLength(1)
    expect(screen.queryByRole('button', { name: ANY_SKIP })).toBeNull()
  })

  it('a watching snapshot without pickState shows no count; the next pickState shows one', async () => {
    const env = setup()
    const socket = await lateJoiner(env)
    await runSnapshotSong(env, socket)
    expect(pickCounts()).toEqual([])
    expect(qa('.panel .row')).toEqual([])
    deliver(socket, pickState(2, 3, 2))
    expect(pickCounts()).toEqual([COUNT.ko(2, 3)])
  })

  it('a watching snapshot whose pickState is invalid shows no count', async () => {
    const env = setup()
    const socket = await lateJoiner(env, { pickState: { picked: 3, total: 2 } })
    await runSnapshotSong(env, socket)
    expect(pickCounts()).toEqual([])
    deliver(socket, pickState(2, 3, 2))
    expect(pickCounts()).toEqual([COUNT.ko(2, 3)])
  })
})
