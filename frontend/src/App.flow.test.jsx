// The game flow of one player. Cycle 078 (D-093): there is no solo form;
// the player plays alone by creating a room (방 만들기), choosing 곡 수 in
// the lobby and clicking 게임 시작.
// Cycle 114 (D-124, declared): 곡 수 is the lobby stepper and the start
// button is .start-row .go, both through src/testing/lobby.js.
import { describe, it, expect, vi, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, act, cleanup } from '@testing-library/react'
import App from './App.jsx'
import { createSocketFactory, createAudioContextFactory, createFakeFetch } from './testing/fakes.js'
import * as lobby from './testing/lobby.js'

const CREDIT = '데이터 출처: AnimeThemes, AniList, anime-offline-database, AniDB, Wikidata'
const NO_OPUS =
  '이 브라우저에서는 음원을 재생할 수 없습니다. 브라우저 또는 iOS를 최신 버전으로 업데이트해 주세요.'
const DISCONNECTED = '서버와 연결이 끊어졌습니다.'
const T0 = 1_000_000

afterEach(() => {
  cleanup()
  vi.useRealTimers()
})

// Renders App with fakes. clock.t is what now() returns.
function setup({ canPlayOpus = true, ctxOptions, fetchOptions } = {}) {
  const sockets = createSocketFactory()
  const audio = createAudioContextFactory(ctxOptions)
  const fetch = createFakeFetch(fetchOptions)
  const clock = { t: T0 }
  render(
    <App
      canPlayOpus={canPlayOpus}
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

function setNickname(nickname) {
  fireEvent.change(screen.getByLabelText('닉네임'), { target: { value: nickname } })
}

const player = (name, { host = false } = {}) => ({ name, score: 0, host, connected: true })
// A room of one: how a player plays alone (D-093).
const ALONE = [player('하루', { host: true })]
const roster = (phase = 'lobby') => ({ type: 'roster', phase, players: ALONE })
const joinedMsg = { type: 'joined', code: 'QWERT', name: '하루' }

// 방 만들기 as 하루 and opens socket number `count`. Real timers only.
async function clickCreate(env, count = 1) {
  setNickname('하루')
  fireEvent.click(screen.getByRole('button', { name: '방 만들기' }))
  await waitFor(() => expect(env.sockets.sockets.length).toBe(count))
  const socket = env.sockets.last
  act(() => socket.open())
  await waitFor(() => expect(socket.sentOfType('create')).toHaveLength(1))
  return socket
}

// clickCreate, then joined and the lobby roster with 하루 alone as host.
async function aloneLobby(env, count = 1) {
  const socket = await clickCreate(env, count)
  deliver(socket, joinedMsg)
  deliver(socket, roster('lobby'))
  return socket
}

// Sets 곡 수 with the lobby stepper and clicks the start button.
function clickStartGame(songCount = '5') {
  lobby.setSongCount(Number(songCount))
  lobby.startGame()
}

// Alone in a room, 게임 시작 with 곡 수 5 sent and roster{playing}
// received: the socket is ready for prepare. Real timers only.
async function startGame(env) {
  const socket = await aloneLobby(env)
  clickStartGame()
  await waitFor(() => expect(socket.sentOfType('start')).toEqual([{ type: 'start', songCount: 5 }]))
  deliver(socket, roster('playing'))
  return socket
}

const prepare = (index = 0) => ({ type: 'prepare', index, total: 5, audioUrl: '/audio/abc' })

// serverTime is T0 + 500, so offsetMs = 500 when now() is T0.
function startMsg({ index = 0, startDelta, length = 90_000 }) {
  const serverTime = T0 + 500
  const startAt = serverTime + startDelta
  return { type: 'start', index, startAt, deadlineAt: startAt + length, serverTime }
}

const revealMsg = (overrides = {}) => ({
  type: 'reveal',
  index: 0,
  reveal: '진격의 거인',
  titles: { ko: ['진격의 거인'], en: ['Attack on Titan'], ja: ['進撃の巨人'], romaji: ['Shingeki no Kyojin'] },
  song: '紅蓮の弓矢',
  artists: ['Revo', 'Linked Horizon'],
  themeType: 'OP',
  themeSlug: 'OP1',
  winner: null,
  ...overrides,
})

// Starts a game, delivers prepare and waits for ready{0}.
async function startAndPrepare(env) {
  const socket = await startGame(env)
  deliver(socket, prepare(0))
  await waitFor(() => expect(socket.sentOfType('ready')).toEqual([{ type: 'ready', index: 0 }]))
  return socket
}

// Fake-timer flush: advances 0 ms (running pending microtasks) until cond()
// holds, at most max times.
async function flushUntil(cond, max = 20) {
  for (let i = 0; i < max && !cond(); i++) {
    await act(async () => {
      await vi.advanceTimersByTimeAsync(0)
    })
  }
}

// startAndPrepare for fake timers (waitFor does not drive them).
async function startAndPrepareFakeTimers(env) {
  setNickname('하루')
  fireEvent.click(screen.getByRole('button', { name: '방 만들기' }))
  await flushUntil(() => env.sockets.sockets.length === 1)
  const socket = env.sockets.last
  act(() => socket.open())
  await flushUntil(() => socket.sentOfType('create').length === 1)
  expect(socket.sentOfType('create')).toHaveLength(1)
  deliver(socket, joinedMsg)
  deliver(socket, roster('lobby'))
  clickStartGame()
  await flushUntil(() => socket.sentOfType('start').length === 1)
  expect(socket.sentOfType('start')).toEqual([{ type: 'start', songCount: 5 }])
  deliver(socket, roster('playing'))
  deliver(socket, prepare(0))
  await flushUntil(() => socket.sentOfType('ready').length > 0)
  expect(socket.sentOfType('ready')).toEqual([{ type: 'ready', index: 0 }])
  return socket
}

// Advances the clock and the fake timers by 250 ms; returns aria-valuenow.
async function tick(env) {
  env.clock.t += 250
  await act(async () => {
    await vi.advanceTimersByTimeAsync(250)
  })
  return Number(screen.getByRole('progressbar').getAttribute('aria-valuenow'))
}

describe('start screen', () => {
  it('shows the nickname input, 방 만들기 and the credit line; the host lobby has the song count stepper at 10 within 5-30', async () => {
    const env = setup()
    expect(screen.getByRole('heading', { level: 1, name: 'SongRush' })).toBeTruthy()
    const nickname = screen.getByLabelText('닉네임')
    expect(nickname.tagName).toBe('INPUT')
    expect(nickname.maxLength).toBe(16)
    expect(screen.getByRole('button', { name: '방 만들기' }).disabled).toBe(false)
    expect(screen.getByText(CREDIT)).toBeTruthy()
    await aloneLobby(env)
    expect(screen.getByLabelText('곡 수')).toBe(lobby.songCountOutput())
    expect(lobby.songCount()).toBe(10)
    lobby.setSongCount(5)
    expect(lobby.stepperButtons()[0].disabled).toBe(true)
    lobby.setSongCount(30)
    expect(lobby.stepperButtons()[1].disabled).toBe(true)
    expect(screen.getByText(CREDIT)).toBeTruthy()
  })

  it('canPlayOpus={false} shows the notice and disables 방 만들기', () => {
    setup({ canPlayOpus: false })
    expect(screen.getByText(NO_OPUS)).toBeTruthy()
    expect(screen.getByRole('button', { name: '방 만들기' }).disabled).toBe(true)
  })
})

describe('starting a game', () => {
  it('방 만들기 calls resume() in the click; 게임 시작 then sends start with the song count as a number', async () => {
    const env = setup()
    setNickname('하루')
    fireEvent.click(screen.getByRole('button', { name: '방 만들기' }))
    // Synchronously inside the click handler (audio unlock).
    expect(env.audio.factory).toHaveBeenCalledTimes(1)
    expect(env.audio.last.resume).toHaveBeenCalledTimes(1)
    await waitFor(() => expect(env.sockets.sockets.length).toBe(1))
    expect(env.sockets.factory).toHaveBeenCalledWith(`ws://${window.location.host}/ws`)
    const socket = env.sockets.last
    expect(socket.sent).toEqual([])
    act(() => socket.open())
    await waitFor(() => expect(socket.sent).toEqual([{ type: 'create', nickname: '하루' }]))
    deliver(socket, joinedMsg)
    deliver(socket, roster('lobby'))
    clickStartGame('5')
    await waitFor(() => expect(socket.sentOfType('start')).toEqual([{ type: 'start', songCount: 5 }]))
    expect(typeof socket.sentOfType('start')[0].songCount).toBe('number')
  })

  it('a second game reuses the AudioContext and only resumes it again', async () => {
    const env = setup()
    const socket = await startAndPrepare(env)
    deliver(socket, { type: 'gameOver', ranking: [{ rank: 1, name: '하루', score: 0 }] })
    deliver(socket, roster('lobby'))
    fireEvent.click(screen.getByRole('button', { name: '대기실로' }))
    fireEvent.click(screen.getByRole('button', { name: '나가기' }))
    setNickname('하루')
    fireEvent.click(screen.getByRole('button', { name: '방 만들기' }))
    expect(env.audio.factory).toHaveBeenCalledTimes(1)
    expect(env.audio.last.resume).toHaveBeenCalledTimes(2)
    await waitFor(() => expect(env.sockets.sockets.length).toBe(2))
  })
})

describe('prepare', () => {
  it('fetches audioUrl, shows 1 / 5 while loading, then sends ready{index}', async () => {
    const env = setup({ ctxOptions: { deferDecode: true } })
    const socket = await startGame(env)
    deliver(socket, prepare(0))
    expect(screen.getByText('1 / 5')).toBeTruthy()
    expect(screen.getByText('음원을 불러오는 중...')).toBeTruthy()
    await waitFor(() => expect(env.audio.last.pendingDecodes).toHaveLength(1))
    expect(env.fetch.urls).toEqual(['/audio/abc'])
    expect(socket.sentOfType('ready')).toEqual([])
    await act(async () => env.audio.last.resolveDecode())
    await waitFor(() => expect(socket.sentOfType('ready')).toEqual([{ type: 'ready', index: 0 }]))
    expect(screen.getByText('1 / 5')).toBeTruthy()
  })

  it('two failed loads show the failure text and still send ready', async () => {
    const env = setup({ fetchOptions: { failures: 2, failWith: 'reject' } })
    const socket = await startGame(env)
    deliver(socket, prepare(0))
    await waitFor(() => expect(screen.getByText('이번 곡을 재생하지 못했습니다.')).toBeTruthy())
    await waitFor(() => expect(socket.sentOfType('ready')).toEqual([{ type: 'ready', index: 0 }]))
    expect(env.fetch.urls).toEqual(['/audio/abc', '/audio/abc'])
  })
})

describe('start', () => {
  it('on time: source.start(when, 0) and stop(stopAt) from planPlayback', async () => {
    const env = setup()
    const socket = await startAndPrepare(env)
    const ctx = env.audio.last
    ctx.currentTime = 10
    // offsetMs 500, delay 3000 ms, deadline 93000 ms away.
    deliver(socket, startMsg({ startDelta: 3000 }))
    await waitFor(() => expect(ctx.startedSources()).toHaveLength(1))
    const source = ctx.startedSources()[0]
    expect(source.buffer).toBe(ctx.decoded[0])
    // declared rewrite 085: source -> the context's gain node -> destination.
    expect(ctx.gains).toHaveLength(1)
    expect(source.connect).toHaveBeenCalledWith(ctx.gains[0])
    expect(ctx.gains[0].connect).toHaveBeenCalledWith(ctx.destination)
    const [when, offset] = source.start.mock.calls[0]
    expect(when).toBeCloseTo(13, 6)
    expect(offset).toBe(0)
    expect(source.stop).toHaveBeenCalledTimes(1)
    expect(source.stop.mock.calls[0][0]).toBeCloseTo(103, 6)
  })

  it('late: source.start(ctxTime, 2.5) when the start was 2500 ms ago', async () => {
    const env = setup()
    const socket = await startAndPrepare(env)
    const ctx = env.audio.last
    ctx.currentTime = 10
    deliver(socket, startMsg({ startDelta: -2500 }))
    await waitFor(() => expect(ctx.startedSources()).toHaveLength(1))
    const source = ctx.startedSources()[0]
    const [when, offset] = source.start.mock.calls[0]
    expect(when).toBeCloseTo(10, 6)
    expect(offset).toBeCloseTo(2.5, 6)
    expect(source.stop.mock.calls[0][0]).toBeCloseTo(97.5, 6)
  })

  it('a decode that finishes after start schedules from the current point', async () => {
    const env = setup({ ctxOptions: { deferDecode: true } })
    const socket = await startGame(env)
    const ctx = env.audio.last
    ctx.currentTime = 10
    deliver(socket, prepare(0))
    await waitFor(() => expect(ctx.pendingDecodes).toHaveLength(1))
    deliver(socket, startMsg({ startDelta: 3000 }))
    // 4 s pass before the decode finishes: the song started 1 s ago.
    env.clock.t = T0 + 4000
    ctx.currentTime = 14
    await act(async () => ctx.resolveDecode())
    await waitFor(() => expect(ctx.startedSources()).toHaveLength(1))
    const [when, offset] = ctx.startedSources()[0].start.mock.calls[0]
    expect(when).toBeCloseTo(14, 6)
    expect(offset).toBeCloseTo(1, 6)
  })

  it('a decode that resolves after the song is revealed does not call source.start', async () => {
    const env = setup({ ctxOptions: { deferDecode: true } })
    const socket = await startGame(env)
    const ctx = env.audio.last
    deliver(socket, prepare(0))
    await waitFor(() => expect(ctx.pendingDecodes).toHaveLength(1))
    deliver(socket, startMsg({ startDelta: 0 }))
    deliver(socket, revealMsg())
    await act(async () => {
      ctx.resolveDecode()
      await new Promise((resolve) => setTimeout(resolve, 50))
    })
    expect(ctx.startedSources()).toEqual([])
  })

  it('the progressbar has aria-valuemax = deadlineAt - startAt and aria-valuenow falls every 250 ms', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval'] })
    const env = setup()
    const socket = await startAndPrepareFakeTimers(env)
    // offsetMs 500; startAt is the server's now, deadline 90000 ms later.
    deliver(socket, startMsg({ startDelta: 0, length: 90_000 }))
    await flushUntil(() => screen.queryByRole('progressbar') !== null)
    const bar = screen.getByRole('progressbar')
    expect(bar.getAttribute('aria-valuemin')).toBe('0')
    expect(bar.getAttribute('aria-valuemax')).toBe('90000')

    const first = await tick(env)
    expect(first).toBe(89_750)
    const second = await tick(env)
    expect(second).toBe(89_500)
    expect(second).toBeLessThan(first)
  })

  it('before startAt the progressbar aria-valuenow equals aria-valuemax', async () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval'] })
    const env = setup()
    const socket = await startAndPrepareFakeTimers(env)
    // The song starts 3000 ms from now; one 250 ms tick is still before it.
    deliver(socket, startMsg({ startDelta: 3000, length: 90_000 }))
    await flushUntil(() => screen.queryByRole('progressbar') !== null)
    const now = await tick(env)
    const bar = screen.getByRole('progressbar')
    expect(bar.getAttribute('aria-valuemax')).toBe('90000')
    expect(now).toBe(90_000)
  })

  it('a suspended context is resumed before scheduling; source.start waits for resume', async () => {
    const env = setup({ ctxOptions: { state: 'suspended', deferResume: true } })
    const socket = await startAndPrepare(env)
    const ctx = env.audio.last
    // The click's resume() is still pending, so the context is suspended.
    expect(ctx.state).toBe('suspended')
    const resumesBefore = ctx.resume.mock.calls.length
    ctx.currentTime = 10
    deliver(socket, startMsg({ startDelta: 3000 }))
    await waitFor(() => expect(ctx.resume.mock.calls.length).toBeGreaterThan(resumesBefore))
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 50))
    })
    expect(ctx.startedSources()).toEqual([])
    await act(async () => ctx.resolveResume())
    await waitFor(() => expect(ctx.startedSources()).toHaveLength(1))
    const [when, offset] = ctx.startedSources()[0].start.mock.calls[0]
    expect(when).toBeCloseTo(13, 6)
    expect(offset).toBe(0)
  })

  it('a decode result for an older index is dropped', async () => {
    const env = setup({ ctxOptions: { deferDecode: true } })
    const socket = await startGame(env)
    const ctx = env.audio.last
    deliver(socket, prepare(0))
    await waitFor(() => expect(ctx.pendingDecodes).toHaveLength(1))
    deliver(socket, startMsg({ index: 0, startDelta: 0 }))
    deliver(socket, prepare(1))
    await waitFor(() => expect(ctx.pendingDecodes).toHaveLength(2))
    // Song 0's decode (the oldest) resolves after prepare for index 1.
    await act(async () => {
      ctx.resolveDecode()
      await new Promise((resolve) => setTimeout(resolve, 50))
    })
    expect(ctx.startedSources()).toEqual([])
    await act(async () => ctx.resolveDecode())
    await waitFor(() => expect(socket.sentOfType('ready')).toContainEqual({ type: 'ready', index: 1 }))
    deliver(socket, startMsg({ index: 1, startDelta: 0 }))
    await waitFor(() => expect(ctx.startedSources()).toHaveLength(1))
    expect(ctx.startedSources()[0].buffer).toBe(ctx.decoded[1])
  })

  it('a reveal when no source was started (load failed twice) still shows the card', async () => {
    const env = setup({ fetchOptions: { failures: 2, failWith: 'reject' } })
    const socket = await startGame(env)
    deliver(socket, prepare(0))
    await waitFor(() => expect(socket.sentOfType('ready')).toEqual([{ type: 'ready', index: 0 }]))
    deliver(socket, startMsg({ startDelta: 0 }))
    deliver(socket, revealMsg())
    expect(env.audio.last.startedSources()).toEqual([])
    // Cycle 111 (declared): the title is also in the deck's .noimg and .cap,
    // so the reveal card's Korean title line is read directly.
    expect(document.querySelector('.reveal dl.titles dd.ko').textContent).toBe('진격의 거인')
    expect(screen.getByText('정답자 없음')).toBeTruthy()
  })
})

