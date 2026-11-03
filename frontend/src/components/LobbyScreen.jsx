import { SONG_COUNTS } from '../game/state.js'
import { isReversed } from '../game/filters.js'
import FilterControls from './FilterControls.jsx'

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
    <div className="start">
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
      <button type="button" disabled={reversed} onClick={() => onStartGame()}>
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
    <div className="lobby">
      <p className="room-code">{`방 코드: ${room.code}`}</p>
      {notice !== null && notice !== undefined && <p className="notice">{notice}</p>}
      <p className="share">
        <span>{link}</span>{' '}
        <button type="button" onClick={() => copyLink(link)}>
          복사
        </button>
      </p>
      <ul className="roster">
        {players.map((p) => (
          <li key={p.name}>{rowText(p, room.me)}</li>
        ))}
      </ul>
      {isHost ? (
        <HostControls selection={selection} onSelectionChange={onSelectionChange} onStartGame={onStartGame} />
      ) : (
        <p>{WAITING}</p>
      )}
      {error !== null && <p className="error">{error}</p>}
      <button type="button" onClick={onLeave}>
        나가기
      </button>
    </div>
  )
}

export default LobbyScreen
