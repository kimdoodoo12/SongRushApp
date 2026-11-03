import { useState } from 'react'

function TimeBar({ max, remaining }) {
  const percent = max > 0 ? (remaining / max) * 100 : 0
  return (
    <div
      className="timebar"
      role="progressbar"
      aria-label="남은 시간"
      aria-valuemin={0}
      aria-valuemax={max}
      aria-valuenow={remaining}
    >
      <div className="timebar-fill" style={{ width: `${percent}%` }} />
    </div>
  )
}

function RevealCard({ reveal }) {
  return (
    <section className="reveal">
      <h2>{reveal.title}</h2>
      <p>{reveal.themeSlug}</p>
      {reveal.song !== null && reveal.song !== undefined && <p>{reveal.song}</p>}
      {reveal.artists.length > 0 && <p>{reveal.artists.join(', ')}</p>}
      <p>{reveal.winner === null || reveal.winner === undefined ? '정답자 없음' : `정답: ${reveal.winner}`}</p>
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
    <form className="chat-form" onSubmit={handleSubmit}>
      <label>
        정답 입력
        <input type="text" value={text} onChange={(e) => setText(e.target.value)} autoComplete="off" />
      </label>
      <button type="submit">보내기</button>
    </form>
  )
}

const WATCHING_TEXT = '이번 곡은 구경 중입니다. 다음 곡부터 참가합니다.'

// One score or ranking row; the player's own row ends with (나).
function ownMark(text, name, me) {
  return name === me ? `${text} (나)` : text
}

function Scoreboard({ rows, me }) {
  return (
    <ul className="scoreboard" aria-label="점수판">
      {rows.map((row) => (
        <li key={row.name}>{ownMark(`${row.name} ${row.score}점`, row.name, me)}</li>
      ))}
    </ul>
  )
}

function Ranking({ rows, me }) {
  return (
    <ul className="ranking" aria-label="최종 순위">
      {rows.map((row) => (
        <li key={row.name}>{ownMark(`${row.rank}위 ${row.name} ${row.score}점`, row.name, me)}</li>
      ))}
    </ul>
  )
}

// The skip vote: the button toggles the own vote; the count is from skipState.
function SkipControls({ voted, count, onSkip }) {
  return (
    <div className="skip">
      <button type="button" onClick={onSkip}>
        {voted ? '스킵 취소' : '스킵'}
      </button>
      {count !== null && <span>{`스킵 ${count.votes}/${count.needed}`}</span>}
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
  } = state
  const me = room === null ? null : room.me
  const canSkip = room !== null && screen === 'game' && started && !watching
  return (
    <div className="game">
      <div className="status">
        {round === null && !watching && <span>서버에 연결하는 중...</span>}
        {round !== null && <span>{`${round.index + 1} / ${round.total}`}</span>}
        {score !== null && <span>{`점수: ${score}`}</span>}
      </div>
      {screen === 'game' && watching && <p className="watching">{WATCHING_TEXT}</p>}
      {screen === 'game' && soundBlocked && (
        <button type="button" className="sound-prompt" onClick={onUnlockSound}>
          탭하여 소리 켜기
        </button>
      )}
      {load !== null && LOAD_TEXT[load] !== undefined && <p>{LOAD_TEXT[load]}</p>}
      {timer !== null && <TimeBar max={timer.max} remaining={timer.remaining} />}
      {canSkip && <SkipControls voted={skipVoted} count={skipCount} onSkip={onSkip} />}
      {correct !== null && <p className="correct">{`${correct} 정답!`}</p>}
      {reveal !== null && <RevealCard reveal={reveal} />}
      {error !== null && <p className="error">{error}</p>}
      {scoreboard !== null && <Scoreboard rows={scoreboard} me={me} />}
      {ranking !== null && <Ranking rows={ranking} me={me} />}
      <ul className="chat-log">
        {chat.map((line) => (
          <li key={line.id}>{line.text}</li>
        ))}
      </ul>
      {screen === 'game' && <ChatForm onSend={onChat} />}
      {screen === 'over' && (
        <section className="over">
          {finalScore !== null && <p>{`최종 점수: ${finalScore}점`}</p>}
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
        <section className="over">
          {finalScore !== null && <p>{`최종 점수: ${finalScore}점`}</p>}
          <p>서버와 연결이 끊어졌습니다.</p>
          <button type="button" onClick={onReset}>
            처음으로
          </button>
        </section>
      )}
    </div>
  )
}

export default GameScreen
