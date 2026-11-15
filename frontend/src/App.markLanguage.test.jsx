// Cycle 138 (issue #66): in a choice room's reveal nothing outside the
// card's KO line is Korean on an English or Japanese screen
// (docs/cycles/138-marks-in-language.md, criteria 1 and 2).
//
// - The tile marks: room.css hard-codes the Korean marks as ::before content
//   and is never edited (D-124); choice.css shows the .tmark's data-mark
//   instead (pinned by choiceCss.test.js). jsdom does not compute ::before
//   content, so this file reads data-mark: answer.markCorrect on the correct
//   tile, answer.markWrong on the own wrong pick, empty on every other tile.
// - The cover's .noimg text, its .cap (the default sunset mood shows the
//   title) and the img alt use the reveal's title in the screen's language:
//   titles[lang][0], else the Korean title, else the frame's reveal text.
// - A language switch during the reveal updates both.
//
// The choice-room flow follows App.choice.test.jsx and the language switch
// App.i18n.test.jsx. Timer rule (cycle 039): real timers and waitFor.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, act, cleanup, within } from '@testing-library/react'
import App from './App.jsx'
import ko from './i18n/ko.json'
import en from './i18n/en.json'
import ja from './i18n/ja.json'
import { createSocketFactory, createAudioContextFactory, createFakeFetch } from './testing/fakes.js'
import { createRoomAs, startGame } from './testing/lobby.js'

const T0 = 1_000_000
const COVERS4 = ['/cover/a1', '/cover/b2', '/cover/c3', '/cover/d4']
const TITLES = { ko: ['오버로드 1기'], en: ['Overlord'], ja: ['オーバーロード'], romaji: ['Overlord'] }
const LANGS = [
  ['English', en, 'Overlord'],
  ['日本語', ja, 'オーバーロード'],
  ['한국어', ko, '오버로드 1기'],
]

// The value of a dotted key with {{variables}} filled in.
function tr(dict, key, vars = {}) {
  let value = dict
  for (const part of key.split('.')) {
    value = value !== null && typeof value === 'object' ? value[part] : undefined
  }
  if (typeof value !== 'string') throw new Error(`missing i18n key ${key}`)
  return value.replace(/\{\{\s*([^}\s]+)\s*\}\}/g, (whole, name) => (name in vars ? String(vars[name]) : whole))
}

// A stand-in for the browser's Image that settles at once with load, so the
// choice preloads never hold back ready.
class FakeImage {
  constructor() {
    this.onload = null
    this.onerror = null
    this.currentSrc = ''
  }

  set src(value) {
    this.currentSrc = String(value)
    queueMicrotask(() => {
      if (typeof this.onload === 'function') this.onload({ type: 'load', target: this })
    })
  }

  get src() {
    return this.currentSrc
  }

  addEventListener(type, fn) {
    if (type === 'load') queueMicrotask(() => fn.call(this, { type, target: this }))
  }

  removeEventListener() {}

  decode() {
    return Promise.resolve()
  }
}

