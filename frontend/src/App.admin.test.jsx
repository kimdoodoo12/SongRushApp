// Cycle 083 (D-097): the owner's feedback admin screen. When the pathname is
// /admin or starts with /admin/, App renders AdminScreen instead of the game:
// no game socket opens (not even a reload rejoin), and there is no footer
// 문의하기 button. Requests go through the global fetch (globalThis.fetch),
// which these tests stub; App's fetchFn prop stays the audio fetch.
//
// The admin screen is Korean-only (not in the i18n files). Exact texts:
//   heading (h1)       문의 관리
//   tabs (role=tab)    새 문의 / 보낸 문의 / 숨긴 문의, each followed by its
//                      count once loaded: accessible name "새 문의 (2)"
//                      (whitespace before "(" is free). 새 문의 is selected
//                      first; the selected tab has aria-selected="true".
//   buttons            Issues로 보내기 / 숨기기 (new items), 다시 보이기 (hidden)
//   link (sent items)  #N, href = issueUrl, target _blank,
//                      rel "noopener noreferrer"
//   title input        an <input> labelled 제목, maxLength 80, value = title
//                      (new items only)
//   category texts     bug 버그, idea 건의, song 곡 요청, other 기타
//   github-disabled    GitHub 토큰이 설정되지 않았습니다
//   already-sent       이미 보낸 문의입니다
//   in-flight          처리 중인 요청이 있습니다
//   feedback-failed    실패했습니다. GitHub에 이미 만들어졌는지 확인한 뒤 다시 시도하세요
//   network failure    서버에 연결하지 못했습니다
//   list 404           관리자 기능이 꺼져 있습니다
//
// Request contract:
//   list   GET /admin/api/feedback?status=all, exactly once at mount; tabs
//          filter on the client. Header X-Requested-With: songrush-admin.
//   send   POST /admin/api/feedback/{id}/send, body JSON {"title": <the
//          title input's current value>}.
//   hide   POST /admin/api/feedback/{id}/hide
//   unhide POST /admin/api/feedback/{id}/unhide
//   Every POST sends X-Requested-With: songrush-admin and Content-Type
//   application/json. A 200 answer is the updated record; the item moves
//   tabs from it, with no second list request.
//
// Markup contract: the visible tab's items sit in the role=tabpanel, one
// <article> per item; an item's error text is shown inside its article.
// The body is plain text (never HTML).
// Timer rule (cycle 039): real timers and waitFor.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, act, cleanup, within } from '@testing-library/react'
import App from './App.jsx'
import { createSocketFactory, createAudioContextFactory, createFakeFetch } from './testing/fakes.js'

const T0 = 1_000_000
const LIST_URL = '/admin/api/feedback?status=all'
const ADMIN_HEADER = 'songrush-admin'

const TEXT = {
  heading: '문의 관리',
  githubDisabled: 'GitHub 토큰이 설정되지 않았습니다',
  alreadySent: '이미 보낸 문의입니다',
  inFlight: '처리 중인 요청이 있습니다',
  failed: '실패했습니다. GitHub에 이미 만들어졌는지 확인한 뒤 다시 시도하세요',
  network: '서버에 연결하지 못했습니다',
  off: '관리자 기능이 꺼져 있습니다',
}

let originalFetch

beforeEach(() => {
  window.history.pushState({}, '', '/admin')
  window.localStorage.clear()
  window.sessionStorage.clear()
  originalFetch = globalThis.fetch
})

afterEach(() => {
  cleanup()
  globalThis.fetch = originalFetch
  window.history.pushState({}, '', '/')
  vi.restoreAllMocks()
  window.localStorage.clear()
  window.sessionStorage.clear()
})

function jsonResponse(status, body) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

function record(id, overrides = {}) {
  return {
    id,
    createdAt: '2026-11-07T03:04:05Z',
    category: 'bug',
    title: `title ${id}`,
    body: `body ${id}`,
    lang: 'ko',
    page: 'start',
    status: 'new',
    issueNumber: null,
    issueUrl: null,
    updatedAt: '2026-11-07T03:04:05Z',
    ...overrides,
  }
}

