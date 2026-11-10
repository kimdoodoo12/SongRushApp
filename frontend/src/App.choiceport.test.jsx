// Cycle 112 (D-124, D-123): a choice room uses the mockup's choice markup and
// behaviour (docs/design/108-ui-design-reference.html: renderChoices,
// pickChoice, flyIn, setMode, renderStage). Spec docs/cycles/112-choice-port.md,
// with its "Spec review" section.
//
// Contract with the implementer:
// - body.choice-mode is on while the current game is a choice game (settings
//   answerMode "choice" with a prepare carrying choices), off in a chat game,
//   and off again after leaving the game screen (대기실로).
// - The tiles are div.choices.k<k> (k = the number of choices), the third
//   child of .now (after .deck and .panel). Tile N (1-based) is a
//   button.tile with aria-label answer.choiceN (보기 N) holding, in order,
//   span.tframe > img (alt "", src the choice's coverUrl), span.tno with the
//   text N, and span.tmark. A null cover URL (the session drops a URL that
//   breaks its rule) or an img that fired error leaves .tframe with no img.
// - Marks: the classes correct, wrong, dim and locked on .tile. The first
//   matching case applies:
//   1. reveal: every tile disabled; correctChoice .correct, the own wrong
//      pick .wrong, every other tile .dim;
//   2. own wrong pick (pick-result, or a rejoin's song.picked) before the
//      reveal: every tile disabled; the picked tile .wrong, the others
//      .locked;
//   3. own pick sent, no pick-result yet: every tile disabled; the picked
//      tile has no mark class, the others .locked;
//   4. picking not allowed (not audible, watching, someone already correct,
//      already-picked with no own pick): every tile disabled, no mark class.
//   Before the reveal nothing marks the correct tile (D-111, A9): no class
//   and no attribute differs between tiles except aria-label.
// - Panel texts in a choice game (i18n keys pinned here, values in ko as the
//   mockup's renderStage):
//     stage.choiceSoon  waiting hint (where a chat game shows stage.soon):
//                       곡이 나오면 표지 중에서 그 애니를 골라 보세요
//     stage.choiceHint  playing hint: 들리는 곡의 애니를 아래 표지에서 골라 보세요
//     stage.choiceSub   playing sub: the mockup's sub text
//     stage.pickWrong   p.pickmsg after a wrong pick, with {{n}} = N:
//                       {{n}}번은 오답이에요. 정답 공개를 기다려 주세요.
//   en and ja carry the same keys.
// - Fly-in (the mockup's flyIn): at the reveal, when the correct tile exists
//   and matchMedia('(prefers-reduced-motion: reduce)') does not match, in a
//   requestAnimationFrame callback the tile's box (the tile or its .tframe)
//   and .cover's box are read; when either has zero width nothing happens;
//   otherwise .cover.animate(keyframes, {duration: 750, easing:
//   'cubic-bezier(.2,.9,.3,1.1)'}) is called once, keyframes[0] being
//   {transform: 'translate(<dx>px,<dy>px) scale(<sc>)', opacity: .6} from the
//   centres and widths, keyframes[1] having opacity 1. Without
//   Element.prototype.animate (jsdom) it does nothing.
//
// Cycle 114 (D-124, declared): the start button is .start-row .go in every
// language, 곡 수 is the stepper and 곡 종류 a .seg group, all through
// src/testing/lobby.js.
//
// Timer rule (cycle 039): real timers and waitFor.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, act, cleanup, within } from '@testing-library/react'
import App from './App.jsx'
import ko from './i18n/ko.json'
import en from './i18n/en.json'
import ja from './i18n/ja.json'
import { createSocketFactory, createAudioContextFactory, createFakeFetch } from './testing/fakes.js'
import { startButton, startGame } from './testing/lobby.js'

const T0 = 1_000_000
const SYNC = 2500
const RTT = 20

const tileName = (n) => `보기 ${n}`

const COVERS4 = ['/cover/a1', '/cover/b2', '/cover/c3', '/cover/d4']
const COVERS6 = ['/cover/e5', '/cover/f6', '/cover/g7', '/cover/h8', '/cover/i9', '/cover/j10']
const COVERS8 = ['/cover/k1', '/cover/k2', '/cover/k3', '/cover/k4', '/cover/k5', '/cover/k6', '/cover/k7', '/cover/k8']
const choicesOf = (urls) => urls.map((coverUrl) => ({ coverUrl }))

