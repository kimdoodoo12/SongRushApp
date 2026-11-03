// One session over one WebSocket, solo or in a room. It sends the opening
// message, loads and schedules each song, runs the remaining-time timer and
// reports everything to the screen through dispatch. All server messages are
// handled in onMessage.
//
// mode 'solo' sends create{nickname, songCount}, ignores joined and roster,
// and closes the socket at gameOver. mode 'create' and 'join' are room
// sessions: they open a lobby with create{nickname} or join{code, nickname},
// stay open across games, and find the player's own rows by the name that
// joined assigned.
import { planPlayback, loadTrack } from '../audio/playback.js'
import { errorText } from './state.js'

const TICK_MS = 250

export function startSession({ socketFactory, url, ctx, fetchFn, now, mode, nickname, songCount, code, dispatch }) {
  const socket = socketFactory(url)
  const room = mode !== 'solo'
  // false once this session is closed; late callbacks then do nothing.
  let active = true
  let prepared = false
  // Room sessions: whether joined has arrived, and the assigned name.
  let joined = false
  let myName = null
  // The current song: { index, revealed, buffer, start, source }.
  let round = null
  let timer = null

  function send(message) {
    socket.send(JSON.stringify(message))
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
    const plan = planPlayback({ ...r.start, nowMs: now(), ctxTime: ctx.currentTime })
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

  function onPrepare(msg) {
    const r = { index: msg.index, revealed: false, buffer: null, start: null, source: null }
    round = r
    prepared = true
    dispatch({ type: 'prepare', index: msg.index, total: msg.total })
    const current = () => active && round === r && !r.revealed
    loadTrack(ctx, msg.audioUrl, fetchFn).then(
      (buffer) => {
        if (!current()) return
        r.buffer = buffer
        dispatch({ type: 'loaded' })
        send({ type: 'ready', index: r.index })
        schedule(r)
      },
      (err) => {
        if (!current()) return
        console.warn('audio load failed twice', err)
        dispatch({ type: 'loadFailed' })
        // Ready anyway, so the game is not held up.
        send({ type: 'ready', index: r.index })
      },
    )
  }

  function onStart(msg) {
    if (round === null || msg.index !== round.index) return
    const r = round
    r.start = { startAt: msg.startAt, deadlineAt: msg.deadlineAt, offsetMs: msg.serverTime - now() }
    dispatch({ type: 'start', max: msg.deadlineAt - msg.startAt, remaining: remaining(r.start) })
    stopTimer()
    timer = setInterval(() => dispatch({ type: 'tick', remaining: remaining(r.start) }), TICK_MS)
    schedule(r)
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
        if (row !== undefined) dispatch({ type: 'scores', score: row.score })
        break
      }
      case 'gameOver': {
        const row = ownRow(msg.ranking)
        // Solo: the server's solo lobby empties once this socket closes, so
        // nothing (such as room-closed) can reach the final-score screen.
        if (!room) close()
        stopTimer()
        dispatch({ type: 'gameOver', score: row === undefined ? null : row.score })
        break
      }
      case 'joined':
        // The server runs solo games in a lobby too; solo ignores it.
        if (!room) break
        joined = true
        myName = msg.name
        dispatch({ type: 'joined', code: msg.code, name: msg.name })
        break
      case 'roster':
        if (!room) break
        dispatch({ type: 'roster', phase: msg.phase, players: msg.players })
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
    // Leaves the room on purpose: the server is told, then the socket closes.
    leave() {
      if (active) send({ type: 'leave' })
      close()
    },
    close,
  }
}
