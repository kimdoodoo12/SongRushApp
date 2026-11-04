// What the screen shows. All session side effects (socket, audio, timer)
// live in session.js; this reducer only turns its events into display state.

const ERROR_TEXT = {
  'bad-nickname': '닉네임은 1~16자로 입력해 주세요.',
  'bad-song-count': '곡 수는 5~30곡입니다.',
  'not-enough-songs': '출제할 곡이 부족합니다.',
  'too-long': '채팅은 200자까지 입력할 수 있습니다.',
  'room-not-found': '방을 찾을 수 없습니다.',
  'room-full': '방이 가득 찼습니다 (최대 8명).',
  'in-game': '게임이 진행 중입니다. 끝난 뒤에 참가해 주세요.',
  'not-host': '방장만 시작할 수 있습니다.',
  'server-full': '서버가 가득 찼습니다. 잠시 후 다시 시도해 주세요.',
  'room-closed': '방이 닫혔습니다.',
  'rejoin-failed': '다시 연결하지 못했습니다. 방에 다시 참가해 주세요.',
  'bad-filter': '조건이 올바르지 않습니다.',
}

// The lobby notice after a game ended without gameOver (F-005).
export const ABANDONED_TEXT = '게임이 중단되어 대기실로 돌아왔습니다.'

// The song counts a game can have (D-047).
export const SONG_COUNTS = Array.from({ length: 26 }, (_, i) => i + 5)

// message is the whole error message, when there is one: not-enough-songs
// names the server's count of drawable songs (F-008).
export function errorText(code, message) {
  if (code === 'not-enough-songs' && message !== undefined && typeof message.available === 'number') {
    return `출제할 수 있는 곡이 ${message.available}곡뿐입니다.`
  }
  return ERROR_TEXT[code] ?? `오류가 발생했습니다 (${code})`
}

// screen: 'start' | 'lobby' | 'game' | 'over' | 'disconnected'
export const initialState = {
  screen: 'start',
  startError: null,
  room: null, // { code, me } once joined; null in solo play
  players: [], // the last roster: { name, score, host, connected }
  lobbyOpen: false, // the room is back in its lobby after a game
  notice: null, // a lobby notice, such as ABANDONED_TEXT

  round: null, // { index, total }
  load: null, // 'loading' | 'loaded' | 'failed'
  timer: null, // { max, remaining }
  chat: [], // { id, text }
  chatSeq: 0,
  correct: null,
  reveal: null,
  score: null,
  error: null,
  finalScore: null,
  scoreboard: null, // room only: the last scores rows { name, score }
  ranking: null, // room only: the gameOver rows { rank, name, score }
  watching: false, // joined mid-song: watching it, playing from the next
  started: false, // the song is between its start and its reveal
  skipVoted: false, // the own skip vote in this song: clicks minus tagged refusals, odd
  skipCount: null, // { votes, needed } from skipState for this song
  soundBlocked: false, // the AudioContext is not running: show 탭하여 소리 켜기
  reconnecting: false, // the connection dropped and a rejoin is in progress
  waitingNext: false, // rejoined between songs: waiting for the next prepare
  replaced: false, // the 'disconnected' screen: replaced by another tab
  settings: null, // the applied filters { types, yearFrom, yearTo, tier } of this game
}

export function reducer(state, action) {
  switch (action.type) {
    case 'connecting':
      return { ...initialState, screen: 'game' }
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
    case 'toLobby':
      return { ...state, screen: 'lobby', error: null, settings: null }
    case 'settings':
      // Sent before the first prepare, so prepare never clears it; the
      // actions that end or replace a game do (spec 061 review B1).
      return {
        ...state,
        settings: { types: action.types, yearFrom: action.yearFrom, yearTo: action.yearTo, tier: action.tier },
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
      }
    }
    case 'reconnecting':
      // The screen keeps its content; the timer is frozen by the session.
      return { ...state, reconnecting: true }
    case 'rejoined':
      return { ...state, reconnecting: false, room: { code: action.code, me: action.name }, error: null }
    case 'restore': {
      // A rejoin snapshot: the lobby, or the game as a player (spec 049).
      const common = {
        ...state,
        reconnecting: false,
        settings: null,
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
      }
    case 'abandoned':
      return {
        ...state,
        screen: 'lobby',
        timer: null,
        error: null,
        lobbyOpen: false,
        notice: ABANDONED_TEXT,
        settings: null,
        watching: false,
        started: false,
        skipVoted: false,
        skipCount: null,
      }
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
      return { ...state, started: true, timer: { max: action.max, remaining: action.remaining } }
    case 'tick':
      if (state.timer === null) return state
      return { ...state, timer: { ...state.timer, remaining: action.remaining } }
    case 'chat':
      return {
        ...state,
        chat: [...state.chat, { id: state.chatSeq, text: `${action.name}: ${action.text}` }],
        chatSeq: state.chatSeq + 1,
      }
    case 'correct':
      return { ...state, correct: action.name }
    case 'reveal':
      return { ...state, timer: null, reveal: action.reveal, started: false, skipCount: null }
    case 'scores':
      // score is null without an own row; board is null in solo.
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
