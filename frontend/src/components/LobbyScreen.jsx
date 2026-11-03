import { useState } from 'react'
import { SONG_COUNTS } from '../game/state.js'

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

function HostControls({ onStartGame }) {
  const [songCount, setSongCount] = useState('10')
  return (
    <div className="start">
      <label>
        곡 수
        <select value={songCount} onChange={(e) => setSongCount(e.target.value)}>
          {SONG_COUNTS.map((n) => (
            <option key={n} value={String(n)}>
              {n}
            </option>
          ))}
        </select>
      </label>
      <button type="button" onClick={() => onStartGame(Number(songCount))}>
        게임 시작
      </button>
    </div>
  )
}

function LobbyScreen({ room, players, error, notice, onStartGame, onLeave }) {
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
      {isHost ? <HostControls onStartGame={onStartGame} /> : <p>{WAITING}</p>}
      {error !== null && <p className="error">{error}</p>}
      <button type="button" onClick={onLeave}>
        나가기
      </button>
    </div>
  )
}

export default LobbyScreen
