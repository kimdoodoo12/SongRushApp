import { useEffect, useMemo, useRef, useState } from 'react'
import { nameColour } from '../game/themes.js'

const SEAT_COUNT = 8
const BUBBLE_MS = 6000
const BUBBLE_MAX = 40
const ELLIPSIS = String.fromCharCode(0x2026)
const CROWN = String.fromCharCode(0x265b)

// The same text as the lobby roster row: the name, (방장), then (나).
function nameText(player, me) {
  let text = player.name
  if (player.host) text += ' (방장)'
  if (player.name === me) text += ' (나)'
  return text
}

function bubbleText(body) {
  return body.length > BUBBLE_MAX ? body.slice(0, BUBBLE_MAX) + ELLIPSIS : body
}

function scoreOf(player, scoreboard) {
  if (scoreboard !== null && scoreboard !== undefined) {
    const row = scoreboard.find((r) => r.name === player.name)
    if (row !== undefined) return row.score
  }
  return player.score
}

// The newest chat entry per name among the entries after the watermark.
// System lines (D-094) never become a bubble.
function newestByName(chat, watermark) {
  const newest = new Map()
  for (const entry of chat) {
    if (entry.kind !== 'system' && entry.id > watermark) newest.set(entry.name, entry)
  }
  return newest
}

// Where a seat sits and where its bubble and the bubble's tail go.
// 'sides' (a room game): below md the seats are one horizontally scrolling
// strip of compact cards, and a bubble covers the card's text area beside
// the avatar (the strip scrolls, so nothing may hang outside a card). From md
// seats 1-4 form the left column and 5-8 the right one, and each bubble sits
// above its own seat, inside the seat's column, so it never covers the
// stage. 'grid' (the lobby) hangs a bubble under the seat.
const MD_ROWS = ['md:row-start-1', 'md:row-start-2', 'md:row-start-3', 'md:row-start-4']
const STRIP_BUBBLE =
  'top-1 right-1 bottom-1 left-11 flex items-center rounded-xl px-2 text-[11px] leading-tight [&>span:last-child]:line-clamp-2'
// A tail is a rotated square showing only its two outer borders, so it
// opens the bubble's border where they meet.
const STRIP_TAIL =
  'top-1/2 left-0 -translate-x-1/2 -translate-y-1/2 border-b-[length:var(--bw)] border-l-[length:var(--bw)]'
const MD_BUBBLE =
  'md:top-auto md:bottom-[calc(100%+6px)] md:block md:w-max md:max-w-full md:translate-y-0 md:rounded-2xl md:px-3 md:py-1.5 md:text-sm md:leading-snug md:[&>span:last-child]:line-clamp-2'
// The md tail hangs from the bubble's bottom edge, pointing down at the seat.
const MD_TAIL =
  'md:top-full md:bottom-auto md:translate-x-0 md:-translate-y-1/2 md:border-t-0 md:border-l-0 md:border-b-[length:var(--bw)] md:border-r-[length:var(--bw)]'
const MD_LEFT = {
  seat: 'md:col-start-1',
  bubble: 'md:right-auto md:left-0',
  tail: `${MD_TAIL} md:right-auto md:left-5`,
}
const MD_RIGHT = {
  seat: 'md:col-start-3',
  bubble: 'md:right-0 md:left-auto',
  tail: `${MD_TAIL} md:right-5 md:left-auto`,
}
const GRID_PLACEMENT = {
  seat: 'flex-col items-center gap-1 p-2 text-center md:flex-row md:gap-3 md:p-3 md:text-left',
  text: 'items-center md:items-start',
  pills: 'justify-center md:justify-start',
  bubble:
    'top-full left-1/2 mt-2 w-max max-w-40 -translate-x-1/2 rounded-2xl px-3 py-1.5 text-sm md:max-w-48',
  tail: 'top-0 left-1/2 -translate-x-1/2 -translate-y-1/2 border-t-[length:var(--bw)] border-l-[length:var(--bw)]',
}

function seatPlacement(layout, index) {
  if (layout !== 'sides') return GRID_PLACEMENT
  const side = index < SEAT_COUNT / 2 ? MD_LEFT : MD_RIGHT
  return {
    seat: `w-34 shrink-0 flex-row items-center gap-2 p-1.5 text-left md:w-auto md:gap-3 md:p-3 ${side.seat} ${MD_ROWS[index % 4]}`,
    text: 'items-start',
    pills: 'justify-start',
    bubble: `${STRIP_BUBBLE} ${MD_BUBBLE} ${side.bubble}`,
    tail: `${STRIP_TAIL} ${side.tail}`,
  }
}

function Bubble({ text, placement }) {
  return (
    <div
      className={`bubble pointer-events-none absolute z-20 break-all transition duration-200 ease-out starting:scale-90 starting:opacity-0 ${placement.bubble}`}
    >
      <span aria-hidden="true" className={`bubble-tail absolute size-2.5 rotate-45 ${placement.tail}`} />
      <span className="relative">{text}</span>
    </div>
  )
}

