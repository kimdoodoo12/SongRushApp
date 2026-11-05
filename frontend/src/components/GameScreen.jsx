import { useEffect, useRef, useState } from 'react'
import { settingsLine } from '../game/filters.js'
import Seats from './Seats.jsx'

function TimeBar({ max, remaining }) {
  const percent = max > 0 ? (remaining / max) * 100 : 0
  return (
    <div
      className="timebar h-4 overflow-hidden rounded-full border border-line bg-slate-950/70 shadow-inner"
      role="progressbar"
      aria-label="남은 시간"
      aria-valuemin={0}
      aria-valuemax={max}
      aria-valuenow={remaining}
    >
      <div
        className="timebar-fill h-full rounded-full bg-gradient-to-r from-accent to-fuchsia-400 shadow-[0_0_16px_2px] shadow-accent/60 transition-[width] duration-200 ease-linear"
        style={{ width: `${percent}%` }}
      />
    </div>
  )
}

function RevealCard({ reveal }) {
  return (
    <section className="reveal rounded-2xl border border-accent/40 bg-gradient-to-br from-accent/20 via-surface-2/80 to-surface-2/80 p-5 text-center shadow-lg shadow-accent/10">
      <h2 className="text-2xl font-extrabold tracking-tight break-words text-white md:text-3xl">{reveal.title}</h2>
      <p className="mt-2 inline-block rounded-full bg-white/10 px-3 py-0.5 text-xs font-semibold tracking-wide text-accent-soft">
        {reveal.themeSlug}
      </p>
      {reveal.song !== null && reveal.song !== undefined && <p className="mt-2 text-base text-ink">{reveal.song}</p>}
      {reveal.artists.length > 0 && <p className="text-sm text-muted">{reveal.artists.join(', ')}</p>}
      <p className="mt-3 text-sm font-semibold text-good">
        {reveal.winner === null || reveal.winner === undefined ? '정답자 없음' : `정답: ${reveal.winner}`}
      </p>
    </section>
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
    <form className="chat-form flex items-end gap-2 border-t border-line bg-surface/80 p-3" onSubmit={handleSubmit}>
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
      <button type="submit" className="shrink-0">
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
    <ul ref={ref} className="chat-log h-56 overflow-y-auto px-1 py-1 text-sm" aria-label="채팅">
      {chat.map((line) => (
        <li key={line.id} className="rounded-lg px-3 py-1 break-words odd:bg-white/[0.03]">
          {line.text}
        </li>
      ))}
    </ul>
  )
}

const WATCHING_TEXT = '이번 곡은 구경 중입니다. 다음 곡부터 참가합니다.'
const DISCONNECTED_TEXT = '서버와 연결이 끊어졌습니다.'
const REPLACED_TEXT = '다른 창에서 접속해 이 연결이 끊겼습니다.'

// One score or ranking row; the player's own row ends with (나).
function ownMark(text, name, me) {
  return name === me ? `${text} (나)` : text
}

function Scoreboard({ rows, me }) {
  return (
    <ul className="scoreboard flex flex-wrap justify-center gap-1.5 text-xs" aria-label="점수판">
      {rows.map((row) => (
        <li key={row.name} className="rounded-full border border-line bg-white/5 px-2.5 py-0.5 text-muted">{ownMark(`${row.name} ${row.score}점`, row.name, me)}</li>
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
          className="rounded-xl border border-line bg-white/5 px-4 py-2 text-sm font-semibold first:border-amber-400/50 first:bg-amber-400/10 first:text-amber-200"
        >{ownMark(`${row.rank}위 ${row.name} ${row.score}점`, row.name, me)}</li>
      ))}
    </ul>
  )
}

