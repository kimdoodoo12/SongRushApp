import { useEffect, useRef, useState } from 'react'
import { settingsLine } from '../game/filters.js'
import { nameColour } from '../game/themes.js'
import Seats from './Seats.jsx'
import VolumeControl from './VolumeControl.jsx'
import { Trans, currentLanguage, messageText, useTranslation } from '../i18n/index.js'

const EQ_BARS = [0, 1, 2, 3, 4, 5, 6]
// The mark before a system line in the chat log.
const CHECK = String.fromCharCode(0x2714)
// The reveal card's title lines, in card order.
const TITLE_LANGS = ['ko', 'en', 'ja', 'romaji']
const HANGUL = /[ㄱ-ㆎ가-힣]/

function TimeBar({ max, remaining }) {
  const { t } = useTranslation()
  const percent = max > 0 ? (remaining / max) * 100 : 0
  return (
    <div
      className="timebar h-3.5 w-full overflow-hidden rounded-full"
      role="progressbar"
      aria-label={t('stage.timeLeft')}
      aria-valuemin={0}
      aria-valuemax={max}
      aria-valuenow={remaining}
    >
      <div
        className="timebar-fill h-full rounded-full transition-[width] duration-200 ease-linear"
        style={{ width: `${percent}%` }}
      />
    </div>
  )
}

// The spinning record and the equalizer while a round runs; decoration only.
// Until the song actually plays they stand still (D-101).
function Disc({ paused }) {
  return (
    <div
      className="disc-area flex flex-1 flex-wrap items-center justify-center gap-6"
      data-paused={paused ? 'true' : undefined}
    >
      <div className="record" aria-hidden="true" />
      <div className="eq" aria-hidden="true">
        {EQ_BARS.map((n) => (
          <span key={n} />
        ))}
      </div>
    </div>
  )
}

// The first accepted title of a language in the reveal frame, or null.
function firstTitle(titles, lang) {
  const list = titles === null || titles === undefined ? undefined : titles[lang]
  if (!Array.isArray(list) || typeof list[0] !== 'string' || list[0].trim() === '') return null
  return list[0]
}

// A title line's language label, the same box in every look and language.
function TitleLabel({ lang }) {
  const { t } = useTranslation()
  return (
    <span className="title-label w-14 shrink-0 truncate text-[11px] leading-4 tracking-wide text-muted">
      {t(`reveal.label.${lang}`)}
    </span>
  )
}

// The title of a language's line. The KO line is the frame's reveal text
// when that is Korean: titles.ko can be the raw source text, such as
// "나루토 (애니메이션)", while reveal is the cleaned title.
function lineTitle(reveal, lang) {
  if (lang === 'ko' && typeof reveal.title === 'string' && HANGUL.test(reveal.title)) return reveal.title
  return firstTitle(reveal.titles, lang)
}

// The answer card; the flip-in is a CSS animation of this one card. The
// big title is the line in the UI language when the frame has one, else
// the line equal to the reveal text, else the bare reveal text; the other
// languages are the smaller lines, so no title shows twice.
function RevealCard({ reveal }) {
  const { t } = useTranslation()
  const lang = currentLanguage()
  const lines = TITLE_LANGS.map((l) => ({ lang: l, title: lineTitle(reveal, l) })).filter((line) => line.title !== null)
  const main = lines.find((line) => line.lang === lang) ?? lines.find((line) => line.title === reveal.title) ?? null
  const others = lines.filter((line) => line !== main)
  return (
    <div className="flip grid w-full min-w-0 max-w-full flex-1 grid-cols-1 place-items-center">
      <section className="reveal card w-full min-w-0 max-w-[min(420px,100%)] p-5 text-center md:p-6">
        <p className="card-label font-display h-5 truncate text-[13px] leading-5 tracking-[0.14em]">{t('reveal.heading')}</p>
        {/* Two lines are kept for the title in every look, so the card has
            the same box whichever font wraps it. */}
        <div className="mt-1.5 mb-1 flex h-[65px] w-full min-w-0 max-w-full items-center justify-center gap-2 md:h-[75px]">
          {main !== null && <TitleLabel lang={main.lang} />}
          <h2 className="font-display line-clamp-2 min-w-0 flex-1 text-[26px] leading-tight wrap-anywhere md:text-[30px]">
            {main === null ? reveal.title : main.title}
          </h2>
        </div>
        {others.map((line) => (
          <p key={line.lang} className="reveal-alt flex h-5 w-full min-w-0 items-center justify-center gap-2 text-sm leading-5">
            <TitleLabel lang={line.lang} />
            <span className="min-w-0 truncate">{line.title}</span>
          </p>
        ))}
        <p className="card-slug mx-auto mt-1 block h-6 w-24 max-w-full truncate rounded-full px-3 py-0.5 text-xs leading-5 tracking-wide">{reveal.themeSlug}</p>
        {reveal.song !== null && reveal.song !== undefined && <p className="mt-2 h-6 min-w-0 truncate text-base leading-6 wrap-anywhere">{reveal.song}</p>}
        {reveal.artists.length > 0 && <p className="h-5 min-w-0 truncate text-sm leading-5 wrap-anywhere text-muted">{reveal.artists.join(', ')}</p>}
        <p className="card-winner font-display mt-3 block h-7 w-full truncate rounded-full px-3.5 py-1 text-sm leading-5">
          {reveal.winner === null || reveal.winner === undefined ? t('reveal.noWinner') : t('reveal.winner', { name: reveal.winner })}
        </p>
      </section>
    </div>
  )
}

