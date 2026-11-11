// Cycle 082 (D-096): in-site feedback. A footer button opens a modal dialog
// with a form; submitting POSTs JSON to /api/feedback, which the server turns
// into a GitHub issue. The player only sees "received" (or an error text).
//
// The request goes through the global fetch (globalThis.fetch), not App's
// fetchFn prop (that one stays the audio fetch). Tests stub globalThis.fetch.
//
// Expected texts come from the dictionaries through these keys (nested JSON,
// i18next's default '.' separator), so the implementer must use exactly them,
// in ko, en and ja:
//   feedback.title            문의하기 (footer button and dialog heading)
//   feedback.label.category   분류
//   feedback.label.title      제목
//   feedback.label.body       내용
//   feedback.category.bug     버그
//   feedback.category.idea    건의
//   feedback.category.song    곡 요청
//   feedback.category.other   기타
//   feedback.submit           보내기
//   feedback.close            닫기
//   feedback.thanks           문의가 접수되었어요. 고마워요!
//   feedback.preparing        문의하기를 준비 중이에요. 잠시 후 다시 시도해 주세요.
//   feedback.rateLimited      잠시 후 다시 보내 주세요.
//   feedback.failed           보내지 못했어요. 잠시 후 다시 시도해 주세요.
//   feedback.bad              제목과 내용을 확인해 주세요.
//   feedback.network          서버에 연결하지 못했어요.
// Markup contract: the dialog is role=dialog, aria-modal="true", named by its
// heading; the select, title input and body textarea are labelled by the
// label.* texts; the honeypot is input[name="website"] with tabIndex -1,
// autoComplete off, and aria-hidden="true" on it or an ancestor; the select's
// option values are bug, idea, song and other. The chat input's send button is
// also 보내기, so dialog queries are scoped with `within`.
// Timer rule (cycle 039): real timers and waitFor.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, act, cleanup, within } from '@testing-library/react'
import App from './App.jsx'
import ko from './i18n/ko.json'
import en from './i18n/en.json'
import { createSocketFactory, createAudioContextFactory, createFakeFetch } from './testing/fakes.js'
import { createRoomAs } from './testing/lobby.js'

const T0 = 1_000_000
const FEEDBACK_URL = '/api/feedback'

// The value of a dotted key.
function tr(dict, key) {
  let value = dict
  for (const part of key.split('.')) {
    value = value !== null && typeof value === 'object' ? value[part] : undefined
  }
  if (typeof value !== 'string') throw new Error(`missing i18n key ${key}`)
  return value
}

let originalFetch

beforeEach(() => {
  window.history.pushState({}, '', '/')
  window.localStorage.clear()
  originalFetch = globalThis.fetch
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
  globalThis.fetch = originalFetch
  window.history.pushState({}, '', '/')
  vi.restoreAllMocks()
  window.localStorage.clear()
})

function jsonResponse(status, body) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

// Stubs globalThis.fetch; `respond` gives the response (or throws) per call.
function stubFetch(respond) {
  const fetchMock = vi.fn(async () => respond())
  globalThis.fetch = fetchMock
  return fetchMock
}

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

function chooseLanguage(label) {
  const group = screen.getByRole('group', { name: 'Language' })
  fireEvent.click(within(group).getByRole('button', { name: label }))
}

function footerButton(dict = ko) {
  return screen.getByRole('button', { name: tr(dict, 'feedback.title') })
}

function openDialog(dict = ko) {
  const button = footerButton(dict)
  fireEvent.click(button)
  const dialog = screen.getByRole('dialog', { name: tr(dict, 'feedback.title') })
  return { button, dialog }
}

function fields(dialog, dict = ko) {
  const d = within(dialog)
  return {
    category: d.getByLabelText(tr(dict, 'feedback.label.category')),
    title: d.getByLabelText(tr(dict, 'feedback.label.title')),
    body: d.getByLabelText(tr(dict, 'feedback.label.body')),
    submit: d.getByRole('button', { name: tr(dict, 'feedback.submit') }),
  }
}

