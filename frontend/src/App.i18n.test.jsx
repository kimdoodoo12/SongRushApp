// Cycle 081 (D-095): the UI in Korean, English and Japanese. Korean is the
// default in test mode; the footer switcher (role=group, aria-label
// "Language") changes the language and stores it in
// localStorage['songrush.lang']; a stored choice applies when App mounts.
// The reveal card shows the KO, EN, JA and Romaji titles; the room theme
// survives a language switch and the picker names follow the language.
//
// Expected texts come from the dictionaries through these keys (nested JSON,
// i18next's default '.' separator), so the implementer must use exactly them:
//   start.createRoom, start.nickname, stage.hint, stage.skip,
//   stage.skipCount ({{votes}}, {{needed}}), chat.correctLine ({{name}}),
//   reveal.heading, reveal.label.ko, reveal.label.en, reveal.label.ja,
//   reveal.label.romaji, theme.label, and theme.<id> for the eight moods
//   sunset, rain, sakura, neon, chalk, pixel, matsuri, manga (cycle 109).
// Markup contract: the system chat line reads '✔ ' + chat.correctLine; each
// reveal title line is one element holding its label (an element of its own
// with exactly the label text) and its title; the card is the nearest
// <section> around the reveal.heading text; the big title is a heading whose
// accessible name is exactly the title.
// Cycle 110 (D-124, declared): the mockup's markup replaces that contract.
// The chat lines are div.msg in .msgs. The card is the .reveal element
// around the reveal.heading text; each title line is a <dt> with exactly the
// label text followed by its <dd> with the title, in dl.titles; the Korean
// title is dd.ko. The card has no big title heading (the mockup has none);
// the slug, song and artist are on the .theme-line.
// Timer rule (cycle 039): real timers and waitFor.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, act, cleanup, within } from '@testing-library/react'
import App from './App.jsx'
import ko from './i18n/ko.json'
import en from './i18n/en.json'
import ja from './i18n/ja.json'
import { createSocketFactory, createAudioContextFactory, createFakeFetch } from './testing/fakes.js'
import { createRoomAs } from './testing/lobby.js'

const T0 = 1_000_000
const LANG_KEY = 'songrush.lang'
const THEME_KEY = 'songrush.roomTheme'
const HANGUL = /[ㄱ-ㅣ가-힣]/
const THEME_IDS = ['sunset', 'rain', 'sakura', 'neon', 'chalk', 'pixel', 'matsuri', 'manga']

// The value of a dotted key with {{variables}} filled in.
function tr(dict, key, vars = {}) {
  let value = dict
  for (const part of key.split('.')) {
    value = value !== null && typeof value === 'object' ? value[part] : undefined
  }
  if (typeof value !== 'string') throw new Error(`missing i18n key ${key}`)
  return value.replace(/\{\{\s*([^}\s]+)\s*\}\}/g, (whole, name) => (name in vars ? String(vars[name]) : whole))
}

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

function switcher() {
  return screen.getByRole('group', { name: 'Language' })
}

function chooseLanguage(label) {
  fireEvent.click(within(switcher()).getByRole('button', { name: label }))
}

function pressedLanguages() {
  return within(switcher())
    .getAllByRole('button')
    .filter((b) => b.getAttribute('aria-pressed') === 'true')
    .map((b) => b.textContent.trim())
}

// The page text without the switcher (its 한국어 button is Korean in every UI).
function pageTextWithoutSwitcher() {
  const switcherText = switcher().textContent
  return document.body.textContent.replace(switcherText, '')
}

// The applied mood: <html data-mood> (cycle 110, D-124).
function rootTheme() {
  expect(document.querySelector('[data-room-theme]')).toBeNull()
  return document.documentElement.dataset.mood
}

function picker(dict) {
  return screen.getByRole('group', { name: tr(dict, 'theme.label') })
}

function pickerNames(dict) {
  return within(picker(dict))
    .getAllByRole('button')
    .map((b) => b.textContent.trim())
}

function pressedThemes(dict) {
  return within(picker(dict))
    .getAllByRole('button')
    .filter((b) => b.getAttribute('aria-pressed') === 'true')
    .map((b) => b.textContent.trim())
}

const player = (name, { host = false } = {}) => ({ name, score: 0, host, connected: true })
const PLAYERS = [player('Kai', { host: true }), player('Mina'), player('Joon')]
const roster = (phase) => ({ type: 'roster', phase, players: PLAYERS })

