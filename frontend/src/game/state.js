// What the screen shows. All session side effects (socket, audio, timer)
// live in session.js; this reducer only turns its events into display state.

// The server's error codes with a text in the dictionary (error.<code>).
const ERROR_CODES = new Set([
  'bad-nickname',
  'bad-song-count',
  'not-enough-songs',
  'too-long',
  'room-not-found',
  'room-full',
  'in-game',
  'not-host',
  'server-full',
  'room-closed',
  'rejoin-failed',
  'bad-filter',
  'bad-room-theme',
  'rate-limited',
  'bad-message',
  'too-large',
  'already-in-room',
  'no-room',
  'bad-answer-mode',
])

// The lobby notice after a game ended without gameOver (F-005).
export const ABANDONED_NOTICE = { key: 'lobby.abandoned' }

// The song counts a game can have (D-047).
export const SONG_COUNTS = Array.from({ length: 26 }, (_, i) => i + 5)

// The text of an error code as { key, values }, translated when shown (see
// messageText in i18n). message is the whole error message, when there is
// one: not-enough-songs names the server's count of drawable songs (F-008).
export function errorText(code, message) {
  if (code === 'not-enough-songs' && message !== undefined && typeof message.available === 'number') {
    // Easy counts series, not songs (cycle 094 unit: "series").
    const key = message.unit === 'series' ? 'error.availableSeries' : 'error.available'
    return { key, values: { available: message.available } }
  }
  if (ERROR_CODES.has(code)) return { key: `error.${code}` }
  return { key: 'error.unknown', values: { code: String(code) } }
}

// screen: 'start' | 'lobby' | 'game' | 'over' | 'disconnected'
export const initialState = {
  screen: 'start',
  startError: null,
  room: null, // { code, me } once joined; null before
  players: [], // the last roster: { name, score, host, connected }
  lobbyOpen: false, // the room is back in its lobby after a game
  notice: null, // a lobby notice, such as ABANDONED_NOTICE

  round: null, // { index, total }
  load: null, // 'loading' | 'loaded' | 'failed'
  timer: null, // { max, remaining }
  chat: [], // { id, name, body, text }, or a system line { id, kind: 'system', winner, placed }
  chatSeq: 0,
  correct: null,
  reveal: null,
  score: null,
  error: null,
  finalScore: null,
  scoreboard: null, // the last scores rows { name, score }
  ranking: null, // the gameOver rows { rank, name, score }
  watching: false, // joined mid-song: watching it, playing from the next
  started: false, // the song is between its start and its reveal
  // The local clock has reached the started song's startAt; set by every
  // start and tick, read only together with started (087).
  reached: false,
  skipVoted: false, // the own skip vote in this song: clicks minus tagged refusals, odd
  skipCount: null, // { votes, needed } from skipState for this song
  soundBlocked: false, // the AudioContext is not running: show the sound prompt
  reconnecting: false, // the connection dropped and a rejoin is in progress
  waitingNext: false, // rejoined between songs: waiting for the next prepare
  replaced: false, // the 'disconnected' screen: replaced by another tab
  settings: null, // the applied filters { types, yearFrom, yearTo, tier, answerMode } of this game
  roomTheme: null, // the room's look id from the server; null: none received
  // A choice room's song (cycle 100): the cover URL per position (null for
  // a cover the client refused), or null without choices. Kept from prepare
  // to the next prepare, so the grid stays under the reveal card.
  choices: null,
  picked: null, // the own pick's position this song, or null
  pickWrong: false, // the server said the own pick was wrong (pick-result)
  correctChoice: null, // the reveal's correct position, or null
}

// The per-song choice fields reset at a new song (or none).
function choiceFields(action) {
  return {
    choices: action.choices ?? null,
    picked: action.picked ?? null,
    pickWrong: action.pickWrong === true,
    correctChoice: null,
  }
}