// Stubs globalThis.fetch. The list GET answers `list` (a Response factory);
// a POST calls `action(url, init)`, which returns a Response, a promise of
// one, or throws.
function stubAdmin({ items = [], list, action } = {}) {
  const fetchMock = vi.fn(async (url, init = {}) => {
    const u = String(url)
    const method = (init.method ?? 'GET').toUpperCase()
    if (method === 'GET' && u.startsWith('/admin/api/feedback')) {
      return list ? list() : jsonResponse(200, { items })
    }
    if (method === 'POST' && action) return action(u, init)
    throw new Error(`unexpected fetch ${method} ${u}`)
  })
  globalThis.fetch = fetchMock
  return fetchMock
}

function setup(props = {}) {
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
      {...props}
    />,
  )
  return { sockets, audio, fetch }
}

function listCalls(fetchMock) {
  return fetchMock.mock.calls.filter(([, init]) => ((init?.method ?? 'GET').toUpperCase() === 'GET'))
}

function postCalls(fetchMock, suffix) {
  return fetchMock.mock.calls.filter(
    ([url, init]) => (init?.method ?? 'GET').toUpperCase() === 'POST' && String(url).endsWith(suffix),
  )
}

function tab(label) {
  return screen.getByRole('tab', { name: new RegExp(`^${label}`) })
}

async function expectCount(label, n) {
  await waitFor(() => expect(screen.getByRole('tab', { name: new RegExp(`^${label}\\s*\\(${n}\\)$`) })).toBeTruthy())
}

function panel() {
  return screen.getByRole('tabpanel')
}

// The item's article in the visible tab, found by its (unique) body text.
function item(body) {
  const node = within(panel()).getByText(body)
  const article = node.closest('article')
  expect(article, `article for ${body}`).not.toBeNull()
  return article
}

function button(article, name) {
  return within(article).getByRole('button', { name })
}

function expectAdminHeaders(init, { post }) {
  const headers = new Headers(init?.headers)
  expect(headers.get('X-Requested-With')).toBe(ADMIN_HEADER)
  if (post) expect(headers.get('Content-Type')).toMatch(/^application\/json/)
}

describe('routing', () => {
  for (const path of ['/admin', '/admin/']) {
    it(`${path} renders the admin screen without the game or the footer button`, async () => {
      window.history.pushState({}, '', path)
      const fetchMock = stubAdmin({ items: [] })
      const env = setup()
      expect(await screen.findByRole('heading', { level: 1, name: TEXT.heading })).toBeTruthy()
      await waitFor(() => expect(listCalls(fetchMock)).toHaveLength(1))
      expect(screen.queryByRole('button', { name: '문의하기' })).toBeNull()
      expect(screen.queryByLabelText('닉네임')).toBeNull()
      expect(screen.queryByRole('button', { name: '방 만들기' })).toBeNull()
      expect(env.sockets.factory).not.toHaveBeenCalled()
      // The audio fetchFn prop is not used for the admin API.
      expect(env.fetch.fetchFn).not.toHaveBeenCalled()
    })
  }

  it('a stored rejoin entry on a reload opens no socket at /admin', async () => {
    window.sessionStorage.setItem('songrush.rejoin', JSON.stringify({ token: 'T1', code: 'ABCDE' }))
    stubAdmin({ items: [] })
    const env = setup({ navigationType: 'reload' })
    expect(await screen.findByRole('heading', { level: 1, name: TEXT.heading })).toBeTruthy()
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 20))
    })
    expect(env.sockets.factory).not.toHaveBeenCalled()
  })

  it('/administrator is not the admin screen', () => {
    window.history.pushState({}, '', '/administrator')
    const fetchMock = stubAdmin({ items: [] })
    setup()
    expect(screen.queryByRole('heading', { level: 1, name: TEXT.heading })).toBeNull()
    expect(screen.getByRole('button', { name: '문의하기' })).toBeTruthy()
    expect(listCalls(fetchMock).filter(([url]) => String(url).startsWith('/admin/'))).toHaveLength(0)
  })
})

