// Cycle 114 (D-124, D-123): the lobby and the start screen in the mockup's
// frame (docs/cycles/114-lobby-port.md, Behaviour and Acceptance 1;
// reference docs/design/108-ui-design-reference.html, #lobbyView, SKIN,
// renderLobby, toLobby, showView, setMood).
//
// Contract with the implementer:
// - One frame for start, lobby and game: .app > header (.logo, .roominfo,
//   .vol, .moodbar) and .main (.stage > .inner, aside.chat). body.lobby-on
//   only on the lobby. A view switch calls window.scrollTo(0, 0).
// - The header .moodbar is the only mood picker (one group 분위기): the own
//   pick, enabled, labelled 분위기, on the start screen and in the lobby, for
//   the host and a guest alike (cycle 137, D-136; it sends nothing).
// - The reconnect banner is a p.warn, the first child of .stage .inner. The
//   lobby notice (ABANDONED) and error are p.warn right after .start-row.
// - Lobby: h1.marquee > span (SKIN.title); .ticket with .tk-main (.tk-tag
//   "<mood name> · <tagline>", .tk-label SKIN.label, .tk-code, .share with
//   span.link and the 복사 .btn, .tk-sub hint or copy result, .copied on a
//   copy) and .tk-stub (.tk-n connected players, .tk-of "/ 8명", .tk-admit
//   SKIN.admit); .lobby-side with .ldisc > canvas 320x320 and .lq "?"; no
//   .viewas. section.opts: .opts-hd (h2 게임 설정, .opts-who), .opt-grid with
//   the .stepper (- / output named 곡 수 / +, 5-30), the .seg groups 곡 종류,
//   난이도, 정답 형식, 게임 모드 and .years (연도 시작, 연도 끝); the year
//   warning is a p.warn in section.opts. .start-row: .btn.primary.go with
//   SKIN.start (host) or p.waiting with .dots (guest), and the 나가기 .btn.
//   .seats: h2 "<SKIN.seats> · 참가자 n / 8", ol.seat-grid of eight li.seat,
//   the empty ones SKIN.empty, a guest's small SKIN.ready.
// - The header .settings is today's settings line for the lobby selection.
// - Lobby chat: aside.chat with .msgs and form.send; the chat message as in a
//   game. The log is cleared on every way into the lobby (from the start
//   screen, 대기실로, an abandoned game, a rejoin into the lobby phase); the
//   mockup's system lines (방에 들어왔어요, 대기실로 돌아왔어요) may follow.
// - Start screen: h1.marquee > span SongRush, a section.opts card with
//   .btn.primary.go 방 만들기, the 방 코드 input and the 참가 .btn; no chat
//   aside shown. Cycle 119 (D-127, declared): the 닉네임 input moved into
//   the nickname dialog, so the card has none.
// Cycle 121 (D-128): the SKIN strings are plain words, not the mockup's.
// No i18n key names are fixed here: Korean texts are literals, English is
// read through the existing lobby.leave key and the pixel SKIN's en values.
// Timer rule (cycle 039): real timers and waitFor.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, act, cleanup, within } from '@testing-library/react'
import App from './App.jsx'
import en from './i18n/en.json'
import { createSocketFactory, createAudioContextFactory, createFakeFetch } from './testing/fakes.js'
import {
  createRoomAs,
  joinRoomAs,
  lobbyCode,
  pressedIn,
  seatNames,
  setSongCount,
  setTier,
  setType,
  setYears,
  songCount,
  songCountOutput,
  startButton,
  startGame,
  stepperButtons,
} from './testing/lobby.js'

const T0 = 1_000_000
const WAITING = '방장이 게임을 시작하기를 기다리는 중...'
const ABANDONED = '게임이 중단되어 대기실로 돌아왔습니다.'
const RECONNECTING = '연결이 끊겨 다시 연결하는 중...'
const YEAR_WARN = '시작 연도가 끝 연도보다 늦습니다.'
const HINT = '링크를 보내면 친구가 닉네임만 넣고 바로 들어와요'
const COPIED = '링크를 복사했어요'
const COPY_FAILED = '복사하지 못했어요. 주소를 길게 눌러 복사해 주세요'
const MOOD_NAMES = ['노을 옥상', '비 오는 밤', '벚꽃 밤', '네온 시티', '칠판 교실', '8비트 오락실', '여름 축제', '만화 원고']