export function reducer(state, action) {
  switch (action.type) {
    case 'connecting':
      // A reload rejoin (cycle 079) shows the reconnecting banner meanwhile.
      return { ...initialState, screen: 'game', reconnecting: action.rejoin === true }
    case 'startError':
      return { ...initialState, startError: action.text }
    case 'reset':
      return initialState
    case 'joined':
      return { ...state, screen: 'lobby', room: { code: action.code, me: action.name }, error: null, notice: null }
    case 'roster':
      return {
        ...state,
        players: action.players,
        lobbyOpen: action.phase === 'lobby' && state.screen === 'over' ? true : state.lobbyOpen,
      }
    case 'roomTheme':
      return { ...state, roomTheme: action.theme }
    case 'toLobby':
      return { ...state, screen: 'lobby', error: null, settings: null }
    case 'settings':
      // Sent before the first prepare, so prepare never clears it; the
      // actions that end or replace a game do (spec 061 review B1).
      return {
        ...state,
        settings: {
          types: action.types,
          yearFrom: action.yearFrom,
          yearTo: action.yearTo,
          tier: action.tier,
          answerMode: action.answerMode,
        },
      }
    case 'prepare': {
      // A new game (index 0) starts from a clean slate (D-062).
      const fresh =
        action.index === 0
          ? { chat: [], score: null, finalScore: null, lobbyOpen: false, scoreboard: null, ranking: null }
          : {}
      return {
        ...state,
        ...fresh,
        screen: 'game',
        round: { index: action.index, total: action.total },
        load: 'loading',
        timer: null,
        correct: null,
        reveal: null,
        error: null,
        notice: null,
        watching: false,
        waitingNext: false,
        started: false,
        skipVoted: false,
        skipCount: null,
        ...choiceFields(action),
      }
    }
    case 'reconnecting':
      // The screen keeps its content; the timer is frozen by the session.
      return { ...state, reconnecting: true }
    case 'rejoined':
      // The server sends roomTheme after the join sequence only when it is
      // not play, so a theme seen before the drop may be stale.
      return { ...state, reconnecting: false, room: { code: action.code, me: action.name }, error: null, roomTheme: null }
    case 'restore': {
      // A rejoin snapshot: the lobby, or the game as a player (spec 049).
      const common = {
        ...state,
        reconnecting: false,
        settings: null,
        roomTheme: null,
        players: action.players === null ? state.players : action.players,
        scoreboard: action.board === null ? state.scoreboard : action.board,
        score: action.score === null ? state.score : action.score,
        timer: null,
        correct: null,
        reveal: null,
        error: null,
        notice: null,
        watching: false,
        started: false,
        skipVoted: false,
        skipCount: null,
        ...choiceFields(action),
      }
      if (action.phase === 'lobby') {
        return { ...common, screen: 'lobby', round: null, load: null, waitingNext: false, lobbyOpen: false }
      }
      return {
        ...common,
        screen: 'game',
        round: action.round,
        load: action.round === null ? null : 'loading',
        waitingNext: action.round === null,
      }
    }
    case 'watching':
      // round is null when the snapshot has no song (paused or revealing).
      return {
        ...state,
        screen: 'game',
        round: action.round,
        load: action.round === null ? null : 'loading',
        timer: null,
        correct: null,
        reveal: null,
        error: null,
        notice: null,
        settings: null,
        watching: true,
        started: false,
        skipVoted: false,
        skipCount: null,
        ...choiceFields(action),
      }
    case 'abandoned':
      return {
        ...state,
        screen: 'lobby',
        timer: null,
        error: null,
        lobbyOpen: false,
        notice: ABANDONED_NOTICE,
        settings: null,
        watching: false,
        started: false,
        skipVoted: false,
        skipCount: null,
        ...choiceFields({}),
      }
    case 'pick':
      // Sent: every tile waits for the server (cycle 100).
      return { ...state, picked: action.choice, pickWrong: false }
    case 'pickRefused':
      // error{for:'pick'}: the pick was not counted; the tiles work again.
      return { ...state, picked: null, pickWrong: false }
    case 'pickWrong':
      return { ...state, picked: action.choice, pickWrong: true }
    case 'ownSkip':
      return { ...state, skipVoted: action.voted }
    case 'skipState':
      return { ...state, skipCount: { votes: action.votes, needed: action.needed } }
    case 'sound':
      return { ...state, soundBlocked: action.blocked }
    case 'loaded':
      return { ...state, load: 'loaded' }
    case 'loadFailed':
      return { ...state, load: 'failed' }
    case 'start':
      return {
        ...state,
        started: true,
        reached: action.reached === true,
        timer: { max: action.max, remaining: action.remaining },
      }
    case 'tick':
      if (state.timer === null) return state
      return { ...state, reached: action.reached === true, timer: { ...state.timer, remaining: action.remaining } }
    case 'chat': {
      const line = { id: state.chatSeq, name: action.name, body: action.text, text: `${action.name}: ${action.text}` }
      // The winner's first chat after correct (D-094) goes just above the
      // system line, which is re-numbered so ids keep the log order.
      const last = state.chat[state.chat.length - 1]
      if (last !== undefined && last.kind === 'system' && !last.placed && last.winner === action.name) {
        return {
          ...state,
          chat: [...state.chat.slice(0, -1), line, { ...last, id: state.chatSeq + 1, placed: true }],
          chatSeq: state.chatSeq + 2,
        }
      }
      return { ...state, chat: [...state.chat, line], chatSeq: state.chatSeq + 1 }
    }
    case 'correct':
      // A system line in the log (D-094). It has no name, so no seat bubble;
      // its text is made when shown, in the current language.
      return {
        ...state,
        correct: action.name,
        chat: [
          ...state.chat,
          { id: state.chatSeq, kind: 'system', winner: action.name, placed: false },
        ],
        chatSeq: state.chatSeq + 1,
      }
    case 'reveal':
      return {
        ...state,
        timer: null,
        reveal: action.reveal,
        started: false,
        skipCount: null,
        correctChoice: action.correctChoice ?? null,
      }
    case 'scores':
      // score is null without an own row.
      return {
        ...state,
        score: action.score === null ? state.score : action.score,
        scoreboard: action.board === null ? state.scoreboard : action.board,
      }
    case 'error':
      return { ...state, error: action.text }
    case 'gameOver':
      return {
        ...state,
        screen: 'over',
        timer: null,
        started: false,
        finalScore: action.score,
        ranking: action.ranking,
        settings: null,
      }
    case 'disconnected':
      return { ...state, screen: 'disconnected', timer: null, reconnecting: false, replaced: false }
    case 'replaced':
      return { ...state, screen: 'disconnected', timer: null, reconnecting: false, replaced: true }
    default:
      throw new Error(`unknown action ${action.type}`)
  }
}