// The skip vote: the button toggles the own vote; the count is from skipState.
function SkipControls({ voted, count, onSkip }) {
  return (
    <div className="skip flex items-center justify-center gap-3">
      <button
        type="button"
        onClick={onSkip}
        className={
          voted
            ? 'bg-warn/20 text-amber-200 shadow-none ring-1 ring-warn/50 hover:bg-warn/30'
            : 'bg-white/10 text-ink shadow-none hover:bg-white/15'
        }
      >
        {voted ? '스킵 취소' : '스킵'}
      </button>
      {count !== null && (
        <span className="rounded-full bg-white/5 px-3 py-1 text-sm text-muted tabular-nums">{`스킵 ${count.votes}/${count.needed}`}</span>
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
  const stage = (
    <section
      aria-label="무대"
      className="relative z-10 flex min-w-0 flex-col gap-4 rounded-2xl border border-line bg-surface/85 p-4 shadow-xl shadow-black/40 backdrop-blur md:col-start-2 md:row-start-1 md:p-6"
    >
      <div className="status flex items-center justify-between gap-3 font-semibold">
        {round === null && !watching && !waitingNext && <span className="text-muted">서버에 연결하는 중...</span>}
        {round === null && waitingNext && <span className="text-muted">다음 곡을 기다리는 중...</span>}
        {round !== null && (
          <span className="rounded-full bg-accent/20 px-3 py-1 text-sm text-accent-soft tabular-nums">{`${round.index + 1} / ${round.total}`}</span>
        )}
        {score !== null && (
          <span className="rounded-full bg-white/10 px-3 py-1 text-sm tabular-nums">{`점수: ${score}`}</span>
        )}
      </div>
      {line !== '' && <p className="settings -mt-2 text-xs text-muted">{line}</p>}
      {screen === 'game' && watching && (
        <p className="watching rounded-xl bg-white/5 px-3 py-2 text-center text-sm text-muted">{WATCHING_TEXT}</p>
      )}
      {screen === 'game' && soundBlocked && (
        <button type="button" className="sound-prompt self-center animate-pulse" onClick={onUnlockSound}>
          탭하여 소리 켜기
        </button>
      )}
      {load !== null && LOAD_TEXT[load] !== undefined && (
        <p className={`text-center text-sm ${load === 'failed' ? 'text-bad' : 'text-muted'}`}>{LOAD_TEXT[load]}</p>
      )}
      {timer !== null && <TimeBar max={timer.max} remaining={timer.remaining} />}
      {canSkip && <SkipControls voted={skipVoted} count={skipCount} onSkip={onSkip} />}
      {correct !== null && (
        <p className="correct rounded-xl border border-good/40 bg-good/15 px-4 py-2 text-center text-lg font-bold text-good">{`${correct} 정답!`}</p>
      )}
      {reveal !== null && <RevealCard reveal={reveal} />}
      {error !== null && <p className="error rounded-xl bg-bad/10 px-3 py-2 text-sm text-bad">{error}</p>}
      {scoreboard !== null && <Scoreboard rows={scoreboard} me={me} />}
      {ranking !== null && <Ranking rows={ranking} me={me} />}
      {screen === 'over' && (
        <section className="over flex flex-col items-center gap-3 text-center">
          {finalScore !== null && <p className="text-xl font-bold">{`최종 점수: ${finalScore}점`}</p>}
          {room === null && (
            <button type="button" onClick={onReset}>
              다시 하기
            </button>
          )}
          {room !== null && lobbyOpen && (
            <button type="button" onClick={onToLobby}>
              대기실로
            </button>
          )}
        </section>
      )}
      {screen === 'disconnected' && (
        <section className="over flex flex-col items-center gap-3 text-center">
          {finalScore !== null && <p className="text-xl font-bold">{`최종 점수: ${finalScore}점`}</p>}
          <p className="text-sm text-bad">{replaced ? REPLACED_TEXT : DISCONNECTED_TEXT}</p>
          <button type="button" onClick={onReset}>
            처음으로
          </button>
        </section>
      )}
    </section>
  )
  return (
    <div className="game flex flex-col gap-4">
      {room === null ? (
        stage
      ) : (
        <div className="flex flex-col gap-4 md:grid md:grid-cols-[minmax(0,10rem)_minmax(0,1fr)_minmax(0,10rem)] md:gap-x-6 lg:grid-cols-[minmax(0,13rem)_minmax(0,1fr)_minmax(0,13rem)]">
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
        </div>
      )}
      <section className="overflow-hidden rounded-2xl border border-line bg-surface/85 shadow-xl shadow-black/40">
        <ChatLog chat={chat} />
        {screen === 'game' && <ChatForm onSend={onChat} />}
      </section>
    </div>
  )
}

export default GameScreen
