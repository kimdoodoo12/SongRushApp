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
//
// A room session that has its token reconnects on an unexpected close
// (cycle 049): new sockets with rejoin{token} after waits of 0.5, 1, 2, 4,
// 8, then 10 s, each given 5 s to reach joined, until 30 s after the drop.
// Until the new socket's snapshot only joined, snapshot, pong, token and
// error are handled; the snapshot then rebuilds the screen as a player.
// A 4001 close (replaced by another tab) never reconnects.
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
// A provisional start (spec 051) moves only for a lower bound this far above.
const CORRECTION_MARGIN_MS = 20
// Reconnect (F-006): the waits before each attempt, the last one repeating,
// the time each attempt has to reach joined, and the limit from the drop
// (the server's grace, cycle 042).
const RECONNECT_WAITS_MS = [500, 1000, 2000, 4000, 8000, 10_000]
const ATTEMPT_MS = 5000
const RECONNECT_LIMIT_MS = 30_000
// The close status of a socket replaced by another tab.
const REPLACED_CODE = 4001
const OPEN = 1
const STORAGE_KEY = 'songrush.rejoin'
// Frames handled on a rejoin socket before its snapshot (spec 049 H1).
const BEFORE_SNAPSHOT = new Set(['joined', 'snapshot', 'pong', 'token', 'error'])

// The rejoin entry in sessionStorage; a blocked storage changes nothing.
function storeRejoin(entry) {
  try {
    window.sessionStorage.setItem(STORAGE_KEY, JSON.stringify(entry))
  } catch (err) {
    console.warn('sessionStorage write failed', err)
  }
}

function clearRejoin() {
  try {
    window.sessionStorage.removeItem(STORAGE_KEY)
  } catch (err) {
    console.warn('sessionStorage clear failed', err)
  }
}

function scheduleTimeout(fn, ms) {
  const id = setTimeout(fn, ms)
  return () => clearTimeout(id)
}