describe('chat', () => {
  it('submit sends chat{text} and clears the input; an empty input sends nothing', async () => {
    const env = setup()
    const socket = await startAndPrepare(env)
    deliver(socket, startMsg({ startDelta: 0 }))
    const input = screen.getByLabelText('정답 입력')
    fireEvent.change(input, { target: { value: '나루토' } })
    fireEvent.submit(input.closest('form'))
    await waitFor(() => expect(socket.sentOfType('chat')).toEqual([{ type: 'chat', text: '나루토' }]))
    expect(screen.getByLabelText('정답 입력').value).toBe('')
    fireEvent.submit(screen.getByLabelText('정답 입력').closest('form'))
    expect(socket.sentOfType('chat')).toHaveLength(1)
  })

  it('incoming chat lines show as <name>: <text>', async () => {
    const env = setup()
    const socket = await startAndPrepare(env)
    deliver(socket, startMsg({ startDelta: 0 }))
    deliver(socket, { type: 'chat', name: '하루', text: '원피스' })
    deliver(socket, { type: 'chat', name: '하루', text: '나루토' })
    expect(screen.getByText('하루: 원피스')).toBeTruthy()
    expect(screen.getByText('하루: 나루토')).toBeTruthy()
  })

  it('correct shows <name> 정답!', async () => {
    const env = setup()
    const socket = await startAndPrepare(env)
    deliver(socket, startMsg({ startDelta: 0 }))
    deliver(socket, { type: 'correct', index: 0, name: '하루' })
    expect(screen.getByText('하루 정답!')).toBeTruthy()
  })
})

