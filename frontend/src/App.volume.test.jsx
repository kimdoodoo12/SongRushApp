// Cycle 085 (D-099): per-player volume. The session creates one GainNode per
// AudioContext; every song source connects to it and it connects to
// ctx.destination. Gain = (v/100)^2, 0 when muted. The setting is stored in
// localStorage['songrush.volume'] as JSON {"v": 0-100, "muted": bool}; a
// missing, invalid or blocked value means {v: 80, muted: false}.
//
// Markup contract the implementer must follow:
//   - the slider is an <input type="range" min="0" max="100" step="1"> with
//     role slider and accessible name t('volume.label') (ko 음량, en Volume,
//     ja 音量), and aria-valuetext like '80%';
//   - the mute button is a <button aria-pressed> with accessible name
//     t('volume.mute') (ko 음소거, en Mute, ja ミュート); the name does not
//     change with the pressed state;
//   - both are on the game stage once a song has started.
// Timer rule (cycle 039): real timers and waitFor.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, act, cleanup, within } from '@testing-library/react'
import App from './App.jsx'
import ko from './i18n/ko.json'
import en from './i18n/en.json'
import ja from './i18n/ja.json'
import { createSocketFactory, createAudioContextFactory, createFakeFetch } from './testing/fakes.js'

const T0 = 1_000_000
const VOLUME_KEY = 'songrush.volume'

beforeEach(() => {
  window.history.pushState({}, '', '/')
  window.localStorage.clear()
})

afterEach(() => {
  vi.restoreAllMocks()
  // Back to Korean through the switcher, so the shared i18n instance does not
  // carry a language into the next test.
  const group = screen.queryByRole('group', { name: 'Language' })
  if (group !== null) {
    const korean = within(group).queryByRole('button', { name: '한국어' })
    if (korean !== null) fireEvent.click(korean)
  }
  cleanup()
  window.history.pushState({}, '', '/')
  window.localStorage.clear()
})

