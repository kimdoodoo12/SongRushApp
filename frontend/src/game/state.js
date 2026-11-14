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
  'bad-game-mode',
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
  // A nickname-dialog attempt (create or join, cycle 119) is connecting: the
  // start screen stays, under the dialog, until joined.
  joining: false,
  room: null, // { code, me } once joined; null before
  players: [], // the last roster: { name, score, host, connected }
  lobbyOpen: false, // the room is back in its lobby after a game
  notice: null, // a lobby notice, such as ABANDONED_NOTICE

  round: null, // { index, total }
  load: null, // 'loading' | 'loaded' | 'failed'
  timer: null, // { max, remaining }
  // { id, name, body, text, at? }, or a system line { id, kind: 'system',
  // winner, rank, placed }; rank is a number only in a ranked game (cycle
  // 101). at is the local arrival time in ms (the .tm, cycle 111), when the
  // dispatcher stamped one. A client-side notice (cycle 111, the mockup's
  // sys lines) is { id, kind: 'notice', key, values }, translated when shown.
  chat: [],
  chatSeq: 0,
  // The round index whose "<n>번째 곡 시작" notice is in the log, or null.
  announced: null,
  correct: null, // the first-come winner's name this song, or null
  // Ranked (cycle 101, R4): every name with a correct answer this song, in
  // rank order. correct stays null in a ranked game, so another player's
  // answer never shows the banner or locks the own tiles.
  correctNames: [],
  reveal: null,
  score: null,
  error: null,
  finalScore: null,
  scoreboard: null, // the last scores rows { name, score }
  ranking: null, // the gameOver rows { rank, name, score }
  watching: false, // joined mid-song: watching it, playing from the next
  started: false, // the song is between its start and its reveal, or the end of its reveal window
  // A reveal window is open (cycle 129, F-035): the revealed song plays on,
  // with its clock, until playUntil.
  windowOpen: false,
  // The local clock has reached the started song's startAt; set by every
  // start and tick, read only together with started (087).
  reached: false,
  skipVoted: false, // the own skip vote in this song: clicks minus tagged refusals, odd
  skipCount: null, // { votes, needed } from skipState for this song
  // A held prepare arrived in a reveal window (cycle 130): the skip button
  // is gone although the old music plays on until the hold ends.
  skipClosed: false,
  // A choice song's { picked, total } from pickState (cycle 123, D-130), or
  // null. start never changes it: it is shown once the song has started.
  pickCount: null,
  soundBlocked: false, // the AudioContext is not running: show the sound prompt
  reconnecting: false, // the connection dropped and a rejoin is in progress
  waitingNext: false, // rejoined between songs: waiting for the next prepare
  replaced: false, // the 'disconnected' screen: replaced by another tab
  settings: null, // the applied filters { types, yearFrom, yearTo, tier, answerMode, gameMode } of this game
  roomTheme: null, // the room's look id from the server; null: none received
  // The host's lobby selection from the last lobbySettings (cycle 116): {
  // songCount, types, yearFrom, yearTo, tier, answerMode, gameMode }, a
  // missing field undefined; null before any. A guest's lobby card shows it.
  lobbySettings: null,
  // Bumped by every rejoin snapshot into the lobby phase (cycle 116 round
  // 3): the server may have handed host to another member meanwhile, so a
  // host sends its selection again.
  lobbyRestores: 0,
  // A choice room's song (cycle 100): the cover URL per position (null for
  // a cover the client refused), or null without choices. Kept from prepare
  // to the next prepare, so the grid stays under the reveal card.
  choices: null,
  picked: null, // the own pick's position this song, or null
  pickWrong: false, // the server said the own pick was wrong (pick-result)
  // No more picks this song: a pick is sent or held by the server, also
  // when which tile it holds is unknown (already-picked, review M1).
  pickLocked: false,
  correctChoice: null, // the reveal's correct position, or null
}

// The per-song choice fields reset at a new song (or none).
function choiceFields(action) {
  return {
    choices: action.choices ?? null,
    picked: action.picked ?? null,
    pickWrong: action.pickWrong === true,
    pickLocked: action.pickLocked === true,
    correctChoice: null,
  }
}

// The song is audible (D-101, 087): a round is current and not revealed (or
// in its reveal window, cycle 129), started, the local clock past its
// startAt, loaded, and the sound not blocked.
export function isAudible(state) {
  return (
    state.screen === 'game' &&
    state.round !== null &&
    (state.reveal === null || state.windowOpen) &&
    state.started &&
    state.reached &&
    state.load !== 'loading' &&
    state.load !== 'failed' &&
    !state.soundBlocked
  )
}

// Appends a client-side notice line (cycle 111) to the log.
function withNotice(state, key, values) {
  return { ...state, chat: [...state.chat, { id: state.chatSeq, kind: 'notice', key, values }], chatSeq: state.chatSeq + 1 }
}

