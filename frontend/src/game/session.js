// One session over one WebSocket, solo or in a room. It sends the opening
// message, loads and schedules each song, runs the remaining-time timer and
// reports everything to the screen through dispatch. All server messages are
// handled in onMessage.
//
// mode 'solo' sends create{nickname, songCount}, ignores joined and roster,
// and closes the socket at gameOver. mode 'create' and 'join' are room
// sessions: they open a lobby with create{nickname} or join{code, nickname},
// stay open across games, and find the player's own rows by the name that
// joined assigned. A room session whose joined is followed by a snapshot of
// a playing game watches the current song (no ready, no skip) and plays from
// the next prepare.
import { planPlayback, loadTrack } from '../audio/playback.js'
import { createClockSync } from '../net/clockSync.js'
import { errorText } from './state.js'

const TICK_MS = 250
// Room clock sync (F-004): a burst of 8 pings on joined, 3 after each load,
// 3 every 30 s, and 3 when a start's receipt offset drifts beyond rtt + 50.
const JOIN_PINGS = 8
const RESYNC_PINGS = 3
const RESYNC_MS = 30_000
const DRIFT_MARGIN_MS = 50

function scheduleTimeout(fn, ms) {
  const id = setTimeout(fn, ms)
  return () => clearTimeout(id)
}

