import { useEffect, useReducer, useRef, useState } from 'react'
import { canPlayOggOpus } from './audio/playback.js'
import { startSession } from './game/session.js'
import { initialState, reducer } from './game/state.js'
import { DEFAULT_SELECTION, isReversed, toFields } from './game/filters.js'
import StartScreen from './components/StartScreen.jsx'
import GameScreen from './components/GameScreen.jsx'
import LobbyScreen from './components/LobbyScreen.jsx'
import ThemeDecor from './components/ThemeDecor.jsx'
import { DEFAULT_THEME, loadOwnTheme, saveOwnTheme, themeId } from './game/themes.js'

const CREDIT = '데이터 출처: AnimeThemes, AniList, anime-offline-database, AniDB, Wikidata'
const RECONNECTING_TEXT = '연결이 끊겨 다시 연결하는 중...'

const defaultSocketFactory = (url) => new WebSocket(url)
const defaultAudioContextFactory = () => new (window.AudioContext || window.webkitAudioContext)()
const defaultFetch = (url) => fetch(url)
// The rejoin entry session.js stores on every token frame (cycle 049).
const REJOIN_KEY = 'songrush.rejoin'

function socketUrl() {
  const scheme = window.location.protocol === 'https:' ? 'wss' : 'ws'
  return `${scheme}://${window.location.host}/ws`
}

// The page's navigation type ('reload', 'navigate', ...), 'navigate' when the
// Performance API cannot tell.
function pageNavigationType() {
  try {
    const entry = window.performance?.getEntriesByType?.('navigation')?.[0]
    return typeof entry?.type === 'string' ? entry.type : 'navigate'
  } catch (err) {
    console.warn('navigation type unavailable', err)
    return 'navigate'
  }
}

function removeRejoinEntry() {
  try {
    window.sessionStorage.removeItem(REJOIN_KEY)
  } catch (err) {
    console.warn('sessionStorage clear failed', err)
  }
}

// The stored {token, code}, or null when there is none, it is malformed, or
// the storage is blocked. Unparsable JSON is removed.
function readRejoinEntry() {
  let raw
  try {
    raw = window.sessionStorage.getItem(REJOIN_KEY)
  } catch (err) {
    console.warn('sessionStorage read failed', err)
    return null
  }
  if (raw === null) return null
  let entry
  try {
    entry = JSON.parse(raw)
  } catch (err) {
    console.warn('malformed rejoin entry', err)
    removeRejoinEntry()
    return null
  }
  if (entry === null || typeof entry !== 'object') return null
  if (typeof entry.token !== 'string' || typeof entry.code !== 'string') return null
  return { token: entry.token, code: entry.code }
}

// The room code from a /r/<code> share link, upper-cased; '' otherwise.
function codeFromPath() {
  const match = /^\/r\/([^/]+)\/?$/.exec(window.location.pathname)
  return match === null ? '' : match[1].toUpperCase().slice(0, 5)
}