function fill(dialog, { category, title, body }, dict = ko) {
  const f = fields(dialog, dict)
  if (category !== undefined) fireEvent.change(f.category, { target: { value: category } })
  if (title !== undefined) fireEvent.change(f.title, { target: { value: title } })
  if (body !== undefined) fireEvent.change(f.body, { target: { value: body } })
  return fields(dialog, dict)
}

function feedbackCalls(fetchMock) {
  return fetchMock.mock.calls.filter(([url]) => String(url) === FEEDBACK_URL)
}

function sentBody(fetchMock) {
  const calls = feedbackCalls(fetchMock)
  expect(calls).toHaveLength(1)
  const [, init] = calls[0]
  expect(init.method).toBe('POST')
  expect(new Headers(init.headers).get('Content-Type')).toMatch(/application\/json/)
  return JSON.parse(init.body)
}

const player = (name, { host = false } = {}) => ({ name, score: 0, host, connected: true })

describe('footer button and dialog', () => {
  it('the footer 문의하기 button opens a modal dialog named 문의하기', () => {
    stubFetch(() => jsonResponse(201, { ok: true }))
    setup()
    const button = footerButton()
    expect(button.closest('footer'), 'the button sits in the footer').not.toBeNull()
    expect(screen.queryByRole('dialog')).toBeNull()
    fireEvent.click(button)
    const dialog = screen.getByRole('dialog', { name: '문의하기' })
    expect(dialog.getAttribute('aria-modal')).toBe('true')
    expect(within(dialog).getByRole('heading', { name: '문의하기' })).toBeTruthy()
  })

  it('the form has the category select, title, body, honeypot, submit and close', () => {
    stubFetch(() => jsonResponse(201, { ok: true }))
    setup()
    const { dialog } = openDialog()
    const f = fields(dialog)

    expect(f.category.tagName).toBe('SELECT')
    const options = Array.from(f.category.querySelectorAll('option'))
    expect(options.map((o) => o.textContent.trim())).toEqual(['버그', '건의', '곡 요청', '기타'])
    expect(options.map((o) => o.value)).toEqual(['bug', 'idea', 'song', 'other'])

    expect(f.title.tagName).toBe('INPUT')
    expect(f.title.maxLength).toBe(80)
    expect(f.body.tagName).toBe('TEXTAREA')
    expect(f.body.maxLength).toBe(2000)

    const honeypot = dialog.querySelector('input[name="website"]')
    expect(honeypot, 'honeypot input named website').not.toBeNull()
    expect(honeypot.tabIndex).toBe(-1)
    expect(honeypot.getAttribute('autocomplete')).toBe('off')
    expect(honeypot.closest('[aria-hidden="true"]'), 'honeypot hidden from assistive tech').not.toBeNull()

    expect(within(dialog).getByRole('button', { name: '닫기' })).toBeTruthy()
  })
})

describe('submit enabled state', () => {
  it('submit is disabled while the title or body is empty after trimming', () => {
    stubFetch(() => jsonResponse(201, { ok: true }))
    setup()
    const { dialog } = openDialog()
    expect(fields(dialog).submit.disabled).toBe(true)

    expect(fill(dialog, { title: '소리가 안 나요' }).submit.disabled).toBe(true)
    expect(fill(dialog, { body: '   \n\t ' }).submit.disabled).toBe(true)
    expect(fill(dialog, { body: '2번째 곡부터 무음' }).submit.disabled).toBe(false)
    expect(fill(dialog, { title: '    ' }).submit.disabled).toBe(true)
    expect(fill(dialog, { title: '소리가 안 나요' }).submit.disabled).toBe(false)
  })

  it('submit is disabled while the request is in flight, so one click sends once', async () => {
    let release
    const pending = new Promise((resolve) => {
      release = resolve
    })
    const fetchMock = vi.fn(() => pending)
    globalThis.fetch = fetchMock
    setup()
    const { dialog } = openDialog()
    const f = fill(dialog, { title: '제목', body: '내용' })
    fireEvent.click(f.submit)
    await waitFor(() => expect(feedbackCalls(fetchMock)).toHaveLength(1))
    await waitFor(() => expect(fields(dialog).submit.disabled).toBe(true))
    fireEvent.click(fields(dialog).submit)
    expect(feedbackCalls(fetchMock)).toHaveLength(1)
    await act(async () => {
      release(jsonResponse(201, { ok: true }))
    })
    expect(await within(dialog).findByText('문의가 접수되었어요. 고마워요!')).toBeTruthy()
  })
})