// Creates room QWERT as Kai through the labels of `dict`; returns the socket
// with the lobby roster delivered.
async function hostLobby(env, dict) {
  createRoomAs('Kai', dict)
  await waitFor(() => expect(env.sockets.sockets.length).toBe(1))
  const socket = env.sockets.last
  act(() => socket.open())
  await waitFor(() => expect(socket.sentOfType('create')).toHaveLength(1))
  deliver(socket, { type: 'joined', code: 'QWERT', name: 'Kai' })
  deliver(socket, roster('lobby'))
  return socket
}

// The lobby, then song 0 prepared, ready sent and started at once.
async function songStarted(env, dict) {
  const socket = await hostLobby(env, dict)
  deliver(socket, roster('playing'))
  deliver(socket, { type: 'prepare', index: 0, total: 5, audioUrl: '/audio/abc' })
  await waitFor(() => expect(socket.sentOfType('ready')).toEqual([{ type: 'ready', index: 0 }]))
  const serverTime = T0 + 500
  deliver(socket, { type: 'start', index: 0, startAt: serverTime, deadlineAt: serverTime + 90_000, serverTime })
  await waitFor(() => expect(env.audio.last.startedSources()).toHaveLength(1))
  return socket
}

const ALL_TITLES = { ko: ['진격의 거인'], en: ['Attack on Titan'], ja: ['進撃の巨人'], romaji: ['Shingeki no Kyojin'] }

const revealMsg = (overrides = {}) => ({
  type: 'reveal',
  index: 0,
  reveal: '진격의 거인',
  titles: ALL_TITLES,
  song: '紅蓮の弓矢',
  artists: ['Revo'],
  themeType: 'OP',
  themeSlug: 'OP1',
  winner: null,
  ...overrides,
})

function revealCard(dict) {
  const heading = screen.getByText(tr(dict, 'reveal.heading'))
  const card = heading.closest('.reveal')
  expect(card, 'the reveal card should be the .reveal element').not.toBeNull()
  return card
}

// The <dd> after the <dt> labelled with reveal.label.<lang>.
function titleLine(card, dict, lang) {
  const label = within(card).getByText(tr(dict, `reveal.label.${lang}`))
  expect(label.tagName).toBe('DT')
  expect(label.closest('dl.titles')).not.toBeNull()
  const title = label.nextElementSibling
  expect(title).not.toBeNull()
  expect(title.tagName).toBe('DD')
  return title
}

// How many times `part` occurs in `text`.
function occurrences(text, part) {
  return text.split(part).length - 1
}

function collapse(text) {
  return text.replace(/\s+/g, ' ').trim()
}

describe('default and switcher', () => {
  it('starts in Korean in test mode, with 한국어 pressed', () => {
    setup()
    expect(screen.getByRole('button', { name: '방 만들기' })).toBeTruthy()
    expect(screen.getByLabelText('닉네임')).toBeTruthy()
    const names = within(switcher())
      .getAllByRole('button')
      .map((b) => b.textContent.trim())
    expect(names).toEqual(['한국어', 'English', '日本語'])
    expect(pressedLanguages()).toEqual(['한국어'])
  })

  it('English switches the start screen, stores en and sets <html lang>', () => {
    setup()
    chooseLanguage('English')
    expect(screen.getByRole('button', { name: 'Create room' })).toBeTruthy()
    expect(screen.getByLabelText('Nickname')).toBeTruthy()
    expect(screen.queryByRole('button', { name: '방 만들기' })).toBeNull()
    expect(window.localStorage.getItem(LANG_KEY)).toBe('en')
    expect(document.documentElement.lang).toBe('en')
    expect(pressedLanguages()).toEqual(['English'])
    // Every visible start-screen string went through t().
    expect(pageTextWithoutSwitcher()).not.toMatch(HANGUL)
  })

  it('日本語 shows the Japanese texts and stores ja', () => {
    setup()
    chooseLanguage('日本語')
    const createRoom = tr(ja, 'start.createRoom')
    expect(createRoom).not.toBe(tr(ko, 'start.createRoom'))
    expect(createRoom).not.toBe(tr(en, 'start.createRoom'))
    expect(screen.getByRole('button', { name: createRoom })).toBeTruthy()
    expect(screen.getByLabelText(tr(ja, 'start.nickname'))).toBeTruthy()
    expect(window.localStorage.getItem(LANG_KEY)).toBe('ja')
    expect(document.documentElement.lang).toBe('ja')
    expect(pressedLanguages()).toEqual(['日本語'])
    expect(pageTextWithoutSwitcher()).not.toMatch(HANGUL)
  })

  it('한국어 after English brings the Korean texts back and stores ko', () => {
    setup()
    chooseLanguage('English')
    chooseLanguage('한국어')
    expect(screen.getByRole('button', { name: '방 만들기' })).toBeTruthy()
    expect(window.localStorage.getItem(LANG_KEY)).toBe('ko')
    expect(document.documentElement.lang).toBe('ko')
  })
})

