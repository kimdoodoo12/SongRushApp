// Cycle 100 (D-111): multiple-choice answers. Spec
// docs/cycles/100-choice-answers.md, with its "Amendments after spec review"
// overriding the text above them.
//
// Contract with the implementer:
// - Lobby: the host sees a role=group named answer.label (정답 형식) holding
//   two buttons, answer.chat (채팅) and answer.choice (객관식), with
//   aria-pressed="true" on the current one; 채팅 by default. A guest has no
//   enabled 객관식 button. The host's choice is kept across games like the
//   filters. The start request carries answerMode:"choice" only when 객관식
//   is chosen; with 채팅 the field is left out (start stays exactly
//   {type:"start", songCount:10} with the defaults, as App.filters.test.jsx
//   already requires).
// - settings{..., answerMode}: the .settings line is the filter line with
//   answer.choice appended as the last ' · ' part when answerMode is
//   "choice" ('객관식' alone, '오프닝만 · 객관식'); answerMode "chat" adds
//   nothing.
// - prepare{..., choices:[{coverUrl}]} (A3): the stage shows one tile per
//   choice, in order. Tile N (1-based) is a <button> whose accessible name is
//   answer.choiceN with n=N (보기 N) and holds an <img alt=""> whose src is
//   choices[N-1].coverUrl. Positions on the wire are 0-based: tile N is
//   position N-1 in pick.choice, pick-result.choice, reveal.correctChoice and
//   snapshot song.picked. A prepare without choices shows no tiles.
// - Image preloading: for every choice the app constructs `new Image()`
//   (the global Image constructor), attaches a load and an error handler
//   (onload/onerror properties, addEventListener('load'|'error'), or
//   img.decode()), and sets src to the coverUrl. The test replaces the global
//   Image with FakeImage below and settles each preload by hand; jsdom never
//   loads images on its own. ready{index} is sent only once the audio has
//   loaded (or failed) AND every choice preload has fired load or error.
// - Tiles are disabled until the song is audible (the cycle 087 reached
//   flag: started and the clock has passed startAt), for watchers, after a
//   pick and from the reveal on.
// - A click sends exactly one {type:"pick", index, choice} and disables all
//   tiles; the clicked tile gets data-choice="picked". pick-result{index,
//   choice} (means wrong) sets data-choice="wrong" on that tile.
//   error{code:"too-early", for:"pick"} re-enables the tiles and clears the
//   mark; error{code:"already-picked", for:"pick"} keeps every tile disabled;
//   an error for something else does not re-enable them.
// - declared change (100 review M1): already-picked locks instead of
//   re-enabling; too-early still re-enables.
// - reveal{..., correctChoice}: the tile at correctChoice gets
//   data-choice="correct"; the own wrong pick keeps data-choice="wrong"; the
//   grid stays (all tiles disabled) under the reveal card. The next prepare
//   replaces the tiles and clears every mark.
// - snapshot: the per-song fields are on song: song.choices and
//   song.picked (position or null). A non-null picked shows that tile as
//   data-choice="wrong" with every tile disabled.
// - Chat in a choice room works exactly as in a chat room (owner O1): the
//   input stays enabled and sends chat{text}, also after a wrong pick; a
//   correct frame from a chat answer ends the song like any other, and the
//   reveal marks correctChoice. There is no everyone-wrong ending (O2).
//
// i18n keys this file relies on (ko / en / ja):
//   answer.label     정답 형식 / Answer format / 回答形式
//   answer.chat      채팅 / Chat / チャット
//   answer.choice    객관식 / Multiple choice / 選択式
//   answer.choiceN   보기 {{n}} / Choice {{n}} / 選択肢{{n}}
//
// Timer rule (cycle 039): real timers and waitFor, except 'rejoin', which
// needs the reconnect backoff and uses fake timers with flushUntil/advance.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, act, cleanup, within } from '@testing-library/react'
import App from './App.jsx'
import ko from './i18n/ko.json'
import en from './i18n/en.json'
import ja from './i18n/ja.json'
import { createSocketFactory, createAudioContextFactory, createFakeFetch } from './testing/fakes.js'

const T0 = 1_000_000
const FAKE_TIMERS = { toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval'] }
const AHEAD = 2000
const SYNC = 2500
const RTT = 20

const KO = { label: '정답 형식', chat: '채팅', choice: '객관식' }
const tileName = (n) => `보기 ${n}`

const COVERS4 = ['/cover/a1', '/cover/b2', '/cover/c3', '/cover/d4']
const COVERS6 = ['/cover/e5', '/cover/f6', '/cover/g7', '/cover/h8', '/cover/i9', '/cover/j10']
const choicesOf = (urls) => urls.map((coverUrl) => ({ coverUrl }))

// The value of a dotted key with {{variables}} filled in.
function tr(dict, key, vars = {}) {
  let value = dict
  for (const part of key.split('.')) {
    value = value !== null && typeof value === 'object' ? value[part] : undefined
  }
  if (typeof value !== 'string') throw new Error(`missing i18n key ${key}`)
  return value.replace(/\{\{\s*([^}\s]+)\s*\}\}/g, (whole, name) => (name in vars ? String(vars[name]) : whole))
}