describe('the request', () => {
  it('posts the exact JSON with lang ko and page start', async () => {
    const fetchMock = stubFetch(() => jsonResponse(201, { ok: true }))
    const env = setup()
    const { dialog } = openDialog()
    const f = fill(dialog, { category: 'song', title: '추가해 주세요', body: '귀멸의 칼날 OP2\n부탁해요' })
    fireEvent.click(f.submit)
    await waitFor(() => expect(feedbackCalls(fetchMock)).toHaveLength(1))
    expect(sentBody(fetchMock)).toEqual({
      category: 'song',
      title: '추가해 주세요',
      body: '귀멸의 칼날 OP2\n부탁해요',
      lang: 'ko',
      page: 'start',
      website: '',
    })
    // The audio fetchFn prop is not used for feedback.
    expect(env.fetch.urls).not.toContain(FEEDBACK_URL)
  })

  it('posts page lobby from the lobby', async () => {
    const fetchMock = stubFetch(() => jsonResponse(201, { ok: true }))
    const env = setup()
    createRoomAs('Kai')
    await waitFor(() => expect(env.sockets.sockets.length).toBe(1))
    const socket = env.sockets.last
    act(() => socket.open())
    await waitFor(() => expect(socket.sentOfType('create')).toHaveLength(1))
    act(() => socket.receive({ type: 'joined', code: 'QWERT', name: 'Kai' }))
    act(() => socket.receive({ type: 'roster', phase: 'lobby', players: [player('Kai', { host: true })] }))

    const { dialog } = openDialog()
    const f = fill(dialog, { category: 'bug', title: '버그', body: '설명' })
    fireEvent.click(f.submit)
    await waitFor(() => expect(feedbackCalls(fetchMock)).toHaveLength(1))
    expect(sentBody(fetchMock)).toEqual({
      category: 'bug',
      title: '버그',
      body: '설명',
      lang: 'ko',
      page: 'lobby',
      website: '',
    })
  })
})