function App({
  socketFactory = defaultSocketFactory,
  audioContextFactory = defaultAudioContextFactory,
  fetchFn = defaultFetch,
  canPlayOpus,
  now = Date.now,
  navigationType,
}) {
  const [state, dispatch] = useReducer(reducer, initialState)
  // Detected once; a canPlayOpus prop overrides it.
  const [detectedOpus] = useState(() => (canPlayOpus === undefined ? canPlayOggOpus() : true))
  const opusOk = canPlayOpus === undefined ? detectedOpus : canPlayOpus
  // Read once at mount (D-048 share links).
  const [initialCode] = useState(codeFromPath)
  // 곡 수 and the filters, the host's lobby controls, kept for the page's
  // lifetime (F-008): neither 처음으로 nor 나가기 resets them.
  const [selection, setSelection] = useState(DEFAULT_SELECTION)
  // The own room look (F-012): the start screen uses it; a room uses the
  // host's pick from the server instead.
  const [ownTheme, setOwnTheme] = useState(loadOwnTheme)
  const ctxRef = useRef(null)
  const sessionRef = useRef(null)
  // The reload check has run (cycle 079); StrictMode runs effects twice.
  const reloadCheckedRef = useRef(false)

  useEffect(() => {
    const sessions = sessionRef
    return () => {
      if (sessions.current !== null) sessions.current.close()
    }
  }, [])

  // A page reload rejoins the room of the stored entry (cycle 079). Only a
  // real reload does: a duplicated tab copies sessionStorage but must not
  // take the original tab's seat, so any other navigation drops the entry.
  // The socket opens from a timeout that the effect cleanup cancels, so
  // StrictMode's mount, unmount, mount opens exactly one.
  useEffect(() => {
    if (reloadCheckedRef.current) return undefined
    const type = navigationType === undefined ? pageNavigationType() : navigationType
    const entry = readRejoinEntry()
    if (entry === null) {
      reloadCheckedRef.current = true
      return undefined
    }
    if (type !== 'reload') {
      reloadCheckedRef.current = true
      removeRejoinEntry()
      return undefined
    }
    const id = setTimeout(() => {
      reloadCheckedRef.current = true
      openSession({ mode: 'rejoin', token: entry.token, code: entry.code })
    }, 0)
    return () => clearTimeout(id)
    // Mount only: the props are read once, like the share-link code.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  function closeSession() {
    if (sessionRef.current !== null) {
      sessionRef.current.close()
      sessionRef.current = null
    }
  }

  // A click that may come after iOS suspended the context: resume inside the
  // gesture, synchronously (cycle 041 review).
  function resumeIfSuspended() {
    const ctx = ctxRef.current
    if (ctx === null || ctx.state === 'running') return
    ctx.resume().catch((err) => {
      console.warn('AudioContext resume failed', err)
    })
  }

  // Starts a room session: mode 'create', 'join', or 'rejoin' after a reload
  // (see session.js). There is no solo mode in the client (D-093): every game
  // is a room game.
  function openSession(options) {
    // The audio-unlock gesture: create and resume synchronously in the click,
    // before anything is awaited (design 029). One context for every game.
    // A reload rejoin has no gesture; a suspended context shows the
    // 탭하여 소리 켜기 prompt.
    if (ctxRef.current === null) {
      // iOS 17+: play through the silent switch (spec 047 review H1).
      if (window.navigator.audioSession) window.navigator.audioSession.type = 'playback'
      ctxRef.current = audioContextFactory()
    }
    const ctx = ctxRef.current
    ctx.resume().catch((err) => {
      // The start message resumes again before scheduling.
      console.warn('AudioContext resume failed', err)
    })
    closeSession()
    dispatch({ type: 'connecting', rejoin: options.mode === 'rejoin' })
    sessionRef.current = startSession({
      socketFactory,
      url: socketUrl(),
      ctx,
      fetchFn,
      now,
      dispatch,
      ...options,
    })
  }

  function handleCreate(nickname) {
    openSession({ mode: 'create', nickname })
  }

  function handleJoin(nickname, code) {
    openSession({ mode: 'join', nickname, code })
  }

  function handleStartGame() {
    if (isReversed(selection)) return
    resumeIfSuspended()
    if (sessionRef.current !== null) sessionRef.current.startGame(Number(selection.songCount), toFields(selection))
  }

  function handleLeave() {
    if (sessionRef.current !== null) {
      sessionRef.current.leave()
      sessionRef.current = null
    }
    dispatch({ type: 'reset' })
  }

  function handleSkip() {
    if (sessionRef.current !== null) sessionRef.current.skipVote()
  }

  function handleChat(text) {
    if (sessionRef.current !== null) sessionRef.current.sendChat(text)
  }

  function handleToLobby() {
    resumeIfSuspended()
    dispatch({ type: 'toLobby' })
  }

  // The 탭하여 소리 켜기 click: resume in the gesture; the prompt goes once
  // the resume leaves the context running.
  function handleUnlockSound() {
    const ctx = ctxRef.current
    if (ctx === null) return
    ctx.resume().then(
      () => dispatch({ type: 'sound', blocked: ctx.state !== 'running' }),
      (err) => console.warn('AudioContext resume failed', err),
    )
  }

  function handleOwnTheme(id) {
    const next = themeId(id)
    setOwnTheme(next)
    saveOwnTheme(next)
  }

  // The host's lobby pick; the pressed button follows the server's frame.
  function handleRoomTheme(id) {
    if (sessionRef.current !== null) sessionRef.current.setRoomTheme(id)
  }

  function handleReset() {
    closeSession()
    dispatch({ type: 'reset' })
  }

  const inRoom = state.room !== null
  const roomTheme = themeId(state.roomTheme ?? DEFAULT_THEME)
  const theme = inRoom ? roomTheme : ownTheme

  return (
    <div className="app-shell min-h-screen" data-room-theme={theme}>
      <ThemeDecor theme={theme} />
      <div className="app relative z-[1] mx-auto flex min-h-screen w-full max-w-6xl flex-col gap-3 px-3 py-3 sm:px-5 md:gap-5 md:py-6">
        <header className="flex items-center justify-center md:justify-start">
          <h1 className="brand w-56 text-center text-3xl whitespace-nowrap tracking-tight md:text-left md:text-4xl">SongRush</h1>
        </header>
        {state.reconnecting && (
          <p
            className="reconnecting rounded-xl border border-warn/40 bg-warn/10 px-4 py-2 text-center text-sm font-semibold text-warn"
            role="status"
          >
            {RECONNECTING_TEXT}
          </p>
        )}
        {state.screen === 'start' && (
          <StartScreen
            canPlayOpus={opusOk}
            error={state.startError}
            initialCode={initialCode}
            theme={ownTheme}
            onPickTheme={handleOwnTheme}
            onCreate={handleCreate}
            onJoin={handleJoin}
          />
        )}
        {state.screen === 'lobby' && (
          <LobbyScreen
            room={state.room}
            players={state.players}
            error={state.error}
            notice={state.notice}
            theme={roomTheme}
            onPickTheme={handleRoomTheme}
            selection={selection}
            onSelectionChange={setSelection}
            onStartGame={handleStartGame}
            onLeave={handleLeave}
          />
        )}
        {state.screen !== 'start' && state.screen !== 'lobby' && (
          <GameScreen
            state={state}
            onChat={handleChat}
            onSkip={handleSkip}
            onReset={handleReset}
            onToLobby={handleToLobby}
            onUnlockSound={handleUnlockSound}
          />
        )}
        <footer className="credit mt-auto pt-4 text-center text-xs text-muted">{CREDIT}</footer>
      </div>
    </div>
  )
}

export default App
