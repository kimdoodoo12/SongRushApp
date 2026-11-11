// Cycle 119 (D-127): the nickname is asked when a room is made or joined.
// The start screen shows 방 만들기 and 방 코드 with 참가; pressing either
// opens the nickname dialog (docs/cycles/119-nickname-dialog.md, Behaviour,
// the spec review resolutions and Acceptance 1).
//
// Contract with the implementer:
// - The start screen has no nickname input. 참가 is enabled only with a
//   full 5-character code. canPlayOpus false disables 방 만들기 and 참가
//   and shows start.noOpus on the start card (section.opts).
// - The dialog is a div role="dialog" aria-modal="true", aria-labelledby
//   its title ("닉네임" with "방 만들기", or "<code> 방 참가"). It holds one
//   text input (maxLength 16, focused on open), 확인 (.btn.primary) and 취소
//   (.btn). Tab cycles inside it; Escape and 취소 close it, send nothing and
//   give focus back to the button that opened it.
// - 확인 is disabled while the trimmed name is blank; it (or Enter in the
//   input) sends today's create{nickname} or join{code, nickname} with the
//   trimmed name, with the audio unlock in the same click.
// - While that attempt connects, the start screen stays under the open
//   dialog and 확인 is disabled (busy); joined closes the dialog. An error
//   answer (bad-nickname, room-not-found, ...) or a socket close before
//   joined keeps the dialog open with today's text as p.warn.
// - The confirmed name is stored in localStorage['songrush.nickname'] and
//   prefilled next time; a failing localStorage changes nothing else.
// - /r/<code> opens the join dialog at once, once per page load: a later
//   return to the start screen (leave, room-closed) does not reopen it.
// - Every dialog text exists in ko, en and ja.
// Timer rule (cycle 039): real timers and waitFor.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, act, cleanup, within } from '@testing-library/react'
import App from './App.jsx'
import ko from './i18n/ko.json'
import { createSocketFactory, createAudioContextFactory, createFakeFetch } from './testing/fakes.js'
import { confirmButton, confirmNickname, lobbyCode, nicknameDialog, nicknameInput } from './testing/lobby.js'

const T0 = 1_000_000
const NICK_KEY = 'songrush.nickname'
const HANGUL = /[ㄱ-ㅣ가-힣]/
const FOCUSABLE = 'button:not(:disabled), input:not(:disabled):not([tabindex="-1"]), select:not(:disabled), textarea:not(:disabled)'

beforeEach(() => {
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
  window.history.pushState({}, '', '/')
  vi.restoreAllMocks()
  window.localStorage.clear()
  window.sessionStorage.clear()
})

