import { useState } from 'react'

const NO_OPUS =
  '이 브라우저에서는 음원을 재생할 수 없습니다. 브라우저 또는 iOS를 최신 버전으로 업데이트해 주세요.'
const SONG_COUNTS = Array.from({ length: 26 }, (_, i) => i + 5)

function StartScreen({ canPlayOpus, error, onStart }) {
  const [nickname, setNickname] = useState('')
  const [songCount, setSongCount] = useState('10')

  function handleSubmit(event) {
    event.preventDefault()
    if (!canPlayOpus) return
    onStart(nickname, Number(songCount))
  }

  return (
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
  )
}

export default StartScreen
