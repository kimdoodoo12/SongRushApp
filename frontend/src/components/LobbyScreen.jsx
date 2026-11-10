import { useEffect, useId, useRef, useState } from 'react'
import { isReversed } from '../game/filters.js'
import { drawPlayer } from './drawPlayer.js'
import FilterControls from './FilterControls.jsx'
import Seats from './Seats.jsx'
import { messageText, useTranslation } from '../i18n/index.js'

// The song counts a game can have (D-047), and the stepper's step.
const MIN_SONGS = 5
const MAX_SONGS = 30
const SEAT_COUNT = 8
// The stepper's minus sign (U+2212), as in the mockup.
const MINUS = String.fromCharCode(0x2212)
// The deck disc's size: the mockup's #discArt is 520 x 520.
const DISC_PX = 520

// The mockup's setMood copy of the deck disc into #lobbyDisc: the disc is
// drawn on a 520 x 520 canvas of its own, as the deck's, then scaled onto
// the target, unsmoothed for pixel. Without a 2d context (jsdom) it draws
// nothing.
function copyDisc(canvas, mood) {
  const c = canvas.getContext('2d')
  if (c === null || c === undefined) return
  const art = document.createElement('canvas')
  art.width = DISC_PX
  art.height = DISC_PX
  drawPlayer(art, mood)
  c.clearRect(0, 0, canvas.width, canvas.height)
  c.imageSmoothingEnabled = mood !== 'pixel'
  c.drawImage(art, 0, 0, canvas.width, canvas.height)
}

// The mood's SKIN row (the mockup's lobby table), translated.
function skinOf(t, theme) {
  const word = (key) => t(`skin.${theme}.${key}`)
  return {
    title: word('title'),
    label: word('label'),
    admit: word('admit'),
    start: word('start'),
    seats: word('seats'),
    ready: word('ready'),
    empty: word('empty'),
  }
}

// The mockup's 곡 수 stepper: -, the count, +, within 5-30. The selection
// keeps the count as the select value it always was (a string); the stepper
// reads and steps it as a number.
function Stepper({ selection, onSelectionChange, disabled }) {
  const { t } = useTranslation()
  const count = Number(selection.songCount)
  function step(by) {
    const next = Math.min(MAX_SONGS, Math.max(MIN_SONGS, count + by))
    if (next !== count) onSelectionChange({ ...selection, songCount: String(next) })
  }
  return (
    <div className="opt">
      <span className="ol">{t('lobby.songCount')}</span>
      <div className="stepper">
        <button type="button" className="btn" aria-label={t('lobby.songCountDown')} disabled={disabled || count <= MIN_SONGS} onClick={() => step(-1)}>
          {MINUS}
        </button>
        <output aria-label={t('lobby.songCount')}>{count}</output>
        <button type="button" className="btn" aria-label={t('lobby.songCountUp')} disabled={disabled || count >= MAX_SONGS} onClick={() => step(1)}>
          +
        </button>
      </div>
    </div>
  )
}

// The mockup's ticket: the mood's tag and label, the code, the share link
// with 복사, the hint or the copy result, and the stub with the connected
// count.
function Ticket({ code, theme, skin, online }) {
  const { t } = useTranslation()
  // null: the hint; 'ok' or 'fail': the last copy's result.
  const [copied, setCopied] = useState(null)
  const link = `${window.location.origin}/r/${code}`

  // The mockup's copy: a missing or refusing clipboard shows the fallback
  // text; the link stays on screen to copy by hand.
  function handleCopy() {
    const fail = (err) => {
      console.warn('clipboard write failed', err)
      setCopied('fail')
    }
    try {
      navigator.clipboard.writeText(link).then(() => setCopied('ok'), fail)
    } catch (err) {
      fail(err)
    }
  }

  let sub = t('lobby.shareHint')
  if (copied === 'ok') sub = t('lobby.copied')
  else if (copied === 'fail') sub = t('lobby.copyFailed')
  return (
    <div className="ticket">
      <div className="tk-main">
        <span className="tk-tag">{`${t(`theme.${theme}`)} · ${t(`mood.tag.${theme}`)}`}</span>
        <span className="tk-label">{skin.label}</span>
        <span className="tk-code">{code}</span>
        <div className="share">
          <span className="link">{link}</span>
          <button type="button" className="btn" onClick={handleCopy}>
            {t('lobby.copy')}
          </button>
        </div>
        <span className={copied === 'ok' ? 'tk-sub copied' : 'tk-sub'}>{sub}</span>
      </div>
      <div className="tk-stub">
        <span className="tk-n">{online}</span>
        <span className="tk-of">{t('lobby.of', { n: SEAT_COUNT })}</span>
        <span className="tk-admit">{skin.admit}</span>
      </div>
    </div>
  )
}

