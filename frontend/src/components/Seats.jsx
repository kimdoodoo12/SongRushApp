import { nameColour } from '../game/themes.js'
import { useTranslation } from '../i18n/index.js'

const SEAT_COUNT = 8

// The name line: the name, (방장), then (나).
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
// skin is the lobby's (cycle 114, the mockup's renderSeats while idle):
// { ready, empty } from the mood's SKIN; then small reads 방장 for the host
// and skin.ready for the others, and an empty seat reads skin.empty.
export default function Seats({ players, me, scoreboard, correct, skin = null }) {
  const { t } = useTranslation()

  const seats = []
  for (let i = 0; i < SEAT_COUNT; i += 1) {
    const player = players[i]
    if (player === undefined) {
      seats.push(
        <li key={`empty-${i}`} className="seat empty">
          {skin === null ? t('seat.empty') : skin.empty}
        </li>,
      )
      continue
    }
    const won = skin === null && (Array.isArray(correct) ? correct.includes(player.name) : correct === player.name)
    const connected = player.connected !== false
    let small
    if (skin !== null) small = player.host ? t('lobby.hostSeat') : skin.ready
    else small = t('seat.score', { score: scoreOf(player, scoreboard) })
    seats.push(
      <li
        key={player.name}
        data-connected={String(connected)}
        className={`seat${won ? ' win' : ''}${connected ? '' : ' opacity-50'}`}
      >
        <span className="av" aria-hidden="true" style={{ background: nameColour(player.name) }}>
          {Array.from(player.name)[0]}
        </span>
        <span className="sm">
          <b>{nameText(player, me, t)}</b>
          <small>
            {small}
            {won && <span className="ok">{t('seat.correct')}</span>}
          </small>
        </span>
      </li>,
    )
  }

  return (
    <ol aria-label={t('seat.list')} className="seat-grid">
      {seats}
    </ol>
  )
}
