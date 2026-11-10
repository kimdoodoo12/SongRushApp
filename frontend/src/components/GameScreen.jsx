import { Fragment, useEffect, useLayoutEffect, useRef, useState } from 'react'
import { settingsLine } from '../game/filters.js'
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

// The anime's cover at the top of the reveal card (cycle 097), kept in the
// card until cycle 111 moves it into the deck's frame. It is decorative (the
// title is on the card), so alt is empty. With no cover, or after the image
// fails, it is the empty placeholder. The caller keys this component by
// coverUrl, so a failure is forgotten when the cover changes.
function RevealCover({ coverUrl }) {
  const [failed, setFailed] = useState(false)
  const showing = coverUrl !== null && !failed
  return (
    <div className={`reveal-cover mx-auto mb-3 h-32 w-24${showing ? '' : ' reveal-cover-empty'}`}>
      {showing && (
        <img
          src={coverUrl}
          alt=""
          draggable={false}
          className="h-full w-full object-contain"
          onError={() => setFailed(true)}
        />
      )}
    </div>
  )
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
      <RevealCover key={reveal.coverUrl ?? 'none'} coverUrl={reveal.coverUrl} />
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

// The chat log: one .msg per line, the name colour as its <i> and the line
// as .t; the own lines are .mine. A system line (D-094) is .msg.sys.correct.
function ChatLog({ chat, me }) {
  const { t } = useTranslation()
  const ref = useRef(null)
  const stickRef = useRef(true)
  const last = chat.length === 0 ? null : chat[chat.length - 1]
  const lastMine = last !== null && last.kind !== 'system' && last.name === me
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
      {chat.map((line) =>
        line.kind === 'system' ? (
          <div key={line.id} className="msg sys correct">
            {typeof line.rank === 'number'
              ? `${CHECK} ${t('chat.correctRanked', { name: line.winner, rank: line.rank })}`
              : `${CHECK} ${t('chat.correctLine', { name: line.winner })}`}
          </div>
        ) : (
          <div key={line.id} className={line.name === me ? 'msg mine' : 'msg'}>
            <i style={{ background: typeof line.name === 'string' ? nameColour(line.name) : 'currentColor' }} />
            <span className="t">{line.text}</span>
          </div>
        ),
      )}
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

// The game room in the mockup's markup (D-124, cycle 110): header, then
// .main with the stage and the chat. banner is the reconnecting line and
// footer the page footer, both from App. A room shows its code and the
// mood picker (enabled for the host); before joined (room null) neither.
function GameScreen({
  state,
  theme,
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
  // The song is audible (D-101, 087): a round is current and not revealed,
  // started, the local clock past its startAt, loaded, and the sound not
  // blocked. body.playing then runs the disc and the logo bars (the
  // mockup's CSS).
  const songCurrent = screen === 'game' && round !== null && reveal === null
  const audible = songCurrent && started && reached && load !== 'loading' && load !== 'failed' && !soundBlocked
  // A choice room's tiles (cycle 100), in the panel until cycle 112: shown
  // from prepare to the next prepare, so also under the reveal card. They
  // work while the song is audible (087 reached), for a player who has not
  // picked, until the first correct answer. In ranked correct stays null
  // (cycle 101, R4), so only the own pick locks them.
  const gridShown = screen === 'game' && choices !== null
  const canPick = started && reached && !watching && !pickLocked && picked === null && correct === null && reveal === null
  const seconds = timer === null ? null : Math.ceil(timer.remaining / 1000)
  const loadText = load !== null && LOAD_KEY[load] !== undefined ? t(LOAD_KEY[load]) : null
  const last = round !== null && round.index + 1 >= round.total
  // The reveal has no winner: the deck's stamp reads 통과 (shown from 111).
  const missed =
    reveal !== null &&
    (Array.isArray(reveal.ranking) ? reveal.ranking.length === 0 : reveal.winner === null || reveal.winner === undefined)

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
    else hint = playing ? t('stage.hint') : t('stage.soon')
    const subs = []
    if (round !== null && loadText !== null) subs.push(loadText)
    if (round !== null && correct === null) subs.push(playing ? t('stage.sub') : t('stage.soonSub'))
    panel = (
      <>
        <p className="hint">{hint}</p>
        {subs.map((text) => (
          <p key={text} className="sub">
            {text}
          </p>
        ))}
      </>
    )
  }

  return (
    <>
      <div className="scan" aria-hidden="true" />
      <div className="app">
        <header>
          <div className="logo">
            <span className="eq" aria-hidden="true">
              <i />
              <i />
              <i />
            </span>
            SongRush
          </div>
          <div className="roominfo">
            {room !== null && <span className="code">{room.code}</span>}
            {line !== '' && <p className="settings">{line}</p>}
          </div>
          <div className="hd-right">
            <VolumeControl volume={volume} onLevel={onVolumeLevel} onToggleMute={onToggleMute} />
          </div>
          {room !== null && (
            <div className="moodbar">
              <span className="ml">{isHost ? t('theme.label') : t('theme.labelGuest')}</span>
              <ThemePicker value={theme} onPick={onPickTheme} disabled={!isHost} />
            </div>
          )}
        </header>

        <div className="main">
          <section className="stage" aria-label={t('stage.label')}>
            <div className="inner">
              {banner}
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
                <Deck idle={!songCurrent && reveal === null} missed={missed} />
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
                  {canSkip && (
                    <div className="row">
                      <button type="button" className={skipVoted ? 'btn voted' : 'btn'} onClick={onSkip}>
                        {skipVoted ? t('stage.skipCancel') : t('stage.skip')}
                      </button>
                      {skipCount !== null && (
                        <span className="skipcount">{t('stage.skipCount', { votes: skipCount.votes, needed: skipCount.needed })}</span>
                      )}
                    </div>
                  )}
                  {gridShown && (
                    <ChoiceGrid
                      choices={choices}
                      picked={picked}
                      pickWrong={pickWrong}
                      correctChoice={correctChoice}
                      enabled={canPick}
                      onPick={onPick}
                    />
                  )}
                  {error !== null && <p className="warn">{messageText(t, error)}</p>}
                </div>
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
              {footer}
            </div>
          </section>

          <aside className="chat" aria-label={t('chat.label')}>
            <div className="chat-hd">
              <b>{t('chat.label')}</b>
              <span className="live">
                <span className="dot" />
                {t('chat.live', { n: online })}
              </span>
            </div>
            <ChatLog chat={chat} me={me} />
            {screen === 'game' && <ChatForm onSend={onChat} />}
          </aside>
        </div>
      </div>
    </>
  )
}

export default GameScreen
