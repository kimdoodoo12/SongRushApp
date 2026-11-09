// Cycle 101 (D-112): game modes, 선착순 (first come, today's rule) and 순위전
// (ranked). Spec docs/cycles/101-game-modes.md; its "Amendments after spec
// review" (R1-R9) override the text above them.
//
// Contract with the implementer:
// - Lobby: the host sees a role=group named mode.label (게임 모드) holding two
//   buttons, mode.first (선착순) and mode.ranked (순위전), with
//   aria-pressed="true" on the current one; 선착순 by default. A guest has no
//   enabled 순위전 button. The host's choice is kept across games and through
//   나가기 like the filters and the answer format. The start request carries
//   gameMode:"ranked" only for 순위전; with 선착순 the field is left out.
// - settings{..., gameMode:"ranked"}: the .settings line gets mode.ranked as
//   its last ' · ' part ('순위전' alone, '오프닝만 · 객관식 · 순위전'). The
//   server also sends settings right after a rejoin snapshot.
// - Ranked is known from settings{gameMode:"ranked"} (sent before the first
//   prepare and after a snapshot); correct{index,name,rank} carries rank.
// - Ranked correct{index,name,rank}:
//   - the chat log adds the system line '✔ ' + chat.correctRanked
//     ('✔ 민수 정답! (1등)');
//   - when name is my own name, my echo chat{name,text}, which the server
//     sends right after, goes just above that line; on every other client the
//     line is already placed, so a later chat from that player appends below
//     it (R3);
//   - the stage shows no chat.correctLine banner ('민수 정답!'), the audio
//     source is not stopped, my choice tiles stay enabled until I pick, and my
//     chat input stays enabled (R4); once I have picked, my tiles are
//     disabled;
//   - the seats mark every correct player with seat.correct (정답!).
// - reveal{..., winner, ranking:[{name,rank,points}]} in ranked: the reveal
//   card holds a role=list named reveal.ranking whose listitems, in order,
//   read reveal.rankLine (whitespace collapsed), and it does not show the
//   single reveal.winner line. An empty ranking shows reveal.noWinner and no
//   ranking list.
// - Rejoin snapshot in ranked (R5): song.answered (bool) and song.correct
//   ([{name, rank}]). The seats get the 정답! tag for every name in correct.
//   In a choice room a held song.picked is shown as data-choice="wrong" only
//   when answered is false; with answered true it is never "wrong". Either
//   way every tile is disabled.
// - First come is unchanged (one regression test here; the rest is covered
//   by App.chatcorrect.test.jsx and App.choice.test.jsx).
//
// i18n keys this file relies on (ko / en / ja):
//   mode.label          게임 모드 / Game mode / ゲームモード
//   mode.first          선착순 / First Come / 早い者勝ち
//   mode.ranked         순위전 / Ranked / 順位戦
//   chat.correctRanked  {{name}} 정답! ({{rank}}등) / {{name}} got it! (#{{rank}}) / {{name}} 正解！（{{rank}}位）
//   reveal.ranking      이번 곡 순위 / This song's ranking / この曲の順位
//   reveal.rankLine     {{rank}}등 {{name}} +{{points}}점 / #{{rank}} {{name}} +{{points}} / {{rank}}位 {{name}} +{{points}}点
//
// declared fix (101): stop assertions ignore the scheduled deadline stop
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
const SYNC = 2500
const RTT = 20

// Korean literals, so a missing key fails on the missing UI.
const KO = { label: '게임 모드', first: '선착순', ranked: '순위전' }
const CHECK = '✔'
const tileName = (n) => `보기 ${n}`

const COVERS4 = ['/cover/a1', '/cover/b2', '/cover/c3', '/cover/d4']
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

const collapse = (s) => s.replace(/\s+/g, ' ').trim()

// A stand-in for the browser's Image (as in App.choice.test.jsx).
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

function preloadsOf(urls) {
  return images.filter((img) => urls.some((url) => img.src !== '' && img.src.endsWith(url)))
}

function pendingPreloads(urls) {
  return preloadsOf(urls).filter((img) => img.settled === null)
}