// The log on a way into the lobby (cycle 114): cleared, then the mockup's
// lobby line for that way, if it has one. ids keep counting up.
function lobbyLog(state, key, values) {
  const cleared = { ...state, chat: [], announced: null }
  return key === undefined ? cleared : withNotice(cleared, key, values)
}

// The log without the join line (chat.lobbyJoined): joined adds it before
// the snapshot says the phase, so a late joiner who goes to the game drops
// it (critic M1 of 114).
function withoutJoinLine(chat) {
  return chat.filter((line) => !(line.kind === 'notice' && line.key === 'chat.lobbyJoined'))
}

// The mockup's startRound line: "<n>번째 곡 시작" once per song, when the
// song becomes audible.
export function reducer(state, action) {
  const next = step(state, action)
  if (next === state || !isAudible(next) || next.announced === next.round.index) return next
  return { ...withNotice(next, 'chat.songStart', { n: next.round.index + 1 }), announced: next.round.index }
}

function step(state, action) {
  switch (action.type) {
    case 'connecting':
      // A nickname-dialog attempt (cycle 119) keeps the start screen until
      // joined; its startError shows in the dialog. A reload rejoin (cycle
      // 079) shows the game frame with the reconnecting banner meanwhile.
      if (action.dialog === true) return { ...initialState, joining: true }
      return { ...initialState, screen: 'game', reconnecting: action.rejoin === true }
    case 'startError':
      return { ...initialState, startError: action.text }
    case 'reset':
      return initialState
    case 'joined':
      return {
        ...lobbyLog(state, 'chat.lobbyJoined', { code: action.code }),
        screen: 'lobby',
        joining: false,
        room: { code: action.code, me: action.name },
        error: null,
        notice: null,
      }
    case 'roster':
      return {
        ...state,
        players: action.players,
        lobbyOpen: action.phase === 'lobby' && state.screen === 'over' ? true : state.lobbyOpen,
      }
    case 'roomTheme':
      return { ...state, roomTheme: action.theme }
    case 'lobbySettings':
      // Kept across a game and a rejoin: the server keeps the selection and
      // sends any change; only leaving the room (reset, connecting) clears it.
      return {
        ...state,
        lobbySettings: {
          songCount: action.songCount,
          types: action.types,
          yearFrom: action.yearFrom,
          yearTo: action.yearTo,
          tier: action.tier,
          answerMode: action.answerMode,
          gameMode: action.gameMode,
        },
      }
    case 'toLobby':
      return { ...lobbyLog(state, 'chat.lobbyBack'), screen: 'lobby', error: null, settings: null }
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
          gameMode: action.gameMode,
        },
      }
    case 'prepare': {
      // A new game (index 0) starts from a clean slate (D-062), with the
      // mockup's "게임을 시작해요" line as its first.
      const fresh =
        action.index === 0
          ? {
              chat: [{ id: state.chatSeq, kind: 'notice', key: 'chat.gameStart' }],
              chatSeq: state.chatSeq + 1,
              announced: null,
              score: null,
              finalScore: null,
              lobbyOpen: false,
              scoreboard: null,
              ranking: null,
            }
          : {}
      return {
        ...state,
        ...fresh,
        screen: 'game',
        round: { index: action.index, total: action.total },
        load: 'loading',
        timer: null,
        correct: null,
        // A ranked prepare may carry the names already correct (spec 101 C2).
        correctNames: action.correctNames ?? [],
        reveal: null,
        error: null,
        notice: null,
        watching: false,
        waitingNext: false,
        started: false,
        windowOpen: false,
        skipVoted: false,
        skipCount: null,
        skipClosed: false,
        pickCount: null,
        ...choiceFields(action),
      }
    }
    case 'reconnecting':
      // The screen keeps its content; the timer is frozen by the session.
      return { ...state, reconnecting: true }
    case 'rejoined':
      // The server sends roomTheme after the join sequence only when it is
      // not the default (sunset), so a theme seen before the drop may be stale.
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
        correctNames: action.correctNames ?? [],
        reveal: null,
        error: null,
        notice: null,
        watching: false,
        started: false,
        windowOpen: false,
        skipVoted: false,
        skipCount: null,
        skipClosed: false,
        // The snapshot's count (cycle 123), validated by the session.
        pickCount: action.pickCount ?? null,
        ...choiceFields(action),
        // A song whose answer is already out (cycle 131) gets no song-start line.
        announced: action.revealed === true && action.round !== null ? action.round.index : state.announced,
      }
      if (action.phase === 'lobby') {
        return {
          ...lobbyLog(common),
          screen: 'lobby',
          round: null,
          load: null,
          waitingNext: false,
          lobbyOpen: false,
          lobbyRestores: state.lobbyRestores + 1,
        }
      }
      return {
        ...common,
        chat: withoutJoinLine(state.chat),
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
        chat: withoutJoinLine(state.chat),
        screen: 'game',
        round: action.round,
        load: action.round === null ? null : 'loading',
        timer: null,
        correct: null,
        correctNames: action.correctNames ?? [],
        reveal: null,
        error: null,
        notice: null,
        settings: null,
        watching: true,
        started: false,
        windowOpen: false,
        skipVoted: false,
        skipCount: null,
        skipClosed: false,
        pickCount: action.pickCount ?? null,
        ...choiceFields(action),
        // A song whose answer is already out (cycle 131) gets no song-start line.
        announced: action.revealed === true && action.round !== null ? action.round.index : state.announced,
      }
    case 'abandoned':
      return {
        ...lobbyLog(state),
        screen: 'lobby',
        timer: null,
        error: null,
        lobbyOpen: false,
        notice: ABANDONED_NOTICE,
        settings: null,
        correctNames: [],
        watching: false,
        started: false,
        windowOpen: false,
        skipVoted: false,
        skipCount: null,
        pickCount: null,
        ...choiceFields({}),
      }
    case 'pick':
      // Sent: every tile waits for the server (cycle 100).
      return { ...state, picked: action.choice, pickWrong: false, pickLocked: true }
    case 'pickRefused':
      // error{for:'pick'}: the pick was not counted; the tiles work again.
      return { ...state, picked: null, pickWrong: false, pickLocked: false }
    case 'pickLocked':
      // error{already-picked, for:'pick'}: the server holds an earlier pick
      // of this song; which tile is unknown, so none is marked.
      return { ...state, picked: null, pickWrong: false, pickLocked: true }
    case 'pickWrong':
      return { ...state, picked: action.choice, pickWrong: true, pickLocked: true }
    case 'held':
      // A held prepare in a reveal window (cycle 130): only the skip button
      // goes; the clock and disc go on through the hold (cycle 129).
      return { ...state, skipClosed: true }
    case 'ownSkip':
      return { ...state, skipVoted: action.voted }
    case 'skipState':
      return { ...state, skipCount: { votes: action.votes, needed: action.needed } }
    case 'pickState':
      return { ...state, pickCount: { picked: action.picked, total: action.total } }
    case 'pickWrongNotice':
      // Another player's wrong pick, by name only (cycle 123, D-130): a
      // notice, not a system line, so the winner-chat ordering skips it.
      return withNotice(state, 'chat.wrongLine', { name: action.name })
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
      const line = {
        id: state.chatSeq,
        name: action.name,
        body: action.text,
        text: `${action.name}: ${action.text}`,
        ...(typeof action.at === 'number' ? { at: action.at } : {}),
      }
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
    case 'correct': {
      // Ranked (cycle 101): known from the settings or the frame's rank. The
      // song goes on, so correct stays null; the name joins correctNames and
      // the line carries the rank. Only the own echo of a chat room goes
      // above the own line (R3): for any other name, and in a choice room
      // where no echo follows a pick (C1), the line is placed already, so a
      // later chat appends below it.
      const ranked = typeof action.rank === 'number' || (state.settings !== null && state.settings.gameMode === 'ranked')
      if (ranked) {
        const choiceRoom = state.choices !== null || (state.settings !== null && state.settings.answerMode === 'choice')
        const echoFollows = !choiceRoom && state.room !== null && state.room.me === action.name
        return {
          ...state,
          correctNames: state.correctNames.includes(action.name) ? state.correctNames : [...state.correctNames, action.name],
          chat: [
            ...state.chat,
            {
              id: state.chatSeq,
              kind: 'system',
              winner: action.name,
              rank: typeof action.rank === 'number' ? action.rank : null,
              placed: !echoFollows,
            },
          ],
          chatSeq: state.chatSeq + 1,
        }
      }
      // A system line in the log (D-094). It has no name, so no seat bubble;
      // its text is made when shown, in the current language.
      return {
        ...state,
        correct: action.name,
        chat: [
          ...state.chat,
          { id: state.chatSeq, kind: 'system', winner: action.name, rank: null, placed: false },
        ],
        chatSeq: state.chatSeq + 1,
      }
    }
    case 'reveal': {
      // A reveal window (cycle 129) keeps the clock and started until its
      // windowEnd; any other reveal ends them now.
      const open = action.window === true
      return {
        ...state,
        timer: open ? state.timer : null,
        reveal: action.reveal,
        started: open ? state.started : false,
        windowOpen: open,
        skipCount: null,
        pickCount: null,
        correctChoice: action.correctChoice ?? null,
      }
    }
    case 'windowOpen':
      // A snapshot's reveal window opens at the first clock estimate (cycle
      // 131); the card, the count and the own vote stay as they are.
      if (state.round === null || state.round.index !== action.index || state.reveal === null) return state
      return { ...state, windowOpen: true }
    case 'windowEnd':
      // A late timer of another song never touches the current one.
      if (!state.windowOpen || state.round === null || state.round.index !== action.index) return state
      return { ...state, timer: null, started: false, windowOpen: false }
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
        ...withNotice(state, 'chat.gameOver'),
        screen: 'over',
        timer: null,
        started: false,
        windowOpen: false,
        skipClosed: false,
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