function setup({ canPlayOpus = true } = {}) {
  const sockets = createSocketFactory()
  const audio = createAudioContextFactory()
  const fetch = createFakeFetch()
  render(
    <App
      canPlayOpus={canPlayOpus}
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

// Waits for socket number `count`, opens it and waits for its first message.
async function openSocket(env, count = 1) {
  await waitFor(() => expect(env.sockets.sockets.length).toBe(count))
  const socket = env.sockets.last
  act(() => socket.open())
  await waitFor(() => expect(socket.sent.length).toBeGreaterThan(0))
  return socket
}

const player = (name, { host = false } = {}) => ({ name, score: 0, host, connected: true })
const lobbyRoster = (players) => ({ type: 'roster', phase: 'lobby', players })

function createButton() {
  return screen.getByRole('button', { name: ko.start.createRoom })
}

function joinButton() {
  return screen.getByRole('button', { name: ko.start.join })
}

function codeInput() {
  return screen.getByLabelText(ko.start.roomCode)
}

function setCode(code) {
  fireEvent.change(codeInput(), { target: { value: code } })
}

function cancelButton() {
  return within(nicknameDialog()).getByRole('button', { name: '취소' })
}

function typeName(name) {
  fireEvent.change(nicknameInput(), { target: { value: name } })
}

// The dialog's title: the element its aria-labelledby names, whitespace
// collapsed.
function dialogTitle() {
  const dialog = nicknameDialog()
  const ids = (dialog.getAttribute('aria-labelledby') ?? '').split(/\s+/).filter((id) => id !== '')
  expect(ids.length, 'aria-labelledby').toBeGreaterThan(0)
  return ids
    .map((id) => document.getElementById(id)?.textContent ?? '')
    .join(' ')
    .replace(/\s+/g, ' ')
    .trim()
}

function expectWarnInDialog(text) {
  const warn = within(nicknameDialog()).getByText(text)
  expect(warn.tagName).toBe('P')
  expect(warn.classList.contains('warn')).toBe(true)
}

function pressKey(target, key, options = {}) {
  return fireEvent.keyDown(target, { key, code: key, ...options })
}

describe('start screen', () => {
  it('has 방 만들기, 방 코드 and 참가 and no nickname input; no dialog is open', () => {
    setup()
    expect(screen.queryByLabelText(ko.start.nickname)).toBeNull()
    expect(screen.queryByRole('textbox', { name: ko.start.nickname })).toBeNull()
    expect(createButton().classList.contains('go')).toBe(true)
    expect(codeInput().tagName).toBe('INPUT')
    expect(joinButton()).toBeTruthy()
    expect(screen.queryByRole('dialog')).toBeNull()
  })

  it('참가 is disabled with a short code and enabled with a full one', () => {
    setup()
    expect(joinButton().disabled).toBe(true)
    setCode('ABC')
    expect(joinButton().disabled).toBe(true)
    setCode('ABCD')
    expect(joinButton().disabled).toBe(true)
    setCode('ABCDE')
    expect(joinButton().disabled).toBe(false)
  })

  it('canPlayOpus false disables 방 만들기 and 참가 and shows start.noOpus on the start card', () => {
    const env = setup({ canPlayOpus: false })
    setCode('ABCDE')
    expect(createButton().disabled).toBe(true)
    expect(joinButton().disabled).toBe(true)
    const notice = screen.getByText(ko.start.noOpus)
    expect(notice.closest('section.opts')).not.toBeNull()
    fireEvent.click(createButton())
    fireEvent.click(joinButton())
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(env.sockets.factory).not.toHaveBeenCalled()
  })
})

describe('the create dialog', () => {
  it('방 만들기 opens a modal dialog titled 닉네임 / 방 만들기 with the nickname input focused, 확인 and 취소', () => {
    const env = setup()
    fireEvent.click(createButton())
    const dialog = nicknameDialog()
    expect(dialog.getAttribute('aria-modal')).toBe('true')
    const title = dialogTitle()
    expect(title).toContain('닉네임')
    expect(title).toContain('방 만들기')
    const input = nicknameInput()
    expect(input.tagName).toBe('INPUT')
    expect(input.maxLength).toBe(16)
    expect(document.activeElement).toBe(input)
    expect(confirmButton().textContent.trim()).toBe('확인')
    expect(cancelButton().classList.contains('btn')).toBe(true)
    expect(env.sockets.factory).not.toHaveBeenCalled()
    expect(env.audio.factory).not.toHaveBeenCalled()
  })

  it('확인 sends create with the trimmed name and unlocks audio in the click', async () => {
    const env = setup()
    fireEvent.click(createButton())
    typeName('  하루  ')
    fireEvent.click(confirmButton())
    // Synchronously inside the 확인 click (audio unlock).
    expect(env.audio.factory).toHaveBeenCalledTimes(1)
    expect(env.audio.last.resume).toHaveBeenCalledTimes(1)
    const socket = await openSocket(env)
    expect(socket.sent).toEqual([{ type: 'create', nickname: '하루' }])
    expect(env.sockets.sockets).toHaveLength(1)
  })

  it('Enter in the nickname input submits', async () => {
    const env = setup()
    fireEvent.click(createButton())
    typeName('하루')
    const input = nicknameInput()
    const notPrevented = pressKey(input, 'Enter', { keyCode: 13, charCode: 13 })
    // A browser submits the enclosing form on Enter unless keydown was
    // cancelled; jsdom does not, so the test does it the same way.
    if (notPrevented && input.form !== null) fireEvent.submit(input.form)
    const socket = await openSocket(env)
    expect(socket.sent).toEqual([{ type: 'create', nickname: '하루' }])
    expect(env.sockets.sockets).toHaveLength(1)
  })

  it('a blank name keeps 확인 disabled', () => {
    const env = setup()
    fireEvent.click(createButton())
    expect(nicknameInput().value).toBe('')
    expect(confirmButton().disabled).toBe(true)
    typeName('   ')
    expect(confirmButton().disabled).toBe(true)
    fireEvent.click(confirmButton())
    expect(env.sockets.factory).not.toHaveBeenCalled()
    typeName('하루')
    expect(confirmButton().disabled).toBe(false)
  })

  it('Escape closes the dialog, sends nothing and gives focus back to 방 만들기', () => {
    const env = setup()
    fireEvent.click(createButton())
    typeName('하루')
    pressKey(nicknameInput(), 'Escape')
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(env.sockets.factory).not.toHaveBeenCalled()
    expect(env.audio.factory).not.toHaveBeenCalled()
    expect(document.activeElement).toBe(createButton())
  })

  it('취소 closes the dialog, sends nothing and gives focus back to 방 만들기', () => {
    const env = setup()
    fireEvent.click(createButton())
    typeName('하루')
    fireEvent.click(cancelButton())
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(env.sockets.factory).not.toHaveBeenCalled()
    expect(env.audio.factory).not.toHaveBeenCalled()
    expect(document.activeElement).toBe(createButton())
  })

  // Cycle 119 review (critic Medium): a drag that starts in the card (for
  // example selecting the name) and is released on the backdrop must not
  // cancel. Only a mousedown and a click both on the backdrop itself cancel.
  it('a mousedown in the card and a click on the backdrop do not cancel; the name stays', () => {
    const env = setup()
    fireEvent.click(createButton())
    typeName('하루')
    const dialog = nicknameDialog()
    const backdrop = dialog.parentElement
    expect(backdrop).not.toBeNull()
    fireEvent.mouseDown(nicknameInput())
    fireEvent.mouseUp(backdrop)
    fireEvent.click(backdrop)
    expect(screen.queryByRole('dialog')).not.toBeNull()
    expect(nicknameInput().value).toBe('하루')
    fireEvent.mouseDown(dialog)
    fireEvent.mouseUp(backdrop)
    fireEvent.click(backdrop)
    expect(screen.queryByRole('dialog')).not.toBeNull()
    expect(nicknameInput().value).toBe('하루')
    expect(env.sockets.factory).not.toHaveBeenCalled()
  })

  it('a mousedown and a click on the backdrop cancel and give focus back to 방 만들기', () => {
    const env = setup()
    fireEvent.click(createButton())
    typeName('하루')
    const backdrop = nicknameDialog().parentElement
    fireEvent.mouseDown(backdrop)
    fireEvent.mouseUp(backdrop)
    fireEvent.click(backdrop)
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(env.sockets.factory).not.toHaveBeenCalled()
    expect(document.activeElement).toBe(createButton())
  })

  it('Tab and Shift+Tab stay inside the dialog', () => {
    setup()
    fireEvent.click(createButton())
    typeName('하루')
    const dialog = nicknameDialog()
    const focusable = Array.from(dialog.querySelectorAll(FOCUSABLE))
    expect(focusable.length).toBeGreaterThanOrEqual(3)
    expect(focusable).toContain(nicknameInput())
    expect(focusable).toContain(confirmButton())
    expect(focusable).toContain(cancelButton())
    const first = focusable[0]
    const last = focusable[focusable.length - 1]
    last.focus()
    pressKey(last, 'Tab')
    expect(document.activeElement).toBe(first)
    pressKey(first, 'Tab', { shiftKey: true })
    expect(document.activeElement).toBe(last)
  })

  it('while connecting the start screen stays under the dialog with 확인 disabled; joined closes it and shows the lobby', async () => {
    const env = setup()
    fireEvent.click(createButton())
    typeName('하루')
    fireEvent.click(confirmButton())
    await waitFor(() => expect(env.sockets.sockets.length).toBe(1))
    expect(nicknameDialog()).toBeTruthy()
    expect(confirmButton().disabled).toBe(true)
    expect(createButton()).toBeTruthy()
    expect(codeInput()).toBeTruthy()
    expect(screen.queryByText(ko.stage.connecting)).toBeNull()
    const socket = env.sockets.last
    act(() => socket.open())
    await waitFor(() => expect(socket.sentOfType('create')).toHaveLength(1))
    expect(nicknameDialog()).toBeTruthy()
    expect(confirmButton().disabled).toBe(true)
    deliver(socket, { type: 'joined', code: 'QWERT', name: '하루' })
    deliver(socket, lobbyRoster([player('하루', { host: true })]))
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(lobbyCode()).toBe('QWERT')
  })

  it('a bad-nickname answer keeps the dialog open with its text as p.warn; the name stays and a retry sends again', async () => {
    const env = setup()
    fireEvent.click(createButton())
    typeName('하루')
    fireEvent.click(confirmButton())
    const socket = await openSocket(env)
    deliver(socket, { type: 'error', code: 'bad-nickname' })
    expectWarnInDialog(ko.error['bad-nickname'])
    expect(socket.closed).toBe(true)
    expect(nicknameInput().value).toBe('하루')
    expect(confirmButton().disabled).toBe(false)
    expect(createButton()).toBeTruthy()
    act(() => socket.serverClose())
    expect(screen.queryByText(ko.game.disconnected)).toBeNull()
    confirmNickname('하루2')
    const retry = await openSocket(env, 2)
    expect(retry.sent).toEqual([{ type: 'create', nickname: '하루2' }])
  })

  it('a socket close before joined keeps the dialog open with the connection-failure text as p.warn', async () => {
    const env = setup()
    fireEvent.click(createButton())
    typeName('하루')
    fireEvent.click(confirmButton())
    const socket = await openSocket(env)
    act(() => socket.serverClose())
    expectWarnInDialog(ko.game.disconnected)
    expect(screen.queryByRole('button', { name: ko.game.reset })).toBeNull()
    expect(createButton()).toBeTruthy()
  })
})

describe('the join dialog', () => {
  it('참가 with a full code opens the dialog titled with the code; 확인 sends join with the code', async () => {
    const env = setup()
    setCode('abcde')
    fireEvent.click(joinButton())
    const title = dialogTitle()
    expect(title).toContain('ABCDE')
    expect(title).toContain('방 참가')
    expect(document.activeElement).toBe(nicknameInput())
    expect(env.sockets.factory).not.toHaveBeenCalled()
    confirmNickname(' Hana ')
    expect(env.audio.factory).toHaveBeenCalledTimes(1)
    const socket = await openSocket(env)
    expect(socket.sent).toEqual([{ type: 'join', code: 'ABCDE', nickname: 'Hana' }])
  })

  it('Escape gives focus back to 참가 and sends nothing', () => {
    const env = setup()
    setCode('ABCDE')
    fireEvent.click(joinButton())
    pressKey(nicknameInput(), 'Escape')
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(document.activeElement).toBe(joinButton())
    expect(env.sockets.factory).not.toHaveBeenCalled()
  })

  it('room-not-found keeps the dialog open with its text as p.warn', async () => {
    const env = setup()
    setCode('ABCDE')
    fireEvent.click(joinButton())
    confirmNickname('Hana')
    const socket = await openSocket(env)
    deliver(socket, { type: 'error', code: 'room-not-found' })
    expectWarnInDialog(ko.error['room-not-found'])
    expect(socket.closed).toBe(true)
    fireEvent.click(cancelButton())
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(joinButton()).toBeTruthy()
  })
})

describe('the remembered nickname', () => {
  it('a stored name is prefilled', () => {
    window.localStorage.setItem(NICK_KEY, '하루')
    setup()
    fireEvent.click(createButton())
    expect(nicknameInput().value).toBe('하루')
    expect(confirmButton().disabled).toBe(false)
  })

  it('the confirmed, trimmed name is stored and prefilled after leaving', async () => {
    const env = setup()
    fireEvent.click(createButton())
    confirmNickname('  하루  ')
    const socket = await openSocket(env)
    deliver(socket, { type: 'joined', code: 'QWERT', name: '하루' })
    deliver(socket, lobbyRoster([player('하루', { host: true })]))
    expect(window.localStorage.getItem(NICK_KEY)).toBe('하루')
    fireEvent.click(screen.getByRole('button', { name: ko.lobby.leave }))
    fireEvent.click(createButton())
    expect(nicknameInput().value).toBe('하루')
  })

  it('a localStorage that throws leaves the dialog empty and still sends create', async () => {
    const realGet = Storage.prototype.getItem
    const realSet = Storage.prototype.setItem
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(function (key) {
      if (key === NICK_KEY) throw new Error('blocked')
      return realGet.call(this, key)
    })
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(function (key, value) {
      if (key === NICK_KEY) throw new Error('blocked')
      return realSet.call(this, key, value)
    })
    vi.spyOn(console, 'warn').mockImplementation(() => {})
    const env = setup()
    fireEvent.click(createButton())
    expect(nicknameInput().value).toBe('')
    confirmNickname('하루')
    const socket = await openSocket(env)
    expect(socket.sent).toEqual([{ type: 'create', nickname: '하루' }])
  })
})

describe('the invite link', () => {
  it('/r/abcde opens the join dialog at once with the code; 확인 sends join', async () => {
    window.history.pushState({}, '', '/r/abcde')
    const env = setup()
    expect(codeInput().value).toBe('ABCDE')
    const title = dialogTitle()
    expect(title).toContain('ABCDE')
    expect(title).toContain('방 참가')
    expect(document.activeElement).toBe(nicknameInput())
    expect(env.sockets.factory).not.toHaveBeenCalled()
    confirmNickname('Hana')
    const socket = await openSocket(env)
    expect(socket.sent).toEqual([{ type: 'join', code: 'ABCDE', nickname: 'Hana' }])
  })

  it('opens once per page load: 나가기 back to the start screen does not reopen it', async () => {
    window.history.pushState({}, '', '/r/ABCDE')
    const env = setup()
    confirmNickname('Hana')
    const socket = await openSocket(env)
    deliver(socket, { type: 'joined', code: 'ABCDE', name: 'Hana' })
    deliver(socket, lobbyRoster([player('Mina', { host: true }), player('Hana')]))
    expect(screen.queryByRole('dialog')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: ko.lobby.leave }))
    expect(createButton()).toBeTruthy()
    expect(screen.queryByRole('dialog')).toBeNull()
  })

  it('opens once per page load: room-closed back to the start screen does not reopen it', async () => {
    window.history.pushState({}, '', '/r/ABCDE')
    const env = setup()
    confirmNickname('Hana')
    const socket = await openSocket(env)
    deliver(socket, { type: 'joined', code: 'ABCDE', name: 'Hana' })
    deliver(socket, lobbyRoster([player('Mina', { host: true }), player('Hana')]))
    deliver(socket, { type: 'error', code: 'room-closed' })
    expect(screen.getByText(ko.error['room-closed'])).toBeTruthy()
    expect(createButton()).toBeTruthy()
    expect(screen.queryByRole('dialog')).toBeNull()
  })

  it('a cancelled invite dialog stays closed', () => {
    window.history.pushState({}, '', '/r/ABCDE')
    const env = setup()
    fireEvent.click(cancelButton())
    expect(screen.queryByRole('dialog')).toBeNull()
    fireEvent.click(within(screen.getByRole('group', { name: 'Language' })).getByRole('button', { name: 'English' }))
    fireEvent.click(within(screen.getByRole('group', { name: 'Language' })).getByRole('button', { name: '한국어' }))
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(env.sockets.factory).not.toHaveBeenCalled()
  })

  // Cycle 119 review (critic Nit): no button opened the invite dialog, so
  // cancelling it puts focus on 참가.
  it('취소 on the invite dialog gives focus to 참가', () => {
    window.history.pushState({}, '', '/r/ABCDE')
    setup()
    fireEvent.click(cancelButton())
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(document.activeElement).toBe(joinButton())
  })

  it('Escape on the invite dialog gives focus to 참가', () => {
    window.history.pushState({}, '', '/r/ABCDE')
    setup()
    pressKey(nicknameInput(), 'Escape')
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(document.activeElement).toBe(joinButton())
  })
})

describe('languages', () => {
  it.each(['English', '日本語'])('in %s the dialog has no Korean text', (language) => {
    setup()
    fireEvent.click(within(screen.getByRole('group', { name: 'Language' })).getByRole('button', { name: language }))
    // 방 만들기 by its class: its text is in `language` now.
    fireEvent.click(document.querySelector('.btn.primary.go'))
    const dialog = nicknameDialog()
    expect(dialog.textContent.trim()).not.toBe('')
    expect(dialog.textContent).not.toMatch(HANGUL)
    expect(confirmButton().textContent.trim()).not.toBe('')
  })
})
