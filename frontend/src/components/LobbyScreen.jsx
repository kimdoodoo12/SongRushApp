import { SONG_COUNTS } from '../game/state.js'
import { isReversed } from '../game/filters.js'
import FilterControls from './FilterControls.jsx'
import Seats from './Seats.jsx'
import ThemePicker from './ThemePicker.jsx'

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
      <button type="button" className="go col-span-full py-3 text-base" disabled={reversed} onClick={() => onStartGame()}>
        게임 시작
      </button>
    </div>
  )
}

// theme is the room look from the server (F-012): the host's picker sends
// a pick through onPickTheme and shows the server's theme as pressed; the
// other players see the picker disabled.
function LobbyScreen({
  room,
  players,
  error,
  notice,
  theme,
  onPickTheme,
  selection,
  onSelectionChange,
  onStartGame,
  onLeave,
}) {
  const link = `${window.location.origin}/r/${room.code}`
  const own = players.find((p) => p.name === room.me)
  const isHost = own !== undefined && own.host
  return (
    <div className="lobby room mx-auto flex w-full max-w-3xl flex-col gap-4">
      <section className="flex flex-col gap-3">
        <p className="room-code font-display flex flex-wrap items-center gap-2 text-base leading-6 text-muted">
          <span className="w-20 whitespace-nowrap">{'방 코드: '}</span>
          <span className="code text-2xl leading-[1.5] md:text-3xl">{room.code}</span>
        </p>
        <p className="share panel flex h-12 min-w-0 items-center gap-2 py-1.5 pr-1.5 pl-3 text-sm leading-5">
          <span className="min-w-0 flex-1 truncate text-muted">{link}</span>{' '}
          <button type="button" className="h-8 w-16 shrink-0 truncate px-2 py-0 text-sm leading-5" onClick={() => copyLink(link)}>
            복사
          </button>
        </p>
        {notice !== null && notice !== undefined && <p className="notice text-sm text-warn">{notice}</p>}
      </section>
      <section className="panel flex flex-col gap-2 p-4">
        <span className="font-display h-5 truncate text-sm leading-5 text-muted">{isHost ? '분위기' : '분위기 (방장이 고름)'}</span>
        <ThemePicker value={theme} onPick={onPickTheme} disabled={!isHost} />
      </section>
      <section className="panel p-4">
        <h2 className="font-display mb-3 h-6 truncate text-base leading-6 text-muted">{`참가자 ${players.length} / 8`}</h2>
        <ul className="roster" hidden>
          {players.map((p) => (
            <li key={p.name}>{rowText(p, room.me)}</li>
          ))}
        </ul>
        <Seats players={players} me={room.me} chat={NO_CHAT} scoreboard={null} correct={null} roundIndex={null} />
      </section>
      <section className="panel p-5">
        {isHost ? (
          <HostControls selection={selection} onSelectionChange={onSelectionChange} onStartGame={onStartGame} />
        ) : (
          <p className="line-clamp-2 h-10 text-center text-sm leading-5 text-muted md:h-5 md:line-clamp-1">{WAITING}</p>
        )}
        {error !== null && <p className="error mt-3 rounded-xl bg-bad/10 px-3 py-2 text-sm text-bad">{error}</p>}
      </section>
      <button type="button" className="self-center" onClick={onLeave}>
        나가기
      </button>
    </div>
  )
}

export default LobbyScreen