beforeEach(() => {
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
  return { sockets, audio, clock }
}

function deliver(socket, msg) {
  act(() => socket.receive(msg))
}

function chooseLanguage(label) {
  const group = screen.getByRole('group', { name: 'Language' })
  fireEvent.click(within(group).getByRole('button', { name: label }))
}

const ALONE = [{ name: '하루', score: 0, host: true, connected: true }]
const roster = (phase) => ({ type: 'roster', phase, players: ALONE })

function tiles(dict) {
  return COVERS4.map((_, i) => screen.getByRole('button', { name: tr(dict, 'answer.choiceN', { n: i + 1 }) }))
}

function dataMarks(dict) {
  return tiles(dict).map((tile) => tile.querySelector('.tmark').getAttribute('data-mark'))
}

function text(selector) {
  const el = document.querySelector(selector)
  return el === null ? null : el.textContent
}

const revealMsg = (extra = {}) => ({
  type: 'reveal',
  index: 0,
  reveal: '오버로드 1기',
  titles: TITLES,
  song: 'Clattanoia',
  artists: ['OxT'],
  themeType: 'OP',
  themeSlug: 'OP1',
  winner: null,
  coverUrl: null,
  correctChoice: 3,
  ...extra,
})

// In `label`'s language: a choice room alone as host, song 0 audible, tile 2
// picked and judged wrong (pick-result). Returns the socket.
async function pickedWrong(env, label, dict) {
  chooseLanguage(label)
  createRoomAs('하루', dict)
  await waitFor(() => expect(env.sockets.sockets.length).toBe(1))
  const socket = env.sockets.last
  act(() => socket.open())
  await waitFor(() => expect(socket.sent.length).toBeGreaterThan(0))
  deliver(socket, { type: 'joined', code: 'QWERT', name: '하루' })
  deliver(socket, roster('lobby'))
  const picker = screen.getByRole('group', { name: tr(dict, 'answer.label') })
  fireEvent.click(within(picker).getByRole('button', { name: tr(dict, 'answer.choice') }))
  startGame()
  await waitFor(() => expect(socket.sentOfType('start')).toHaveLength(1))
  deliver(socket, roster('playing'))
  deliver(socket, { type: 'settings', answerMode: 'choice' })
  deliver(socket, {
    type: 'prepare',
    index: 0,
    total: 5,
    audioUrl: '/audio/s0',
    choices: COVERS4.map((coverUrl) => ({ coverUrl })),
  })
  await waitFor(() => expect(socket.sentOfType('ready')).toEqual([{ type: 'ready', index: 0 }]))
  const serverTime = env.clock.t + 500
  deliver(socket, { type: 'start', index: 0, startAt: serverTime, deadlineAt: serverTime + 90_000, serverTime })
  await waitFor(() => expect(env.audio.last.startedSources()).toHaveLength(1))
  await waitFor(() => expect(tiles(dict).every((tile) => !tile.disabled)).toBe(true))
  expect(dataMarks(dict)).toEqual(['', '', '', ''])
  fireEvent.click(tiles(dict)[1])
  await waitFor(() => expect(socket.sentOfType('pick')).toHaveLength(1))
  deliver(socket, { type: 'pick-result', index: 0, choice: 1 })
  await waitFor(() => expect(tiles(dict)[1].classList.contains('wrong')).toBe(true))
  return socket
}

describe('dictionaries', () => {
  it('ko, en and ja carry the tile marks; Korean matches room.css', () => {
    expect(tr(ko, 'answer.markCorrect')).toBe(`${String.fromCharCode(0x2714)} 정답`)
    expect(tr(ko, 'answer.markWrong')).toBe(`${String.fromCharCode(0x2715)} 오답`)
    expect(tr(en, 'answer.markCorrect')).toBe(`${String.fromCharCode(0x2714)} Correct`)
    expect(tr(en, 'answer.markWrong')).toBe(`${String.fromCharCode(0x2715)} Wrong`)
    expect(tr(ja, 'answer.markCorrect')).toBe(`${String.fromCharCode(0x2714)} 正解`)
    expect(tr(ja, 'answer.markWrong')).toBe(`${String.fromCharCode(0x2715)} 不正解`)
  })
})

describe.each(LANGS)('%s', (label, dict, title) => {
  it('the own wrong pick reads the wrong mark before the reveal', async () => {
    const env = setup()
    await pickedWrong(env, label, dict)
    expect(dataMarks(dict)).toEqual(['', tr(dict, 'answer.markWrong'), '', ''])
  })

  it('after the reveal the marks, the no-image text, the caption and the alt are in the language', async () => {
    const env = setup()
    const socket = await pickedWrong(env, label, dict)
    deliver(socket, revealMsg())
    await waitFor(() => expect(screen.getByText(tr(dict, 'reveal.heading'))).toBeTruthy())
    expect(dataMarks(dict)).toEqual(['', tr(dict, 'answer.markWrong'), '', tr(dict, 'answer.markCorrect')])
    await waitFor(() => expect(text('.deck .cover .noimg')).toBe(title))
    expect(text('.deck .cover .cap')).toBe(title)
    expect(document.querySelector('.deck .cover img').getAttribute('alt')).toBe(tr(dict, 'reveal.coverAlt', { title }))
  })
})

describe('fallback to Korean', () => {
  it.each([
    ['English', en, { ko: ['오버로드 1기'], ja: ['オーバーロード'] }],
    ['日本語', ja, { ko: ['오버로드 1기'], en: ['Overlord'], ja: [''] }],
  ])('%s with no title in the language: the no-image text and the caption are Korean', async (label, dict, titles) => {
    const env = setup()
    const socket = await pickedWrong(env, label, dict)
    deliver(socket, revealMsg({ titles }))
    await waitFor(() => expect(text('.deck .cover .noimg')).toBe('오버로드 1기'))
    expect(text('.deck .cover .cap')).toBe('오버로드 1기')
  })

  it('English with no titles at all: the frame reveal text', async () => {
    const env = setup()
    const socket = await pickedWrong(env, 'English', en)
    deliver(socket, revealMsg({ titles: {} }))
    await waitFor(() => expect(text('.deck .cover .noimg')).toBe('오버로드 1기'))
    expect(text('.deck .cover .cap')).toBe('오버로드 1기')
  })
})

describe('a switch during the reveal', () => {
  it('Korean to English: the marks and the caption follow', async () => {
    const env = setup()
    const socket = await pickedWrong(env, '한국어', ko)
    deliver(socket, revealMsg())
    await waitFor(() => expect(text('.deck .cover .cap')).toBe('오버로드 1기'))
    expect(dataMarks(ko)).toEqual(['', tr(ko, 'answer.markWrong'), '', tr(ko, 'answer.markCorrect')])
    chooseLanguage('English')
    await waitFor(() => expect(text('.deck .cover .cap')).toBe('Overlord'))
    expect(text('.deck .cover .noimg')).toBe('Overlord')
    expect(dataMarks(en)).toEqual(['', tr(en, 'answer.markWrong'), '', tr(en, 'answer.markCorrect')])
  })
})
