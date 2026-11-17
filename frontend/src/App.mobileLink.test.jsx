// Cycle 154 (D-140): the shared footer has a 모바일 버전 link (app.mobileVersion,
// ko/en/ja) to the matching /m/ path: /m/ on the start screen, /m/r/<code>
// for a /r/<code> link and in a room. A click stores localStorage
// songrush.ui = "mobile". Timer rule (cycle 039): real timers and waitFor.
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, act, cleanup, within } from '@testing-library/react'
import App from './App.jsx'
import ko from './i18n/ko.json'
import en from './i18n/en.json'
import ja from './i18n/ja.json'
import { createSocketFactory, createAudioContextFactory, createFakeFetch } from './testing/fakes.js'
import { createRoomAs } from './testing/lobby.js'

const T0 = 1_000_000

const player = (name, { host = false } = {}) => ({ name, score: 0, host, connected: true })

beforeEach(() => {
  window.history.pushState({}, '', '/')
  window.localStorage.clear()
  window.sessionStorage.clear()
})

afterEach(() => {
  // Back to Korean, so the shared i18n instance carries no language over.
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

function setup() {
  const sockets = createSocketFactory()
  render(
    <App
      canPlayOpus={true}
      socketFactory={sockets.factory}
      audioContextFactory={createAudioContextFactory().factory}
      fetchFn={createFakeFetch().fetchFn}
      now={() => T0}
    />,
  )
  return { sockets }
}

function mobileLink(dict = ko) {
  return screen.getByRole('link', { name: dict.app.mobileVersion })
}

// jsdom does not navigate; the default action is cancelled so the test sees
// only what the click stored.
function clickWithoutNavigating(link) {
  link.addEventListener('click', (e) => e.preventDefault(), { once: true })
  fireEvent.click(link)
}

describe('footer 모바일 버전 link', () => {
  it('on the start screen sits in the footer, links to /m/ and stores songrush.ui = mobile', () => {
    setup()
    const link = mobileLink()
    expect(link.closest('footer')).not.toBe(null)
    expect(link.getAttribute('href')).toBe('/m/')
    clickWithoutNavigating(link)
    expect(window.localStorage.getItem('songrush.ui')).toBe('mobile')
  })

  it('on a /r/<code> link goes to /m/r/<code>', () => {
    window.history.pushState({}, '', '/r/ABCDE')
    setup()
    expect(mobileLink().getAttribute('href')).toBe('/m/r/ABCDE')
  })

  it('is translated in English and Japanese', () => {
    setup()
    const group = screen.getByRole('group', { name: 'Language' })
    fireEvent.click(within(group).getByRole('button', { name: 'English' }))
    expect(mobileLink(en).getAttribute('href')).toBe('/m/')
    fireEvent.click(within(group).getByRole('button', { name: '日本語' }))
    expect(mobileLink(ja).getAttribute('href')).toBe('/m/')
  })

  it('in a room links to /m/r/<code>', async () => {
    const env = setup()
    createRoomAs('Kai')
    await waitFor(() => expect(env.sockets.sockets.length).toBe(1))
    const socket = env.sockets.last
    act(() => socket.open())
    await waitFor(() => expect(socket.sentOfType('create')).toHaveLength(1))
    act(() => socket.receive({ type: 'joined', code: 'QWERT', name: 'Kai' }))
    act(() => socket.receive({ type: 'roster', phase: 'lobby', players: [player('Kai', { host: true })] }))
    await waitFor(() => expect(mobileLink().getAttribute('href')).toBe('/m/r/QWERT'))
  })
})
