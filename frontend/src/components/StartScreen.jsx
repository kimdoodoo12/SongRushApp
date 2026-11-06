import { useState } from 'react'
import { SONG_COUNTS } from '../game/state.js'
import { isReversed } from '../game/filters.js'
import FilterControls from './FilterControls.jsx'
import ThemePicker from './ThemePicker.jsx'

const NO_OPUS =
  '이 브라우저에서는 음원을 재생할 수 없습니다. 브라우저 또는 iOS를 최신 버전으로 업데이트해 주세요.'
const CODE_LENGTH = 5

// The solo form (닉네임, 곡 수, 시작) and, outside it, the room controls
// (방 만들기, 방 코드, 참가). The code field is outside the form so Enter in
// it joins the room instead of starting a solo game. 곡 수 and the filters
// are the App-level selection, shared with the host controls (F-008).
// theme is the own room look, changed with the 분위기 picker (F-012).
function StartScreen({
  canPlayOpus,
  error,
  initialCode,
  theme,
  onPickTheme,
  selection,
  onSelectionChange,
  onStart,
  onCreate,
  onJoin,
}) {
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
    <div className="start-screen mx-auto flex w-full max-w-md flex-col gap-4">
      <div className="panel flex flex-col gap-2 p-4">
        <span className="font-display h-5 truncate text-sm leading-5 text-muted">분위기</span>
        <ThemePicker value={theme} onPick={onPickTheme} />
      </div>
      <form className="start grid grid-cols-2 gap-3 panel p-5 [&_label]:flex [&_label]:flex-col [&_label]:gap-1 [&_label]:text-xs [&_label]:font-medium [&_label]:text-muted" onSubmit={handleSubmit}>
        <h2 className="font-display col-span-full text-xl">혼자 하기</h2>
        <label className="col-span-full">
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
        <button type="submit" className="go col-span-full py-3 text-base" disabled={!canStart}>
          시작
        </button>
        {!canPlayOpus && <p className="notice col-span-full text-sm text-warn">{NO_OPUS}</p>}
        {error !== null && <p className="error col-span-full rounded-xl bg-bad/10 px-3 py-2 text-sm text-bad">{error}</p>}
      </form>
      <div className="start room-controls grid grid-cols-[minmax(0,1fr)_auto] items-end gap-3 panel p-5 [&_label]:flex [&_label]:flex-col [&_label]:gap-1 [&_label]:text-xs [&_label]:font-medium [&_label]:text-muted">
        <h2 className="font-display col-span-full text-xl">같이 하기</h2>
        <button type="button" className="go col-span-full py-3 text-base" disabled={!canPlayOpus} onClick={() => onCreate(nickname)}>
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
            className="font-mono tracking-[0.25em] uppercase"
          />
        </label>
        <button
          type="button"
          disabled={!canJoin}
          onClick={handleJoin}
        >
          참가
        </button>
      </div>
    </div>
  )
}

export default StartScreen
