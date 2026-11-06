import { useEffect, useRef, useState } from 'react'
import { settingsLine } from '../game/filters.js'
import { nameColour } from '../game/themes.js'
import Seats from './Seats.jsx'

const HINT = '들리는 곡의 애니 제목을 채팅으로 맞혀 보세요'
const EQ_BARS = [0, 1, 2, 3, 4, 5, 6]

function TimeBar({ max, remaining }) {
  const percent = max > 0 ? (remaining / max) * 100 : 0
  return (
    <div
      className="timebar h-3.5 w-full overflow-hidden rounded-full"
      role="progressbar"
      aria-label="남은 시간"
      aria-valuemin={0}
      aria-valuemax={max}
      aria-valuenow={remaining}
    >
      <div
        className="timebar-fill h-full rounded-full transition-[width] duration-200 ease-linear"
        style={{ width: `${percent}%` }}
      />
    </div>
  )
}

// The spinning record and the equalizer while a song plays; decoration only.
function Disc() {
  return (
    <div className="disc-area flex flex-1 flex-wrap items-center justify-center gap-6">
      <div className="record" aria-hidden="true" />
      <div className="eq" aria-hidden="true">
        {EQ_BARS.map((n) => (
          <span key={n} />
        ))}
      </div>
    </div>
  )
}

// The answer card; the flip-in is a CSS animation of this one card.
function RevealCard({ reveal }) {
  return (
    <div className="flip grid w-full min-w-0 max-w-full flex-1 grid-cols-1 place-items-center">
      <section className="reveal card w-full min-w-0 max-w-[min(420px,100%)] p-5 text-center md:p-6">
        <p className="card-label font-display h-5 truncate text-[13px] leading-5 tracking-[0.14em]">정답 공개</p>
        {/* Two lines are kept for the title in every look, so the card has
            the same box whichever font wraps it. */}
        <div className="mt-1.5 mb-1 flex h-[65px] w-full min-w-0 max-w-full items-center justify-center md:h-[75px]">
          <h2 className="font-display line-clamp-2 w-full min-w-0 text-[26px] leading-tight wrap-anywhere md:text-[30px]">{reveal.title}</h2>
        </div>
        <p className="card-slug mx-auto mt-1 block h-6 w-24 max-w-full truncate rounded-full px-3 py-0.5 text-xs leading-5 tracking-wide">{reveal.themeSlug}</p>
        {reveal.song !== null && reveal.song !== undefined && <p className="mt-2 h-6 min-w-0 truncate text-base leading-6 wrap-anywhere">{reveal.song}</p>}
        {reveal.artists.length > 0 && <p className="h-5 min-w-0 truncate text-sm leading-5 wrap-anywhere text-muted">{reveal.artists.join(', ')}</p>}
        <p className="card-winner font-display mt-3 block h-7 w-full truncate rounded-full px-3.5 py-1 text-sm leading-5">
          {reveal.winner === null || reveal.winner === undefined ? '정답자 없음' : `정답: ${reveal.winner}`}
        </p>
      </section>
    </div>
  )
}

function ChatForm({ onSend }) {
  const [text, setText] = useState('')

  function handleSubmit(event) {
    event.preventDefault()
    if (text.trim() === '') return
    onSend(text)
    setText('')
  }

  return (
    <form className="chat-form send flex items-end gap-2 p-2 md:p-3" onSubmit={handleSubmit}>
      <label className="flex min-w-0 flex-1 flex-col gap-1 text-xs text-muted">
        정답 입력
        <input
          type="text"
          value={text}
          onChange={(e) => setText(e.target.value)}
          autoComplete="off"
          placeholder="애니 제목을 입력하세요"
        />
      </label>
      <button type="submit" className="go h-11 w-24 shrink-0 truncate px-2">
        보내기
      </button>
    </form>
  )
}

// The chat log, scrolled to the newest line whenever a line arrives.
function ChatLog({ chat }) {
  const ref = useRef(null)
  useEffect(() => {
    const el = ref.current
    if (el !== null) el.scrollTop = el.scrollHeight
  }, [chat])
  return (
    <ul ref={ref} className="chat-log flex h-36 flex-col gap-1 overflow-y-auto px-3 py-2 text-[15px] md:h-44" aria-label="채팅">
      {chat.map((line) => (
        <li key={line.id} className="min-w-0 wrap-anywhere">
          <span
            aria-hidden="true"
            className="chat-mark mr-2 inline-block size-2.5 rounded-full align-middle"
            style={{ background: typeof line.name === 'string' ? nameColour(line.name) : 'currentColor' }}
          />
          {line.text}
        </li>
      ))}
    </ul>
  )
}

const WATCHING_TEXT = '이번 곡은 구경 중입니다. 다음 곡부터 참가합니다.'
const DISCONNECTED_TEXT = '서버와 연결이 끊어졌습니다.'
const REPLACED_TEXT = '다른 창에서 접속해 이 연결이 끊겼습니다.'

