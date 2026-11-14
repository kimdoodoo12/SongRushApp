import { Fragment, useEffect, useLayoutEffect, useRef, useState } from 'react'
import { settingsLine } from '../game/filters.js'
import { isAudible } from '../game/state.js'
import { nameColour } from '../game/themes.js'
import ChoiceGrid from './ChoiceGrid.jsx'
import Deck from './Deck.jsx'
import Seats from './Seats.jsx'
import ThemePicker from './ThemePicker.jsx'
import VolumeControl from './VolumeControl.jsx'
import { Trans, messageText, useTranslation } from '../i18n/index.js'

// The mark before a system line in the chat log.
const CHECK = String.fromCharCode(0x2714)
// The reveal card's title lines, in card order.
const TITLE_LANGS = ['ko', 'en', 'ja', 'romaji']
const HANGUL = /[ㄱ-ㆎ가-힣]/
// The clock turns .low at this many seconds or less (the mockup's rule).
const LOW_SECONDS = 10
// The chat log follows new lines while it is scrolled to within this many
// pixels of its end (the mockup's rule); the own line always scrolls it.
const STICK_PX = 60

const LOAD_KEY = {
  loading: 'stage.loading',
  failed: 'stage.loadFailed',
}

// The time bar: the fill shrinks from the left by scaleX, as in the mockup.
// It is a progressbar only while a song has a clock.
function TimeBar({ timer }) {
  const { t } = useTranslation()
  if (timer === null) {
    return (
      <div className="timebar">
        <div className="timebar-fill" />
      </div>
    )
  }
  const ratio = timer.max > 0 ? Math.min(1, Math.max(0, timer.remaining / timer.max)) : 0
  return (
    <div
      className="timebar"
      role="progressbar"
      aria-label={t('stage.timeLeft')}
      aria-valuemin={0}
      aria-valuemax={timer.max}
      aria-valuenow={timer.remaining}
    >
      <div className="timebar-fill" style={{ transform: `scaleX(${ratio})` }} />
    </div>
  )
}

// The first accepted title of a language in the reveal frame, or null.
function firstTitle(titles, lang) {
  const list = titles === null || titles === undefined ? undefined : titles[lang]
  if (!Array.isArray(list) || typeof list[0] !== 'string' || list[0].trim() === '') return null
  return list[0]
}

// The title of a language's line. The KO line is the frame's reveal text
// when that is Korean: titles.ko can be the raw source text, such as
// "나루토 (애니메이션)", while reveal is the cleaned title.
function lineTitle(reveal, lang) {
  if (lang === 'ko' && typeof reveal.title === 'string' && HANGUL.test(reveal.title)) return reveal.title
  return firstTitle(reveal.titles, lang)
}

// The local HH:MM of a chat line's arrival (the mockup's .tm), or ''.
function clockTime(at) {
  if (typeof at !== 'number') return ''
  const d = new Date(at)
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}

// The names that solved a reveal's song: its ranking (ranked) or its winner.
function solversOf(reveal) {
  if (reveal === null) return []
  if (Array.isArray(reveal.ranking)) return reveal.ranking.map((row) => row.name)
  return reveal.winner === null || reveal.winner === undefined ? [] : [reveal.winner]
}

// The slug, then the song and the artists when the frame has them:
// "OP1 · <b>song</b> — artist, artist".
function ThemeLine({ reveal }) {
  const hasSong = typeof reveal.song === 'string' && reveal.song !== ''
  const artists = Array.isArray(reveal.artists) ? reveal.artists : []
  return (
    <p className="theme-line">
      {reveal.themeSlug}
      {hasSong && (
        <>
          {' · '}
          <b>{reveal.song}</b>
        </>
      )}
      {artists.length > 0 && ` — ${artists.join(', ')}`}
    </p>
  )
}

