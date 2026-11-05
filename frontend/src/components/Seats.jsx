import { useEffect, useMemo, useRef, useState } from 'react'

const SEAT_COUNT = 8
const BUBBLE_MS = 6000
const BUBBLE_MAX = 40
const ELLIPSIS = String.fromCharCode(0x2026)
const CROWN = String.fromCharCode(0x265b)

// Avatar colours, picked from the name so a player keeps one colour.
const AVATAR_COLOURS = [
  'bg-rose-500',
  'bg-orange-500',
  'bg-amber-500',
  'bg-emerald-500',
  'bg-teal-500',
  'bg-sky-500',
  'bg-indigo-500',
  'bg-fuchsia-500',
]

function avatarColour(name) {
  let hash = 0
  for (let i = 0; i < name.length; i += 1) hash = (hash * 31 + name.charCodeAt(i)) >>> 0
  return AVATAR_COLOURS[hash % AVATAR_COLOURS.length]
}

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
function newestByName(chat, watermark) {
  const newest = new Map()
  for (const entry of chat) {
    if (entry.id > watermark) newest.set(entry.name, entry)
  }
  return newest
}

// Where a seat sits and where its bubble and the bubble's tail go.
// 'sides' (a room game): below md the seats are one horizontally scrolling
// strip of compact cards, and a bubble covers the card's text area beside
// the avatar (the strip scrolls, so nothing may hang outside a card). From md
// seats 1-4 form the left column and 5-8 the right one, and their bubbles
// point into the stage. 'grid' (the lobby) hangs a bubble under the seat.
const MD_ROWS = ['md:row-start-1', 'md:row-start-2', 'md:row-start-3', 'md:row-start-4']
const STRIP_BUBBLE =
  'top-1 right-1 bottom-1 left-11 flex items-center rounded-xl px-2 text-[11px] leading-tight [&>span:last-child]:line-clamp-2'
const STRIP_TAIL = 'top-1/2 left-0 -translate-x-1/2 -translate-y-1/2'
const MD_BUBBLE =
  'md:bottom-auto md:block md:w-max md:max-w-48 md:rounded-2xl md:px-3 md:py-1.5 md:text-sm md:leading-normal md:[&>span:last-child]:line-clamp-none'
const MD_LEFT = {
  seat: 'md:col-start-1',
  bubble: 'md:top-1/2 md:right-auto md:left-full md:ml-3 md:-translate-y-1/2',
  tail: '',
}
const MD_RIGHT = {
  seat: 'md:col-start-3',
  bubble: 'md:top-1/2 md:right-full md:left-auto md:mr-3 md:-translate-y-1/2',
  tail: 'md:right-0 md:left-auto md:translate-x-1/2',
}
const GRID_PLACEMENT = {
  seat: 'flex-col items-center gap-1 p-2 text-center md:flex-row md:gap-3 md:p-3 md:text-left',
  text: 'items-center md:items-start',
  pills: 'justify-center md:justify-start',
  bubble:
    'top-full left-1/2 mt-2 w-max max-w-40 -translate-x-1/2 rounded-2xl px-3 py-1.5 text-sm md:max-w-48',
  tail: '-top-1 left-1/2 -translate-x-1/2',
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
      className={`pointer-events-none absolute z-20 bg-white font-medium break-all text-slate-900 shadow-lg shadow-black/40 transition duration-200 ease-out starting:scale-90 starting:opacity-0 ${placement.bubble}`}
    >
      <span aria-hidden="true" className={`absolute size-2.5 rotate-45 bg-white ${placement.tail}`} />
      <span className="relative">{text}</span>
    </div>
  )
}

function FilledSeat({ player, me, score, isCorrect, bubble, placement }) {
  const own = player.name === me
  const connected = player.connected !== false
  const ring = isCorrect
    ? 'border-good ring-2 ring-good shadow-[0_0_24px_-4px] shadow-good/60'
    : own
      ? 'border-accent/60 ring-2 ring-accent/70'
      : 'border-line'
  return (
    <div
      role="listitem"
      data-connected={String(connected)}
      className={`relative flex min-w-0 rounded-2xl border bg-surface-2/70 shadow-md shadow-black/30 transition ${ring} ${connected ? '' : 'opacity-50'} ${placement.seat}`}
    >
      <div
        aria-hidden="true"
        className={`relative grid size-9 shrink-0 place-items-center rounded-full text-base font-bold text-white md:size-11 md:text-lg ${avatarColour(player.name)}`}
      >
        {Array.from(player.name)[0]}
        {player.host && (
          <span className="absolute -top-2 -right-1 text-sm text-amber-300 drop-shadow">{CROWN}</span>
        )}
      </div>
      <div className={`flex w-full min-w-0 flex-col gap-0.5 ${placement.text}`}>
        <span className="w-full truncate text-xs font-semibold md:text-sm">{nameText(player, me)}</span>
        <span className={`flex flex-wrap items-center gap-1 ${placement.pills}`}>
          <span className="rounded-full bg-white/10 px-2 py-0.5 text-xs font-semibold text-ink tabular-nums">{`${score}점`}</span>
          {isCorrect && (
            <span className="rounded-full bg-good px-2 py-0.5 text-xs font-bold text-slate-950">정답!</span>
          )}
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
      className={`grid place-items-center rounded-2xl border border-dashed border-white/15 p-2 text-xs text-muted ${size} ${placement.seat}`}
    >
      빈 자리
    </div>
  )
}

const LIST_CLASS = {
  // The lobby: a 4x2 grid, 2 columns on the narrowest phones.
  grid: 'grid grid-cols-2 gap-3 min-[420px]:grid-cols-4',
  // A room game: below md one strip that scrolls inside itself (p-1 keeps
  // the rings visible); from md the list spans the three columns of the
  // parent grid (subgrid) and the stage sits in the empty middle column.
  sides:
    'flex min-w-0 gap-2 overflow-x-auto p-1 md:grid md:col-span-3 md:col-start-1 md:row-start-1 md:grid-cols-subgrid md:grid-rows-4 md:gap-y-4 md:overflow-visible md:p-0',
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