describe('stored choice', () => {
  it('a stored ja before mount starts the app in Japanese', () => {
    window.localStorage.setItem(LANG_KEY, 'ja')
    setup()
    expect(screen.getByRole('button', { name: tr(ja, 'start.createRoom') })).toBeTruthy()
    expect(screen.getByLabelText(tr(ja, 'start.nickname'))).toBeTruthy()
    expect(screen.queryByRole('button', { name: '방 만들기' })).toBeNull()
    expect(pressedLanguages()).toEqual(['日本語'])
  })
})

describe('English game', () => {
  it('the stage hint, skip label, skip count and system chat line are English', async () => {
    const env = setup()
    chooseLanguage('English')
    const socket = await songStarted(env, en)

    expect(await screen.findByText(tr(en, 'stage.hint'))).toBeTruthy()
    expect(tr(en, 'stage.hint')).not.toBe(tr(ko, 'stage.hint'))
    const skip = screen.getByRole('button', { name: tr(en, 'stage.skip') })
    expect(tr(en, 'stage.skip')).not.toBe(tr(ko, 'stage.skip'))

    deliver(socket, { type: 'skipState', index: 0, votes: 1, needed: 2 })
    expect(screen.getByText(tr(en, 'stage.skipCount', { votes: 1, needed: 2 }))).toBeTruthy()
    fireEvent.click(skip)
    expect(socket.sentOfType('skipVote')).toEqual([{ type: 'skipVote', index: 0 }])

    deliver(socket, { type: 'chat', name: 'Joon', text: 'One Piece' })
    deliver(socket, { type: 'correct', index: 0, name: 'Mina' })
    const line = `✔ ${tr(en, 'chat.correctLine', { name: 'Mina' })}`
    expect(tr(en, 'chat.correctLine', { name: 'Mina' })).not.toBe(tr(ko, 'chat.correctLine', { name: 'Mina' }))
    const items = Array.from(document.querySelectorAll('aside.chat .msgs .msg')).map((el) => collapse(el.textContent))
    expect(items).toContain(collapse(line))

    // Before the reveal (whose KO line is Korean on purpose) no Korean is left.
    expect(pageTextWithoutSwitcher()).not.toMatch(HANGUL)
  })
})