export function startSession({ socketFactory, url, ctx, fetchFn, now, mode, nickname, songCount, code, dispatch }) {
  const socket = socketFactory(url)
  const room = mode !== 'solo'
  // false once this session is closed; late callbacks then do nothing.
  let active = true
  let prepared = false
  // Room sessions: whether joined has arrived, and the assigned name.
  let joined = false
  let myName = null
  // The current song: { index, revealed, buffer, start, source, watching,
  // lateStart }. lateStart is a watched song's { startAt, deadlineAt }, kept
  // until a clock estimate exists.
  let round = null
  // Room sessions: a game is on (prepare or a playing snapshot) and its
  // gameOver has not arrived; a lobby roster then means it was abandoned.
  let inGame = false
  // Own skip toggles sent since the last skipState of this song.
  let pendingSkips = 0
  let timer = null
  // Room sessions only; solo keeps the receipt offset and sends no ping.
  const sync = room ? createClockSync({ now, send, schedule: scheduleTimeout }) : null
  let resyncTimer = null

  function send(message) {
    socket.send(JSON.stringify(message))
  }

  function stopSync() {
    if (resyncTimer !== null) {
      clearInterval(resyncTimer)
      resyncTimer = null
    }
    if (sync !== null) sync.stop()
  }

  // The sound prompt: shown while the context is not running.
  function soundCheck() {
    dispatch({ type: 'sound', blocked: ctx.state !== 'running' })
  }

  function outputLatency() {
    return Number.isFinite(ctx.outputLatency) ? ctx.outputLatency : 0
  }

  // Ready for the song; in a room the link is idle now, so it re-syncs.
  function sendReady(index) {
    send({ type: 'ready', index })
    if (sync !== null) sync.burst(RESYNC_PINGS)
  }

  // The offset used at start: the synced estimate when there is one, else
  // the receipt offset. A receipt that drifts from the estimate re-syncs
  // from fresh samples, and the song uses the smaller of the two offsets,
  // which is never early (cycle 047 review M1).
  function startOffset(serverTime) {
    const receipt = serverTime - now()
    const estimate = sync === null ? null : sync.current()
    if (estimate === null) return receipt
    if (Math.abs(receipt - estimate.offsetMs) <= estimate.rttMs + DRIFT_MARGIN_MS) return estimate.offsetMs
    sync.clear()
    sync.burst(RESYNC_PINGS)
    return Math.min(receipt, estimate.offsetMs)
  }

  function stopTimer() {
    if (timer !== null) {
      clearInterval(timer)
      timer = null
    }
  }

  function stopSource() {
    if (round !== null && round.source !== null) round.source.stop()
  }

  function remaining(start) {
    const max = start.deadlineAt - start.startAt
    const left = start.deadlineAt - start.offsetMs - now()
    return Math.min(max, Math.max(0, left))
  }

  // Plays the round's buffer once both the buffer and the start time are
  // known, whichever comes last (a late decode plays from the current point).
  async function schedule(r) {
    if (r.buffer === null || r.start === null || r.source !== null || r.revealed) return
    if (ctx.state !== 'running') {
      try {
        await ctx.resume()
      } catch (err) {
        // Scheduling goes on; a context that stays suspended is silent.
        console.warn('AudioContext resume failed', err)
      }
    }
    if (!active || round !== r || r.revealed || r.source !== null) return
    const plan = planPlayback({ ...r.start, nowMs: now(), ctxTime: ctx.currentTime, outputLatency: outputLatency() })
    if (plan === null) return
    const source = ctx.createBufferSource()
    source.buffer = r.buffer
    source.connect(ctx.destination)
    source.start(plan.when, plan.offset)
    source.stop(plan.stopAt)
    r.source = source
  }

  // The player's own row in scores or ranking. Solo: the first row is
  // always the player, whose server name may differ from the input.
  function ownRow(rows) {
    if (!room) return rows[0]
    return rows.find((row) => row.name === myName)
  }

  function onError(code) {
    // A limited skip vote was not counted: undo its toggle (spec 048 review M1).
    if (code === 'rate-limited' && pendingSkips > 0) {
      pendingSkips -= 1
      dispatch({ type: 'skipToggle' })
    }
    const text = errorText(code)
    const beforeGame = room ? !joined : !prepared
    if (beforeGame || (room && code === 'room-closed')) {
      // Back to the start screen with the message.
      close()
      dispatch({ type: 'startError', text })
    } else {
      dispatch({ type: 'error', text })
    }
  }

  function newRound(index, watching, lateStart) {
    return { index, revealed: false, buffer: null, start: null, source: null, watching, lateStart }
  }

  // Loads the round's audio. A played round then sends ready, also after a
  // failed load so the game is not held up; a watched round sends none.
  function load(r, audioUrl) {
    const current = () => active && round === r && !r.revealed
    loadTrack(ctx, audioUrl, fetchFn).then(
      (buffer) => {
        if (!current()) return
        r.buffer = buffer
        dispatch({ type: 'loaded' })
        if (!r.watching) sendReady(r.index)
        schedule(r)
      },
      (err) => {
        if (!current()) return
        console.warn('audio load failed twice', err)
        dispatch({ type: 'loadFailed' })
        if (!r.watching) sendReady(r.index)
      },
    )
  }

  function onPrepare(msg) {
    const r = newRound(msg.index, false, null)
    round = r
    prepared = true
    if (room) inGame = true
    pendingSkips = 0
    dispatch({ type: 'prepare', index: msg.index, total: msg.total })
    soundCheck()
    load(r, msg.audioUrl)
  }

  // Starts the round's clock and timer; it plays once the buffer is there.
  function beginStart(r, start) {
    r.start = start
    dispatch({ type: 'start', max: start.deadlineAt - start.startAt, remaining: remaining(start) })
    soundCheck()
    stopTimer()
    timer = setInterval(() => dispatch({ type: 'tick', remaining: remaining(start) }), TICK_MS)
    schedule(r)
  }

  function onStart(msg) {
    if (round === null || msg.index !== round.index) return
    beginStart(round, { startAt: msg.startAt, deadlineAt: msg.deadlineAt, offsetMs: startOffset(msg.serverTime) })
  }

  // A watched song already playing: the snapshot has no serverTime, so it
  // starts late once the first clock estimate exists (spec 048 review H1).
  function lateStart(r) {
    if (r.lateStart === null || r.start !== null || r.revealed) return
    const estimate = sync.current()
    if (estimate === null) return
    beginStart(r, { ...r.lateStart, offsetMs: estimate.offsetMs })
  }

  // joined, then a snapshot of a playing game: watch its current song.
  function onSnapshot(msg) {
    if (!room || !joined || msg.phase !== 'playing') return
    inGame = true
    pendingSkips = 0
    const song = msg.song
    if (song === null || song === undefined) {
      round = null
      dispatch({ type: 'watching', round: null })
      return
    }
    const late = typeof song.startAt === 'number' ? { startAt: song.startAt, deadlineAt: song.deadlineAt } : null
    const r = newRound(song.index, true, late)
    round = r
    dispatch({ type: 'watching', round: { index: song.index, total: song.total } })
    soundCheck()
    load(r, song.audioUrl)
    lateStart(r)
  }

  function onSkipState(msg) {
    if (round === null || msg.index !== round.index) return
    // The server has counted every toggle sent before this.
    pendingSkips = 0
    dispatch({ type: 'skipState', votes: msg.votes, needed: msg.needed })
  }

  // A lobby roster while a game is on and before its gameOver: the game was
  // abandoned (in practice only a watcher gets this, spec 048 review H2).
  function abandon() {
    inGame = false
    pendingSkips = 0
    stopTimer()
    if (round !== null) {
      round.revealed = true
      stopSource()
      round = null
    }
    dispatch({ type: 'abandoned' })
  }

  function onReveal(msg) {
    stopTimer()
    if (round !== null) {
      round.revealed = true
      stopSource()
    }
    dispatch({
      type: 'reveal',
      reveal: {
        title: msg.reveal,
        themeSlug: msg.themeSlug,
        song: msg.song,
        artists: msg.artists ?? [],
        winner: msg.winner,
      },
    })
  }

  function onMessage(msg) {
    switch (msg.type) {
      case 'prepare':
        onPrepare(msg)
        break
      case 'start':
        onStart(msg)
        break
      case 'chat':
        dispatch({ type: 'chat', name: msg.name, text: msg.text })
        break
      case 'correct':
        dispatch({ type: 'correct', name: msg.name })
        break
      case 'reveal':
        onReveal(msg)
        break
      case 'scores': {
        const row = ownRow(msg.scores)
        dispatch({ type: 'scores', score: row === undefined ? null : row.score, board: room ? msg.scores : null })
        break
      }
      case 'gameOver': {
        const row = ownRow(msg.ranking)
        // Solo: the server's solo lobby empties once this socket closes, so
        // nothing (such as room-closed) can reach the final-score screen.
        if (!room) close()
        stopTimer()
        inGame = false
        dispatch({
          type: 'gameOver',
          score: row === undefined ? null : row.score,
          ranking: room ? msg.ranking : null,
        })
        break
      }
      case 'joined':
        // The server runs solo games in a lobby too; solo ignores it.
        if (!room) break
        joined = true
        myName = msg.name
        dispatch({ type: 'joined', code: msg.code, name: msg.name })
        if (resyncTimer === null) {
          sync.burst(JOIN_PINGS)
          resyncTimer = setInterval(() => sync.burst(RESYNC_PINGS), RESYNC_MS)
        }
        break
      case 'pong':
        if (sync === null) break
        sync.onPong(msg)
        if (round !== null) lateStart(round)
        break
      case 'snapshot':
        onSnapshot(msg)
        break
      case 'skipState':
        onSkipState(msg)
        break
      case 'roster':
        if (!room) break
        dispatch({ type: 'roster', phase: msg.phase, players: msg.players })
        if (msg.phase === 'lobby' && inGame) abandon()
        break
      case 'error':
        onError(msg.code)
        break
      default:
        console.warn('unknown message', msg.type)
    }
  }

  // Closing on purpose: handlers are cleared first, so the old socket's
  // events never reach the screen.
  function close() {
    if (!active) return
    active = false
    stopTimer()
    stopSync()
    stopSource()
    socket.onopen = null
    socket.onmessage = null
    socket.onclose = null
    socket.onerror = null
    socket.close()
  }

  socket.onopen = () => {
    if (mode === 'solo') send({ type: 'create', nickname, songCount })
    else if (mode === 'create') send({ type: 'create', nickname })
    else send({ type: 'join', code, nickname })
  }
  socket.onmessage = (event) => {
    let msg
    try {
      msg = JSON.parse(event.data)
    } catch (err) {
      console.warn('unreadable server message', err)
      return
    }
    onMessage(msg)
  }
  socket.onclose = () => {
    if (!active) return
    active = false
    stopTimer()
    stopSync()
    stopSource()
    // A solo session is already closed after gameOver, and a room stays
    // open between games, so every close that gets here is unexpected.
    dispatch({ type: 'disconnected' })
  }
  // The browser fires close after error; the close handler reports it.
  socket.onerror = () => {
    console.warn('WebSocket error')
  }

  return {
    sendChat(text) {
      if (active) send({ type: 'chat', text })
    },
    startGame(count) {
      if (active) send({ type: 'start', songCount: count })
    },
    // Toggles the own skip vote for the current song, between its start and
    // its reveal. Watchers and solo do not vote.
    skipVote() {
      if (!active || !room || round === null || round.watching || round.start === null || round.revealed) return
      send({ type: 'skipVote', index: round.index })
      pendingSkips += 1
      dispatch({ type: 'skipToggle' })
    },
    // Leaves the room on purpose: the server is told, then the socket closes.
    leave() {
      if (active) send({ type: 'leave' })
      close()
    },
    close,
  }
}
