// One solo game over one WebSocket: sends create, loads and schedules each
// song, runs the remaining-time timer and reports everything to the screen
// through dispatch. All server messages are handled in onMessage.
import { planPlayback, loadTrack } from '../audio/playback.js'
import { errorText } from './state.js'

const TICK_MS = 250

export function startSession({ socketFactory, url, ctx, fetchFn, now, nickname, songCount, dispatch }) {
  const socket = socketFactory(url)
  // false once this session is closed; late callbacks then do nothing.
  let active = true
  let prepared = false
  let over = false
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
      case 'scores':
        // Solo: one row, and the server's name may differ from the input.
        dispatch({ type: 'scores', score: msg.scores[0].score })
        break
      case 'gameOver':
        over = true
        stopTimer()
        dispatch({ type: 'gameOver', score: msg.ranking[0].score })
        break
      case 'error':
        if (!prepared) {
          // A start-screen error: back to the start screen with the message.
          close()
          dispatch({ type: 'startError', text: errorText(msg.code) })
        } else {
          dispatch({ type: 'error', text: errorText(msg.code) })
        }
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
    send({ type: 'create', nickname, songCount })
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
    // After gameOver the server may drop the socket; nothing is lost then.
    if (!over) dispatch({ type: 'disconnected' })
  }
  // The browser fires close after error; the close handler reports it.
  socket.onerror = () => {
    console.warn('WebSocket error')
  }

  return {
    sendChat(text) {
      if (active) send({ type: 'chat', text })
    },
    close,
  }
}