describe('the list', () => {
  const items = [
    record('aaaaaaaaaaa1', { category: 'song', title: '곡 추가', body: '귀멸 OP2', lang: 'en', page: 'lobby' }),
    record('aaaaaaaaaaa2', { category: 'idea', title: '건의 하나', body: '건의 내용' }),
    record('bbbbbbbbbbb1', {
      status: 'sent',
      title: '보낸 제목',
      body: '보낸 내용',
      issueNumber: 12,
      issueUrl: 'https://github.com/o/r/issues/12',
    }),
    record('ccccccccccc1', { status: 'hidden', category: 'other', title: '스팸', body: '스팸 내용' }),
  ]

  it('loads once with the admin header and shows the tab counts', async () => {
    const fetchMock = stubAdmin({ items })
    setup()
    await expectCount('새 문의', 2)
    await expectCount('보낸 문의', 1)
    await expectCount('숨긴 문의', 1)
    const calls = listCalls(fetchMock)
    expect(calls).toHaveLength(1)
    expect(String(calls[0][0])).toBe(LIST_URL)
    expectAdminHeaders(calls[0][1], { post: false })
    expect(tab('새 문의').getAttribute('aria-selected')).toBe('true')
    expect(tab('보낸 문의').getAttribute('aria-selected')).not.toBe('true')
  })

  it('new items show category, lang, page, an editable title and both buttons', async () => {
    stubAdmin({ items })
    setup()
    await expectCount('새 문의', 2)
    const a = item('귀멸 OP2')
    expect(within(a).getByText('곡 요청')).toBeTruthy()
    expect(a.textContent).toContain('en')
    expect(a.textContent).toContain('lobby')
    const title = within(a).getByLabelText('제목')
    expect(title.tagName).toBe('INPUT')
    expect(title.maxLength).toBe(80)
    expect(title.value).toBe('곡 추가')
    expect(button(a, 'Issues로 보내기')).toBeTruthy()
    expect(button(a, '숨기기')).toBeTruthy()
    expect(within(a).queryByRole('button', { name: '다시 보이기' })).toBeNull()
    expect(within(item('건의 내용')).getByText('건의')).toBeTruthy()
    // Other tabs' items are not in the new tab.
    expect(within(panel()).queryByText('보낸 내용')).toBeNull()
    expect(within(panel()).queryByText('스팸 내용')).toBeNull()
  })

  it('the sent tab shows the #N link opening in a new tab', async () => {
    stubAdmin({ items })
    setup()
    await expectCount('보낸 문의', 1)
    fireEvent.click(tab('보낸 문의'))
    await waitFor(() => expect(tab('보낸 문의').getAttribute('aria-selected')).toBe('true'))
    const a = item('보낸 내용')
    const link = within(a).getByRole('link', { name: '#12' })
    expect(link.getAttribute('href')).toBe('https://github.com/o/r/issues/12')
    expect(link.getAttribute('target')).toBe('_blank')
    expect(link.getAttribute('rel')).toBe('noopener noreferrer')
    expect(within(a).getByText('보낸 제목')).toBeTruthy()
    expect(within(a).queryByRole('button', { name: 'Issues로 보내기' })).toBeNull()
    expect(within(a).queryByRole('button', { name: '숨기기' })).toBeNull()
    expect(within(a).queryByRole('textbox')).toBeNull()
  })

  it('the hidden tab shows 다시 보이기 only', async () => {
    stubAdmin({ items })
    setup()
    await expectCount('숨긴 문의', 1)
    fireEvent.click(tab('숨긴 문의'))
    const a = await waitFor(() => item('스팸 내용'))
    expect(within(a).getByText('기타')).toBeTruthy()
    expect(button(a, '다시 보이기')).toBeTruthy()
    expect(within(a).queryByRole('button', { name: 'Issues로 보내기' })).toBeNull()
    expect(within(a).queryByRole('button', { name: '숨기기' })).toBeNull()
  })

  it('the body renders as literal text, never as HTML', async () => {
    stubAdmin({ items: [record('ddddddddddd1', { body: '<b>x</b>\n둘째 줄' })] })
    setup()
    await expectCount('새 문의', 1)
    const node = within(panel()).getByText((_, el) => el !== null && el.textContent === '<b>x</b>\n둘째 줄' && el.children.length === 0)
    expect(node.closest('article').querySelector('b')).toBeNull()
  })

  it('a 404 on the list shows the off text and no tabs', async () => {
    stubAdmin({ list: () => jsonResponse(404, { error: 'not-found' }) })
    setup()
    expect(await screen.findByText(TEXT.off)).toBeTruthy()
    expect(screen.queryByRole('tab')).toBeNull()
  })
})

