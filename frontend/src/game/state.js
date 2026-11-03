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
}

// The song counts a game can have (D-047).
export const SONG_COUNTS = Array.from({ length: 26 }, (_, i) => i + 5)

export function errorText(code) {
  return ERROR_TEXT[code] ?? `오류가 발생했습니다 (${code})`
}

// screen: 'start' | 'lobby' | 'game' | 'over' | 'disconnected'
export const initialState = {
  screen: 'start',
  startError: null,
  room: null, // { code, me } once joined; null in solo play
  players: [], // the last roster: { name, score, host, connected }
  lobbyOpen: false, // the room is back in its lobby after a game

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
      return { ...state, screen: 'lobby', room: { code: action.code, me: action.name }, error: null }
    case 'roster':
      return {
        ...state,
        players: action.players,
        lobbyOpen: action.phase === 'lobby' && state.screen === 'over' ? true : state.lobbyOpen,
      }
    case 'toLobby':
      return { ...state, screen: 'lobby', error: null }
    case 'prepare': {
      // A new game (index 0) starts from a clean slate (D-062).
      const fresh = action.index === 0 ? { chat: [], score: null, finalScore: null, lobbyOpen: false } : {}
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
      }
    }
    case 'loaded':
      return { ...state, load: 'loaded' }
    case 'loadFailed':
      return { ...state, load: 'failed' }
    case 'start':
      return { ...state, timer: { max: action.max, remaining: action.remaining } }
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
      return { ...state, timer: null, reveal: action.reveal }
    case 'scores':
      return { ...state, score: action.score }
    case 'error':
      return { ...state, error: action.text }
    case 'gameOver':
      return { ...state, screen: 'over', timer: null, finalScore: action.score }
    case 'disconnected':
      return { ...state, screen: 'disconnected', timer: null }
    default:
      throw new Error(`unknown action ${action.type}`)
  }
}