function ChatForm({ onSend }) {
  const { t } = useTranslation()
  const [text, setText] = useState('')

  function handleSubmit(event) {
    event.preventDefault()
    if (text.trim() === '') return
    onSend(text)
    setText('')
  }

  return (
    <form className="chat-form send flex items-end gap-2 p-2 md:p-3" onSubmit={handleSubmit}>
      <label className="flex min-w-0 flex-1 flex-col gap-1 text-xs text-muted">
        {t('chat.input')}
        <input
          type="text"
          value={text}
          onChange={(e) => setText(e.target.value)}
          autoComplete="off"
          placeholder={t('chat.placeholder')}
        />
      </label>
      <button type="submit" className="go h-11 w-24 shrink-0 truncate px-2">
        {t('chat.send')}
      </button>
    </form>
  )
}

// The chat log, scrolled to the newest line whenever a line arrives. A
// system line (D-094) has no name marker and uses the good colour.
function ChatLog({ chat }) {
  const { t } = useTranslation()
  const ref = useRef(null)
  useEffect(() => {
    const el = ref.current
    if (el !== null) el.scrollTop = el.scrollHeight
  }, [chat])
  return (
    <ul ref={ref} className="chat-log flex h-36 flex-col gap-1 overflow-y-auto px-3 py-2 text-[15px] md:h-44" aria-label={t('chat.label')}>
      {chat.map((line) =>
        line.kind === 'system' ? (
          <li key={line.id} className="chat-system min-w-0 wrap-anywhere">
            {`${CHECK} ${t('chat.correctLine', { name: line.winner })}`}
          </li>
        ) : (
          <li key={line.id} className="min-w-0 wrap-anywhere">
            <span
              aria-hidden="true"
              className="chat-mark mr-2 inline-block size-2.5 rounded-full align-middle"
              style={{ background: typeof line.name === 'string' ? nameColour(line.name) : 'currentColor' }}
            />
            {line.text}
          </li>
        ),
      )}
    </ul>
  )
}

// The tail of a score or ranking row: " N점", and " (나)" on the player's
// own row. These parts never shrink, so a long name is cut instead; the
// leading spaces are kept (whitespace-pre) because the row is a flex box.
function ScoreTail({ row, me }) {
  const { t } = useTranslation()
  return (
    <>
      <span className="shrink-0 whitespace-pre">{` ${t('score.points', { score: row.score })}`}</span>
      {row.name === me && <span className="shrink-0 whitespace-pre">{` ${t('seat.me')}`}</span>}
    </>
  )
}

function Scoreboard({ rows, me }) {
  const { t } = useTranslation()
  return (
    <ul className="scoreboard grid grid-cols-2 gap-1.5 text-xs md:grid-cols-4" aria-label={t('score.board')}>
      {rows.map((row) => (
        <li key={row.name} className="chip flex h-6 min-w-0 items-center justify-center px-2.5 py-0.5 leading-4">
          <span className="min-w-0 truncate">{row.name}</span>
          <ScoreTail row={row} me={me} />
        </li>
      ))}
    </ul>
  )
}

function Ranking({ rows, me }) {
  const { t } = useTranslation()
  return (
    <ul className="ranking flex flex-col gap-1.5" aria-label={t('score.ranking')}>
      {rows.map((row) => (
        <li
          key={row.name}
          className="rank-row panel flex h-10 min-w-0 items-center px-4 py-2 text-sm leading-5"
        >
          <span className="shrink-0 whitespace-pre">{`${t('score.rank', { rank: row.rank })} `}</span>
          <span className="min-w-0 truncate">{row.name}</span>
          <ScoreTail row={row} me={me} />
        </li>
      ))}
    </ul>
  )
}

