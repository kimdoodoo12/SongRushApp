import { useContext } from 'react'
import { nameColour } from '../game/themes.js'
import { useTranslation } from '../i18n/index.js'
import { SeatsAsDivs } from './seatList.js'

const SEAT_COUNT = 8

// The same text as the lobby roster row: the name, (방장), then (나).
function nameText(player, me, t) {
  let text = player.name
  if (player.host) text += ` ${t('seat.host')}`
  if (player.name === me) text += ` ${t('seat.me')}`
  return text
}

function scoreOf(player, scoreboard) {
  if (scoreboard !== null && scoreboard !== undefined) {
    const row = scoreboard.find((r) => r.name === player.name)
    if (row !== undefined) return row.score
  }
  return player.score
}

// The eight room seats in roster order, in the mockup's markup (D-124,
// cycle 110): ol.seat-grid of li.seat, a filled seat with .av (the initial
// on the name colour) and .sm (b: the name with its tags; small: the points
// and, for a correct answer, .ok). The seat of a correct answer is .win.
// correct is the one first-come winner's name, or in a ranked game (cycle
// 101) the list of every name that answered correctly; null for none. Chat
// is shown only in the chat log, never in a seat.
export default function Seats({ players, me, scoreboard, correct }) {
  const { t } = useTranslation()
  const asDivs = useContext(SeatsAsDivs)
  const List = asDivs ? 'div' : 'ol'
  const Item = asDivs ? 'div' : 'li'
  const itemRole = asDivs ? 'listitem' : undefined

  const seats = []
  for (let i = 0; i < SEAT_COUNT; i += 1) {
    const player = players[i]
    if (player === undefined) {
      seats.push(
        <Item key={`empty-${i}`} role={itemRole} className="seat empty">
          {t('seat.empty')}
        </Item>,
      )
      continue
    }
    const won = Array.isArray(correct) ? correct.includes(player.name) : correct === player.name
    const connected = player.connected !== false
    seats.push(
      <Item
        key={player.name}
        role={itemRole}
        data-connected={String(connected)}
        className={`seat${won ? ' win' : ''}${connected ? '' : ' opacity-50'}`}
      >
        <span className="av" aria-hidden="true" style={{ background: nameColour(player.name) }}>
          {Array.from(player.name)[0]}
        </span>
        <span className="sm">
          <b>{nameText(player, me, t)}</b>
          <small>
            {t('seat.score', { score: scoreOf(player, scoreboard) })}
            {won && <span className="ok">{t('seat.correct')}</span>}
          </small>
        </span>
      </Item>,
    )
  }

  return (
    <List role={asDivs ? 'list' : undefined} aria-label={t('seat.list')} className="seat-grid">
      {seats}
    </List>
  )
}
