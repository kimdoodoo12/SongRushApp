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

const LOAD_TEXT = {
  loading: '음원을 불러오는 중...',
  failed: '이번 곡을 재생하지 못했습니다.',
}

function GameScreen({ state, onChat, onReset }) {
  const { screen, round, load, timer, chat, correct, reveal, score, error, finalScore } = state
  return (
    <div className="game">
      <div className="status">
        {round === null ? <span>서버에 연결하는 중...</span> : <span>{`${round.index + 1} / ${round.total}`}</span>}
        {score !== null && <span>{`점수: ${score}`}</span>}
      </div>
      {load !== null && LOAD_TEXT[load] !== undefined && <p>{LOAD_TEXT[load]}</p>}
      {timer !== null && <TimeBar max={timer.max} remaining={timer.remaining} />}
      {correct !== null && <p className="correct">{`${correct} 정답!`}</p>}
      {reveal !== null && <RevealCard reveal={reveal} />}
      {error !== null && <p className="error">{error}</p>}
      <ul className="chat-log">
        {chat.map((line) => (
          <li key={line.id}>{line.text}</li>
        ))}
      </ul>
      {screen === 'game' && <ChatForm onSend={onChat} />}
      {screen === 'over' && (
        <section className="over">
          <p>{`최종 점수: ${finalScore}점`}</p>
          <button type="button" onClick={onReset}>
            다시 하기
          </button>
        </section>
      )}
      {screen === 'disconnected' && (
        <section className="over">
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