export function startSession({ socketFactory, url, ctx, fetchFn, now, mode, nickname, songCount, code, dispatch }) {
  let socket = socketFactory(url)
  const room = mode !== 'solo'
  // false once this session is closed; late callbacks then do nothing.
  let active = true
  let prepared = false
  // Room sessions: whether joined has arrived, and the assigned name.
  let joined = false
  let myName = null
  // The current song: { index, revealed, buffer, start, source, watching,
  // lateStart, provisional }. lateStart is a watched song's { startAt,
  // deadlineAt }, kept until a clock estimate exists. provisional: the start
  // used a drifted receipt and fresh pongs may move it earlier (spec 051);
  // a new round (prepare, snapshot, reconnect) starts without it.
  let round = null
  // Room sessions: a game is on (prepare or a playing snapshot) and its
  // gameOver has not arrived; a lobby roster then means it was abandoned.
  let inGame = false
  // The own skip vote for the current song (spec 050): skipVote clicks sent
  // and the rate-limited errors tagged for:'skipVote'; an odd difference is
  // a vote. Reset with the song (prepare, snapshot, reconnect).
  let skipsSent = 0
  let skipsRefused = 0
  let timer = null
  // Room sessions only; solo keeps the receipt offset and sends no ping.
  const sync = room ? createClockSync({ now, send, schedule: scheduleTimeout }) : null
  let resyncTimer = null
  // Reconnect state: the last token and the room code; whether a rejoin is
  // in progress (from the drop until joined); whether the socket is a rejoin
  // socket, and whether its snapshot is still to come.
  let token = null
  let roomCode = null
  let reconnecting = false
  let rejoined = false
  let awaitingSnapshot = false
  let attempts = 0
  let retryTimer = null
  let attemptTimer = null
  let limitTimer = null

  // Dropped while the socket is not open: a connecting WebSocket throws.
  function send(message) {
    if (socket.readyState !== OPEN) return
    socket.send(JSON.stringify(message))
  }

  function detach(s) {
    s.onopen = null
    s.onmessage = null
    s.onclose = null
    s.onerror = null
  }

  function cancelReconnect() {
    reconnecting = false
    for (const id of [retryTimer, attemptTimer, limitTimer]) {
      if (id !== null) clearTimeout(id)
    }
    retryTimer = null
    attemptTimer = null
    limitTimer = null
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
  // which is never early (cycle 047 review M1). A drift that used the
  // receipt (a late frame or a forward clock jump) is provisional: fresh
  // pongs may still move it earlier (cycle 051).
  function startOffset(serverTime) {
    const receipt = serverTime - now()
    const estimate = sync === null ? null : sync.current()
    if (estimate === null) return { offsetMs: receipt, provisional: false }
    if (Math.abs(receipt - estimate.offsetMs) <= estimate.rttMs + DRIFT_MARGIN_MS) {
      return { offsetMs: estimate.offsetMs, provisional: false }
    }
    sync.clear()
    sync.burst(RESYNC_PINGS)
    if (receipt < estimate.offsetMs) return { offsetMs: receipt, provisional: true }
    return { offsetMs: estimate.offsetMs, provisional: false }
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

  function resetSkips() {
    skipsSent = 0
    skipsRefused = 0
  }

  function dispatchOwnSkip() {
    dispatch({ type: 'ownSkip', voted: (skipsSent - skipsRefused) % 2 === 1 })
  }

  function onError(code, refused) {
    // A refused skip vote was not counted (spec 050); an untagged error (a
    // chat refusal) never touches the vote.
    if (code === 'rate-limited' && refused === 'skipVote' && skipsRefused < skipsSent) {
      skipsRefused += 1
      dispatchOwnSkip()
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
    return { index, revealed: false, buffer: null, start: null, source: null, watching, lateStart, provisional: false }
  }

  // Loads the round's audio. A played round then sends ready, also after a
  // failed load so the game is not held up; a watched round, or a played one
  // already running (a rejoin into PLAYING), sends none.
  function load(r, audioUrl) {
    const readies = !r.watching && r.lateStart === null
    const current = () => active && round === r && !r.revealed
    loadTrack(ctx, audioUrl, fetchFn).then(
      (buffer) => {
        if (!current()) return
        r.buffer = buffer
        dispatch({ type: 'loaded' })
        if (readies) sendReady(r.index)
        schedule(r)
      },
      (err) => {
        if (!current()) return
        console.warn('audio load failed twice', err)
        dispatch({ type: 'loadFailed' })
        if (readies) sendReady(r.index)
      },
    )
  }

  function onPrepare(msg) {
    const r = newRound(msg.index, false, null)
    round = r
    prepared = true
    if (room) inGame = true
    resetSkips()
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
    const { offsetMs, provisional } = startOffset(msg.serverTime)
    round.provisional = provisional
    beginStart(round, { startAt: msg.startAt, deadlineAt: msg.deadlineAt, offsetMs })
  }

  // A provisional start moves earlier when a fresh pong's lower bound is
  // more than 20 ms above the offset in use; it never moves later, so it is
  // never early (spec 051). The ticks read the same start object.
  function correctStart(r) {
    if (!r.provisional || r.revealed || r.start === null) return
    const bound = sync.lowerBound()
    if (bound === null || bound <= r.start.offsetMs + CORRECTION_MARGIN_MS) return
    r.start.offsetMs = bound
    if (r.source !== null) {
      r.source.stop()
      r.source = null
    }
    schedule(r)
  }

  // A watched song already playing: the snapshot has no serverTime, so it
  // starts late once the first clock estimate exists (spec 048 review H1).
  function lateStart(r) {
    if (r.lateStart === null || r.start !== null || r.revealed) return
    const estimate = sync.current()
    if (estimate === null) return
    beginStart(r, { ...r.lateStart, offsetMs: estimate.offsetMs })
  }

  // The current round ends: its source stops and late callbacks are ignored.
  function dropRound() {
    stopTimer()
    if (round !== null) {
      round.revealed = true
      stopSource()
      round = null
    }
  }

  // A snapshot on a rejoin socket rebuilds the screen from it, so a repeated
  // joined/snapshot pair gives the same result. Its song is a played round:
  // PLAYING starts late once a clock estimate exists, PREPARE sends ready.
  function onRejoinSnapshot(msg) {
    awaitingSnapshot = false
    dropRound()
    resetSkips()
    const playing = msg.phase === 'playing'
    const song = playing && msg.song !== null && msg.song !== undefined ? msg.song : null
    const scores = Array.isArray(msg.scores) && msg.scores.length > 0 ? msg.scores : null
    const own = scores === null ? undefined : ownRow(scores)
    inGame = playing
    dispatch({
      type: 'restore',
      phase: playing ? 'playing' : 'lobby',
      players: Array.isArray(msg.players) ? msg.players : null,
      round: song === null ? null : { index: song.index, total: song.total },
      board: scores,
      score: own === undefined ? null : own.score,
    })
    if (song === null) return
    const late = typeof song.startAt === 'number' ? { startAt: song.startAt, deadlineAt: song.deadlineAt } : null
    const r = newRound(song.index, false, late)
    round = r
    soundCheck()
    load(r, song.audioUrl)
    lateStart(r)
  }

  // joined, then a snapshot of a playing game: watch its current song.
  // On a rejoin socket the snapshot rebuilds instead.
  function onSnapshot(msg) {
    if (!room || !joined) return
    if (rejoined) {
      onRejoinSnapshot(msg)
      return
    }
    if (msg.phase !== 'playing') return
    inGame = true
    resetSkips()
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
    // Only the count: the own vote follows clicks and tagged refusals.
    dispatch({ type: 'skipState', votes: msg.votes, needed: msg.needed })
  }

  // A lobby roster while a game is on and before its gameOver: the game was
  // abandoned (in practice only a watcher gets this, spec 048 review H2).
  function abandon() {
    inGame = false
    resetSkips()
    dropRound()
    dispatch({ type: 'abandoned' })
  }

  function onReveal(msg) {
    stopTimer()
    if (round !== null) {
      round.revealed = true
      round.provisional = false
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
    // Queued broadcasts may reach a rejoin socket before joined (spec 049 H1).
    if (awaitingSnapshot && !BEFORE_SNAPSHOT.has(msg.type)) return
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
        roomCode = msg.code
        if (rejoined) {
          // Back in the room: the reconnect ends; the snapshot shows the rest.
          cancelReconnect()
          dispatch({ type: 'rejoined', code: msg.code, name: msg.name })
        } else {
          dispatch({ type: 'joined', code: msg.code, name: msg.name })
        }
        if (resyncTimer === null) {
          sync.burst(JOIN_PINGS)
          resyncTimer = setInterval(() => sync.burst(RESYNC_PINGS), RESYNC_MS)
        }
        break
      case 'pong':
        if (sync === null) break
        sync.onPong(msg)
        if (round !== null) {
          lateStart(round)
          correctStart(round)
        }
        break
      case 'snapshot':
        onSnapshot(msg)
        break
      case 'token':
        // Private to this session; solo never reconnects.
        if (!room) break
        token = msg.token
        storeRejoin({ token, code: roomCode })
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
        onError(msg.code, msg.for)
        break
      default:
        console.warn('unknown message', msg.type)
    }
  }

  // Stops everything the session runs: reconnect, timer, sync and sound.
  function shutdown() {
    active = false
    cancelReconnect()
    stopTimer()
    stopSync()
    stopSource()
  }

  // Closing on purpose: handlers are cleared first, so the old socket's
  // events never reach the screen. A connecting attempt is closed the same
  // way, and the rejoin entry goes with the session.
  function close() {
    if (!active) return
    shutdown()
    detach(socket)
    socket.close()
    clearRejoin()
  }

  // An unexpected close with a token: the screen stays, the sound and the
  // timer stop, the join is forgotten and new sockets follow until the limit.
  function beginReconnect() {
    reconnecting = true
    rejoined = true
    awaitingSnapshot = true
    joined = false
    resetSkips()
    attempts = 0
    dropRound()
    stopSync()
    // The next joined starts a fresh clock sync.
    sync.clear()
    dispatch({ type: 'reconnecting' })
    limitTimer = setTimeout(giveUp, RECONNECT_LIMIT_MS)
    scheduleAttempt()
  }

  function scheduleAttempt() {
    const wait = RECONNECT_WAITS_MS[Math.min(attempts, RECONNECT_WAITS_MS.length - 1)]
    attempts += 1
    retryTimer = setTimeout(openAttempt, wait)
  }

  function openAttempt() {
    retryTimer = null
    const s = socketFactory(url)
    socket = s
    attach(s)
    // No joined within 5 s, connecting or open: the attempt has failed.
    attemptTimer = setTimeout(() => {
      attemptTimer = null
      detach(s)
      s.close()
      scheduleAttempt()
    }, ATTEMPT_MS)
  }

  // 30 s after the drop the server's grace is over.
  function giveUp() {
    limitTimer = null
    shutdown()
    detach(socket)
    socket.close()
    clearRejoin()
    dispatch({ type: 'disconnected' })
  }

  function onSocketClose(s, event) {
    if (!active || s !== socket) return
    detach(s)
    if (event !== undefined && event !== null && event.code === REPLACED_CODE) {
      // Replaced by another tab: no reconnect.
      shutdown()
      clearRejoin()
      dispatch({ type: 'replaced' })
      return
    }
    if (reconnecting) {
      // A failed attempt: the next wait applies.
      if (attemptTimer !== null) {
        clearTimeout(attemptTimer)
        attemptTimer = null
      }
      scheduleAttempt()
      return
    }
    if (room && token !== null) {
      beginReconnect()
      return
    }
    // A solo session is already closed after gameOver, and a room without a
    // token cannot rejoin, so this close ends the session.
    shutdown()
    dispatch({ type: 'disconnected' })
  }

  function attach(s) {
    s.onopen = () => {
      if (s !== socket) return
      if (rejoined) send({ type: 'rejoin', token })
      else if (mode === 'solo') send({ type: 'create', nickname, songCount })
      else if (mode === 'create') send({ type: 'create', nickname })
      else send({ type: 'join', code, nickname })
    }
    s.onmessage = (event) => {
      if (s !== socket) return
      let msg
      try {
        msg = JSON.parse(event.data)
      } catch (err) {
        console.warn('unreadable server message', err)
        return
      }
      onMessage(msg)
    }
    s.onclose = (event) => onSocketClose(s, event)
    // The browser fires close after error; the close handler reports it.
    s.onerror = () => {
      console.warn('WebSocket error')
    }
  }

  attach(socket)

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
      skipsSent += 1
      dispatchOwnSkip()
    },
    // Leaves the room on purpose: the server is told, then the socket closes.
    leave() {
      if (active) send({ type: 'leave' })
      close()
    },
    close,
  }
}