const HINT_SOON = '곡이 나오면 표지 중에서 그 애니를 골라 보세요'
const HINT_PLAY = '들리는 곡의 애니를 아래 표지에서 골라 보세요'
const SUB_PLAY = '한 번만 고를 수 있어요. 틀리면 이번 곡은 끝까지 구경해요. 내가 고른 표지는 다른 사람에게 보이지 않고, 채팅은 대화용이에요.'
const pickMsg = (n) => `${n}번은 오답이에요. 정답 공개를 기다려 주세요.`

const MARKS = ['correct', 'wrong', 'dim', 'locked']

// The value of a dotted key with {{variables}} filled in.
function tr(dict, key, vars = {}) {
  let value = dict
  for (const part of key.split('.')) {
    value = value !== null && typeof value === 'object' ? value[part] : undefined
  }
  if (typeof value !== 'string') throw new Error(`missing i18n key ${key}`)
  return value.replace(/\{\{\s*([^}\s]+)\s*\}\}/g, (whole, name) => (name in vars ? String(vars[name]) : whole))
}

// A stand-in for the browser's Image (the preloads); fire() settles one.
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

async function settlePreloads(urls) {
  await waitFor(() => expect(preloadsOf(urls).length).toBeGreaterThanOrEqual(urls.length))
  await act(async () => {
    for (const img of preloadsOf(urls).filter((i) => i.settled === null)) img.fire('load')
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
  const group = screen.queryByRole('group', { name: 'Language' })
  if (group !== null) {
    const korean = within(group).queryByRole('button', { name: '한국어' })
    if (korean !== null) fireEvent.click(korean)
  }
  cleanup()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
  delete window.matchMedia
  delete Element.prototype.animate
  document.body.classList.remove('choice-mode')
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

async function hostLobby(env, { players = ALONE } = {}) {
  fireEvent.change(screen.getByLabelText(ko.start.nickname), { target: { value: '하루' } })
  fireEvent.click(screen.getByRole('button', { name: ko.start.createRoom }))
  const socket = await openSocket(env)
  deliver(socket, { type: 'joined', code: 'QWERT', name: '하루' })
  deliver(socket, roster(players))
  return socket
}

async function guestJoin(env) {
  fireEvent.change(screen.getByLabelText(ko.start.nickname), { target: { value: 'Hana' } })
  fireEvent.change(screen.getByLabelText(ko.start.roomCode), { target: { value: 'ABCDE' } })
  fireEvent.click(screen.getByRole('button', { name: ko.start.join }))
  const socket = await openSocket(env)
  deliver(socket, { type: 'joined', code: 'ABCDE', name: 'Hana' })
  return socket
}

async function clickStartGame(socket) {
  startGame()
  await waitFor(() => expect(socket.sentOfType('start')).toHaveLength(1))
}

const prepareMsg = (index, choices) => ({
  type: 'prepare',
  index,
  total: 5,
  audioUrl: `/audio/s${index}`,
  ...(choices === null ? {} : { choices }),
})

function readiesFor(socket, index) {
  return socket.sentOfType('ready').filter((m) => m.index === index)
}

function deliverStart(env, socket, { index = 0 } = {}) {
  const serverTime = env.clock.t + 500
  deliver(socket, { type: 'start', index, startAt: serverTime, deadlineAt: serverTime + 90_000, serverTime })
}

function koTiles(count) {
  return Array.from({ length: count }, (_, i) => screen.getByRole('button', { name: tileName(i + 1) }))
}

function disabledStates(list) {
  return list.map((b) => b.disabled)
}

// The tile's mark class (correct, wrong, dim or locked), null with none;
// two at once are joined with a space, so they fail any expectation.
function marks(list) {
  return list.map((b) => {
    const found = MARKS.filter((m) => b.classList.contains(m))
    return found.length === 0 ? null : found.join(' ')
  })
}

function panelHint() {
  const el = document.querySelector('.panel .hint')
  return el === null ? null : el.textContent
}

function panelSubs() {
  return Array.from(document.querySelectorAll('.panel .sub')).map((el) => el.textContent)
}

function pickMsgs() {
  return Array.from(document.querySelectorAll('.panel .pickmsg')).map((el) => el.textContent)
}

const choiceMode = () => document.body.classList.contains('choice-mode')

// Alone as host with 객관식 chosen, prepare{0} with `urls`, preloads settled
// and ready sent. `valid` are the URLs that are preloaded (all by default).
async function choiceRoomPrepared(env, urls = COVERS4, { dict = ko, players = ALONE, valid = urls } = {}) {
  const socket = await hostLobby(env, { players })
  if (dict === en) chooseLanguage('English')
  const picker = screen.getByRole('group', { name: tr(dict, 'answer.label') })
  fireEvent.click(within(picker).getByRole('button', { name: tr(dict, 'answer.choice') }))
  await clickStartGame(socket)
  deliver(socket, roster(players, 'playing'))
  deliver(socket, { type: 'settings', answerMode: 'choice' })
  deliver(socket, prepareMsg(0, choicesOf(urls)))
  await settlePreloads(valid)
  await waitFor(() => expect(readiesFor(socket, 0)).toEqual([{ type: 'ready', index: 0 }]))
  return socket
}

async function choiceSongAudible(env, urls = COVERS4, options = {}) {
  const socket = await choiceRoomPrepared(env, urls, options)
  const dict = options.dict ?? ko
  const named = () => urls.map((_, i) => screen.getByRole('button', { name: tr(dict, 'answer.choiceN', { n: i + 1 }) }))
  deliverStart(env, socket)
  await waitFor(() => expect(env.audio.last.startedSources()).toHaveLength(1))
  await waitFor(() => expect(disabledStates(named()).every((d) => d === false)).toBe(true))
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

async function revealed(socket, correctChoice, extra = {}) {
  deliver(socket, revealMsg(0, { correctChoice, ...extra }))
  await waitFor(() => expect(screen.getByText(ko.reveal.heading)).toBeTruthy())
}

async function pickWrong(socket, position) {
  fireEvent.click(koTiles(4)[position])
  await waitFor(() => expect(socket.sentOfType('pick')).toEqual([{ type: 'pick', index: 0, choice: position }]))
  deliver(socket, { type: 'pick-result', index: 0, choice: position })
  await waitFor(() => expect(marks(koTiles(4))[position]).toBe('wrong'))
}

describe('dictionaries', () => {
  it('ko carries the choice panel texts of the mockup; en and ja carry the same keys', () => {
    expect(tr(ko, 'stage.choiceSoon')).toBe(HINT_SOON)
    expect(tr(ko, 'stage.choiceHint')).toBe(HINT_PLAY)
    expect(tr(ko, 'stage.choiceSub')).toBe(SUB_PLAY)
    expect(tr(ko, 'stage.pickWrong', { n: 3 })).toBe(pickMsg(3))
    for (const dict of [en, ja]) {
      for (const key of ['stage.choiceSoon', 'stage.choiceHint', 'stage.choiceSub', 'stage.pickWrong']) {
        expect(tr(dict, key).trim(), key).not.toBe('')
        expect(tr(dict, key), key).not.toBe(tr(ko, key))
      }
      expect(tr(dict, 'stage.pickWrong', { n: 3 })).toContain('3')
    }
  })
})

describe('body.choice-mode', () => {
  it('is on in a choice game and off again after 대기실로', async () => {
    const env = setup()
    const socket = await choiceRoomPrepared(env, COVERS4, { players: PAIR })
    expect(choiceMode()).toBe(true)
    deliver(socket, { type: 'gameOver', ranking: [{ rank: 1, name: '하루', score: 0 }, { rank: 2, name: 'Joon', score: 0 }] })
    deliver(socket, roster(PAIR, 'lobby'))
    fireEvent.click(screen.getByRole('button', { name: ko.game.toLobby }))
    await waitFor(() => expect(startButton()).not.toBeNull())
    expect(choiceMode()).toBe(false)
  })

  it('is off in a chat game', async () => {
    const env = setup()
    const socket = await hostLobby(env)
    await clickStartGame(socket)
    deliver(socket, roster(ALONE, 'playing'))
    deliver(socket, { type: 'settings', answerMode: 'chat' })
    deliver(socket, prepareMsg(0, null))
    await waitFor(() => expect(readiesFor(socket, 0)).toHaveLength(1))
    expect(choiceMode()).toBe(false)
  })
})

describe('the markup', () => {
  it('.choices is the third child of .now, with k4, k6 and k8 following k', async () => {
    for (const [urls, k] of [[COVERS4, 4], [COVERS6, 6], [COVERS8, 8]]) {
      const env = setup()
      await choiceRoomPrepared(env, urls)
      const box = document.querySelector('.choices')
      expect(box, `k=${k}`).not.toBeNull()
      expect(box.classList.contains(`k${k}`), `k=${k}`).toBe(true)
      for (const other of [4, 6, 8].filter((n) => n !== k)) expect(box.classList.contains(`k${other}`)).toBe(false)
      expect(document.querySelector('.now').children[2]).toBe(box)
      expect(box.querySelectorAll('.tile')).toHaveLength(k)
      cleanup()
      images.length = 0
    }
  })

  it('the tiles are in server order: button.tile 보기 N with .tframe > img, .tno N and .tmark', async () => {
    const env = setup()
    await choiceRoomPrepared(env, COVERS6)
    const box = document.querySelector('.choices')
    const list = Array.from(box.children)
    expect(list).toEqual(koTiles(6))
    list.forEach((tile, i) => {
      expect(tile.tagName).toBe('BUTTON')
      expect(tile.classList.contains('tile')).toBe(true)
      expect(tile.getAttribute('aria-label')).toBe(tileName(i + 1))
      expect(Array.from(tile.children).map((c) => c.className)).toEqual(['tframe', 'tno', 'tmark'])
      const img = tile.querySelector('.tframe > img')
      expect(img).not.toBeNull()
      expect(img.getAttribute('alt')).toBe('')
      expect(img.getAttribute('src')).toBe(COVERS6[i])
      expect(tile.querySelector('.tno').textContent).toBe(String(i + 1))
    })
  })

  it('a null choice URL gives a tile whose .tframe holds no img', async () => {
    const env = setup()
    const urls = ['/cover/a1', 'https://evil.example/x.png', '/cover/c3', '/cover/d4']
    await choiceRoomPrepared(env, urls, { valid: ['/cover/a1', '/cover/c3', '/cover/d4'] })
    const list = koTiles(4)
    expect(list[1].querySelector('.tframe')).not.toBeNull()
    expect(list[1].querySelector('img')).toBeNull()
    expect(list[1].querySelector('.tno').textContent).toBe('2')
    expect(list[0].querySelector('.tframe > img').getAttribute('src')).toBe('/cover/a1')
  })

  it('an img that fails to load leaves its .tframe empty', async () => {
    const env = setup()
    await choiceRoomPrepared(env, COVERS4)
    const img = koTiles(4)[2].querySelector('.tframe > img')
    fireEvent.error(img)
    await waitFor(() => expect(koTiles(4)[2].querySelector('img')).toBeNull())
    expect(koTiles(4)[2].querySelector('.tframe')).not.toBeNull()
  })
})

describe('secrecy before the reveal (D-111, A9)', () => {
  it('no tile is marked and the tiles differ only in aria-label', async () => {
    const env = setup()
    await choiceSongAudible(env)
    const list = koTiles(4)
    expect(marks(list)).toEqual([null, null, null, null])
    const shape = (b) =>
      b
        .getAttributeNames()
        .filter((n) => n !== 'aria-label')
        .sort()
        .map((n) => `${n}=${b.getAttribute(n)}`)
        .join(';')
    expect(new Set(list.map(shape)).size).toBe(1)
  })

  it('after the own wrong pick only that tile is marked wrong; nothing is correct or dim', async () => {
    const env = setup()
    const socket = await choiceSongAudible(env)
    await pickWrong(socket, 1)
    const list = koTiles(4)
    for (const tile of list) {
      expect(tile.classList.contains('correct')).toBe(false)
      expect(tile.classList.contains('dim')).toBe(false)
    }
    expect(list.filter((t) => t.classList.contains('wrong'))).toEqual([list[1]])
  })
})

describe('marks', () => {
  it('reveal with the own wrong pick: correct, wrong, the rest dim; all disabled', async () => {
    const env = setup()
    const socket = await choiceSongAudible(env)
    await pickWrong(socket, 1)
    await revealed(socket, 3)
    expect(marks(koTiles(4))).toEqual(['dim', 'wrong', 'dim', 'correct'])
    expect(disabledStates(koTiles(4))).toEqual([true, true, true, true])
  })

  it('reveal without a pick: correct and the rest dim; all disabled', async () => {
    const env = setup()
    const socket = await choiceSongAudible(env)
    await revealed(socket, 0)
    expect(marks(koTiles(4))).toEqual(['correct', 'dim', 'dim', 'dim'])
    expect(disabledStates(koTiles(4))).toEqual([true, true, true, true])
  })

  it('own wrong pick before the reveal: the pick wrong, the others locked; all disabled', async () => {
    const env = setup()
    const socket = await choiceSongAudible(env)
    await pickWrong(socket, 2)
    expect(marks(koTiles(4))).toEqual(['locked', 'locked', 'wrong', 'locked'])
    expect(disabledStates(koTiles(4))).toEqual([true, true, true, true])
  })

  it('own pick awaiting its result: the pick unmarked and disabled, the others locked', async () => {
    const env = setup()
    const socket = await choiceSongAudible(env)
    fireEvent.click(koTiles(4)[0])
    await waitFor(() => expect(socket.sentOfType('pick')).toHaveLength(1))
    await waitFor(() => expect(marks(koTiles(4))).toEqual([null, 'locked', 'locked', 'locked']))
    expect(disabledStates(koTiles(4))).toEqual([true, true, true, true])
  })

  it('not audible yet: every tile disabled with no mark', async () => {
    const env = setup()
    await choiceRoomPrepared(env)
    expect(disabledStates(koTiles(4))).toEqual([true, true, true, true])
    expect(marks(koTiles(4))).toEqual([null, null, null, null])
  })

  it('someone already correct: every tile disabled with no mark', async () => {
    const env = setup()
    const socket = await choiceSongAudible(env)
    deliver(socket, { type: 'correct', index: 0, name: '하루' })
    await waitFor(() => expect(disabledStates(koTiles(4))).toEqual([true, true, true, true]))
    expect(marks(koTiles(4))).toEqual([null, null, null, null])
  })

  it('already-picked refusal with no own pick: every tile disabled with no mark', async () => {
    const env = setup()
    const socket = await choiceSongAudible(env)
    fireEvent.click(koTiles(4)[1])
    await waitFor(() => expect(socket.sentOfType('pick')).toHaveLength(1))
    deliver(socket, { type: 'error', code: 'already-picked', for: 'pick' })
    await waitFor(() => expect(marks(koTiles(4))).toEqual([null, null, null, null]))
    expect(disabledStates(koTiles(4))).toEqual([true, true, true, true])
  })

  it('a late joiner watching: every tile disabled with no mark while the song plays', async () => {
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
    expect(disabledStates(koTiles(4))).toEqual([true, true, true, true])
    expect(marks(koTiles(4))).toEqual([null, null, null, null])
  })
})

describe('panel texts', () => {
  it('waiting: the choice waiting hint, not the chat one', async () => {
    const env = setup()
    await choiceRoomPrepared(env)
    await waitFor(() => expect(panelHint()).toBe(HINT_SOON))
  })

  it('playing: the choice hint and the mockup sub text; the skip row stays', async () => {
    const env = setup()
    await choiceSongAudible(env)
    await waitFor(() => expect(panelHint()).toBe(HINT_PLAY))
    expect(panelSubs()).toContain(SUB_PLAY)
    expect(screen.getByRole('button', { name: ko.stage.skip })).toBeTruthy()
    expect(pickMsgs()).toEqual([])
  })

  it('after a wrong pick: p.pickmsg names the tile number', async () => {
    const env = setup()
    const socket = await choiceSongAudible(env)
    await pickWrong(socket, 2)
    await waitFor(() => expect(pickMsgs()).toEqual([pickMsg(3)]))
  })

  it('English: the playing hint reads from en.json', async () => {
    const env = setup()
    await choiceSongAudible(env, COVERS4, { dict: en })
    await waitFor(() => expect(panelHint()).toBe(tr(en, 'stage.choiceHint')))
  })
})

// The mockup's flyIn, with jsdom's missing layout and Web Animations stubbed.
describe('fly-in', () => {
  const TILE_BOX = { left: 10, top: 400, width: 100, height: 120 }
  const COVER_BOX = { left: 50, top: 50, width: 200, height: 240 }

  function rect({ left, top, width, height }) {
    return { x: left, y: top, left, top, width, height, right: left + width, bottom: top + height, toJSON() {} }
  }

  // tileWidth/coverWidth override the width of the tile's (and its
  // descendants') box and .cover's box.
  function stubAll({ reduce = false, tileWidth = TILE_BOX.width, coverWidth = COVER_BOX.width } = {}) {
    const raf = vi.fn((cb) => {
      cb(0)
      return 1
    })
    vi.stubGlobal('requestAnimationFrame', raf)
    window.requestAnimationFrame = raf
    vi.spyOn(Element.prototype, 'getBoundingClientRect').mockImplementation(function box() {
      if (this.closest && this.closest('.tile') !== null) return rect({ ...TILE_BOX, width: tileWidth })
      if (this.classList && this.classList.contains('cover')) return rect({ ...COVER_BOX, width: coverWidth })
      return rect({ left: 0, top: 0, width: 300, height: 300 })
    })
    const targets = []
    const animate = vi.fn(function animate() {
      targets.push(this)
      return { cancel() {}, finish() {}, finished: Promise.resolve(), onfinish: null }
    })
    Element.prototype.animate = animate
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
    return { animate, targets }
  }

  it('the stubs are in place', () => {
    const { animate } = stubAll()
    expect(window.requestAnimationFrame).toBeTypeOf('function')
    expect(document.createElement('div').animate).toBe(animate)
  })

  it('at the reveal .cover animates once from the correct tile, 750 ms, the mockup keyframes and easing', async () => {
    const env = setup()
    const socket = await choiceSongAudible(env)
    const { animate, targets } = stubAll()
    await revealed(socket, 3)
    await waitFor(() => expect(animate).toHaveBeenCalledTimes(1))
    await settle(200)
    expect(animate).toHaveBeenCalledTimes(1)
    expect(targets[0].classList.contains('cover')).toBe(true)
    expect(targets[0]).toBe(document.querySelector('.deck .cover'))
    const [keyframes, options] = animate.mock.calls[0]
    expect(options).toEqual({ duration: 750, easing: 'cubic-bezier(.2,.9,.3,1.1)' })
    expect(keyframes).toHaveLength(2)
    // Centres: tile (60, 460), cover (150, 170); scale 100 / 200.
    expect(keyframes[0]).toEqual({ transform: 'translate(-90px,290px) scale(0.5)', opacity: 0.6 })
    expect(keyframes[1].opacity).toBe(1)
    expect(typeof keyframes[1].transform).toBe('string')
  })

  it('not under reduced motion', async () => {
    const env = setup()
    const socket = await choiceSongAudible(env)
    const { animate } = stubAll({ reduce: true })
    await revealed(socket, 3)
    await settle(300)
    expect(window.matchMedia).toHaveBeenCalledWith('(prefers-reduced-motion: reduce)')
    expect(animate).not.toHaveBeenCalled()
  })

  it('not when the tile box has zero width', async () => {
    const env = setup()
    const socket = await choiceSongAudible(env)
    const { animate } = stubAll({ tileWidth: 0 })
    await revealed(socket, 3)
    await settle(300)
    expect(animate).not.toHaveBeenCalled()
  })

  it('not when the .cover box has zero width', async () => {
    const env = setup()
    const socket = await choiceSongAudible(env)
    const { animate } = stubAll({ coverWidth: 0 })
    await revealed(socket, 3)
    await settle(300)
    expect(animate).not.toHaveBeenCalled()
  })

  it('not in a chat game', async () => {
    const env = setup()
    const socket = await hostLobby(env)
    await clickStartGame(socket)
    deliver(socket, roster(ALONE, 'playing'))
    deliver(socket, prepareMsg(0, null))
    await waitFor(() => expect(readiesFor(socket, 0)).toHaveLength(1))
    deliverStart(env, socket)
    await waitFor(() => expect(env.audio.last.startedSources()).toHaveLength(1))
    const { animate } = stubAll()
    await revealed(socket, undefined)
    await settle(300)
    expect(animate).not.toHaveBeenCalled()
  })
})

describe('chat game unchanged', () => {
  it('a chat game keeps the chat hint and has no .choices', async () => {
    const env = setup()
    const socket = await hostLobby(env)
    await clickStartGame(socket)
    deliver(socket, roster(ALONE, 'playing'))
    deliver(socket, prepareMsg(0, null))
    await waitFor(() => expect(readiesFor(socket, 0)).toHaveLength(1))
    deliverStart(env, socket)
    await waitFor(() => expect(env.audio.last.startedSources()).toHaveLength(1))
    await waitFor(() => expect(panelHint()).toBe(ko.stage.hint))
    expect(document.querySelector('.choices')).toBeNull()
    expect(choiceMode()).toBe(false)
  })
})