describe('send', () => {
  const sentUrl = 'https://github.com/o/r/issues/34'

  it('posts the edited title with the headers; the item moves to 보낸 문의 with #N', async () => {
    const items = [record('aaaaaaaaaaa1', { title: '원래 제목', body: '본문 하나' }), record('aaaaaaaaaaa2', { body: '본문 둘' })]
    const fetchMock = stubAdmin({
      items,
      action: (url, init) => {
        const sentTitle = JSON.parse(init.body).title
        return jsonResponse(200, { ...items[0], title: sentTitle, status: 'sent', issueNumber: 34, issueUrl: sentUrl })
      },
    })
    setup()
    await expectCount('새 문의', 2)
    const a = item('본문 하나')
    fireEvent.change(within(a).getByLabelText('제목'), { target: { value: '고친 제목' } })
    fireEvent.click(button(a, 'Issues로 보내기'))

    await waitFor(() => expect(postCalls(fetchMock, '/admin/api/feedback/aaaaaaaaaaa1/send')).toHaveLength(1))
    const [url, init] = postCalls(fetchMock, '/send')[0]
    expect(String(url)).toBe('/admin/api/feedback/aaaaaaaaaaa1/send')
    expectAdminHeaders(init, { post: true })
    expect(JSON.parse(init.body)).toEqual({ title: '고친 제목' })

    await expectCount('새 문의', 1)
    await expectCount('보낸 문의', 1)
    expect(within(panel()).queryByText('본문 하나')).toBeNull()
    fireEvent.click(tab('보낸 문의'))
    const moved = await waitFor(() => item('본문 하나'))
    const link = within(moved).getByRole('link', { name: '#34' })
    expect(link.getAttribute('href')).toBe(sentUrl)
    expect(link.getAttribute('target')).toBe('_blank')
    expect(link.getAttribute('rel')).toBe('noopener noreferrer')
    expect(listCalls(fetchMock)).toHaveLength(1)
  })

  it("that item's buttons are disabled while the send runs; one click sends once", async () => {
    let release
    const pending = new Promise((resolve) => {
      release = resolve
    })
    const items = [record('aaaaaaaaaaa1', { body: '본문 하나' }), record('aaaaaaaaaaa2', { body: '본문 둘' })]
    const fetchMock = stubAdmin({ items, action: () => pending })
    setup()
    await expectCount('새 문의', 2)
    fireEvent.click(button(item('본문 하나'), 'Issues로 보내기'))
    await waitFor(() => expect(postCalls(fetchMock, '/send')).toHaveLength(1))
    await waitFor(() => expect(button(item('본문 하나'), 'Issues로 보내기').disabled).toBe(true))
    expect(button(item('본문 하나'), '숨기기').disabled).toBe(true)
    // The other item stays usable.
    expect(button(item('본문 둘'), 'Issues로 보내기').disabled).toBe(false)
    expect(button(item('본문 둘'), '숨기기').disabled).toBe(false)
    fireEvent.click(button(item('본문 하나'), 'Issues로 보내기'))
    fireEvent.click(button(item('본문 하나'), '숨기기'))
    expect(postCalls(fetchMock, '/send')).toHaveLength(1)
    expect(postCalls(fetchMock, '/hide')).toHaveLength(0)
    await act(async () => {
      release(jsonResponse(200, { ...items[0], status: 'sent', issueNumber: 5, issueUrl: 'https://github.com/o/r/issues/5' }))
    })
    await expectCount('보낸 문의', 1)
  })

  const errorCases = [
    ['503 github-disabled', () => jsonResponse(503, { error: 'github-disabled' }), TEXT.githubDisabled],
    ['502 feedback-failed', () => jsonResponse(502, { error: 'feedback-failed' }), TEXT.failed],
    ['409 already-sent', () => jsonResponse(409, { error: 'already-sent' }), TEXT.alreadySent],
    ['409 in-flight', () => jsonResponse(409, { error: 'in-flight' }), TEXT.inFlight],
    [
      'a network failure',
      () => {
        throw new TypeError('Failed to fetch')
      },
      TEXT.network,
    ],
  ]

  for (const [label, respond, text] of errorCases) {
    it(`${label} shows its text inside the item, which stays new and usable`, async () => {
      const items = [record('aaaaaaaaaaa1', { body: '본문 하나' }), record('aaaaaaaaaaa2', { body: '본문 둘' })]
      stubAdmin({ items, action: respond })
      setup()
      await expectCount('새 문의', 2)
      fireEvent.change(within(item('본문 하나')).getByLabelText('제목'), { target: { value: '고친 제목' } })
      fireEvent.click(button(item('본문 하나'), 'Issues로 보내기'))
      expect(await within(item('본문 하나')).findByText(text)).toBeTruthy()
      expect(within(item('본문 둘')).queryByText(text)).toBeNull()
      await expectCount('새 문의', 2)
      await expectCount('보낸 문의', 0)
      const a = item('본문 하나')
      expect(within(a).getByLabelText('제목').value).toBe('고친 제목')
      expect(button(a, 'Issues로 보내기').disabled).toBe(false)
      expect(button(a, '숨기기').disabled).toBe(false)
    })
  }
})