// The mockup's answer card in the panel. A ranked song (cycle 101) names
// every solver in .who and lists its ranking in ol.rank; a first-come song
// names its winner. The four titles are dl.titles, the Korean one dd.ko.
// There is no answer-time line (D-123).
function RevealCard({ reveal, last }) {
  const { t } = useTranslation()
  const lines = TITLE_LANGS.map((lang) => ({ lang, title: lineTitle(reveal, lang) })).filter((line) => line.title !== null)
  const ranked = Array.isArray(reveal.ranking)
  const solvers = ranked ? reveal.ranking.map((row) => row.name) : []
  let who
  if (ranked) who = solvers.length > 0 ? t('reveal.solvers', { names: solvers.join(', ') }) : null
  else who = reveal.winner === null || reveal.winner === undefined ? null : t('reveal.winner', { name: reveal.winner })
  return (
    <div className="reveal">
      <h3>{t('reveal.heading')}</h3>
      {who === null ? <p className="who none">{t('reveal.noWinner')}</p> : <p className="who">{who}</p>}
      <dl className="titles">
        {lines.length === 0 ? (
          <>
            <dt />
            <dd className="ko">{reveal.title}</dd>
          </>
        ) : (
          lines.map((line) => (
            <Fragment key={line.lang}>
              <dt>{t(`reveal.label.${line.lang}`)}</dt>
              <dd className={line.lang === 'ko' ? 'ko' : undefined}>{line.title}</dd>
            </Fragment>
          ))
        )}
      </dl>
      {ranked && reveal.ranking.length > 0 && (
        <ol className="rank" aria-label={t('reveal.ranking')}>
          {reveal.ranking.map((row) => (
            <li key={row.name}>
              <span className="p">{t('reveal.rankPlace', { rank: row.rank })}</span>{' '}
              <span>{row.name}</span>{' '}
              <span className="s">{t('reveal.rankPoints', { points: row.points })}</span>
            </li>
          ))}
        </ol>
      )}
      <ThemeLine reveal={reveal} />
      <p className="next">{last ? t('reveal.nextFinal') : t('reveal.next')}</p>
    </div>
  )
}

// The final ranking card: ol.rank in server order, each row "N위 name N점",
// with " (나)" after the own row's points.
function FinalCard({ ranking, me, finalScore, toLobby, onToLobby }) {
  const { t } = useTranslation()
  return (
    <div className="reveal">
      <h3>{t('score.ranking')}</h3>
      {ranking !== null && (
        <ol className="rank" aria-label={t('score.ranking')}>
          {ranking.map((row) => (
            <li key={row.name}>
              <span className="p">{t('score.rank', { rank: row.rank })}</span>{' '}
              <span>{row.name}</span>{' '}
              <span>
                <span className="s">{t('score.points', { score: row.score })}</span>
                {row.name === me && ` ${t('seat.me')}`}
              </span>
            </li>
          ))}
        </ol>
      )}
      {finalScore !== null && <p className="sub">{t('score.final', { score: finalScore })}</p>}
      {toLobby && (
        <button type="button" className="btn primary" onClick={onToLobby}>
          {t('game.toLobby')}
        </button>
      )}
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
    <form className="send" onSubmit={handleSubmit}>
      <input
        type="text"
        maxLength={200}
        value={text}
        onChange={(e) => setText(e.target.value)}
        autoComplete="off"
        placeholder={t('chat.placeholder')}
        aria-label={t('chat.input')}
      />
      <button type="submit">{t('chat.send')}</button>
    </form>
  )
}

// The chat log: one .msg per line, the name colour as its <i>, the arrival
// time as .tm and the line as .t; the own lines are .mine. A correct line
// (D-094) is .msg.sys.correct, a client-side notice (cycle 111) .msg.sys,
// both in the language current when shown.
function ChatLog({ chat, me }) {
  const { t } = useTranslation()
  const ref = useRef(null)
  const stickRef = useRef(true)
  const last = chat.length === 0 ? null : chat[chat.length - 1]
  const lastMine = last !== null && last.kind === undefined && last.name === me
  useLayoutEffect(() => {
    const el = ref.current
    if (el !== null && (stickRef.current || lastMine)) el.scrollTop = el.scrollHeight
  }, [chat, lastMine])

  function handleScroll() {
    const el = ref.current
    if (el !== null) stickRef.current = el.scrollHeight - el.scrollTop - el.clientHeight < STICK_PX
  }

  return (
    <div ref={ref} className="msgs" role="log" aria-live="off" onScroll={handleScroll}>
      {chat.map((line) => {
        if (line.kind === 'notice') {
          // The wrong-pick line is red (cycle 124, D-131): .msg.wrong in
          // choice.css, as room.css has no red and is never edited.
          return (
            <div key={line.id} className={line.key === 'chat.wrongLine' ? 'msg sys wrong' : 'msg sys'}>
              {t(line.key, line.values)}
            </div>
          )
        }
        if (line.kind === 'system') {
          return (
            <div key={line.id} className="msg sys correct">
              {typeof line.rank === 'number'
                ? `${CHECK} ${t('chat.correctRanked', { name: line.winner, rank: line.rank })}`
                : `${CHECK} ${t('chat.correctLine', { name: line.winner })}`}
            </div>
          )
        }
        return (
          <div key={line.id} className={line.name === me ? 'msg mine' : 'msg'}>
            <i style={{ background: typeof line.name === 'string' ? nameColour(line.name) : 'currentColor' }} />
            <span className="tm">{clockTime(line.at)}</span>
            <span className="t">{line.text}</span>
          </div>
        )
      })}
    </div>
  )
}