// The mood's disc, copied from the deck's drawing (the mockup's setMood
// copy into #lobbyDisc), with the ? over it.
function LobbyDisc({ theme }) {
  const ref = useRef(null)
  useEffect(() => {
    copyDisc(ref.current, theme)
  }, [theme])
  return (
    <div className="ldisc" aria-hidden="true">
      <canvas ref={ref} width="320" height="320" />
      <span className="lq">?</span>
    </div>
  )
}

// The lobby in the mockup's #lobbyView markup (D-124, cycle 114): the
// marquee, the ticket and the disc, the settings card, the start row, and
// the seats. Its text follows the mood's SKIN. theme is the room look from
// the server (F-012); the header's .moodbar is its picker. 곡 수 and the
// filters are the App-level selection, so they survive a game and the
// return to the lobby (D-062, F-008); a guest sees them disabled. notice
// (such as ABANDONED) and error are p.warn lines under the start row.
function LobbyScreen({ room, players, error, notice, theme, selection, onSelectionChange, onStartGame, onLeave }) {
  const { t } = useTranslation()
  const titleId = useId()
  const own = players.find((p) => p.name === room.me)
  const isHost = own !== undefined && own.host
  const online = players.filter((p) => p.connected !== false).length
  const reversed = isReversed(selection)
  const skin = skinOf(t, theme)
  return (
    <>
      <div className="lobby">
        <h1 className="marquee">
          <span>{skin.title}</span>
        </h1>
        <div className="lobby-top">
          <Ticket code={room.code} theme={theme} skin={skin} online={online} />
          <div className="lobby-side">
            <LobbyDisc theme={theme} />
          </div>
        </div>

        <section className="opts" aria-labelledby={titleId}>
          <div className="opts-hd">
            <h2 id={titleId}>{t('lobby.settingsTitle')}</h2>
            <span className="opts-who">{isHost ? t('lobby.whoHost') : t('lobby.whoGuest')}</span>
          </div>
          <div className="opt-grid">
            <Stepper selection={selection} onSelectionChange={onSelectionChange} disabled={!isHost} />
            <FilterControls selection={selection} onChange={onSelectionChange} disabled={!isHost} />
          </div>
          {reversed && <p className="warn">{t('filter.reversed')}</p>}
        </section>

        <div className="start-row">
          {isHost ? (
            <button type="button" className="btn primary go" disabled={reversed} onClick={() => onStartGame()}>
              {skin.start}
            </button>
          ) : (
            <p className="waiting">
              <span className="dots" aria-hidden="true">
                <i />
                <i />
                <i />
              </span>
              {t('lobby.waiting')}
            </p>
          )}
          <button type="button" className="btn" onClick={onLeave}>
            {t('lobby.leave')}
          </button>
        </div>
        {notice !== null && notice !== undefined && <p className="warn">{messageText(t, notice)}</p>}
        {error !== null && error !== undefined && <p className="warn">{messageText(t, error)}</p>}
      </div>

      <div className="seats">
        <h2>{t('lobby.seats', { seats: skin.seats, n: players.length })}</h2>
        <Seats players={players} me={room.me} scoreboard={null} correct={null} skin={skin} />
      </div>
    </>
  )
}

export default LobbyScreen
