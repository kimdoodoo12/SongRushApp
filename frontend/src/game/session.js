// One room session over one WebSocket. It sends the opening message, loads
// and schedules each song, runs the remaining-time timer and reports
// everything to the screen through dispatch. All server messages are
// handled in onMessage.
//
// mode 'create' and 'join' open a lobby with create{nickname} or
// join{code, nickname} (the client has no solo mode, D-093); sessions
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
//
// mode 'rejoin' (cycle 079) is the first socket after a page reload: it
// sends rejoin{token} for the stored entry and is then a cycle 049 rejoin
// socket. rejoin-failed, a close before its first joined, or no joined
// within ATTEMPT_MS of its creation ends the session on the start screen
// with the rejoin-failed text; there is no retry ladder for this attempt.
// A create or join closed before its joined ends with a startError of the
// game.disconnected text (cycle 119): the nickname dialog shows it.
import { planPlayback, loadTrack } from '../audio/playback.js'
import { createClockSync } from '../net/clockSync.js'
import { errorText } from './state.js'

const TICK_MS = 250
// A reveal's cover path (cycle 097): the server's CoverMap.ID rule under
// /cover/. Anything else is dropped, so the client never loads another URL.
// The same rule applies to a choice's cover (cycle 100).
const COVER_URL = /^\/cover\/[A-Za-z0-9_-]{1,64}$/
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

// A frame's choices (cycle 100) as the list of their cover URLs, in order,
// or null when the frame has none. An entry whose coverUrl breaks the rule
// stays as null, so the positions still match the server's.
function choiceUrls(choices) {
  if (!Array.isArray(choices) || choices.length === 0) return null
  return choices.map((c) =>
    c !== null && typeof c === 'object' && typeof c.coverUrl === 'string' && COVER_URL.test(c.coverUrl) ? c.coverUrl : null,
  )
}

// The correct list of a ranked snapshot's song (cycle 101, R5) or of a
// ranked prepare (C2) as the names in rank order; [] for anything else, such
// as a first-come frame.
function correctNamesOf(frame) {
  if (frame === null || frame === undefined || !Array.isArray(frame.correct)) return []
  return frame.correct.filter((c) => c !== null && typeof c === 'object' && typeof c.name === 'string').map((c) => c.name)
}

// A ranked reveal's ranking (cycle 101 item 5) as { name, rank, points }
// rows, or null when the frame has none (first come).
function rankingRows(ranking) {
  if (!Array.isArray(ranking)) return null
  return ranking
    .filter(
      (r) =>
        r !== null && typeof r === 'object' && typeof r.name === 'string' && Number.isInteger(r.rank) && Number.isInteger(r.points),
    )
    .map((r) => ({ name: r.name, rank: r.rank, points: r.points }))
}

function isPosition(value, choices) {
  return choices !== null && Number.isInteger(value) && value >= 0 && value < choices.length
}

// A pickState frame's, or a snapshot song's, count (cycle 123, D-130) as
// { picked, total }, or null when the numbers are not a valid count.
function pickCountOf(value) {
  if (value === null || typeof value !== 'object') return null
  const { picked, total } = value
  if (!Number.isInteger(picked) || !Number.isInteger(total) || picked < 0 || picked > total || total < 1) return null
  return { picked, total }
}

function scheduleTimeout(fn, ms) {
  const id = setTimeout(fn, ms)
  return () => clearTimeout(id)
}