// The tail of a score or ranking row: " N점", and " (나)" on the player's
// own row. These parts never shrink, so a long name is cut instead; the
// leading spaces are kept (whitespace-pre) because the row is a flex box.
function ScoreTail({ row, me }) {
  return (
    <>
      <span className="shrink-0 whitespace-pre">{` ${row.score}점`}</span>
      {row.name === me && <span className="shrink-0 whitespace-pre">{' (나)'}</span>}
    </>
  )
}

function Scoreboard({ rows, me }) {
  return (
    <ul className="scoreboard grid grid-cols-2 gap-1.5 text-xs md:grid-cols-4" aria-label="점수판">
      {rows.map((row) => (
        <li key={row.name} className="chip flex h-6 min-w-0 items-center justify-center px-2.5 py-0.5 leading-4">
          <span className="min-w-0 truncate">{row.name}</span>
          <ScoreTail row={row} me={me} />
        </li>
      ))}
    </ul>
  )
}

function Ranking({ rows, me }) {
  return (
    <ul className="ranking flex flex-col gap-1.5" aria-label="최종 순위">
      {rows.map((row) => (
        <li
          key={row.name}
          className="rank-row panel flex h-10 min-w-0 items-center px-4 py-2 text-sm leading-5"
        >
          <span className="shrink-0 whitespace-pre">{`${row.rank}위 `}</span>
          <span className="min-w-0 truncate">{row.name}</span>
          <ScoreTail row={row} me={me} />
        </li>
      ))}
    </ul>
  )
}

// The skip vote: the button toggles the own vote; the count is from skipState.
function SkipControls({ voted, count, onSkip }) {
  return (
    <div className="skip flex items-center gap-3 md:col-start-2">
      <button type="button" onClick={onSkip} className={`h-11 w-28 shrink-0 truncate px-2 ${voted ? 'skip-on' : ''}`}>
        {voted ? '스킵 취소' : '스킵'}
      </button>
      {count !== null && (
        <span className="chip h-8 w-24 shrink-0 truncate px-2 py-1 text-center text-sm leading-5 tabular-nums">{`스킵 ${count.votes}/${count.needed}`}</span>
      )}
    </div>
  )
}

const LOAD_TEXT = {
  loading: '음원을 불러오는 중...',
  failed: '이번 곡을 재생하지 못했습니다.',
}