async function settlePreloads(urls) {
  await waitFor(() => expect(preloadsOf(urls).length).toBeGreaterThanOrEqual(urls.length))
  await act(async () => {
    for (const img of pendingPreloads(urls)) img.fire('load')
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
const TRIO = [player('하루', { host: true }), player('민수'), player('지수')]
const PAIR = [player('하루', { host: true }), player('Joon')]
const GUEST_PLAYERS = [player('Mina', { host: true }), player('Hana')]
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

function modePicker(dict = null) {
  return screen.getByRole('group', { name: dict === null ? KO.label : tr(dict, 'mode.label') })
}

function answerPicker() {
  return screen.getByRole('group', { name: ko.answer.label })
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

function readiesFor(socket, index) {
  return socket.sentOfType('ready').filter((m) => m.index === index)
}

// The server starts the song now (receipt offset 500).
function deliverStart(env, socket, index = 0) {
  const serverTime = env.clock.t + 500
  deliver(socket, { type: 'start', index, startAt: serverTime, deadlineAt: serverTime + 90_000, serverTime })
}

function settingsLines() {
  return Array.from(document.querySelectorAll('.settings')).map((el) => el.textContent)
}

// The chat log lines in order, whitespace collapsed.
function chatLines(dict = ko) {
  const log = screen.getByRole('list', { name: tr(dict, 'chat.label') })
  return within(log)
    .queryAllByRole('listitem')
    .map((li) => collapse(li.textContent))
}

const rankedLine = (name, rank, dict = ko) => `${CHECK} ${tr(dict, 'chat.correctRanked', { name, rank })}`

function seatItems() {
  return within(screen.getByRole('list', { name: ko.seat.list })).getAllByRole('listitem')
}

// The names whose seat carries the 정답! tag, in seat order.
function taggedSeats(names) {
  return names.filter((name) => {
    const seat = seatItems().find((li) => li.textContent.includes(name))
    if (seat === undefined) throw new Error(`no seat for ${name}`)
    return seat.textContent.includes(ko.seat.correct)
  })
}

function koTiles(count) {
  return Array.from({ length: count }, (_, i) => screen.getByRole('button', { name: tileName(i + 1) }))
}

function disabledStates(list) {
  return list.map((b) => b.disabled)
}

function marks(list) {
  return list.map((b) => b.getAttribute('data-choice'))
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

// 하루 hosts 민수 and 지수; the mode picker is set to `mode` (KO label), a
// game of 5 starts and song 0 plays. The server's settings frame follows the
// start for ranked.
async function chatRoomPlaying(env, mode = KO.ranked) {
  const socket = await hostLobby(env, { players: TRIO })
  // 선착순 is the default; the first-come path does not touch the new picker.
  if (mode !== KO.first) fireEvent.click(within(modePicker()).getByRole('button', { name: mode }))
  fireEvent.change(screen.getByLabelText(ko.lobby.songCount), { target: { value: '5' } })
  await clickStartGame(socket)
  deliver(socket, roster(TRIO, 'playing'))
  if (mode === KO.ranked) deliver(socket, { type: 'settings', gameMode: 'ranked' })
  deliver(socket, { type: 'prepare', index: 0, total: 5, audioUrl: '/audio/s0' })
  await waitFor(() => expect(readiesFor(socket, 0)).toEqual([{ type: 'ready', index: 0 }]))
  deliverStart(env, socket)
  await waitFor(() => expect(env.audio.last.startedSources()).toHaveLength(1))
  return socket
}

// A ranked choice room with 하루, 민수 and 지수; song 0 audible with 4 tiles.
async function choiceRoomAudible(env) {
  const socket = await hostLobby(env, { players: TRIO })
  fireEvent.click(within(modePicker()).getByRole('button', { name: KO.ranked }))
  fireEvent.click(within(answerPicker()).getByRole('button', { name: ko.answer.choice }))
  await clickStartGame(socket)
  deliver(socket, roster(TRIO, 'playing'))
  deliver(socket, { type: 'settings', answerMode: 'choice', gameMode: 'ranked' })
  deliver(socket, { type: 'prepare', index: 0, total: 5, audioUrl: '/audio/s0', choices: choicesOf(COVERS4) })
  await settlePreloads(COVERS4)
  await waitFor(() => expect(readiesFor(socket, 0)).toEqual([{ type: 'ready', index: 0 }]))
  deliverStart(env, socket)
  await waitFor(() => expect(env.audio.last.startedSources()).toHaveLength(1))
  await waitFor(() => expect(disabledStates(koTiles(4))).toEqual([false, false, false, false]))
  return socket
}

describe('dictionaries', () => {
  it('ko, en and ja carry the game-mode keys with the decided values', () => {
    expect(tr(ko, 'mode.label')).toBe('게임 모드')
    expect(tr(ko, 'mode.first')).toBe('선착순')
    expect(tr(ko, 'mode.ranked')).toBe('순위전')
    expect(tr(ko, 'chat.correctRanked', { name: '민수', rank: 2 })).toBe('민수 정답! (2등)')
    expect(tr(en, 'mode.label')).toBe('Game mode')
    expect(tr(en, 'mode.first')).toBe('First Come')
    expect(tr(en, 'mode.ranked')).toBe('Ranked')
    expect(tr(en, 'chat.correctRanked', { name: 'Joon', rank: 2 })).toBe('Joon got it! (#2)')
    expect(tr(ja, 'mode.label')).toBe('ゲームモード')
    expect(tr(ja, 'mode.first')).toBe('早い者勝ち')
    expect(tr(ja, 'mode.ranked')).toBe('順位戦')
    expect(tr(ja, 'chat.correctRanked', { name: 'Joon', rank: 2 })).toBe('Joon 正解！（2位）')
  })

  it('reveal.ranking and reveal.rankLine exist in ko, en and ja and fill rank, name and points', () => {
    for (const dict of [ko, en, ja]) {
      expect(tr(dict, 'reveal.ranking').length).toBeGreaterThan(0)
      const line = tr(dict, 'reveal.rankLine', { rank: 7, name: 'Zed', points: 9 })
      expect(line).toContain('7')
      expect(line).toContain('Zed')
      expect(line).toContain('9')
      expect(line).not.toContain('{{')
    }
    expect(tr(ko, 'reveal.rankLine', { rank: 1, name: '민수', points: 3 })).toBe('1등 민수 +3점')
  })
})

describe('lobby picker', () => {
  it('the host sees 게임 모드 with 선착순 and 순위전, 선착순 pressed by default', async () => {
    const env = setup()
    await hostLobby(env)
    const group = modePicker()
    expect(within(group).getAllByRole('button').map((b) => b.textContent.trim())).toEqual([KO.first, KO.ranked])
    expect(pressedIn(group)).toEqual([KO.first])
  })

  it('the default start carries no gameMode', async () => {
    const env = setup()
    const socket = await hostLobby(env)
    expect(await clickStartGame(socket)).toEqual({ type: 'start', songCount: 10 })
  })

  it('순위전 pressed: start carries gameMode:"ranked"', async () => {
    const env = setup()
    const socket = await hostLobby(env)
    fireEvent.click(within(modePicker()).getByRole('button', { name: KO.ranked }))
    expect(pressedIn(modePicker())).toEqual([KO.ranked])
    expect(await clickStartGame(socket)).toEqual({ type: 'start', songCount: 10, gameMode: 'ranked' })
  })

  it('순위전 then 선착순: start carries no gameMode', async () => {
    const env = setup()
    const socket = await hostLobby(env)
    fireEvent.click(within(modePicker()).getByRole('button', { name: KO.ranked }))
    fireEvent.click(within(modePicker()).getByRole('button', { name: KO.first }))
    expect(pressedIn(modePicker())).toEqual([KO.first])
    expect(await clickStartGame(socket)).toEqual({ type: 'start', songCount: 10 })
  })

  it('순위전 with 객관식 and 오프닝만: all go into start', async () => {
    const env = setup()
    const socket = await hostLobby(env)
    fireEvent.change(screen.getByLabelText(ko.filter.type), { target: { value: 'OP' } })
    fireEvent.click(within(answerPicker()).getByRole('button', { name: ko.answer.choice }))
    fireEvent.click(within(modePicker()).getByRole('button', { name: KO.ranked }))
    expect(await clickStartGame(socket)).toEqual({
      type: 'start',
      songCount: 10,
      types: ['OP'],
      answerMode: 'choice',
      gameMode: 'ranked',
    })
  })

  it('the choice is kept after a game and 대기실로, and sent again', async () => {
    const env = setup()
    const socket = await hostLobby(env, { players: PAIR })
    fireEvent.click(within(modePicker()).getByRole('button', { name: KO.ranked }))
    await clickStartGame(socket)
    deliver(socket, roster(PAIR, 'playing'))
    deliver(socket, { type: 'gameOver', ranking: [{ rank: 1, name: '하루', score: 0 }, { rank: 2, name: 'Joon', score: 0 }] })
    deliver(socket, roster(PAIR, 'lobby'))
    fireEvent.click(screen.getByRole('button', { name: ko.game.toLobby }))
    expect(pressedIn(modePicker())).toEqual([KO.ranked])
    expect(await clickStartGame(socket, 2)).toEqual({ type: 'start', songCount: 10, gameMode: 'ranked' })
  })

  it('the choice is kept through 나가기 into a new room', async () => {
    const env = setup()
    await hostLobby(env)
    fireEvent.click(within(modePicker()).getByRole('button', { name: KO.ranked }))
    fireEvent.click(screen.getByRole('button', { name: ko.lobby.leave }))
    const second = await hostLobby(env, { count: 2 })
    expect(second.sent[0]).toEqual({ type: 'create', nickname: '하루' })
    expect(pressedIn(modePicker())).toEqual([KO.ranked])
  })

  it('a guest has no enabled 순위전 button', async () => {
    const env = setup()
    const socket = await guestJoin(env)
    deliver(socket, roster(GUEST_PLAYERS))
    expect(screen.getByText(ko.lobby.waiting)).toBeTruthy()
    const button = screen.queryByRole('button', { name: KO.ranked })
    if (button !== null) expect(button.disabled).toBe(true)
  })
})

describe('the settings line', () => {
  it('a guest sees 순위전 from settings{gameMode:"ranked"}', async () => {
    const env = setup()
    const socket = await guestJoin(env)
    deliver(socket, roster(GUEST_PLAYERS))
    deliver(socket, roster(GUEST_PLAYERS, 'playing'))
    deliver(socket, { type: 'settings', gameMode: 'ranked' })
    deliver(socket, { type: 'prepare', index: 0, total: 5, audioUrl: '/audio/s0' })
    await waitFor(() => expect(settingsLines()).toEqual([KO.ranked]))
  })

  it('with a filter and 객관식 the mode is the last part: 오프닝만 · 객관식 · 순위전', async () => {
    const env = setup()
    const socket = await guestJoin(env)
    deliver(socket, roster(GUEST_PLAYERS))
    deliver(socket, roster(GUEST_PLAYERS, 'playing'))
    deliver(socket, { type: 'settings', types: ['OP'], answerMode: 'choice', gameMode: 'ranked' })
    deliver(socket, { type: 'prepare', index: 0, total: 5, audioUrl: '/audio/s0', choices: choicesOf(COVERS4) })
    await waitFor(() => expect(settingsLines()).toEqual([`${ko.filter.op} · ${ko.answer.choice} · ${KO.ranked}`]))
  })

  it('with a filter alone: 오프닝만 · 순위전', async () => {
    const env = setup()
    const socket = await guestJoin(env)
    deliver(socket, roster(GUEST_PLAYERS))
    deliver(socket, roster(GUEST_PLAYERS, 'playing'))
    deliver(socket, { type: 'settings', types: ['OP'], gameMode: 'ranked' })
    deliver(socket, { type: 'prepare', index: 0, total: 5, audioUrl: '/audio/s0' })
    await waitFor(() => expect(settingsLines()).toEqual([`${ko.filter.op} · ${KO.ranked}`]))
  })
})

describe('ranked correct in a chat room', () => {
  it('correct{민수, rank 1} adds ✔ 민수 정답! (1등)', async () => {
    const env = setup()
    const socket = await chatRoomPlaying(env)
    deliver(socket, { type: 'correct', index: 0, name: '민수', rank: 1 })
    expect(chatLines()).toEqual([rankedLine('민수', 1)])
    expect(chatLines()).toEqual(['✔ 민수 정답! (1등)'])
  })

  it('two correct frames add two lines with their ranks, in order', async () => {
    const env = setup()
    const socket = await chatRoomPlaying(env)
    deliver(socket, { type: 'chat', name: '지수', text: '원피스' })
    deliver(socket, { type: 'correct', index: 0, name: '민수', rank: 1 })
    deliver(socket, { type: 'correct', index: 0, name: '지수', rank: 2 })
    expect(chatLines()).toEqual(['지수: 원피스', '✔ 민수 정답! (1등)', '✔ 지수 정답! (2등)'])
  })

  it("another player's correct shows no stage banner and does not stop the source", async () => {
    const env = setup()
    const socket = await chatRoomPlaying(env)
    const source = env.audio.last.startedSources()[0]
    const before = source.stop.mock.calls.length
    deliver(socket, { type: 'correct', index: 0, name: '민수', rank: 1 })
    await settle(300)
    expect(screen.queryByText(tr(ko, 'chat.correctLine', { name: '민수' }))).toBeNull()
    expect(source.stop.mock.calls.length).toBe(before)
    expect(source.stop.mock.calls.some((args) => args.length === 0)).toBe(false)
    expect(screen.queryByText(ko.reveal.heading)).toBeNull()
  })

  it('my own correct shows no stage banner either, and the source keeps playing', async () => {
    const env = setup()
    const socket = await chatRoomPlaying(env)
    const source = env.audio.last.startedSources()[0]
    const before = source.stop.mock.calls.length
    deliver(socket, { type: 'correct', index: 0, name: '하루', rank: 1 })
    deliver(socket, { type: 'chat', name: '하루', text: '진격의 거인' })
    await settle(300)
    expect(screen.queryByText(tr(ko, 'chat.correctLine', { name: '하루' }))).toBeNull()
    expect(source.stop.mock.calls.length).toBe(before)
    expect(source.stop.mock.calls.some((args) => args.length === 0)).toBe(false)
  })

  it('my own echo right after my correct goes above my system line (R3)', async () => {
    const env = setup()
    const socket = await chatRoomPlaying(env)
    deliver(socket, { type: 'chat', name: '지수', text: '원피스' })
    deliver(socket, { type: 'correct', index: 0, name: '하루', rank: 1 })
    deliver(socket, { type: 'chat', name: '하루', text: '진격의 거인' })
    deliver(socket, { type: 'chat', name: '지수', text: '아깝다' })
    expect(chatLines()).toEqual(['지수: 원피스', '하루: 진격의 거인', '✔ 하루 정답! (1등)', '지수: 아깝다'])
  })

  it("on another client a later chat from the correct player goes below the ✔ line (R3)", async () => {
    const env = setup()
    const socket = await chatRoomPlaying(env)
    deliver(socket, { type: 'correct', index: 0, name: '민수', rank: 1 })
    deliver(socket, { type: 'chat', name: '민수', text: '쉽다' })
    expect(chatLines()).toEqual(['✔ 민수 정답! (1등)', '민수: 쉽다'])
  })

  it('the seats tag every correct player with 정답!', async () => {
    const env = setup()
    const socket = await chatRoomPlaying(env)
    deliver(socket, { type: 'correct', index: 0, name: '민수', rank: 1 })
    await waitFor(() => expect(taggedSeats(['하루', '민수', '지수'])).toEqual(['민수']))
    deliver(socket, { type: 'correct', index: 0, name: '지수', rank: 2 })
    await waitFor(() => expect(taggedSeats(['하루', '민수', '지수'])).toEqual(['민수', '지수']))
    deliver(socket, { type: 'correct', index: 0, name: '하루', rank: 3 })
    await waitFor(() => expect(taggedSeats(['하루', '민수', '지수'])).toEqual(['하루', '민수', '지수']))
  })

  it('the next prepare clears the tags', async () => {
    const env = setup()
    const socket = await chatRoomPlaying(env)
    deliver(socket, { type: 'correct', index: 0, name: '민수', rank: 1 })
    deliver(socket, { type: 'correct', index: 0, name: '지수', rank: 2 })
    deliver(socket, revealMsg(0, { winner: '민수', ranking: [{ name: '민수', rank: 1, points: 3 }, { name: '지수', rank: 2, points: 2 }] }))
    await waitFor(() => expect(screen.getByText(ko.reveal.heading)).toBeTruthy())
    deliver(socket, { type: 'prepare', index: 1, total: 5, audioUrl: '/audio/s1' })
    await waitFor(() => expect(taggedSeats(['하루', '민수', '지수'])).toEqual([]))
  })

  it('after I answer, the chat input stays enabled and still sends chat', async () => {
    const env = setup()
    const socket = await chatRoomPlaying(env)
    deliver(socket, { type: 'correct', index: 0, name: '하루', rank: 1 })
    deliver(socket, { type: 'chat', name: '하루', text: '진격의 거인' })
    const input = screen.getByLabelText(ko.chat.input)
    expect(input.disabled).toBe(false)
    fireEvent.change(input, { target: { value: 'ㅋㅋ' } })
    fireEvent.submit(input.closest('form'))
    await waitFor(() => expect(socket.sentOfType('chat')).toEqual([{ type: 'chat', text: 'ㅋㅋ' }]))
  })
})

describe('ranked correct in a choice room (R4)', () => {
  it("after another player's correct my tiles stay enabled, no banner shows, my chat stays enabled", async () => {
    const env = setup()
    const socket = await choiceRoomAudible(env)
    deliver(socket, { type: 'correct', index: 0, name: '민수', rank: 1 })
    await settle(300)
    expect(disabledStates(koTiles(4))).toEqual([false, false, false, false])
    expect(screen.queryByText(tr(ko, 'chat.correctLine', { name: '민수' }))).toBeNull()
    expect(screen.getByLabelText(ko.chat.input).disabled).toBe(false)
    expect(chatLines()).toEqual(['✔ 민수 정답! (1등)'])
    fireEvent.click(koTiles(4)[2])
    await waitFor(() => expect(socket.sentOfType('pick')).toEqual([{ type: 'pick', index: 0, choice: 2 }]))
  })

  it('once I pick my tiles are disabled; my correct keeps them disabled and tags my seat', async () => {
    const env = setup()
    const socket = await choiceRoomAudible(env)
    fireEvent.click(koTiles(4)[1])
    await waitFor(() => expect(socket.sentOfType('pick')).toHaveLength(1))
    expect(disabledStates(koTiles(4))).toEqual([true, true, true, true])
    deliver(socket, { type: 'correct', index: 0, name: '하루', rank: 2 })
    await settle(300)
    expect(disabledStates(koTiles(4))).toEqual([true, true, true, true])
    expect(marks(koTiles(4))[1]).not.toBe('wrong')
    expect(chatLines()).toEqual(['✔ 하루 정답! (2등)'])
    expect(taggedSeats(['하루', '민수', '지수'])).toEqual(['하루'])
    expect(screen.getByLabelText(ko.chat.input).disabled).toBe(false)
  })
})

describe('the ranked reveal', () => {
  function rankingRows(dict = ko) {
    const list = screen.getByRole('list', { name: tr(dict, 'reveal.ranking') })
    return within(list)
      .getAllByRole('listitem')
      .map((li) => collapse(li.textContent))
  }

  it('lists the ranking (rank, name, +points) in order in place of the winner line', async () => {
    const env = setup()
    const socket = await chatRoomPlaying(env)
    deliver(socket, { type: 'correct', index: 0, name: '민수', rank: 1 })
    deliver(socket, { type: 'correct', index: 0, name: '하루', rank: 2 })
    deliver(socket, { type: 'correct', index: 0, name: '지수', rank: 3 })
    const ranking = [
      { name: '민수', rank: 1, points: 3 },
      { name: '하루', rank: 2, points: 2 },
      { name: '지수', rank: 3, points: 1 },
    ]
    deliver(socket, revealMsg(0, { winner: '민수', ranking }))
    await waitFor(() => expect(screen.getByText(ko.reveal.heading)).toBeTruthy())
    expect(rankingRows()).toEqual(['1등 민수 +3점', '2등 하루 +2점', '3등 지수 +1점'])
    expect(rankingRows()).toEqual(ranking.map((r) => tr(ko, 'reveal.rankLine', r)))
    const card = screen.getByText(ko.reveal.heading).closest('section')
    expect(card).not.toBeNull()
    expect(within(card).getByRole('list', { name: tr(ko, 'reveal.ranking') })).toBeTruthy()
    expect(screen.queryByText(tr(ko, 'reveal.winner', { name: '민수' }))).toBeNull()
  })

  it('a partial ranking (deadline) lists only the players who answered', async () => {
    const env = setup()
    const socket = await chatRoomPlaying(env)
    deliver(socket, { type: 'correct', index: 0, name: '지수', rank: 1 })
    deliver(socket, revealMsg(0, { winner: '지수', ranking: [{ name: '지수', rank: 1, points: 3 }] }))
    await waitFor(() => expect(screen.getByText(ko.reveal.heading)).toBeTruthy())
    expect(rankingRows()).toEqual(['1등 지수 +3점'])
  })

  it('an empty ranking says 정답자 없음 and shows no ranking list', async () => {
    const env = setup()
    const socket = await chatRoomPlaying(env)
    deliver(socket, revealMsg(0, { winner: null, ranking: [] }))
    await waitFor(() => expect(screen.getByText(ko.reveal.heading)).toBeTruthy())
    expect(screen.getByText(ko.reveal.noWinner)).toBeTruthy()
    expect(screen.queryByRole('list', { name: tr(ko, 'reveal.ranking') })).toBeNull()
  })
})

describe('first come is unchanged', () => {
  it('correct{민수} without rank: banner, ✔ 민수 정답! without a rank, only 민수 tagged; start without gameMode', async () => {
    const env = setup()
    const socket = await chatRoomPlaying(env, KO.first)
    expect(socket.sentOfType('start')).toEqual([{ type: 'start', songCount: 5 }])
    deliver(socket, { type: 'correct', index: 0, name: '민수' })
    deliver(socket, { type: 'chat', name: '민수', text: '진격의 거인' })
    expect(chatLines()).toEqual(['민수: 진격의 거인', '✔ 민수 정답!'])
    expect(screen.getAllByText('민수 정답!')).toHaveLength(1)
    await waitFor(() => expect(taggedSeats(['하루', '민수', '지수'])).toEqual(['민수']))
    deliver(socket, revealMsg(0, { winner: '민수' }))
    await waitFor(() => expect(screen.getByText(tr(ko, 'reveal.winner', { name: '민수' }))).toBeTruthy())
    // No ranking list in a first-come reveal card (no new key needed here).
    const card = screen.getByText(ko.reveal.heading).closest('section')
    expect(within(card).queryAllByRole('list')).toEqual([])
  })
})

describe('rejoin (R5)', () => {
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

  // The lobby as host 하루 of QWERT (with Joon) and token T1; a drop and,
  // 500 ms later, the first reconnect attempt opened and joined again.
  async function rejoined(env) {
    fireEvent.change(screen.getByLabelText(ko.start.nickname), { target: { value: '하루' } })
    fireEvent.click(screen.getByRole('button', { name: ko.start.createRoom }))
    await flushUntil(() => env.sockets.sockets.length === 1)
    const socket = env.sockets.last
    act(() => socket.open())
    await flushUntil(() => socket.sent.length > 0)
    deliver(socket, { type: 'joined', code: 'QWERT', name: '하루' })
    deliver(socket, roster(PAIR))
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

  // A ranked playing snapshot whose start has passed, then the settings the
  // server sends after it; after the pong the song plays.
  async function rankedSnapshot(env, attempt, songExtra, settings) {
    const startAt = env.clock.t + RTT + SYNC - 10_000
    deliver(attempt, {
      type: 'snapshot',
      phase: 'playing',
      players: PAIR,
      song: { index: 0, total: 5, audioUrl: '/audio/re', startAt, deadlineAt: startAt + 90_000, ...songExtra },
      scores: [],
    })
    deliver(attempt, { type: 'settings', gameMode: 'ranked', ...settings })
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

  it('chat room: correct:[Joon] restores the tag on Joon only', async () => {
    const env = setup()
    const attempt = await rejoined(env)
    await rankedSnapshot(env, attempt, { answered: false, correct: [{ name: 'Joon', rank: 1 }] }, {})
    expect(taggedSeats(['하루', 'Joon'])).toEqual(['Joon'])
    expect(screen.queryByText(tr(ko, 'chat.correctLine', { name: 'Joon' }))).toBeNull()
    expect(screen.getByLabelText(ko.chat.input).disabled).toBe(false)
  })

  it('chat room: answered with correct:[Joon, 하루] tags both seats', async () => {
    const env = setup()
    const attempt = await rejoined(env)
    await rankedSnapshot(
      env,
      attempt,
      { answered: true, correct: [{ name: 'Joon', rank: 1 }, { name: '하루', rank: 2 }] },
      {},
    )
    expect(taggedSeats(['하루', 'Joon'])).toEqual(['하루', 'Joon'])
  })

  it('choice room: picked 2 with answered:true is not marked wrong; every tile disabled', async () => {
    const env = setup()
    const attempt = await rejoined(env)
    await rankedSnapshot(
      env,
      attempt,
      { choices: choicesOf(COVERS4), picked: 2, answered: true, correct: [{ name: '하루', rank: 1 }] },
      { answerMode: 'choice' },
    )
    expect(marks(koTiles(4))[2]).not.toBe('wrong')
    expect(marks(koTiles(4)).includes('wrong')).toBe(false)
    expect(disabledStates(koTiles(4))).toEqual([true, true, true, true])
    expect(taggedSeats(['하루', 'Joon'])).toEqual(['하루'])
  })

  it('choice room: picked 2 with answered:false is marked wrong; every tile disabled', async () => {
    const env = setup()
    const attempt = await rejoined(env)
    await rankedSnapshot(
      env,
      attempt,
      { choices: choicesOf(COVERS4), picked: 2, answered: false, correct: [{ name: 'Joon', rank: 1 }] },
      { answerMode: 'choice' },
    )
    expect(marks(koTiles(4))).toEqual([null, null, 'wrong', null])
    expect(disabledStates(koTiles(4))).toEqual([true, true, true, true])
    expect(taggedSeats(['하루', 'Joon'])).toEqual(['Joon'])
  })

  it("choice room: picked null after another player's correct: my tiles are enabled", async () => {
    const env = setup()
    const attempt = await rejoined(env)
    await rankedSnapshot(
      env,
      attempt,
      { choices: choicesOf(COVERS4), picked: null, answered: false, correct: [{ name: 'Joon', rank: 1 }] },
      { answerMode: 'choice' },
    )
    expect(disabledStates(koTiles(4))).toEqual([false, false, false, false])
  })
})

describe('other languages', () => {
  it('English: the picker, the settings line and the ranked line read from en.json', async () => {
    const env = setup()
    const socket = await hostLobby(env, { players: TRIO })
    chooseLanguage('English')
    const group = modePicker(en)
    expect(within(group).getAllByRole('button').map((b) => b.textContent.trim())).toEqual([
      tr(en, 'mode.first'),
      tr(en, 'mode.ranked'),
    ])
    fireEvent.click(within(group).getByRole('button', { name: tr(en, 'mode.ranked') }))
    expect(await clickStartGame(socket, 1, en.lobby.startGame)).toEqual({ type: 'start', songCount: 10, gameMode: 'ranked' })
    deliver(socket, roster(TRIO, 'playing'))
    deliver(socket, { type: 'settings', gameMode: 'ranked' })
    deliver(socket, { type: 'prepare', index: 0, total: 5, audioUrl: '/audio/s0' })
    await waitFor(() => expect(settingsLines()).toEqual([tr(en, 'mode.ranked')]))
    await waitFor(() => expect(readiesFor(socket, 0)).toHaveLength(1))
    deliverStart(env, socket)
    deliver(socket, { type: 'correct', index: 0, name: '민수', rank: 2 })
    expect(chatLines(en)).toEqual([rankedLine('민수', 2, en)])
    expect(chatLines(en)).toEqual(['✔ 민수 got it! (#2)'])
    deliver(socket, revealMsg(0, { winner: '지수', ranking: [{ name: '지수', rank: 1, points: 3 }, { name: '민수', rank: 2, points: 2 }] }))
    const list = await waitFor(() => screen.getByRole('list', { name: tr(en, 'reveal.ranking') }))
    expect(within(list).getAllByRole('listitem').map((li) => collapse(li.textContent))).toEqual([
      tr(en, 'reveal.rankLine', { rank: 1, name: '지수', points: 3 }),
      tr(en, 'reveal.rankLine', { rank: 2, name: '민수', points: 2 }),
    ])
  })

  it('Japanese: the picker, the settings line and the ranked line read from ja.json', async () => {
    const env = setup()
    const socket = await hostLobby(env, { players: TRIO })
    chooseLanguage('日本語')
    const group = modePicker(ja)
    expect(within(group).getAllByRole('button').map((b) => b.textContent.trim())).toEqual([
      tr(ja, 'mode.first'),
      tr(ja, 'mode.ranked'),
    ])
    fireEvent.click(within(group).getByRole('button', { name: tr(ja, 'mode.ranked') }))
    await clickStartGame(socket, 1, ja.lobby.startGame)
    deliver(socket, roster(TRIO, 'playing'))
    deliver(socket, { type: 'settings', gameMode: 'ranked' })
    deliver(socket, { type: 'prepare', index: 0, total: 5, audioUrl: '/audio/s0' })
    await waitFor(() => expect(settingsLines()).toEqual([tr(ja, 'mode.ranked')]))
    await waitFor(() => expect(readiesFor(socket, 0)).toHaveLength(1))
    deliverStart(env, socket)
    deliver(socket, { type: 'correct', index: 0, name: '민수', rank: 3 })
    expect(chatLines(ja)).toEqual(['✔ 민수 正解！（3位）'])
  })
})

// review round 2 C1/C2 (docs/cycles/101-game-modes.md "Review round 2"):
// C1 - in a ranked choice room no echo follows my correct pick, so my own
// system line is created as already placed and a later chat of mine goes
// below it. C2 - a ranked prepare (also a resume's re-prepare of the same
// index) may carry correct:[{name, rank}]; the client restores the Seats
// tags and its own answered state from it (my tiles stay disabled, never
// marked wrong).
describe('review round 2 C1: my correct pick in a ranked choice room', () => {
  it('a later chat of mine goes below my ✔ line', async () => {
    const env = setup()
    const socket = await choiceRoomAudible(env)
    fireEvent.click(koTiles(4)[1])
    await waitFor(() => expect(socket.sentOfType('pick')).toHaveLength(1))
    deliver(socket, { type: 'correct', index: 0, name: '하루', rank: 1 })
    deliver(socket, { type: 'chat', name: '하루', text: 'ㅋㅋ' })
    expect(chatLines()).toEqual(['✔ 하루 정답! (1등)', '하루: ㅋㅋ'])
  })

  it('the ranked chat format is unchanged: there my echo still goes above my line', async () => {
    const env = setup()
    const socket = await chatRoomPlaying(env)
    deliver(socket, { type: 'correct', index: 0, name: '하루', rank: 1 })
    deliver(socket, { type: 'chat', name: '하루', text: '진격의 거인' })
    expect(chatLines()).toEqual(['하루: 진격의 거인', '✔ 하루 정답! (1등)'])
  })
})

describe('review round 2 C2: prepare carries correct', () => {
  const TRIO_NAMES = ['하루', '민수', '지수']

  // A re-prepare of song 0 in a ranked choice room (a resume): the preloads
  // settle, ready is sent again and the song starts and becomes audible.
  async function rePrepareChoice(env, socket, correct) {
    deliver(socket, { type: 'prepare', index: 0, total: 5, audioUrl: '/audio/s0', choices: choicesOf(COVERS4), correct })
    await settlePreloads(COVERS4)
    await waitFor(() => expect(readiesFor(socket, 0)).toHaveLength(2))
    deliverStart(env, socket)
    await waitFor(() => expect(env.audio.last.startedSources().length).toBeGreaterThanOrEqual(2))
    await settle(300)
  }

  it('chat room: a re-prepare with correct:[민수, 지수] restores both tags', async () => {
    const env = setup()
    const socket = await chatRoomPlaying(env)
    deliver(socket, { type: 'prepare', index: 0, total: 5, audioUrl: '/audio/s0', correct: [{ name: '민수', rank: 1 }, { name: '지수', rank: 2 }] })
    await waitFor(() => expect(taggedSeats(TRIO_NAMES)).toEqual(['민수', '지수']))
  })

  it('chat room: after the resume the tags still stand once the song starts again', async () => {
    const env = setup()
    const socket = await chatRoomPlaying(env)
    deliver(socket, { type: 'correct', index: 0, name: '민수', rank: 1 })
    deliver(socket, { type: 'prepare', index: 0, total: 5, audioUrl: '/audio/s0', correct: [{ name: '민수', rank: 1 }] })
    await waitFor(() => expect(readiesFor(socket, 0)).toHaveLength(2))
    deliverStart(env, socket)
    await settle(300)
    expect(taggedSeats(TRIO_NAMES)).toEqual(['민수'])
  })

  it('choice room: my name in correct keeps my tiles disabled, never marked wrong, and tags my seat', async () => {
    const env = setup()
    const socket = await choiceRoomAudible(env)
    fireEvent.click(koTiles(4)[1])
    await waitFor(() => expect(socket.sentOfType('pick')).toHaveLength(1))
    deliver(socket, { type: 'correct', index: 0, name: '하루', rank: 1 })
    await rePrepareChoice(env, socket, [{ name: '하루', rank: 1 }, { name: '민수', rank: 2 }])
    expect(disabledStates(koTiles(4))).toEqual([true, true, true, true])
    expect(marks(koTiles(4)).includes('wrong')).toBe(false)
    expect(taggedSeats(TRIO_NAMES)).toEqual(['하루', '민수'])
    fireEvent.click(koTiles(4)[2])
    await settle()
    expect(socket.sentOfType('pick')).toHaveLength(1)
  })

  it('choice room: a fresh client whose name is in correct is treated as answered', async () => {
    const env = setup()
    const socket = await hostLobby(env, { players: TRIO })
    fireEvent.click(within(modePicker()).getByRole('button', { name: KO.ranked }))
    fireEvent.click(within(answerPicker()).getByRole('button', { name: ko.answer.choice }))
    await clickStartGame(socket)
    deliver(socket, roster(TRIO, 'playing'))
    deliver(socket, { type: 'settings', answerMode: 'choice', gameMode: 'ranked' })
    deliver(socket, { type: 'prepare', index: 0, total: 5, audioUrl: '/audio/s0', choices: choicesOf(COVERS4), correct: [{ name: '하루', rank: 1 }] })
    await settlePreloads(COVERS4)
    await waitFor(() => expect(readiesFor(socket, 0)).toHaveLength(1))
    deliverStart(env, socket)
    await waitFor(() => expect(env.audio.last.startedSources()).toHaveLength(1))
    await settle(300)
    expect(disabledStates(koTiles(4))).toEqual([true, true, true, true])
    expect(marks(koTiles(4)).includes('wrong')).toBe(false)
    expect(taggedSeats(TRIO_NAMES)).toEqual(['하루'])
  })

  it('choice room: correct without my name leaves my tiles enabled and tags only the others', async () => {
    const env = setup()
    const socket = await choiceRoomAudible(env)
    deliver(socket, { type: 'correct', index: 0, name: '민수', rank: 1 })
    await rePrepareChoice(env, socket, [{ name: '민수', rank: 1 }])
    expect(disabledStates(koTiles(4))).toEqual([false, false, false, false])
    expect(taggedSeats(TRIO_NAMES)).toEqual(['민수'])
  })
})