export function startSession({
  socketFactory,
  url,
  ctx,
  // The node every song source connects to: the context's one gain node,
  // which App owns (D-099); the session holds no volume state.
  output,
  fetchFn,
  now,
  mode,
  nickname,
  code,
  token: initialToken = null,
  dispatch,
}) {
  let socket = socketFactory(url)
  // false once this session is closed; late callbacks then do nothing.
  let active = true
  // Whether joined has arrived, and the assigned name.
  let joined = false
  let myName = null
  // The current song: { index, revealed, over, buffer, start, source,
  // watching, lateStart, provisional, choices, audioDone, imagesLeft,
  // readySent, windowTimer }. revealed: the answer is out (no pick, no
  // skip); over: the music and the clock are finished, so late audio, the
  // decode, the clock correction and the late start are ignored. A reveal
  // window (cycle 129) sets revealed only, and windowTimer ends it.
  // held: a prepare with holdMs whose prepare, loaded and loadFailed are not
  // dispatched yet (cycle 107); prepareAction is that prepare and outcome the
  // load's result ('loaded' or 'failed'), dispatched when the hold ends.
  // lateStart is a watched song's { startAt, deadlineAt }, kept until a
  // clock estimate exists. provisional: the start used a drifted receipt and
  // fresh pongs may move it earlier (spec 051); a new round (prepare,
  // snapshot, reconnect) starts without it. choices are the cover URLs of a
  // choice room's song (cycle 100); ready waits for the audio and for every
  // choice image to load or fail.
  let round = null
  // The round a held prepare replaced while its reveal window still plays
  // (cycle 129): its source and window-end timer run until the hold ends,
  // the next start, its playUntil, gameOver or a rejoin snapshot; or null.
  let prev = null
  // The own pick in a choice room (cycle 100): { index, choice, wrong }, or
  // null. It belongs to a song index, so a resume's re-prepare of the same
  // song keeps it (spec 100 A4); another index clears it.
  let ownPick = null
  // A game is on (prepare or a playing snapshot) and its
  // gameOver has not arrived; a lobby roster then means it was abandoned.
  let inGame = false
  // The own skip vote for the current song (spec 050): skipVote clicks sent
  // and the rate-limited errors tagged for:'skipVote'; an odd difference is
  // a vote. Reset with the song (prepare, snapshot, reconnect).
  let skipsSent = 0
  let skipsRefused = 0
  let timer = null
  // The hold of a prepare with holdMs during a reveal (cycle 107, D-119):
  // the round loads at once but the screen keeps the reveal until the timer
  // or the round's start ends it.
  let holdTimer = null
  const sync = createClockSync({ now, send, schedule: scheduleTimeout })
  let resyncTimer = null
  // Reconnect state: the last token and the room code; whether a rejoin is
  // in progress (from the drop until joined); whether the socket is a rejoin
  // socket, and whether its snapshot is still to come.
  // A reload rejoin (mode 'rejoin') starts as a rejoin socket with the
  // stored token; everJoined tells a failed first attempt from a later drop.
  const reloadRejoin = mode === 'rejoin'
  let token = reloadRejoin ? initialToken : null
  let roomCode = reloadRejoin ? code : null
  let reconnecting = false
  let rejoined = reloadRejoin
  let awaitingSnapshot = reloadRejoin
  let everJoined = false
  let attempts = 0
  let retryTimer = null
  let attemptTimer = null
  let limitTimer = null
  // The next song's covers loaded during a reveal (cycle 135, F-038), by
  // URL: { img, state, waiters }. state is 'loading' (waiters are the
  // callbacks to run when it settles), 'loaded' or 'failed'. The Image is
  // kept because /cover/ answers no-store. Cleared when the game ends.
  const covers = new Map()

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
    sync.stop()
  }

  // The sound prompt: shown while the context is not running.
  function soundCheck() {
    dispatch({ type: 'sound', blocked: ctx.state !== 'running' })
  }

  function outputLatency() {
    return Number.isFinite(ctx.outputLatency) ? ctx.outputLatency : 0
  }

  // Ready for the song; the link is idle now, so it re-syncs.
  function sendReady(index) {
    send({ type: 'ready', index })
    sync.burst(RESYNC_PINGS)
  }

  // The offset used at start: the synced estimate when there is one, else
  // the receipt offset. A receipt that drifts from the estimate re-syncs
  // from fresh samples, and the song uses the smaller of the two offsets,
  // which is never early (cycle 047 review M1). A drift that used the
  // receipt (a late frame or a forward clock jump) is provisional: fresh
  // pongs may still move it earlier (cycle 051).
  function startOffset(serverTime) {
    const receipt = serverTime - now()
    const estimate = sync.current()
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

  function clearWindow(r) {
    if (r.windowTimer !== null) {
      clearTimeout(r.windowTimer)
      r.windowTimer = null
    }
  }

  // A round's music ends: its window timer is cleared and its source, if it
  // still plays, stops. A round already over is left alone.
  function retire(r) {
    clearWindow(r)
    if (r.over) return
    r.over = true
    if (r.source !== null) r.source.stop()
  }

  function stopPrev() {
    if (prev === null) return
    retire(prev)
    prev = null
  }

  // The local time a reveal window ends, min(playUntil, deadlineAt), or null
  // when the reveal opens none: no start for this client yet, the music is
  // over, or playUntil is missing or not later than now.
  function windowEndOf(r, playUntil) {
    if (r.start === null || r.over) return null
    return windowEndFrom(r.start, playUntil)
  }

  // The same for a start's { deadlineAt, offsetMs }, also before the round
  // has begun it (a snapshot in a window, cycle 131).
  function windowEndFrom(start, playUntil) {
    if (typeof playUntil !== 'number' || !Number.isFinite(playUntil)) return null
    if (playUntil - start.offsetMs <= now()) return null
    const end = Math.min(playUntil, start.deadlineAt) - start.offsetMs
    return end > now() ? end : null
  }

  // The window's end (its playUntil): the tick and the source stop, and the
  // screen keeps the card. A round no longer current or previous is ignored.
  function endWindow(r) {
    r.windowTimer = null
    if (!active || r.over || (round !== r && prev !== r)) return
    stopTimer()
    retire(r)
    if (prev === r) prev = null
    dispatch({ type: 'windowEnd', index: r.index })
  }

  function remaining(start) {
    const max = start.deadlineAt - start.startAt
    const left = start.deadlineAt - start.offsetMs - now()
    return Math.min(max, Math.max(0, left))
  }

  // The local clock has reached the song's start (the server sends start
  // ahead of startAt). Display only; playback timing is planPlayback's.
  function reached(start) {
    return now() >= start.startAt - start.offsetMs
  }

  // Plays the round's buffer once both the buffer and the start time are
  // known, whichever comes last (a late decode plays from the current point).
  async function schedule(r) {
    if (r.buffer === null || r.start === null || r.source !== null || r.over) return
    if (ctx.state !== 'running') {
      try {
        await ctx.resume()
      } catch (err) {
        // Scheduling goes on; a context that stays suspended is silent.
        console.warn('AudioContext resume failed', err)
      }
    }
    if (!active || round !== r || r.over || r.source !== null) return
    const plan = planPlayback({ ...r.start, nowMs: now(), ctxTime: ctx.currentTime, outputLatency: outputLatency() })
    if (plan === null) return
    const source = ctx.createBufferSource()
    source.buffer = r.buffer
    source.connect(output)
    source.start(plan.when, plan.offset)
    source.stop(plan.stopAt)
    r.source = source
  }

  // The player's own row in scores or ranking, by the name joined assigned.
  function ownRow(rows) {
    return rows.find((row) => row.name === myName)
  }

  function resetSkips() {
    skipsSent = 0
    skipsRefused = 0
  }

  function dispatchOwnSkip() {
    dispatch({ type: 'ownSkip', voted: (skipsSent - skipsRefused) % 2 === 1 })
  }

  function onError(msg) {
    const code = msg.code
    const refused = msg.for
    // A refused pick. already-picked: the server holds an earlier pick of
    // this song (a resume's re-prepare keeps it, review M1), so the tiles
    // stay locked with no tile marked, since which one it holds is unknown.
    // Any other refusal (too-early) was not counted: the tiles work again.
    // The tiles are the feedback, so no error text is shown.
    if (refused === 'pick') {
      if (ownPick !== null && !ownPick.wrong && round !== null && ownPick.index === round.index) {
        if (code === 'already-picked') {
          ownPick = { index: round.index, choice: null, wrong: true }
          dispatch({ type: 'pickLocked' })
        } else {
          ownPick = null
          dispatch({ type: 'pickRefused' })
        }
      }
      return
    }
    // A refused skip vote was not counted (spec 050); an untagged error (a
    // chat refusal) never touches the vote.
    if (code === 'rate-limited' && refused === 'skipVote' && skipsRefused < skipsSent) {
      skipsRefused += 1
      dispatchOwnSkip()
    }
    const text = errorText(code, msg)
    if (!joined || code === 'room-closed') {
      // Back to the start screen with the message.
      close()
      dispatch({ type: 'startError', text })
    } else {
      dispatch({ type: 'error', text })
    }
  }

  function newRound(index, watching, lateStart, choices = null) {
    return {
      index,
      revealed: false,
      over: false,
      windowTimer: null,
      buffer: null,
      start: null,
      source: null,
      watching,
      lateStart,
      provisional: false,
      choices,
      audioDone: false,
      imagesLeft: 0,
      readySent: false,
      held: false,
      prepareAction: null,
      outcome: null,
      // A snapshot's song.reveal (cycle 131): the round was revealed before
      // this client had it; its window opens at the first clock estimate.
      snapshotReveal: null,
    }
  }

  // A played round sends ready once its audio has loaded (or failed, so the
  // game is not held up) and every choice image has loaded or failed. A
  // watched round, or a played one already running (a rejoin into PLAYING),
  // sends none.
  function maybeReady(r) {
    if (r.watching || r.lateStart !== null || r.readySent) return
    if (!r.audioDone || r.imagesLeft > 0) return
    r.readySent = true
    sendReady(r.index)
  }

  // Loads the round's choice covers before the start (spec 100 A3), so every
  // player can see them from the same moment. A failed image counts as
  // settled: its tile shows the empty placeholder.
  // A cover loaded during the reveal (cycle 135) does not count; one still
  // loading counts and settles with that load; a failed one loads again.
  function preloadChoices(r) {
    if (r.choices === null) return
    const current = () => active && round === r && !r.over
    for (const url of r.choices) {
      if (url === null) continue
      const stored = covers.get(url)
      if (stored !== undefined && stored.state === 'loaded') continue
      r.imagesLeft += 1
      if (stored !== undefined && stored.state === 'loading') {
        stored.waiters.push(() => {
          if (!current()) return
          r.imagesLeft -= 1
          maybeReady(r)
        })
        continue
      }
      const img = new Image()
      let settled = false
      const done = () => {
        if (settled) return
        settled = true
        img.onload = null
        img.onerror = null
        if (!current()) return
        r.imagesLeft -= 1
        maybeReady(r)
      }
      img.onload = done
      img.onerror = done
      img.src = url
    }
  }

  // A reveal's nextChoices (cycle 134): each valid cover not already loaded
  // or loading starts loading now, never shown, so the next prepare can use
  // it. A failed one is left to that prepare.
  function preloadNext(nextChoices) {
    const urls = choiceUrls(nextChoices)
    if (urls === null) return
    for (const url of urls) {
      if (url === null || covers.has(url)) continue
      const img = new Image()
      const entry = { img, state: 'loading', waiters: [] }
      const settle = (state) => () => {
        if (entry.state !== 'loading') return
        entry.state = state
        img.onload = null
        img.onerror = null
        const waiters = entry.waiters
        entry.waiters = []
        for (const fn of waiters) fn()
      }
      img.onload = settle('loaded')
      img.onerror = settle('failed')
      covers.set(url, entry)
      img.src = url
    }
  }

  // The game ended or was left: the stored covers and their waiters go.
  function clearCovers() {
    for (const entry of covers.values()) {
      entry.img.onload = null
      entry.img.onerror = null
      entry.waiters = []
    }
    covers.clear()
  }

  // Loads the round's audio and its choice images; ready follows maybeReady.
  function load(r, audioUrl) {
    const current = () => active && round === r && !r.over
    preloadChoices(r)
    loadTrack(ctx, audioUrl, fetchFn).then(
      (buffer) => {
        if (!current()) return
        r.buffer = buffer
        r.audioDone = true
        if (r.held) r.outcome = 'loaded'
        else dispatch({ type: 'loaded' })
        maybeReady(r)
        schedule(r)
      },
      (err) => {
        if (!current()) return
        console.warn('audio load failed twice', err)
        r.audioDone = true
        if (r.held) r.outcome = 'failed'
        else dispatch({ type: 'loadFailed' })
        maybeReady(r)
      },
    )
  }

  // The own pick as the screen shows it, for the song at index.
  function pickFields(index) {
    if (ownPick === null || ownPick.index !== index) return { picked: null, pickWrong: false, pickLocked: false }
    return { picked: ownPick.choice, pickWrong: ownPick.wrong, pickLocked: true }
  }

  function clearHold() {
    if (holdTimer !== null) {
      clearTimeout(holdTimer)
      holdTimer = null
    }
  }

  // The hold ends (its timer, or the round's start): the prepared round shows
  // as a normal prepare shows it, then the load's result if it is known.
  function endHold(r) {
    clearHold()
    if (!r.held) return
    r.held = false
    // The last song's window, if it still plays, ends with the hold.
    stopPrev()
    stopTimer()
    dispatch(r.prepareAction)
    soundCheck()
    if (r.outcome === 'loaded') dispatch({ type: 'loaded' })
    else if (r.outcome === 'failed') dispatch({ type: 'loadFailed' })
  }

  function onPrepare(msg) {
    clearHold()
    // A reveal is on screen: the last round was revealed, or is a held
    // round whose hold keeps the reveal still.
    const revealShown = round !== null && (round.revealed || round.held)
    const hold = revealShown && Number.isFinite(msg.holdMs) && msg.holdMs > 0
    // The last round's music (cycle 129): a held prepare leaves it playing
    // as prev until the hold ends; any other prepare stops it and its clock.
    stopPrev()
    if (round !== null) {
      if (hold) {
        prev = round
      } else {
        stopTimer()
        retire(round)
      }
    }
    const r = newRound(msg.index, false, null, choiceUrls(msg.choices))
    round = r
    inGame = true
    resetSkips()
    if (ownPick !== null && ownPick.index !== msg.index) ownPick = null
    // A ranked prepare may carry the names already correct this song (spec
    // 101 C2), also on a resume's re-prepare. With the own name in it this
    // player has answered: no more picks, and a held pick is not wrong.
    const correctNames = correctNamesOf(msg)
    if (myName !== null && correctNames.includes(myName)) {
      ownPick = { index: msg.index, choice: ownPick === null ? null : ownPick.choice, wrong: false }
    }
    const action = {
      type: 'prepare',
      index: msg.index,
      total: msg.total,
      choices: r.choices,
      correctNames,
      ...pickFields(msg.index),
    }
    if (hold) {
      r.held = true
      r.prepareAction = action
      // The skip button goes at once (cycle 130); the server ignores votes
      // after this prepare, although the old music plays on.
      dispatch({ type: 'held', index: msg.index })
      holdTimer = setTimeout(() => {
        holdTimer = null
        if (active && round === r) endHold(r)
      }, msg.holdMs)
    } else {
      dispatch(action)
      soundCheck()
    }
    load(r, msg.audioUrl)
  }

  // Starts the round's clock and timer; it plays once the buffer is there.
  function beginStart(r, start) {
    r.start = start
    dispatch({ type: 'start', max: start.deadlineAt - start.startAt, remaining: remaining(start), reached: reached(start) })
    soundCheck()
    stopTimer()
    stopPrev()
    timer = setInterval(() => dispatch({ type: 'tick', remaining: remaining(start), reached: reached(start) }), TICK_MS)
    schedule(r)
  }

  function onStart(msg) {
    // A song revealed before its start here (cycle 129, 8a) never starts.
    if (round === null || msg.index !== round.index || round.over) return
    if (round.held) endHold(round)
    const { offsetMs, provisional } = startOffset(msg.serverTime)
    round.provisional = provisional
    beginStart(round, { startAt: msg.startAt, deadlineAt: msg.deadlineAt, offsetMs })
  }

  // A provisional start moves earlier when a fresh pong's lower bound is
  // more than 20 ms above the offset in use; it never moves later, so it is
  // never early (spec 051). The ticks read the same start object.
  function correctStart(r) {
    if (!r.provisional || r.over || r.start === null) return
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
  // A snapshot taken in a reveal window (cycle 131) begins only while its
  // window is still open here, and then opens it; otherwise the round is over.
  function lateStart(r) {
    if (r.lateStart === null || r.start !== null || r.over) return
    const estimate = sync.current()
    if (estimate === null) return
    const start = { ...r.lateStart, offsetMs: estimate.offsetMs }
    if (r.snapshotReveal === null) {
      beginStart(r, start)
      return
    }
    const windowEnd = windowEndFrom(start, r.snapshotReveal.playUntil)
    if (windowEnd === null) {
      r.over = true
      return
    }
    beginStart(r, start)
    dispatch({ type: 'windowOpen', index: r.index })
    r.windowTimer = setTimeout(() => endWindow(r), windowEnd - now())
  }

  // A snapshot song's reveal (cycle 131), or null when it has none.
  function snapshotRevealOf(song) {
    return song.reveal !== null && typeof song.reveal === 'object' && !Array.isArray(song.reveal) ? song.reveal : null
  }

  // A snapshot in a reveal window (cycle 131): the round is revealed and the
  // card shows at once, with no window yet (lateStart opens it). The
  // snapshot's count is applied only now, so a newer skipState overrides it.
  // The own vote starts as not voted (the skips were reset).
  function showSnapshotReveal(r, reveal, skipState) {
    r.revealed = true
    r.snapshotReveal = reveal
    // Without a startAt nothing can play from the right point.
    if (r.lateStart === null) r.over = true
    dispatch(revealAction(reveal, r.choices, false))
    preloadNext(reveal.nextChoices)
    if (
      skipState !== null &&
      typeof skipState === 'object' &&
      Number.isInteger(skipState.votes) &&
      Number.isInteger(skipState.needed)
    ) {
      dispatch({ type: 'skipState', votes: skipState.votes, needed: skipState.needed })
    }
    dispatchOwnSkip()
  }

  // The current round ends: its source stops and late callbacks are ignored.
  // A held prepare's previous round (prev) is left to the rejoin snapshot,
  // gameOver or its own window end (cycle 129, criterion 7).
  function dropRound() {
    clearHold()
    stopTimer()
    if (round !== null) {
      const windowOpen = round.windowTimer !== null && !round.over
      clearWindow(round)
      round.revealed = true
      round.over = true
      stopSource()
      if (windowOpen) dispatch({ type: 'windowEnd', index: round.index })
      round = null
    }
  }

  // A snapshot on a rejoin socket rebuilds the screen from it, so a repeated
  // joined/snapshot pair gives the same result. Its song is a played round:
  // PLAYING starts late once a clock estimate exists, PREPARE sends ready.
  function onRejoinSnapshot(msg) {
    awaitingSnapshot = false
    clearHold()
    dropRound()
    stopPrev()
    resetSkips()
    const playing = msg.phase === 'playing'
    const song = playing && msg.song !== null && msg.song !== undefined ? msg.song : null
    const scores = Array.isArray(msg.scores) && msg.scores.length > 0 ? msg.scores : null
    const own = scores === null ? undefined : ownRow(scores)
    const choices = song === null ? null : choiceUrls(song.choices)
    const reveal = song === null ? null : snapshotRevealOf(song)
    // The server's record of the own pick this song (L3). In first come a
    // pick it holds was wrong, since a correct one ends the song; in ranked
    // the song goes on after a correct pick, so it was wrong only when
    // answered is false (cycle 101, R5). answered is absent in first come.
    const answered = song !== null && song.answered === true
    ownPick =
      song !== null && isPosition(song.picked, choices) ? { index: song.index, choice: song.picked, wrong: !answered } : null
    inGame = playing
    dispatch({
      type: 'restore',
      phase: playing ? 'playing' : 'lobby',
      players: Array.isArray(msg.players) ? msg.players : null,
      round: song === null ? null : { index: song.index, total: song.total },
      board: scores,
      score: own === undefined ? null : own.score,
      choices,
      correctNames: correctNamesOf(song),
      pickCount: song === null || choices === null ? null : pickCountOf(song.pickState),
      ...pickFields(song === null ? null : song.index),
      revealed: reveal !== null,
    })
    if (song === null) return
    const late = typeof song.startAt === 'number' ? { startAt: song.startAt, deadlineAt: song.deadlineAt } : null
    const r = newRound(song.index, false, late, choices)
    round = r
    if (reveal !== null) showSnapshotReveal(r, reveal, song.skipState)
    soundCheck()
    load(r, song.audioUrl)
    lateStart(r)
  }

  // joined, then a snapshot of a playing game: watch its current song.
  // On a rejoin socket the snapshot rebuilds instead.
  function onSnapshot(msg) {
    if (!joined) return
    if (rejoined) {
      onRejoinSnapshot(msg)
      return
    }
    if (msg.phase !== 'playing') return
    inGame = true
    resetSkips()
    ownPick = null
    const song = msg.song
    if (song === null || song === undefined) {
      round = null
      dispatch({ type: 'watching', round: null, choices: null, correctNames: [] })
      return
    }
    const late = typeof song.startAt === 'number' ? { startAt: song.startAt, deadlineAt: song.deadlineAt } : null
    const r = newRound(song.index, true, late, choiceUrls(song.choices))
    round = r
    const reveal = snapshotRevealOf(song)
    dispatch({
      type: 'watching',
      round: { index: song.index, total: song.total },
      choices: r.choices,
      correctNames: correctNamesOf(song),
      pickCount: r.choices === null ? null : pickCountOf(song.pickState),
      revealed: reveal !== null,
    })
    if (reveal !== null) showSnapshotReveal(r, reveal, song.skipState)
    soundCheck()
    load(r, song.audioUrl)
    lateStart(r)
  }

  function onSkipState(msg) {
    if (round === null || msg.index !== round.index) return
    // Only the count: the own vote follows clicks and tagged refusals.
    dispatch({ type: 'skipState', votes: msg.votes, needed: msg.needed })
  }

  // How many have picked this song (cycle 123, D-130): choice songs only.
  // The session's round decides, since the screen still holds the last song
  // while a prepare is held. Anything else is ignored, with no warning.
  function onPickState(msg) {
    if (round === null || msg.index !== round.index || round.choices === null) return
    const count = pickCountOf(msg)
    if (count !== null) dispatch({ type: 'pickState', ...count })
  }

  // A player picked wrong (cycle 123, D-130): a chat line with the name.
  function onWrong(msg) {
    if (round === null || msg.index !== round.index || round.choices === null) return
    if (typeof msg.name !== 'string' || msg.name === '') return
    dispatch({ type: 'pickWrongNotice', name: msg.name })
  }

  // A lobby roster while a game is on and before its gameOver: the game was
  // abandoned (in practice only a watcher gets this, spec 048 review H2).
  function abandon() {
    inGame = false
    ownPick = null
    resetSkips()
    clearHold()
    dropRound()
    stopPrev()
    clearCovers()
    dispatch({ type: 'abandoned' })
  }

  // pick-result means the own pick was wrong (spec 100 A11): no more picks
  // this song.
  function onPickResult(msg) {
    if (round === null || msg.index !== round.index || !isPosition(msg.choice, round.choices)) return
    ownPick = { index: round.index, choice: msg.choice, wrong: true }
    dispatch({ type: 'pickWrong', choice: msg.choice })
  }

  // The reveal action of a live reveal frame or of a snapshot's song.reveal
  // (cycle 131), with the same card fields; opens: a reveal window opens.
  function revealAction(msg, choices, opens) {
    return {
      type: 'reveal',
      window: opens,
      // The correct tile of a choice room (spec 100 item 6), or null.
      correctChoice: isPosition(msg.correctChoice, choices) ? msg.correctChoice : null,
      reveal: {
        title: msg.reveal,
        // The accepted titles per language, for the card's KO/EN/JA/Romaji lines.
        titles: msg.titles !== null && typeof msg.titles === 'object' ? msg.titles : {},
        themeSlug: msg.themeSlug,
        song: msg.song,
        artists: msg.artists ?? [],
        winner: msg.winner,
        // Ranked only (cycle 101): the song's ranking rows; null in first come.
        ranking: rankingRows(msg.ranking),
        coverUrl: typeof msg.coverUrl === 'string' && COVER_URL.test(msg.coverUrl) ? msg.coverUrl : null,
      },
    }
  }

  // A reveal whose playUntil is later than now, for a song this client has
  // started, opens a window (cycle 129, F-035): the source and the tick go
  // on until min(playUntil, deadlineAt). Any other reveal ends them now.
  function onReveal(msg) {
    const choices = round === null ? null : round.choices
    const r = round
    const windowEnd = r === null ? null : windowEndOf(r, msg.playUntil)
    if (r !== null) {
      r.revealed = true
      r.provisional = false
      clearWindow(r)
    }
    if (windowEnd === null) {
      stopTimer()
      if (r !== null) {
        r.over = true
        stopSource()
      }
    } else {
      r.windowTimer = setTimeout(() => endWindow(r), windowEnd - now())
    }
    dispatch(revealAction(msg, choices, windowEnd !== null))
    preloadNext(msg.nextChoices)
    // In a window the skip vote goes on (cycle 130): the server cleared the
    // votes cast before the reveal, so the own vote starts as not voted.
    if (windowEnd !== null) {
      resetSkips()
      dispatchOwnSkip()
    }
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
        // rank is set only in a ranked game (cycle 101).
        dispatch({ type: 'correct', name: msg.name, rank: Number.isInteger(msg.rank) ? msg.rank : undefined })
        break
      case 'reveal':
        onReveal(msg)
        break
      case 'pick-result':
        onPickResult(msg)
        break
      case 'scores': {
        const row = ownRow(msg.scores)
        dispatch({ type: 'scores', score: row === undefined ? null : row.score, board: msg.scores })
        break
      }
      case 'gameOver': {
        const row = ownRow(msg.ranking)
        stopTimer()
        clearHold()
        // A reveal window, current or held over, ends with the game.
        stopPrev()
        if (round !== null) retire(round)
        clearCovers()
        inGame = false
        ownPick = null
        dispatch({
          type: 'gameOver',
          score: row === undefined ? null : row.score,
          ranking: msg.ranking,
        })
        break
      }
      case 'joined':
        joined = true
        everJoined = true
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
        // Private to this session.
        token = msg.token
        storeRejoin({ token, code: roomCode })
        break
      case 'skipState':
        onSkipState(msg)
        break
      case 'pickState':
        onPickState(msg)
        break
      case 'wrong':
        onWrong(msg)
        break
      case 'settings':
        // The conditions the server applied to this game (F-008).
        dispatch({
          type: 'settings',
          types: msg.types,
          yearFrom: msg.yearFrom,
          yearTo: msg.yearTo,
          tier: msg.tier,
          answerMode: msg.answerMode,
          gameMode: msg.gameMode,
        })
        break
      case 'roster':
        dispatch({ type: 'roster', phase: msg.phase, players: msg.players })
        if (msg.phase === 'lobby' && inGame) abandon()
        break
      case 'roomTheme':
        // The look the host picked for the room (F-012).
        dispatch({ type: 'roomTheme', theme: msg.theme })
        break
      case 'lobbySettings':
        // The host's lobby selection, shown on a guest's card (cycle 116).
        dispatch({
          type: 'lobbySettings',
          songCount: msg.songCount,
          types: msg.types,
          yearFrom: msg.yearFrom,
          yearTo: msg.yearTo,
          tier: msg.tier,
          answerMode: msg.answerMode,
          gameMode: msg.gameMode,
        })
        break
      case 'error':
        onError(msg)
        break
      default:
        console.warn('unknown message', msg.type)
    }
  }

  // Stops everything the session runs: reconnect, timer, sync and sound.
  function shutdown() {
    active = false
    cancelReconnect()
    clearHold()
    stopTimer()
    stopSync()
    stopSource()
    if (round !== null) clearWindow(round)
    stopPrev()
    clearCovers()
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
    // The snapshot after the rejoin carries the server's record of the pick.
    ownPick = null
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

  // The reload rejoin never got in: back to the start screen. The handlers
  // are cleared first, so the socket's later events change nothing.
  function failReloadRejoin() {
    shutdown()
    detach(socket)
    socket.close()
    clearRejoin()
    dispatch({ type: 'startError', text: errorText('rejoin-failed') })
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
    if (reloadRejoin && !everJoined) {
      failReloadRejoin()
      return
    }
    if (!reloadRejoin && !everJoined) {
      // A create or join from the nickname dialog (cycle 119) that never got
      // in: the dialog stays open with the connection-failure text.
      shutdown()
      dispatch({ type: 'startError', text: { key: 'game.disconnected' } })
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
    if (token !== null) {
      beginReconnect()
      return
    }
    // A room without a token cannot rejoin, so this close ends the session.
    shutdown()
    dispatch({ type: 'disconnected' })
  }

  function attach(s) {
    s.onopen = () => {
      if (s !== socket) return
      if (rejoined) send({ type: 'rejoin', token })
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
  if (reloadRejoin) {
    // No joined within ATTEMPT_MS, connecting or open: the reload rejoin has
    // failed. joined clears it through cancelReconnect.
    attemptTimer = setTimeout(() => {
      attemptTimer = null
      failReloadRejoin()
    }, ATTEMPT_MS)
  }

  return {
    sendChat(text) {
      if (active) send({ type: 'chat', text })
    },
    startGame(count, startFields = {}) {
      if (active) send({ type: 'start', songCount: count, ...startFields })
    },
    // The host's lobby selection, with the fields of start (cycle 116); the
    // server shares it with every member.
    sendLobbySettings(count, fields = {}) {
      if (active) send({ type: 'lobbySettings', songCount: count, ...fields })
    },
    // Toggles the own skip vote for the current song, between its start and
    // its reveal, or the end of its reveal window (cycle 130). Watchers do
    // not vote.
    skipVote() {
      if (!active || round === null || round.watching || round.start === null || round.over) return
      send({ type: 'skipVote', index: round.index })
      skipsSent += 1
      dispatchOwnSkip()
    },
    // The own pick in a choice room (cycle 100): one per song, between the
    // song becoming audible and its reveal. Watchers do not pick.
    pick(choice) {
      if (!active || round === null || round.watching || round.start === null || round.revealed) return
      if (!isPosition(choice, round.choices) || !reached(round.start)) return
      if (ownPick !== null && ownPick.index === round.index) return
      ownPick = { index: round.index, choice, wrong: false }
      send({ type: 'pick', index: round.index, choice })
      dispatch({ type: 'pick', choice })
    },
    // Leaves the room on purpose: the server is told, then the socket closes.
    leave() {
      if (active) send({ type: 'leave' })
      close()
    },
    close,
  }
}
