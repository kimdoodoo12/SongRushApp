import { SONG_COUNTS } from '../game/state.js'
import { isReversed } from '../game/filters.js'
import FilterControls from './FilterControls.jsx'
import Seats from './Seats.jsx'

// The lobby has no chat, so its seats show no bubbles.
const NO_CHAT = []
const WAITING = '방장이 게임을 시작하기를 기다리는 중...'

// One roster row: the name, then (방장) for the host, then (나) for the player.
function rowText(player, me) {
  let text = player.name
  if (player.host) text += ' (방장)'
  if (player.name === me) text += ' (나)'
  return text
}

function copyLink(link) {
  if (navigator.clipboard === undefined || navigator.clipboard === null) return
  navigator.clipboard.writeText(link).catch((err) => {
    // The link stays on screen as text, so it can still be copied by hand.
    console.warn('clipboard write failed', err)
  })
}

// 곡 수 and the filters are the App-level selection, so they survive a game
// and the return to the lobby (D-062, F-008).
function HostControls({ selection, onSelectionChange, onStartGame }) {
  const reversed = isReversed(selection)
  return (
    <div className="start grid grid-cols-2 gap-3 sm:grid-cols-3 [&_label]:flex [&_label]:flex-col [&_label]:gap-1 [&_label]:text-xs [&_label]:text-muted">
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
      <button type="button" className="col-span-full py-3 text-base" disabled={reversed} onClick={() => onStartGame()}>
        게임 시작
      </button>
    </div>
  )
}

function LobbyScreen({ room, players, error, notice, selection, onSelectionChange, onStartGame, onLeave }) {
  const link = `${window.location.origin}/r/${room.code}`
  const own = players.find((p) => p.name === room.me)
  const isHost = own !== undefined && own.host
  return (
    <div className="lobby mx-auto flex w-full max-w-3xl flex-col gap-4">
      <section className="rounded-2xl border border-line bg-surface/85 p-5 shadow-xl shadow-black/40">
        <p className="room-code text-sm text-muted">
          <span>{'방 코드: '}</span>
          <span className="ml-1 font-mono text-3xl font-extrabold tracking-[0.3em] text-white md:text-4xl">{room.code}</span>
        </p>
        <p className="share mt-3 flex min-w-0 items-center gap-2 rounded-xl border border-line bg-slate-950/50 py-1.5 pr-1.5 pl-3 text-sm">
          <span className="min-w-0 flex-1 truncate text-muted">{link}</span>{' '}
          <button type="button" className="shrink-0 px-3 py-1 text-sm" onClick={() => copyLink(link)}>
            복사
          </button>
        </p>
        {notice !== null && notice !== undefined && <p className="notice mt-3 text-sm text-warn">{notice}</p>}
      </section>
      <section className="rounded-2xl border border-line bg-surface/60 p-4 shadow-xl shadow-black/30">
        <h2 className="mb-3 text-sm font-semibold text-muted">{`참가자 ${players.length} / 8`}</h2>
        <ul className="roster" hidden>
          {players.map((p) => (
            <li key={p.name}>{rowText(p, room.me)}</li>
          ))}
        </ul>
        <Seats players={players} me={room.me} chat={NO_CHAT} scoreboard={null} correct={null} roundIndex={null} />
      </section>
      <section className="rounded-2xl border border-line bg-surface/85 p-5 shadow-xl shadow-black/40">
        {isHost ? (
          <HostControls selection={selection} onSelectionChange={onSelectionChange} onStartGame={onStartGame} />
        ) : (
          <p className="text-center text-sm text-muted">{WAITING}</p>
        )}
        {error !== null && <p className="error mt-3 rounded-xl bg-bad/10 px-3 py-2 text-sm text-bad">{error}</p>}
      </section>
      <button type="button" className="self-center bg-white/5 text-ink shadow-none ring-1 ring-line enabled:hover:bg-white/10" onClick={onLeave}>
        나가기
      </button>
    </div>
  )
}

export default LobbyScreen