// A stand-in for the browser's Image. Every instance is recorded; fire()
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

function pendingPreloads(urls) {
  return preloadsOf(urls).filter((img) => img.settled === null)
}

// Settles every pending preload of `urls`: all load, except the positions in
// `failing`, which fire error.
async function settlePreloads(urls, failing = []) {
  await waitFor(() => expect(preloadsOf(urls).map((img) => urls.find((u) => img.src.endsWith(u))).sort()).toEqual([...urls].sort()))
  await act(async () => {
    for (const img of pendingPreloads(urls)) {
      const position = urls.findIndex((u) => img.src.endsWith(u))
      img.fire(failing.includes(position) ? 'error' : 'load')
    }
  })
}

beforeEach(() => {
  images.length = 0
  vi.stubGlobal('Image', FakeImage)
  if (typeof window !== 'undefined' && window.Image !== FakeImage) window.Image = FakeImage
  window.history.pushState({}, '', '/')
  window.localStorage.clear()
  window.sessionStorage.clear()
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

// A fetch whose calls stay pending until release() (oldest first).
function createDeferredFetch() {
  const pending = []
  const fetchFn = vi.fn(() => new Promise((resolve) => pending.push(resolve)))
  function release() {
    const resolve = pending.shift()
    if (!resolve) throw new Error('no pending fetch')
    const buffer = new ArrayBuffer(8)
    resolve({ ok: true, status: 200, arrayBuffer: async () => buffer })
  }
  return { fetchFn, pending, release }
}

function setup({ ctxOptions, fetch } = {}) {
  const sockets = createSocketFactory()
  const audio = createAudioContextFactory(ctxOptions)
  const theFetch = fetch === undefined ? createFakeFetch() : fetch
  const clock = { t: T0 }
  render(
    <App
      canPlayOpus={true}
      socketFactory={sockets.factory}
      audioContextFactory={audio.factory}
      fetchFn={theFetch.fetchFn}
      now={() => clock.t}
    />,
  )
  return { sockets, audio, fetch: theFetch, clock }
}

function deliver(socket, msg) {
  act(() => socket.receive(msg))
}

async function settle(ms = 50) {
  await act(async () => {
    await new Promise((resolve) => setTimeout(resolve, ms))
  })
}

function chooseLanguage(label) {
  const group = screen.getByRole('group', { name: 'Language' })
  fireEvent.click(within(group).getByRole('button', { name: label }))
}

const player = (name, { host = false, score = 0, connected = true } = {}) => ({ name, score, host, connected })
const ALONE = [player('하루', { host: true })]
const PAIR = [player('하루', { host: true }), player('Joon')]
const roster = (players, phase = 'lobby') => ({ type: 'roster', phase, players })

async function openSocket(env, count = 1) {
  await waitFor(() => expect(env.sockets.sockets.length).toBe(count))
  const socket = env.sockets.last
  act(() => socket.open())
  await waitFor(() => expect(socket.sent.length).toBeGreaterThan(0))
  return socket
}

// 방 만들기 as 하루; joined and a lobby roster that makes 하루 host.
async function hostLobby(env, { count = 1, players = ALONE, dict = ko } = {}) {
  fireEvent.change(screen.getByLabelText(dict.start.nickname), { target: { value: '하루' } })
  fireEvent.click(screen.getByRole('button', { name: dict.start.createRoom }))
  const socket = await openSocket(env, count)
  deliver(socket, { type: 'joined', code: 'QWERT', name: '하루' })
  deliver(socket, roster(players))
  return socket
}

// 참가 to ABCDE as Hana; Mina is host.
async function guestJoin(env) {
  fireEvent.change(screen.getByLabelText(ko.start.nickname), { target: { value: 'Hana' } })
  fireEvent.change(screen.getByLabelText(ko.start.roomCode), { target: { value: 'ABCDE' } })
  fireEvent.click(screen.getByRole('button', { name: ko.start.join }))
  const socket = await openSocket(env)
  deliver(socket, { type: 'joined', code: 'ABCDE', name: 'Hana' })
  return socket
}

const GUEST_PLAYERS = [player('Mina', { host: true }), player('Hana')]

// The Korean name is a literal, so a missing key fails on the missing UI.
function answerPicker(dict = null) {
  return screen.getByRole('group', { name: dict === null ? KO.label : tr(dict, 'answer.label') })
}

function pressedIn(group) {
  return within(group)
    .getAllByRole('button')
    .filter((b) => b.getAttribute('aria-pressed') === 'true')
    .map((b) => b.textContent.trim())
}

async function clickStartGame(socket, total = 1, label = ko.lobby.startGame) {
  fireEvent.click(screen.getByRole('button', { name: label }))
  await waitFor(() => expect(socket.sentOfType('start')).toHaveLength(total))
  return socket.sentOfType('start')[total - 1]
}

const prepareMsg = (index, urls, extra = {}) => ({
  type: 'prepare',
  index,
  total: 5,
  audioUrl: `/audio/s${index}`,
  choices: choicesOf(urls),
  ...extra,
})

function readiesFor(socket, index) {
  return socket.sentOfType('ready').filter((m) => m.index === index)
}

// The server starts the song; the receipt offset is 500, so by the local
// clock the song starts startInMs after the clock.
function deliverStart(env, socket, { index = 0, startInMs = 0 } = {}) {
  const serverTime = env.clock.t + 500
  const startAt = serverTime + startInMs
  deliver(socket, { type: 'start', index, startAt, deadlineAt: startAt + 90_000, serverTime })
}

function tiles(count, dict = ko) {
  return Array.from({ length: count }, (_, i) => screen.getByRole('button', { name: tr(dict, 'answer.choiceN', { n: i + 1 }) }))
}

function koTiles(count) {
  return Array.from({ length: count }, (_, i) => screen.getByRole('button', { name: tileName(i + 1) }))
}

function noTiles() {
  expect(screen.queryByRole('button', { name: tileName(1) })).toBeNull()
}

function disabledStates(list) {
  return list.map((b) => b.disabled)
}

function marks(list) {
  return list.map((b) => b.getAttribute('data-choice'))
}

function settingsLines() {
  return Array.from(document.querySelectorAll('.settings')).map((el) => el.textContent)
}

// Alone as host with 객관식 chosen: start, roster{playing}, settings, then
// prepare{0} with `urls`; the preloads settle and ready is sent.
async function choiceRoomPrepared(env, urls = COVERS4) {
  const socket = await hostLobby(env)
  fireEvent.click(within(answerPicker()).getByRole('button', { name: KO.choice }))
  await clickStartGame(socket)
  deliver(socket, roster(ALONE, 'playing'))
  deliver(socket, { type: 'settings', answerMode: 'choice' })
  deliver(socket, prepareMsg(0, urls))
  await settlePreloads(urls)
  await waitFor(() => expect(readiesFor(socket, 0)).toEqual([{ type: 'ready', index: 0 }]))
  return socket
}

// choiceRoomPrepared, then started and audible.
async function choiceSongAudible(env, urls = COVERS4) {
  const socket = await choiceRoomPrepared(env, urls)
  deliverStart(env, socket)
  await waitFor(() => expect(env.audio.last.startedSources()).toHaveLength(1))
  await waitFor(() => expect(disabledStates(koTiles(urls.length)).every((d) => d === false)).toBe(true))
  return socket
}

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

describe('the harness', () => {
  it('new Image() and new window.Image() both build a FakeImage', () => {
    expect(new Image()).toBeInstanceOf(FakeImage)
    expect(new window.Image()).toBeInstanceOf(FakeImage)
    images.length = 0
  })
})

describe('dictionaries', () => {
  it('ko, en and ja carry the answer-format keys with the decided values', () => {
    expect(tr(ko, 'answer.label')).toBe('정답 형식')
    expect(tr(ko, 'answer.chat')).toBe('채팅')
    expect(tr(ko, 'answer.choice')).toBe('객관식')
    expect(tr(ko, 'answer.choiceN', { n: 3 })).toBe('보기 3')
    expect(tr(en, 'answer.label')).toBe('Answer format')
    expect(tr(en, 'answer.chat')).toBe('Chat')
    expect(tr(en, 'answer.choice')).toBe('Multiple choice')
    expect(tr(en, 'answer.choiceN', { n: 3 })).toBe('Choice 3')
    expect(tr(ja, 'answer.label')).toBe('回答形式')
    expect(tr(ja, 'answer.chat')).toBe('チャット')
    expect(tr(ja, 'answer.choice')).toBe('選択式')
    expect(tr(ja, 'answer.choiceN', { n: 3 })).toBe('選択肢3')
  })
})

describe('lobby picker', () => {
  it('the host sees 정답 형식 with 채팅 and 객관식, 채팅 pressed by default', async () => {
    const env = setup()
    await hostLobby(env)
    const group = answerPicker()
    expect(within(group).getAllByRole('button').map((b) => b.textContent.trim())).toEqual([KO.chat, KO.choice])
    expect(pressedIn(group)).toEqual([KO.chat])
  })

  it('객관식 pressed: start carries answerMode:"choice"', async () => {
    const env = setup()
    const socket = await hostLobby(env)
    fireEvent.click(within(answerPicker()).getByRole('button', { name: KO.choice }))
    expect(pressedIn(answerPicker())).toEqual([KO.choice])
    expect(await clickStartGame(socket)).toEqual({ type: 'start', songCount: 10, answerMode: 'choice' })
  })

  it('객관식 then 채팅: start carries no answerMode', async () => {
    const env = setup()
    const socket = await hostLobby(env)
    fireEvent.click(within(answerPicker()).getByRole('button', { name: KO.choice }))
    fireEvent.click(within(answerPicker()).getByRole('button', { name: KO.chat }))
    expect(pressedIn(answerPicker())).toEqual([KO.chat])
    expect(await clickStartGame(socket)).toEqual({ type: 'start', songCount: 10 })
  })

  it('객관식 with 오프닝만: both go into start', async () => {
    const env = setup()
    const socket = await hostLobby(env)
    fireEvent.change(screen.getByLabelText(ko.filter.type), { target: { value: 'OP' } })
    fireEvent.click(within(answerPicker()).getByRole('button', { name: KO.choice }))
    expect(await clickStartGame(socket)).toEqual({ type: 'start', songCount: 10, types: ['OP'], answerMode: 'choice' })
  })

  it('the choice is kept after a game and 대기실로, and sent again', async () => {
    const env = setup()
    const socket = await hostLobby(env, { players: PAIR })
    fireEvent.click(within(answerPicker()).getByRole('button', { name: KO.choice }))
    await clickStartGame(socket)
    deliver(socket, roster(PAIR, 'playing'))
    deliver(socket, { type: 'gameOver', ranking: [{ rank: 1, name: '하루', score: 0 }, { rank: 2, name: 'Joon', score: 0 }] })
    deliver(socket, roster(PAIR, 'lobby'))
    fireEvent.click(screen.getByRole('button', { name: ko.game.toLobby }))
    expect(pressedIn(answerPicker())).toEqual([KO.choice])
    expect(await clickStartGame(socket, 2)).toEqual({ type: 'start', songCount: 10, answerMode: 'choice' })
  })

  it('the choice is kept through 나가기 into a new room', async () => {
    const env = setup()
    await hostLobby(env)
    fireEvent.click(within(answerPicker()).getByRole('button', { name: KO.choice }))
    fireEvent.click(screen.getByRole('button', { name: ko.lobby.leave }))
    const second = await hostLobby(env, { count: 2 })
    expect(second.sent[0]).toEqual({ type: 'create', nickname: '하루' })
    expect(pressedIn(answerPicker())).toEqual([KO.choice])
  })

  it('a guest has no enabled 객관식 button', async () => {
    const env = setup()
    const socket = await guestJoin(env)
    deliver(socket, roster(GUEST_PLAYERS))
    expect(screen.getByText(ko.lobby.waiting)).toBeTruthy()
    const button = screen.queryByRole('button', { name: KO.choice })
    if (button !== null) expect(button.disabled).toBe(true)
  })
})

describe('the settings line', () => {
  it('a guest sees 객관식 from settings{answerMode:"choice"}', async () => {
    const env = setup()
    const socket = await guestJoin(env)
    deliver(socket, roster(GUEST_PLAYERS))
    deliver(socket, roster(GUEST_PLAYERS, 'playing'))
    deliver(socket, { type: 'settings', answerMode: 'choice' })
    deliver(socket, prepareMsg(0, COVERS4))
    await waitFor(() => expect(settingsLines()).toEqual([KO.choice]))
  })

  it('with a filter the mode is the last part: 오프닝만 · 객관식', async () => {
    const env = setup()
    const socket = await guestJoin(env)
    deliver(socket, roster(GUEST_PLAYERS))
    deliver(socket, roster(GUEST_PLAYERS, 'playing'))
    deliver(socket, { type: 'settings', types: ['OP'], answerMode: 'choice' })
    deliver(socket, prepareMsg(0, COVERS4))
    await waitFor(() => expect(settingsLines()).toEqual([`${ko.filter.op} · ${KO.choice}`]))
  })

  it('answerMode "chat" adds nothing to the line', async () => {
    const env = setup()
    const socket = await guestJoin(env)
    deliver(socket, roster(GUEST_PLAYERS))
    deliver(socket, roster(GUEST_PLAYERS, 'playing'))
    deliver(socket, { type: 'settings', types: ['OP'], answerMode: 'chat' })
    deliver(socket, { type: 'prepare', index: 0, total: 5, audioUrl: '/audio/s0' })
    await waitFor(() => expect(settingsLines()).toEqual([ko.filter.op]))
  })
})

describe('the grid', () => {
  it('prepare with 4 choices shows 보기 1..4 in order, each an img alt="" with its coverUrl', async () => {
    const env = setup()
    await choiceRoomPrepared(env, COVERS4)
    const list = koTiles(4)
    list.forEach((tile, i) => {
      expect(tile.tagName).toBe('BUTTON')
      const img = tile.querySelector('img')
      expect(img).not.toBeNull()
      expect(img.getAttribute('alt')).toBe('')
      expect(img.getAttribute('src')).toBe(COVERS4[i])
    })
    expect(screen.queryByRole('button', { name: tileName(5) })).toBeNull()
  })

  it('prepare with 6 choices shows 6 tiles', async () => {
    const env = setup()
    await choiceRoomPrepared(env, COVERS6)
    expect(koTiles(6).map((t) => t.querySelector('img').getAttribute('src'))).toEqual(COVERS6)
    expect(screen.queryByRole('button', { name: tileName(7) })).toBeNull()
  })

  it('a prepare without choices shows no tiles', async () => {
    const env = setup()
    const socket = await hostLobby(env)
    await clickStartGame(socket)
    deliver(socket, roster(ALONE, 'playing'))
    deliver(socket, { type: 'prepare', index: 0, total: 5, audioUrl: '/audio/s0' })
    await waitFor(() => expect(readiesFor(socket, 0)).toHaveLength(1))
    noTiles()
  })
})

describe('ready waits for the images', () => {
  it('audio loaded, images pending: no ready; one load and errors for the rest send ready once', async () => {
    const env = setup()
    const socket = await hostLobby(env)
    fireEvent.click(within(answerPicker()).getByRole('button', { name: KO.choice }))
    await clickStartGame(socket)
    deliver(socket, roster(ALONE, 'playing'))
    deliver(socket, prepareMsg(0, COVERS4))
    await waitFor(() => expect(env.audio.last.decoded).toHaveLength(1))
    await waitFor(() => expect(preloadsOf(COVERS4)).toHaveLength(4))
    await settle()
    expect(readiesFor(socket, 0)).toEqual([])
    // Three settle (one by error): still waiting for the last one.
    await act(async () => {
      const pending = pendingPreloads(COVERS4)
      pending[0].fire('load')
      pending[1].fire('error')
      pending[2].fire('load')
    })
    await settle()
    expect(readiesFor(socket, 0)).toEqual([])
    await act(async () => pendingPreloads(COVERS4)[0].fire('error'))
    await waitFor(() => expect(readiesFor(socket, 0)).toEqual([{ type: 'ready', index: 0 }]))
    await settle()
    expect(readiesFor(socket, 0)).toHaveLength(1)
  })

  it('images settled, audio pending: ready only after the audio loads', async () => {
    const fetch = createDeferredFetch()
    const env = setup({ fetch })
    const socket = await hostLobby(env)
    fireEvent.click(within(answerPicker()).getByRole('button', { name: KO.choice }))
    await clickStartGame(socket)
    deliver(socket, roster(ALONE, 'playing'))
    deliver(socket, prepareMsg(0, COVERS4))
    await waitFor(() => expect(fetch.pending).toHaveLength(1))
    await settlePreloads(COVERS4)
    await settle()
    expect(readiesFor(socket, 0)).toEqual([])
    await act(async () => fetch.release())
    await waitFor(() => expect(readiesFor(socket, 0)).toEqual([{ type: 'ready', index: 0 }]))
  })

  it('every preload targets one of the choice cover URLs', async () => {
    const env = setup()
    await choiceRoomPrepared(env, COVERS6)
    expect(preloadsOf(COVERS6)).toHaveLength(6)
  })
})

describe('when the tiles work', () => {
  it('disabled after prepare and before the start; disabled while the start is ahead; enabled once the clock passes it', async () => {
    const env = setup()
    const socket = await choiceRoomPrepared(env)
    expect(disabledStates(koTiles(4))).toEqual([true, true, true, true])
    const startClock = env.clock.t
    deliverStart(env, socket, { startInMs: AHEAD })
    await waitFor(() => expect(env.audio.last.startedSources()).toHaveLength(1))
    await settle(300)
    expect(disabledStates(koTiles(4))).toEqual([true, true, true, true])
    env.clock.t = startClock + AHEAD + 100
    await waitFor(() => expect(disabledStates(koTiles(4))).toEqual([false, false, false, false]))
  })

  it('a click while disabled sends nothing', async () => {
    const env = setup()
    const socket = await choiceRoomPrepared(env)
    fireEvent.click(koTiles(4)[0])
    await settle()
    expect(socket.sentOfType('pick')).toEqual([])
  })
})

describe('a pick', () => {
  it('sends one pick{index, choice} (0-based), then every tile is disabled and the tile is marked picked', async () => {
    const env = setup()
    const socket = await choiceSongAudible(env)
    fireEvent.click(koTiles(4)[2])
    await waitFor(() => expect(socket.sentOfType('pick')).toEqual([{ type: 'pick', index: 0, choice: 2 }]))
    expect(disabledStates(koTiles(4))).toEqual([true, true, true, true])
    expect(marks(koTiles(4))).toEqual([null, null, 'picked', null])
    fireEvent.click(koTiles(4)[2])
    fireEvent.click(koTiles(4)[0])
    await settle()
    expect(socket.sentOfType('pick')).toHaveLength(1)
  })

  it('error{too-early, for:"pick"} re-enables the tiles and clears the mark', async () => {
    const env = setup()
    const socket = await choiceSongAudible(env)
    fireEvent.click(koTiles(4)[1])
    await waitFor(() => expect(socket.sentOfType('pick')).toHaveLength(1))
    deliver(socket, { type: 'error', code: 'too-early', for: 'pick' })
    await waitFor(() => expect(disabledStates(koTiles(4))).toEqual([false, false, false, false]))
    expect(marks(koTiles(4))).toEqual([null, null, null, null])
    fireEvent.click(koTiles(4)[3])
    await waitFor(() => expect(socket.sentOfType('pick')).toEqual([
      { type: 'pick', index: 0, choice: 1 },
      { type: 'pick', index: 0, choice: 3 },
    ]))
  })

  it('error{already-picked, for:"pick"} keeps the tiles locked', async () => {
    const env = setup()
    const socket = await choiceSongAudible(env)
    fireEvent.click(koTiles(4)[0])
    await waitFor(() => expect(socket.sentOfType('pick')).toHaveLength(1))
    deliver(socket, { type: 'error', code: 'already-picked', for: 'pick' })
    await settle(300)
    expect(disabledStates(koTiles(4))).toEqual([true, true, true, true])
    fireEvent.click(koTiles(4)[1])
    await settle()
    expect(socket.sentOfType('pick')).toHaveLength(1)
  })

  it('an error for something else leaves the tiles disabled', async () => {
    const env = setup()
    const socket = await choiceSongAudible(env)
    fireEvent.click(koTiles(4)[0])
    await waitFor(() => expect(socket.sentOfType('pick')).toHaveLength(1))
    deliver(socket, { type: 'error', code: 'rate-limited', for: 'skipVote' })
    await settle()
    expect(disabledStates(koTiles(4))).toEqual([true, true, true, true])
    expect(marks(koTiles(4))).toEqual(['picked', null, null, null])
  })

  it('pick-result marks the tile wrong and the tiles stay disabled', async () => {
    const env = setup()
    const socket = await choiceSongAudible(env)
    fireEvent.click(koTiles(4)[1])
    await waitFor(() => expect(socket.sentOfType('pick')).toHaveLength(1))
    deliver(socket, { type: 'pick-result', index: 0, choice: 1 })
    await waitFor(() => expect(marks(koTiles(4))).toEqual([null, 'wrong', null, null]))
    await settle(300)
    expect(disabledStates(koTiles(4))).toEqual([true, true, true, true])
  })
})

describe('the reveal', () => {
  it('marks correctChoice, keeps the own wrong pick, and the grid stays under the reveal card', async () => {
    const env = setup()
    const socket = await choiceSongAudible(env)
    fireEvent.click(koTiles(4)[1])
    await waitFor(() => expect(socket.sentOfType('pick')).toHaveLength(1))
    deliver(socket, { type: 'pick-result', index: 0, choice: 1 })
    deliver(socket, revealMsg(0, { correctChoice: 3 }))
    await waitFor(() => expect(screen.getByText(ko.reveal.heading)).toBeTruthy())
    expect(marks(koTiles(4))).toEqual([null, 'wrong', null, 'correct'])
    expect(disabledStates(koTiles(4))).toEqual([true, true, true, true])
    expect(koTiles(4).map((t) => t.querySelector('img').getAttribute('src'))).toEqual(COVERS4)
  })

  it('without a pick: only the correct tile is marked, every tile disabled', async () => {
    const env = setup()
    const socket = await choiceSongAudible(env)
    deliver(socket, revealMsg(0, { correctChoice: 0 }))
    await waitFor(() => expect(screen.getByText(ko.reveal.heading)).toBeTruthy())
    expect(marks(koTiles(4))).toEqual(['correct', null, null, null])
    expect(disabledStates(koTiles(4))).toEqual([true, true, true, true])
  })

  it('the next prepare replaces the tiles and clears every mark', async () => {
    const env = setup()
    const socket = await choiceSongAudible(env)
    fireEvent.click(koTiles(4)[1])
    await waitFor(() => expect(socket.sentOfType('pick')).toHaveLength(1))
    deliver(socket, { type: 'pick-result', index: 0, choice: 1 })
    deliver(socket, revealMsg(0, { correctChoice: 3 }))
    await waitFor(() => expect(screen.getByText(ko.reveal.heading)).toBeTruthy())
    deliver(socket, prepareMsg(1, COVERS6))
    await waitFor(() => expect(koTiles(6).map((t) => t.querySelector('img').getAttribute('src'))).toEqual(COVERS6))
    expect(marks(koTiles(6))).toEqual([null, null, null, null, null, null])
    expect(disabledStates(koTiles(6))).toEqual([true, true, true, true, true, true])
  })
})

describe('chat in a choice room (owner O1)', () => {
  it('submit sends chat{text}', async () => {
    const env = setup()
    const socket = await choiceSongAudible(env)
    const input = screen.getByLabelText(ko.chat.input)
    fireEvent.change(input, { target: { value: '진격의 거인' } })
    fireEvent.submit(input.closest('form'))
    await waitFor(() => expect(socket.sentOfType('chat')).toEqual([{ type: 'chat', text: '진격의 거인' }]))
    expect(socket.sentOfType('pick')).toEqual([])
  })

  it('after a wrong pick the chat input stays enabled and still sends chat', async () => {
    const env = setup()
    const socket = await choiceSongAudible(env)
    fireEvent.click(koTiles(4)[1])
    await waitFor(() => expect(socket.sentOfType('pick')).toHaveLength(1))
    deliver(socket, { type: 'pick-result', index: 0, choice: 1 })
    await waitFor(() => expect(marks(koTiles(4))).toEqual([null, 'wrong', null, null]))
    const input = screen.getByLabelText(ko.chat.input)
    expect(input.disabled).toBe(false)
    fireEvent.change(input, { target: { value: '진격의 거인' } })
    fireEvent.submit(input.closest('form'))
    await waitFor(() => expect(socket.sentOfType('chat')).toEqual([{ type: 'chat', text: '진격의 거인' }]))
  })

  it('a correct frame from a chat answer, then the reveal: tiles disabled and correctChoice marked', async () => {
    const env = setup()
    const socket = await choiceSongAudible(env)
    deliver(socket, { type: 'correct', index: 0, name: '하루' })
    await waitFor(() => expect(disabledStates(koTiles(4))).toEqual([true, true, true, true]))
    deliver(socket, revealMsg(0, { correctChoice: 2, winner: '하루' }))
    await waitFor(() => expect(screen.getByText(ko.reveal.heading)).toBeTruthy())
    expect(marks(koTiles(4))).toEqual([null, null, 'correct', null])
    expect(disabledStates(koTiles(4))).toEqual([true, true, true, true])
    expect(socket.sentOfType('pick')).toEqual([])
  })
})

describe('watchers', () => {
  it('a late joiner sees the tiles of song.choices, disabled also while the song plays', async () => {
    const env = setup()
    const socket = await guestJoin(env)
    const startAt = T0 + RTT + SYNC - 10_000
    deliver(socket, {
      type: 'snapshot',
      phase: 'playing',
      players: GUEST_PLAYERS,
      song: { index: 2, total: 5, audioUrl: '/audio/watch', startAt, deadlineAt: startAt + 90_000, choices: choicesOf(COVERS4), picked: null },
      scores: [],
    })
    deliver(socket, roster(GUEST_PLAYERS, 'playing'))
    const ctx = env.audio.last
    await waitFor(() => expect(ctx.decoded).toHaveLength(1))
    await waitFor(() => expect(socket.sentOfType('ping').length).toBeGreaterThan(0))
    const ping = socket.sentOfType('ping')[0]
    env.clock.t = T0 + RTT
    deliver(socket, { type: 'pong', id: ping.id, serverTime: T0 + RTT / 2 + SYNC })
    await waitFor(() => expect(ctx.startedSources()).toHaveLength(1))
    await settle(300)
    const list = koTiles(4)
    expect(list.map((t) => t.querySelector('img').getAttribute('src'))).toEqual(COVERS4)
    expect(disabledStates(list)).toEqual([true, true, true, true])
    fireEvent.click(list[0])
    await settle()
    expect(socket.sentOfType('pick')).toEqual([])
  })
})

describe('rejoin', () => {
  beforeEach(() => {
    vi.useFakeTimers(FAKE_TIMERS)
  })

  async function flushUntil(cond, max = 20) {
    for (let i = 0; i < max && !cond(); i++) {
      await act(async () => {
        await vi.advanceTimersByTimeAsync(0)
      })
    }
  }

  async function advance(env, ms) {
    env.clock.t += ms
    await act(async () => {
      await vi.advanceTimersByTimeAsync(ms)
    })
  }

  // The lobby as host 하루 of QWERT with token T1; a drop and, 500 ms later,
  // the first reconnect attempt opened and joined again.
  async function rejoined(env) {
    fireEvent.change(screen.getByLabelText(ko.start.nickname), { target: { value: '하루' } })
    fireEvent.click(screen.getByRole('button', { name: ko.start.createRoom }))
    await flushUntil(() => env.sockets.sockets.length === 1)
    const socket = env.sockets.last
    act(() => socket.open())
    await flushUntil(() => socket.sent.length > 0)
    deliver(socket, { type: 'joined', code: 'QWERT', name: '하루' })
    deliver(socket, roster(ALONE))
    deliver(socket, { type: 'token', token: 'T1' })
    act(() => socket.serverClose(1006))
    await advance(env, 500)
    expect(env.sockets.sockets).toHaveLength(2)
    const attempt = env.sockets.last
    act(() => attempt.open())
    await flushUntil(() => attempt.sent.length > 0)
    expect(attempt.sent).toEqual([{ type: 'rejoin', token: 'T1' }])
    deliver(attempt, { type: 'joined', code: 'QWERT', name: '하루' })
    return attempt
  }

  // A playing snapshot whose start has passed; after the pong the song plays.
  async function playingSnapshot(env, attempt, picked) {
    const startAt = env.clock.t + RTT + SYNC - 10_000
    deliver(attempt, {
      type: 'snapshot',
      phase: 'playing',
      players: ALONE,
      song: { index: 0, total: 5, audioUrl: '/audio/re', startAt, deadlineAt: startAt + 90_000, choices: choicesOf(COVERS4), picked },
      scores: [],
    })
    const ctx = env.audio.last
    await flushUntil(() => ctx.decoded.length === 1)
    const pings = attempt.sentOfType('ping')
    expect(pings.length).toBeGreaterThan(0)
    const ping = pings[pings.length - 1]
    const t0 = env.clock.t
    env.clock.t += RTT
    deliver(attempt, { type: 'pong', id: ping.id, serverTime: t0 + RTT / 2 + SYNC })
    await flushUntil(() => ctx.startedSources().length === 1)
    expect(ctx.startedSources()).toHaveLength(1)
    await advance(env, 300)
  }

  it('picked:null: the snapshot tiles are enabled once the song plays', async () => {
    const env = setup()
    const attempt = await rejoined(env)
    await playingSnapshot(env, attempt, null)
    expect(koTiles(4).map((t) => t.querySelector('img').getAttribute('src'))).toEqual(COVERS4)
    expect(disabledStates(koTiles(4))).toEqual([false, false, false, false])
    expect(marks(koTiles(4))).toEqual([null, null, null, null])
  })

  it('picked:2: tile 보기 3 is marked wrong and every tile stays disabled', async () => {
    const env = setup()
    const attempt = await rejoined(env)
    await playingSnapshot(env, attempt, 2)
    expect(marks(koTiles(4))).toEqual([null, null, 'wrong', null])
    expect(disabledStates(koTiles(4))).toEqual([true, true, true, true])
  })

  // Declared change (100 review M1): the server remembers the pick across a
  // resume's re-prepare of the same index, so already-picked locks the tiles.
  it('song:null snapshot, then prepare for the same index: a pick answered already-picked leaves every tile disabled', async () => {
    const env = setup()
    const attempt = await rejoined(env)
    deliver(attempt, { type: 'snapshot', phase: 'playing', players: ALONE, song: null, scores: [] })
    deliver(attempt, roster(ALONE, 'playing'))
    deliver(attempt, prepareMsg(0, COVERS4))
    await flushUntil(() => preloadsOf(COVERS4).length === 4)
    expect(preloadsOf(COVERS4)).toHaveLength(4)
    await act(async () => {
      for (const img of pendingPreloads(COVERS4)) img.fire('load')
    })
    await flushUntil(() => readiesFor(attempt, 0).length === 1)
    expect(readiesFor(attempt, 0)).toEqual([{ type: 'ready', index: 0 }])
    deliverStart(env, attempt)
    const ctx = env.audio.last
    await flushUntil(() => ctx.startedSources().length === 1)
    expect(ctx.startedSources()).toHaveLength(1)
    await advance(env, 300)
    fireEvent.click(koTiles(4)[1])
    await flushUntil(() => attempt.sentOfType('pick').length === 1)
    expect(attempt.sentOfType('pick')).toEqual([{ type: 'pick', index: 0, choice: 1 }])
    deliver(attempt, { type: 'error', code: 'already-picked', for: 'pick' })
    await advance(env, 300)
    expect(disabledStates(koTiles(4))).toEqual([true, true, true, true])
    fireEvent.click(koTiles(4)[2])
    await advance(env, 50)
    expect(attempt.sentOfType('pick')).toHaveLength(1)
  })
})

describe('other languages', () => {
  it('English: the picker and the tiles read from en.json', async () => {
    const env = setup()
    const socket = await hostLobby(env)
    chooseLanguage('English')
    const group = answerPicker(en)
    expect(within(group).getAllByRole('button').map((b) => b.textContent.trim())).toEqual([
      tr(en, 'answer.chat'),
      tr(en, 'answer.choice'),
    ])
    fireEvent.click(within(group).getByRole('button', { name: tr(en, 'answer.choice') }))
    await clickStartGame(socket, 1, en.lobby.startGame)
    deliver(socket, roster(ALONE, 'playing'))
    deliver(socket, { type: 'settings', answerMode: 'choice' })
    deliver(socket, prepareMsg(0, COVERS4))
    await settlePreloads(COVERS4)
    await waitFor(() => expect(tiles(4, en)).toHaveLength(4))
    expect(settingsLines()).toEqual([tr(en, 'answer.choice')])
  })

  it('Japanese: the picker and the tiles read from ja.json', async () => {
    const env = setup()
    const socket = await hostLobby(env)
    chooseLanguage('日本語')
    const group = answerPicker(ja)
    expect(within(group).getAllByRole('button').map((b) => b.textContent.trim())).toEqual([
      tr(ja, 'answer.chat'),
      tr(ja, 'answer.choice'),
    ])
    fireEvent.click(within(group).getByRole('button', { name: tr(ja, 'answer.choice') }))
    await clickStartGame(socket, 1, ja.lobby.startGame)
    deliver(socket, roster(ALONE, 'playing'))
    deliver(socket, { type: 'settings', answerMode: 'choice' })
    deliver(socket, prepareMsg(0, COVERS6))
    await settlePreloads(COVERS6)
    await waitFor(() => expect(tiles(6, ja)).toHaveLength(6))
  })
})
