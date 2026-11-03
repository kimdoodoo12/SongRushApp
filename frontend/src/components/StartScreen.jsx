import { useState } from 'react'
import { SONG_COUNTS } from '../game/state.js'

const NO_OPUS =
  '이 브라우저에서는 음원을 재생할 수 없습니다. 브라우저 또는 iOS를 최신 버전으로 업데이트해 주세요.'
const CODE_LENGTH = 5

// The solo form (닉네임, 곡 수, 시작) and, outside it, the room controls
// (방 만들기, 방 코드, 참가). The code field is outside the form so Enter in
// it joins the room instead of starting a solo game.
function StartScreen({ canPlayOpus, error, initialCode, onStart, onCreate, onJoin }) {
  const [nickname, setNickname] = useState('')
  const [songCount, setSongCount] = useState('10')
  const [code, setCode] = useState(initialCode)
  const canJoin = canPlayOpus && code !== ''

  function handleSubmit(event) {
    event.preventDefault()
    if (!canPlayOpus) return
    onStart(nickname, Number(songCount))
  }

  function handleJoin() {
    if (!canJoin) return
    onJoin(nickname, code)
  }

  function handleCodeKeyDown(event) {
    if (event.key !== 'Enter') return
    event.preventDefault()
    handleJoin()
  }

  return (
    <div className="start-screen">
      <form className="start" onSubmit={handleSubmit}>
        <label>
          닉네임
          <input type="text" maxLength={16} value={nickname} onChange={(e) => setNickname(e.target.value)} />
        </label>
        <label>
          곡 수
          <select value={songCount} onChange={(e) => setSongCount(e.target.value)}>
            {SONG_COUNTS.map((n) => (
              <option key={n} value={String(n)}>
                {n}
              </option>
            ))}
          </select>
        </label>
        <button type="submit" disabled={!canPlayOpus}>
          시작
        </button>
        {!canPlayOpus && <p className="notice">{NO_OPUS}</p>}
        {error !== null && <p className="error">{error}</p>}
      </form>
      <div className="start room-controls">
        <button type="button" disabled={!canPlayOpus} onClick={() => onCreate(nickname)}>
          방 만들기
        </button>
        <label>
          방 코드
          <input
            type="text"
            maxLength={CODE_LENGTH}
            value={code}
            autoComplete="off"
            onChange={(e) => setCode(e.target.value.toUpperCase().slice(0, CODE_LENGTH))}
            onKeyDown={handleCodeKeyDown}
          />
        </label>
        <button type="button" disabled={!canJoin} onClick={handleJoin}>
          참가
        </button>
      </div>
    </div>
  )
}

export default StartScreen