// Today's scores, for assistive technology: the seats show the same points,
// so these stay out of sight (the mockup has no separate scoreboard).
function ScoreLists({ score, scoreboard, me }) {
  const { t } = useTranslation()
  if (score === null && scoreboard === null) return null
  return (
    <div className="sr-only">
      {score !== null && <p>{t('stage.score', { score })}</p>}
      {scoreboard !== null && (
        <ul aria-label={t('score.board')}>
          {scoreboard.map((row) => (
            <li key={row.name}>
              <span>{row.name}</span>
              <span>{` ${t('score.points', { score: row.score })}`}</span>
              {row.name === me && <span>{` ${t('seat.me')}`}</span>}
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}

// The room frame in the mockup's markup (D-124, cycle 110), shared by the
// start screen, the lobby and the game (cycle 114): header (the logo,
// .roominfo with the room code and the settings line, the volume, and the
// .moodbar picker), then .main with the stage and the chat. picker is null
// (no picker) or { value, onPick, disabled }; a disabled picker is labelled
// as the host's pick. banner is the reconnecting line, the first child of
// .stage .inner; footer the page footer, its last. chat is null (no chat
// aside) or { lines, me, online, onSend }, onSend null without the form.
// The header's height is measured (cycle 119 review) and set as --hd (px) on
// .app, for scroll.css and choice.css: longer mood names in en or ja wrap the
// mood bar. Without ResizeObserver it is measured once, on mount.
export function Frame({ code, line, picker, volume, onVolumeLevel, onToggleMute, banner, footer, chat, solo = false, children }) {
  const { t } = useTranslation()
  const headerRef = useRef(null)
  const [headerHeight, setHeaderHeight] = useState(null)

  useLayoutEffect(() => {
    const header = headerRef.current
    if (header === null) return undefined
    const measure = () => setHeaderHeight(header.offsetHeight)
    measure()
    if (typeof ResizeObserver === 'undefined') return undefined
    const observer = new ResizeObserver(measure)
    observer.observe(header)
    return () => observer.disconnect()
  }, [])

  return (
    <>
      <div className="scan" aria-hidden="true" />
      <div className="app" style={headerHeight === null ? undefined : { '--hd': `${headerHeight}px` }}>
        <header ref={headerRef}>
          <div className="logo">
            <span className="eq" aria-hidden="true">
              <i />
              <i />
              <i />
            </span>
            SongRush
          </div>
          <div className="roominfo">
            {code !== null && <span className="code">{code}</span>}
            {line !== '' && <p className="settings">{line}</p>}
          </div>
          <div className="hd-right">
            <VolumeControl volume={volume} onLevel={onVolumeLevel} onToggleMute={onToggleMute} />
          </div>
          {picker !== null && (
            <div className="moodbar">
              <span className="ml">{picker.disabled ? t('theme.labelGuest') : t('theme.label')}</span>
              <ThemePicker value={picker.value} onPick={picker.onPick} disabled={picker.disabled} />
            </div>
          )}
        </header>

        <div className={solo ? 'main solo' : 'main'}>
          <section className="stage" aria-label={t('stage.label')}>
            <div className="inner">
              {banner}
              {children}
              {footer}
            </div>
          </section>

          {chat !== null && (
            <aside className="chat" aria-label={t('chat.label')}>
              <div className="chat-hd">
                <b>{t('chat.label')}</b>
                <span className="live">
                  <span className="dot" />
                  {t('chat.live', { n: chat.online })}
                </span>
              </div>
              <ChatLog chat={chat.lines} me={chat.me} />
              {chat.onSend !== null && <ChatForm onSend={chat.onSend} />}
            </aside>
          )}
        </div>
      </div>
    </>
  )
}

// The game room inside the frame: the stage top, the time bar, the deck
// with the panel (and a choice room's tiles), and the seats. banner is the
// reconnecting line and footer the page footer, both from App. A room shows
// its code and the mood picker (enabled for the host); before joined (room
// null) neither. choiceGame is App's body.choice-mode flag, for the deck.
function GameScreen({
  state,
  theme,
  choiceGame = false,
  onPickTheme,
  onChat,
  onPick,
  onSkip,
  onReset,
  onToLobby,
  onUnlockSound,
  volume,
  onVolumeLevel,
  onToggleMute,
  banner,
  footer,
}) {
  const { t } = useTranslation()
  const {
    screen,
    round,
    load,
    timer,
    chat,
    correct,
    correctNames,
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
    reached,
    skipVoted,
    skipCount,
    pickCount,
    waitingNext,
    replaced,
    settings,
    players,
    choices,
    picked,
    pickWrong,
    pickLocked,
    correctChoice,
  } = state
  const line = settings === null ? '' : settingsLine(settings, t)
  const me = room === null ? null : room.me
  const own = players.find((p) => p.name === me)
  const isHost = own !== undefined && own.host
  const online = players.filter((p) => p.connected !== false).length
  const canSkip = room !== null && screen === 'game' && started && !watching
  const playing = screen === 'game' && started && reveal === null
  // While the song is audible (isAudible) body.playing runs the disc and the
  // logo bars (the mockup's CSS).
  const audible = isAudible(state)
  // A choice room's tiles (cycle 100), below the deck and the panel (cycle
  // 112, the mockup's .choice-mode .now): shown from prepare to the next
  // prepare, so also with the reveal card. They work while the song is
  // audible (087 reached), for a player who has not picked, until the first
  // correct answer. In ranked correct stays null (cycle 101, R4), so only
  // the own pick locks them.
  const gridShown = screen === 'game' && choices !== null
  // How many have picked (cycle 123, D-130): during a choice song only. A
  // watcher has no skip button but sees it, alone in the row.
  const pickShown = gridShown && started && reveal === null && pickCount !== null
  const canPick = started && reached && !watching && !pickLocked && picked === null && correct === null && reveal === null
  const seconds = timer === null ? null : Math.ceil(timer.remaining / 1000)
  const loadText = load !== null && LOAD_KEY[load] !== undefined ? t(LOAD_KEY[load]) : null
  const last = round !== null && round.index + 1 >= round.total
  // The deck (the mockup's startRound, reveal and finish): idle before the
  // first song and after a game, revealed from the reveal to the next
  // prepare. Its frame shows the reveal's cover and Korean title; the stamp
  // reads 통과 when nobody solved the song.
  const deckIdle = !(screen === 'game' && (round !== null || reveal !== null))
  const solvers = solversOf(reveal)
  const missed = reveal !== null && solvers.length === 0
  let outcome = 'none'
  if (solvers.length > 0) outcome = me !== null && solvers.includes(me) ? 'me' : 'other'
  const coverTitle = reveal === null ? null : (lineTitle(reveal, 'ko') ?? reveal.title)

  useEffect(() => {
    document.body.classList.toggle('playing', audible)
    return () => document.body.classList.remove('playing')
  }, [audible])

  let panel
  if (screen === 'disconnected') {
    panel = (
      <div className="reveal">
        {finalScore !== null && <p className="sub">{t('score.final', { score: finalScore })}</p>}
        <p className="who none">{replaced ? t('game.replaced') : t('game.disconnected')}</p>
        <button type="button" className="btn primary" onClick={onReset}>
          {t('game.reset')}
        </button>
      </div>
    )
  } else if (screen === 'over') {
    // The last song's card stays under the final ranking.
    panel = (
      <>
        <FinalCard
          ranking={ranking}
          me={me}
          finalScore={finalScore}
          toLobby={room !== null && lobbyOpen}
          onToLobby={onToLobby}
        />
        {reveal !== null && <RevealCard reveal={reveal} last={last} />}
      </>
    )
  } else if (reveal !== null) {
    panel = <RevealCard reveal={reveal} last={last} />
  } else {
    let hint
    if (correct !== null) hint = t('chat.correctLine', { name: correct })
    else if (round === null) hint = waitingNext ? t('stage.waitingNext') : watching ? t('stage.watching') : t('stage.connecting')
    else if (gridShown) hint = playing ? t('stage.choiceHint') : t('stage.choiceSoon')
    else hint = playing ? t('stage.hint') : t('stage.soon')
    const subs = []
    if (round !== null && loadText !== null) subs.push(loadText)
    if (round !== null && correct === null) {
      if (playing) subs.push(gridShown ? t('stage.choiceSub') : t('stage.sub'))
      else subs.push(t('stage.soonSub'))
    }
    // The mockup's p.pickmsg after the own wrong pick, until the reveal.
    const pickMsg = gridShown && round !== null && pickWrong && picked !== null
    panel = (
      <>
        <p className="hint">{hint}</p>
        {subs.map((text) => (
          <p key={text} className="sub">
            {text}
          </p>
        ))}
        {pickMsg && <p className="pickmsg">{t('stage.pickWrong', { n: picked + 1 })}</p>}
      </>
    )
  }

  return (
    <Frame
      code={room === null ? null : room.code}
      line={line}
      picker={room === null ? null : { value: theme, onPick: onPickTheme, disabled: !isHost }}
      volume={volume}
      onVolumeLevel={onVolumeLevel}
      onToggleMute={onToggleMute}
      banner={banner}
      footer={footer}
      chat={{ lines: chat, me, online, onSend: screen === 'game' ? onChat : null }}
    >
      <div className="stage-top">
        <div className="round">
          <span className="tag">{`${t(`theme.${theme}`)} · ${t(`mood.tag.${theme}`)}`}</span>
          {round !== null && (
            <Trans
              i18nKey="stage.round"
              values={{ index: round.index + 1, total: round.total }}
              components={{ lead: <span />, num: <span className="num" /> }}
            />
          )}
        </div>
        <div className={seconds !== null && seconds <= LOW_SECONDS ? 'clock low' : 'clock'}>
          {seconds === null ? '' : t('stage.seconds', { seconds })}
        </div>
      </div>
      <TimeBar timer={timer} />

      <div className="now">
        <Deck
          idle={deckIdle}
          revealed={screen === 'game' && reveal !== null}
          reveal={reveal}
          title={coverTitle}
          outcome={outcome}
          number={round === null ? 1 : round.index + 1}
          missed={missed}
          mood={theme}
          choice={choiceGame}
        />
        <div className="panel">
          {panel}
          {/* A mid-song joiner watches until the next song, also
              through its reveal; before a round it is the hint. */}
          {screen === 'game' && watching && (round !== null || waitingNext) && (
            <p className="sub">{t('stage.watching')}</p>
          )}
          {screen === 'game' && soundBlocked && (
            <button type="button" className="btn primary" onClick={onUnlockSound}>
              {t('stage.unlockSound')}
            </button>
          )}
          {(canSkip || pickShown) && (
            <div className="row">
              {canSkip && (
                <button type="button" className={skipVoted ? 'btn voted' : 'btn'} onClick={onSkip}>
                  {skipVoted ? t('stage.skipCancel') : t('stage.skip')}
                </button>
              )}
              {canSkip && skipCount !== null && (
                <span className="skipcount">{t('stage.skipCount', { votes: skipCount.votes, needed: skipCount.needed })}</span>
              )}
              {pickShown && (
                <span className="skipcount pickcount">
                  {t('stage.pickCount', { picked: pickCount.picked, total: pickCount.total })}
                </span>
              )}
            </div>
          )}
          {error !== null && <p className="warn">{messageText(t, error)}</p>}
        </div>
        {gridShown && (
          <ChoiceGrid
            choices={choices}
            revealed={reveal !== null}
            picked={picked}
            pickWrong={pickWrong}
            correctChoice={correctChoice}
            enabled={canPick}
            onPick={onPick}
          />
        )}
      </div>

      <div className="seats">
        <h2>{t('seat.list')}</h2>
        <Seats
          players={players}
          me={me}
          scoreboard={scoreboard}
          correct={correctNames.length > 0 ? correctNames : correct}
        />
      </div>
      <ScoreLists score={score} scoreboard={scoreboard} me={me} />
    </Frame>
  )
}

export default GameScreen