// The game and its result. In a room the result has no 다시 하기; once the
// room is back in its lobby, 대기실로 shows the lobby screen. A room also
// shows everyone's scores, the skip vote and the final ranking.
function GameScreen({ state, onChat, onSkip, onReset, onToLobby, onUnlockSound }) {
  const {
    screen,
    round,
    load,
    timer,
    chat,
    correct,
    reveal,
    score,
    error,
    finalScore,
    room,
    lobbyOpen,
    soundBlocked,
    scoreboard,
    ranking,
    watching,
    started,
    skipVoted,
    skipCount,
    waitingNext,
    replaced,
    settings,
    players,
  } = state
  const line = settings === null ? '' : settingsLine(settings)
  const me = room === null ? null : room.me
  const canSkip = room !== null && screen === 'game' && started && !watching
  // The record, the equalizer and the hint: between the start and the reveal.
  const playing = screen === 'game' && started && reveal === null
  const clock = timer === null ? null : `${Math.ceil(timer.remaining / 1000)}초`
  const stage = (
    <section
      aria-label="무대"
      className="stage panel relative z-10 flex min-w-0 flex-col items-stretch gap-3 p-4 md:col-start-2 md:row-start-1 md:min-h-[340px] md:gap-4 md:p-5"
    >
      <div className="status stage-head flex flex-wrap items-center gap-3">
        {round === null && !watching && !waitingNext && <span className="text-muted">서버에 연결하는 중...</span>}
        {round === null && waitingNext && <span className="text-muted">다음 곡을 기다리는 중...</span>}
        {round !== null && (
          <span className="round font-display flex h-10 w-40 items-center gap-1 overflow-hidden text-[15px] leading-5 whitespace-nowrap md:h-12 md:w-46">
            <span className="round-num block h-8 w-[3.9em] shrink-0 overflow-hidden text-left text-2xl leading-8 tabular-nums md:h-9 md:text-3xl md:leading-9">{`${round.index + 1} / ${round.total}`}</span>{' '}
            번째 곡
          </span>
        )}
        {score !== null && <span className="chip h-8 w-24 shrink-0 truncate px-2 py-1 text-center text-sm leading-5 tabular-nums">{`점수: ${score}`}</span>}
        {clock !== null && <span className="clock ml-auto h-10 w-24 text-4xl leading-10 tabular-nums md:h-12 md:w-29 md:text-[44px] md:leading-12">{clock}</span>}
      </div>
      {screen === 'game' && watching && (
        <p className="watching chip line-clamp-2 h-15 rounded-xl px-3 py-2 text-center text-sm leading-5">{WATCHING_TEXT}</p>
      )}
      {screen === 'game' && soundBlocked && (
        <button type="button" className="sound-prompt go h-11 w-44 self-center truncate px-2 py-0 animate-pulse" onClick={onUnlockSound}>
          탭하여 소리 켜기
        </button>
      )}
      {load !== null && LOAD_TEXT[load] !== undefined && (
        <p className={`h-5 truncate text-center text-sm leading-5 ${load === 'failed' ? 'text-bad' : 'text-muted'}`}>{LOAD_TEXT[load]}</p>
      )}
      {playing && <Disc />}
      {correct !== null && (
        // Right-to-left on the box, left-to-right on the text: when the line is
        // too long the ellipsis cuts the start of the name, so " 정답!" stays.
        // The text stays one node, so the banner reads "<name> 정답!" as is.
        <p className="correct font-display h-10 w-60 max-w-full self-center truncate rounded-full px-4 py-1.5 text-center text-lg leading-7 [direction:rtl]">
          <span className="[direction:ltr] [unicode-bidi:isolate]">{`${correct} 정답!`}</span>
        </p>
      )}
      {reveal !== null && <RevealCard reveal={reveal} />}
      {timer !== null && <TimeBar max={timer.max} remaining={timer.remaining} />}
      {(playing || canSkip) && (
        <div className="stage-foot grid grid-cols-1 items-center gap-3 md:grid-cols-[minmax(0,1fr)_auto]">
          {playing && <span className="stage-hint line-clamp-2 h-10 text-sm leading-5 text-muted">{HINT}</span>}
          {canSkip && <SkipControls voted={skipVoted} count={skipCount} onSkip={onSkip} />}
        </div>
      )}
      {error !== null && <p className="error rounded-xl bg-bad/10 px-3 py-2 text-sm text-bad">{error}</p>}
      {scoreboard !== null && <Scoreboard rows={scoreboard} me={me} />}
      {ranking !== null && <Ranking rows={ranking} me={me} />}
      {screen === 'over' && (
        <section className="over flex flex-col items-center gap-3 text-center">
          {finalScore !== null && <p className="font-display h-8 w-full truncate text-2xl leading-8">{`최종 점수: ${finalScore}점`}</p>}
          {room !== null && lobbyOpen && (
            <button type="button" className="go h-11 w-32 truncate px-2 py-0" onClick={onToLobby}>
              대기실로
            </button>
          )}
        </section>
      )}
      {screen === 'disconnected' && (
        <section className="over flex flex-col items-center gap-3 text-center">
          {finalScore !== null && <p className="font-display h-8 w-full truncate text-2xl leading-8">{`최종 점수: ${finalScore}점`}</p>}
          <p className="text-sm text-bad">{replaced ? REPLACED_TEXT : DISCONNECTED_TEXT}</p>
          <button type="button" className="go h-11 w-32 truncate px-2 py-0" onClick={onReset}>
            처음으로
          </button>
        </section>
      )}
    </section>
  )
  const chatPanel = (
    <section className="chat panel min-w-0 overflow-hidden md:col-start-2 md:row-start-2">
      <ChatLog chat={chat} />
      {screen === 'game' && <ChatForm onSend={onChat} />}
    </section>
  )
  // The title row: the room code chip (no 방 코드: label in a game) and the
  // one settings line of this game.
  const titleRow = (room !== null || line !== '') && (
    <div className="room-top flex items-center justify-between gap-3">
      {room !== null && <span className="code shrink-0 text-sm leading-[1.5] md:text-base">{room.code}</span>}
      {line !== '' && <p className="settings chip h-7 w-52 min-w-0 truncate px-3 py-0.5 text-center text-[13px] leading-5">{line}</p>}
    </div>
  )
  if (room === null) {
    return (
      <div className="game room mx-auto flex w-full max-w-3xl flex-col gap-3 md:gap-4">
        {titleRow}
        {stage}
        {chatPanel}
      </div>
    )
  }
  return (
    <div className="game room flex flex-col gap-3 md:gap-4">
      {titleRow}
      <div className="flex flex-col gap-3 md:grid md:grid-cols-[minmax(0,10rem)_minmax(0,1fr)_minmax(0,10rem)] md:gap-x-6 md:gap-y-4 lg:grid-cols-[minmax(0,11rem)_minmax(0,1fr)_minmax(0,11rem)]">
        <Seats
          layout="sides"
          players={players}
          me={me}
          chat={chat}
          scoreboard={scoreboard}
          correct={correct}
          roundIndex={round === null ? null : round.index}
        />
        {stage}
        {chatPanel}
      </div>
    </div>
  )
}

export default GameScreen