// The lobby's per-mood texts in plain words (D-128): only the title differs
// by mood; the rest is the same in every mood. ko values for sunset and manga.
const SKIN = {
  sunset: { title: '노을', label: '방 코드', admit: 'ADMIT ONE', start: 'Play Song', seats: '대기실', ready: '참가', empty: '빈 자리' },
  manga: { title: '만화', label: '방 코드', admit: 'ADMIT ONE', start: 'Play Song', seats: '대기실', ready: '참가', empty: '빈 자리' },
  // en values: the test switches to English.
  pixel: { title: 'Arcade', label: 'Room code', admit: 'ADMIT ONE', start: 'Play Song', seats: 'Lobby', ready: 'Joined', empty: 'Empty' },
}

let scrollTo

beforeEach(() => {
  window.history.pushState({}, '', '/')
  window.localStorage.clear()
  window.sessionStorage.clear()
  document.body.classList.remove('lobby-on')
  scrollTo = vi.spyOn(window, 'scrollTo').mockImplementation(() => {})
})

afterEach(() => {
  // Back to Korean, so the shared i18n instance carries no language on.
  const group = screen.queryByRole('group', { name: 'Language' })
  if (group !== null) {
    const korean = within(group).queryByRole('button', { name: '한국어' })
    if (korean !== null) fireEvent.click(korean)
  }
  cleanup()
  window.history.pushState({}, '', '/')
  window.localStorage.clear()
  window.sessionStorage.clear()
  vi.restoreAllMocks()
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

function q(selector) {
  return document.querySelector(selector)
}

function qa(selector) {
  return Array.from(document.querySelectorAll(selector))
}

function text(selector) {
  const el = q(selector)
  expect(el, selector).not.toBeNull()
  return el.textContent.trim()
}

const player = (name, { host = false, connected = true } = {}) => ({ name, score: 0, host, connected })
const roster = (players, phase = 'lobby') => ({ type: 'roster', phase, players })
const HOST_PLAYERS = [player('하루', { host: true }), player('Joon')]
const GUEST_PLAYERS = [player('Mina', { host: true }), player('Hana'), player('Joon')]

async function openSocket(env, count = 1) {
  await waitFor(() => expect(env.sockets.sockets.length).toBe(count))
  const socket = env.sockets.last
  act(() => socket.open())
  await waitFor(() => expect(socket.sent.length).toBeGreaterThan(0))
  return socket
}

// 방 만들기 as 하루: joined QWERT and a lobby roster with 하루 as host.
async function hostLobby(env, { count = 1, players = HOST_PLAYERS } = {}) {
  createRoomAs('하루')
  const socket = await openSocket(env, count)
  deliver(socket, { type: 'joined', code: 'QWERT', name: '하루' })
  deliver(socket, roster(players))
  return socket
}

// 참가 to ABCDE as Hana: joined, then `players` (Mina hosts) unless null.
async function guestLobby(env, { count = 1, players = GUEST_PLAYERS } = {}) {
  joinRoomAs('Hana', 'ABCDE')
  const socket = await openSocket(env, count)
  deliver(socket, { type: 'joined', code: 'ABCDE', name: 'Hana' })
  if (players !== null) deliver(socket, roster(players))
  return socket
}

// The host starts a game; song 0 is prepared and ready is sent.
async function hostInGame(env, players = HOST_PLAYERS) {
  const socket = await hostLobby(env, { players })
  startGame()
  await waitFor(() => expect(socket.sentOfType('start')).toHaveLength(1))
  deliver(socket, roster(players, 'playing'))
  deliver(socket, { type: 'prepare', index: 0, total: 5, audioUrl: '/audio/s0' })
  await waitFor(() => expect(socket.sentOfType('ready')).toHaveLength(1))
  return socket
}

// From the game: chat, correct, reveal, gameOver and the lobby roster; the
// result screen with 대기실로.
function finishGame(socket, players = HOST_PLAYERS) {
  deliver(socket, { type: 'chat', name: 'Joon', text: '원피스' })
  deliver(socket, { type: 'correct', index: 0, name: 'Joon' })
  deliver(socket, {
    type: 'reveal',
    index: 0,
    reveal: '진격의 거인',
    titles: { ko: ['진격의 거인'], en: ['Attack on Titan'], ja: ['進撃の巨人'], romaji: ['Shingeki no Kyojin'] },
    song: '紅蓮の弓矢',
    artists: ['Revo'],
    themeType: 'OP',
    themeSlug: 'OP1',
    winner: 'Joon',
  })
  deliver(socket, {
    type: 'gameOver',
    ranking: players.map((p, i) => ({ rank: i + 1, name: p.name, score: 0 })),
  })
  deliver(socket, roster(players, 'lobby'))
}

// The lines of the chat log.
function logLines() {
  return qa('aside.chat .msgs .msg').map((el) => ({
    sys: el.classList.contains('sys'),
    correct: el.classList.contains('correct'),
    text: el.textContent.replace(/\s+/g, ' ').trim(),
  }))
}

// The .t text of every player chat line.
function chatTexts() {
  return qa('aside.chat .msgs .msg:not(.sys) .t').map((el) => el.textContent)
}

// Nothing from before is left in the log: no player line, no ✔ line, none of
// `before`, and no game-start notice.
function expectFreshLog(before = []) {
  expect(q('aside.chat .msgs'), 'the lobby chat log').not.toBeNull()
  const lines = logLines()
  expect(lines.filter((l) => !l.sys), 'player lines').toEqual([])
  expect(lines.filter((l) => l.correct), 'correct lines').toEqual([])
  for (const old of [...before, '게임을 시작해요']) {
    expect(lines.some((l) => l.text.includes(old)), old).toBe(false)
  }
}

// The p.warn lines right after .start-row, in order.
function warnsAfterStartRow() {
  const row = q('.start-row')
  expect(row, '.start-row').not.toBeNull()
  const out = []
  for (let el = row.nextElementSibling; el !== null && el.matches('p.warn'); el = el.nextElementSibling) {
    out.push(el.textContent.trim())
  }
  return out
}

function moodGroups() {
  return screen.getAllByRole('group', { name: '분위기' })
}

function moodButtons() {
  const bar = q('header .moodbar')
  expect(bar, 'header .moodbar').not.toBeNull()
  return within(bar).getAllByRole('button')
}

function pressedMood() {
  return moodButtons()
    .filter((b) => b.getAttribute('aria-pressed') === 'true')
    .map((b) => b.textContent.trim())
}

function chooseLanguage(label) {
  const group = screen.getByRole('group', { name: 'Language' })
  fireEvent.click(within(group).getByRole('button', { name: label }))
}

function expectSkin(skin) {
  expect(text('.stage .inner h1.marquee > span')).toBe(skin.title)
  expect(text('.ticket .tk-label')).toBe(skin.label)
  expect(text('.ticket .tk-admit')).toBe(skin.admit)
  const go = startButton()
  expect(go, 'the start button').not.toBeNull()
  expect(go.textContent.trim()).toBe(skin.start)
  expect(screen.getByRole('button', { name: skin.start })).toBe(go)
  expect(text('.seats h2').startsWith(skin.seats)).toBe(true)
  const empty = qa('.seats li.seat.empty')
  expect(empty.length).toBeGreaterThan(0)
  for (const seat of empty) expect(seat.textContent.trim()).toBe(skin.empty)
}

describe('one frame', () => {
  it('the start screen: the game header with an empty .roominfo, the marquee, the card, no lobby-on and no chat', () => {
    setup()
    expect(document.body.classList.contains('lobby-on')).toBe(false)
    expect(q('.app > header .logo')).not.toBeNull()
    expect(q('.app > header .roominfo')).not.toBeNull()
    expect(q('.app > header .roominfo .code')).toBeNull()
    expect(q('.app > header .roominfo .settings')).toBeNull()
    expect(q('.app > header .vol')).not.toBeNull()
    expect(q('.app > header .moodbar')).not.toBeNull()
    expect(text('.app .main .stage .inner h1.marquee > span')).toBe('SongRush')
    const card = q('.stage .inner section.opts')
    expect(card).not.toBeNull()
    expect(screen.queryByLabelText('닉네임')).toBeNull()
    expect(card.contains(screen.getByLabelText('방 코드'))).toBe(true)
    const create = screen.getByRole('button', { name: '방 만들기' })
    expect(create.classList.contains('btn')).toBe(true)
    expect(create.classList.contains('primary')).toBe(true)
    expect(create.classList.contains('go')).toBe(true)
    expect(screen.getByLabelText('방 코드').tagName).toBe('INPUT')
    expect(screen.getByRole('button', { name: '참가' }).classList.contains('btn')).toBe(true)
    const aside = q('aside.chat')
    expect(aside === null || aside.closest('[hidden]') !== null).toBe(true)
    expect(q('.ticket')).toBeNull()
  })

  it('the start screen: the header picker is the only one, labelled 분위기, enabled, with the own pick', () => {
    window.localStorage.setItem('songrush.roomTheme', 'rain')
    setup()
    expect(moodGroups()).toHaveLength(1)
    expect(q('header .moodbar').contains(moodGroups()[0])).toBe(true)
    expect(text('header .moodbar .ml')).toBe('분위기')
    expect(moodButtons().map((b) => b.textContent.trim())).toEqual(MOOD_NAMES)
    for (const b of moodButtons()) expect(b.disabled).toBe(false)
    expect(pressedMood()).toEqual(['비 오는 밤'])
    fireEvent.click(within(moodGroups()[0]).getByRole('button', { name: '칠판 교실' }))
    expect(document.documentElement.dataset.mood).toBe('chalk')
    expect(pressedMood()).toEqual(['칠판 교실'])
  })

  it('the lobby has body.lobby-on, the room code in the header, the host picker enabled and no second picker', async () => {
    const env = setup()
    const socket = await hostLobby(env)
    expect(document.body.classList.contains('lobby-on')).toBe(true)
    expect(text('header .roominfo .code')).toBe('QWERT')
    expect(moodGroups()).toHaveLength(1)
    expect(q('.stage .moods')).toBeNull()
    expect(text('header .moodbar .ml')).toBe('분위기')
    for (const b of moodButtons()) expect(b.disabled).toBe(false)
    fireEvent.click(within(moodGroups()[0]).getByRole('button', { name: '네온 시티' }))
    expect(pressedMood()).toEqual(['네온 시티'])
    expect(socket.sentOfType('roomTheme')).toEqual([])
  })

  it('a guest: the header picker is enabled and its label reads 분위기; a roomTheme frame does not press another', async () => {
    const env = setup()
    const socket = await guestLobby(env)
    expect(moodGroups()).toHaveLength(1)
    expect(text('header .moodbar .ml')).toBe('분위기')
    for (const b of moodButtons()) expect(b.disabled).toBe(false)
    deliver(socket, { type: 'roomTheme', theme: 'sakura' })
    expect(pressedMood()).toEqual(['노을 옥상'])
    fireEvent.click(within(moodGroups()[0]).getByRole('button', { name: '벚꽃 밤' }))
    expect(pressedMood()).toEqual(['벚꽃 밤'])
  })

  it('body.lobby-on is off in the game, on again after 대기실로 and off on the start screen after 나가기', async () => {
    const env = setup()
    const socket = await hostInGame(env)
    expect(document.body.classList.contains('lobby-on')).toBe(false)
    finishGame(socket)
    expect(document.body.classList.contains('lobby-on')).toBe(false)
    fireEvent.click(screen.getByRole('button', { name: '대기실로' }))
    expect(document.body.classList.contains('lobby-on')).toBe(true)
    fireEvent.click(screen.getByRole('button', { name: '나가기' }))
    expect(screen.getByRole('button', { name: '방 만들기' })).toBeTruthy()
    expect(document.body.classList.contains('lobby-on')).toBe(false)
  })

  it('the reconnect banner is a p.warn at the top of .stage .inner in the lobby', async () => {
    const env = setup()
    const socket = await hostLobby(env)
    deliver(socket, { type: 'token', token: 'T1' })
    act(() => socket.serverClose(1006))
    const inner = q('.stage .inner')
    expect(inner).not.toBeNull()
    const first = inner.firstElementChild
    expect(first.matches('p.warn')).toBe(true)
    expect(first.textContent.trim()).toBe(RECONNECTING)
  })

  it('a lobby error is a p.warn right after .start-row', async () => {
    const env = setup()
    const socket = await hostLobby(env)
    startGame()
    await waitFor(() => expect(socket.sentOfType('start')).toHaveLength(1))
    deliver(socket, { type: 'error', code: 'not-enough-songs', available: 3 })
    expect(warnsAfterStartRow()).toEqual(['출제할 수 있는 곡이 3곡뿐입니다.'])
  })

  it('the abandoned notice is a p.warn right after .start-row', async () => {
    const env = setup()
    const socket = await guestLobby(env, { players: null })
    deliver(socket, { type: 'snapshot', phase: 'playing', players: GUEST_PLAYERS, song: null, scores: [] })
    deliver(socket, roster(GUEST_PLAYERS, 'playing'))
    deliver(socket, roster(GUEST_PLAYERS, 'lobby'))
    expect(warnsAfterStartRow()).toEqual([ABANDONED])
  })

  it('the header .settings shows the lobby selection as today\'s settings line', async () => {
    const env = setup()
    await hostLobby(env)
    expect(q('header .roominfo .settings')).toBeNull()
    setType('오프닝만')
    setTier('쉬움')
    expect(text('header .roominfo .settings')).toBe('오프닝만 · 쉬움')
  })
})

describe('lobby markup', () => {
  it('ko sunset (default): the marquee, ticket, start button, seats and tag follow SKIN', async () => {
    const env = setup()
    await hostLobby(env)
    expectSkin(SKIN.sunset)
    expect(text('.seats h2')).toBe('대기실 · 참가자 2 / 8')
    expect(text('.ticket .tk-tag')).toBe('노을 옥상 · 애니 OP·ED 맞히기')
  })

  it('ko manga from the own pick: SKIN.manga, and a guest seat reads SKIN.ready', async () => {
    const env = setup()
    await hostLobby(env)
    fireEvent.click(within(moodGroups()[0]).getByRole('button', { name: '만화 원고' }))
    expectSkin(SKIN.manga)
    expect(text('.seats h2')).toBe('대기실 · 참가자 2 / 8')
    expect(text('.ticket .tk-tag')).toBe('만화 원고 · 애니 OP·ED 맞히기')
    const joon = qa('.seats li.seat:not(.empty)').find((li) => li.querySelector('.sm b').textContent === 'Joon')
    expect(joon).toBeTruthy()
    expect(joon.querySelector('.sm small').textContent.trim()).toBe(SKIN.manga.ready)
  })

  it('en pixel: the SKIN strings are English and the lobby is in English', async () => {
    const env = setup()
    await hostLobby(env)
    fireEvent.click(within(moodGroups()[0]).getByRole('button', { name: '8비트 오락실' }))
    chooseLanguage('English')
    expectSkin(SKIN.pixel)
    expect(screen.getByRole('button', { name: en.lobby.leave })).toBeTruthy()
    expect(screen.queryByRole('button', { name: '나가기' })).toBeNull()
  })

  it('the ticket: .tk-code, .tk-n (connected), .tk-of, the share link and the hint; the disc canvas and ?; no .viewas', async () => {
    const env = setup()
    await guestLobby(env, {
      players: [player('Mina', { host: true }), player('Hana'), player('Joon', { connected: false })],
    })
    expect(lobbyCode()).toBe('ABCDE')
    expect(text('.ticket .tk-stub .tk-n')).toBe('2')
    expect(text('.ticket .tk-stub .tk-of')).toBe('/ 8명')
    expect(text('.ticket .tk-main .share .link')).toBe(`${window.location.origin}/r/ABCDE`)
    expect(text('.ticket .tk-main .tk-sub')).toBe(HINT)
    const canvas = q('.lobby-top .lobby-side .ldisc > canvas')
    expect(canvas).not.toBeNull()
    expect(canvas.getAttribute('width')).toBe('320')
    expect(canvas.getAttribute('height')).toBe('320')
    expect(text('.lobby-side .ldisc .lq')).toBe('?')
    expect(q('.viewas')).toBeNull()
  })

  it('복사 copies the link and shows the copied state in .tk-sub', async () => {
    const writeText = vi.fn(() => Promise.resolve())
    Object.defineProperty(window.navigator, 'clipboard', { value: { writeText }, configurable: true })
    try {
      const env = setup()
      await hostLobby(env)
      const copy = within(q('.ticket .share')).getByRole('button', { name: '복사' })
      expect(copy.classList.contains('btn')).toBe(true)
      fireEvent.click(copy)
      await waitFor(() => expect(writeText).toHaveBeenCalledWith(`${window.location.origin}/r/QWERT`))
      await waitFor(() => expect(q('.tk-sub').classList.contains('copied')).toBe(true))
      expect(text('.tk-sub')).toBe(COPIED)
    } finally {
      delete window.navigator.clipboard
    }
  })

  it('a failed copy shows the failure text without .copied', async () => {
    vi.spyOn(console, 'warn').mockImplementation(() => {})
    const writeText = vi.fn(() => Promise.reject(new Error('denied')))
    Object.defineProperty(window.navigator, 'clipboard', { value: { writeText }, configurable: true })
    try {
      const env = setup()
      await hostLobby(env)
      fireEvent.click(within(q('.ticket .share')).getByRole('button', { name: '복사' }))
      await waitFor(() => expect(text('.tk-sub')).toBe(COPY_FAILED))
      expect(q('.tk-sub').classList.contains('copied')).toBe(false)
    } finally {
      delete window.navigator.clipboard
    }
  })

  it('section.opts: 게임 설정, the host line, and the controls in the mockup order with today\'s defaults', async () => {
    const env = setup()
    await hostLobby(env)
    expect(text('section.opts .opts-hd h2')).toBe('게임 설정')
    expect(text('section.opts .opts-hd .opts-who')).toBe('방장만 바꿀 수 있어요')
    const grid = q('section.opts .opt-grid')
    expect(grid).not.toBeNull()
    const output = songCountOutput()
    expect(screen.getByLabelText('곡 수')).toBe(output)
    expect(output.tagName).toBe('OUTPUT')
    expect(songCount()).toBe(10)
    const groups = ['곡 종류', '난이도', '정답 형식', '게임 모드'].map((name) => screen.getByRole('group', { name }))
    for (const g of groups) {
      expect(grid.contains(g)).toBe(true)
      expect(g.classList.contains('seg')).toBe(true)
    }
    expect(within(groups[0]).getAllByRole('button').map((b) => b.textContent.trim())).toEqual(['전체', '오프닝만', '엔딩만'])
    expect(within(groups[1]).getAllByRole('button').map((b) => b.textContent.trim())).toEqual(['쉬움', '보통', '어려움'])
    expect(within(groups[2]).getAllByRole('button').map((b) => b.textContent.trim())).toEqual(['채팅', '객관식'])
    expect(within(groups[3]).getAllByRole('button').map((b) => b.textContent.trim())).toEqual(['선착순', '순위전'])
    expect(pressedIn('곡 종류')).toEqual(['전체'])
    expect(pressedIn('난이도')).toEqual(['보통'])
    expect(pressedIn('정답 형식')).toEqual(['채팅'])
    expect(pressedIn('게임 모드')).toEqual(['선착순'])
    const from = screen.getByLabelText('연도 시작')
    const to = screen.getByLabelText('연도 끝')
    expect(from.closest('.years')).not.toBeNull()
    expect(to.closest('.years')).toBe(from.closest('.years'))
    expect(grid.contains(from)).toBe(true)
    expect(Array.from(from.options).map((o) => [o.value, o.textContent])).toEqual([
      ['', '처음부터'],
      ...[1990, 2000, 2005, 2010, 2015, 2020, 2025].map((y) => [String(y), String(y)]),
    ])
    expect(Array.from(to.options).map((o) => [o.value, o.textContent])).toEqual([
      ['', '지금까지'],
      ...[1999, 2004, 2009, 2014, 2019, 2024].map((y) => [String(y), String(y)]),
    ])
    // The mockup's order: 곡 수, 곡 종류, 난이도, 연도, 정답 형식, 게임 모드.
    const order = [output, groups[0], groups[1], from, groups[2], groups[3]]
    for (let i = 1; i < order.length; i += 1) {
      expect(order[i - 1].compareDocumentPosition(order[i]) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    }
  })

  it('the stepper: - and + move 곡 수 by one within 5-30 and start sends it as a number', async () => {
    const env = setup()
    const socket = await hostLobby(env)
    const [down, up] = stepperButtons()
    expect(down.getAttribute('aria-label')).toBe('곡 수 줄이기')
    expect(up.getAttribute('aria-label')).toBe('곡 수 늘리기')
    fireEvent.click(up)
    expect(songCount()).toBe(11)
    fireEvent.click(down)
    fireEvent.click(down)
    expect(songCount()).toBe(9)
    setSongCount(5)
    expect(stepperButtons()[0].disabled).toBe(true)
    fireEvent.click(stepperButtons()[0])
    expect(songCount()).toBe(5)
    setSongCount(30)
    expect(stepperButtons()[1].disabled).toBe(true)
    fireEvent.click(stepperButtons()[1])
    expect(songCount()).toBe(30)
    startGame()
    await waitFor(() => expect(socket.sentOfType('start')).toEqual([{ type: 'start', songCount: 30 }]))
    expect(typeof socket.sentOfType('start')[0].songCount).toBe('number')
  })

  it('2010 to 1999 shows the year warning in section.opts and disables start; fixing it enables start', async () => {
    const env = setup()
    const socket = await hostLobby(env)
    expect(screen.queryByText(YEAR_WARN)).toBeNull()
    setYears('2010', '1999')
    const warn = screen.getByText(YEAR_WARN)
    expect(warn.matches('p.warn')).toBe(true)
    expect(warn.closest('section.opts')).not.toBeNull()
    expect(startButton().disabled).toBe(true)
    fireEvent.click(startButton())
    expect(socket.sentOfType('start')).toEqual([])
    setYears(undefined, '2014')
    expect(screen.queryByText(YEAR_WARN)).toBeNull()
    expect(startButton().disabled).toBe(false)
    startGame()
    await waitFor(() =>
      expect(socket.sentOfType('start')).toEqual([{ type: 'start', songCount: 10, yearFrom: 2010, yearTo: 2014 }]),
    )
  })

  it('the host: .start-row holds .btn.primary.go and the 나가기 .btn, and no waiting line', async () => {
    const env = setup()
    await hostLobby(env)
    const go = startButton()
    expect(go.classList.contains('btn')).toBe(true)
    expect(go.classList.contains('primary')).toBe(true)
    expect(q('.start-row .waiting')).toBeNull()
    expect(screen.queryByText(WAITING)).toBeNull()
    const leave = within(q('.start-row')).getByRole('button', { name: '나가기' })
    expect(leave.classList.contains('btn')).toBe(true)
  })

  it('a guest sees every control disabled, the waiting line with .dots and no start button', async () => {
    const env = setup()
    const socket = await guestLobby(env)
    expect(startButton()).toBeNull()
    expect(q('.start-row .go')).toBeNull()
    const waiting = q('.start-row p.waiting')
    expect(waiting).not.toBeNull()
    expect(waiting.querySelector('.dots')).not.toBeNull()
    expect(waiting.textContent.trim()).toBe(WAITING)
    // Cycle 116 (declared change, round 2): before any lobbySettings the host is still choosing;
    // after one, the card shows the host's settings.
    expect(text('section.opts .opts-hd .opts-who')).toBe('방장이 설정을 고르는 중이에요')
    deliver(socket, { type: 'lobbySettings', songCount: 10 })
    expect(text('section.opts .opts-hd .opts-who')).toBe('방장이 정한 설정이에요')
    const controls = qa('section.opts .opt-grid button, section.opts .opt-grid select')
    expect(controls.length).toBeGreaterThanOrEqual(2 + 3 + 3 + 2 + 2 + 2)
    for (const c of controls) expect(c.disabled, c.outerHTML).toBe(true)
    expect(within(q('.start-row')).getByRole('button', { name: '나가기' })).toBeTruthy()
  })

  it('the seats: .seats with ol.seat-grid of eight li.seat; the name lines are the roster rows', async () => {
    const env = setup()
    await guestLobby(env)
    const grid = q('.seats ol.seat-grid')
    expect(grid).not.toBeNull()
    expect(grid.querySelectorAll('li.seat')).toHaveLength(8)
    expect(seatNames()).toEqual(['Mina (방장)', 'Hana (나)', 'Joon'])
  })
})

describe('lobby chat', () => {
  it('the lobby shows aside.chat with .msgs and form.send; sending emits a chat message', async () => {
    const env = setup()
    const socket = await guestLobby(env)
    const aside = q('aside.chat')
    expect(aside).not.toBeNull()
    expect(aside.closest('[hidden]')).toBeNull()
    expect(aside.querySelector('.msgs')).not.toBeNull()
    const form = aside.querySelector('form.send')
    expect(form).not.toBeNull()
    const input = form.querySelector('input')
    fireEvent.change(input, { target: { value: '안녕하세요' } })
    fireEvent.submit(form)
    await waitFor(() => expect(socket.sentOfType('chat')).toEqual([{ type: 'chat', text: '안녕하세요' }]))
  })

  it('an incoming chat in the lobby renders as .msg > .t', async () => {
    const env = setup()
    const socket = await guestLobby(env)
    deliver(socket, { type: 'chat', name: 'Mina', text: '몇 곡 해요?' })
    const line = qa('aside.chat .msgs .msg > .t').find((el) => el.textContent === 'Mina: 몇 곡 해요?')
    expect(line).toBeTruthy()
  })

  it('joining shows the mockup\'s line "<code> 방에 들어왔어요…" and no other line', async () => {
    const env = setup()
    await guestLobby(env)
    const lines = logLines()
    expect(lines.every((l) => l.sys && !l.correct)).toBe(true)
    expect(lines.map((l) => l.text)).toContain('ABCDE 방에 들어왔어요. 방장이 설정을 고르는 중이에요')
  })

  it('the log is cleared on entering from the start screen again', async () => {
    const env = setup()
    const first = await guestLobby(env)
    deliver(first, { type: 'chat', name: 'Mina', text: '첫 방 인사' })
    expect(chatTexts()).toEqual(['Mina: 첫 방 인사'])
    fireEvent.click(screen.getByRole('button', { name: '나가기' }))
    await guestLobby(env, { count: 2 })
    expectFreshLog(['첫 방 인사'])
  })

  it('the log is cleared after 대기실로, which adds the mockup\'s 대기실로 돌아왔어요', async () => {
    const env = setup()
    const socket = await hostInGame(env)
    finishGame(socket)
    expect(chatTexts()).toEqual(['Joon: 원피스'])
    fireEvent.click(screen.getByRole('button', { name: '대기실로' }))
    expectFreshLog(['원피스', 'Joon 정답!', '게임 종료'])
    expect(logLines().map((l) => l.text)).toContain('대기실로 돌아왔어요')
    deliver(socket, { type: 'chat', name: 'Joon', text: '한 판 더' })
    expect(chatTexts()).toEqual(['Joon: 한 판 더'])
  })

  it('the log is cleared when an abandoned game returns to the lobby', async () => {
    const env = setup()
    const socket = await guestLobby(env, { players: null })
    deliver(socket, { type: 'snapshot', phase: 'playing', players: GUEST_PLAYERS, song: null, scores: [] })
    deliver(socket, roster(GUEST_PLAYERS, 'playing'))
    deliver(socket, { type: 'chat', name: 'Mina', text: '구경 중 채팅' })
    expect(chatTexts()).toEqual(['Mina: 구경 중 채팅'])
    deliver(socket, roster(GUEST_PLAYERS, 'lobby'))
    expect(screen.getByText(ABANDONED)).toBeTruthy()
    expectFreshLog(['구경 중 채팅'])
  })

  it('the log is cleared when a rejoin restores the lobby phase', async () => {
    const env = setup()
    const socket = await hostInGame(env)
    deliver(socket, { type: 'token', token: 'T1' })
    deliver(socket, { type: 'chat', name: 'Joon', text: '끊기기 전' })
    expect(chatTexts()).toEqual(['Joon: 끊기기 전'])
    act(() => socket.serverClose(1006))
    await waitFor(() => expect(env.sockets.sockets).toHaveLength(2), { timeout: 3000 })
    const attempt = env.sockets.last
    act(() => attempt.open())
    await waitFor(() => expect(attempt.sent).toEqual([{ type: 'rejoin', token: 'T1' }]))
    deliver(attempt, { type: 'joined', code: 'QWERT', name: '하루' })
    deliver(attempt, { type: 'snapshot', phase: 'lobby', players: HOST_PLAYERS, song: null, scores: [] })
    expect(lobbyCode()).toBe('QWERT')
    expect(document.body.classList.contains('lobby-on')).toBe(true)
    expectFreshLog(['끊기기 전'])
  })
})

describe('scroll on a view switch', () => {
  it('start to lobby, lobby to game and game to lobby each call window.scrollTo(0, 0)', async () => {
    const env = setup()
    scrollTo.mockClear()
    const socket = await hostLobby(env)
    expect(scrollTo).toHaveBeenCalledWith(0, 0)
    scrollTo.mockClear()
    startGame()
    await waitFor(() => expect(socket.sentOfType('start')).toHaveLength(1))
    deliver(socket, roster(HOST_PLAYERS, 'playing'))
    deliver(socket, { type: 'prepare', index: 0, total: 5, audioUrl: '/audio/s0' })
    await waitFor(() => expect(socket.sentOfType('ready')).toHaveLength(1))
    expect(scrollTo).toHaveBeenCalledWith(0, 0)
    finishGame(socket)
    scrollTo.mockClear()
    fireEvent.click(screen.getByRole('button', { name: '대기실로' }))
    expect(scrollTo).toHaveBeenCalledWith(0, 0)
  })
})