describe('responses', () => {
  it('201 replaces the form with the thanks text and a close button', async () => {
    stubFetch(() => jsonResponse(201, { ok: true }))
    setup()
    const { dialog } = openDialog()
    fireEvent.click(fill(dialog, { title: '제목', body: '내용' }).submit)
    expect(await within(dialog).findByText('문의가 접수되었어요. 고마워요!')).toBeTruthy()
    expect(within(dialog).queryByLabelText('제목')).toBeNull()
    expect(within(dialog).queryByLabelText('내용')).toBeNull()
    expect(within(dialog).queryByRole('button', { name: '보내기' })).toBeNull()
    expect(within(dialog).getByRole('button', { name: '닫기' })).toBeTruthy()
  })

  it('503 feedback-disabled shows the preparing text and no form', async () => {
    stubFetch(() => jsonResponse(503, { error: 'feedback-disabled' }))
    setup()
    const { dialog } = openDialog()
    fireEvent.click(fill(dialog, { title: '제목', body: '내용' }).submit)
    expect(await within(dialog).findByText('문의하기를 준비 중이에요. 잠시 후 다시 시도해 주세요.')).toBeTruthy()
    expect(within(dialog).queryByLabelText('제목')).toBeNull()
    expect(within(dialog).queryByLabelText('내용')).toBeNull()
    expect(within(dialog).queryByRole('button', { name: '보내기' })).toBeNull()
  })

  it('429 rate-limited shows the text and keeps the inputs', async () => {
    stubFetch(() => jsonResponse(429, { error: 'rate-limited' }))
    setup()
    const { dialog } = openDialog()
    fireEvent.click(fill(dialog, { category: 'idea', title: '건의 제목', body: '건의 내용' }).submit)
    expect(await within(dialog).findByText('잠시 후 다시 보내 주세요.')).toBeTruthy()
    const f = fields(dialog)
    expect(f.category.value).toBe('idea')
    expect(f.title.value).toBe('건의 제목')
    expect(f.body.value).toBe('건의 내용')
    expect(f.submit.disabled).toBe(false)
  })

  it('400 bad-feedback shows the check text and keeps the inputs', async () => {
    stubFetch(() => jsonResponse(400, { error: 'bad-feedback' }))
    setup()
    const { dialog } = openDialog()
    fireEvent.click(fill(dialog, { title: '제목', body: '내용' }).submit)
    expect(await within(dialog).findByText('제목과 내용을 확인해 주세요.')).toBeTruthy()
    expect(fields(dialog).title.value).toBe('제목')
  })

  it('502 feedback-failed shows the failed text and keeps the inputs', async () => {
    stubFetch(() => jsonResponse(502, { error: 'feedback-failed' }))
    setup()
    const { dialog } = openDialog()
    fireEvent.click(fill(dialog, { title: '제목', body: '내용' }).submit)
    expect(await within(dialog).findByText('보내지 못했어요. 잠시 후 다시 시도해 주세요.')).toBeTruthy()
    expect(fields(dialog).body.value).toBe('내용')
  })

  it('a network failure shows the network text and keeps the inputs', async () => {
    stubFetch(() => {
      throw new TypeError('Failed to fetch')
    })
    setup()
    const { dialog } = openDialog()
    fireEvent.click(fill(dialog, { title: '제목', body: '내용' }).submit)
    expect(await within(dialog).findByText('서버에 연결하지 못했어요.')).toBeTruthy()
    expect(fields(dialog).title.value).toBe('제목')
    expect(fields(dialog).body.value).toBe('내용')
  })
})

describe('closing', () => {
  it('Escape closes the dialog and focus returns to the footer button', async () => {
    stubFetch(() => jsonResponse(201, { ok: true }))
    setup()
    const { button, dialog } = openDialog()
    fireEvent.keyDown(dialog, { key: 'Escape', code: 'Escape' })
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(document.activeElement).toBe(footerButton())
    expect(footerButton()).toBe(button)
  })

  it('the close button closes the dialog and focus returns to the footer button', async () => {
    stubFetch(() => jsonResponse(201, { ok: true }))
    setup()
    const { dialog } = openDialog()
    fireEvent.click(within(dialog).getByRole('button', { name: '닫기' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(document.activeElement).toBe(footerButton())
  })
})

describe('English', () => {
  it('the texts come from en.json and lang en is sent', async () => {
    const fetchMock = stubFetch(() => jsonResponse(429, { error: 'rate-limited' }))
    setup()
    chooseLanguage('English')
    for (const key of ['feedback.title', 'feedback.label.title', 'feedback.label.body', 'feedback.submit', 'feedback.rateLimited']) {
      expect(tr(en, key), key).not.toBe(tr(ko, key))
    }
    expect(screen.queryByRole('button', { name: '문의하기' })).toBeNull()

    const { dialog } = openDialog(en)
    const f = fields(dialog, en)
    const options = Array.from(f.category.querySelectorAll('option')).map((o) => o.textContent.trim())
    expect(options).toEqual(['bug', 'idea', 'song', 'other'].map((id) => tr(en, `feedback.category.${id}`)))
    expect(within(dialog).getByRole('button', { name: tr(en, 'feedback.close') })).toBeTruthy()

    fireEvent.click(fill(dialog, { category: 'other', title: 'Hello', body: 'Nice game' }, en).submit)
    await waitFor(() => expect(feedbackCalls(fetchMock)).toHaveLength(1))
    expect(sentBody(fetchMock)).toEqual({
      category: 'other',
      title: 'Hello',
      body: 'Nice game',
      lang: 'en',
      page: 'start',
      website: '',
    })
    expect(await within(dialog).findByText(tr(en, 'feedback.rateLimited'))).toBeTruthy()
  })
})
