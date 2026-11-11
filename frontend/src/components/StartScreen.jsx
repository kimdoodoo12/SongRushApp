import { useState } from 'react'
import { messageText, useTranslation } from '../i18n/index.js'

const CODE_LENGTH = 5

// The start screen inside the room frame (cycle 114; the mockup has none):
// the marquee, then one section.opts card with 방 만들기, then 방 코드 with
// 참가. The input is .start-input (index.css), styled like the mockup's
// .years select. Every game is a room game (D-093): 곡 수 and the filters
// are host controls in the lobby. The nickname is asked by App's nickname
// dialog (cycle 119, D-127): onCreate(opener) and onJoin(code, opener) open
// it, opener being the element that gets the focus back when it closes.
// 참가 needs a full code; Enter in the code field does what 참가 does. The
// mood picker is the header's (the own pick, F-012).
function StartScreen({ canPlayOpus, error, initialCode, onCreate, onJoin }) {
  const { t } = useTranslation()
  const [code, setCode] = useState(initialCode)
  const canJoin = canPlayOpus && code.length === CODE_LENGTH

  function handleJoin(event) {
    if (!canJoin) return
    onJoin(code, event.currentTarget)
  }

  function handleCodeKeyDown(event) {
    if (event.key !== 'Enter') return
    event.preventDefault()
    handleJoin(event)
  }

  return (
    <div className="start-screen flex max-w-md flex-col gap-5">
      <h1 className="marquee">
        <span>{t('app.title')}</span>
      </h1>
      <section className="opts flex flex-col gap-4">
        <button type="button" className="btn primary go" disabled={!canPlayOpus} onClick={(e) => onCreate(e.currentTarget)}>
          {t('start.createRoom')}
        </button>
        <p className="oh">{t('start.orCode')}</p>
        <div className="flex items-end gap-2">
          <label className="flex min-w-0 flex-1 flex-col gap-1.5">
            <span className="ol">{t('start.roomCode')}</span>
            <input
              type="text"
              className="start-input font-mono tracking-[0.25em] uppercase"
              maxLength={CODE_LENGTH}
              value={code}
              autoComplete="off"
              onChange={(e) => setCode(e.target.value.toUpperCase().slice(0, CODE_LENGTH))}
              onKeyDown={handleCodeKeyDown}
            />
          </label>
          <button type="button" className="btn" disabled={!canJoin} onClick={handleJoin}>
            {t('start.join')}
          </button>
        </div>
        {!canPlayOpus && <p className="warn">{t('start.noOpus')}</p>}
        {error !== null && <p className="warn">{messageText(t, error)}</p>}
      </section>
    </div>
  )
}

export default StartScreen
