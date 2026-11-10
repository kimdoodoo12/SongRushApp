import { useEffect, useLayoutEffect, useReducer, useRef, useState } from 'react'
import { canPlayOggOpus } from './audio/playback.js'
import { startSession } from './game/session.js'
import { initialState, isAudible, reducer } from './game/state.js'
import { DEFAULT_SELECTION, isReversed, settingsLine, toFields } from './game/filters.js'
import StartScreen from './components/StartScreen.jsx'
import GameScreen, { Frame } from './components/GameScreen.jsx'
import LobbyScreen from './components/LobbyScreen.jsx'
import LanguageSwitcher from './components/LanguageSwitcher.jsx'
import FeedbackDialog from './components/FeedbackDialog.jsx'
import AdminScreen from './components/AdminScreen.jsx'
import { DEFAULT_THEME, loadOwnTheme, saveOwnTheme, themeId } from './game/themes.js'
import { gainOf, loadVolume, saveVolume, toggleMute, withLevel } from './game/volume.js'
import { applyStoredLanguage, useTranslation } from './i18n/index.js'
import {
  canvasDpr,
  createCanvasState,
  drawSky,
  drawViz,
  fireworkAt,
  initSky,
  prefersReducedMotion,
  resetViz,
} from './canvas/index.js'

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

// One 2d context per canvas element, asked for once (null without one, as
// in jsdom).
const contexts = new WeakMap()
function contextOf(canvas) {
  if (canvas === null) return null
  if (!contexts.has(canvas)) contexts.set(canvas, canvas.getContext('2d'))
  return contexts.get(canvas)
}

// The deck's box in CSS px (manga's speed lines), or null without a deck.
function deckBox() {
  const deck = document.querySelector('.deck')
  if (deck === null) return null
  const b = deck.getBoundingClientRect()
  return { left: b.left, top: b.top, width: b.width, height: b.height }
}

// The matsuri fireworks at a reveal (the mockup's showCover): three big
// ones over the deck, 0, 250 and 520 ms after it.
const REVEAL_FIREWORKS = [0, 250, 520]

function removeRejoinEntry() {
  try {
    window.sessionStorage.removeItem(REJOIN_KEY)
  } catch (err) {
    console.warn('sessionStorage clear failed', err)
  }
}

// The stored entry: { entry, unparsable }. entry is {token, code}, or null
// when there is none, it is malformed, or the storage is blocked; unparsable
// marks JSON that the mount effect removes. Reads only, so it is safe in a
// state initializer.
function readRejoinEntry() {
  let raw
  try {
    raw = window.sessionStorage.getItem(REJOIN_KEY)
  } catch (err) {
    console.warn('sessionStorage read failed', err)
    return { entry: null, unparsable: false }
  }
  if (raw === null) return { entry: null, unparsable: false }
  let entry
  try {
    entry = JSON.parse(raw)
  } catch (err) {
    console.warn('malformed rejoin entry', err)
    return { entry: null, unparsable: true }
  }
  if (entry === null || typeof entry !== 'object') return { entry: null, unparsable: false }
  if (typeof entry.token !== 'string' || typeof entry.code !== 'string') return { entry: null, unparsable: false }
  return { entry: { token: entry.token, code: entry.code }, unparsable: false }
}

// The mount-time reload decision (cycle 079): rejoin is the entry to rejoin
// with on a real reload, else null; drop says the entry is to be removed.
function reloadPlan(navigationType) {
  const type = navigationType === undefined ? pageNavigationType() : navigationType
  const { entry, unparsable } = readRejoinEntry()
  if (entry === null) return { rejoin: null, drop: unparsable }
  if (type !== 'reload') return { rejoin: null, drop: true }
  return { rejoin: entry, drop: false }
}

// The session's dispatch, with each chat line stamped with its local arrival
// time (the mockup's .tm, cycle 111). The stamp is taken here, not in the
// reducer, so the reducer stays pure.
function stampChat(dispatch) {
  return (action) => dispatch(action.type === 'chat' ? { ...action, at: Date.now() } : action)
}