function FilledSeat({ player, me, score, isCorrect, bubble, placement }) {
  const own = player.name === me
  const connected = player.connected !== false
  const mark = isCorrect ? 'seat-got' : own ? 'seat-me' : ''
  return (
    <div
      role="listitem"
      data-connected={String(connected)}
      className={`seat panel relative flex min-w-0 transition ${mark} ${connected ? '' : 'opacity-50'} ${placement.seat}`}
    >
      {isCorrect && <span className="seat-tag absolute -top-2.5 left-2.5 z-10 h-5 whitespace-nowrap px-2 text-xs leading-5">정답!</span>}
      <div
        aria-hidden="true"
        className="avatar relative grid size-9 shrink-0 place-items-center text-base md:size-11 md:text-xl"
        style={{ background: nameColour(player.name) }}
      >
        {Array.from(player.name)[0]}
        {player.host && <span className="crown absolute -top-2.5 -right-1.5 text-sm md:text-base">{CROWN}</span>}
      </div>
      <div className={`flex w-full min-w-0 flex-col gap-0.5 ${placement.text}`}>
        <span className="seat-name font-display h-5 w-full truncate text-sm leading-5 md:h-5.5 md:text-[15px] md:leading-5.5">{nameText(player, me)}</span>
        <span className={`flex flex-wrap items-center gap-1 ${placement.pills}`}>
          <span className="seat-score h-4 whitespace-nowrap text-xs leading-4 text-muted tabular-nums md:h-5 md:text-[13px] md:leading-5">{`${score}점`}</span>
        </span>
      </div>
      {bubble !== null && <Bubble key={bubble.id} text={bubbleText(bubble.body)} placement={placement} />}
    </div>
  )
}

function EmptySeat({ layout, placement }) {
  const size = layout === 'sides' ? 'w-34 shrink-0 min-h-12 md:w-auto md:min-h-17' : 'min-h-16 md:min-h-17'
  return (
    <div
      role="listitem"
      className={`seat-empty grid place-items-center whitespace-nowrap p-2 text-xs leading-4 ${size} ${placement.seat}`}
    >
      빈 자리
    </div>
  )
}

const LIST_CLASS = {
  // The lobby: a 4x2 grid, 2 columns on the narrowest phones.
  grid: 'grid grid-cols-2 gap-3 min-[420px]:grid-cols-4',
  // A room game: below md one strip that scrolls inside itself (pt-3 keeps
  // the 정답! tags visible); from md each seat has room above it for its
  // two-line bubble (pt-14, gap-y-14), and the list spans the three columns and
  // both rows of the parent grid (subgrid columns) and the stage and the
  // chat sit in the empty middle column.
  sides:
    'flex min-w-0 gap-2 overflow-x-auto px-1 pt-3 pb-1.5 md:grid md:col-span-3 md:col-start-1 md:row-span-2 md:row-start-1 md:grid-cols-subgrid md:grid-rows-[repeat(4,auto)] md:content-start md:items-start md:self-start md:gap-y-14 md:overflow-visible md:p-0 md:pt-14',
}

// The eight room seats in roster order, with each player's newest chat line
// as a speech bubble. A bubble shows only lines after the watermark, which
// moves to the newest line whenever roundIndex changes (the next song is
// prepared); each bubble hides 6 s after its line, and a newer line from the
// same player restarts that player's timer.
export default function Seats({ players, me, chat, scoreboard, correct, roundIndex, layout = 'grid' }) {
  const [round, setRound] = useState(roundIndex)
  const [watermark, setWatermark] = useState(-1)
  // name -> the id of the line whose bubble timed out.
  const [expired, setExpired] = useState({})
  const timersRef = useRef(new Map())

  if (roundIndex !== round) {
    setRound(roundIndex)
    if (chat.length > 0) setWatermark(Math.max(watermark, chat[chat.length - 1].id))
  }

  const newest = useMemo(() => newestByName(chat, watermark), [chat, watermark])

  useEffect(() => {
    const timers = timersRef.current
    for (const [name, entry] of newest) {
      const running = timers.get(name)
      if (running !== undefined && running.id === entry.id) continue
      if (running !== undefined) clearTimeout(running.handle)
      const handle = setTimeout(() => {
        timers.delete(name)
        setExpired((prev) => ({ ...prev, [name]: entry.id }))
      }, BUBBLE_MS)
      timers.set(name, { id: entry.id, handle })
    }
  }, [newest])

  useEffect(() => {
    const timers = timersRef.current
    return () => {
      for (const { handle } of timers.values()) clearTimeout(handle)
      timers.clear()
    }
  }, [])

  const seats = []
  for (let i = 0; i < SEAT_COUNT; i += 1) {
    const placement = seatPlacement(layout, i)
    const player = players[i]
    if (player === undefined) {
      seats.push(<EmptySeat key={`empty-${i}`} layout={layout} placement={placement} />)
      continue
    }
    const entry = newest.get(player.name)
    const bubble = entry !== undefined && expired[player.name] !== entry.id ? entry : null
    seats.push(
      <FilledSeat
        key={player.name}
        player={player}
        me={me}
        score={scoreOf(player, scoreboard)}
        isCorrect={correct !== null && correct !== undefined && correct === player.name}
        bubble={bubble}
        placement={placement}
      />,
    )
  }

  return (
    <div role="list" aria-label="자리" className={LIST_CLASS[layout] ?? LIST_CLASS.grid}>
      {seats}
    </div>
  )
}
