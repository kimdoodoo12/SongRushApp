// What the screen shows. All session side effects (socket, audio, timer)
// live in session.js; this reducer only turns its events into display state.

const ERROR_TEXT = {
  'bad-nickname': '닉네임은 1~16자로 입력해 주세요.',
  'bad-song-count': '곡 수는 5~30곡입니다.',
  'not-enough-songs': '출제할 곡이 부족합니다.',
  'too-long': '채팅은 200자까지 입력할 수 있습니다.',
}

export function errorText(code) {
  return ERROR_TEXT[code] ?? `오류가 발생했습니다 (${code})`
}

// screen: 'start' | 'game' | 'over' | 'disconnected'
export const initialState = {
  screen: 'start',
  startError: null,
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
    case 'prepare':
      return {
        ...state,
        round: { index: action.index, total: action.total },
        load: 'loading',
        timer: null,
        correct: null,
        reveal: null,
        error: null,
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