function setup({ ctxOptions } = {}) {
  const sockets = createSocketFactory()
  const audio = createAudioContextFactory(ctxOptions)
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

const player = (name, { host = false } = {}) => ({ name, score: 0, host, connected: true })
const PLAYERS = [player('하루', { host: true })]
const roster = (phase) => ({ type: 'roster', phase, players: PLAYERS })

// Creates a room alone as 하루 (labels from `dict`) on socket number `count`,
// then the game starts and prepare{0} is delivered. Returns the socket.
async function gamePrepared(env, dict = ko, count = 1) {
  fireEvent.change(screen.getByLabelText(dict.start.nickname), { target: { value: '하루' } })
  fireEvent.click(screen.getByRole('button', { name: dict.start.createRoom }))
  await waitFor(() => expect(env.sockets.sockets.length).toBe(count))
  const socket = env.sockets.last
  act(() => socket.open())
  await waitFor(() => expect(socket.sentOfType('create')).toHaveLength(1))
  deliver(socket, { type: 'joined', code: 'QWERT', name: '하루' })
  deliver(socket, roster('lobby'))
  deliver(socket, roster('playing'))
  deliver(socket, { type: 'prepare', index: 0, total: 5, audioUrl: '/audio/abc' })
  return socket
}

function deliverStart(socket, index = 0) {
  const serverTime = T0 + 500
  deliver(socket, { type: 'start', index, startAt: serverTime, deadlineAt: serverTime + 90_000, serverTime })
}

// gamePrepared, then ready sent and song 0 started at once. `started` is the
// number of sources the context has started once this song is playing.
async function songStarted(env, dict = ko, count = 1, started = 1) {
  const socket = await gamePrepared(env, dict, count)
  await waitFor(() => expect(socket.sentOfType('ready')).toEqual([{ type: 'ready', index: 0 }]))
  deliverStart(socket)
  await waitFor(() => expect(env.audio.last.startedSources()).toHaveLength(started))
  return socket
}

function slider(dict = ko) {
  return screen.getByRole('slider', { name: dict.volume.label })
}

function muteButton(dict = ko) {
  return screen.getByRole('button', { name: dict.volume.mute })
}

function moveSlider(value) {
  fireEvent.change(slider(), { target: { value: String(value) } })
}

function stored() {
  const raw = window.localStorage.getItem(VOLUME_KEY)
  return raw === null ? null : JSON.parse(raw)
}

// The single gain node of the context; fails if there is not exactly one.
function theGain(env) {
  const ctx = env.audio.last
  expect(ctx.gains).toHaveLength(1)
  return ctx.gains[0]
}

describe('audio graph', () => {
  it('the first song connects source -> gain -> destination with gain 0.64 (default 80)', async () => {
    const env = setup()
    await songStarted(env)
    const ctx = env.audio.last
    const gain = theGain(env)
    const source = ctx.startedSources()[0]
    expect(source.connect).toHaveBeenCalledWith(gain)
    expect(source.connect).not.toHaveBeenCalledWith(ctx.destination)
    expect(gain.connect).toHaveBeenCalledWith(ctx.destination)
    expect(gain.gain.value).toBeCloseTo(0.64, 6)
    // The control shows the default.
    const s = slider()
    expect(s.tagName).toBe('INPUT')
    expect(s.type).toBe('range')
    expect(s.min).toBe('0')
    expect(s.max).toBe('100')
    expect(s.step).toBe('1')
    expect(s.value).toBe('80')
    expect(s.getAttribute('aria-valuetext')).toBe('80%')
    expect(muteButton().getAttribute('aria-pressed')).toBe('false')
  })
})

describe('slider and mute', () => {
  it('moving the slider to 50 sets 0.25 on the same node mid-song and stores it', async () => {
    const env = setup()
    await songStarted(env)
    const gain = theGain(env)
    moveSlider(50)
    expect(env.audio.last.gains).toHaveLength(1)
    expect(env.audio.last.gains[0]).toBe(gain)
    expect(gain.gain.value).toBeCloseTo(0.25, 6)
    expect(slider().value).toBe('50')
    expect(slider().getAttribute('aria-valuetext')).toBe('50%')
    expect(stored()).toEqual({ v: 50, muted: false })
    // Nothing about playback changed: still one source, started once.
    expect(env.audio.last.startedSources()).toHaveLength(1)
    expect(env.audio.last.startedSources()[0].start).toHaveBeenCalledTimes(1)
  })

  it('mute sets gain 0 with aria-pressed=true and keeps the slider; unmute restores 0.25', async () => {
    const env = setup()
    await songStarted(env)
    const gain = theGain(env)
    moveSlider(50)
    fireEvent.click(muteButton())
    expect(gain.gain.value).toBeCloseTo(0, 6)
    expect(muteButton().getAttribute('aria-pressed')).toBe('true')
    expect(slider().value).toBe('50')
    expect(stored()).toEqual({ v: 50, muted: true })
    fireEvent.click(muteButton())
    expect(gain.gain.value).toBeCloseTo(0.25, 6)
    expect(muteButton().getAttribute('aria-pressed')).toBe('false')
    expect(stored()).toEqual({ v: 50, muted: false })
  })

  it('moving the slider above 0 while muted unmutes', async () => {
    const env = setup()
    await songStarted(env)
    const gain = theGain(env)
    fireEvent.click(muteButton())
    expect(gain.gain.value).toBeCloseTo(0, 6)
    moveSlider(60)
    expect(muteButton().getAttribute('aria-pressed')).toBe('false')
    expect(gain.gain.value).toBeCloseTo(0.36, 6)
    expect(stored()).toEqual({ v: 60, muted: false })
  })

  it('a slider at 0 gives gain 0', async () => {
    const env = setup()
    await songStarted(env)
    const gain = theGain(env)
    moveSlider(0)
    expect(gain.gain.value).toBeCloseTo(0, 6)
    expect(slider().getAttribute('aria-valuetext')).toBe('0%')
  })

  it('typing in the chat input does not change the volume', async () => {
    const env = setup()
    await songStarted(env)
    const gain = theGain(env)
    const before = gain.gain.value
    const chat = screen.getByLabelText('정답 입력')
    fireEvent.keyDown(chat, { key: 'ArrowLeft' })
    fireEvent.keyDown(chat, { key: 'm' })
    fireEvent.change(chat, { target: { value: 'm' } })
    expect(gain.gain.value).toBe(before)
    expect(slider().value).toBe('80')
    expect(muteButton().getAttribute('aria-pressed')).toBe('false')
  })
})

describe('stored setting', () => {
  it('{"v":30,"muted":true} before mount gives gain 0 and slider 30; unmute gives 0.09', async () => {
    window.localStorage.setItem(VOLUME_KEY, JSON.stringify({ v: 30, muted: true }))
    const env = setup()
    await songStarted(env)
    const gain = theGain(env)
    expect(gain.gain.value).toBeCloseTo(0, 6)
    expect(slider().value).toBe('30')
    expect(muteButton().getAttribute('aria-pressed')).toBe('true')
    fireEvent.click(muteButton())
    expect(gain.gain.value).toBeCloseTo(0.09, 6)
  })

  const BAD = [
    ['not JSON', '{oops'],
    ['v out of range', JSON.stringify({ v: 150, muted: false })],
    ['v not a number', JSON.stringify({ v: 'loud', muted: false })],
    ['muted not a bool', JSON.stringify({ v: 30, muted: 'yes' })],
    ['a bare number', '42'],
    ['null', 'null'],
  ]
  for (const [label, raw] of BAD) {
    it(`an invalid stored value (${label}) gives the default 80, unmuted`, async () => {
      window.localStorage.setItem(VOLUME_KEY, raw)
      const env = setup()
      await songStarted(env)
      expect(theGain(env).gain.value).toBeCloseTo(0.64, 6)
      expect(slider().value).toBe('80')
      expect(muteButton().getAttribute('aria-pressed')).toBe('false')
    })
  }

  it('blocked storage (getItem and setItem throw) gives the default and the control still works', async () => {
    const realGet = Storage.prototype.getItem
    const realSet = Storage.prototype.setItem
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(function (key) {
      if (key === VOLUME_KEY) throw new Error('SecurityError')
      return realGet.call(this, key)
    })
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(function (key, value) {
      if (key === VOLUME_KEY) throw new Error('QuotaExceededError')
      return realSet.call(this, key, value)
    })
    const env = setup()
    await songStarted(env)
    const gain = theGain(env)
    expect(gain.gain.value).toBeCloseTo(0.64, 6)
    expect(slider().value).toBe('80')
    moveSlider(50)
    expect(gain.gain.value).toBeCloseTo(0.25, 6)
    fireEvent.click(muteButton())
    expect(gain.gain.value).toBeCloseTo(0, 6)
  })
})

describe('later songs', () => {
  it('the next song uses the same gain node and the current setting', async () => {
    const env = setup()
    const socket = await songStarted(env)
    moveSlider(50)
    deliver(socket, {
      type: 'reveal',
      index: 0,
      reveal: '진격의 거인',
      titles: { ko: ['진격의 거인'] },
      song: '紅蓮の弓矢',
      artists: ['Revo'],
      themeType: 'OP',
      themeSlug: 'OP1',
      winner: null,
    })
    deliver(socket, { type: 'prepare', index: 1, total: 5, audioUrl: '/audio/def' })
    await waitFor(() => expect(socket.sentOfType('ready')).toHaveLength(2))
    const serverTime = T0 + 500
    deliver(socket, { type: 'start', index: 1, startAt: serverTime, deadlineAt: serverTime + 90_000, serverTime })
    const ctx = env.audio.last
    await waitFor(() => expect(ctx.startedSources()).toHaveLength(2))
    const gain = theGain(env)
    expect(ctx.startedSources()[1].connect).toHaveBeenCalledWith(gain)
    expect(gain.gain.value).toBeCloseTo(0.25, 6)
  })
})

describe('one gain node per AudioContext (spec review H1)', () => {
  it('a second game on the same context reuses ctx.gains[0]', async () => {
    const env = setup()
    const socket = await songStarted(env)
    deliver(socket, { type: 'gameOver', ranking: [{ rank: 1, name: '하루', score: 0 }] })
    deliver(socket, roster('lobby'))
    fireEvent.click(screen.getByRole('button', { name: '대기실로' }))
    fireEvent.click(screen.getByRole('button', { name: '나가기' }))
    await songStarted(env, ko, 2, 2)
    expect(env.audio.factory).toHaveBeenCalledTimes(1)
    const ctx = env.audio.last
    const gain = theGain(env)
    expect(ctx.startedSources()[1].connect).toHaveBeenCalledWith(gain)
    expect(ctx.startedSources()[1].connect).not.toHaveBeenCalledWith(ctx.destination)
    expect(gain.gain.value).toBeCloseTo(0.64, 6)
  })

  it('before the first song (loading) the slider already sets the gain the first source uses', async () => {
    const env = setup({ ctxOptions: { deferDecode: true } })
    const socket = await gamePrepared(env)
    const ctx = env.audio.last
    await waitFor(() => expect(ctx.pendingDecodes).toHaveLength(1))
    expect(ctx.sources).toEqual([])
    moveSlider(50)
    expect(ctx.gains).toHaveLength(1)
    expect(ctx.gains[0].gain.value).toBeCloseTo(0.25, 6)
    expect(ctx.gains[0].connect).toHaveBeenCalledWith(ctx.destination)
    await act(async () => ctx.resolveDecode())
    await waitFor(() => expect(socket.sentOfType('ready')).toEqual([{ type: 'ready', index: 0 }]))
    deliverStart(socket)
    await waitFor(() => expect(ctx.startedSources()).toHaveLength(1))
    const gain = theGain(env)
    expect(ctx.startedSources()[0].connect).toHaveBeenCalledWith(gain)
    expect(gain.gain.value).toBeCloseTo(0.25, 6)
  })
})

describe('unmuting at 0 (spec review N3)', () => {
  it('muted with the slider at 0, unmuting sets the slider to 80 and the gain to 0.64', async () => {
    const env = setup()
    await songStarted(env)
    const gain = theGain(env)
    fireEvent.click(muteButton())
    expect(muteButton().getAttribute('aria-pressed')).toBe('true')
    // Moving to 0 is not "above 0", so it stays muted.
    moveSlider(0)
    expect(muteButton().getAttribute('aria-pressed')).toBe('true')
    expect(gain.gain.value).toBeCloseTo(0, 6)
    fireEvent.click(muteButton())
    expect(muteButton().getAttribute('aria-pressed')).toBe('false')
    expect(slider().value).toBe('80')
    expect(slider().getAttribute('aria-valuetext')).toBe('80%')
    expect(gain.gain.value).toBeCloseTo(0.64, 6)
    expect(stored()).toEqual({ v: 80, muted: false })
  })
})

describe('i18n', () => {
  it('the keys exist in ko, en and ja with the declared texts', () => {
    expect(ko.volume.label).toBe('음량')
    expect(ko.volume.mute).toBe('음소거')
    expect(en.volume.label).toBe('Volume')
    expect(en.volume.mute).toBe('Mute')
    expect(ja.volume.label).toBe('音量')
    expect(ja.volume.mute).toBe('ミュート')
  })

  it('after switching to English the labels come from en.json', async () => {
    const env = setup()
    fireEvent.click(within(screen.getByRole('group', { name: 'Language' })).getByRole('button', { name: 'English' }))
    await songStarted(env, en)
    expect(slider(en)).toBeTruthy()
    expect(muteButton(en)).toBeTruthy()
    expect(screen.queryByRole('slider', { name: ko.volume.label })).toBeNull()
    expect(screen.queryByRole('button', { name: ko.volume.mute })).toBeNull()
  })
})
