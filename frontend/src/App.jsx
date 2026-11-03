import { useEffect, useReducer, useRef, useState } from 'react'
import { canPlayOggOpus } from './audio/playback.js'
import { startSession } from './game/session.js'
import { initialState, reducer } from './game/state.js'
import StartScreen from './components/StartScreen.jsx'
import GameScreen from './components/GameScreen.jsx'
import LobbyScreen from './components/LobbyScreen.jsx'
import './App.css'

const CREDIT = '데이터 출처: AnimeThemes, AniList, anime-offline-database, AniDB, Wikidata'

const defaultSocketFactory = (url) => new WebSocket(url)
const defaultAudioContextFactory = () => new (window.AudioContext || window.webkitAudioContext)()
const defaultFetch = (url) => fetch(url)

function socketUrl() {
  const scheme = window.location.protocol === 'https:' ? 'wss' : 'ws'
  return `${scheme}://${window.location.host}/ws`
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
}) {
  const [state, dispatch] = useReducer(reducer, initialState)
  // Detected once; a canPlayOpus prop overrides it.
  const [detectedOpus] = useState(() => (canPlayOpus === undefined ? canPlayOggOpus() : true))
  const opusOk = canPlayOpus === undefined ? detectedOpus : canPlayOpus
  // Read once at mount (D-048 share links).
  const [initialCode] = useState(codeFromPath)
  const ctxRef = useRef(null)
  const sessionRef = useRef(null)

  useEffect(() => {
    const sessions = sessionRef
    return () => {
      if (sessions.current !== null) sessions.current.close()
    }
  }, [])

  function closeSession() {
    if (sessionRef.current !== null) {
      sessionRef.current.close()
      sessionRef.current = null
    }
  }

  // Starts a session: mode 'solo', 'create' or 'join' (see session.js).
  function openSession(options) {
    // The audio-unlock gesture: create and resume synchronously in the click,
    // before anything is awaited (design 029). One context for every game.
    if (ctxRef.current === null) ctxRef.current = audioContextFactory()
    const ctx = ctxRef.current
    ctx.resume().catch((err) => {
      // The start message resumes again before scheduling.
      console.warn('AudioContext resume failed', err)
    })
    closeSession()
    dispatch({ type: 'connecting' })
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

  function handleStart(nickname, songCount) {
    openSession({ mode: 'solo', nickname, songCount })
  }

  function handleCreate(nickname) {
    openSession({ mode: 'create', nickname })
  }

  function handleJoin(nickname, code) {
    openSession({ mode: 'join', nickname, code })
  }

  function handleStartGame(songCount) {
    if (sessionRef.current !== null) sessionRef.current.startGame(songCount)
  }

  function handleLeave() {
    if (sessionRef.current !== null) {
      sessionRef.current.leave()
      sessionRef.current = null
    }
    dispatch({ type: 'reset' })
  }

  function handleChat(text) {
    if (sessionRef.current !== null) sessionRef.current.sendChat(text)
  }

  function handleReset() {
    closeSession()
    dispatch({ type: 'reset' })
  }

  return (
    <div className="app">
      <h1>SongRush</h1>
      {state.screen === 'start' && (
        <StartScreen
          canPlayOpus={opusOk}
          error={state.startError}
          initialCode={initialCode}
          onStart={handleStart}
          onCreate={handleCreate}
          onJoin={handleJoin}
        />
      )}
      {state.screen === 'lobby' && (
        <LobbyScreen
          room={state.room}
          players={state.players}
          error={state.error}
          onStartGame={handleStartGame}
          onLeave={handleLeave}
        />
      )}
      {state.screen !== 'start' && state.screen !== 'lobby' && (
        <GameScreen
          state={state}
          onChat={handleChat}
          onReset={handleReset}
          onToLobby={() => dispatch({ type: 'toLobby' })}
        />
      )}
      <footer className="credit">{CREDIT}</footer>
    </div>
  )
}

export default App
