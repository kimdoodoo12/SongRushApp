import { useState } from 'react'
import ThemePicker from './ThemePicker.jsx'
import { messageText, useTranslation } from '../i18n/index.js'

const CODE_LENGTH = 5

// One card, top to bottom: 닉네임, the 분위기 picker, 방 만들기, then 방 코드
// with 참가. Every game is a room game (D-093): there is no solo form, and
// 곡 수 and the filters are host controls in the lobby. Enter in the code
// field joins the room. theme is the own room look, changed with the
// 분위기 picker (F-012).
function StartScreen({ canPlayOpus, error, initialCode, theme, onPickTheme, onCreate, onJoin }) {
  const { t } = useTranslation()
  const [nickname, setNickname] = useState('')
  const [code, setCode] = useState(initialCode)
  const canJoin = canPlayOpus && code !== ''

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
      <div className="start room-controls panel grid grid-cols-[minmax(0,1fr)_auto] items-end gap-x-3 gap-y-4 p-5 [&_label]:flex [&_label]:flex-col [&_label]:gap-1 [&_label]:text-xs [&_label]:font-medium [&_label]:text-muted">
        <label className="col-span-full">
          {t('start.nickname')}
          <input type="text" maxLength={16} value={nickname} onChange={(e) => setNickname(e.target.value)} />
        </label>
        <div className="col-span-full flex flex-col gap-1">
          <span className="h-4 truncate text-xs leading-4 font-medium text-muted">{t('theme.label')}</span>
          <ThemePicker value={theme} onPick={onPickTheme} />
        </div>
        <button type="button" className="go col-span-full h-13 truncate py-0 text-base leading-6" disabled={!canPlayOpus} onClick={() => onCreate(nickname)}>
          {t('start.createRoom')}
        </button>
        <div className="col-span-full flex h-4 items-center gap-3 text-xs leading-4 text-muted" aria-hidden="true">
          <span className="h-px flex-1 bg-line" />
          {t('start.orCode')}
          <span className="h-px flex-1 bg-line" />
        </div>
        <label>
          {t('start.roomCode')}
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
          className="h-9 w-20 truncate px-2 py-0"
          disabled={!canJoin}
          onClick={handleJoin}
        >
          {t('start.join')}
        </button>
        {!canPlayOpus && <p className="notice col-span-full text-sm text-warn">{t('start.noOpus')}</p>}
        {error !== null && <p className="error col-span-full rounded-xl bg-bad/10 px-3 py-2 text-sm text-bad">{messageText(t, error)}</p>}
      </div>
    </div>
  )
}

export default StartScreen