describe('hide and restore', () => {
  it('숨기기 moves the item to 숨긴 문의 and 다시 보이기 brings it back to 새 문의', async () => {
    const items = [record('aaaaaaaaaaa1', { body: '본문 하나' }), record('aaaaaaaaaaa2', { body: '본문 둘' })]
    const fetchMock = stubAdmin({
      items,
      action: (url) => {
        if (url.endsWith('/hide')) return jsonResponse(200, { ...items[0], status: 'hidden' })
        if (url.endsWith('/unhide')) return jsonResponse(200, { ...items[0], status: 'new' })
        throw new Error(`unexpected POST ${url}`)
      },
    })
    setup()
    await expectCount('새 문의', 2)
    await expectCount('숨긴 문의', 0)

    fireEvent.click(button(item('본문 하나'), '숨기기'))
    await waitFor(() => expect(postCalls(fetchMock, '/hide')).toHaveLength(1))
    const [hideUrl, hideInit] = postCalls(fetchMock, '/hide')[0]
    expect(String(hideUrl)).toBe('/admin/api/feedback/aaaaaaaaaaa1/hide')
    expectAdminHeaders(hideInit, { post: true })
    await expectCount('새 문의', 1)
    await expectCount('숨긴 문의', 1)
    expect(within(panel()).queryByText('본문 하나')).toBeNull()

    fireEvent.click(tab('숨긴 문의'))
    const hidden = await waitFor(() => item('본문 하나'))
    fireEvent.click(button(hidden, '다시 보이기'))
    await waitFor(() => expect(postCalls(fetchMock, '/unhide')).toHaveLength(1))
    const [unhideUrl, unhideInit] = postCalls(fetchMock, '/unhide')[0]
    expect(String(unhideUrl)).toBe('/admin/api/feedback/aaaaaaaaaaa1/unhide')
    expectAdminHeaders(unhideInit, { post: true })
    await expectCount('새 문의', 2)
    await expectCount('숨긴 문의', 0)

    fireEvent.click(tab('새 문의'))
    const back = await waitFor(() => item('본문 하나'))
    expect(button(back, 'Issues로 보내기')).toBeTruthy()
    expect(listCalls(fetchMock)).toHaveLength(1)
  })

  it('다시 보이기 is disabled while the unhide runs', async () => {
    let release
    const pending = new Promise((resolve) => {
      release = resolve
    })
    const items = [record('ccccccccccc1', { status: 'hidden', body: '숨긴 본문' })]
    const fetchMock = stubAdmin({ items, action: () => pending })
    setup()
    await expectCount('숨긴 문의', 1)
    fireEvent.click(tab('숨긴 문의'))
    const a = await waitFor(() => item('숨긴 본문'))
    fireEvent.click(button(a, '다시 보이기'))
    await waitFor(() => expect(button(item('숨긴 본문'), '다시 보이기').disabled).toBe(true))
    fireEvent.click(button(item('숨긴 본문'), '다시 보이기'))
    expect(postCalls(fetchMock, '/unhide')).toHaveLength(1)
    await act(async () => {
      release(jsonResponse(200, { ...items[0], status: 'new' }))
    })
    await expectCount('새 문의', 1)
    await expectCount('숨긴 문의', 0)
  })
})