// A reload rejoin starts in the connecting state, with the banner, so the
// start screen never flashes.
function initState(plan) {
  return plan.rejoin === null ? initialState : reducer(initialState, { type: 'connecting', rejoin: true })
}

// The room code from a /r/<code> share link, upper-cased; '' otherwise.
function codeFromPath() {
  const match = /^\/r\/([^/]+)\/?$/.exec(window.location.pathname)
  return match === null ? '' : match[1].toUpperCase().slice(0, 5)
}

// The owner's admin page (cycle 083): /admin or /admin/..., not /administrator.
function isAdminPath() {
  const path = window.location.pathname
  return path === '/admin' || path.startsWith('/admin/')
}

// /admin renders only the admin screen: no game socket, no reload rejoin, no
// footer feedback button. The path is read once at mount.
function App(props) {
  const [admin] = useState(isAdminPath)
  if (admin) {
    return (
      <div className="min-h-screen">
        <div className="px-3 py-3 sm:px-5 md:py-6">
          <AdminScreen />
        </div>
      </div>
    )
  }
  return <GameApp {...props} />
}

function GameApp({
  socketFactory = defaultSocketFactory,
  audioContextFactory = defaultAudioContextFactory,
  fetchFn = defaultFetch,
  canPlayOpus,
  now = Date.now,
  navigationType,
}) {
  // A stored language choice applies before the first render (D-095); in
  // test mode the detector is off, so this is where a stored choice lands.
  useState(applyStoredLanguage)
  const { t } = useTranslation()
  // Decided once at mount; the effect below acts on it.
  const [plan] = useState(() => reloadPlan(navigationType))
  const [state, dispatch] = useReducer(reducer, plan, initState)
  // Detected once; a canPlayOpus prop overrides it.
  const [detectedOpus] = useState(() => (canPlayOpus === undefined ? canPlayOggOpus() : true))
  const opusOk = canPlayOpus === undefined ? detectedOpus : canPlayOpus
  // Read once at mount (D-048 share links).
  const [initialCode] = useState(codeFromPath)
  // 곡 수, the filters and 정답 형식 (cycle 100), the host's lobby controls,
  // kept for the page's lifetime (F-008): neither 처음으로 nor 나가기 resets
  // them.
  const [selection, setSelection] = useState(DEFAULT_SELECTION)
  // The own room look (F-012): the start screen uses it; a room uses the
  // host's pick from the server instead.
  const [ownTheme, setOwnTheme] = useState(loadOwnTheme)
  const ctxRef = useRef(null)
  // The context's one gain node (D-099), made with the context and never
  // recreated while it lives; every song source connects to it.
  const gainRef = useRef(null)
  // The own volume, stored on the device. The ref mirrors it for openSession,
  // which may run from the mount effect's closure.
  const [volume, setVolume] = useState(loadVolume)
  const volumeRef = useRef(volume)
  const sessionRef = useRef(null)
  // The reload check has run (cycle 079); StrictMode runs effects twice.
  const reloadCheckedRef = useRef(false)
  // The 문의하기 dialog (cycle 082); focus returns to its footer button.
  const [feedbackOpen, setFeedbackOpen] = useState(false)
  const feedbackButtonRef = useRef(null)
  // The mockup's canvases (cycle 115, D-124): #sky and the deck's #viz,
  // drawn by one requestAnimationFrame loop. Their drawing state is one per
  // mount; reduce and dpr are read once, as the mockup's constants are.
  const skyRef = useRef(null)
  const [canvasState] = useState(createCanvasState)
  const [motion] = useState(() => ({ reduce: prefersReducedMotion(), dpr: canvasDpr() }))
  // The visualizer's analyser, a side branch off the gain (made with it).
  const analyserRef = useRef(null)
  // What the loop reads each frame: the mood and whether a song is audible.
  const frameRef = useRef({ mood: DEFAULT_THEME, playing: false })
  const fireworkTimersRef = useRef(new Set())

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
    if (plan.rejoin === null) {
      reloadCheckedRef.current = true
      if (plan.drop) removeRejoinEntry()
      return undefined
    }
    const entry = plan.rejoin
    const id = setTimeout(() => {
      reloadCheckedRef.current = true
      openSession({ mode: 'rejoin', token: entry.token, code: entry.code })
    }, 0)
    return () => clearTimeout(id)
    // Mount only: the plan is read once, like the share-link code.
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
      const created = audioContextFactory()
      const gain = created.createGain()
      gain.gain.value = gainOf(volumeRef.current)
      gain.connect(created.destination)
      // The visualizer's tap (cycle 115): gain -> compressor (the mockup's
      // level normalisation) -> analyser, which connects to nothing, so the
      // playback path and what is heard stay as they are.
      const comp = created.createDynamicsCompressor()
      comp.threshold.value = -14
      comp.ratio.value = 4
      const analyser = created.createAnalyser()
      analyser.fftSize = 256
      analyser.smoothingTimeConstant = 0.78
      gain.connect(comp)
      comp.connect(analyser)
      ctxRef.current = created
      gainRef.current = gain
      analyserRef.current = analyser
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
      output: gainRef.current,
      fetchFn,
      now,
      dispatch: stampChat(dispatch),
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

  // A choice tile click (cycle 100); the session checks it may be sent.
  function handlePick(choice) {
    if (sessionRef.current !== null) sessionRef.current.pick(choice)
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

  // A volume change applies at once, also to the song already playing: the
  // gain is set directly, with no automation (D-099).
  function applyVolume(next) {
    volumeRef.current = next
    setVolume(next)
    saveVolume(next)
    if (gainRef.current !== null) gainRef.current.gain.value = gainOf(next)
  }

  function handleVolumeLevel(v) {
    applyVolume(withLevel(volumeRef.current, v))
  }

  function handleToggleMute() {
    applyVolume(toggleMute(volumeRef.current))
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

  function handleCloseFeedback() {
    setFeedbackOpen(false)
    feedbackButtonRef.current?.focus()
  }

  function handleReset() {
    closeSession()
    dispatch({ type: 'reset' })
  }

  const inRoom = state.room !== null
  const roomTheme = themeId(state.roomTheme ?? DEFAULT_THEME)
  // The screen a feedback post reports; over and disconnected count as game.
  const page = state.screen === 'start' || state.screen === 'lobby' ? state.screen : 'game'
  // The game screen always shows the room theme, sunset until its frame, also
  // while connecting and rejoining (cycle 111, critic M1 of 110), so it never
  // flips between the own pick and the room's.
  const theme = page === 'game' || inRoom ? roomTheme : ownTheme

  // The mood is <html data-mood> (D-124): the mockup's stylesheet reads it
  // there. The own pick outside a room, the room's pick in one and on the
  // game screen.
  useLayoutEffect(() => {
    document.documentElement.dataset.mood = theme
  }, [theme])

  const audible = isAudible(state)
  useLayoutEffect(() => {
    frameRef.current = { mood: theme, playing: audible }
  }, [theme, audible])

  // The mockup's setMood and resize: initSky for the mood, a fresh
  // visualizer state, and under reduced motion the still sky.
  useLayoutEffect(() => {
    const sky = skyRef.current
    const redo = () => {
      initSky(canvasState, sky, theme, motion.dpr)
      if (motion.reduce) {
        drawSky(canvasState, contextOf(sky), theme, performance.now(), false, {
          playing: frameRef.current.playing,
          reduce: true,
          focus: null,
        })
      }
    }
    redo()
    resetViz(canvasState)
    window.addEventListener('resize', redo)
    return () => window.removeEventListener('resize', redo)
  }, [theme, canvasState, motion])

  // The mockup's loop: drawViz each frame, drawSky(move) every other frame
  // (every frame in rain), never under reduced motion; nothing while the
  // tab is hidden. A frame callback that comes back at once (a synchronous
  // requestAnimationFrame) is deferred to a timeout, so the loop never
  // recurses.
  useEffect(() => {
    const levels = new Uint8Array(128)
    let fr = 0
    let pending = null
    let deferred = null
    let stopped = false

    function frame(t) {
      if (stopped) return
      if (!document.hidden) {
        const { mood, playing } = frameRef.current
        const analyser = analyserRef.current
        const live = playing && analyser !== null
        if (live) analyser.getByteFrequencyData(levels)
        const viz = document.getElementById('viz')
        if (viz !== null) drawViz(canvasState, contextOf(viz), mood, t, { live, levels, reduce: motion.reduce })
        if (!motion.reduce && (fr++ % 2 === 0 || mood === 'rain')) {
          const focus = mood === 'manga' && playing ? deckBox() : null
          drawSky(canvasState, contextOf(skyRef.current), mood, t, true, { playing, reduce: false, focus })
        }
      }
      arm()
    }

    function arm() {
      let inside = true
      pending = window.requestAnimationFrame((t) => {
        if (!inside) {
          frame(t)
          return
        }
        deferred = setTimeout(() => {
          deferred = null
          frame(t)
        }, 0)
      })
      inside = false
    }

    arm()
    return () => {
      stopped = true
      if (pending !== null) window.cancelAnimationFrame(pending)
      if (deferred !== null) clearTimeout(deferred)
    }
  }, [canvasState, motion])

  // matsuri's fireworks at the reveal (the mockup's showCover), not under
  // reduced motion. Positions are in sky pixels.
  const revealShown = state.screen === 'game' && state.reveal !== null ? state.reveal : null
  useEffect(() => {
    if (revealShown === null || theme !== 'matsuri' || motion.reduce) return
    const box = deckBox()
    if (box === null) return
    const timers = fireworkTimersRef.current
    REVEAL_FIREWORKS.forEach((delay, k) => {
      const id = setTimeout(() => {
        timers.delete(id)
        fireworkAt(canvasState, (box.left + box.width * (0.3 + k * 0.25)) * motion.dpr, (box.top + box.height * 0.1) * motion.dpr, true)
      }, delay)
      timers.add(id)
    })
  }, [revealShown, theme, canvasState, motion])

  useEffect(() => {
    const timers = fireworkTimersRef.current
    return () => {
      for (const id of timers) clearTimeout(id)
      timers.clear()
    }
  }, [])

  // body.choice-mode while the current game is a choice game (the mockup's
  // setMode, cycle 112): the room.css layout with the tiles below the deck
  // and the panel. Off in a chat game and once the game screen is left.
  const choiceGame =
    (state.screen === 'game' || state.screen === 'over') &&
    (state.choices !== null || (state.settings !== null && state.settings.answerMode === 'choice'))
  useLayoutEffect(() => {
    document.body.classList.toggle('choice-mode', choiceGame)
    return () => document.body.classList.remove('choice-mode')
  }, [choiceGame])

  // The view the frame shows: 'start', 'lobby', or 'game' (also over and
  // disconnected). body.lobby-on is the lobby's (the mockup's showView), for
  // room.css's per-mood lobby seats.
  const view = page
  useLayoutEffect(() => {
    document.body.classList.toggle('lobby-on', view === 'lobby')
    return () => document.body.classList.remove('lobby-on')
  }, [view])

  // The own player is the host on the lobby view (cycle 116). While it is,
  // the lobby selection goes to the server: once on entering the lobby as
  // host or on a roster that makes it host, and again on each change, so the
  // guests' card shows what the next start uses. A rejoin into the lobby
  // sends it again (lobbyRestores): host may have passed to another member
  // and back while the socket was down, and changes made meanwhile were
  // dropped. A reversed year range is not sent (the server refuses it, and
  // start is disabled meanwhile).
  const ownRow = inRoom ? state.players.find((p) => p.name === state.room.me) : undefined
  const isHost = ownRow !== undefined && ownRow.host
  const hostInLobby = view === 'lobby' && isHost
  useEffect(() => {
    if (!hostInLobby || isReversed(selection) || sessionRef.current === null) return
    sessionRef.current.sendLobbySettings(Number(selection.songCount), toFields(selection))
  }, [hostInLobby, selection, state.lobbyRestores])

  // A view switch starts at the top, as the mockup's showView does: the
  // stage (its own scroller on wide screens) and the page. Not on mount.
  const shownViewRef = useRef(view)
  useLayoutEffect(() => {
    if (shownViewRef.current === view) return
    shownViewRef.current = view
    const stage = document.querySelector('.stage')
    if (stage !== null) stage.scrollTop = 0
    window.scrollTo(0, 0)
  }, [view])

  const banner = state.reconnecting && (
    <p className="warn" role="status">
      {t('app.reconnecting')}
    </p>
  )
  const footer = (
    <footer className="mt-auto flex flex-col items-center gap-2 pt-8">
      <div className="flex flex-wrap items-center justify-center gap-2">
        <LanguageSwitcher />
        <button ref={feedbackButtonRef} type="button" className="btn" onClick={() => setFeedbackOpen(true)}>
          {t('feedback.title')}
        </button>
      </div>
      <p className="credit text-center text-xs text-muted">{t('app.credit')}</p>
    </footer>
  )
  const feedback = feedbackOpen && <FeedbackDialog page={page} onClose={handleCloseFeedback} />
  const volumeProps = { volume, onVolumeLevel: handleVolumeLevel, onToggleMute: handleToggleMute }
  // The first element of the root on every screen (before the frame's .scan
  // and .app), so it stays the same element across them.
  const sky = <canvas ref={skyRef} id="sky" aria-hidden="true" />

  if (view === 'game') {
    return (
      <>
        {sky}
        <GameScreen
          state={state}
          theme={roomTheme}
          onPickTheme={handleRoomTheme}
          onChat={handleChat}
          onPick={handlePick}
          onSkip={handleSkip}
          onReset={handleReset}
          onToLobby={handleToLobby}
          onUnlockSound={handleUnlockSound}
          {...volumeProps}
          banner={banner}
          footer={footer}
        />
        {feedback}
      </>
    )
  }

  if (view === 'start') {
    return (
      <>
        {sky}
        <Frame
          code={null}
          line=""
          picker={{ value: ownTheme, onPick: handleOwnTheme, disabled: false }}
          {...volumeProps}
          banner={banner}
          footer={footer}
          chat={null}
        >
          <StartScreen
            canPlayOpus={opusOk}
            error={state.startError}
            initialCode={initialCode}
            onCreate={handleCreate}
            onJoin={handleJoin}
          />
        </Frame>
        {feedback}
      </>
    )
  }

  // The lobby (cycle 114): the header shows the room code and the settings
  // line of the lobby selection, the own one for the host and the received
  // one for a guest (cycle 116); the chat is open.
  const room = state.room
  const online = state.players.filter((p) => p.connected !== false).length
  let line = ''
  if (isHost) line = settingsLine(toFields(selection), t)
  else if (state.lobbySettings !== null) line = settingsLine(state.lobbySettings, t)
  return (
    <>
      {sky}
      <Frame
        code={room.code}
        line={line}
        picker={{ value: roomTheme, onPick: handleRoomTheme, disabled: !isHost }}
        {...volumeProps}
        banner={banner}
        footer={footer}
        chat={{ lines: state.chat, me: room.me, online, onSend: handleChat }}
      >
        <LobbyScreen
          room={room}
          players={state.players}
          error={state.error}
          notice={state.notice}
          theme={roomTheme}
          selection={selection}
          lobbySettings={state.lobbySettings}
          onSelectionChange={setSelection}
          onStartGame={handleStartGame}
          onLeave={handleLeave}
        />
      </Frame>
      {feedback}
    </>
  )
}

export default App
