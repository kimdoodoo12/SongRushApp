// Cycle 110 (D-124): the game room renders the mockup's markup
// (docs/cycles/110-room-port.md, Markup and Acceptance 3; reference
// docs/design/108-ui-design-reference.html, #gameView).
//
// Contract with the implementer:
// - The mood is <html data-mood="<id>">: sunset by default, the roomTheme
//   frame's id in a room. No element carries data-room-theme.
// - header: .logo (the .eq bars, three <i>, and "SongRush"); .roominfo with
//   .code (the room code) and .settings (the one settings line);
//   .hd-right with .vol and .moods. .moods holds the eight mood buttons in
//   picker order, each with a .sw swatch, its i18n name and aria-pressed;
//   enabled for the host (a click sends roomTheme), disabled for a guest.
// - .main holds a .stage (with .inner) and aside.chat.
// - .stage-top: .round with .tag (the mood name and tagline) and .num
//   "index / total"; .clock with today's whole-seconds text, .low at 10 s or
//   less. .timebar (role progressbar, name 남은 시간) holds .timebar-fill.
// - .now holds .deck (.player .disc canvas, .mark) and .panel.
// - .panel: .hint and .sub while waiting, loading or playing; playing adds a
//   .row with the skip .btn (.voted after the own vote) and .skipcount.
//   Reveal: a .reveal card with h3 정답 공개, .who "정답: <name>" or
//   .who.none 정답자 없음, dl.titles (dt KO EN JA Romaji, dd.ko the Korean
//   title), .theme-line (slug, song, artist), .next; no speed line.
//   Ranked: .who lists the solvers in order, ol.rank holds the ranking rows.
//   Correct before the reveal: .hint "<name> 정답!". Error: p.warn.
//   Game over: .reveal with h3 최종 순위, ol.rank (.p place, .s points) in
//   order, and 대기실로 as .btn.primary once the lobby is open.
//   Disconnected: .reveal with today's text and 처음으로 as .btn.primary.
//   The sound unlock button is a .btn.primary in the panel.
// - .seats: h2 자리 and ol.seat-grid (list name 자리), eight li.seat; a
//   filled seat has .av and .sm (b name, small points, .ok 정답! for the
//   winner); the winner's seat is .win; empty seats are .seat.empty 빈 자리.
//   Chat is no longer shown in seats.
// - aside.chat: .chat-hd (b 채팅, .live), .msgs with one div.msg per line
//   (i, span.t "이름: 내용"; .mine for the own lines; .msg.sys, and
//   .msg.sys.correct for the ✔ line), form.send (input 정답 입력, 보내기).
// - body.playing while a song is audible.
// - room === null (the game screen before joined): no .code, no .moods.
// Timer rule (cycle 039): real timers and waitFor.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, act, cleanup, within } from '@testing-library/react'
import App from './App.jsx'
import ko from './i18n/ko.json'
import { createSocketFactory, createAudioContextFactory, createFakeFetch } from './testing/fakes.js'
import { createRoomAs, joinRoomAs } from './testing/lobby.js'

const T0 = 1_000_000
const MOOD_IDS = ['sunset', 'rain', 'sakura', 'neon', 'chalk', 'pixel', 'matsuri', 'manga']
const MOOD_NAMES = MOOD_IDS.map((id) => ko.theme[id])
const TITLES = { ko: ['진격의 거인'], en: ['Attack on Titan'], ja: ['進撃の巨人'], romaji: ['Shingeki no Kyojin'] }
const REJOIN_KEY = 'songrush.rejoin'

beforeEach(() => {
  window.history.pushState({}, '', '/')
  window.localStorage.clear()
  window.sessionStorage.clear()
})

afterEach(() => {
  cleanup()
  vi.useRealTimers()
  window.history.pushState({}, '', '/')
  vi.restoreAllMocks()
  window.localStorage.clear()
  window.sessionStorage.clear()
})