describe('reveal', () => {
  it('stops the source and shows the card with 정답자 없음 and themeSlug as given', async () => {
    const env = setup()
    const socket = await startAndPrepare(env)
    const ctx = env.audio.last
    deliver(socket, startMsg({ startDelta: 0 }))
    await waitFor(() => expect(ctx.startedSources()).toHaveLength(1))
    const source = ctx.startedSources()[0]
    const stopsBefore = source.stop.mock.calls.length
    deliver(socket, revealMsg())
    expect(source.stop.mock.calls.length).toBeGreaterThan(stopsBefore)
    const lastStop = source.stop.mock.calls[source.stop.mock.calls.length - 1]
    // stop() or stop(t) with t no later than now: the song stops at once.
    if (lastStop.length > 0) expect(lastStop[0]).toBeLessThanOrEqual(ctx.currentTime)
    // Cycle 111 (declared): the title is also in the deck's .noimg and .cap,
    // so the reveal card's Korean title line is read directly.
    expect(document.querySelector('.reveal dl.titles dd.ko').textContent).toBe('진격의 거인')
    expect(screen.getByText(/OP1/)).toBeTruthy()
    expect(screen.queryByText(/OPOP1/)).toBeNull()
    expect(screen.getByText(/紅蓮の弓矢/)).toBeTruthy()
    expect(screen.getByText(/Revo, Linked Horizon/)).toBeTruthy()
    expect(screen.getByText('정답자 없음')).toBeTruthy()
  })

  it('shows 정답: <winner>, omits a null song and empty artists, and scores shows 점수: <n>', async () => {
    const env = setup()
    const socket = await startAndPrepare(env)
    deliver(socket, startMsg({ startDelta: 0 }))
    deliver(socket, revealMsg({ song: null, artists: [], themeSlug: 'ED2-EN', winner: '하루' }))
    deliver(socket, { type: 'scores', scores: [{ name: '하루', score: 1 }] })
    expect(screen.getByText('정답: 하루')).toBeTruthy()
    expect(screen.getByText(/ED2-EN/)).toBeTruthy()
    expect(screen.queryByText(/null/)).toBeNull()
    expect(screen.queryByText('정답자 없음')).toBeNull()
    expect(screen.getByText('점수: 1')).toBeTruthy()
  })
})