// The skip vote: the button toggles the own vote; the count is from skipState.
function SkipControls({ voted, count, onSkip }) {
  const { t } = useTranslation()
  return (
    <div className="skip flex items-center gap-3 md:col-start-2">
      <button type="button" onClick={onSkip} className={`h-11 w-28 shrink-0 truncate px-2 ${voted ? 'skip-on' : ''}`}>
        {voted ? t('stage.skipCancel') : t('stage.skip')}
      </button>
      {count !== null && (
        <span className="chip h-8 w-24 shrink-0 truncate px-2 py-1 text-center text-sm leading-5 tabular-nums">{t('stage.skipCount', { votes: count.votes, needed: count.needed })}</span>
      )}
    </div>
  )
}

const LOAD_KEY = {
  loading: 'stage.loading',
  failed: 'stage.loadFailed',
}

// The game and its result. In a room the result has no 다시 하기; once the
// room is back in its lobby, 대기실로 shows the lobby screen. A room also
// shows everyone's scores, the skip vote and the final ranking.
function GameScreen({ state, onChat, onSkip, onReset, onToLobby, onUnlockSound, volume, onVolumeLevel, onToggleMute }) {
  const { t } = useTranslation()
  const {
    screen,
    round,
    load,
    timer,
    chat,
    correct,
    reveal,
    score,
    error,
    finalScore,
    room,
    lobbyOpen,
    soundBlocked,
    scoreboard,
    ranking,
    watching,
    started,
    skipVoted,
    skipCount,
    waitingNext,
    replaced,
    settings,
    players,
  } = state
  const line = settings === null ? '' : settingsLine(settings, t)
  const me = room === null ? null : room.me
  const canSkip = room !== null && screen === 'game' && started && !watching
  // The hint: between the start and the reveal.
  const playing = screen === 'game' && started && reveal === null
  // The record and the equalizer (D-101, 087): drawn whenever a round is
  // current and not revealed, so from prepare on; they stand still until the
  // song actually plays (before the start, while loading, after a failed load).
  const discShown = screen === 'game' && round !== null && reveal === null
  const discPaused = !started || load === 'loading' || load === 'failed'
  const clock = timer === null ? null : t('stage.seconds', { seconds: Math.ceil(timer.remaining / 1000) })
  const stage = (
    <section
      aria-label={t('stage.label')}
      className="stage panel relative z-10 flex min-w-0 flex-col items-stretch gap-3 p-4 md:col-start-2 md:row-start-1 md:min-h-[340px] md:gap-4 md:p-5"
    >
      <div className="status stage-head flex flex-wrap items-center gap-3">
        {round === null && !watching && !waitingNext && <span className="text-muted">{t('stage.connecting')}</span>}
        {round === null && waitingNext && <span className="text-muted">{t('stage.waitingNext')}</span>}
        {round !== null && (
          <span className="round font-display flex h-10 w-40 items-center gap-1 overflow-hidden text-[15px] leading-5 whitespace-nowrap md:h-12 md:w-46">
            {/* The label sits before or after the number, by language. A
                label before it (lead) is a fixed box, so the number box
                stays put whichever look's font draws the label. */}
            <Trans
              i18nKey="stage.round"
              values={{ index: round.index + 1, total: round.total }}
              components={{
                lead: <span className="round-lead block w-12 shrink-0 truncate" />,
                num: (
                  <span className="round-num block h-8 w-[3.9em] shrink-0 overflow-hidden text-right text-2xl leading-8 tabular-nums md:h-9 md:text-3xl md:leading-9" />
                ),
              }}
            />
          </span>
        )}
        {score !== null && <span className="chip h-8 w-24 shrink-0 truncate px-2 py-1 text-center text-sm leading-5 tabular-nums">{t('stage.score', { score })}</span>}
        {clock !== null && <span className="clock ml-auto h-10 w-24 text-4xl leading-10 tabular-nums md:h-12 md:w-29 md:text-[44px] md:leading-12">{clock}</span>}
      </div>
      {/* The own volume (D-099): a row of its own, right after the head, in
          every stage state. */}
      <VolumeControl volume={volume} onLevel={onVolumeLevel} onToggleMute={onToggleMute} />
      {screen === 'game' && watching && (
        <p className="watching chip line-clamp-2 h-15 rounded-xl px-3 py-2 text-center text-sm leading-5">{t('stage.watching')}</p>
      )}
      {screen === 'game' && soundBlocked && (
        <button type="button" className="sound-prompt go h-11 w-44 self-center truncate px-2 py-0 animate-pulse" onClick={onUnlockSound}>
          {t('stage.unlockSound')}
        </button>
      )}
      {load !== null && LOAD_KEY[load] !== undefined && (
        <p className={`h-5 truncate text-center text-sm leading-5 ${load === 'failed' ? 'text-bad' : 'text-muted'}`}>{t(LOAD_KEY[load])}</p>
      )}
      {discShown && <Disc paused={discPaused} />}
      {correct !== null && (
        // Right-to-left on the box, left-to-right on the text: when the line is
        // too long the ellipsis cuts the start of the name, so " 정답!" stays.
        // The text stays one node, so the banner reads "<name> 정답!" as is
        // (chat.correctLine in the current language).
        <p className="correct font-display h-10 w-60 max-w-full self-center truncate rounded-full px-4 py-1.5 text-center text-lg leading-7 [direction:rtl]">
          <span className="[direction:ltr] [unicode-bidi:isolate]">{t('chat.correctLine', { name: correct })}</span>
        </p>
      )}
      {reveal !== null && <RevealCard reveal={reveal} />}
      {timer !== null && <TimeBar max={timer.max} remaining={timer.remaining} />}
      {(playing || canSkip) && (
        <div className="stage-foot grid grid-cols-1 items-center gap-3 md:grid-cols-[minmax(0,1fr)_auto]">
          {playing && <span className="stage-hint line-clamp-2 h-10 text-sm leading-5 text-muted">{t('stage.hint')}</span>}
          {canSkip && <SkipControls voted={skipVoted} count={skipCount} onSkip={onSkip} />}
        </div>
      )}
      {error !== null && <p className="error rounded-xl bg-bad/10 px-3 py-2 text-sm text-bad">{messageText(t, error)}</p>}
      {scoreboard !== null && <Scoreboard rows={scoreboard} me={me} />}
      {ranking !== null && <Ranking rows={ranking} me={me} />}
      {screen === 'over' && (
        <section className="over flex flex-col items-center gap-3 text-center">
          {finalScore !== null && <p className="font-display h-8 w-full truncate text-2xl leading-8">{t('score.final', { score: finalScore })}</p>}
          {room !== null && lobbyOpen && (
            <button type="button" className="go h-11 w-32 truncate px-2 py-0" onClick={onToLobby}>
              {t('game.toLobby')}
            </button>
          )}
        </section>
      )}
      {screen === 'disconnected' && (
        <section className="over flex flex-col items-center gap-3 text-center">
          {finalScore !== null && <p className="font-display h-8 w-full truncate text-2xl leading-8">{t('score.final', { score: finalScore })}</p>}
          <p className="text-sm text-bad">{replaced ? t('game.replaced') : t('game.disconnected')}</p>
          <button type="button" className="go h-11 w-32 truncate px-2 py-0" onClick={onReset}>
            {t('game.reset')}
          </button>
        </section>
      )}
    </section>
  )
  const chatPanel = (
    <section className="chat panel min-w-0 overflow-hidden md:col-start-2 md:row-start-2">
      <ChatLog chat={chat} />
      {screen === 'game' && <ChatForm onSend={onChat} />}
    </section>
  )
  // The title row: the room code chip (no 방 코드: label in a game) and the
  // one settings line of this game.
  const titleRow = (room !== null || line !== '') && (
    <div className="room-top flex items-center justify-between gap-3">
      {room !== null && <span className="code shrink-0 text-sm leading-[1.5] md:text-base">{room.code}</span>}
      {line !== '' && <p className="settings chip h-7 w-52 min-w-0 truncate px-3 py-0.5 text-center text-[13px] leading-5">{line}</p>}
    </div>
  )
  if (room === null) {
    return (
      <div className="game room mx-auto flex w-full max-w-3xl flex-col gap-3 md:gap-4">
        {titleRow}
        {stage}
        {chatPanel}
      </div>
    )
  }
  return (
    <div className="game room flex flex-col gap-3 md:gap-4">
      {titleRow}
      <div className="flex flex-col gap-3 md:grid md:grid-cols-[minmax(0,10rem)_minmax(0,1fr)_minmax(0,10rem)] md:gap-x-6 md:gap-y-4 lg:grid-cols-[minmax(0,11rem)_minmax(0,1fr)_minmax(0,11rem)]">
        <Seats
          layout="sides"
          players={players}
          me={me}
          chat={chat}
          scoreboard={scoreboard}
          correct={correct}
          roundIndex={round === null ? null : round.index}
        />
        {stage}
        {chatPanel}
      </div>
    </div>
  )
}

export default GameScreen
