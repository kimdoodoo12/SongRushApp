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
//   reveal.label.romaji, theme.label, theme.play, theme.neon, theme.sakura,
//   theme.chalk, theme.star, theme.arcade.
// Markup contract: the system chat line reads '✔ ' + chat.correctLine; each
// reveal title line is one element holding its label (an element of its own
// with exactly the label text) and its title; the card is the nearest
// <section> around the reveal.heading text; the big title is a heading whose
// accessible name is exactly the title.
// Timer rule (cycle 039): real timers and waitFor.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, act, cleanup, within } from '@testing-library/react'
import App from './App.jsx'
import ko from './i18n/ko.json'
import en from './i18n/en.json'
import ja from './i18n/ja.json'
import { createSocketFactory, createAudioContextFactory, createFakeFetch } from './testing/fakes.js'

const T0 = 1_000_000
const LANG_KEY = 'songrush.lang'
const THEME_KEY = 'songrush.roomTheme'
const HANGUL = /[ㄱ-ㅣ가-힣]/
const THEME_IDS = ['play', 'neon', 'sakura', 'chalk', 'star', 'arcade']

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

function rootTheme() {
  const root = document.querySelector('[data-room-theme]')
  expect(root).not.toBeNull()
  return root.getAttribute('data-room-theme')
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
  fireEvent.change(screen.getByLabelText(tr(dict, 'start.nickname')), { target: { value: 'Kai' } })
  fireEvent.click(screen.getByRole('button', { name: tr(dict, 'start.createRoom') }))
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
  const card = heading.closest('section')
  expect(card, 'the reveal card should be a <section>').not.toBeNull()
  return card
}

// The element holding the title line labelled with reveal.label.<lang>.
function titleLine(card, dict, lang) {
  const label = within(card).getByText(tr(dict, `reveal.label.${lang}`))
  return label.parentElement
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
    const items = screen.getAllByRole('listitem').map((li) => collapse(li.textContent))
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
    // The big title is the Korean line in the Korean UI.
    expect(within(card).getByRole('heading', { name: '진격의 거인' })).toBeTruthy()
    // The other reveal texts stay once each.
    expect(within(card).getAllByText('OP1')).toHaveLength(1)
    expect(within(card).getAllByText('紅蓮の弓矢')).toHaveLength(1)
    expect(within(card).getAllByText('Revo')).toHaveLength(1)
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

  it('in English the big title is the EN line', async () => {
    const env = setup()
    chooseLanguage('English')
    const socket = await songStarted(env, en)
    deliver(socket, revealMsg())
    const card = revealCard(en)
    expect(within(card).getByRole('heading', { name: 'Attack on Titan' })).toBeTruthy()
    expect(titleLine(card, en, 'ko').textContent).toContain('진격의 거인')
    expect(titleLine(card, en, 'romaji').textContent).toContain('Shingeki no Kyojin')
  })

  it('in Japanese with no JA title the big title is the frame reveal text', async () => {
    const env = setup()
    chooseLanguage('日本語')
    const socket = await songStarted(env, ja)
    deliver(socket, revealMsg({ reveal: 'Shingeki no Kyojin', titles: { en: ['Attack on Titan'], romaji: ['Shingeki no Kyojin'] } }))
    const card = revealCard(ja)
    expect(within(card).getByRole('heading', { name: 'Shingeki no Kyojin' })).toBeTruthy()
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
    expect(pressedThemes(en)).toEqual(['Chalkboard'])
    expect(window.localStorage.getItem(THEME_KEY)).toBe('chalk')
  })

  it('in a room the room theme stays across switches and the names follow the language', async () => {
    const env = setup()
    const socket = await hostLobby(env, ko)
    deliver(socket, { type: 'roomTheme', theme: 'star' })
    expect(rootTheme()).toBe('star')
    expect(pressedThemes(ko)).toEqual(['별밤'])

    chooseLanguage('English')
    expect(rootTheme()).toBe('star')
    expect(pickerNames(en)).toEqual(['Playground', 'Stage', 'Sakura', 'Chalkboard', 'Starry Night', 'Arcade'])
    expect(pressedThemes(en)).toEqual(['Starry Night'])

    chooseLanguage('日本語')
    expect(rootTheme()).toBe('star')
    expect(pickerNames(ja)).toEqual(['あそびば', 'ステージ', 'さくら', 'こくばん', 'ほしぞら', 'ゲーセン'])
    expect(pressedThemes(ja)).toEqual(['ほしぞら'])

    // A language switch sends nothing to the server and leaves the own pick.
    expect(socket.sentOfType('roomTheme')).toEqual([])
    expect(window.localStorage.getItem(THEME_KEY)).toBeNull()
  })
})
