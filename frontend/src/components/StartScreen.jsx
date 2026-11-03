import { useState } from 'react'
import { SONG_COUNTS } from '../game/state.js'
import { isReversed } from '../game/filters.js'
import FilterControls from './FilterControls.jsx'

const NO_OPUS =
  '이 브라우저에서는 음원을 재생할 수 없습니다. 브라우저 또는 iOS를 최신 버전으로 업데이트해 주세요.'
const CODE_LENGTH = 5

// The solo form (닉네임, 곡 수, 시작) and, outside it, the room controls
// (방 만들기, 방 코드, 참가). The code field is outside the form so Enter in
// it joins the room instead of starting a solo game. 곡 수 and the filters
// are the App-level selection, shared with the host controls (F-008).
function StartScreen({ canPlayOpus, error, initialCode, selection, onSelectionChange, onStart, onCreate, onJoin }) {
  const [nickname, setNickname] = useState('')
  const [code, setCode] = useState(initialCode)
  const canJoin = canPlayOpus && code !== ''
  const canStart = canPlayOpus && !isReversed(selection)

  function handleSubmit(event) {
    event.preventDefault()
    if (!canStart) return
    onStart(nickname)
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
          <select
            value={selection.songCount}
            onChange={(e) => onSelectionChange({ ...selection, songCount: e.target.value })}
          >
            {SONG_COUNTS.map((n) => (
              <option key={n} value={String(n)}>
                {n}
              </option>
            ))}
          </select>
        </label>
        <FilterControls selection={selection} onChange={onSelectionChange} />
        <button type="submit" disabled={!canStart}>
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