describe('game over and errors', () => {
  it('gameOver shows the final score; 대기실로 and 나가기 return to the start screen and ignore the old socket', async () => {
    const env = setup()
    const socket = await startAndPrepare(env)
    deliver(socket, { type: 'gameOver', ranking: [{ rank: 1, name: '하루', score: 3 }] })
    expect(screen.getByText('최종 점수: 3점')).toBeTruthy()
    deliver(socket, roster('lobby'))
    fireEvent.click(screen.getByRole('button', { name: '대기실로' }))
    fireEvent.click(screen.getByRole('button', { name: '나가기' }))
    expect(socket.closed).toBe(true)
    expect(screen.getByRole('button', { name: '방 만들기' })).toBeTruthy()
    expect(screen.getByLabelText('닉네임')).toBeTruthy()
    act(() => socket.serverClose())
    expect(screen.queryByText(DISCONNECTED)).toBeNull()
    expect(screen.getByRole('button', { name: '방 만들기' })).toBeTruthy()
  })

  it('error{code:"not-enough-songs"} after 게임 시작 shows its message in the lobby, which stays open', async () => {
    const env = setup()
    const socket = await aloneLobby(env)
    clickStartGame()
    await waitFor(() => expect(socket.sentOfType('start')).toHaveLength(1))
    deliver(socket, { type: 'error', code: 'not-enough-songs' })
    expect(screen.getByText('출제할 곡이 부족합니다.')).toBeTruthy()
    expect(lobby.startButton()).not.toBeNull()
    expect(socket.closed).toBe(false)
  })

  it.each([
    ['bad-nickname', '닉네임은 1~16자로 입력해 주세요.'],
    ['xyz', '오류가 발생했습니다 (xyz)'],
  ])('an error{code:"%s"} answering create shows its message on the start screen', async (code, text) => {
    const env = setup()
    const socket = await clickCreate(env)
    deliver(socket, { type: 'error', code })
    expect(screen.getByText(text)).toBeTruthy()
    expect(screen.getByRole('button', { name: '방 만들기' })).toBeTruthy()
    expect(socket.closed).toBe(true)
    act(() => socket.serverClose())
    expect(screen.queryByText(DISCONNECTED)).toBeNull()
  })

  it('error{code:"bad-song-count"} after 게임 시작 shows its message in the lobby', async () => {
    const env = setup()
    const socket = await aloneLobby(env)
    clickStartGame()
    await waitFor(() => expect(socket.sentOfType('start')).toHaveLength(1))
    deliver(socket, { type: 'error', code: 'bad-song-count' })
    expect(screen.getByText('곡 수는 5~30곡입니다.')).toBeTruthy()
    expect(lobby.startButton()).not.toBeNull()
    expect(socket.closed).toBe(false)
  })

  it('error{code:"too-long"} during a game shows its message', async () => {
    const env = setup()
    const socket = await startAndPrepare(env)
    deliver(socket, startMsg({ startDelta: 0 }))
    deliver(socket, { type: 'error', code: 'too-long' })
    expect(screen.getByText('채팅은 200자까지 입력할 수 있습니다.')).toBeTruthy()
  })

  it('an unexpected close shows the disconnect message and 처음으로', async () => {
    const env = setup()
    const socket = await startAndPrepare(env)
    deliver(socket, startMsg({ startDelta: 0 }))
    act(() => socket.serverClose())
    expect(screen.getByText(DISCONNECTED)).toBeTruthy()
    expect(screen.getByRole('button', { name: '처음으로' })).toBeTruthy()
  })
})
