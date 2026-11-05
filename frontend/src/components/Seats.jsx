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

// Where a seat sits and where its bubble and the bubble's tail go. Below md
// a bubble hangs under its seat; in the phone strip of four the outer seats
// align it to their outer edge so it stays on screen. In 'sides' (a room
// game) seats 1-4 form the left column and 5-8 the right one from md up,
// and their bubbles point into the stage. 'grid' is the lobby.
const MOBILE_BUBBLE = [
  { bubble: 'top-full left-0 mt-2', tail: '-top-1 left-6' },
  { bubble: 'top-full left-1/2 -translate-x-1/2 mt-2', tail: '-top-1 left-1/2 -translate-x-1/2' },
  { bubble: 'top-full left-1/2 -translate-x-1/2 mt-2', tail: '-top-1 left-1/2 -translate-x-1/2' },
  { bubble: 'top-full right-0 mt-2', tail: '-top-1 right-6' },
]
const MD_ROWS = ['md:row-start-1', 'md:row-start-2', 'md:row-start-3', 'md:row-start-4']
const MD_LEFT = {
  seat: 'md:col-start-1',
  bubble: 'md:top-1/2 md:right-auto md:left-full md:mt-0 md:ml-3 md:translate-x-0 md:-translate-y-1/2',
  tail: 'md:top-1/2 md:right-auto md:left-0 md:-translate-x-1/2 md:-translate-y-1/2',
}
const MD_RIGHT = {
  seat: 'md:col-start-3',
  bubble: 'md:top-1/2 md:right-full md:left-auto md:mt-0 md:mr-3 md:translate-x-0 md:-translate-y-1/2',
  tail: 'md:top-1/2 md:right-0 md:left-auto md:translate-x-1/2 md:-translate-y-1/2',
}

function seatPlacement(layout, index) {
  if (layout !== 'sides') return { seat: '', ...MOBILE_BUBBLE[1] }
  const mobile = MOBILE_BUBBLE[index % 4]
  const side = index < SEAT_COUNT / 2 ? MD_LEFT : MD_RIGHT
  return {
    seat: `${side.seat} ${MD_ROWS[index % 4]}`,
    bubble: `${mobile.bubble} ${side.bubble}`,
    tail: `${mobile.tail} ${side.tail}`,
  }
}

function Bubble({ text, placement }) {
  return (
    <div
      className={`pointer-events-none absolute z-20 w-max max-w-40 rounded-2xl bg-white px-3 py-1.5 text-sm font-medium break-all text-slate-900 shadow-lg shadow-black/40 transition duration-200 ease-out starting:scale-90 starting:opacity-0 md:max-w-48 ${placement.bubble}`}
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
      className={`relative flex min-w-0 flex-col items-center gap-1 rounded-2xl border bg-surface-2/70 p-2 text-center shadow-md shadow-black/30 transition md:flex-row md:gap-3 md:p-3 md:text-left ${ring} ${connected ? '' : 'opacity-50'} ${placement.seat}`}
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
      <div className="flex w-full min-w-0 flex-col items-center gap-0.5 md:items-start">
        <span className="w-full truncate text-xs font-semibold md:text-sm">{nameText(player, me)}</span>
        <span className="flex flex-wrap items-center justify-center gap-1 md:justify-start">
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

function EmptySeat({ placement }) {
  return (
    <div
      role="listitem"
      className={`grid min-h-16 place-items-center rounded-2xl border border-dashed border-white/15 p-2 text-xs text-muted md:min-h-17 ${placement.seat}`}
    >
      빈 자리
    </div>
  )
}

const LIST_CLASS = {
  // The lobby: a 4x2 grid, 2 columns on the narrowest phones.
  grid: 'grid grid-cols-2 gap-3 min-[420px]:grid-cols-4',
  // A room game: a strip of four per row on phones; from md the list spans
  // the three columns of the parent grid (subgrid) and the stage sits in the
  // empty middle column.
  sides:
    'grid grid-cols-4 gap-2 md:col-span-3 md:col-start-1 md:row-start-1 md:grid-cols-subgrid md:grid-rows-4 md:gap-y-4',
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
      seats.push(<EmptySeat key={`empty-${i}`} placement={placement} />)
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