// Renders App with fakes. clock.t is what now() returns.
function setup({ audioOptions, navigationType } = {}) {
  const sockets = createSocketFactory()
  const audio = createAudioContextFactory(audioOptions)
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

const player = (name, { host = false, score = 0, connected = true } = {}) => ({ name, score, host, connected })
const roster = (players, phase = 'lobby') => ({ type: 'roster', phase, players })
// Mina hosts; the player is Hana (2), second in server order.
const GUEST_PLAYERS = [player('Mina', { host: true }), player('Hana (2)'), player('Joon')]
// 하루 hosts.
const HOST_PLAYERS = [player('하루', { host: true }), player('Mina')]
const prepare = (index = 0) => ({ type: 'prepare', index, total: 5, audioUrl: '/audio/abc' })

// A start now (receipt offset 500 while now() is T0): the song lasts `length` ms.
function startMsg({ index = 0, length = 90_000 } = {}) {
  const serverTime = T0 + 500
  return { type: 'start', index, startAt: serverTime, deadlineAt: serverTime + length, serverTime }
}

const revealMsg = (overrides = {}) => ({
  type: 'reveal',
  index: 0,
  reveal: '진격의 거인',
  titles: TITLES,
  song: '紅蓮の弓矢',
  artists: ['Revo'],
  themeType: 'OP',
  themeSlug: 'OP1',
  winner: null,
  ...overrides,
})

async function openSocket(env, count = 1) {
  await waitFor(() => expect(env.sockets.sockets.length).toBe(count))
  const socket = env.sockets.last
  act(() => socket.open())
  await waitFor(() => expect(socket.sent.length).toBeGreaterThan(0))
  return socket
}

// Joins ABCDE as Hana and receives joined (Hana (2)) and the lobby roster.
async function guestLobby(env) {
  joinRoomAs('Hana', 'ABCDE')
  const socket = await openSocket(env)
  deliver(socket, { type: 'joined', code: 'ABCDE', name: 'Hana (2)' })
  deliver(socket, roster(GUEST_PLAYERS))
  return socket
}

// Creates QWERT as 하루, the host, with Mina in the lobby.
async function hostLobby(env) {
  createRoomAs('하루')
  const socket = await openSocket(env)
  deliver(socket, { type: 'joined', code: 'QWERT', name: '하루' })
  deliver(socket, roster(HOST_PLAYERS))
  return socket
}

// The lobby, then song 0 prepared, ready sent and started now.
async function songStarted(env, socket, players, { length } = {}) {
  deliver(socket, roster(players, 'playing'))
  deliver(socket, prepare(0))
  await waitFor(() => expect(socket.sentOfType('ready').filter((m) => m.index === 0)).toHaveLength(1))
  deliver(socket, startMsg({ length }))
  await waitFor(() => expect(env.audio.last.startedSources()).toHaveLength(1))
  return socket
}

async function guestPlaying(env, options) {
  const socket = await guestLobby(env)
  return songStarted(env, socket, GUEST_PLAYERS, options)
}

async function hostPlaying(env, options) {
  const socket = await hostLobby(env)
  return songStarted(env, socket, HOST_PLAYERS, options)
}

function q(selector) {
  return document.querySelector(selector)
}

function qa(selector) {
  return Array.from(document.querySelectorAll(selector))
}

function mood() {
  return document.documentElement.dataset.mood
}

function moodButtons() {
  return qa('header .moods button')
}

function seatList() {
  return screen.getByRole('list', { name: ko.seat.list })
}

function seats() {
  return within(seatList()).getAllByRole('listitem')
}

function seatOf(name) {
  const seat = seats().find((li) => li.textContent.includes(name))
  expect(seat, `a seat for ${name}`).toBeTruthy()
  return seat
}

// The chat lines and the correct line. Cycle 111 (D-124, declared): the
// client-only system lines (.msg.sys without .correct: game start, song
// start, game over) are left out here and tested in App.deck.test.jsx.
function msgs() {
  return qa('aside.chat .msgs .msg:not(.sys), aside.chat .msgs .msg.sys.correct')
}

// A line's text: its .t (cycle 111: the .tm time sits beside it), or the
// whole line for a system line.
function lineText(line) {
  return collapse((line.querySelector(':scope > .t') ?? line).textContent)
}

function revealCard() {
  const card = q('.panel .reveal')
  expect(card, 'the .reveal card in the panel').not.toBeNull()
  return card
}

const collapse = (text) => text.replace(/\s+/g, ' ').trim()

describe('mood on <html data-mood>', () => {
  it('is sunset by default and no element carries data-room-theme', () => {
    setup()
    expect(mood()).toBe('sunset')
    expect(q('[data-room-theme]')).toBeNull()
  })

  it('follows the roomTheme frame for each of the eight ids in a room game', async () => {
    const env = setup()
    const socket = await guestPlaying(env)
    expect(mood()).toBe('sunset')
    for (const id of MOOD_IDS) {
      deliver(socket, { type: 'roomTheme', theme: id })
      expect(mood(), id).toBe(id)
    }
    expect(q('[data-room-theme]')).toBeNull()
  })
})

describe('the frame of a room game', () => {
  it('main holds the stage (with .inner) and aside.chat; .now holds .deck and .panel', async () => {
    const env = setup()
    await guestPlaying(env)
    expect(q('.main .stage .inner')).not.toBeNull()
    expect(q('.main > aside.chat')).not.toBeNull()
    expect(q('.stage .now .deck')).not.toBeNull()
    expect(q('.stage .now .panel')).not.toBeNull()
  })

  it('the header has .logo with the eq bars and SongRush, .code and .settings', async () => {
    const env = setup()
    const socket = await guestPlaying(env)
    deliver(socket, { type: 'settings', types: ['OP'] })
    const logo = q('header .logo')
    expect(logo).not.toBeNull()
    expect(logo.textContent).toContain('SongRush')
    expect(logo.querySelectorAll('.eq i')).toHaveLength(3)
    expect(q('header .roominfo .code').textContent).toBe('ABCDE')
    expect(q('header .roominfo .settings').textContent).toBe(ko.filter.op)
    expect(q('header .hd-right .vol')).not.toBeNull()
  })

  it('the host sees eight enabled .moods buttons with swatches, sunset pressed; a click sends roomTheme', async () => {
    const env = setup()
    const socket = await hostPlaying(env)
    const buttons = moodButtons()
    expect(buttons.map((b) => b.textContent.trim())).toEqual(MOOD_NAMES)
    for (const b of buttons) {
      expect(b.disabled, b.textContent).toBe(false)
      expect(b.querySelector('.sw'), b.textContent).not.toBeNull()
    }
    expect(buttons.filter((b) => b.getAttribute('aria-pressed') === 'true').map((b) => b.textContent.trim())).toEqual([ko.theme.sunset])
    fireEvent.click(buttons[MOOD_IDS.indexOf('chalk')])
    await waitFor(() => expect(socket.sentOfType('roomTheme')).toEqual([{ type: 'roomTheme', theme: 'chalk' }]))
    deliver(socket, { type: 'roomTheme', theme: 'chalk' })
    expect(moodButtons().filter((b) => b.getAttribute('aria-pressed') === 'true').map((b) => b.textContent.trim())).toEqual([ko.theme.chalk])
  })

  it('a guest sees the eight .moods buttons disabled', async () => {
    const env = setup()
    const socket = await guestPlaying(env)
    const buttons = moodButtons()
    expect(buttons).toHaveLength(8)
    for (const b of buttons) expect(b.disabled, b.textContent).toBe(true)
    fireEvent.click(buttons[1])
    expect(socket.sentOfType('roomTheme')).toEqual([])
  })

  it('.stage-top .round has .num "1 / 5" and a .tag with the mood name', async () => {
    const env = setup()
    const socket = await guestPlaying(env)
    expect(q('.stage-top .round .num').textContent).toBe('1 / 5')
    expect(q('.stage-top .round .tag').textContent).toContain(ko.theme.sunset)
    deliver(socket, { type: 'roomTheme', theme: 'neon' })
    expect(q('.stage-top .round .tag').textContent).toContain(ko.theme.neon)
  })

  it('.clock reads the whole seconds and gets .low at 10 s or less', async () => {
    const env = setup()
    await guestPlaying(env, { length: 30_000 })
    const clock = () => q('.stage-top .clock')
    await waitFor(() => expect(clock().textContent).toBe('30초'))
    expect(clock().classList.contains('low')).toBe(false)
    env.clock.t = T0 + 19_000
    await waitFor(() => expect(clock().textContent).toBe('11초'))
    expect(clock().classList.contains('low')).toBe(false)
    env.clock.t = T0 + 20_000
    await waitFor(() => expect(clock().textContent).toBe('10초'))
    expect(clock().classList.contains('low')).toBe(true)
  })

  it('the time bar is a progressbar named 남은 시간 holding .timebar-fill', async () => {
    const env = setup()
    await guestPlaying(env)
    const bar = screen.getByRole('progressbar', { name: ko.stage.timeLeft })
    expect(bar.classList.contains('timebar')).toBe(true)
    expect(bar.querySelector('.timebar-fill')).not.toBeNull()
  })

  it('.deck holds .player .disc canvas and .mark; body.playing while the song is audible', async () => {
    const env = setup()
    const socket = await guestPlaying(env)
    expect(q('.deck .player .disc canvas')).not.toBeNull()
    expect(q('.deck .mark')).not.toBeNull()
    await waitFor(() => expect(document.body.classList.contains('playing')).toBe(true))
    deliver(socket, revealMsg({ winner: 'Mina' }))
    await waitFor(() => expect(document.body.classList.contains('playing')).toBe(false))
  })

  it('playing: the panel shows .hint and .sub, and a .row with the skip .btn and .skipcount', async () => {
    const env = setup()
    const socket = await guestPlaying(env)
    expect(q('.panel .hint').textContent).toBe(ko.stage.hint)
    expect(q('.panel .sub')).not.toBeNull()
    const skip = screen.getByRole('button', { name: ko.stage.skip })
    expect(skip.classList.contains('btn')).toBe(true)
    expect(skip.closest('.panel .row')).not.toBeNull()
    expect(skip.classList.contains('voted')).toBe(false)
    fireEvent.click(skip)
    expect(socket.sentOfType('skipVote')).toEqual([{ type: 'skipVote', index: 0 }])
    deliver(socket, { type: 'skipState', index: 0, votes: 1, needed: 2 })
    const voted = screen.getByRole('button', { name: ko.stage.skipCancel })
    expect(voted.classList.contains('voted')).toBe(true)
    expect(q('.panel .row .skipcount').textContent).toBe('스킵 1/2')
  })
})

describe('seats', () => {
  it('a .seat-grid list named 자리 under the h2 자리 with 8 li: filled seats with .av and .sm, then 빈 자리', async () => {
    const env = setup()
    await guestPlaying(env)
    const list = seatList()
    expect(list.tagName).toBe('OL')
    expect(list.classList.contains('seat-grid')).toBe(true)
    const heading = list.closest('.seats').querySelector('h2')
    expect(heading.textContent).toBe(ko.seat.list)
    const all = seats()
    expect(all).toHaveLength(8)
    for (const li of all) expect(li.classList.contains('seat')).toBe(true)
    const mina = all[0]
    expect(mina.querySelector('.av').textContent).toBe('M')
    expect(mina.querySelector('.sm b').textContent).toContain('Mina (방장)')
    expect(mina.querySelector('.sm small').textContent).toContain('0점')
    expect(all[1].querySelector('.sm b').textContent).toContain('Hana (2) (나)')
    for (const li of all.slice(3)) {
      expect(li.classList.contains('empty')).toBe(true)
      expect(li.textContent).toBe(ko.seat.empty)
    }
  })

  it("the winner's seat is .win with .ok 정답!", async () => {
    const env = setup()
    const socket = await guestPlaying(env)
    deliver(socket, { type: 'correct', index: 0, name: 'Mina' })
    deliver(socket, revealMsg({ winner: 'Mina' }))
    await waitFor(() => expect(seatOf('Mina').classList.contains('win')).toBe(true))
    expect(seatOf('Mina').querySelector('.ok').textContent).toBe(ko.seat.correct)
    expect(seatOf('Hana (2)').classList.contains('win')).toBe(false)
    expect(seatOf('Joon').classList.contains('win')).toBe(false)
    expect(seatOf('Joon').querySelector('.ok')).toBeNull()
  })
})

describe('chat', () => {
  it('the chat panel has .chat-hd (b 채팅, .live) and form.send with 정답 입력 and 보내기', async () => {
    const env = setup()
    await guestPlaying(env)
    expect(q('aside.chat .chat-hd b').textContent).toBe(ko.chat.label)
    expect(q('aside.chat .chat-hd .live')).not.toBeNull()
    const input = screen.getByLabelText(ko.chat.input)
    expect(input.closest('form.send')).not.toBeNull()
    expect(within(input.closest('form.send')).getByRole('button', { name: ko.chat.send })).toBeTruthy()
  })

  it("a chat line is .msg > .t 'Mina: 이거 뭐였지' in the log, and not in Mina's seat", async () => {
    const env = setup()
    const socket = await guestPlaying(env)
    deliver(socket, { type: 'chat', name: 'Mina', text: '이거 뭐였지' })
    const lines = msgs()
    expect(lines).toHaveLength(1)
    const line = lines[0]
    expect(line.querySelector(':scope > .t').textContent).toBe('Mina: 이거 뭐였지')
    expect(line.querySelector(':scope > i')).not.toBeNull()
    expect(line.classList.contains('mine')).toBe(false)
    expect(line.classList.contains('sys')).toBe(false)
    expect(seatOf('Mina').textContent).not.toContain('이거 뭐였지')
  })

  it('the own line is .msg.mine', async () => {
    const env = setup()
    const socket = await guestPlaying(env)
    const input = screen.getByLabelText(ko.chat.input)
    fireEvent.change(input, { target: { value: '원피스' } })
    fireEvent.submit(input.closest('form'))
    await waitFor(() => expect(socket.sentOfType('chat')).toEqual([{ type: 'chat', text: '원피스' }]))
    deliver(socket, { type: 'chat', name: 'Hana (2)', text: '원피스' })
    deliver(socket, { type: 'chat', name: 'Joon', text: '아님' })
    const lines = msgs()
    expect(lines.map((l) => l.querySelector('.t').textContent)).toEqual(['Hana (2): 원피스', 'Joon: 아님'])
    expect(lines.map((l) => l.classList.contains('mine'))).toEqual([true, false])
  })

  it('the correct line is .msg.sys.correct reading ✔ Mina 정답!, and the panel .hint reads Mina 정답!', async () => {
    const env = setup()
    const socket = await guestPlaying(env)
    deliver(socket, { type: 'correct', index: 0, name: 'Mina' })
    deliver(socket, { type: 'chat', name: 'Mina', text: '진격의 거인' })
    const sys = qa('.msgs .msg.sys.correct')
    expect(sys).toHaveLength(1)
    expect(collapse(sys[0].textContent)).toBe('✔ Mina 정답!')
    expect(msgs().map(lineText)).toEqual(['Mina: 진격의 거인', '✔ Mina 정답!'])
    expect(q('.panel .hint').textContent).toBe('Mina 정답!')
  })
})

describe('reveal', () => {
  it('before the reveal frame no title, song or artist text is in the DOM', async () => {
    const env = setup()
    await guestPlaying(env)
    const text = document.body.textContent
    for (const title of [...Object.values(TITLES).flat(), '紅蓮の弓矢', 'Revo']) {
      expect(text, title).not.toContain(title)
    }
    expect(q('.panel .reveal')).toBeNull()
  })

  it('the .reveal card: h3 정답 공개, .who, dl.titles with four titles, .theme-line, .next, and no speed line', async () => {
    const env = setup()
    const socket = await guestPlaying(env)
    deliver(socket, { type: 'correct', index: 0, name: 'Mina' })
    deliver(socket, revealMsg({ winner: 'Mina' }))
    const card = revealCard()
    expect(card.querySelector('h3').textContent).toBe(ko.reveal.heading)
    const who = card.querySelector('.who')
    expect(who.textContent).toBe('정답: Mina')
    expect(who.classList.contains('none')).toBe(false)
    const dl = card.querySelector('dl.titles')
    expect(dl).not.toBeNull()
    expect(Array.from(dl.querySelectorAll('dt'), (dt) => dt.textContent)).toEqual(['KO', 'EN', 'JA', 'Romaji'])
    expect(Array.from(dl.querySelectorAll('dd'), (dd) => dd.textContent)).toEqual([
      '진격의 거인',
      'Attack on Titan',
      '進撃の巨人',
      'Shingeki no Kyojin',
    ])
    expect(dl.querySelector('dd.ko').textContent).toBe('진격의 거인')
    const themeLine = card.querySelector('.theme-line').textContent
    for (const part of ['OP1', '紅蓮の弓矢', 'Revo']) expect(themeLine).toContain(part)
    expect(card.querySelector('.next')).not.toBeNull()
    expect(q('.speed')).toBeNull()
    expect(card.textContent).not.toMatch(/초 만에/)
  })

  it('no winner: .who.none reads 정답자 없음', async () => {
    const env = setup()
    const socket = await guestPlaying(env)
    deliver(socket, revealMsg({ winner: null }))
    const who = revealCard().querySelector('.who')
    expect(who.classList.contains('none')).toBe(true)
    expect(who.textContent).toBe(ko.reveal.noWinner)
  })

  it('ranked: .who lists both solvers in order, ol.rank has the ranking rows, and both seats are .win', async () => {
    const env = setup()
    const socket = await guestPlaying(env)
    deliver(socket, { type: 'settings', gameMode: 'ranked' })
    deliver(socket, { type: 'correct', index: 0, name: 'Joon', rank: 1 })
    deliver(socket, { type: 'correct', index: 0, name: 'Mina', rank: 2 })
    const ranking = [
      { name: 'Joon', rank: 1, points: 3 },
      { name: 'Mina', rank: 2, points: 2 },
    ]
    deliver(socket, revealMsg({ winner: 'Joon', ranking }))
    const card = revealCard()
    const who = card.querySelector('.who').textContent
    expect(who).toContain('Joon')
    expect(who).toContain('Mina')
    expect(who.indexOf('Joon')).toBeLessThan(who.indexOf('Mina'))
    const rows = Array.from(card.querySelectorAll('ol.rank > li'))
    expect(rows).toHaveLength(2)
    expect(rows.map((li) => li.querySelector('.p') !== null && li.querySelector('.s') !== null)).toEqual([true, true])
    expect(rows[0].textContent).toContain('Joon')
    expect(rows[1].textContent).toContain('Mina')
    await waitFor(() => expect(seatOf('Joon').classList.contains('win')).toBe(true))
    expect(seatOf('Mina').classList.contains('win')).toBe(true)
    expect(seatOf('Hana (2)').classList.contains('win')).toBe(false)
  })
})

describe('panel states', () => {
  it('an error shows as p.warn in the panel', async () => {
    const env = setup()
    const socket = await guestPlaying(env)
    deliver(socket, { type: 'error', code: 'too-long' })
    const warn = screen.getByText(ko.error['too-long'])
    expect(warn.tagName).toBe('P')
    expect(warn.classList.contains('warn')).toBe(true)
  })

  it('disconnected: a .reveal card with the text and 처음으로 as .btn.primary', async () => {
    const env = setup()
    const socket = await guestPlaying(env)
    act(() => socket.serverClose())
    const card = revealCard()
    expect(card.textContent).toContain(ko.game.disconnected)
    const reset = within(card).getByRole('button', { name: ko.game.reset })
    expect(reset.classList.contains('btn')).toBe(true)
    expect(reset.classList.contains('primary')).toBe(true)
  })

  it('game over: .reveal with h3 최종 순위, ol.rank in score order with .p and .s, and 대기실로 as .btn.primary', async () => {
    const env = setup()
    const socket = await guestPlaying(env)
    deliver(socket, revealMsg({ winner: 'Mina' }))
    deliver(socket, {
      type: 'gameOver',
      ranking: [
        { rank: 1, name: 'Mina', score: 3 },
        { rank: 2, name: 'Hana (2)', score: 1 },
        { rank: 3, name: 'Joon', score: 0 },
      ],
    })
    deliver(socket, roster(GUEST_PLAYERS, 'lobby'))
    const card = revealCard()
    expect(card.querySelector('h3').textContent).toBe(ko.score.ranking)
    const rows = Array.from(card.querySelectorAll('ol.rank > li'))
    expect(rows).toHaveLength(3)
    expect(rows.map((li) => li.querySelector('.p').textContent)).toEqual(['1위', '2위', '3위'])
    expect(rows.map((li) => li.querySelector('.s').textContent)).toEqual(['3점', '1점', '0점'])
    expect(rows[0].textContent).toContain('Mina')
    expect(rows[1].textContent).toContain('Hana (2)')
    expect(rows[2].textContent).toContain('Joon')
    expect(screen.getByText('최종 점수: 1점')).toBeTruthy()
    const toLobby = within(card).getByRole('button', { name: ko.game.toLobby })
    expect(toLobby.classList.contains('btn')).toBe(true)
    expect(toLobby.classList.contains('primary')).toBe(true)
  })

  // Cycle 119 (D-127, declared): a create or join from the nickname dialog
  // stays on the start screen until joined, so the game frame before joined
  // is reached by a reload rejoin.
  it('before joined (room null): the connecting text as .hint in the panel, no .code and no .moods', async () => {
    window.sessionStorage.setItem(REJOIN_KEY, JSON.stringify({ token: 'T1', code: 'ABCDE' }))
    const env = setup({ navigationType: 'reload' })
    await waitFor(() => expect(env.sockets.sockets.length).toBe(1))
    const text = screen.getByText(ko.stage.connecting)
    expect(text.classList.contains('hint')).toBe(true)
    expect(text.closest('.panel')).not.toBeNull()
    expect(q('.code')).toBeNull()
    expect(q('.moods')).toBeNull()
  })
})

describe('after a reload rejoin', () => {
  function seed() {
    window.sessionStorage.setItem(REJOIN_KEY, JSON.stringify({ token: 'T1', code: 'ABCDE' }))
  }

  async function rejoined(env) {
    const socket = await openSocket(env)
    expect(socket.sent[0]).toEqual({ type: 'rejoin', token: 'T1' })
    deliver(socket, { type: 'joined', code: 'ABCDE', name: '하루' })
    return socket
  }

  it('a late joiner waiting for the next song sees the waiting text as a .hint or .sub in the panel', async () => {
    seed()
    const env = setup({ navigationType: 'reload' })
    const socket = await rejoined(env)
    deliver(socket, { type: 'snapshot', phase: 'playing', players: HOST_PLAYERS, song: null, scores: [] })
    const text = await screen.findByText(ko.stage.waitingNext)
    expect(text.classList.contains('hint') || text.classList.contains('sub')).toBe(true)
    expect(text.closest('.panel')).not.toBeNull()
  })

  it('the sound unlock button is a .btn.primary in the panel and resumes the audio context', async () => {
    seed()
    const env = setup({ navigationType: 'reload', audioOptions: { state: 'suspended', deferResume: true } })
    const socket = await rejoined(env)
    const startAt = T0 - 10_000
    deliver(socket, {
      type: 'snapshot',
      phase: 'playing',
      players: HOST_PLAYERS,
      song: { index: 0, total: 5, audioUrl: '/audio/re', startAt, deadlineAt: startAt + 90_000 },
      scores: [],
    })
    const button = await screen.findByRole('button', { name: ko.stage.unlockSound })
    expect(button.classList.contains('btn')).toBe(true)
    expect(button.classList.contains('primary')).toBe(true)
    expect(button.closest('.panel')).not.toBeNull()
    const ctx = env.audio.last
    const before = ctx.resume.mock.calls.length
    fireEvent.click(button)
    expect(ctx.resume.mock.calls.length).toBeGreaterThan(before)
  })
})