describe('reveal titles', () => {
  it('the card shows the KO, EN, JA and Romaji lines; each title once in the Korean UI', async () => {
    const env = setup()
    const socket = await songStarted(env, ko)
    deliver(socket, revealMsg())
    const card = revealCard(ko)
    const expected = {
      ko: '진격의 거인',
      en: 'Attack on Titan',
      ja: '進撃の巨人',
      romaji: 'Shingeki no Kyojin',
    }
    for (const [lang, title] of Object.entries(expected)) {
      const line = titleLine(card, ko, lang)
      expect(line.textContent, `${lang} line`).toContain(title)
      for (const [other, otherTitle] of Object.entries(expected)) {
        if (other !== lang) expect(line.textContent, `${lang} line`).not.toContain(otherTitle)
      }
      expect(within(card).getAllByText(title), title).toHaveLength(1)
    }
    // The Korean title is dd.ko.
    expect(card.querySelector('dl.titles dd.ko').textContent).toBe('진격의 거인')
    // The other reveal texts stay once each, on the theme line.
    const themeLine = card.querySelector('.theme-line')
    expect(themeLine).not.toBeNull()
    for (const part of ['OP1', '紅蓮の弓矢', 'Revo']) {
      expect(occurrences(themeLine.textContent, part), part).toBe(1)
      expect(occurrences(card.textContent, part), part).toBe(1)
    }
  })

  it('a missing language (empty or absent) has no line', async () => {
    const env = setup()
    const socket = await songStarted(env, ko)
    deliver(socket, revealMsg({ titles: { ko: ['진격의 거인'], en: ['Attack on Titan'], ja: [] } }))
    const card = revealCard(ko)
    expect(titleLine(card, ko, 'ko').textContent).toContain('진격의 거인')
    expect(titleLine(card, ko, 'en').textContent).toContain('Attack on Titan')
    expect(within(card).queryByText(tr(ko, 'reveal.label.ja'))).toBeNull()
    expect(within(card).queryByText(tr(ko, 'reveal.label.romaji'))).toBeNull()
  })

  it('in English the card keeps the four lines, in KO EN JA Romaji order', async () => {
    const env = setup()
    chooseLanguage('English')
    const socket = await songStarted(env, en)
    deliver(socket, revealMsg())
    const card = revealCard(en)
    expect(Array.from(card.querySelectorAll('dl.titles dd'), (dd) => dd.textContent)).toEqual([
      '진격의 거인',
      'Attack on Titan',
      '進撃の巨人',
      'Shingeki no Kyojin',
    ])
    expect(titleLine(card, en, 'en').textContent).toBe('Attack on Titan')
    expect(titleLine(card, en, 'ko').textContent).toContain('진격의 거인')
    expect(titleLine(card, en, 'romaji').textContent).toContain('Shingeki no Kyojin')
  })

  it('in Japanese with no JA title the card has only the EN and Romaji lines', async () => {
    const env = setup()
    chooseLanguage('日本語')
    const socket = await songStarted(env, ja)
    deliver(socket, revealMsg({ reveal: 'Shingeki no Kyojin', titles: { en: ['Attack on Titan'], romaji: ['Shingeki no Kyojin'] } }))
    const card = revealCard(ja)
    expect(Array.from(card.querySelectorAll('dl.titles dd'), (dd) => dd.textContent)).toEqual(['Attack on Titan', 'Shingeki no Kyojin'])
    expect(within(card).queryByText(tr(ja, 'reveal.label.ja'))).toBeNull()
    expect(within(card).queryByText(tr(ja, 'reveal.label.ko'))).toBeNull()
  })
})

describe('language and room theme', () => {
  it('on the start screen the own pick stays and the picker names follow the language', () => {
    setup()
    fireEvent.click(within(picker(ko)).getByRole('button', { name: tr(ko, 'theme.chalk') }))
    expect(rootTheme()).toBe('chalk')
    chooseLanguage('English')
    expect(rootTheme()).toBe('chalk')
    expect(pickerNames(en)).toEqual(THEME_IDS.map((id) => tr(en, `theme.${id}`)))
    expect(pressedThemes(en)).toEqual(['Chalkboard Classroom'])
    expect(window.localStorage.getItem(THEME_KEY)).toBe('chalk')
  })

  it('in a room the room theme stays across switches and the names follow the language', async () => {
    const env = setup()
    const socket = await hostLobby(env, ko)
    deliver(socket, { type: 'roomTheme', theme: 'rain' })
    expect(rootTheme()).toBe('rain')
    expect(pickerNames(ko)).toEqual(['노을 옥상', '비 오는 밤', '벚꽃 밤', '네온 시티', '칠판 교실', '8비트 오락실', '여름 축제', '만화 원고'])
    expect(pressedThemes(ko)).toEqual(['비 오는 밤'])

    chooseLanguage('English')
    expect(rootTheme()).toBe('rain')
    expect(pickerNames(en)).toEqual([
      'Sunset Rooftop',
      'Rainy Night',
      'Cherry Blossom Night',
      'Neon City',
      'Chalkboard Classroom',
      '8-bit Arcade',
      'Summer Festival',
      'Manga Manuscript',
    ])
    expect(pressedThemes(en)).toEqual(['Rainy Night'])

    chooseLanguage('日本語')
    expect(rootTheme()).toBe('rain')
    expect(pickerNames(ja)).toEqual(['夕焼けの屋上', '雨の夜', '夜桜', 'ネオンシティ', '黒板の教室', '8ビットゲーセン', '夏祭り', '漫画原稿'])
    expect(pressedThemes(ja)).toEqual(['雨の夜'])

    // A language switch sends nothing to the server and leaves the own pick.
    expect(socket.sentOfType('roomTheme')).toEqual([])
    expect(window.localStorage.getItem(THEME_KEY)).toBeNull()
  })
})
